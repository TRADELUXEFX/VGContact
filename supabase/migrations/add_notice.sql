-- add_notice.sql  (updated)
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run (one time). Safe to run twice.
-- "Notice to users": the admin writes a message (Settings > Maintenance) and every user sees
-- it once as a pop-up when they open the app. Empty = no notice.
-- While maintenance mode (sync_paused) is ON and no message is written, users see a default one.
-- Needs the app update (versionCode 82). Nothing existing is changed.

insert into public.app_settings (key, value) values ('maintenance_message', ' ')
on conflict (key) do nothing;

create or replace function public.get_notice()
 returns table(message text)
 language sql
 stable
 security definer
 set search_path to 'public'
as $function$
  select q.m
    from (
      select coalesce(
               (select nullif(btrim(s.value), '') from app_settings s where s.key = 'maintenance_message'),
               case when exists (select 1 from app_settings p
                                  where p.key = 'sync_paused' and lower(btrim(p.value)) = 'true')
                    then 'We are doing maintenance. Your contacts will update again when it is finished.'
               end
             ) as m
    ) q
   where q.m is not null;
$function$;

revoke all on function public.get_notice() from public;
grant execute on function public.get_notice() to anon, authenticated;

notify pgrst, 'reload schema';

-- CHECK: one row, value is blank (no notice yet)
select key, '[' || value || ']' as value from public.app_settings where key = 'maintenance_message';
