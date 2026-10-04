-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Bank details shown on the app's Pay to verify screen.
-- Run once in Supabase -> SQL Editor (needs add_app_settings.sql first). Safe to run again.
--
-- The three rows start EMPTY on purpose. Until pay_account_number has a value, the app shows
-- "Account details aren't available yet" and hides Copy and Send proof, so nobody is ever
-- shown a fake account. Fill them in from the admin page: Settings tab ->
--   Pay screen: bank name / account name / account number -> Save.
--
-- To change one later without the admin page:
--   update app_settings set value = 'Opay' where key = 'pay_bank_name';

insert into app_settings (key, value) values
  ('pay_bank_name',      ''),
  ('pay_account_name',   ''),
  ('pay_account_number', '')
on conflict (key) do nothing;

-- If an earlier version of this file (with REPLACE WITH ... placeholders) was already run,
-- blank those placeholders so they can't reach the app. Real values are never touched.
update app_settings set value = ''
where key in ('pay_bank_name', 'pay_account_name', 'pay_account_number')
  and value like 'REPLACE WITH%';
