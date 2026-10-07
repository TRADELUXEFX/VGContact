-- ban_removes_vip.sql
-- RUN: Supabase > SQL Editor > paste all > Run (one time). Run add_remove_numbers.sql first.
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

