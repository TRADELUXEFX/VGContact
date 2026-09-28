-- Unlock several contact groups in ONE step: all or nothing.
-- Spends 1 key per NEW group (groups the user already unlocked are free).
-- Runs as one transaction and locks the user's row while it works, so two
-- taps at the same time can't spend the same keys twice.
--
-- message is one of: OK, NO_KEYS, GROUP_NOT_FOUND, GROUP_NOT_FULL, USER_NOT_FOUND

create or replace function public.spend_keys_unlock_groups(
  p_user_id uuid,
  p_group_ids uuid[]
)
returns table (success boolean, message text, remaining_keys integer)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_balance integer;
  v_ids uuid[];
  v_new uuid[];
  v_need integer;
begin
  select u.key_balance into v_balance
  from users u
  where u.id = p_user_id
  for update;

  if not found then
    return query select false, 'USER_NOT_FOUND'::text, 0;
    return;
  end if;

  select coalesce(array_agg(distinct x), '{}'::uuid[]) into v_ids
  from unnest(p_group_ids) as x;

  if coalesce(array_length(v_ids, 1), 0) = 0 then
    return query select false, 'GROUP_NOT_FOUND'::text, v_balance;
    return;
  end if;

  if (select count(*) from contact_groups g where g.id = any(v_ids)) <> array_length(v_ids, 1) then
    return query select false, 'GROUP_NOT_FOUND'::text, v_balance;
    return;
  end if;

  if exists (select 1 from contact_groups g where g.id = any(v_ids) and g.is_full is not true) then
    return query select false, 'GROUP_NOT_FULL'::text, v_balance;
    return;
  end if;

  select coalesce(array_agg(x), '{}'::uuid[]) into v_new
  from unnest(v_ids) as x
  where not exists (
    select 1 from group_unlocks gu
    where gu.user_id = p_user_id and gu.group_id = x
  );

  v_need := coalesce(array_length(v_new, 1), 0);

  if v_need = 0 then
    return query select true, 'OK'::text, v_balance;
    return;
  end if;

  if v_balance < v_need then
    return query select false, 'NO_KEYS'::text, v_balance;
    return;
  end if;

  insert into group_unlocks (user_id, group_id)
  select p_user_id, x from unnest(v_new) as x;

  update users set key_balance = key_balance - v_need where id = p_user_id;

  return query select true, 'OK'::text, v_balance - v_need;
end;
$$;

grant execute on function public.spend_keys_unlock_groups(uuid, uuid[]) to anon, authenticated;
