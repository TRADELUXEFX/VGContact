-- Makes the "sign-ups are closed" error exact, so the app can show "Registration is paused".
-- Run once in Supabase > SQL Editor.
--
-- BEFORE running, look at what the live trigger does today, in case it has extra rules
-- you want to keep:
--   select pg_get_functiondef(t.tgfoid)
--   from pg_trigger t where t.tgname = 'a_block_signup_when_closed';
--
-- The app (RegisterActivity.isSignupClosed) already recognises this code, and also
-- recognises common wording like "registration closed", so the app works either way.

create or replace function public._block_signup_when_closed_v2()
returns trigger
language plpgsql
as $$
begin
  if exists (
    select 1 from public.app_settings s
    where s.key = 'registrations_open'
      and lower(btrim(s.value)) = 'false'
  ) then
    raise exception 'REGISTRATIONS_CLOSED' using errcode = 'P0001';
  end if;
  return new;
end;
$$;

drop trigger if exists a_block_signup_when_closed on public.users;
create trigger a_block_signup_when_closed
  before insert on public.users
  for each row execute function public._block_signup_when_closed_v2();
