-- VGContact: "your sync stopped" push nudge. Paste into the Supabase SQL Editor and run.
-- Safe to run more than once. Run AFTER add_inactivity.sql and
-- add_notification_actions_and_triggers.sql.
--
-- What it does:
--   * users.sync_reported: set to true the first time the app calls record_sync.
--     Only those users are ever nudged, so a phone that never reports syncs
--     (very old build) is not told its sync stopped when it did not.
--   * users.last_sync_nudge_at: when this user was last nudged.
--   * record_sync(user): same as before, and also sets sync_reported = true.
--   * send_stalled_sync_nudge(): inserts ONE notification (action 'fix_sync') for every
--     user whose last successful sync is older than app_settings 'sync_nudge_after_hours'
--     (default 48), but not older than 14 days. The existing trigger then sends the push.
--       - Once per stall: not again until the user has synced, or 7 days have passed.
--       - Skips banned users and users with no push token.
--   * Returns how many nudges were created.
--
-- Change the wait without a new APK:
--   insert into app_settings (key, value) values ('sync_nudge_after_hours', '72')
--   on conflict (key) do update set value = excluded.value;

alter table public.users add column if not exists sync_reported boolean not null default false;
alter table public.users add column if not exists last_sync_nudge_at timestamptz;

create or replace function public.record_sync(p_user_id uuid)
 returns void
 language sql
 security definer
 set search_path to 'public'
as $fn$
  update users set last_synced_at = now(), sync_reported = true where id = p_user_id;
$fn$;

create or replace function public.send_stalled_sync_nudge()
 returns integer
 language plpgsql
 security definer
 set search_path to 'public'
as $fn$
declare
  v_hours integer;
  v_count integer;
begin
  v_hours := coalesce(
    (select nullif(btrim(value), '')::int from app_settings where key = 'sync_nudge_after_hours'),
    48);

  with due as (
    select u.id
      from users u
     where u.fcm_token is not null
       and coalesce(u.is_banned, false) = false
       and u.sync_reported
       and u.last_synced_at < now() - make_interval(hours => v_hours)
       and u.last_synced_at > now() - interval '14 days'
       and (
             u.last_sync_nudge_at is null
          or u.last_sync_nudge_at < u.last_synced_at
          or u.last_sync_nudge_at < now() - interval '7 days'
       )
  ),
  marked as (
    update users set last_sync_nudge_at = now()
     where id in (select id from due)
    returning id
  ),
  inserted as (
    insert into notifications (user_id, title, body, action)
    select id,
           'Your contact sync stopped',
           'Your phone may be blocking VGContact in the background. Tap to fix it so new viewers keep reaching you.',
           'fix_sync'
      from marked
    returning 1
  )
  select count(*) into v_count from inserted;

  return coalesce(v_count, 0);
end;
$fn$;

revoke all on function public.send_stalled_sync_nudge() from public, anon, authenticated;
grant execute on function public.record_sync(uuid) to anon, authenticated;

-- Schedule it once a day. Needs pg_cron (Dashboard -> Database -> Extensions -> pg_cron).
-- pg_cron runs in UTC. 09:00 UTC = 10:00 Lagos (WAT). Uncomment after enabling pg_cron:
--
-- select cron.unschedule('stalled-sync-nudge')
--   where exists (select 1 from cron.job where jobname = 'stalled-sync-nudge');
-- select cron.schedule('stalled-sync-nudge', '0 9 * * *',
--                      $$ select send_stalled_sync_nudge(); $$);
--
-- Test it right now (returns the number of nudges created):
--   select send_stalled_sync_nudge();

notify pgrst, 'reload schema';
