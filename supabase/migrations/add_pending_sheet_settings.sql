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
