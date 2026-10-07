-- fix_removed_numbers.sql
-- RUN: Supabase > SQL Editor > paste all > Run (one time).
-- Numbers to delete from this phone. Every number on the list is returned to every phone, no
-- exceptions. The app also stops saving these numbers again.
create or replace function public.get_removed_numbers(p_user_id uuid, p_secret text)
 returns table(phone text)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not public._auth_ok(p_user_id, p_secret) then raise exception 'UNAUTHORIZED'; end if;
  return query select r.phone from public.contact_removals r;
end;
$function$;

revoke all on function public.get_removed_numbers(uuid, text) from public;
grant execute on function public.get_removed_numbers(uuid, text) to anon, authenticated;
notify pgrst, 'reload schema';
