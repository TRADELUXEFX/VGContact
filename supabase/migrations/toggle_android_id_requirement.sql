-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- VGContact: switch the Android ID requirement ON / OFF from the database.
-- Paste the whole file into the Supabase SQL Editor and run it.
-- Safe to run more than once. No app update needed.
--
-- While the switch is OFF:
--   * Sign up always creates a NEW account, even if this phone already has one.
--   * Login by phone works without the Android ID matching.
--   * A username that is already taken gets a number added (chidera -> chidera2).
--   * A duplicate PHONE number is STILL blocked.
--
-- The switch:
--   TURN OFF:  update public.app_settings set value = 'false' where key = 'require_android_id';
--   TURN ON:   update public.app_settings set value = 'true'  where key = 'require_android_id';

-- 1. A small private settings table (the app cannot read or change it).
create table if not exists public.app_settings (
  key   text primary key,
  value text not null
);
alter table public.app_settings enable row level security;
revoke all on public.app_settings from anon, authenticated;

-- Default is ON. Re-running this never flips an existing switch back.
insert into public.app_settings (key, value)
values ('require_android_id', 'true')
on conflict (key) do nothing;

-- 2. Helper: is the Android ID currently required? (missing row = yes)
create or replace function public._android_id_required()
 returns boolean
 language sql
 stable
 security definer
 set search_path to 'public'
as $$
  select coalesce(
    (select lower(btrim(value)) <> 'false' from app_settings where key = 'require_android_id'),
    true);
$$;

-- 3. register_or_fetch_user: skips the Android ID rules when the switch is OFF.
CREATE OR REPLACE FUNCTION public.register_or_fetch_user(
  p_android_id text, p_username text, p_phone text, p_referred_by text DEFAULT NULL::text)
 RETURNS TABLE(id uuid, username text, phone text, referred_by text,
               created_at timestamp with time zone, key_balance integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
#variable_conflict use_column
declare
  v_required boolean := public._android_id_required();
  v_android  text := btrim(coalesce(p_android_id, ''));
  v_username text := btrim(coalesce(p_username, ''));
  v_phone    text := btrim(coalesce(p_phone, ''));
  v_ref      text := left(nullif(btrim(coalesce(p_referred_by, '')), ''), 50);
  v_store    text;
  v_id       uuid;
  v_try      text;
  v_n        int := 1;
begin
  if v_username = '' or length(v_username) > 50
     or v_phone !~ '^0[0-9]{10}$' then
    raise exception 'INVALID_INPUT';
  end if;

  if v_required then
    if v_android = '' or length(v_android) > 64 then
      raise exception 'INVALID_INPUT';
    end if;
    v_store := v_android;
    select u.id into v_id from users u where u.android_id = v_android;
  else
    -- Switch OFF: never reuse an old account for this device. The column is
    -- UNIQUE, so give each new account its own value.
    v_store := left(coalesce(nullif(v_android, ''), 'none'), 40)
               || ':' || left(gen_random_uuid()::text, 8);
  end if;

  if v_id is null then
    -- Username already taken (any capitalisation)? Add a number:
    -- chidera -> chidera2 -> chidera3 ...  Phone duplicates still fail.
    v_try := v_username;
    while exists (select 1 from users u where lower(u.username) = lower(v_try)) loop
      v_n := v_n + 1;
      v_try := left(v_username, 50 - length(v_n::text)) || v_n::text;
    end loop;

    -- If two people grab the same name at the same instant, retry with the next number.
    loop
      begin
        insert into users (android_id, username, phone, referred_by)
        values (v_store, v_try, v_phone, v_ref)
        returning users.id into v_id;
        exit;
      exception when unique_violation then
        if exists (select 1 from users u where u.phone = v_phone) or v_n > 200 then
          raise;
        end if;
        v_n := v_n + 1;
        v_try := left(v_username, 50 - length(v_n::text)) || v_n::text;
      end;
    end loop;

    perform public._place_user_in_group(v_id);
  end if;

  return query
    select u.id, u.username, u.phone, u.referred_by, u.created_at, u.key_balance
      from users u where u.id = v_id;
end;
$function$;

-- 4. login_by_phone: skips the device match when the switch is OFF.
CREATE OR REPLACE FUNCTION public.login_by_phone(p_phone text, p_android_id text)
 RETURNS TABLE(account_exists boolean, device_matches boolean, id uuid, username text,
               phone text, referred_by text, created_at timestamp with time zone,
               key_balance integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
#variable_conflict use_column
declare
  v_required boolean := public._android_id_required();
  v_user  users%rowtype;
  v_phone text := btrim(coalesce(p_phone, ''));
begin
  if v_phone !~ '^0[0-9]{10}$' then
    return query select false, false, null::uuid, null::text, null::text,
                        null::text, null::timestamptz, null::integer;
    return;
  end if;

  select * into v_user from users u where u.phone = v_phone;

  if not found then
    return query select false, false, null::uuid, null::text, null::text,
                        null::text, null::timestamptz, null::integer;
  elsif (not v_required)
        or (btrim(coalesce(p_android_id, '')) <> ''
            and v_user.android_id = btrim(p_android_id)) then
    return query select true, true, v_user.id, v_user.username, v_user.phone,
                        v_user.referred_by, v_user.created_at, v_user.key_balance;
  else
    return query select true, false, null::uuid, null::text, null::text,
                        null::text, null::timestamptz, null::integer;
  end if;
end;
$function$;

grant execute on function public.register_or_fetch_user(text, text, text, text) to anon, authenticated;
grant execute on function public.login_by_phone(text, text) to anon, authenticated;

-- 5. TURN THE ANDROID ID REQUIREMENT OFF NOW.
update public.app_settings set value = 'false' where key = 'require_android_id';

-- To turn it back on later, run only this line:
-- update public.app_settings set value = 'true' where key = 'require_android_id';

-- Check the switch (should show false right now):
select * from public.app_settings where key = 'require_android_id';
