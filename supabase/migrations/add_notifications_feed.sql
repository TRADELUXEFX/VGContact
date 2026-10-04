-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Notifications feed: the two database functions the Notifications screen
-- calls (fetch_notifications, mark_notifications_read) plus the small
-- table that remembers what each user has already read.
--
-- Run this whole file once in Supabase -> SQL Editor. It is safe to run
-- again: every statement is "if not exists" / "create or replace".
--
-- This is separate from the send-push Edge Function. The Edge Function only
-- sends the phone alert. The Notifications screen reads the list through
-- these two functions.

-- 1. Broadcast notifications (shown to everyone) have no single owner, so
--    user_id must be allowed to be empty. Own notifications still set it.
ALTER TABLE notifications ALTER COLUMN user_id DROP NOT NULL;

-- 2. Remembers which notifications each user has read. One row per
--    (user, notification), so a broadcast can be read by one person
--    without marking it read for everyone else.
CREATE TABLE IF NOT EXISTS notification_reads (
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  read_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, notification_id)
);

ALTER TABLE notification_reads ENABLE ROW LEVEL SECURITY;
-- No policies on purpose: the app never touches this table directly, only
-- through the SECURITY DEFINER functions below.

-- 3. The list: the user's own notifications plus every broadcast, newest
--    first, at most 50, each flagged read or unread for THIS user.
CREATE OR REPLACE FUNCTION fetch_notifications(p_user_id UUID)
RETURNS TABLE (
  id UUID,
  title TEXT,
  body TEXT,
  created_at TIMESTAMP WITH TIME ZONE,
  is_read BOOLEAN
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    n.id,
    n.title,
    n.body,
    n.created_at,
    (r.notification_id IS NOT NULL) AS is_read
  FROM notifications n
  LEFT JOIN notification_reads r
    ON r.notification_id = n.id
   AND r.user_id = p_user_id
  WHERE n.user_id = p_user_id
     OR n.user_id IS NULL
  ORDER BY n.created_at DESC
  LIMIT 50;
$$;

-- 4. Marks everything this user can currently see as read.
CREATE OR REPLACE FUNCTION mark_notifications_read(p_user_id UUID)
RETURNS VOID
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  INSERT INTO notification_reads (user_id, notification_id)
  SELECT p_user_id, n.id
  FROM notifications n
  WHERE n.user_id = p_user_id
     OR n.user_id IS NULL
  ON CONFLICT (user_id, notification_id) DO NOTHING;
$$;

-- 5. Let the app (anon key) call them.
GRANT EXECUTE ON FUNCTION fetch_notifications(UUID) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION mark_notifications_read(UUID) TO anon, authenticated;

-- 6. Make sure Supabase's API notices the new functions right away.
NOTIFY pgrst, 'reload schema';
