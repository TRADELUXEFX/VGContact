-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- Run once in Supabase > SQL Editor. Safe to run more than once.
-- Lets the Referral screen mark people who are not verified yet ("Pending").
-- It reuses get_my_referrals (same access rules: you and your own levels only),
-- so it can only ever answer for people that function already returns.
-- Verified = has at least one verified daily_reposts row (same rule as get_home).
-- No other change needed: the app treats a missing function as "nobody pending".

create or replace function public.get_my_referrals_status(p_user_id uuid, p_target_user_id uuid default null)
 returns table(user_id uuid, is_verified boolean)
 language sql stable security definer set search_path to 'public'
as $function$
  select r.user_id,
         exists (select 1 from public.daily_reposts d
                  where d.user_id = r.user_id and d.status = 'verified')
    from public.get_my_referrals(p_user_id, p_target_user_id) r;
$function$;

grant execute on function public.get_my_referrals_status(uuid, uuid) to anon, authenticated;
notify pgrst, 'reload schema';
