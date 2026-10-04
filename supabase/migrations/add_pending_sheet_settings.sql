-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Values shown on the pending bottom sheet. Change them without shipping a new APK.
-- Run once in Supabase -> SQL Editor (needs add_app_settings.sql first). Safe to run again.
--
-- To change one later, e.g. the price:
--   update app_settings set value = '₦2,000' where key = 'pending_pay_amount';

insert into app_settings (key, value) values
  ('pending_viewers',      '500+'),
  ('pending_min_views',    '30'),
  ('pending_pay_amount',   '₦1,500'),
  ('pending_verify_hours', '24')
on conflict (key) do nothing;
