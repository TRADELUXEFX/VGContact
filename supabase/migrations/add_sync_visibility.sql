-- add_sync_visibility.sql
-- Sync visibility + count-based reminders. Run in Supabase > SQL Editor (whole file). Safe to run more than once.
-- Run it BEFORE installing the new app build (an app that calls a function that does not exist yet
-- just fails quietly, but nothing is recorded until this has run).
--
-- What it does, in plain words:
--   1. users gets: device_brand, device_model, android_sdk, app_version, last_bg_sync_at, last_list_count.
--   2. report_sync_device(...): the app calls it after every successful sync. It saves the phone brand,
--      model, Android version and app version, stamps last_bg_sync_at when the sync was automatic
--      (background), and stores how many contacts the user's list had (last_list_count). The count is
--      computed here with the same function the sync uses, so later comparisons are like for like.
--   3. admin_unsynced_users(days): users with no successful sync for N days (default 3), with brand.
--   4. admin_sync_by_brand(days): one row per phone brand: how many users, how many not synced, and how
--      many background syncs actually work. This is the table that shows which brands fail.
--   5. send_sync_reminders(): visible push reminders with a count, on day 1, 3 and 7 after the user's
--      last sync (30h / 72h / 168h, because Android may run a daily job late). It only sends when the
--      user's list has grown since their last sync, so nobody is nagged for nothing.
--        day 1 -> action 'sync_waiting'  ("N new viewers waiting", tap opens the app, the app syncs)
--        day 3 and 7 -> action 'fix_sync' (same text plus "check background is allowed"; opens the
--                       existing fix dialog with Fix now / Sync now)
--      After the 3rd reminder it stops until the user syncs again (a sync resets the count).
--
-- NOTE: send_sync_reminders() REPLACES the older send_stalled_sync_nudge() (add_stalled_sync_nudge.sql),
-- which was never scheduled. Do NOT schedule both: users would get two messages about the same thing.

-- 1 -------------------------------------------------------------------------------
alter table public.users
  add column if not exists device_brand text,
  add column if not exists device_model text,
  add column if not exists android_sdk integer,
  add column if not exists app_version integer,
  add column if not exists last_bg_sync_at timestamptz,
  add column if not exists last_list_count integer;

-- 2 -------------------------------------------------------------------------------
create or replace function public.report_sync_device(
  p_user_id uuid, p_secret text,
  p_brand text, p_model text, p_sdk integer, p_app_version integer,
  p_background boolean default false)
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
         last_list_count = (select count(*) from public.get_sync_contacts(p_user_id))
   where u.id = p_user_id;
end;
$fn$;

revoke all on function public.report_sync_device(uuid, text, text, text, integer, integer, boolean) from public;
grant execute on function public.report_sync_device(uuid, text, text, text, integer, integer, boolean) to anon, authenticated;

-- 3 -------------------------------------------------------------------------------
create or replace function public.admin_unsynced_users(p_days integer default 3)
 returns table(user_id uuid, username text, phone text, device_brand text, device_model text,
               android_sdk integer, app_version integer,
               last_synced_at timestamp with time zone, last_bg_sync_at timestamp with time zone,
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
         u.last_synced_at, u.last_bg_sync_at,
         floor(extract(epoch from (now() - u.last_synced_at)) / 86400)::int,
         (u.fcm_token is not null),
         u.sync_reported
    from users u
   where coalesce(u.is_banned, false) = false
     and u.last_synced_at < now() - make_interval(days => greatest(coalesce(p_days, 3), 1))
   order by u.last_synced_at asc;
end;
$fn$;

-- 4 -------------------------------------------------------------------------------
create or replace function public.admin_sync_by_brand(p_days integer default 3)
 returns table(brand text, total integer, not_synced integer, bg_ok_2d integer, bg_never_2d_plus integer)
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
                             and u.created_at < now() - interval '2 days'))::int
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

-- 5 -------------------------------------------------------------------------------
create or replace function public.send_sync_reminders()
 returns integer
 language plpgsql
 security definer
 set search_path to 'public'
as $fn$
declare
  r record;
  v_waiting integer;
  v_sent integer;
  v_age_hours numeric;
  v_total integer := 0;
  v_word text;
begin
  for r in
    select u.id, u.last_synced_at, u.last_list_count
      from users u
     where u.fcm_token is not null
       and coalesce(u.is_banned, false) = false
       and u.sync_reported
       and u.last_list_count is not null
       and u.last_synced_at < now() - interval '30 hours'
  loop
    v_age_hours := extract(epoch from (now() - r.last_synced_at)) / 3600;

    -- Reminders already sent since the user's last sync.
    select count(*) into v_sent
      from notifications n
     where n.user_id = r.id
       and n.action in ('sync_waiting', 'fix_sync')
       and n.created_at > r.last_synced_at;

    -- Day 1 (30h), day 3 (72h), day 7 (168h): one reminder per stage, then stop.
    if not ((v_sent = 0 and v_age_hours >= 30)
         or (v_sent = 1 and v_age_hours >= 72)
         or (v_sent = 2 and v_age_hours >= 168)) then
      continue;
    end if;

    -- How many contacts the list has gained since the last sync (same function the sync uses).
    select count(*) into v_waiting from public.get_sync_contacts(r.id);
    v_waiting := v_waiting - r.last_list_count;
    if v_waiting <= 0 then continue; end if;

    v_word := case when v_waiting = 1 then 'viewer' else 'viewers' end;

    if v_sent = 0 then
      insert into notifications (user_id, title, body, action)
      values (r.id,
              'New viewers waiting',
              v_waiting || ' new ' || v_word || ' waiting to be saved on your phone. Tap to save.',
              'sync_waiting');
    else
      insert into notifications (user_id, title, body, action)
      values (r.id,
              'Viewers still not saved',
              v_waiting || ' new ' || v_word || ' still waiting. Tap to save, and check that VGContact is allowed to run in the background.',
              'fix_sync');
    end if;
    v_total := v_total + 1;
  end loop;

  return v_total;
end;
$fn$;

revoke all on function public.send_sync_reminders() from public, anon, authenticated;

-- Once a day at 09:00 UTC (10:00 Lagos), away from the 06:00, 08:00 and 16:00 UTC jobs.
select cron.unschedule('sync-reminders')
  where exists (select 1 from cron.job where jobname = 'sync-reminders');
select cron.schedule('sync-reminders', '0 9 * * *',
                     $$ select public.send_sync_reminders(); $$);

notify pgrst, 'reload schema';

-- CHECKS (run separately, they only read):
--   Brands and bg health:      select * from admin_sync_by_brand(3);      -- needs the admin login, returns 'not admin' in the SQL Editor
--   Dry run of the reminders:  select count(*) from users u where u.fcm_token is not null and u.sync_reported
--                                 and u.last_list_count is not null and u.last_synced_at < now() - interval '30 hours';
--   Send now (pushes at once): select send_sync_reminders();
