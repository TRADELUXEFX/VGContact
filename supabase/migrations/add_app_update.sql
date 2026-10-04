-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- App update pop-up + download page, the VGKontact way:
--   every build is logged automatically (unpublished), and you PICK which one to publish
--   from the admin page's Updates tab. Nothing to type.
-- Run once in Supabase -> SQL Editor (needs add_app_settings.sql first).
-- Safe to run again, and it replaces the earlier version of this file.

-- 1. One row per build. The GitHub build adds the row itself (see deploy.yml step
--    "Log build for the update pop-up"). approved = true means "this is the update users get".
create table if not exists app_releases (
  version_code  int primary key,                 -- the GitHub run number, e.g. 954
  version_name  text not null,                   -- e.g. 1.0.954
  download_url  text not null,                   -- direct link to the APK
  changelog     text not null default '',        -- "What's new" shown to users
  approved      boolean not null default false,
  force_update  boolean not null default false,  -- users below this build can't use the app
  created_at    timestamptz not null default now(),
  published_at  timestamptz
);
alter table app_releases enable row level security;   -- no policies: only the functions below can read it

-- 2. Used by the app and the download page. Returns one row.
--    The latest published build is the update. A forced build makes every older build update.
--    "Update now" opens update_url (your download page, set once in the Updates tab), or the APK
--    link itself when no page link is set. Nothing is reported unless a link exists.
drop function if exists get_app_update(int);
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
      coalesce((select max(r.version_code) from app_releases r where r.approved), 0) as latest,
      coalesce((select max(r.version_code) from app_releases r where r.approved and r.force_update), 0) as minb,
      coalesce((select r.download_url from app_releases r where r.approved order by r.version_code desc limit 1), '') as apk,
      coalesce((select r.changelog from app_releases r where r.approved order by r.version_code desc limit 1), '') as notes,
      coalesce((select trim(a.value) from app_settings a where a.key = 'update_url'), '') as page
  ),
  l as (select *, coalesce(nullif(page, ''), apk) as link from s)
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

-- 3. Admin page: list the builds (newest first).
create or replace function admin_list_releases()
returns table (
  version_code int, version_name text, download_url text, changelog text,
  approved boolean, force_update boolean, created_at timestamptz, published_at timestamptz
)
language plpgsql
security definer
set search_path = public
as $$
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  return query
    select r.version_code, r.version_name, r.download_url, r.changelog,
           r.approved, r.force_update, r.created_at, r.published_at
    from app_releases r
    order by r.version_code desc
    limit 30;
end $$;
revoke all on function admin_list_releases() from public;
grant execute on function admin_list_releases() to authenticated;

-- 4. Admin page: publish or unpublish a build you picked.
create or replace function admin_set_release(
  p_version_code int, p_approved boolean, p_force boolean, p_changelog text
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  update app_releases
     set approved     = p_approved,
         force_update = case when p_approved then coalesce(p_force, false) else false end,
         changelog    = coalesce(p_changelog, changelog),
         published_at = case when p_approved then now() else published_at end
   where version_code = p_version_code;
  if not found then raise exception 'release not found'; end if;
end $$;
revoke all on function admin_set_release(int, boolean, boolean, text) from public;
grant execute on function admin_set_release(int, boolean, boolean, text) to authenticated;

-- 5. The download page link ("Update now" opens it). Set it once in the Updates tab.
insert into app_settings (key, value) values ('update_url', '')
on conflict (key) do nothing;
