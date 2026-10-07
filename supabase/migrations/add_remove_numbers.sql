-- add_remove_numbers.sql
-- "Delete from phones": the admin types a phone number and every phone deletes any saved contact
-- with that number on its next sync, whatever the contact is named (VIP / system numbers have no
-- VGC tag, so the normal clean-up never touches them).
--
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run. Safe to run more than once.
-- Needs an app update (the app has to ask for the list). Admin page: add a small box that calls
-- admin_add_removal(phone) / admin_list_removals() / admin_cancel_removal(id).

-- 1. The list --------------------------------------------------------------------------------
create table if not exists public.contact_removals (
  id          uuid primary key default gen_random_uuid(),
  phone       text not null,
  created_at  timestamptz not null default now(),
  constraint contact_removals_phone_check
    check (length(regexp_replace(phone, '\D', '', 'g')) >= 10)
);

-- the same number can't be listed twice (compared by its last 9 digits)
create unique index if not exists contact_removals_phone_key
  on public.contact_removals (right(regexp_replace(phone, '\D', '', 'g'), 9));

alter table public.contact_removals enable row level security;     -- no policies: app can't read it
revoke all on public.contact_removals from anon, authenticated;

-- 2. What the app asks for -------------------------------------------------------------------
-- Numbers to delete from this phone. Every number on the list is returned to every phone, no
-- exceptions. The app also stops saving these numbers again.
create or replace function public.get_removed_numbers(p_user_id uuid, p_secret text)
 returns table(phone text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select r.phone from public.contact_removals r;
end;
$function$;

revoke all on function public.get_removed_numbers(uuid, text) from public;
grant execute on function public.get_removed_numbers(uuid, text) to anon, authenticated;

-- 3. Admin ------------------------------------------------------------------------------------
create or replace function public.admin_add_removal(p_phone text)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
  v_phone text := regexp_replace(btrim(coalesce(p_phone, '')), '[\s\-()]', '', 'g');
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  if length(regexp_replace(v_phone, '\D', '', 'g')) < 10 then
    raise exception 'Enter a valid phone number (at least 10 digits)';
  end if;
  -- also switch the number off in the system list so it is not sent again
  update public.system_contacts set is_active = false
   where right(regexp_replace(phone, '\D', '', 'g'), 9)
       = right(regexp_replace(v_phone, '\D', '', 'g'), 9);
  begin
    insert into public.contact_removals (phone) values (v_phone);
  exception when unique_violation then
    null; -- already listed
  end;
end;
$function$;

create or replace function public.admin_list_removals()
 returns table(id uuid, phone text, created_at timestamp with time zone)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  return query select r.id, r.phone, r.created_at from public.contact_removals r order by r.created_at desc;
end;
$function$;

create or replace function public.admin_cancel_removal(p_id uuid)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  delete from public.contact_removals where id = p_id;
end;
$function$;

revoke all on function public.admin_add_removal(text) from public, anon;
revoke all on function public.admin_list_removals() from public, anon;
revoke all on function public.admin_cancel_removal(uuid) from public, anon;
grant execute on function public.admin_add_removal(text) to authenticated;
grant execute on function public.admin_list_removals() to authenticated;
grant execute on function public.admin_cancel_removal(uuid) to authenticated;

-- 4. A banned VIP is removed from all phones -------------------------------------------------
-- VIPs do not need a group (everyone already receives them). When a user is banned and their
-- number is in the VIP / extra contacts list, the number is switched off there and put on the
-- delete list. Every phone deletes it on its next sync.
create or replace function public._on_user_banned()
 returns trigger
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
  v_key text := right(regexp_replace(coalesce(new.phone, ''), '\D', '', 'g'), 9);
begin
  if length(v_key) < 9 then return new; end if;
  if not exists (select 1 from public.system_contacts sc
                  where right(regexp_replace(sc.phone, '\D', '', 'g'), 9) = v_key) then
    return new;
  end if;
  update public.system_contacts set is_active = false
   where right(regexp_replace(phone, '\D', '', 'g'), 9) = v_key;
  begin
    insert into public.contact_removals (phone) values (new.phone);
  exception when unique_violation then
    null;
  end;
  return new;
end;
$function$;

drop trigger if exists trg_group_member_removed on public.contact_group_members;
drop function if exists public._on_group_member_removed();
drop trigger if exists trg_user_banned on public.users;
create trigger trg_user_banned
  after update of is_banned on public.users
  for each row
  when (new.is_banned is true and old.is_banned is distinct from true)
  execute function public._on_user_banned();

notify pgrst, 'reload schema';

-- USE: select public.admin_add_removal('08012345678');   (or the admin page)
-- Keep the row for a few weeks so phones that sync late still delete it, then cancel it.

-- CHECK
select p.proname, has_function_privilege('anon', p.oid, 'execute') as anon_can_run
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname in ('get_removed_numbers','admin_add_removal','admin_list_removals','admin_cancel_removal')
 order by p.proname;
