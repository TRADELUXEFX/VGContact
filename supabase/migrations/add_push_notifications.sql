-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Adds push-notification support on top of the existing notifications table.
-- Run this once in the Supabase SQL editor (or via CLI migration).

-- 1. Where each device's current FCM token lives.
ALTER TABLE users ADD COLUMN IF NOT EXISTS fcm_token TEXT;

-- 2. Requires the pg_net extension (enable it once via
--    Database -> Extensions -> pg_net in the Supabase dashboard, or:
--    CREATE EXTENSION IF NOT EXISTS pg_net;
-- pg_net lets a Postgres trigger make an outbound HTTP call without
-- blocking the transaction that fired it.
CREATE EXTENSION IF NOT EXISTS pg_net;

-- 3. Trigger function: fires after a new notifications row is inserted,
--    calls the send-push Edge Function with that row's id. The function
--    itself does the actual FCM lookup + send (see
--    supabase/functions/send-push/index.ts) - this trigger's only job is
--    to kick it off.
--
--    project_ref and the service role key below are filled in per-project;
--    see the setup notes at the bottom of this file.
CREATE OR REPLACE FUNCTION trigger_send_push_notification()
RETURNS TRIGGER AS $$
BEGIN
  PERFORM net.http_post(
    url := 'https://<PROJECT_REF>.supabase.co/functions/v1/send-push',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer <SUPABASE_SERVICE_ROLE_KEY>'
    ),
    body := jsonb_build_object('notification_id', NEW.id)
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_notification_created ON notifications;
CREATE TRIGGER on_notification_created
  AFTER INSERT ON notifications
  FOR EACH ROW
  EXECUTE FUNCTION trigger_send_push_notification();

-- ---------------------------------------------------------------------
-- SETUP NOTES (one-time, per Supabase project)
--
-- 1. Enable pg_net:
--    Supabase Dashboard -> Database -> Extensions -> search "pg_net" -> Enable
--
-- 2. Replace <PROJECT_REF> above with your project ref (the subdomain in
--    your Supabase URL, e.g. "abcdefghijkl" from
--    https://abcdefghijkl.supabase.co).
--
-- 3. Replace <SUPABASE_SERVICE_ROLE_KEY> above with your project's service
--    role key (Dashboard -> Project Settings -> API -> service_role key).
--    This key bypasses RLS - it must ONLY appear here, server-side, inside
--    this trigger definition. Never put it in the Android app or commit it
--    to a public repo.
--
-- 4. Deploy the Edge Function itself (see supabase/functions/send-push/):
--      supabase functions deploy send-push
--
-- 5. Set the Edge Function's own secrets (the Firebase service account
--    credentials it needs to call FCM) - see the setup notes at the top
--    of supabase/functions/send-push/index.ts.
-- ---------------------------------------------------------------------
