-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- App settings you can change without shipping a new APK.
-- Run once in Supabase -> SQL Editor. Safe to run again.
--
-- To change the community link later, run:
--   update app_settings set value = 'https://chat.whatsapp.com/NEWCODE'
--   where key = 'community_link';
-- (or edit the row in Table Editor -> app_settings).

create table if not exists app_settings (
  key   text primary key,
  value text not null
);

-- No policies: the anon key can't read or write the table directly.
alter table app_settings enable row level security;

insert into app_settings (key, value)
values ('community_link', 'https://chat.whatsapp.com/LuTImF6uCmnDexMnnIDkGo')
on conflict (key) do nothing;

-- Read-only lookup for the app. Returns a table so the app gets a JSON array.
create or replace function get_app_setting(p_key text)
returns table (value text)
language sql
security definer
set search_path = public
as $$
  select s.value from app_settings s where s.key = p_key;
$$;

revoke all on function get_app_setting(text) from public;
grant execute on function get_app_setting(text) to anon, authenticated;
