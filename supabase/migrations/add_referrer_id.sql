-- add_referrer_id.sql  (run once; safe to run again)
-- Saves WHO referred each user (an ID) at sign-up, so the app never has to compare text across all users.
-- Same matching rules as before: username (any capitals) or the last 10 digits of a phone number.

-- 1. The new column and the lookup indexes -------------------------------------------------
alter table public.users add column if not exists referrer_id uuid references public.users(id) on delete set null;

create index if not exists idx_users_referrer on public.users (referrer_id) where referrer_id is not null;
create index if not exists idx_users_username_ci on public.users (lower(btrim(username)));
create index if not exists idx_users_phone_tail on public.users (right(regexp_replace(coalesce(phone, ''), '\D', '', 'g'), 10));
create index if not exists idx_users_unres_ref_name on public.users (lower(btrim(referred_by)))
  where referrer_id is null and referred_by is not null;
create index if not exists idx_users_unres_ref_phone on public.users (right(regexp_replace(referred_by, '\D', '', 'g'), 10))
  where referrer_id is null and referred_by is not null;

-- 2. Work out the referrer from the text someone typed ------------------------------------
create or replace function public._resolve_referrer(p_ref text, p_self uuid)
 returns uuid
 language sql
 stable
 set search_path to 'public'
as $function$
  select coalesce(
    (select u.id from users u
      where nullif(btrim(p_ref), '') is not null and u.id <> p_self
        and lower(btrim(u.username)) = lower(btrim(p_ref))
      order by u.created_at limit 1),
    (select u.id from users u
      where nullif(btrim(p_ref), '') is not null and u.id <> p_self
        and p_ref !~ '[A-Za-z]'
        and length(regexp_replace(p_ref, '\D', '', 'g')) >= 10
        and right(regexp_replace(coalesce(u.phone, ''), '\D', '', 'g'), 10)
          = right(regexp_replace(p_ref, '\D', '', 'g'), 10)
      order by u.created_at limit 1)
  );
$function$;

-- 3. Fill it in automatically ------------------------------------------------------------
-- (a) when someone signs up (or their referral text is changed)
create or replace function public._users_set_referrer()
 returns trigger
 language plpgsql
 set search_path to 'public'
as $function$
begin
  if TG_OP = 'INSERT' then
    NEW.referrer_id := public._resolve_referrer(NEW.referred_by, NEW.id);
  elsif NEW.referred_by is distinct from OLD.referred_by then
    NEW.referrer_id := public._resolve_referrer(NEW.referred_by, NEW.id);
  end if;
  return NEW;
end;
$function$;

drop trigger if exists b_users_set_referrer on public.users;
create trigger b_users_set_referrer
  before insert or update of referred_by on public.users
  for each row execute function public._users_set_referrer();

-- (b) when the person who was named signs up AFTER the people who named them
create or replace function public._users_link_referees()
 returns trigger
 language plpgsql
 set search_path to 'public'
as $function$
begin
  update users u
     set referrer_id = NEW.id
   where u.referrer_id is null
     and u.referred_by is not null
     and u.id <> NEW.id
     and (
           lower(btrim(u.referred_by)) = lower(btrim(NEW.username))
        or (
             u.referred_by !~ '[A-Za-z]'
             and length(regexp_replace(u.referred_by, '\D', '', 'g')) >= 10
             and right(regexp_replace(u.referred_by, '\D', '', 'g'), 10)
               = right(regexp_replace(coalesce(NEW.phone, ''), '\D', '', 'g'), 10)
           )
         );
  return NEW;
end;
$function$;

drop trigger if exists c_users_link_referees on public.users;
create trigger c_users_link_referees
  after insert on public.users
  for each row execute function public._users_link_referees();

revoke all on function public._resolve_referrer(text, uuid) from public, anon, authenticated;
revoke all on function public._users_set_referrer() from public, anon, authenticated;
revoke all on function public._users_link_referees() from public, anon, authenticated;

-- 4. Fill it in for everyone who already exists -----------------------------------------
update public.users u
   set referrer_id = public._resolve_referrer(u.referred_by, u.id)
 where nullif(btrim(u.referred_by), '') is not null;

-- 5. Make the app functions use it (same results, same columns) ---------------------------
create or replace function public._count_referrals(p_user_id uuid)
 returns integer
 language sql
 stable security definer
 set search_path to 'public'
as $function$
  select count(*)::int from users r where r.referrer_id = p_user_id and r.id <> p_user_id;
$function$;

create or replace function public.get_my_referrer(p_user_id uuid)
 returns table(username text, phone text)
 language sql
 stable security definer
 set search_path to 'public'
as $function$
  select r.username, r.phone
    from users me
    join users r on r.id = me.referrer_id
   where me.id = p_user_id
   limit 1;
$function$;

create or replace function public.get_my_referrals(p_user_id uuid, p_target_user_id uuid default null::uuid)
 returns table(user_id uuid, username text, phone text, created_at timestamp with time zone, invited_count integer)
 language plpgsql
 stable security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
declare
  t uuid := coalesce(p_target_user_id, p_user_id);
begin
  if not exists (select 1 from users u
                  where u.id = p_user_id and coalesce(u.is_banned, false) = false) then
    return;
  end if;

  -- Looking at someone else's list: they must be 1 or 2 levels below you.
  if t <> p_user_id then
    if not exists (
      with recursive d(uid, depth) as (
        select r.id, 1
          from users r
         where r.referrer_id = p_user_id and r.id <> p_user_id
           and coalesce(r.is_banned, false) = false
        union
        select r.id, d.depth + 1
          from d
          join users r on r.referrer_id = d.uid
         where d.depth < 2
           and r.id <> p_user_id and r.id <> d.uid
           and coalesce(r.is_banned, false) = false
      )
      select 1 from d where d.uid = t
    ) then
      return;
    end if;
  end if;

  return query
  select r.id, r.username::text, r.phone::text, r.created_at::timestamptz,
         (select count(*)::int from users c
           where coalesce(c.is_banned, false) = false and c.referrer_id = r.id)
    from users r
   where r.referrer_id = t and r.id <> t
     and coalesce(r.is_banned, false) = false
   order by r.created_at desc
   limit 500;
end;
$function$;

create or replace function public.get_referral_leaderboard(p_user_id uuid)
 returns table(username text, referral_count integer, is_me boolean)
 language plpgsql
 stable security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not exists (select 1 from users u
                  where u.id = p_user_id and coalesce(u.is_banned, false) = false) then
    return;
  end if;

  return query
  select u.username::text, c.n, (u.id = p_user_id)
    from (select r.referrer_id as uid, count(*)::int as n
            from users r
           where coalesce(r.is_banned, false) = false
             and r.referrer_id is not null and r.referrer_id <> r.id
           group by r.referrer_id) c
    join users u on u.id = c.uid
   where coalesce(u.is_banned, false) = false
   order by c.n desc, u.username
   limit 50;
end;
$function$;

create or replace function public.get_referral_leaderboard_phone(p_user_id uuid)
 returns table(username text, phone text, referral_count integer, is_me boolean)
 language sql
 stable security definer
 set search_path to 'public'
as $function$
  with counts as (
    select me.id as uid, me.username as un, me.phone as ph, count(r.id)::int as c
      from users me
      join users r on r.referrer_id = me.id and r.id <> me.id
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
$function$;

create or replace function public.get_sync_contacts(p_user_id uuid)
 returns table(phone text, display_name text)
 language plpgsql
 stable security definer
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
            ) b
        ) a
       where a.c_key <> ''
       order by a.c_key, a.c_prio, a.c_depth, a.c_name
    ) f
   order by f.c_prio, f.c_depth, f.c_name;
end;
$function$;

notify pgrst, 'reload schema';

-- 6. CHECK: should return NO ROWS. A row means the old text matching and the new saved
--    referrer disagree for that user.
select * from (
  select u.username,
         (select count(*) from users r
           where r.id <> u.id and r.referred_by is not null
             and (lower(btrim(r.referred_by)) = lower(u.username)
                  or (r.referred_by !~ '[A-Za-z]'
                      and length(regexp_replace(r.referred_by, '\D', '', 'g')) >= 10
                      and right(regexp_replace(r.referred_by, '\D', '', 'g'), 10)
                        = right(regexp_replace(u.phone, '\D', '', 'g'), 10)))) as old_way,
         (select count(*) from users r where r.referrer_id = u.id) as new_way
    from users u
) x
where old_way <> new_way;
