-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- ONE rule for "referred viewers": everyone who registered under you.
-- Used by BOTH the app (get_home) and the admin (admin_find_users), so the two
-- numbers always match.
--
-- A person counts as registered under you when the referral they typed at
-- sign-up (users.referred_by) is:
--   * your username (any capitals, extra spaces ignored), OR
--   * your phone number (any format: 0811..., +234811..., 234 811...; the last
--     10 digits are compared).
-- You never count yourself. No "verified repost" needed - registered is enough.
--
-- Run in Supabase > SQL Editor. Safe to run more than once.

create or replace function public._count_referrals(p_user_id uuid)
returns integer
language sql
stable
security definer
set search_path to 'public'
as $$
  select count(*)::int
    from users me
    join users r on r.id <> me.id
   where me.id = p_user_id
     and r.referred_by is not null
     and (
           lower(btrim(r.referred_by)) = lower(me.username)
        or (
             r.referred_by !~ '[A-Za-z]'
             and length(regexp_replace(r.referred_by, '\D', '', 'g')) >= 10
             and right(regexp_replace(r.referred_by, '\D', '', 'g'), 10)
               = right(regexp_replace(me.phone,       '\D', '', 'g'), 10)
           )
         );
$$;

-- App side: same function as before, only the referral count changed.
create or replace function public.get_home(p_user_id uuid)
returns table(status text, free_current integer, free_max integer, extra_current integer,
              extra_max integer, referral_count integer, verified_reposts integer)
language plpgsql
stable
security definer
set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  v_size    int := public._group_size();
  v_default uuid;
  v_fc      int;
  v_ec      int;
  v_em      int;
  v_ref     int;
  v_vr      int;
begin
  if not exists (select 1 from users u where u.id = p_user_id) then return; end if;

  select m.group_id into v_default
    from contact_group_members m
   where m.user_id = p_user_id and m.joined_via = 'signup'
   order by m.created_at limit 1;

  -- Free viewers: signups in the default group.
  select count(*) into v_fc
    from contact_group_members m
   where m.group_id = v_default and m.joined_via = 'signup';

  -- Extra viewers: everyone in every other group this user belongs to.
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

  -- Referred viewers: total people registered under this user.
  v_ref := public._count_referrals(p_user_id);

  select count(*)::int into v_vr
    from daily_reposts d where d.user_id = p_user_id and d.status = 'verified';

  return query select
    case when v_vr > 0 then 'verified' else 'pending' end,
    v_fc, v_size, v_ec, v_em, v_ref, v_vr;
end;
$function$;

-- Admin side: same search as before, referrals now use the same rule.
create or replace function public.admin_find_users(p_q text)
returns table(id uuid, username text, phone text, key_balance integer, is_banned boolean,
              reposts integer, referrals integer, created_at timestamp with time zone)
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  return query
    select u.id, u.username, u.phone, coalesce(u.key_balance,0), u.is_banned,
      (select count(*)::int from daily_reposts d where d.user_id = u.id and d.status = 'verified'),
      public._count_referrals(u.id),
      u.created_at
    from users u
    where u.username ilike '%'||p_q||'%' or u.phone ilike '%'||p_q||'%'
    order by u.created_at desc limit 30;
end $function$;

notify pgrst, 'reload schema';
