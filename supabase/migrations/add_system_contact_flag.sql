-- add_system_contact_flag.sql
-- The admin number and the status number are saved on the phone with their plain name
-- ("VGContact Admin", "VGContact Status"), with NO "VGC<number>" ending.
-- The server now tells the app which rows are those two numbers (is_system = true).
--
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run. Safe to run more than once.
-- Run it AFTER add_user_secret.sql (it replaces the secret version of get_sync_contacts).
-- Then install the new app build. An older app build keeps working (it ignores the new column).

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
  select f.phone, f.display_name,
         exists (
           select 1 from app_settings s
            where s.key in ('admin_phone', 'anon_phone')
              and nullif(btrim(s.value), '') is not null
              and right(regexp_replace(s.value, '\D', '', 'g'), 9)
                = right(regexp_replace(coalesce(f.phone, ''), '\D', '', 'g'), 9)
         )
    from public.get_sync_contacts(p_user_id) f;
end;
$function$;

revoke all on function public.get_sync_contacts(uuid, text) from public;
grant execute on function public.get_sync_contacts(uuid, text) to anon, authenticated;

notify pgrst, 'reload schema';

-- CHECK: shows what the app would receive for the first user. The admin and status rows
-- should say is_system = true, every other row false.
-- (Runs the inner function directly, so it does not need a secret.)
select f.phone, f.display_name,
       exists (
         select 1 from app_settings s
          where s.key in ('admin_phone', 'anon_phone')
            and nullif(btrim(s.value), '') is not null
            and right(regexp_replace(s.value, '\D', '', 'g'), 9)
              = right(regexp_replace(coalesce(f.phone, ''), '\D', '', 'g'), 9)
       ) as is_system
  from public.get_sync_contacts((select id from public.users order by created_at limit 1)) f;
