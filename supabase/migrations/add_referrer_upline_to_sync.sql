-- add_referrer_upline_to_sync.sql
-- Referrals now work BOTH ways in the sync list. Run in Supabase > SQL Editor (whole file).
-- Safe to run more than once. No app update needed (the app saves whatever the server returns).
--
-- Problem (confirmed on the live database 2026-10-11): get_sync_contacts(uuid) only returned the
-- people who were referred BY the user (downline, 3 levels). The referred person did NOT get
-- their referrer saved, so the referrer's status was not visible to them (WhatsApp needs both
-- people to have each other saved).
--
-- Fix, in the function itself: get_sync_contacts(uuid) now also returns the person who referred
-- the user, and who referred that person, and so on, up to 3 levels (the upline), using
-- users.referrer_id. Same rules as the downline: banned users are skipped, a blank phone is
-- skipped, the user never gets their own number, and a number is sent once (priority: admin,
-- status, group member, downline referral, upline referral).
--
-- Everything else in the function is unchanged from add_referrer_id.sql (confirmed live: no
-- inactive filter, no 200 cap). The secret version get_sync_contacts(uuid, text) calls this one,
-- so it needs no change. CREATE OR REPLACE keeps the existing permissions (locked from anon).

create or replace function public.get_sync_contacts(p_user_id uuid)
 returns table(phone text, display_name text)
 language plpgsql
 stable
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not exists (select 1 from users u
                  where u.id = p_user_id and coalesce(u.is_banned, false) = false) then
    return;
  end if;

  return query
  select f.c_phone, f.c_name
    from (
      select distinct on (a.c_key) a.c_phone, a.c_name, a.c_prio, a.c_depth
        from (
          select b.c_phone, b.c_name, b.c_prio, b.c_depth,
                 right(regexp_replace(coalesce(b.c_phone, ''), '[^0-9]', '', 'g'), 9) as c_key
            from (
              select s.value::text as c_phone,
                     coalesce((select nullif(btrim(n.value), '') from app_settings n
                                where n.key = 'admin_contact_name'), 'VGContact Admin')::text as c_name,
                     1 as c_prio, 0 as c_depth
                from app_settings s
               where s.key = 'admin_phone' and nullif(btrim(s.value), '') is not null
              union all
              select s.value::text,
                     coalesce((select nullif(btrim(n.value), '') from app_settings n
                                where n.key = 'anon_contact_name'), 'VGContact Status')::text,
                     2, 0
                from app_settings s
               where s.key = 'anon_phone' and nullif(btrim(s.value), '') is not null
              union all
              select m.phone::text, m.username::text, 3, 0
                from contact_group_members m
                join users mu on mu.id = m.user_id
               where m.user_id <> p_user_id
                 and m.group_id in (select x.group_id from contact_group_members x
                                     where x.user_id = p_user_id)
                 and coalesce(mu.is_banned, false) = false
                 and public._is_verified(m.user_id)
              union all
              -- Downline: people I referred, up to 3 levels deep.
              select z.uphone::text, z.uname::text, 4, z.depth
                from (
                  with recursive chain(uid, uname, uphone, ubanned, depth) as (
                    select r.id, r.username, r.phone, coalesce(r.is_banned, false), 1
                      from users r
                     where r.referrer_id = p_user_id and r.id <> p_user_id
                    union
                    select r.id, r.username, r.phone, coalesce(r.is_banned, false), c.depth + 1
                      from chain c
                      join users r on r.referrer_id = c.uid
                                  and r.id <> p_user_id and r.id <> c.uid
                     where c.depth < 3
                  )
                  select distinct on (chain.uid)
                         chain.uid, chain.uname, chain.uphone, chain.depth
                    from chain
                   where chain.ubanned = false
                     and nullif(btrim(chain.uphone), '') is not null
                   order by chain.uid, chain.depth
                ) z
              union all
              -- Upline: the person who referred me, who referred them, and so on, up to 3 levels.
              select z.uphone::text, z.uname::text, 5, z.depth
                from (
                  with recursive up(uid, depth) as (
                    select u.referrer_id, 1
                      from users u
                     where u.id = p_user_id and u.referrer_id is not null
                    union
                    select r.referrer_id, up.depth + 1
                      from up
                      join users r on r.id = up.uid
                     where up.depth < 3 and r.referrer_id is not null
                  )
                  select us.id as uid, us.username as uname, us.phone as uphone,
                         min(up.depth) as depth
                    from up
                    join users us on us.id = up.uid
                   where us.id <> p_user_id
                     and coalesce(us.is_banned, false) = false
                     and nullif(btrim(us.phone), '') is not null
                   group by us.id, us.username, us.phone
                ) z
            ) b
        ) a
       where a.c_key <> ''
       order by a.c_key, a.c_prio, a.c_depth, a.c_name
    ) f
   order by f.c_prio, f.c_depth, f.c_name;
end;
$function$;

revoke all on function public.get_sync_contacts(uuid) from public, anon, authenticated;

notify pgrst, 'reload schema';

-- CHECK (run last). Every row should show referrer_in_referreds_list = true.
select u.username as referred_user,
       r.username as referrer,
       exists (select 1 from public.get_sync_contacts(r.id) g
                where right(regexp_replace(g.phone, '\D', '', 'g'), 9)
                    = right(regexp_replace(u.phone, '\D', '', 'g'), 9)) as referred_in_referrers_list,
       exists (select 1 from public.get_sync_contacts(u.id) g
                where right(regexp_replace(g.phone, '\D', '', 'g'), 9)
                    = right(regexp_replace(r.phone, '\D', '', 'g'), 9)) as referrer_in_referreds_list
  from users u
  join users r on r.id = u.referrer_id
 order by 1;
