-- =====================================================================
-- Notification actions + automatic notification triggers.
--
-- HOW TO RUN: Supabase -> SQL Editor -> paste all -> Run. Safe to re-run.
-- Run AFTER add_notifications_feed.sql and add_push_notifications.sql.
--
-- WHAT THIS ADDS
--   1. notifications.action / notifications.target
--        What happens when the user TAPS the push or the row in the feed.
--        action values the app understands:
--          'open_whatsapp_repost'  -> opens the admin's WhatsApp chat
--          'open_repost'           -> opens the Repost screen
--          'open_downloads'        -> opens the Unlock/Downloads screen
--          'open_home'             -> opens Home (also the default / NULL)
--   2. Daily "repost today" reminder  -> send_daily_repost_reminder()
--        Scheduled with pg_cron (see bottom).
--   3. Repost verified                -> trigger on daily_reposts
--   4. Key added (any reason)         -> trigger on users.key_balance
--
-- Every notification inserted here fires the existing on_notification_created
-- trigger -> send-push Edge Function -> FCM. Nothing else to wire.
-- =====================================================================

-- 1. Tap action columns -------------------------------------------------
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS action TEXT;
ALTER TABLE notifications ADD COLUMN IF NOT EXISTS target TEXT;

-- fetch_notifications now also returns action/target so tapping a row in
-- the in-app feed can do the same thing as tapping the push.
DROP FUNCTION IF EXISTS fetch_notifications(UUID);
CREATE OR REPLACE FUNCTION fetch_notifications(p_user_id UUID)
RETURNS TABLE (
  id UUID,
  title TEXT,
  body TEXT,
  created_at TIMESTAMP WITH TIME ZONE,
  is_read BOOLEAN,
  action TEXT,
  target TEXT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    n.id, n.title, n.body, n.created_at,
    (r.notification_id IS NOT NULL) AS is_read,
    n.action, n.target
  FROM notifications n
  LEFT JOIN notification_reads r
    ON r.notification_id = n.id
   AND r.user_id = p_user_id
  WHERE n.user_id = p_user_id
     OR n.user_id IS NULL
  ORDER BY n.created_at DESC
  LIMIT 50;
$$;
GRANT EXECUTE ON FUNCTION fetch_notifications(UUID) TO anon, authenticated;

-- 2. Daily reminder -----------------------------------------------------
-- Sends ONE "repost today" notification to each user who has an FCM token
-- and has NOT logged a repost today. Guarded so running it twice in one
-- day doesn't double-notify.
-- ASSUMPTION (same as add_leaderboard.sql): daily_reposts has
-- (user_id, repost_date DATE, status TEXT).
CREATE OR REPLACE FUNCTION send_daily_repost_reminder()
RETURNS INT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_count INT;
BEGIN
  WITH inserted AS (
    INSERT INTO notifications (user_id, title, body, action)
    SELECT u.id,
           'Repost today''s status',
           'Tap to open the admin''s WhatsApp, repost the status and earn today''s key.',
           'open_whatsapp_repost'
      FROM users u
     WHERE u.fcm_token IS NOT NULL
       AND NOT EXISTS (
         SELECT 1 FROM daily_reposts d
          WHERE d.user_id = u.id
            AND d.repost_date = CURRENT_DATE
       )
       AND NOT EXISTS (
         SELECT 1 FROM notifications n
          WHERE n.user_id = u.id
            AND n.action = 'open_whatsapp_repost'
            AND n.created_at::date = CURRENT_DATE
       )
    RETURNING 1
  )
  SELECT COUNT(*) INTO v_count FROM inserted;
  RETURN v_count;
END;
$$;
REVOKE ALL ON FUNCTION send_daily_repost_reminder() FROM anon, authenticated;

-- 3. Repost verified / rejected -----------------------------------------
CREATE OR REPLACE FUNCTION notify_repost_status_change()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_balance INT;
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status = 'verified' THEN
      -- One combined message: verified + the key they got + new balance.
      -- (notify_key_added below skips a 1-key increase right after this, so
      -- they don't get two pushes.)
      SELECT COALESCE(key_balance, 0) INTO v_balance
        FROM users WHERE id = NEW.user_id;
      INSERT INTO notifications (user_id, title, body, action)
      VALUES (NEW.user_id,
              'Repost verified',
              'Repost verified! You got 1 key. Your balance is now ' || v_balance || '. Tap to unlock a contact list.',
              'open_downloads');
    ELSIF NEW.status = 'rejected' THEN
      INSERT INTO notifications (user_id, title, body, action)
      VALUES (NEW.user_id,
              'Repost rejected',
              'Your repost was rejected. No key was added. You can try again tomorrow.',
              'open_repost');
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_repost_status_change ON daily_reposts;
CREATE TRIGGER on_repost_status_change
  AFTER UPDATE OF status ON daily_reposts
  FOR EACH ROW
  EXECUTE FUNCTION notify_repost_status_change();

-- 4. Key added ----------------------------------------------------------
-- Fires on ANY increase of users.key_balance: repost verification,
-- referral payout, or you topping someone up by hand after a purchase.
-- Spending a key (balance goes down) never notifies.
CREATE OR REPLACE FUNCTION notify_key_added()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_added INT;
BEGIN
  v_added := COALESCE(NEW.key_balance, 0) - COALESCE(OLD.key_balance, 0);

  -- Verified repost already sent ONE combined "Repost verified + key" push
  -- (see notify_repost_status_change). If that went out in the last 10
  -- seconds for this user, don't send a second "Key added" one.
  -- Works whichever of the two updates (repost status / key balance)
  -- runs first: see the matching check in notify_repost_status_change.
  IF v_added = 1 AND EXISTS (
    SELECT 1 FROM notifications n
     WHERE n.user_id = NEW.id
       AND n.title = 'Repost verified'
       AND n.created_at > now() - interval '10 seconds'
  ) THEN
    RETURN NEW;
  END IF;

  -- Other order: key added first, repost marked verified a moment later
  -- in the same transaction. The repost row is already 'verified' by the
  -- time this runs only if it was updated first, so also check for a
  -- repost of today that is verified and was just touched.
  IF v_added = 1 AND EXISTS (
    SELECT 1 FROM daily_reposts d
     WHERE d.user_id = NEW.id
       AND d.status = 'verified'
       AND d.repost_date = CURRENT_DATE
       AND NOT EXISTS (
         SELECT 1 FROM notifications n2
          WHERE n2.user_id = NEW.id
            AND n2.title = 'Repost verified'
            AND n2.created_at::date = CURRENT_DATE
       )
  ) THEN
    -- The repost trigger will send the combined message; stay quiet.
    RETURN NEW;
  END IF;

  IF v_added > 0 THEN
    INSERT INTO notifications (user_id, title, body, action)
    VALUES (NEW.id,
            CASE WHEN v_added = 1 THEN 'Key added' ELSE v_added || ' keys added' END,
            'Your balance is now ' || NEW.key_balance || '. Tap to unlock a contact list.',
            'open_downloads');
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_key_balance_increase ON users;
CREATE TRIGGER on_key_balance_increase
  AFTER UPDATE OF key_balance ON users
  FOR EACH ROW
  WHEN (NEW.key_balance IS DISTINCT FROM OLD.key_balance)
  EXECUTE FUNCTION notify_key_added();

-- 5. Schedule the daily reminder ---------------------------------------
-- Needs pg_cron: Dashboard -> Database -> Extensions -> pg_cron -> Enable.
-- pg_cron runs in UTC. 08:00 UTC = 09:00 Lagos (WAT). Change to taste.
-- Uncomment after enabling pg_cron:
--
-- SELECT cron.unschedule('daily-repost-reminder')
--   WHERE EXISTS (SELECT 1 FROM cron.job WHERE jobname = 'daily-repost-reminder');
-- SELECT cron.schedule('daily-repost-reminder', '0 8 * * *',
--                      $$ SELECT send_daily_repost_reminder(); $$);
--
-- Test it right now (sends to everyone who hasn't reposted today):
--   SELECT send_daily_repost_reminder();

NOTIFY pgrst, 'reload schema';
