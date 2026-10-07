-- app_version_min.sql
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run (one time). Safe to run twice.
-- Two settings for app updates:
--   LATEST  = the one live build. Everyone who taps Update gets this one.
--   MINIMUM = users below this build can't use the app until they update. At or above: full access.
-- No app update needed (the app already understands "force update").

-- 1. Save today's forced minimum first, so nothing changes for users
--    (today: the highest live build that is marked forced; 0 = no minimum).
insert into public.app_settings (key, value)
values ('min_version_code',
        coalesce((select max(version_code) from public.app_releases where approved and force_update), 0)::text)
on conflict (key) do nothing;

-- 2. Only ONE live (latest) build: keep the highest live one, switch off the rest
update public.app_releases
   set approved = false, force_update = false
 where approved
   and version_code <> (select max(version_code) from public.app_releases where approved);

create or replace function public._one_live_release()
 returns trigger
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
  update public.app_releases
     set approved = false, force_update = false
   where approved and version_code <> new.version_code;
  return new;
end;
$function$;

drop trigger if exists trg_one_live_release on public.app_releases;
create trigger trg_one_live_release
  after insert or update of approved on public.app_releases
  for each row
  when (new.approved is true)
  execute function public._one_live_release();

-- 3. What the app asks: update available = latest is newer than the phone.
--    Forced = the phone is below the MINIMUM.
drop function if exists public.get_app_update(int);
create function public.get_app_update(p_current_build int)
returns table (
  update_available boolean,
  force_update     boolean,
  latest_build     int,
  download_url     text,
  apk_url          text,
  notes            text
)
language sql
security definer
set search_path = public
as $$
  with s as (
    select
      coalesce((select max(r.version_code) from app_releases r where r.approved), 0) as latest,
      coalesce((select nullif(trim(a.value), '')::int from app_settings a where a.key = 'min_version_code'), 0) as minb,
      coalesce((select r.download_url from app_releases r where r.approved order by r.version_code desc limit 1), '') as apk,
      coalesce((select r.changelog from app_releases r where r.approved order by r.version_code desc limit 1), '') as notes,
      coalesce((select trim(a.value) from app_settings a where a.key = 'update_url'), '') as page
  ),
  l as (select *, coalesce(nullif(page, ''), apk) as link from s)
  select
    (latest > p_current_build and link <> ''),
    (minb   > p_current_build and link <> ''),
    latest,
    link,
    apk,
    notes
  from l;
$$;
revoke all on function public.get_app_update(int) from public;
grant execute on function public.get_app_update(int) to anon, authenticated;

-- 4. Admin: read and set the minimum
create or replace function public.admin_get_min_version()
returns table (min_version integer)
language plpgsql
security definer
set search_path = public
as $$
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  return query
    select coalesce((select nullif(trim(a.value), '')::int from app_settings a where a.key = 'min_version_code'), 0);
end $$;

create or replace function public.admin_set_min_version(p_version_code integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare v_latest int;
begin
  if not _is_admin() then raise exception 'not admin'; end if;
  p_version_code := coalesce(p_version_code, 0);
  select coalesce(max(version_code), 0) into v_latest from app_releases where approved;
  if p_version_code > v_latest then
    raise exception 'The minimum cannot be newer than the latest live version';
  end if;
  insert into app_settings (key, value) values ('min_version_code', p_version_code::text)
  on conflict (key) do update set value = excluded.value;
end $$;

revoke all on function public.admin_get_min_version() from public, anon;
revoke all on function public.admin_set_min_version(integer) from public, anon;
grant execute on function public.admin_get_min_version() to authenticated;
grant execute on function public.admin_set_min_version(integer) to authenticated;

notify pgrst, 'reload schema';

-- CHECK: one live build, and the minimum
select 'live' as what, version_code::text as value from public.app_releases where approved
union all
select 'minimum', value from public.app_settings where key = 'min_version_code';
