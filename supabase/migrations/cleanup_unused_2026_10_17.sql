-- cleanup_unused_2026_10_17.sql
-- Dropped 7 functions nothing used (checked on the live database 2026-10-17) and removed 4 settings
-- rows nothing read. ALREADY RUN on the live database on 2026-10-17. Do not re-run.
drop function if exists public._get_sync_contacts_core(uuid);
drop function if exists public._is_banned(uuid);
drop function if exists public._ref_match(text, text, text);
drop function if exists public.get_my_referrals_status(uuid, uuid);
drop function if exists public.send_stalled_sync_nudge();
drop function if exists public.admin_add_user_to_group(uuid, integer);
drop function if exists public.admin_verify_reposts(text[], date, boolean);
delete from public.app_settings where key in ('update_apk_url', 'update_build', 'update_min_build', 'update_notes');
