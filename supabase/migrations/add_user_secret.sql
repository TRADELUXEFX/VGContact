-- add_user_secret.sql
-- Every account now has a private SECRET. Knowing a user's id is no longer enough to act as them.
--
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run. Safe to run more than once.
-- Then install the new app build (versionCode 74). Old builds stop working on purpose.
--
-- What it does, in plain words:
--   1. users.secret: a long random code made at sign-up / login and kept on the phone.
--   2. _auth_ok(user, secret): the one check every app function now makes first.
--   3. register_account / login_account: the new sign-up and login. They return the secret.
--      Sign-up also refuses official-sounding names (admin, support, vgcontact, vgc ...).
--   4. Every function the app uses gets a new version that takes p_secret, checks it, and only then
--      calls the existing function (so no rule is copied). Wrong secret = error UNAUTHORIZED.
--   5. The OLD versions (no secret) are locked so the app key can no longer call them.
--   6. get_app_setting only answers for a short list of safe keys.
--
-- Not changed: get_ban_status, get_app_update (needed before login, nothing private in them),
-- and all admin_* functions (they already check the admin).

-- 1 -------------------------------------------------------------------------------
alter table public.users add column if not exists secret text;

-- 2 -------------------------------------------------------------------------------
create or replace function public._auth_ok(p_user_id uuid, p_secret text)
 returns boolean
 language sql
 stable
 security definer
 set search_path to 'public'
as $function$
  select coalesce(
    (select u.secret is not null and u.secret = p_secret
       from users u where u.id = p_user_id),
    false);
$function$;

-- Names nobody may pick: they would look official on other people's phones, or collide with the
-- "VGC<number>" tag the app uses to recognise its own contacts. Letters only, any capitals.
create or replace function public._name_reserved(p_name text)
 returns boolean
 language sql
 immutable
 set search_path to 'public'
as $function$
  select regexp_replace(lower(coalesce(p_name, '')), '[^a-z]', '', 'g')
         ~ '(vgc|vgkontact|vgcontact|admin|support|official|moderator|customercare|helpdesk)';
$function$;

-- 3 -------------------------------------------------------------------------------
create or replace function public.register_account(
  p_android_id text, p_username text, p_phone text, p_referred_by text default null)
 returns table(id uuid, username text, phone text, referred_by text,
               created_at timestamp with time zone, key_balance integer, secret text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  r record;
  v_secret text;
begin
  if public._name_reserved(p_username) then
    raise exception 'RESERVED_NAME';
  end if;

  -- The existing function keeps doing the real work (ban check, android id rule, numbering).
  select * into r from public.register_or_fetch_user(p_android_id, p_username, p_phone, p_referred_by);

  update users
     set secret = coalesce(users.secret,
                           replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''))
   where users.id = r.id
  returning users.secret into v_secret;

  return query
    select r.id, r.username, r.phone, r.referred_by, r.created_at, r.key_balance, v_secret;
end;
$function$;

create or replace function public.login_account(p_phone text, p_android_id text)
 returns table(account_exists boolean, device_matches boolean, id uuid, username text,
               phone text, referred_by text, created_at timestamp with time zone,
               key_balance integer, secret text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  r record;
  v_secret text := null;
begin
  select * into r from public.login_by_phone(p_phone, p_android_id);

  if r.account_exists and r.device_matches then
    update users
       set secret = coalesce(users.secret,
                             replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''))
     where users.id = r.id
    returning users.secret into v_secret;
  end if;

  return query
    select r.account_exists, r.device_matches, r.id, r.username, r.phone,
           r.referred_by, r.created_at, r.key_balance, v_secret;
end;
$function$;

-- 4 -------------------------------------------------------------------------------
-- Each new function = check the secret, then call the existing one.

create or replace function public.get_app_bundle(p_user_id uuid, p_secret text, p_phone text, p_android_id text, p_current_build integer)
 returns table(bundle jsonb)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_app_bundle(p_user_id, p_phone, p_android_id, p_current_build);
end;
$function$;

create or replace function public.get_home(p_user_id uuid, p_secret text)
 returns table(status text, free_current integer, free_max integer, extra_current integer, extra_max integer, referral_count integer, verified_reposts integer)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_home(p_user_id);
end;
$function$;

create or replace function public.get_today_repost_status(p_user_id uuid, p_secret text)
 returns table(status text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_today_repost_status(p_user_id);
end;
$function$;

create or replace function public.get_today_verify_status(p_user_id uuid, p_secret text)
 returns table(status text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_today_verify_status(p_user_id);
end;
$function$;

create or replace function public.get_sync_contacts(p_user_id uuid, p_secret text)
 returns table(phone text, display_name text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_sync_contacts(p_user_id);
end;
$function$;

create or replace function public.get_my_referrals_full(p_user_id uuid, p_secret text, p_target_user_id uuid)
 returns table(user_id uuid, username text, phone text, created_at timestamp with time zone, invited_count integer, is_verified boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_my_referrals_full(p_user_id, p_target_user_id);
end;
$function$;

create or replace function public.get_referral_leaderboard_phone(p_user_id uuid, p_secret text)
 returns table(username text, phone text, referral_count integer, is_me boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_referral_leaderboard_phone(p_user_id);
end;
$function$;

create or replace function public.get_referral_leaderboard(p_user_id uuid, p_secret text)
 returns table(username text, referral_count integer, is_me boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_referral_leaderboard(p_user_id);
end;
$function$;

create or replace function public.get_repost_leaderboard_phone(p_user_id uuid, p_secret text)
 returns table(rank integer, username text, phone text, score integer, is_me boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_repost_leaderboard_phone(p_user_id);
end;
$function$;

create or replace function public.get_leaderboard(p_user_id uuid, p_secret text, p_metric text, p_period text, p_limit integer)
 returns table(rank bigint, user_id uuid, username text, score integer, is_me boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_leaderboard(p_user_id, p_metric, p_period, p_limit);
end;
$function$;

create or replace function public.open_notifications(p_user_id uuid, p_secret text)
 returns table(id uuid, title text, body text, created_at timestamp with time zone, is_read boolean, action text, target text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.open_notifications(p_user_id);
end;
$function$;

create or replace function public.mark_notifications_read(p_user_id uuid, p_secret text)
 returns void
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  perform public.mark_notifications_read(p_user_id);
end;
$function$;

create or replace function public.record_notification_delivered(p_user_id uuid, p_secret text, p_notification_id uuid)
 returns void
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  perform public.record_notification_delivered(p_user_id, p_notification_id);
end;
$function$;

create or replace function public.record_notification_opened(p_user_id uuid, p_secret text, p_notification_id uuid)
 returns void
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  perform public.record_notification_opened(p_user_id, p_notification_id);
end;
$function$;

create or replace function public.report_notifications_enabled(p_user_id uuid, p_secret text, p_enabled boolean)
 returns void
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  perform public.report_notifications_enabled(p_user_id, p_enabled);
end;
$function$;

create or replace function public.save_fcm_token(p_user_id uuid, p_secret text, p_token text)
 returns void
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  perform public.save_fcm_token(p_user_id, p_token);
end;
$function$;

create or replace function public.record_sync(p_user_id uuid, p_secret text)
 returns void
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  perform public.record_sync(p_user_id);
end;
$function$;

create or replace function public.get_my_profile(p_user_id uuid, p_secret text)
 returns table(created_at timestamp with time zone, referred_by text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_my_profile(p_user_id);
end;
$function$;

create or replace function public.get_my_referrer(p_user_id uuid, p_secret text)
 returns table(username text, phone text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.get_my_referrer(p_user_id);
end;
$function$;

create or replace function public.submit_daily_repost(p_user_id uuid, p_secret text, p_kind text)
 returns table(success boolean, message text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select * from public.submit_daily_repost(p_user_id, p_kind);
end;
$function$;

-- 5 -------------------------------------------------------------------------------
-- Lock every OLD (no secret) version, plus the sign-up/login the app no longer calls,
-- plus functions the app never calls directly but that were open.
do $$
declare r record;
begin
  for r in
    select p.oid::regprocedure as sig
      from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname in (
         'get_app_bundle','get_home','get_today_repost_status','get_today_verify_status',
         'get_sync_contacts','get_my_referrals_full','get_my_referrals','get_my_referrals_status',
         'get_referral_leaderboard_phone','get_referral_leaderboard',
         'get_repost_leaderboard_phone','get_leaderboard',
         'open_notifications','fetch_notifications','mark_notifications_read',
         'record_notification_delivered','record_notification_opened',
         'report_notifications_enabled','save_fcm_token','record_sync',
         'get_my_profile','get_my_referrer','submit_daily_repost',
         'register_or_fetch_user','login_by_phone')
       and not ('p_secret' = any (coalesce(p.proargnames, '{}'::text[])))
  loop
    execute format('revoke all on function %s from public, anon, authenticated', r.sig);
  end loop;
end $$;

-- Open the new versions to the app (the anon key) and nobody else by default.
do $$
declare r record;
begin
  for r in
    select p.oid::regprocedure as sig
      from pg_proc p join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and ( 'p_secret' = any (coalesce(p.proargnames, '{}'::text[]))
             or p.proname in ('register_account','login_account') )
  loop
    execute format('revoke all on function %s from public', r.sig);
    execute format('grant execute on function %s to anon, authenticated', r.sig);
  end loop;
end $$;

-- The helpers stay internal.
revoke all on function public._auth_ok(uuid, text) from public, anon, authenticated;
revoke all on function public._name_reserved(text) from public, anon, authenticated;

-- 6 -------------------------------------------------------------------------------
-- Settings: only these keys can be read with the app key.
create or replace function public.get_app_setting(p_key text)
 returns table(value text)
 language sql
 security definer
 set search_path to 'public'
as $function$
  select s.value from app_settings s
   where s.key = p_key
     and p_key in ('community_link', 'update_url');
$function$;

notify pgrst, 'reload schema';

-- 7. CHECK (run after). The first list should show ONLY functions with p_secret (plus
--    register_account, login_account, get_ban_status, get_app_update, get_app_setting).
select p.proname, pg_get_function_identity_arguments(p.oid) as args
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and has_function_privilege('anon', p.oid, 'execute')
 order by p.proname;
