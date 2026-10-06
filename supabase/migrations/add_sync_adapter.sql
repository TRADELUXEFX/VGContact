-- add_sync_adapter.sql
-- Sync adapter (the "VGContact" account that gives the contact sync a second, Android-run trigger).
-- Run in Supabase > SQL Editor (whole file) AFTER add_sync_visibility.sql. Safe to run more than once.
-- Run it BEFORE installing the app build that has the sync adapter (versionCode 80).
--
-- What it does, in plain words:
--   1. app_settings 'sync_adapter_enabled' = 'false'. This is the admin switch (Settings tab > Background
--      sync). OFF = no phone has the "VGContact" account. ON = phones create it the next time the app is
--      opened. Turning it OFF again removes the account on each phone the next time the app is opened or
--      the account wakes up. It starts OFF.
--   2. get_sync_adapter_enabled(user, secret): the app asks for that switch.
--   3. users.last_adapter_sync_at: stamped when a sync was started by the account (not by the app's own
--      background job). This is how you see whether the adapter really runs on Tecno / Infinix / itel.
--   4. report_sync_device gets a new optional parameter p_source ('adapter', 'worker' or empty).
--      The older 7-argument version is dropped so there is only one version.
--   5. admin_sync_by_brand and admin_unsynced_users get the adapter columns (the Sync tab shows them).
--      They are dropped and re-created because their result columns change.

-- 1 -------------------------------------------------------------------------------
insert into public.app_settings (key, value)
values ('sync_adapter_enabled', 'false')
on conflict (key) do nothing;

-- 2 -------------------------------------------------------------------------------
create or replace function public.get_sync_adapter_enabled(p_user_id uuid, p_secret text)
 returns table(enabled boolean)
 language plpgsql
 security definer
 set search_path to 'public'
as $fn$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query
  select coalesce(
    (select lower(btrim(s.value)) = 'true' from app_settings s where s.key = 'sync_adapter_enabled'),
    false);
end;
$fn$;

revoke all on function public.get_sync_adapter_enabled(uuid, text) from public;
grant execute on function public.get_sync_adapter_enabled(uuid, text) to anon, authenticated;

-- 3 -------------------------------------------------------------------------------
alter table public.users add column if not exists last_adapter_sync_at timestamptz;

-- 4 -------------------------------------------------------------------------------
drop function if exists public.report_sync_device(uuid, text, text, text, integer, integer, boolean);

create or replace function public.report_sync_device(
  p_user_id uuid, p_secret text,
  p_brand text, p_model text, p_sdk integer, p_app_version integer,
  p_background boolean default false,
  p_source text default null)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $fn$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  update users u
     set device_brand    = left(lower(nullif(btrim(coalesce(p_brand, '')), '')), 40),
         device_model    = left(nullif(btrim(coalesce(p_model, '')), ''), 60),
         android_sdk     = p_sdk,
         app_version     = p_app_version,
         last_bg_sync_at = case when coalesce(p_background, false) then now() else u.last_bg_sync_at end,
         last_adapter_sync_at = case when p_source = 'adapter' then now() else u.last_adapter_sync_at end,
         last_list_count = (select count(*) from public.get_sync_contacts(p_user_id))
   where u.id = p_user_id;
end;
$fn$;

revoke all on function public.report_sync_device(uuid, text, text, text, integer, integer, boolean, text) from public;
grant execute on function public.report_sync_device(uuid, text, text, text, integer, integer, boolean, text) to anon, authenticated;

-- 5 -------------------------------------------------------------------------------
drop function if exists public.admin_unsynced_users(integer);
drop function if exists public.admin_sync_by_brand(integer);

create or replace function public.admin_unsynced_users(p_days integer default 3)
 returns table(user_id uuid, username text, phone text, device_brand text, device_model text,
               android_sdk integer, app_version integer,
               last_synced_at timestamp with time zone, last_bg_sync_at timestamp with time zone,
               last_adapter_sync_at timestamp with time zone,
               days_since_sync integer, has_push boolean, ever_reported boolean)
 language plpgsql
 security definer
 set search_path to 'public'
as $fn$
#variable_conflict use_column
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  return query
  select u.id, u.username, u.phone, u.device_brand, u.device_model,
         u.android_sdk, u.app_version,
         u.last_synced_at, u.last_bg_sync_at, u.last_adapter_sync_at,
         floor(extract(epoch from (now() - u.last_synced_at)) / 86400)::int,
         (u.fcm_token is not null),
         u.sync_reported
    from users u
   where coalesce(u.is_banned, false) = false
     and u.last_synced_at < now() - make_interval(days => greatest(coalesce(p_days, 3), 1))
   order by u.last_synced_at asc;
end;
$fn$;

create or replace function public.admin_sync_by_brand(p_days integer default 3)
 returns table(brand text, total integer, not_synced integer, bg_ok_2d integer,
               bg_never_2d_plus integer, adapter_ok_2d integer)
 language plpgsql
 security definer
 set search_path to 'public'
as $fn$
#variable_conflict use_column
declare
  d integer := greatest(coalesce(p_days, 3), 1);
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  return query
  select coalesce(u.device_brand, 'unknown'),
         count(*)::int,
         (count(*) filter (where u.last_synced_at < now() - make_interval(days => d)))::int,
         (count(*) filter (where u.last_bg_sync_at >= now() - interval '2 days'))::int,
         (count(*) filter (where u.last_bg_sync_at is null
                             and u.created_at < now() - interval '2 days'))::int,
         (count(*) filter (where u.last_adapter_sync_at >= now() - interval '2 days'))::int
    from users u
   where coalesce(u.is_banned, false) = false
   group by coalesce(u.device_brand, 'unknown')
   order by 3 desc, 2 desc;
end;
$fn$;

revoke all on function public.admin_unsynced_users(integer) from public, anon;
revoke all on function public.admin_sync_by_brand(integer) from public, anon;
grant execute on function public.admin_unsynced_users(integer) to authenticated;
grant execute on function public.admin_sync_by_brand(integer) to authenticated;

notify pgrst, 'reload schema';

-- CHECK (reads only): the switch exists and is OFF, and only one report_sync_device version is left.
select s.key, s.value from app_settings s where s.key = 'sync_adapter_enabled';
select p.oid::regprocedure as report_sync_device_versions
  from pg_proc p where p.proname = 'report_sync_device' and p.pronamespace = 'public'::regnamespace;
