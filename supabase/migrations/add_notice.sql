-- add_notice.sql  (updated: now also says whether Maintenance mode is on)
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run (one time). Safe to run twice.
-- Home shows an amber banner when the admin writes a notice (Settings > Maintenance) or turns
-- Maintenance mode on. While Maintenance mode is on and no notice is written, users see a default.
-- Needs the app update. Nothing existing is changed.

insert into public.app_settings (key, value) values ('maintenance_message', ' ')
on conflict (key) do nothing;

drop function if exists public.get_notice();

create function public.get_notice()
 returns table(message text, paused boolean)
 language sql
 stable
 security definer
 set search_path to 'public'
as $function$
  select coalesce(s.custom,
                  case when s.p then 'Contacts sync is paused. Your saved contacts are safe.' end) as m,
         s.p
    from (
      select (select nullif(btrim(a.value), '') from app_settings a where a.key = 'maintenance_message') as custom,
             exists (select 1 from app_settings b
                      where b.key = 'sync_paused' and lower(btrim(b.value)) = 'true') as p
    ) s
   where coalesce(s.custom, case when s.p then 'x' end) is not null;
$function$;

revoke all on function public.get_notice() from public;
grant execute on function public.get_notice() to anon, authenticated;

notify pgrst, 'reload schema';

-- CHECK: returns no row while there is no notice and Maintenance mode is off
select * from public.get_notice();
