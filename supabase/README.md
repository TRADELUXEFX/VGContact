# VGContact database: how to keep the files and the live database in step

**The live Supabase database is the truth. The files in `migrations/` are only a history of what was run.**
Many of the older files were changed later by hand, by newer files, or by SQL pasted straight into the
SQL Editor. Running an old file again can put an old version of a function back and bring old bugs back.

## Rules
1. Never re-run a file marked `OUTDATED - DO NOT RE-RUN` at the top.
2. To see the real current code, run the queries in `export_live_definitions.sql` and read the result.
3. After you change the database, save the SQL you ran as a new file in `migrations/` (new name, never
   edit an old one), and add a line to the change log in `VGCONTACT_SYSTEM.md`.

## Current files (newest changes, all already run on the live database)
| File | What it did |
| --- | --- |
| `add_user_secret.sql` | Account secret: `users.secret`, `_auth_ok`, `_name_reserved`, `register_account`, `login_account`, and a `p_secret` version of every app function. Old no-secret versions are locked. |
| `add_referrer_id.sql` | `users.referrer_id` filled by triggers; referral functions use it. |
| `add_system_contact_flag.sql` | `get_sync_contacts(uuid, text)` returns `is_system` for the admin and status numbers. |
| `add_push_caller_check.sql` | `push_caller_ok` for the `send-push` caller check. |
| `use_nigeria_time.sql` | A repost "day" is a Nigerian day (`_today_ng()`). |
| `wipe_test_data.sql` | Optional, manual, one-off: clears all users before launch. Not part of the normal history. |

## Switching the Android ID requirement
Use the admin page setting `require_android_id` (true or false), or run
`update app_settings set value = 'false' where key = 'require_android_id';` (use `'true'` to turn it back on).
Do not re-run `toggle_android_id_requirement.sql` for this: it also contains old function versions.

## Facts from the live database (2026-10-04)
- Extensions: plpgsql, pg_stat_statements, uuid-ossp, pgcrypto, supabase_vault, pg_net, pg_cron.
- Triggers: `a_block_signup_when_closed` (before insert on users), `b_users_set_referrer` and
  `c_users_link_referees` (users), `on_notification_created` (notifications, calls `send-push`),
  `on_repost_status_change` and `place_on_repost_verified` (daily_reposts).
- Row security is ON for every table. The only policy lets `anon` read published `contact_groups`.
  Everything else goes through `security definer` functions.
- Only functions that take `p_secret` (plus `register_account`, `login_account`, `get_ban_status`,
  `get_app_update`, `get_app_setting`) are callable with the app key.
