-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Run once in Supabase > SQL Editor. Safe to run more than once.
-- Repost leaderboard: every user gets a UNIQUE rank (1, 2, 3, 4...).
-- Tiebreaker when reposts are equal: whoever reached that score FIRST ranks higher
-- (time of their latest verified repost, earlier = higher). If that is also identical,
-- username A-Z decides, so two people can never share a rank.
-- Counts kind = 'repost' only (verification does not score). Banned users are left out.
-- No app rebuild needed: the app already shows the rank the server sends.

-- 1. Main board (Phone number / Username switch) ------------------------------------
create or replace function public.get_repost_leaderboard_phone(p_user_id uuid)
 returns table(rank integer, username text, phone text, score integer, is_me boolean)
 language sql stable security definer set search_path to 'public'
as $function$
  with s as (
    select u.id as uid, u.username as un, u.phone as ph,
           count(d.id)::int as sc,
           max(coalesce(d.verified_at, d.created_at)) as reached_at
      from users u
      join daily_reposts d on d.user_id = u.id and d.status = 'verified' and d.kind = 'repost'
     where not coalesce(u.is_banned, false) or u.id = p_user_id
     group by u.id, u.username, u.phone
  ), r as (
    select uid, un, ph, sc,
           row_number() over (order by sc desc, reached_at asc, un asc, uid)::int as rk
      from s
  )
  select rk, un, ph, sc, (uid = p_user_id)
    from r
   where rk <= 50 or uid = p_user_id
   order by rk;
$function$;

grant execute on function public.get_repost_leaderboard_phone(uuid) to anon, authenticated;

-- 2. Fallback board (used by the app if the function above is missing) ---------------
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

grant execute on function public.get_leaderboard(uuid, text, text, integer) to anon, authenticated;

notify pgrst, 'reload schema';
