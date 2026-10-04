-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- VGContact: inactivity (part 2 of 2). Run AFTER add_inactivity.sql (this function breaks without it). Safe to run more than once.
-- Users with no successful sync for 30+ days (app_settings 'inactive_after_days') are left out of
-- other people's sync lists: group members and referral contacts. They return on their next sync.
-- Only two lines differ from the live get_sync_contacts: the two "_is_inactive" filters.
-- Referral levels still pass through an inactive person, so their downline is not cut off.

CREATE OR REPLACE FUNCTION public.get_sync_contacts(p_user_id uuid)
 RETURNS TABLE(phone text, display_name text)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
#variable_conflict use_column
begin
  if not exists (select 1 from users u
                  where u.id = p_user_id and coalesce(u.is_banned, false) = false) then
    return;
  end if;

  return query
  select s.value,
         coalesce((select nullif(btrim(n.value), '') from app_settings n
                    where n.key = 'admin_contact_name'), 'VGContact Admin')
    from app_settings s
   where s.key = 'admin_phone' and nullif(btrim(s.value), '') is not null
  union all
  select s.value,
         coalesce((select nullif(btrim(n.value), '') from app_settings n
                    where n.key = 'anon_contact_name'), 'VGContact Status')
    from app_settings s
   where s.key = 'anon_phone' and nullif(btrim(s.value), '') is not null
  union all
  select m.phone, m.username
    from contact_group_members m
    join contact_groups g on g.id = m.group_id
    join users mu on mu.id = m.user_id
   where m.user_id <> p_user_id
     and m.group_id in (select x.group_id from contact_group_members x
                         where x.user_id = p_user_id)
     and (g.is_full
          or exists (select 1 from contact_group_members mine
                      where mine.user_id = p_user_id
                        and mine.group_id = m.group_id
                        and mine.joined_via = 'added'))
     and coalesce(mu.is_banned, false) = false
     and public._is_verified(m.user_id)
     and not public._is_inactive(m.user_id)
  union all
  -- Referrals, up to 3 levels deep, nearest first, capped at 200.
  select z.uphone::text, z.uname::text
    from (
      select d.uphone, d.uname, d.depth, d.uid
        from (
          with recursive chain(uid, uname, uphone, ubanned, depth) as (
            -- level 1: people who name ME as their referrer
            select r.id, r.username, r.phone, coalesce(r.is_banned, false), 1
              from users me
              join users r on r.id <> me.id
             where me.id = p_user_id
               and nullif(btrim(r.referred_by), '') is not null
               and (
                    lower(btrim(r.referred_by)) = lower(btrim(me.username))
                    or (btrim(r.referred_by) ~ '^[0-9]+$'
                        and right(btrim(r.referred_by), 10)
                            = right(regexp_replace(coalesce(me.phone, ''), '[^0-9]', '', 'g'), 10))
                   )
            union
            -- next level: people who name someone from the level above
            select r.id, r.username, r.phone, coalesce(r.is_banned, false), c.depth + 1
              from chain c
              join users r on r.id <> p_user_id and r.id <> c.uid
             where c.depth < 3                               -- CHANGE ME: number of levels
               and nullif(btrim(r.referred_by), '') is not null
               and (
                    lower(btrim(r.referred_by)) = lower(btrim(c.uname))
                    or (btrim(r.referred_by) ~ '^[0-9]+$'
                        and right(btrim(r.referred_by), 10)
                            = right(regexp_replace(coalesce(c.uphone, ''), '[^0-9]', '', 'g'), 10))
                   )
          )
          select distinct on (chain.uid)
                 chain.uid, chain.uname, chain.uphone, chain.depth
            from chain
           where chain.ubanned = false
             and not public._is_inactive(chain.uid)
             and nullif(btrim(chain.uphone), '') is not null
           order by chain.uid, chain.depth
        ) d
       order by d.depth, d.uname
       limit 200                                             -- CHANGE ME: max referral contacts
    ) z;
end;
$function$;

notify pgrst, 'reload schema';
