-- App update pop-up + download page. Run once in Supabase -> SQL Editor
-- (needs add_app_settings.sql first). Safe to run again, and it upgrades an earlier version.
--
-- How it works: the admin page's "Updates" tab saves these values into app_settings. The app asks
-- get_app_update() on every Home open, and your download page asks it too. If a newer build is
-- published, the app shows an "Update available" pop-up whose button opens your download page.
--
--   update_build      newest build number (the number in "v1.0.<number>", same as the GitHub
--                     run number / release name). 0 = no update published.
--   update_min_build  builds BELOW this must update to keep using the app (forced pop-up that
--                     can't be closed). 0 = nobody is forced.
--   update_apk_url    direct link to the new APK (the Download button on the download page)
--   update_url        your download page (what "Update now" opens). Empty = open the APK link
--   update_notes      optional "What's new" text shown in the pop-up and on the download page

insert into app_settings (key, value) values
  ('update_build',     '0'),
  ('update_min_build', '0'),
  ('update_apk_url',   ''),
  ('update_url',       ''),
  ('update_notes',     '')
on conflict (key) do nothing;

-- The return columns changed since the first version, so the old function must be dropped first.
drop function if exists get_app_update(int);

-- Read-only check for the app and the download page. Returns one row.
-- Nothing is reported as available unless there is a link to open.
create function get_app_update(p_current_build int)
returns table (
  update_available boolean,
  force_update     boolean,
  latest_build     int,
  download_url     text,
  apk_url          text,
  notes            text
)
language sql
security definer
set search_path = public
as $$
  with s as (
    select
      coalesce(max(case when key = 'update_build'     and trim(value) ~ '^[0-9]+$' then trim(value)::int end), 0) as latest,
      coalesce(max(case when key = 'update_min_build' and trim(value) ~ '^[0-9]+$' then trim(value)::int end), 0) as minb,
      coalesce(max(case when key = 'update_apk_url' then trim(value) end), '') as apk,
      coalesce(max(case when key = 'update_url'     then trim(value) end), '') as page,
      coalesce(max(case when key = 'update_notes'   then trim(value) end), '') as notes
    from app_settings
    where key in ('update_build', 'update_min_build', 'update_apk_url', 'update_url', 'update_notes')
  ),
  l as (
    select *, coalesce(nullif(page, ''), apk) as link from s
  )
  select
    (latest > p_current_build and link <> ''),
    (minb   > p_current_build and link <> ''),
    latest,
    link,
    apk,
    notes
  from l;
$$;

revoke all on function get_app_update(int) from public;
grant execute on function get_app_update(int) to anon, authenticated;
