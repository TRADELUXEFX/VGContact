-- READ-ONLY CHECKS. Safe to run: nothing here changes data.
-- Run each block separately in Supabase > SQL Editor and send me the results.
-- Purpose: list every error the database can send the app, so each one can have a clear message.

-- 1. Current settings that decide errors (registrations_open = 'false' blocks sign-up).
select key, value
from app_settings
where key in ('registrations_open', 'require_android_id', 'free_repost_cap', 'group_size')
order by key;

-- 2. The LIVE triggers on users, with their full source (this is the sign-up lock).
select t.tgname as trigger_name, t.tgenabled as enabled, p.proname as function_name,
       pg_get_functiondef(p.oid) as source
from pg_trigger t
join pg_proc p on p.oid = t.tgfoid
where not t.tgisinternal and t.tgrelid = 'public.users'::regclass
order by t.tgname;

-- 3. Every error text raised by any function or trigger in the public schema.
select m[1] as error_text, p.proname as function_name
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace and n.nspname = 'public'
cross join lateral regexp_matches(pg_get_functiondef(p.oid),
       'raise\s+exception\s+''([^'']+)''', 'gi') as m
where p.prokind = 'f'
order by m[1], p.proname;

-- 4. Every UPPER_CASE code a function can hand back (like NOT_VERIFIED).
--    Ignore plain words such as ACTIVE; look for names that read like errors.
select distinct m[1] as code, p.proname as function_name
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace and n.nspname = 'public'
cross join lateral regexp_matches(pg_get_functiondef(p.oid),
       '''([A-Z][A-Z0-9_]{4,})''', 'g') as m
where p.prokind = 'f'
order by 1, 2;

-- 5. What the app already explains in plain words (compare with blocks 3 and 4):
--    REGISTRATIONS_CLOSED (also matches wording like "registration closed/paused"),
--    RESERVED_NAME, duplicate (23505), UNAUTHORIZED, ACCOUNT_BANNED,
--    ALREADY_REPOSTED_TODAY, NOT_VERIFIED, ALREADY_VERIFIED, REPOST_LIMIT_REACHED,
--    USER_NOT_FOUND. Anything else shows a general "Couldn't ... Please try again".

-- 6. Test the paused sign-up message end to end. Run the whole thing together:
--    it turns sign-ups off, tries one sign-up (you should see the error text), then
--    puts everything back. The error is expected, so run it as ONE script and read the
--    error. After it, confirm block 1 shows registrations_open = 'true' again.
--
-- begin;
--   update app_settings set value = 'false' where key = 'registrations_open';
--   select * from register_account('test-device-id', 'zz_test_user', '09000000000', null);
-- rollback;
