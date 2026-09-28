-- VGContact: phone numbers must be EXACTLY 11 digits, starting with 0
-- (example: 09110321143). Enforced in the app AND here in the database.
-- Safe to run more than once. Paste into the Supabase SQL Editor.

-- 0. PRE-CHECK: any existing rows that break the rule? (should return 0 rows)
--    If it returns rows, fix or delete them first, or step 1 will fail.
-- select id, username, phone from users where phone !~ '^0[0-9]{10}$';

-- 1. Table-level rule: nothing else can ever be saved with a bad phone.
alter table public.users drop constraint if exists users_phone_11_digits;
alter table public.users
  add constraint users_phone_11_digits
  check (phone ~ '^0[0-9]{10}$');

-- 2. Phone must be unique too (register relies on this to catch duplicates).
create unique index if not exists users_phone_unique on public.users (phone);

-- 3. register_or_fetch_user: same as before, but rejects a bad phone.
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
  v_android  text := btrim(coalesce(p_android_id, ''));
  v_username text := btrim(coalesce(p_username, ''));
  v_phone    text := btrim(coalesce(p_phone, ''));
  v_ref      text := left(nullif(btrim(coalesce(p_referred_by, '')), ''), 50);
  v_id       uuid;
begin
  if v_android = '' or length(v_android) > 64
     or v_username = '' or length(v_username) > 50
     or v_phone !~ '^0[0-9]{10}$' then
    raise exception 'INVALID_INPUT';
  end if;

  select u.id into v_id from users u where u.android_id = v_android;

  if v_id is null then
    insert into users (android_id, username, phone, referred_by)
    values (v_android, v_username, v_phone, v_ref)
    returning users.id into v_id;

    perform public._place_user_in_group(v_id);
  end if;

  return query
    select u.id, u.username, u.phone, u.referred_by, u.created_at, u.key_balance
      from users u where u.id = v_id;
end;
$function$;

-- 4. login_by_phone: same as before, but a bad phone just means "not found".
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
  elsif btrim(coalesce(p_android_id, '')) <> ''
        and v_user.android_id = btrim(p_android_id) then
    return query select true, true, v_user.id, v_user.username, v_user.phone,
                        v_user.referred_by, v_user.created_at, v_user.key_balance;
  else
    return query select true, false, null::uuid, null::text, null::text,
                        null::text, null::timestamptz, null::integer;
  end if;
end;
$function$;

-- 5. Make sure the app (anon key) is allowed to call both functions.
grant execute on function public.register_or_fetch_user(text, text, text, text) to anon, authenticated;
grant execute on function public.login_by_phone(text, text) to anon, authenticated;

-- 6. Clean up the TESTUSER row I created while testing.
delete from public.users where username = 'TESTUSER';
