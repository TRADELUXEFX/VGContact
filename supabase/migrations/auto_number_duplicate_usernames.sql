-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- VGContact: if a username is already taken, add a number instead of failing.
--   chidera  ->  chidera2  ->  chidera3 ...
-- Works whether the Android ID switch is ON or OFF, and is case-insensitive
-- (Chidera and chidera count as the same name). A duplicate PHONE number is
-- still blocked. Run AFTER toggle_android_id_requirement.sql.
-- Safe to run more than once. Paste into the Supabase SQL Editor.

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

grant execute on function public.register_or_fetch_user(text, text, text, text) to anon, authenticated;
