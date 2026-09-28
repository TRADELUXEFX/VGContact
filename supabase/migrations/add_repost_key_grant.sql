-- Adds 1 key when a repost becomes 'verified'.
-- Run AFTER add_notification_actions_and_triggers.sql. Safe to re-run.
-- Trigger is named on_repost_a_grant_key so it fires BEFORE on_repost_status_change
-- (alphabetical), so the 'Repost verified' push shows the new balance.

CREATE OR REPLACE FUNCTION grant_key_on_repost_verified()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE users
     SET key_balance = COALESCE(key_balance, 0) + 1
   WHERE id = NEW.user_id;
  RETURN NEW;
END;
$$;

-- Named so it fires BEFORE on_repost_status_change (alphabetical order),
-- so the key is added first and the "Repost verified" push shows the new balance.
DROP TRIGGER IF EXISTS on_repost_verified_grant_key ON daily_reposts;
DROP TRIGGER IF EXISTS on_repost_a_grant_key ON daily_reposts;
CREATE TRIGGER on_repost_a_grant_key
  AFTER UPDATE OF status ON daily_reposts
  FOR EACH ROW
  WHEN (NEW.status = 'verified' AND OLD.status IS DISTINCT FROM 'verified')
  EXECUTE FUNCTION grant_key_on_repost_verified();

NOTIFY pgrst, 'reload schema';
