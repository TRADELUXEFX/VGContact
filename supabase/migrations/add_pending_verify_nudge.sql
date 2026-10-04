-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- VGContact: one-time push for users who signed up but are still not verified.
-- Run in Supabase > SQL Editor. Safe to run more than once. No app change needed for the push.
--
-- Pending users have not been verified yet, so no viewers reach them.
-- This pushes "Claim your free viewers" (opens the Repost screen) to a user who:
--   * has a push token, is not banned, and has NO verified repost,
--   * signed up more than 24 hours ago (app_settings 'pending_nudge_after_hours'),
--   * signed up less than 30 days ago (stops nagging old accounts),
--   * has not already received this nudge in the last 3 days.
-- The existing on_notification_created trigger sends the push.
-- This is separate from the daily "repost today" reminder (send_daily_repost_reminder).
--
-- Change the wait without a new APK:
--   insert into app_settings (key, value) values ('pending_nudge_after_hours', '48')
--   on conflict (key) do update set value = excluded.value;

create or replace function public.send_pending_verify_nudge()
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
       from app_settings where key = 'pending_nudge_after_hours'),
    24);

  insert into notifications (user_id, title, body, action)
  select u.id,
         'Claim your free viewers',
         'Repost the admin''s status to get verified and start receiving viewers.',
         'open_repost'
    from users u
   where u.fcm_token is not null
     and coalesce(u.is_banned, false) = false
     and not public._is_verified(u.id)
     and u.created_at < now() - make_interval(hours => v_hours)
     and u.created_at > now() - interval '30 days'
     and not exists (
           select 1 from notifications n
            where n.user_id = u.id
              and n.title = 'Claim your free viewers'
              and n.created_at > now() - interval '3 days'
     );

  get diagnostics v_count = row_count;
  return v_count;
end;
$fn$;

revoke all on function public.send_pending_verify_nudge() from public, anon, authenticated;

-- Once a day at 16:00 UTC (17:00 Lagos), away from the 08:00 UTC daily reminder.
-- Needs pg_cron (already used by daily-repost-reminder and catch-up-group-grants).
select cron.unschedule('pending-verify-nudge')
  where exists (select 1 from cron.job where jobname = 'pending-verify-nudge');
select cron.schedule('pending-verify-nudge', '0 16 * * *',
                     $$ select public.send_pending_verify_nudge(); $$);

-- Test it right now (returns the number of nudges created; they are pushed immediately):
--   select send_pending_verify_nudge();

notify pgrst, 'reload schema';
