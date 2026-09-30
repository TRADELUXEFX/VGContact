# VGContact: System Guide

> **For any Claude chat reading this:** this file describes the whole system. Read it before touching anything.
> **Rule for maintainers:** whenever you change the database, the app or the admin page, update this file in the same delivery and add a line to the Changelog at the bottom. Always ship it at the root of the ZIP.
> Last verified against the live database: 2026-09-30.

## 1. Goal and ground rules
- The **live Supabase database is the source of truth**. The Android app and the admin dashboard must follow it. Do not change the database to fit them.
- **Keys no longer exist.** No key feature should remain in the app or admin. (Leftover key columns in the database are harmless; see section 9.)
- The owner writes short dictated messages. Act without asking questions, make small step-by-step edits, deliver ZIPs with files at the root, and check JS syntax before delivering.

## 2. The idea in plain words
Users want more people to see their WhatsApp status. Users are put into groups of 3. The app saves other group members' phone numbers into your phone, so you and they can see each other's statuses.

Three parts:
- **Database (Supabase)** holds every rule and decision.
- **Android app** is only a screen. It asks the database for numbers and shows them.
- **Admin dashboard (`index.html`)** is the owner's control panel. Only the admin email hardcoded in `_is_admin()` may use it.

## 3. The rules
1. **Signup.** The new user goes into the lowest-numbered group that is not full. If every group is full, a new group is created. A group fills at `group_size` signups (default 3). When it fills it becomes `is_full` and `is_published`.
2. **Free viewers** = signups in your own default group, shown as current / group_size (for example 1/3).
3. **Repost.** A user may submit one repost per UTC day. It starts `pending`. Blocked once the user has `free_repost_cap` verified reposts (default 3).
4. **Verify.** The admin verifies a pending repost. The user is then added (`joined_via = 'added'`) to the lowest-numbered full group they are not already in. If no full group exists, they get nothing, and the verified repost still counts toward the cap. **Catch-up:** once a day `_catch_up_group_grants` gives users any group they are owed (verified reposts, capped by `free_repost_cap`, minus their `added` groups) once a full group exists.
5. **Extra viewers** = everyone in every other group the user belongs to, shown as current / max (max is the larger of group_size and the actual count, per group).
6. **Referred viewers** = users whose `referred_by` matches this user's username (case-insensitive), or is all digits and matches the last 10 digits of this user's phone.
7. **Verified badge.** A user is VERIFIED once they have at least one verified repost, otherwise PENDING.
8. **Sync** (what the phone saves): the admin number (`admin_phone`, named `admin_contact_name` or "VGContact Admin"), the optional `anon_phone` (named `anon_contact_name` or "VGContact Status"), plus other members of your groups. Group members are included only if they are not banned and have at least one verified repost themselves. A group counts only if it is full, or if you were added to it.
   **Referred users** (rule 6) are also in the sync list (from `get_sync_contacts`), named by username; banned ones are excluded, and no verified repost is required from them.
   **On the phone:** each saved contact is named `<name> VGC<N>` (e.g. `Chidera VGC3`, N = lowest free number). The `VGC<N>` ending is how the app recognises its own contacts, read from the phone itself, so it survives clearing app data. Each sync also removes VGC contacts whose number is no longer in the server list (banned or removed member); contacts without the tag are never touched, and an empty server list never deletes anything.
9. **Bans** block by user, phone and device (`banned_identities`). Banned users get `ACCOUNT_BANNED`.
10. **Support.** "Buy Status Viewers" opens a WhatsApp chat with support (number in `SupportContact.kt`).

## 4. Tables (public schema)
- `users`: id, android_id, username, phone, referred_by, created_at, total_downloads, total_reposts, subscription_status, key_balance (unused), fcm_token, is_banned, ban_reason, fcm_token_updated_at, notifications_enabled, notifications_enabled_at
- `contact_groups`: id, group_number, member_count, is_full, is_published, created_at, filled_at
- `contact_group_members`: id, group_id, user_id, username, phone, member_position (smallint, no upper limit), created_at, joined_via (`signup` or `added`)
- `daily_reposts`: id, user_id, repost_date, phone, status (`pending`/`verified`/`rejected`), verified_at, created_at (unique per user and date)
- `app_settings`: key, value
- `banned_identities`: user_id, phone, android_base, reason
- `notifications`: id, user_id (null = broadcast), title, body, is_sent, created_at, action, target, sent_by_admin, fcm_sent, fcm_failed
- `notification_reads`, `notification_receipts` (delivered/opened tracking), `activity_logs`
- Views: `notification_reach`, `notification_stats`

## 5. Settings (`app_settings`)
`group_size` (3), `free_repost_cap` (3), `registrations_open` (true/false), `require_android_id` (currently false), `daily_reminder_enabled` (true/false), `admin_phone`, `community_link`. Optional: `anon_phone`, `admin_contact_name`, `anon_contact_name`.

## 6. Database functions
All are `SECURITY DEFINER` and read-only unless noted.

**Helpers (internal, not callable by the app):** `_group_size`, `_repost_cap`, `_setting_int`, `_android_id_required`, `_is_admin`, `_is_banned`, `_is_verified`, `_count_referrals`.

**Writes:**
- `_place_user_in_group(user)`: signup placement (advisory lock `vgcontact_group_placement`).
- `_catch_up_group_grants()`: cron catch-up, calls `_grant_next_group` once per missing group, returns attempts (internal, not granted to anon).
- `_grant_next_group(user)`: adds the user to the lowest full group they are not in (`joined_via='added'`).
- `_verify_repost(user, date)`: returns `no_pending_repost`, `cap_reached` or `verified`; on verify it calls `_grant_next_group`.
- `register_or_fetch_user`: validates, auto-numbers duplicate usernames (chidera → chidera2), inserts the user, then places them in a group. Phone format is 11 digits starting with 0.
- `submit_daily_repost`: returns `OK`, `ALREADY_REPOSTED_TODAY`, `REPOST_LIMIT_REACHED` or `USER_NOT_FOUND`.
- `save_fcm_token`, `report_notifications_enabled`, `mark_notifications_read`, `record_notification_delivered/opened`.
- `send_daily_repost_reminder`: inserts reminder notifications (run by cron).

**Reads used by the app:** `login_by_phone`, `get_home`, `get_sync_contacts`, `get_my_profile`, `get_leaderboard` (no longer called by the app), `get_today_repost_status`, `get_ban_status`, `get_app_setting`, `fetch_notifications`.

**Admin only (checked by `_is_admin()`):** `admin_find_users`, `admin_pending_reposts`, `admin_set_repost` (verify or reject), `admin_set_banned`, `admin_get_settings`, `admin_set_setting`, `admin_send_notification`, `admin_notification_history`, `admin_notification_stats`, `admin_notification_reach`. Also defined but not used by the admin page: `admin_verify_reposts` (bulk verify by username, with preview) and `admin_add_user_to_group`.

## 7. Automatic things
- **Triggers:** `a_block_signup_when_closed` (BEFORE INSERT on users, honours `registrations_open`); `on_repost_status_change` (AFTER UPDATE on daily_reposts, creates "Repost verified" or "Repost rejected" notifications); `on_notification_created` (AFTER INSERT on notifications, calls the `send-push` edge function).
- **Push:** needs the secret `service_role_key` in Supabase Vault (confirmed present 2026-09-30). If it is missing, pushes silently do nothing.
- **Cron:** `daily-repost-reminder` runs at `0 8 * * *` (08:00 UTC, 09:00 Nigeria time). `catch-up-group-grants` runs once a day at `0 6 * * *` (06:00 UTC, 07:00 Nigeria time).

## 8. Permissions gotcha (important)
The app connects as the `anon` role and can only run functions it has `EXECUTE` on. A new or recreated function may lack it. Symptom: the call fails silently and the screen shows placeholders. Fix:
```sql
grant execute on function public.<name>(<args>) to anon, authenticated;
notify pgrst, 'reload schema';
```
Check with `has_function_privilege('anon', p.oid, 'execute')`. Internal helpers (names starting with `_`) should stay locked. On 2026-09-30, `get_home` and `get_sync_contacts` were missing this and were granted.

## 9. Known leftovers and open decisions
- `users.key_balance` still exists and is returned by `login_by_phone`, `register_or_fetch_user`, `admin_find_users` and `admin_pending_reposts`. Harmless. Decide whether to remove.
- `admin_set_setting` still validates a `keys_per_repost` key. `admin_send_notification` still allows the `open_downloads` action.
- Old key migrations in `supabase/migrations` are misleading. Decide whether to delete them.
- ~~A repost verified before any group is full gives nothing~~ Resolved 2026-09-30 by the catch-up cron.

## 10. Android app map (`app/src/main/java/com/vgcontact/app`)
- `SupabaseClient.kt`: every server call (`rpc("name", params)`); reads `SUPABASE_URL` and `SUPABASE_ANON_KEY` from BuildConfig.
- `HomeActivity`: Free/Extra/Referred viewers (from `get_home`), Sync Contacts, Buy Status Viewers. Starts at 0 and /0; shows a toast if loading fails.
- `RepostActivity` (no leaderboard; header title only), `ReferralActivity` (built like Repost: centered title, no Back arrow), `ProfileActivity`, `NotificationsActivity`, `LoginActivity`, `RegisterActivity`, `SessionManager` (stores `user_id`), `ContactSync` (writes contacts to the phone), `SupportContact.kt`, `LegalContent.kt` (Terms and Privacy, rewritten for groups and reposts).
- `DailySyncWorker.kt`: background contact sync every ~24 hours (WorkManager), scheduled from `VGApp`. Same `ContactSync` as the Sync button. Skips when logged out, banned or paused. Contacts permission off gives one reminder notification. Offline or server failure queues a one-time retry that fires when the network returns. New contacts added gives a notification like "3 contacts synced today at 10:30" (nothing is shown on Home). With data off, the sync waits and runs, with the notification, as soon as data is back.
- `SyncPrefs.kt`: local paused flag (`vgc_sync` prefs). Profile > **Delete My Contacts** removes every `VGC<N>` contact and pauses all syncing (button, first-run, background); the same button becomes **Resume Syncing**, which unpauses and syncs at once. Pause is local only, not reported to the database.
- `BuyKeysActivity.kt` and `DownloadsActivity.kt` are empty stubs and can be deleted from the repo.
- Build: GitHub Actions (`.github/workflows/deploy.yml`) using secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY`. Debug-signed APKs.
- UI convention: green top, white bottom, Poppins font.

## 11. Admin dashboard (`index.html`)
Single-file page. Tabs cover users, pending reposts (verify/reject), settings, notifications and stats. The Key purchases tab, key log and add-keys were removed.

## 12. Test loop (expected results)
1. Register users 1, 2 and 3: group 1 fills, each shows Free 3/3.
2. Register user 4: lands in group 2, shows 1/3.
3. User 4 submits a repost; admin verifies it.
4. User 4 shows Extra 3/3 and gets a "Repost verified" push. Sync Contacts adds users 1 to 3, provided they have a verified repost themselves.

## 13. Changelog
- 2026-09-30: Keys removed from admin and app. Shared `_count_referrals`. Position limit of 3 dropped (verify crash). Home defaults to 0 and /0, and shows an error toast on load failure. Granted `EXECUTE` on `get_home` and `get_sync_contacts` to anon (Home was stuck on placeholders). Legal text rewritten. Created this guide.
- 2026-09-30: Leaderboard removed from the Repost screen (tabs, layout, client code). Referral screen rebuilt from scratch to match Repost (no Back arrow, same header and cards). Phone Back on Repost, Referral and Profile now returns to Home; Home exits the app.
- 2026-09-30: Permission screen now has step 2 (contacts). If allowed, the sync runs right there and saves the contacts (admin number, and verified group members) before Home opens. Before this, contacts were only requested when tapping Sync Contacts on Home.
- 2026-09-30: Added background contact sync every ~24 hours (`DailySyncWorker`, WorkManager). There was no background sync before.
- 2026-09-30: Contact naming and cleanup ported from VGKontact, tag `VGC`. Contacts saved as `<name> VGC<N>`; sync now removes VGC contacts that left the server list (with empty-list safety); Profile got Delete My Contacts / Resume Syncing with a local pause flag honoured by the background worker. No database change.
- 2026-09-30: Banned screen now removes every VGC contact from the phone; `ContactSync.run` refuses to sync while `BanPrefs.isBanned` (resumes on its own if the ban is lifted). Background worker upgraded: retry on reconnect, permission-off reminder, new-contacts notification. No database change.
- 2026-09-30: `get_sync_contacts` now also returns users referred by the caller (rule 6 matching, banned excluded). SQL in `get_sync_contacts_with_referrals.sql`. No app change.
- 2026-09-30: Added `_catch_up_group_grants` and cron `catch-up-group-grants` (once a day, 06:00 UTC) so reposts verified before any group was full get their groups later. SQL in `catch_up_group_grants.sql`. No app change.
- 2026-09-30: Background sync notification now reads "N contacts synced today at HH:mm". No Home-screen line, no sync history screen.
- 2026-09-30: Sync Contacts button message on Home now reads "N contacts added today". Problem messages (no internet, banned, paused) unchanged.
- 2026-09-30: "N contacts added/synced today" (Sync button message and background notification) is now the running total for the day, kept in `SyncPrefs`; it resets on a new day and when Delete My Contacts is used.
