-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- ONE call per screen. Run once in Supabase > SQL Editor BEFORE installing the new app.
-- Safe to run again. Only COMPOSES existing functions (no rule is copied).
--
--  1. get_app_bundle     Home/Repost: adds the pending-sheet/pay settings and today's verify
--                        status, so the verify sheet and Pay screen make 0 extra calls
--                        (was 7 get_app_setting + 1 get_today_verify_status).
--  2. get_my_referrals_full  Referral list + pending flag in 1 call (was 2).
--  3. open_notifications Notifications list + mark-as-read in 1 call (was 2).

-- 1 -------------------------------------------------------------------------------
drop function if exists public.get_app_bundle(uuid, text, text, integer);

create function public.get_app_bundle(
  p_user_id       uuid,
  p_phone         text,
  p_android_id    text,
  p_current_build integer
)
returns table (bundle jsonb)
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_home   record;
  v_ban    record;
  v_upd    record;
  v_unread boolean;
  v_link   text;
  v_today  text;
  v_verify text;
  v_sett   jsonb;
begin
  select * into v_home from public.get_home(p_user_id);
  select * into v_ban  from public.get_ban_status(p_user_id, p_phone, p_android_id);
  select * into v_upd  from public.get_app_update(coalesce(p_current_build, 0));

  select exists (
    select 1
      from notifications n
      left join notification_reads r
        on r.notification_id = n.id and r.user_id = p_user_id
     where (n.user_id = p_user_id or n.user_id is null)
       and r.notification_id is null
  ) into v_unread;

  select s.value into v_link from app_settings s where s.key = 'community_link';

  select t.status into v_today  from public.get_today_repost_status(p_user_id) t limit 1;
  select t.status into v_verify from public.get_today_verify_status(p_user_id) t limit 1;

  select coalesce(jsonb_object_agg(s.key, s.value), '{}'::jsonb) into v_sett
    from app_settings s
   where s.key in ('pending_viewers', 'pending_min_views', 'pending_pay_amount',
                   'pending_verify_hours', 'pay_bank_name', 'pay_account_name',
                   'pay_account_number');

  return query select jsonb_build_object(
    'home', case when v_home.status is null then null else jsonb_build_object(
      'status',           v_home.status,
      'free_current',     v_home.free_current,
      'free_max',         v_home.free_max,
      'extra_current',    v_home.extra_current,
      'extra_max',        v_home.extra_max,
      'referral_count',   v_home.referral_count,
      'verified_reposts', v_home.verified_reposts
    ) end,
    'has_unread',          coalesce(v_unread, false),
    'banned',              coalesce(v_ban.banned, false),
    'ban_reason',          v_ban.reason,
    'update', case when v_upd.latest_build is null then null else jsonb_build_object(
      'update_available', v_upd.update_available,
      'force_update',     v_upd.force_update,
      'latest_build',     v_upd.latest_build,
      'download_url',     v_upd.download_url,
      'notes',            v_upd.notes
    ) end,
    'community_link',      v_link,
    'today_repost_status', v_today,
    'today_verify_status', v_verify,
    'settings',            v_sett
  );
end;
$function$;

revoke all on function public.get_app_bundle(uuid, text, text, integer) from public;
grant execute on function public.get_app_bundle(uuid, text, text, integer) to anon, authenticated;

-- 2 -------------------------------------------------------------------------------
-- Same access rules as get_my_referrals (it is the source), plus the verified flag
-- (same rule as get_my_referrals_status).
create or replace function public.get_my_referrals_full(p_user_id uuid, p_target_user_id uuid default null)
 returns table(user_id uuid, username text, phone text, created_at timestamptz,
               invited_count integer, is_verified boolean)
 language sql stable security definer set search_path to 'public'
as $function$
  select r.user_id::uuid, r.username::text, r.phone::text, r.created_at::timestamptz,
         r.invited_count::integer,
         exists (select 1 from public.daily_reposts d
                  where d.user_id = r.user_id and d.status = 'verified')
    from public.get_my_referrals(p_user_id, p_target_user_id) r;
$function$;

grant execute on function public.get_my_referrals_full(uuid, uuid) to anon, authenticated;

-- 3 -------------------------------------------------------------------------------
-- Returns the list exactly as fetch_notifications does (is_read as it was BEFORE this
-- visit), then marks everything read, all in one call.
create or replace function public.open_notifications(p_user_id uuid)
returns table (
  id uuid, title text, body text, created_at timestamptz,
  is_read boolean, action text, target text
)
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
  return query select f.id, f.title, f.body, f.created_at, f.is_read, f.action, f.target
                 from public.fetch_notifications(p_user_id) f;
  perform public.mark_notifications_read(p_user_id);
end;
$function$;

grant execute on function public.open_notifications(uuid) to anon, authenticated;

notify pgrst, 'reload schema';
