-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Run in Supabase > SQL Editor. Safe to run more than once.
-- Repost leaderboard with phone numbers: top 50 by verified reposts (kind = 'repost' only,
-- verification rows do not count) (all time)
-- plus your own row. Unique ranks; ties broken by who reached the score first (see fix_unique_repost_rank.sql). Banned users are left out.
-- Returns BOTH username and phone so the app can switch between them.

drop function if exists public.get_repost_leaderboard_phone(uuid);

create function public.get_repost_leaderboard_phone(p_user_id uuid)
returns table(rank integer, username text, phone text, score integer, is_me boolean)
language sql
stable
security definer
set search_path to 'public'
as $$
  with s as (
    select u.id as uid, u.username as un, u.phone as ph, count(d.id)::int as sc,
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
$$;

grant execute on function public.get_repost_leaderboard_phone(uuid) to anon, authenticated;
notify pgrst, 'reload schema';
