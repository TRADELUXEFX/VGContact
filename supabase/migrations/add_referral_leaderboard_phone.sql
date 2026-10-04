-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Run in Supabase > SQL Editor. Safe to run more than once.
-- Referral leaderboard: top 50 by direct referrals plus your own row.
-- Returns BOTH username and phone so the app can switch between them.
-- Same referral rule as _count_referrals. Banned users are left out.

drop function if exists public.get_referral_leaderboard_phone(uuid);

create function public.get_referral_leaderboard_phone(p_user_id uuid)
returns table(username text, phone text, referral_count integer, is_me boolean)
language sql
stable
security definer
set search_path to 'public'
as $$
  with counts as (
    select me.id as uid, me.username as un, me.phone as ph, count(r.id)::int as c
      from users me
      join users r
        on r.id <> me.id
       and r.referred_by is not null
       and (
             lower(btrim(r.referred_by)) = lower(me.username)
          or (
               r.referred_by !~ '[A-Za-z]'
               and length(regexp_replace(r.referred_by, '\D', '', 'g')) >= 10
               and right(regexp_replace(r.referred_by, '\D', '', 'g'), 10)
                 = right(regexp_replace(me.phone, '\D', '', 'g'), 10)
             )
           )
     where not coalesce(me.is_banned, false) or me.id = p_user_id
     group by me.id, me.username, me.phone
  ), ranked as (
    select uid, un, ph, c, row_number() over (order by c desc, un) as rn
      from counts
  )
  select un, ph, c, (uid = p_user_id)
    from ranked
   where rn <= 50 or uid = p_user_id
   order by c desc, un;
$$;

grant execute on function public.get_referral_leaderboard_phone(uuid) to anon, authenticated;
notify pgrst, 'reload schema';
