-- add_notice.sql
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run (one time). Safe to run twice.
-- "Notice to users": the admin writes a message (Settings > Notice to users) and every user sees
-- it once as a pop-up when they open the app. Empty = no notice.
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
  select nullif(btrim(s.value), '')
    from app_settings s
   where s.key = 'maintenance_message'
     and nullif(btrim(s.value), '') is not null;
$function$;

revoke all on function public.get_notice() from public;
grant execute on function public.get_notice() to anon, authenticated;

notify pgrst, 'reload schema';

-- CHECK: one row, value is blank (no notice yet)
select key, '[' || value || ']' as value from public.app_settings where key = 'maintenance_message';
