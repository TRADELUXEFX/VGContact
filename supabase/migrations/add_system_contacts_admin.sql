-- add_system_contacts_admin.sql
-- Lets the admin page list, add, edit, switch on/off and delete the extra "system" contacts
-- (table system_contacts, see add_system_contacts.sql, which must be run first).
-- Same pattern as the other admin_* functions: only the admin can run them (_is_admin()).
--
-- RUN: Supabase > SQL Editor > paste the WHOLE file > Run. Safe to run more than once.
-- Then upload the new admin page (index.html). No app update needed.

create or replace function public.admin_list_system_contacts()
 returns table(id uuid, name text, phone text, sort_order integer, is_active boolean,
               created_at timestamp with time zone)
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
#variable_conflict use_column
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  return query
    select s.id, s.name, s.phone, s.sort_order, s.is_active, s.created_at
      from public.system_contacts s
     order by s.sort_order, s.created_at;
end;
$function$;

create or replace function public.admin_add_system_contact(
  p_name text, p_phone text, p_sort_order integer default 0)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
  v_phone text := regexp_replace(btrim(coalesce(p_phone, '')), '[\s\-()]', '', 'g');
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  if length(regexp_replace(v_phone, '\D', '', 'g')) < 10 then
    raise exception 'Enter a valid phone number (at least 10 digits)';
  end if;
  begin
    insert into public.system_contacts (name, phone, sort_order)
    values (nullif(btrim(coalesce(p_name, '')), ''), v_phone, coalesce(p_sort_order, 0));
  exception when unique_violation then
    raise exception 'That number is already in the list';
  end;
end;
$function$;

create or replace function public.admin_update_system_contact(
  p_id uuid, p_name text, p_phone text, p_sort_order integer, p_is_active boolean)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
declare
  v_phone text := regexp_replace(btrim(coalesce(p_phone, '')), '[\s\-()]', '', 'g');
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  if length(regexp_replace(v_phone, '\D', '', 'g')) < 10 then
    raise exception 'Enter a valid phone number (at least 10 digits)';
  end if;
  begin
    update public.system_contacts
       set name       = nullif(btrim(coalesce(p_name, '')), ''),
           phone      = v_phone,
           sort_order = coalesce(p_sort_order, 0),
           is_active  = coalesce(p_is_active, true)
     where id = p_id;
  exception when unique_violation then
    raise exception 'That number is already in the list';
  end;
  if not found then raise exception 'contact not found'; end if;
end;
$function$;

create or replace function public.admin_delete_system_contact(p_id uuid)
 returns void
 language plpgsql
 security definer
 set search_path to 'public'
as $function$
begin
  if not public._is_admin() then raise exception 'not admin'; end if;
  delete from public.system_contacts where id = p_id;
end;
$function$;

revoke all on function public.admin_list_system_contacts() from public, anon;
revoke all on function public.admin_add_system_contact(text, text, integer) from public, anon;
revoke all on function public.admin_update_system_contact(uuid, text, text, integer, boolean) from public, anon;
revoke all on function public.admin_delete_system_contact(uuid) from public, anon;
grant execute on function public.admin_list_system_contacts() to authenticated;
grant execute on function public.admin_add_system_contact(text, text, integer) to authenticated;
grant execute on function public.admin_update_system_contact(uuid, text, text, integer, boolean) to authenticated;
grant execute on function public.admin_delete_system_contact(uuid) to authenticated;

notify pgrst, 'reload schema';

-- CHECK: the four functions exist, and only "authenticated" (the logged-in admin page) can run them.
select p.proname, pg_get_function_identity_arguments(p.oid) as args,
       has_function_privilege('anon', p.oid, 'execute')          as anon_can_run,
       has_function_privilege('authenticated', p.oid, 'execute') as authenticated_can_run
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.proname like '%system_contact%'
 order by p.proname;
