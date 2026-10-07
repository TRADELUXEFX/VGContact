-- add_system_contacts.sql
-- A list of extra "system" numbers that every user's phone saves on every sync, with a plain
-- name and NO "VGC<number>" ending, exactly like the admin and status numbers.
-- Manage the list with the small statements at the bottom (add / rename / switch off / remove).
--
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run. Safe to run more than once.
-- No app update needed (the app already saves rows with is_system = true under their plain name).
-- It only replaces the wrapper get_sync_contacts(uuid, text); the inner function is untouched.

-- 1. The list --------------------------------------------------------------------------------
create table if not exists public.system_contacts (
  id          uuid primary key default gen_random_uuid(),
  name        text,                              -- empty = use the default name (setting below)
  phone       text not null,
  sort_order  integer not null default 0,        -- lower number is saved first
  is_active   boolean not null default true,     -- false = no longer sent on new syncs
  created_at  timestamptz not null default now(),
  constraint system_contacts_phone_check
    check (length(regexp_replace(phone, '\D', '', 'g')) >= 10)
);

-- the same number can't be listed twice (compared by its last 9 digits)
create unique index if not exists system_contacts_phone_key
  on public.system_contacts (right(regexp_replace(phone, '\D', '', 'g'), 9));

alter table public.system_contacts enable row level security;     -- no policies: app can't read it
revoke all on public.system_contacts from anon, authenticated;

-- default name for rows whose name is empty
insert into public.app_settings (key, value)
values ('system_contact_default_name', 'VGContact')
on conflict (key) do nothing;

-- 2. What the app receives -------------------------------------------------------------------
-- Order: admin + status numbers, then this list (sort_order), then everything else as before.
-- A number that is both here and in someone's group/referral list is sent once, as a system
-- contact. A user never receives their own number.
drop function if exists public.get_sync_contacts(uuid, text);

create function public.get_sync_contacts(p_user_id uuid, p_secret text)
 returns table(phone text, display_name text, is_system boolean)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query
  with me as (
    select right(regexp_replace(coalesce(u.phone, ''), '\D', '', 'g'), 9) as k
      from users u where u.id = p_user_id
  ),
  base as (
    select f.phone as c_phone, f.display_name as c_name, f.ord,
           right(regexp_replace(coalesce(f.phone, ''), '\D', '', 'g'), 9) as k,
           exists (
             select 1 from app_settings s
              where s.key in ('admin_phone', 'anon_phone')
                and nullif(btrim(s.value), '') is not null
                and right(regexp_replace(s.value, '\D', '', 'g'), 9)
                  = right(regexp_replace(coalesce(f.phone, ''), '\D', '', 'g'), 9)
           ) as c_sys
      from public.get_sync_contacts(p_user_id) with ordinality as f(phone, display_name, ord)
  ),
  extra as (
    select sc.phone as c_phone,
           coalesce(nullif(btrim(sc.name), ''),
                    (select nullif(btrim(d.value), '') from app_settings d
                      where d.key = 'system_contact_default_name'),
                    'VGContact') as c_name,
           sc.sort_order, sc.created_at,
           right(regexp_replace(sc.phone, '\D', '', 'g'), 9) as k
      from system_contacts sc
     where sc.is_active
       and right(regexp_replace(sc.phone, '\D', '', 'g'), 9) <> (select me.k from me)
       and not exists (select 1 from base b where b.c_sys and b.k =
                       right(regexp_replace(sc.phone, '\D', '', 'g'), 9))
  )
  select r.c_phone, r.c_name, r.c_sys
    from (
      select b.c_phone, b.c_name, b.c_sys,
             case when b.c_sys then 0 else 2 end as grp, 0 as srt, b.ord as ord2, null::timestamptz as ca
        from base b
       where b.c_sys or not exists (select 1 from extra e where e.k = b.k)
      union all
      select e.c_phone, e.c_name, true, 1, e.sort_order, 0::bigint, e.created_at
        from extra e
    ) r
   order by r.grp, r.srt, r.ca, r.ord2;
end;
$function$;

revoke all on function public.get_sync_contacts(uuid, text) from public;
grant execute on function public.get_sync_contacts(uuid, text) to anon, authenticated;

notify pgrst, 'reload schema';

-- 3. MANAGE THE LIST (run these one at a time, whenever you want) -----------------------------
-- Add a person (own name):
--   insert into public.system_contacts (name, phone, sort_order) values ('Coach Ada', '08012345678', 1);
-- Add a person with the default name:
--   insert into public.system_contacts (phone) values ('08098765432');
-- Change the default name:
--   update public.app_settings set value = 'VGContact Team' where key = 'system_contact_default_name';
-- Rename:
--   update public.system_contacts set name = 'New name' where phone = '08012345678';
-- Stop sending (keeps the row):
--   update public.system_contacts set is_active = false where phone = '08012345678';
-- Remove for good:
--   delete from public.system_contacts where phone = '08012345678';
-- See the list:
--   select name, phone, sort_order, is_active from public.system_contacts order by sort_order, created_at;
-- NOTE: removing or switching off a number stops it being sent, but phones that already saved it
-- keep it (the app never deletes contacts that have no VGC tag). To delete a number from phones too,
-- use add_remove_numbers.sql (admin_add_removal).

-- 4. CHECK: the function was created and the list is readable (run after the file ran).
select p.proname, pg_get_function_identity_arguments(p.oid) as args,
       has_function_privilege('anon', p.oid, 'execute') as anon_can_run
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname = 'get_sync_contacts';
select name, phone, sort_order, is_active from public.system_contacts order by sort_order, created_at;
