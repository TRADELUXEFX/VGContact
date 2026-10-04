-- drop_unknown_referral.sql  (run once in Supabase > SQL Editor; safe to run again)
-- Rule: a referral is only kept if it matches a real, different account (username in any
-- capitals, or the last 10 digits of a phone number). If nobody matches, referred_by is saved
-- as NULL, so the Profile shows "None" and no one is credited.
-- Only changes the trigger function _users_set_referrer (runs before insert/update of
-- referred_by on users). register_account / login_account already read referred_by back
-- from the table, so the app gets the cleaned value.

create or replace function public._users_set_referrer()
 returns trigger
 language plpgsql
 set search_path to 'public'
as $function$
begin
  if TG_OP = 'INSERT' or NEW.referred_by is distinct from OLD.referred_by then
    NEW.referrer_id := public._resolve_referrer(NEW.referred_by, NEW.id);
    if NEW.referrer_id is null then
      NEW.referred_by := null;      -- nobody matched: log no referral at all
    end if;
  end if;
  return NEW;
end;
$function$;

revoke all on function public._users_set_referrer() from public, anon, authenticated;

-- Clean up accounts that already saved a referral nobody matched (your test account).
update public.users
   set referred_by = null
 where referrer_id is null
   and referred_by is not null;

notify pgrst, 'reload schema';

-- CHECK: should return 0 rows (no user keeps referral text without a matching referrer)
select id, username, referred_by from public.users
 where referred_by is not null and referrer_id is null;
