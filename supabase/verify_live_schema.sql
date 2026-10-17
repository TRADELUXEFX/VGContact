-- verify_live_schema.sql
-- READ-ONLY. Changes nothing in the database.
-- Run in Supabase > SQL Editor. Run EACH numbered block on its own (select the block, press Run),
-- then copy the result (the table, or the single cell) and paste it back in the chat.
--
-- Purpose: check the parts of VGCONTACT_SYSTEM.md and the app that the repo cannot prove,
-- because the repo is missing several functions, tables and triggers the app depends on.

-- =====================================================================================
-- 0. CONFIRM THE PROJECT. Lists the tables in the public schema of THIS project.
--    Expected: users, contact_groups, contact_group_members, daily_reposts, app_settings,
--    banned_identities, notifications, system_contacts, contact_removals, app_releases, ...
--    If users is missing here, you are in a different Supabase project (or an empty one):
--    open the project the app uses (its URL is in local.properties, SUPABASE_URL) and run again.
-- =====================================================================================
select table_schema, table_name
  from information_schema.tables
 where table_schema = 'public'
 order by table_name;

-- =====================================================================================
-- 1. WHO CAN RUN WHAT: every public function, its security flags and its callers.
--    Red flag: anon_can_run = true on a function that does NOT take p_secret, unless it is
--    one of: register_account, login_account, get_ban_status, get_app_update, get_notice,
--    get_app_setting (limited keys only), register_or_fetch_user/login_by_phone (should be FALSE).
--    Red flag: has_search_path = false on a security_definer = true function.
-- =====================================================================================
select p.proname as fn,
       pg_get_function_identity_arguments(p.oid) as args,
       p.prosecdef as security_definer,
       has_function_privilege('anon', p.oid, 'EXECUTE') as anon_can_run,
       has_function_privilege('authenticated', p.oid, 'EXECUTE') as auth_can_run,
       has_function_privilege('service_role', p.oid, 'EXECUTE') as service_can_run,
       coalesce(exists (select 1 from unnest(coalesce(p.proconfig, '{}'::text[])) c
                         where c like 'search_path=%'), false) as has_search_path,
       ('p_secret' = any (coalesce(p.proargnames, '{}'::text[]))) as takes_secret
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prokind = 'f'
 order by anon_can_run desc, fn, args;

-- =====================================================================================
-- 2. EXISTENCE CHECK: every function the app calls, every helper and admin function the
--    doc names, and the trigger functions. overloads = 0 means it is MISSING from the live DB.
--    Expected: all rows have overloads >= 1, except notify_key_added (should be 0).
-- =====================================================================================
with wanted(fn) as (values
  -- app calls (SupabaseClient.kt)
  ('get_app_bundle'),('get_home'),('get_today_repost_status'),('get_today_verify_status'),
  ('get_sync_contacts'),('get_removed_numbers'),('get_my_profile'),('get_my_referrer'),
  ('get_my_referrals_full'),('get_my_referrals'),('get_my_referrals_status'),
  ('get_referral_leaderboard'),('get_referral_leaderboard_phone'),
  ('get_repost_leaderboard_phone'),('get_leaderboard'),('open_notifications'),
  ('fetch_notifications'),('mark_notifications_read'),('record_notification_delivered'),
  ('record_notification_opened'),('report_notifications_enabled'),('save_fcm_token'),
  ('record_sync'),('report_sync_device'),('get_sync_adapter_enabled'),('submit_daily_repost'),
  ('register_account'),('login_account'),('get_ban_status'),('get_app_update'),('get_notice'),
  ('get_app_setting'),
  -- helpers named in the doc (not in the repo)
  ('_is_admin'),('_is_banned'),('_is_verified'),('_place_user_in_group'),('_place_on_verify'),
  ('_grant_next_group'),('_group_size'),('_repost_cap'),('_setting_int'),('_ref_match'),
  ('_verify_repost'),('_catch_up_group_grants'),('_auth_ok'),('_name_reserved'),('_today_ng'),
  ('_is_inactive'),('_inactive_after_days'),('_android_id_required'),('_count_referrals'),
  ('_resolve_referrer'),('_users_set_referrer'),('_users_link_referees'),('_on_user_banned'),
  ('_one_live_release'),('_block_signup_when_closed_v2'),('push_caller_ok'),
  ('trigger_send_push_notification'),('notify_repost_status_change'),
  ('register_or_fetch_user'),('login_by_phone'),
  -- admin functions named in the doc (not in the repo)
  ('admin_set_banned'),('admin_get_settings'),('admin_set_setting'),('admin_send_notification'),
  ('admin_notification_history'),('admin_notification_reach'),('admin_notification_stats'),
  ('admin_add_user_to_group'),('admin_verify_reposts'),('admin_set_repost'),
  ('admin_pending_reposts'),('admin_find_users'),('admin_list_releases'),('admin_set_release'),
  ('admin_get_min_version'),('admin_set_min_version'),('admin_list_system_contacts'),
  ('admin_add_system_contact'),('admin_update_system_contact'),('admin_delete_system_contact'),
  ('admin_list_removals'),('admin_add_removal'),('admin_cancel_removal'),
  ('admin_unsynced_users'),('admin_sync_by_brand'),
  -- scheduled jobs' functions
  ('send_daily_repost_reminder'),('send_pending_verify_nudge'),('send_sync_reminders'),
  ('send_stalled_sync_nudge'),
  -- should NOT exist any more (old key feature)
  ('notify_key_added')
)
select w.fn,
       (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public' and p.proname = w.fn) as overloads,
       (select bool_or(has_function_privilege('anon', p.oid, 'EXECUTE'))
          from pg_proc p join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public' and p.proname = w.fn) as anon_can_run
  from wanted w
 order by overloads, w.fn;

-- =====================================================================================
-- 3. TRIGGERS: which table fires which function. Expected (per the doc):
--    place_on_repost_verified (daily_reposts), a_block_signup_when_closed (users),
--    b_users_set_referrer (users), c_users_link_referees (users),
--    on_notification_created (notifications), on_repost_status_change (daily_reposts),
--    trg_user_banned (users), trg_one_live_release (app_releases).
--    NOT expected: on_key_balance_increase, trg_group_member_removed.
-- =====================================================================================
select c.relname as table_name,
       t.tgname as trigger_name,
       p.proname as function_name,
       t.tgenabled as enabled
  from pg_trigger t
  join pg_class c on c.oid = t.tgrelid
  join pg_namespace n on n.oid = c.relnamespace
  join pg_proc p on p.oid = t.tgfoid
 where n.nspname = 'public' and not t.tgisinternal
 order by 1, 2;

-- =====================================================================================
-- 4. SCHEDULED JOBS (pg_cron). Expected (per the doc): daily-repost-reminder 0 8 * * *,
--    catch-up-group-grants 0 6 * * *, pending-verify-nudge 0 16 * * *, sync-reminders 0 9 * * *.
--    stalled-sync-nudge must NOT be scheduled (it was replaced by sync-reminders).
-- =====================================================================================
select jobid, jobname, schedule, command, active
  from cron.job
 order by jobid;

-- 4b. Failed scheduled runs in the last 14 days (should be empty or explained).
select j.jobname, d.status, d.return_message, d.start_time
  from cron.job_run_details d
  left join cron.job j on j.jobid = d.jobid
 where d.status <> 'succeeded'
   and d.start_time > now() - interval '14 days'
 order by d.start_time desc
 limit 30;

-- =====================================================================================
-- 5. TABLES: row security on/off, number of policies, approximate row count.
--    Expected: rls_on = true everywhere. policies = 0 everywhere except contact_groups
--    (anon may read published groups), per the doc.
-- =====================================================================================
select c.relname as table_name,
       c.relrowsecurity as rls_on,
       (select count(*) from pg_policies pol
         where pol.schemaname = 'public' and pol.tablename = c.relname) as policies,
       s.n_live_tup as approx_rows
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  left join pg_stat_user_tables s on s.relid = c.oid
 where n.nspname = 'public' and c.relkind = 'r'
 order by 1;

-- =====================================================================================
-- 6. DRIFT CHECK: is each key function the NEWEST version the doc describes?
--    Every row should show actual = expected. A "false" means the live function is older
--    (or changed) than the doc says. Read the check_name to see which rule it is.
-- =====================================================================================
select check_name, expected, actual, (expected = actual) as ok
from (
  select 'register_or_fetch_user has the ban check (banned_identities)' as check_name, true as expected,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%banned_identities%'), false) as actual
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'register_or_fetch_user'
  union all
  select 'register_or_fetch_user does NOT place users at sign-up (rule 1)', false,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%_place_user_in_group%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'register_or_fetch_user'
  union all
  select 'submit_daily_repost uses the Nigerian day (_today_ng)', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%_today_ng()%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'submit_daily_repost'
  union all
  select 'get_today_repost_status uses the Nigerian day (_today_ng)', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%_today_ng()%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_today_repost_status'
  union all
  select 'get_sync_contacts(uuid) has the upline (3 levels up)', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%with recursive up(%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_sync_contacts'
     and cardinality(coalesce(p.proargnames, '{}'::text[])) = 1
  union all
  select 'get_sync_contacts(uuid, text) returns is_system', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%is_system%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_sync_contacts'
     and 'p_secret' = any (coalesce(p.proargnames, '{}'::text[]))
  union all
  select 'get_app_bundle returns today_verify_status', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%today_verify_status%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_app_bundle'
  union all
  select 'get_app_update reads min_version_code (new rule)', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%min_version_code%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_app_update'
  union all
  select 'get_leaderboard ranks are unique (row_number)', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%row_number()%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_leaderboard'
  union all
  select 'get_home counts referrals through referrer_id', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%referrer_id%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname in ('get_home', '_count_referrals')
  union all
  select 'get_app_setting only answers community_link and update_url', true,
         coalesce(bool_or(pg_get_functiondef(p.oid) like '%community_link%update_url%'
                       or pg_get_functiondef(p.oid) like '%update_url%community_link%'), false)
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname = 'get_app_setting'
) x
order by check_name;

-- =====================================================================================
-- 7. THE PARTS THE REPO IS MISSING: the live definitions of the group, admin, ban and
--    trigger functions. Paste this single cell back. (Long output: that is expected.)
-- =====================================================================================
select string_agg(pg_get_functiondef(p.oid), E'\n\n' order by p.proname, p.oid) as dump
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prokind = 'f'
   and p.proname in (
     '_is_admin','_is_banned','_is_verified','_place_user_in_group','_place_on_verify',
     '_grant_next_group','_group_size','_repost_cap','_setting_int','_ref_match',
     '_catch_up_group_grants','_verify_repost','get_ban_status','admin_set_banned',
     'admin_get_settings','admin_set_setting','admin_send_notification','admin_add_user_to_group',
     'admin_verify_reposts','admin_set_repost','notify_repost_status_change',
     '_users_set_referrer','_users_link_referees','_on_user_banned','_block_signup_when_closed_v2',
     'trigger_send_push_notification','send_daily_repost_reminder','_resolve_referrer'
   );

-- =====================================================================================
-- 8. DATA HEALTH: counts that should be zero (or are informational). Read the labels.
-- =====================================================================================
select
  (select count(*) from public.users where phone !~ '^0[0-9]{10}$')                        as bad_phone_format,
  (select count(*) from (select phone from public.users group by phone having count(*) > 1) d) as duplicate_phones,
  (select count(*) from public.users where secret is null)                                 as users_without_secret,
  (select count(*) from public.users where referred_by is not null and referrer_id is null) as referred_by_matching_nobody,
  (select count(*) from public.contact_group_members m
     join public.users u on u.id = m.user_id
    where m.joined_via = 'signup'
      and not exists (select 1 from public.daily_reposts d
                       where d.user_id = u.id and d.status = 'verified'))           as unverified_in_default_group,
  (select count(*) from public.contact_groups g
    where g.member_count <> (select count(*) from public.contact_group_members m
                              where m.group_id = g.id and m.joined_via = 'signup')) as member_count_mismatch,
  (select count(*) from public.contact_group_members m
     join public.users u on u.id = m.user_id
    where coalesce(u.is_banned, false))                                             as banned_members_in_groups_by_design,
  (select count(*) from public.notifications
    where is_sent = false and created_at < now() - interval '1 day')                as unsent_notifications_over_1_day,
  (select count(*) from public.users where coalesce(is_banned, false) and fcm_token is not null) as banned_users_with_push_token;

-- 8b. Groups with more default (signup) members than group_size. Expected: no rows.
select g.id, g.group_number, count(m.id) as signup_members
  from public.contact_groups g
  join public.contact_group_members m on m.group_id = g.id and m.joined_via = 'signup'
 group by g.id, g.group_number
having count(m.id) > coalesce((select value::int from public.app_settings where key = 'group_size'), 3);

-- =====================================================================================
-- 9. ACCOUNTS: how people can sign in to Supabase Auth (the admin page uses it).
--    Expected: only the admin account. If you see many 'email' users you did not create,
--    public sign-up is open, and _is_admin() must check the email strictly.
-- =====================================================================================
select coalesce(raw_app_meta_data->>'provider', '(none)') as provider,
       count(*) as accounts
  from auth.users
 group by 1
 order by 2 desc;

-- =====================================================================================
-- 10. SETTINGS SNAPSHOT: the live app_settings values. Confirm require_android_id.
--     (The admin account number, pay account and admin phone are shown here; this is your
--     own database, and nothing is changed.)
-- =====================================================================================
select key, left(value, 80) as value
  from public.app_settings
 order by key;
