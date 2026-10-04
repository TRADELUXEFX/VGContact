-- use_nigeria_time.sql
-- A "day" for reposts, today's status, weekly/monthly leaderboards and the daily reminder is now a
-- NIGERIAN day (Africa/Lagos, UTC+1, no daylight saving). Before, everything used UTC, so the
-- "daily" repost reset at 1:00 am Nigerian time instead of midnight.
--
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run. Safe to run more than once.
-- No app update is needed. Old rows keep their stored date (only matters for the changeover day).
--
-- What changes:
--   _today_ng()                       new helper: today's date in Nigeria
--   daily_reposts.repost_date         default is now the Nigerian date
--   submit_daily_repost               stamps the Nigerian date
--   get_today_repost_status / _verify_status   look for today's Nigerian date
--   get_leaderboard                   "this week" / "this month" count Nigerian days
--   admin_verify_reposts              default date = Nigerian today
--   send_daily_repost_reminder        "already reposted today?" uses the Nigerian date
--   admin_notification_stats          groups notifications by Nigerian day

-- 1. Helper ----------------------------------------------------------------------------
create or replace function public._today_ng()
 returns date
 language sql
 stable
 set search_path to 'public'
as $function$
  select (now() at time zone 'Africa/Lagos')::date;
$function$;

revoke all on function public._today_ng() from public, anon, authenticated;

-- 2. Column default ------------------------------------------------------------------------
alter table public.daily_reposts
  alter column repost_date set default ((now() at time zone 'Africa/Lagos'))::date;

-- 3. Today's status (the app) ----------------------------------------------------------------
create or replace function public.get_today_repost_status(p_user_id uuid)
 returns table(status text)
 language sql
 security definer
 set search_path to 'public'
as $function$
  select d.status from daily_reposts d
   where d.user_id = p_user_id and d.kind = 'repost'
     and d.repost_date = public._today_ng();
$function$;

create or replace function public.get_today_verify_status(p_user_id uuid)
 returns table(status text)
 language sql
 security definer
 set search_path to 'public'
as $function$
  select d.status from daily_reposts d
   where d.user_id = p_user_id and d.kind = 'verify'
     and d.repost_date = public._today_ng();
$function$;

-- 4. Submitting a repost ---------------------------------------------------------------------
create or replace function public.submit_daily_repost(p_user_id uuid, p_kind text default 'repost'::text)
 returns table(success boolean, message text)
 language plpgsql
 security definer
 set search_path to 'public'
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
  values (p_user_id, public._today_ng(), v_phone, 'pending', p_kind)
  on conflict (user_id, repost_date, kind) do nothing;
  get diagnostics v_rows = row_count;

  if v_rows = 0 then
    return query select false, 'ALREADY_REPOSTED_TODAY';
  else
    return query select true, 'OK';
  end if;
end;
$function$;

-- 5. Leaderboard (this week = today and the 6 days before; this month = 30 days) ------
create or replace function public.get_leaderboard(p_user_id uuid, p_metric text default 'reposts'::text, p_period text default 'week'::text, p_limit integer default 20)
 returns table(rank bigint, user_id uuid, username text, score integer, is_me boolean)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare v_since date;
begin
  if p_metric <> 'reposts' then raise exception 'INVALID_METRIC'; end if;
  p_limit := least(greatest(coalesce(p_limit, 20), 1), 50);
  v_since := case p_period
    when 'week'  then public._today_ng() - 6
    when 'month' then public._today_ng() - 29
    else date '1970-01-01' end;
  return query
  with scores as (
    select u.id as uid, u.username as uname,
           count(d.id)::int as pts,
           max(coalesce(d.verified_at, d.created_at)) as reached_at
      from public.users u
      join public.daily_reposts d on d.user_id = u.id
       and d.status = 'verified' and d.kind = 'repost' and d.repost_date >= v_since
     group by u.id, u.username
  ), ranked as (
    select row_number() over (order by pts desc, reached_at asc, uname asc, uid) as rnk,
           uid, uname, pts
      from scores
  )
  select r.rnk, case when r.uid = p_user_id then r.uid else null end, r.uname, r.pts, (r.uid = p_user_id)
    from ranked r
   where r.rnk <= p_limit or r.uid = p_user_id
   order by r.rnk;
end;
$function$;

-- 6. Admin: verify by usernames (default date = Nigerian today) -------------------------
create or replace function public.admin_verify_reposts(p_usernames text[], p_date date default null::date, p_apply boolean default false)
 returns table(username text, result text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  v_date date := coalesce(p_date, public._today_ng());
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

-- 7. Daily reminder --------------------------------------------------------------------------
create or replace function public.send_daily_repost_reminder()
 returns integer
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare v_count int; v_today date := public._today_ng();
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
       and not exists (select 1 from daily_reposts d where d.user_id = u.id and d.kind = 'repost' and d.repost_date = v_today)
       and not exists (select 1 from notifications n where n.user_id = u.id
                        and n.action = 'open_whatsapp_repost'
                        and (n.created_at at time zone 'Africa/Lagos')::date = v_today)
    returning 1)
  select count(*) into v_count from inserted;
  return v_count;
end $function$;

-- 8. Admin notification stats (group by Nigerian day) ---------------------------------------
create or replace function public.admin_notification_stats()
 returns table(out_title text, out_day date, out_broadcast boolean, out_recipients bigint, out_accepted bigint, out_rejected bigint, out_accepted_pct numeric, out_opened bigint, out_open_pct numeric)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Not authorized'; END IF;
  RETURN QUERY
  WITH per AS (
    SELECT n.id, n.title AS t, (n.created_at AT TIME ZONE 'Africa/Lagos')::date AS d, (n.user_id IS NULL) AS b,
           n.fcm_sent AS s, n.fcm_failed AS f,
           (SELECT COUNT(*) FROM notification_receipts r
             WHERE r.notification_id = n.id AND r.opened_at IS NOT NULL) AS o
      FROM notifications n
     WHERE n.created_at > now() - interval '30 days'
  )
  SELECT p.t, p.d, p.b,
         COUNT(*)::BIGINT,
         SUM(p.s)::BIGINT,
         SUM(p.f)::BIGINT,
         ROUND(100.0 * SUM(p.s) / NULLIF(SUM(p.s) + SUM(p.f), 0), 1),
         SUM(p.o)::BIGINT,
         ROUND(100.0 * SUM(p.o) / NULLIF(SUM(p.s), 0), 1)
    FROM per p
   GROUP BY p.t, p.d, p.b
   ORDER BY p.d DESC, p.t
   LIMIT 30;
END;
$function$;

notify pgrst, 'reload schema';

-- CHECK: nigeria_today equals utc_today except between 23:00 and 24:00 UTC (midnight to 1 am in
-- Nigeria). column_default must mention Africa/Lagos.
select public._today_ng() as nigeria_today,
       (now() at time zone 'utc')::date as utc_today,
       (now() at time zone 'Africa/Lagos') as nigeria_time_now,
       (select column_default from information_schema.columns
         where table_schema = 'public' and table_name = 'daily_reposts'
           and column_name = 'repost_date') as column_default;
