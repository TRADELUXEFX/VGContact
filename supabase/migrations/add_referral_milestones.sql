-- =====================================================================
-- Referral milestones: every 10 referrals = 1 key (no cap).
--   10 -> 1 key, 20 -> 2 keys, 30 -> 3 keys, 50 -> 5 keys ...
--
-- HOW TO RUN: Supabase dashboard -> SQL Editor -> paste all of this ->
-- Run. Safe to run more than once.
--
-- WHO COUNTS AS A REFERRAL
--   A user whose referred_by matches your phone number or your username,
--   AND who has at least one verified repost (a daily_reposts row with
--   status = 'verified' - the same table the leaderboard already reads).
--   That stops fake accounts from farming keys.
--   To count every signup instead, change  v_require_verified  to FALSE
--   below and run this file again.
--
-- HOW KEYS ARE PAID
--   The app calls claim_referral_keys() when the Refer & Earn page opens.
--   referral_rewards remembers how many keys were already paid to each
--   user, so the same key is never paid twice, even if the app calls it
--   many times or two calls arrive at once.
-- =====================================================================

CREATE TABLE IF NOT EXISTS referral_rewards (
  user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  keys_paid  INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Only the function below may touch this table.
ALTER TABLE referral_rewards ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON referral_rewards FROM anon, authenticated;

CREATE OR REPLACE FUNCTION claim_referral_keys(p_user_id UUID)
RETURNS TABLE (
  referrals   INT,   -- qualifying referrals right now
  keys_earned INT,   -- total keys those referrals are worth
  keys_added  INT    -- keys added to the balance by THIS call
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_require_verified CONSTANT BOOLEAN := TRUE;
  v_phone    TEXT;
  v_username TEXT;
  v_count    INT;
  v_earned   INT;
  v_paid     INT;
  v_add      INT;
BEGIN
  SELECT btrim(u.phone), btrim(u.username)
    INTO v_phone, v_username
    FROM users u
   WHERE u.id = p_user_id;

  IF v_phone IS NULL THEN
    RAISE EXCEPTION 'USER_NOT_FOUND';
  END IF;

  SELECT COUNT(*)::INT
    INTO v_count
    FROM users r
   WHERE r.id <> p_user_id
     AND lower(btrim(r.referred_by)) IN (lower(v_phone), lower(v_username))
     AND (
       NOT v_require_verified
       OR EXISTS (
         SELECT 1
           FROM daily_reposts d
          WHERE d.user_id = r.id
            AND d.status = 'verified'
       )
     );

  v_earned := v_count / 10;

  -- Lock this user's payout row so two calls at once can't both pay.
  INSERT INTO referral_rewards (user_id)
  VALUES (p_user_id)
  ON CONFLICT (user_id) DO NOTHING;

  SELECT rr.keys_paid
    INTO v_paid
    FROM referral_rewards rr
   WHERE rr.user_id = p_user_id
     FOR UPDATE;

  v_add := GREATEST(v_earned - v_paid, 0);

  IF v_add > 0 THEN
    UPDATE users
       SET key_balance = COALESCE(key_balance, 0) + v_add
     WHERE id = p_user_id;

    UPDATE referral_rewards
       SET keys_paid  = v_paid + v_add,
           updated_at = NOW()
     WHERE user_id = p_user_id;
  END IF;

  RETURN QUERY SELECT v_count, v_earned, v_add;
END;
$$;

GRANT EXECUTE ON FUNCTION claim_referral_keys(UUID) TO anon, authenticated;
