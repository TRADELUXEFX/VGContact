-- VGContact: "your sync stopped" push nudge (simplified).
-- Paste into the Supabase SQL Editor and run. Safe to run more than once.
-- Run AFTER add_inactivity.sql, add_app_settings.sql and
-- add_notification_actions_and_triggers.sql.
--
-- What it does, in plain words:
--   * A user is nudged when their last successful sync is older than 48 hours
--     (app_settings 'sync_nudge_after_hours') and they are NOT yet inactive.
--     "Inactive" is the existing rule (30 days, app_settings 'inactive_after_days'),
--     so the nudges stop by themselves when the user is dropped from other
--     people's lists. Syncing again resets everything.
--   * At most one nudge every 7 days per user (checked in the notifications table).
--   * users.sync_reported: set to true the first time the app calls record_sync.
--     Only those users are nudged, so an old app build, or someone who signed up
--     and never synced, is never told their sync "stopped".
--   * The existing on_notification_created trigger sends the push.
--   * Skips banned users and users with no push token.
--
-- Change the wait without a new APK:
--   insert into app_settings (key, value) values ('sync_nudge_after_hours', '72')
--   on conflict (key) do update set value = excluded.value;

alter table public.users
  add column if not exists sync_reported boolean not null default false;

create or replace function public.record_sync(p_user_id uuid)
 returns void
 language sql
 security definer
 set search_path to 'public'
as $fn$
  update users
     set last_synced_at = now(), sync_reported = true
   where id = p_user_id;
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
    (select nullif(btrim(value), '')::int
       from app_settings where key = 'sync_nudge_after_hours'),
    48);

  insert into notifications (user_id, title, body, action)
  select u.id,
         'Your contact sync stopped',
         'Your phone may be blocking VGContact in the background. Tap to fix it so new viewers keep reaching you.',
         'fix_sync'
    from users u
   where u.fcm_token is not null
     and coalesce(u.is_banned, false) = false
     and u.sync_reported
     and u.last_synced_at < now() - make_interval(hours => v_hours)
     and not public._is_inactive(u.id)
     and not exists (
           select 1 from notifications n
            where n.user_id = u.id
              and n.action = 'fix_sync'
              and n.created_at > now() - interval '7 days'
     );

  get diagnostics v_count = row_count;
  return v_count;
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
