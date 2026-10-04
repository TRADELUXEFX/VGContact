-- add_push_caller_check.sql
-- Lets the send-push Edge Function ask: "is this the same key our trigger sends?"
-- The trigger sends the vault secret named service_role_key. This function compares a key to it
-- and answers true/false (it never returns the key). Only the service role may call it.
-- RUN BEFORE deploying the new send-push. Safe to run more than once.

create or replace function public.push_caller_ok(p_token text)
 returns boolean
 language sql
 stable
 security definer
 set search_path to 'public'
as $function$
  select coalesce(exists (
    select 1 from vault.decrypted_secrets ds
     where ds.name = 'service_role_key'
       and ds.decrypted_secret = p_token
  ), false);
$function$;

revoke all on function public.push_caller_ok(text) from public, anon, authenticated;
grant execute on function public.push_caller_ok(text) to service_role;

notify pgrst, 'reload schema';

-- CHECK: a wrong key must give false.
select public.push_caller_ok('not-the-key') as should_be_false;
