-- ############################################################################
-- OUTDATED - DO NOT RE-RUN. Kept only as history of what was once run.
-- The live database was changed after this file (account secret, referrer_id, Nigerian time
-- and more). Running it again can put an old version of a function back and bring old bugs
-- back. For the real current code see supabase/README.md and export_live_definitions.sql.
-- ############################################################################

-- VGContact: only BANNED users are left out of other people's sync list.
-- Run in Supabase > SQL Editor. Safe to run more than once.
-- Patches the LIVE get_sync_contacts in place (it keeps the no-cap referral rule and the
-- duplicate-number removal exactly as they are live): removes only the "_is_inactive" filters.
-- If it cannot remove them cleanly it stops and changes nothing.

do $do$
declare d text;
begin
  d := pg_get_functiondef('public.get_sync_contacts(uuid)'::regprocedure);
  if position('_is_inactive' in d) = 0 then
    raise notice 'No inactive filter found; nothing to change.';
    return;
  end if;
  d := regexp_replace(d, '\n[ \t]*and not public\._is_inactive\([a-z_.]+\)', '', 'g');
  if position('_is_inactive' in d) > 0 then
    raise exception 'Could not remove every inactive filter automatically; send the live function text.';
  end if;
  execute d;
end $do$;

notify pgrst, 'reload schema';
