-- Leaderboard: everything the Repost -> Leaderboard tab needs.
--
-- Run this whole file once in Supabase -> SQL Editor. It is safe to run
-- again: every statement is "if not exists" / "create or replace".
--
-- Same pattern as add_notifications_feed.sql: the app uses the anon key
-- with no Supabase auth session, so it never reads users/daily_reposts
-- directly for the leaderboard. It calls the SECURITY DEFINER functions
-- below, which return ONLY safe columns (username + numbers). Phone
-- numbers, android_id and fcm_token are never exposed.
--
-- One backend serves all three UI options:
--   Option 1 (podium)        -> get_leaderboard(p_user_id, 'streak', 'week', 20)
--   Option 2 (tabbed list)   -> get_leaderboard(..., 'streak' | 'referrals' | 'reposts', ...)
--   Option 3 (league/tiers)  -> get_my_league(p_user_id) + get_leaderboard(...)
--
-- ASSUMPTION: daily_reposts has (user_id, repost_date DATE, status TEXT)
-- with status = 'verified' once you confirm the repost each night. That
-- is what the app code reads (SupabaseClient.fetchTodayRepostStatus).
-- vgcontact_keys_schema.sql was not in the zip, so if your column names
-- differ, only the daily_reposts references below need to change.

-- ---------------------------------------------------------------------
-- 1. Performance: the ranking queries filter verified reposts by date.
-- ---------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_daily_reposts_verified_date
  ON daily_reposts (repost_date, user_id)
  WHERE status = 'verified';

-- referred_by stores the referrer's PHONE (the app shows the phone as the
-- "Referral code"), so index it for the referral count.
CREATE INDEX IF NOT EXISTS idx_users_referred_by ON users (referred_by);

-- ---------------------------------------------------------------------
-- 2. Opt-out: lets a user hide from the public board (privacy).
--    Hidden users still see their own rank, they just don't appear
--    in other people's lists.
-- ---------------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS hide_from_leaderboard BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------
-- 3. Current streak per user = consecutive verified days ending today
--    (or yesterday, so a streak isn't shown as broken before the user has
--    had a chance to repost today).
--    Classic "gaps and islands": within a run of consecutive dates,
--    (repost_date - row_number) is constant.
-- ---------------------------------------------------------------------
CREATE OR REPLACE VIEW user_streaks AS
WITH days AS (
  SELECT DISTINCT user_id, repost_date
  FROM daily_reposts
  WHERE status = 'verified'
),
islands AS (
  SELECT
    user_id,
    repost_date,
    repost_date - (ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY repost_date))::int AS grp
  FROM days
),
runs AS (
  SELECT
    user_id,
    COUNT(*)::int AS run_length,
    MAX(repost_date) AS run_end
  FROM islands
  GROUP BY user_id, grp
)
SELECT
  user_id,
  run_length AS current_streak
FROM runs
WHERE run_end >= (now() AT TIME ZONE 'UTC')::date - 1;

-- Views are not reachable by the app on their own (no grant to anon).
REVOKE ALL ON user_streaks FROM anon, authenticated;

-- ---------------------------------------------------------------------
-- 4. The board.
--    p_metric : 'streak'    current consecutive verified days
--               'reposts'   verified reposts inside the period
--               'referrals' users who signed up with this user's phone
--    p_period : 'week' | 'month' | 'all'   (ignored for 'streak')
--    Returns the top p_limit rows PLUS the caller's own row (flagged
--    is_me) even when they are outside the top, so the app can pin
--    "#14 You" under the list.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION get_leaderboard(
  p_user_id UUID,
  p_metric  TEXT DEFAULT 'streak',
  p_period  TEXT DEFAULT 'week',
  p_limit   INT  DEFAULT 20
)
RETURNS TABLE (
  rank      BIGINT,
  user_id   UUID,
  username  TEXT,
  score     INT,
  is_me     BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_since DATE;
BEGIN
  IF p_metric NOT IN ('streak', 'reposts', 'referrals') THEN
    RAISE EXCEPTION 'INVALID_METRIC';
  END IF;

  -- Cap so a modified client can't ask for the whole user table.
  p_limit := LEAST(GREATEST(COALESCE(p_limit, 20), 1), 50);

  v_since := CASE p_period
    WHEN 'week'  THEN CURRENT_DATE - 6
    WHEN 'month' THEN CURRENT_DATE - 29
    ELSE DATE '1970-01-01'
  END;

  RETURN QUERY
  WITH scores AS (
    SELECT
      u.id AS uid,
      u.username AS uname,
      u.hide_from_leaderboard AS hidden,
      CASE p_metric
        WHEN 'streak' THEN COALESCE(
          (SELECT s.current_streak FROM user_streaks s WHERE s.user_id = u.id), 0)
        WHEN 'reposts' THEN COALESCE(
          (SELECT COUNT(*)::int FROM daily_reposts d
            WHERE d.user_id = u.id AND d.status = 'verified'
              AND d.repost_date >= v_since), 0)
        ELSE COALESCE(
          (SELECT COUNT(*)::int FROM users r
            WHERE r.referred_by = u.phone
              AND r.created_at::date >= v_since), 0)
      END AS pts
    FROM users u
  ),
  ranked AS (
    -- Ties share the same rank (1,2,2,4). Older accounts break ties in
    -- the ORDER BY below only for stable display order.
    SELECT
      RANK() OVER (ORDER BY pts DESC) AS rnk,
      uid, uname, hidden, pts
    FROM scores
    WHERE pts > 0
  )
  SELECT r.rnk, r.uid, r.uname, r.pts, (r.uid = p_user_id)
  FROM ranked r
  WHERE (NOT r.hidden AND r.rnk <= p_limit)   -- public top N
     OR r.uid = p_user_id                      -- always include caller
  ORDER BY r.rnk, r.uname;
END;
$$;

-- ---------------------------------------------------------------------
-- 5. Option 3 only: the caller's league (tier) + progress to the next.
--    Tiers are based on verified reposts in the current calendar week:
--      Bronze 0-2, Silver 3-6, Gold 7-13, Diamond 14+
--    Change the CASE thresholds below to tune difficulty.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION get_my_league(p_user_id UUID)
RETURNS TABLE (
  league        TEXT,
  next_league   TEXT,
  weekly_score  INT,
  to_next       INT,
  rank_in_league BIGINT
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  WITH weekly AS (
    SELECT u.id AS uid,
           COALESCE((
             SELECT COUNT(*)::int FROM daily_reposts d
             WHERE d.user_id = u.id AND d.status = 'verified'
               AND d.repost_date >= date_trunc('week', CURRENT_DATE)::date
           ), 0) AS pts
    FROM users u
  ),
  tiered AS (
    SELECT uid, pts,
      CASE WHEN pts >= 14 THEN 'Diamond'
           WHEN pts >= 7  THEN 'Gold'
           WHEN pts >= 3  THEN 'Silver'
           ELSE 'Bronze' END AS lg
    FROM weekly
  ),
  me AS (SELECT * FROM tiered WHERE uid = p_user_id)
  SELECT
    me.lg,
    CASE me.lg WHEN 'Bronze' THEN 'Silver'
               WHEN 'Silver' THEN 'Gold'
               WHEN 'Gold'   THEN 'Diamond'
               ELSE NULL END,
    me.pts,
    CASE me.lg WHEN 'Bronze' THEN 3  - me.pts
               WHEN 'Silver' THEN 7  - me.pts
               WHEN 'Gold'   THEN 14 - me.pts
               ELSE 0 END,
    (SELECT COUNT(*) + 1 FROM tiered t WHERE t.lg = me.lg AND t.pts > me.pts)
  FROM me;
$$;

-- ---------------------------------------------------------------------
-- 6. Privacy toggle (for a switch on the Profile screen).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_leaderboard_visibility(p_user_id UUID, p_hidden BOOLEAN)
RETURNS VOID
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
  UPDATE users SET hide_from_leaderboard = p_hidden WHERE id = p_user_id;
$$;

-- ---------------------------------------------------------------------
-- 7. Let the app (anon key) call them.
-- ---------------------------------------------------------------------
GRANT EXECUTE ON FUNCTION get_leaderboard(UUID, TEXT, TEXT, INT)      TO anon, authenticated;
GRANT EXECUTE ON FUNCTION get_my_league(UUID)                          TO anon, authenticated;
GRANT EXECUTE ON FUNCTION set_leaderboard_visibility(UUID, BOOLEAN)    TO anon, authenticated;

-- ---------------------------------------------------------------------
-- 8. Make sure Supabase's API notices the new functions right away.
-- ---------------------------------------------------------------------
NOTIFY pgrst, 'reload schema';
