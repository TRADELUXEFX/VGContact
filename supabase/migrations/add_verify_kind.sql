-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- add_verify_kind.sql
-- Splits "verification" (first task, pending sheet) from "repost" (daily task, verified users).
--   * daily_reposts.kind = 'verify' | 'repost'. One row per user, per day, PER KIND.
--   * A 'verify' row, once approved, only verifies the user (the existing trigger places
--     them in the next group). It adds NO extra group, NO streak, NO leaderboard score,
--     and does not use up the daily repost or the repost cap.
--   * Only verified users can submit a 'repost'. Only unverified users can submit a 'verify'.
-- Run the whole file once in the Supabase SQL editor. Then release the app (versionCode 55).

-- 1. kind column --------------------------------------------------------------
alter table public.daily_reposts add column if not exists kind text not null default 'repost';
alter table public.daily_reposts drop constraint if exists daily_reposts_kind_check;
alter table public.daily_reposts add constraint daily_reposts_kind_check check (kind in ('verify','repost'));

-- Old rows of users who are not verified yet were verification attempts.
update public.daily_reposts d set kind = 'verify'
 where d.status in ('pending','rejected')
   and not exists (select 1 from public.daily_reposts x where x.user_id = d.user_id and x.status = 'verified');

-- 2. one row per user + day + kind ----------------------------------------------
do $$
declare r record;
begin
  for r in
    select c.conname
      from pg_constraint c
     where c.conrelid = 'public.daily_reposts'::regclass and c.contype = 'u'
       and (select array_agg(a.attname::text order by a.attname)
              from unnest(c.conkey) k
              join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k)
           = array['repost_date','user_id']
  loop
    execute format('alter table public.daily_reposts drop constraint %I', r.conname);
  end loop;
  for r in
    select i.relname
      from pg_index x join pg_class i on i.oid = x.indexrelid
     where x.indrelid = 'public.daily_reposts'::regclass and x.indisunique and not x.indisprimary
       and (select array_agg(a.attname::text order by a.attname)
              from unnest(x.indkey::int2[]) k
              join pg_attribute a on a.attrelid = x.indrelid and a.attnum = k)
           = array['repost_date','user_id']
  loop
    execute format('drop index if exists public.%I', r.relname);
  end loop;
end $$;
create unique index if not exists daily_reposts_user_date_kind_uq
  on public.daily_reposts (user_id, repost_date, kind);

-- 3. submit -----------------------------------------------------------------------
drop function if exists public.submit_daily_repost(uuid);
create or replace function public.submit_daily_repost(p_user_id uuid, p_kind text default 'repost')
 returns table(success boolean, message text)
 language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  v_phone text; v_rows integer; v_done integer; v_verified boolean;
begin
  if p_kind not in ('verify','repost') then
    return query select false, 'BAD_KIND'; return;
  end if;
  select u.phone into v_phone from users u where u.id = p_user_id;
  if not found then
    return query select false, 'USER_NOT_FOUND'; return;
  end if;

  select exists (select 1 from daily_reposts d where d.user_id = p_user_id and d.status = 'verified')
    into v_verified;

  if p_kind = 'verify' and v_verified then
    return query select false, 'ALREADY_VERIFIED'; return;
  end if;
  if p_kind = 'repost' and not v_verified then
    return query select false, 'NOT_VERIFIED'; return;
  end if;

  if p_kind = 'repost' then
    select count(*) into v_done from daily_reposts d
     where d.user_id = p_user_id and d.status = 'verified' and d.kind = 'repost';
    if v_done >= public._repost_cap() then
      return query select false, 'REPOST_LIMIT_REACHED'; return;
    end if;
  end if;

  insert into daily_reposts (user_id, repost_date, phone, status, kind)
  values (p_user_id, (now() at time zone 'utc')::date, v_phone, 'pending', p_kind)
  on conflict (user_id, repost_date, kind) do nothing;
  get diagnostics v_rows = row_count;

  if v_rows = 0 then
    return query select false, 'ALREADY_REPOSTED_TODAY';
  else
    return query select true, 'OK';
  end if;
end;
$function$;

-- 4. today's status (repost and verify are separate) ------------------------------
create or replace function public.get_today_repost_status(p_user_id uuid)
 returns table(status text) language sql security definer set search_path to 'public'
as $function$
  select d.status from daily_reposts d
   where d.user_id = p_user_id and d.kind = 'repost'
     and d.repost_date = (now() at time zone 'utc')::date;
$function$;

create or replace function public.get_today_verify_status(p_user_id uuid)
 returns table(status text) language sql security definer set search_path to 'public'
as $function$
  select d.status from daily_reposts d
   where d.user_id = p_user_id and d.kind = 'verify'
     and d.repost_date = (now() at time zone 'utc')::date;
$function$;

-- 5. approve ----------------------------------------------------------------------
-- With no kind given it takes that day's pending row, 'verify' first, then 'repost'.
drop function if exists public._verify_repost(uuid, date);
create or replace function public._verify_repost(p_user_id uuid, p_date date, p_kind text default null)
 returns text language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare v_kind text; v_count int;
begin
  select d.kind into v_kind from daily_reposts d
   where d.user_id = p_user_id and d.repost_date = p_date and d.status = 'pending'
     and (p_kind is null or d.kind = p_kind)
   order by (d.kind = 'verify') desc limit 1;
  if v_kind is null then return 'no_pending_repost'; end if;

  if v_kind = 'repost' then
    select count(*) into v_count from daily_reposts d
     where d.user_id = p_user_id and d.status = 'verified' and d.kind = 'repost';
    if v_count >= public._repost_cap() then return 'cap_reached'; end if;
  end if;

  update daily_reposts d set status = 'verified', verified_at = now()
   where d.user_id = p_user_id and d.repost_date = p_date and d.kind = v_kind and d.status = 'pending';
  -- The place_on_repost_verified trigger puts a newly verified user in their group.
  -- Only a real repost earns an extra group.
  if v_kind = 'repost' then perform public._grant_next_group(p_user_id); end if;
  return 'verified';
end;
$function$;

drop function if exists public.admin_set_repost(uuid, date, text);
create or replace function public.admin_set_repost(p_user_id uuid, p_date date, p_status text, p_kind text default null)
 returns void language plpgsql security definer set search_path to 'public'
as $function$
declare v_result text;
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  if p_status not in ('verified','rejected') then raise exception 'bad status'; end if;

  if p_status = 'verified' then
    v_result := public._verify_repost(p_user_id, p_date, p_kind);
    if v_result = 'cap_reached' then raise exception 'CAP_REACHED'; end if;
  else
    update daily_reposts set status = 'rejected'
     where id = (select d.id from daily_reposts d
                  where d.user_id = p_user_id and d.repost_date = p_date and d.status = 'pending'
                    and (p_kind is null or d.kind = p_kind)
                  order by (d.kind = 'verify') desc limit 1);
  end if;
end;
$function$;

create or replace function public.admin_verify_reposts(p_usernames text[], p_date date default null::date, p_apply boolean default false)
 returns table(username text, result text)
 language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  v_date date := coalesce(p_date, (now() at time zone 'utc')::date);
  v_name text; v_id uuid; v_res text; v_cnt int; v_kind text;
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  for v_name in select distinct btrim(n) from unnest(p_usernames) as n where btrim(n) <> '' loop
    v_id := null;
    select u.id into v_id from users u where lower(u.username) = lower(v_name) limit 1;
    if v_id is null then
      username := v_name; result := 'not_found'; return next; continue;
    end if;
    if p_apply then
      v_res := public._verify_repost(v_id, v_date);
    else
      select d.kind into v_kind from daily_reposts d
       where d.user_id = v_id and d.repost_date = v_date and d.status = 'pending'
       order by (d.kind = 'verify') desc limit 1;
      if v_kind is null then
        v_res := 'no_pending_repost';
      elsif v_kind = 'repost' then
        select count(*) into v_cnt from daily_reposts d
         where d.user_id = v_id and d.status = 'verified' and d.kind = 'repost';
        v_res := case when v_cnt >= public._repost_cap() then 'cap_reached' else 'will_verify' end;
      else
        v_res := 'will_verify';
      end if;
    end if;
    username := v_name; result := v_res; return next;
  end loop;
end;
$function$;

-- Admin list now also says which task each row is ('verify' or 'repost').
drop function if exists public.admin_pending_reposts();
create or replace function public.admin_pending_reposts()
 returns table(user_id uuid, username text, phone text, repost_date date, key_balance integer, kind text)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  return query
    select d.user_id, u.username, u.phone, d.repost_date, coalesce(u.key_balance,0), d.kind
    from daily_reposts d join users u on u.id = d.user_id
    where d.status = 'pending' order by d.repost_date desc, u.username;
end $function$;

-- 6. notification text ----------------------------------------------------------------
create or replace function public.notify_repost_status_change()
 returns trigger language plpgsql security definer set search_path to 'public'
as $function$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NEW.status = 'verified' THEN
      INSERT INTO notifications (user_id, title, body, action)
      VALUES (NEW.user_id,
              case when NEW.kind = 'verify' then 'Account verified' else 'Repost verified' end,
              case when NEW.kind = 'verify' then 'You are verified. Your contacts are being added.'
                   else 'Your repost was verified. Your contacts are being added.' end,
              'open_repost');
    ELSIF NEW.status = 'rejected' THEN
      INSERT INTO notifications (user_id, title, body, action)
      VALUES (NEW.user_id,
              case when NEW.kind = 'verify' then 'Verification rejected' else 'Repost rejected' end,
              'Your post was rejected. You can try again tomorrow.',
              'open_repost');
    END IF;
  END IF;
  RETURN NEW;
END;
$function$;

-- 7. Home and leaderboards: verification is not a repost ------------------------------
create or replace function public.get_home(p_user_id uuid)
 returns table(status text, free_current integer, free_max integer, extra_current integer, extra_max integer, referral_count integer, verified_reposts integer)
 language plpgsql stable security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  v_size int := public._group_size();
  v_default uuid; v_fc int; v_ec int; v_em int; v_ref int; v_vr int; v_any boolean;
begin
  if not exists (select 1 from users u where u.id = p_user_id) then return; end if;

  select m.group_id into v_default
    from contact_group_members m
   where m.user_id = p_user_id and m.joined_via = 'signup'
   order by m.created_at limit 1;

  select count(*) into v_fc
    from contact_group_members m
   where m.group_id = v_default and m.joined_via = 'signup';

  with mine as (
    select m.group_id from contact_group_members m
     where m.user_id = p_user_id and m.group_id is distinct from v_default
  ), sizes as (
    select m.group_id, count(*)::int as c
      from contact_group_members m join mine on mine.group_id = m.group_id
     group by m.group_id
  )
  select coalesce(sum(c), 0)::int, coalesce(sum(greatest(v_size, c)), 0)::int
    into v_ec, v_em from sizes;

  v_ref := public._count_referrals(p_user_id);

  select exists (select 1 from daily_reposts d where d.user_id = p_user_id and d.status = 'verified') into v_any;
  select count(*)::int into v_vr
    from daily_reposts d where d.user_id = p_user_id and d.status = 'verified' and d.kind = 'repost';

  return query select
    case when v_any then 'verified' else 'pending' end,
    v_fc, v_size, v_ec, v_em, v_ref, v_vr;
end;
$function$;

create or replace function public.get_leaderboard(p_user_id uuid, p_metric text default 'reposts'::text, p_period text default 'week'::text, p_limit integer default 20)
 returns table(rank bigint, user_id uuid, username text, score integer, is_me boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
declare v_since date;
begin
  if p_metric <> 'reposts' then raise exception 'INVALID_METRIC'; end if;
  p_limit := least(greatest(coalesce(p_limit, 20), 1), 50);
  v_since := case p_period
    when 'week'  then (now() at time zone 'utc')::date - 6
    when 'month' then (now() at time zone 'utc')::date - 29
    else date '1970-01-01' end;
  return query
  with scores as (
    select u.id as uid, u.username as uname,
           coalesce((select count(*)::int from public.daily_reposts d
                      where d.user_id = u.id and d.status = 'verified' and d.kind = 'repost'
                        and d.repost_date >= v_since), 0) as pts
      from public.users u
  ), ranked as (
    select rank() over (order by pts desc) as rnk, uid, uname, pts from scores where pts > 0
  )
  select r.rnk, case when r.uid = p_user_id then r.uid else null end, r.uname, r.pts, (r.uid = p_user_id)
    from ranked r
   where r.rnk <= p_limit or r.uid = p_user_id
   order by r.rnk, r.uname;
end;
$function$;

create or replace function public.get_repost_leaderboard_phone(p_user_id uuid)
 returns table(rank integer, username text, phone text, score integer, is_me boolean)
 language sql stable security definer set search_path to 'public'
as $function$
  with s as (
    select u.id as uid, u.username as un, u.phone as ph, count(d.id)::int as sc
      from users u
      join daily_reposts d on d.user_id = u.id and d.status = 'verified' and d.kind = 'repost'
     where not coalesce(u.is_banned, false) or u.id = p_user_id
     group by u.id, u.username, u.phone
  ), r as (
    select uid, un, ph, sc,
           rank() over (order by sc desc)::int as rk,
           row_number() over (order by sc desc, un) as rn
      from s
  )
  select rk, un, ph, sc, (uid = p_user_id) from r
   where rn <= 50 or uid = p_user_id order by rk, un;
$function$;

-- 8. Daily catch-up job: only real reposts earn extra groups ------------------------------
create or replace function public._catch_up_group_grants()
 returns integer language plpgsql security definer set search_path to 'public'
as $function$
declare
  r record; i integer; attempted integer := 0;
begin
  for r in
    select u.id as user_id,
           least(v.cnt, public._repost_cap()) - coalesce(a.cnt, 0) as missing
      from users u
      join (select user_id, count(*) cnt from daily_reposts
             where status = 'verified' and kind = 'repost' group by user_id) v on v.user_id = u.id
      left join (select user_id, count(*) cnt from contact_group_members
                  where joined_via = 'added' group by user_id) a on a.user_id = u.id
     where coalesce(u.is_banned, false) = false
       and least(v.cnt, public._repost_cap()) > coalesce(a.cnt, 0)
  loop
    for i in 1..r.missing loop
      perform public._grant_next_group(r.user_id);
      attempted := attempted + 1;
    end loop;
  end loop;
  return attempted;
end;
$function$;

-- 9. Admin user search: "reposts" = real reposts only, plus a "verified" flag --------------
-- (the admin page shows "Pending" for users who are not verified yet)
drop function if exists public.admin_find_users(text);
create or replace function public.admin_find_users(p_q text)
 returns table(id uuid, username text, phone text, key_balance integer, is_banned boolean, reposts integer, referrals integer, created_at timestamp with time zone, verified boolean)
 language plpgsql security definer set search_path to 'public'
as $function$
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  return query
    select u.id, u.username, u.phone, coalesce(u.key_balance,0), u.is_banned,
      (select count(*)::int from daily_reposts d where d.user_id = u.id and d.status = 'verified' and d.kind = 'repost'),
      public._count_referrals(u.id),
      u.created_at,
      public._is_verified(u.id)
    from users u
    where u.username ilike '%'||p_q||'%' or u.phone ilike '%'||p_q||'%'
    order by u.created_at desc limit 30;
end $function$;
revoke all on function public.admin_find_users(text) from public, anon;
grant execute on function public.admin_find_users(text) to authenticated, service_role;

-- 10. Reminders -------------------------------------------------------------------------------
-- 08:00 "repost today's status" goes only to verified users who have not reposted today.
create or replace function public.send_daily_repost_reminder()
 returns integer language plpgsql security definer set search_path to 'public'
as $function$
declare v_count int;
begin
  if coalesce((select lower(btrim(value)) from app_settings where key = 'daily_reminder_enabled'), 'true') = 'false' then
    return 0;
  end if;
  with inserted as (
    insert into notifications (user_id, title, body, action)
    select u.id, 'Repost today''s status',
           'Tap to open the admin''s WhatsApp and repost today''s status.',
           'open_whatsapp_repost'
      from users u
     where u.fcm_token is not null
       and public._is_verified(u.id)
       and not exists (select 1 from daily_reposts d where d.user_id = u.id and d.kind = 'repost' and d.repost_date = current_date)
       and not exists (select 1 from notifications n where n.user_id = u.id
                        and n.action = 'open_whatsapp_repost' and n.created_at::date = current_date)
    returning 1)
  select count(*) into v_count from inserted;
  return v_count;
end $function$;

-- 16:00 nudge for users who are still not verified: new wording (the first task is the invite post).
create or replace function public.send_pending_verify_nudge()
 returns integer language plpgsql security definer set search_path to 'public'
as $function$
declare v_hours integer; v_count integer;
begin
  v_hours := coalesce(
    (select nullif(btrim(value), '')::int from app_settings where key = 'pending_nudge_after_hours'), 24);

  insert into notifications (user_id, title, body, action)
  select u.id,
         'Claim your free viewers',
         'Post your invite on your status and send us the screenshot to get verified and start receiving viewers.',
         'open_repost'
    from users u
   where u.fcm_token is not null
     and coalesce(u.is_banned, false) = false
     and not public._is_verified(u.id)
     and u.created_at < now() - make_interval(hours => v_hours)
     and u.created_at > now() - interval '30 days'
     and not exists (
           select 1 from notifications n
            where n.user_id = u.id and n.title = 'Claim your free viewers'
              and n.created_at > now() - interval '3 days');
  get diagnostics v_count = row_count;
  return v_count;
end;
$function$;

-- 11. Permissions: the dropped-and-recreated functions get back exactly what they had ----------
revoke all on function public.submit_daily_repost(uuid, text) from public, anon, authenticated;
grant execute on function public.submit_daily_repost(uuid, text) to anon, service_role;

revoke all on function public._verify_repost(uuid, date, text) from public, anon, authenticated;
grant execute on function public._verify_repost(uuid, date, text) to service_role;

revoke all on function public.admin_set_repost(uuid, date, text, text) from public, anon;
grant execute on function public.admin_set_repost(uuid, date, text, text) to authenticated, service_role;

revoke all on function public.admin_pending_reposts() from public, anon;
grant execute on function public.admin_pending_reposts() to authenticated, service_role;

grant execute on function public.get_today_verify_status(uuid) to anon, authenticated, service_role;

-- 12. Check after running: (a) the old unique on (user_id, repost_date) must be gone,
--    (b) only the new one should be listed.
select indexname, indexdef from pg_indexes
 where schemaname = 'public' and tablename = 'daily_reposts';

select p.proname, pg_get_function_identity_arguments(p.oid) as args, p.proacl
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and p.proname in ('submit_daily_repost','_verify_repost','admin_set_repost','admin_pending_reposts','admin_verify_reposts');
