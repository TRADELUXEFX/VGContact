-- Run in Supabase > SQL Editor. Safe to run more than once.
-- Repost leaderboard with phone numbers: top 50 by verified reposts (all time)
-- plus your own row. Ties share a rank. Banned users are left out.
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
    select u.id as uid, u.username as un, u.phone as ph, count(d.id)::int as sc
      from users u
      join daily_reposts d on d.user_id = u.id and d.status = 'verified'
     where not coalesce(u.is_banned, false) or u.id = p_user_id
     group by u.id, u.username, u.phone
  ), r as (
    select uid, un, ph, sc,
           rank() over (order by sc desc)::int as rk,
           row_number() over (order by sc desc, un) as rn
      from s
  )
  select rk, un, ph, sc, (uid = p_user_id)
    from r
   where rn <= 50 or uid = p_user_id
   order by rk, un;
$$;

grant execute on function public.get_repost_leaderboard_phone(uuid) to anon, authenticated;
notify pgrst, 'reload schema';
