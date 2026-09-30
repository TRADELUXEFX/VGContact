-- VGContact: inactivity tracking (part 1 of 2). Paste into the Supabase SQL Editor and run.
-- Safe to run more than once. If the editor errors, run each statement on its own. The app starts calling record_sync as soon as this exists.
--
-- What it does:
--   * users.last_synced_at: when contacts last synced successfully (opening the app alone does not count).
--   * record_sync(user): the app calls it after each successful sync to stamp last_synced_at.
--   * _inactive_after_days(): days without a sync before a user counts as inactive
--     (app_settings key 'inactive_after_days', default 30).
--   * _is_inactive(user): true when last_synced_at is older than that.
-- Part 2 (hiding inactive users from other people's sync lists) changes get_sync_contacts
-- and is sent separately.

alter table public.users
  add column if not exists last_synced_at timestamptz not null default now();

create index if not exists users_last_synced_at_idx on public.users (last_synced_at);

create or replace function public._inactive_after_days()
 returns integer
 language sql
 stable
 security definer
 set search_path to 'public'
as $fn$
  select coalesce(
    (select nullif(btrim(value), '')::int from app_settings where key = 'inactive_after_days'),
    30);
$fn$;

create or replace function public._is_inactive(p_user_id uuid)
 returns boolean
 language sql
 stable
 security definer
 set search_path to 'public'
as $fn$
  select coalesce(
    (select u.last_synced_at < now() - make_interval(days => public._inactive_after_days())
       from users u where u.id = p_user_id),
    false);
$fn$;

create or replace function public.record_sync(p_user_id uuid)
 returns void
 language sql
 security definer
 set search_path to 'public'
as $fn$
  update users set last_synced_at = now() where id = p_user_id;
$fn$;

revoke all on function public._inactive_after_days() from public, anon, authenticated;
revoke all on function public._is_inactive(uuid) from public, anon, authenticated;
grant execute on function public.record_sync(uuid) to anon, authenticated;
notify pgrst, 'reload schema';
