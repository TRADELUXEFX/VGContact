-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Run in Supabase > SQL Editor BEFORE installing this build. Safe to run more than once.
-- Profile > Referred by: returns the username and phone number of the account that
-- referred the caller. users.referred_by holds either that person's username or
-- their phone number (same matching rule as the referral leaderboard).
-- No row = nobody referred this user, or the referrer could not be found.

drop function if exists public.get_my_referrer(uuid);

create function public.get_my_referrer(p_user_id uuid)
returns table(username text, phone text)
language sql
stable
security definer
set search_path to 'public'
as $$
  select r.username, r.phone
    from users me
    join users r
      on r.id <> me.id
     and (
           lower(btrim(me.referred_by)) = lower(btrim(r.username))
        or (
             btrim(me.referred_by) !~ '[A-Za-z]'
             and length(regexp_replace(me.referred_by, '\D', '', 'g')) >= 10
             and right(regexp_replace(me.referred_by, '\D', '', 'g'), 10)
               = right(regexp_replace(r.phone, '\D', '', 'g'), 10)
           )
         )
   where me.id = p_user_id
     and nullif(btrim(me.referred_by), '') is not null
   limit 1;
$$;

grant execute on function public.get_my_referrer(uuid) to anon, authenticated;
notify pgrst, 'reload schema';
