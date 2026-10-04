-- export_live_definitions.sql
-- Read-only queries that print what the LIVE database really contains. Run each one in
-- Supabase > SQL Editor and copy the single result cell. Nothing is changed.
-- Use these whenever you need the truth: the live database, not the files in migrations/.

-- 1. Tables, columns, constraints, indexes, triggers, row security, policies, grants -----------
select
  'EXTENSIONS: ' || (select string_agg(extname, ', ') from pg_extension)
  || E'\n\nTABLES:\n' || coalesce((
    select string_agg(
      'TABLE ' || c.relname || E'\n' ||
      (select string_agg('  ' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod) ||
              case when a.attnotnull then ' not null' else '' end ||
              coalesce(' default ' || pg_get_expr(d.adbin, d.adrelid), ''), E'\n' order by a.attnum)
         from pg_attribute a
         left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
        where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped),
      E'\n\n' order by c.relname)
    from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relkind = 'r'), '')
  || E'\n\nCONSTRAINTS:\n' || coalesce((
    select string_agg(conrelid::regclass || ': ' || pg_get_constraintdef(oid), E'\n'
                      order by conrelid::regclass::text, conname)
      from pg_constraint where connamespace = 'public'::regnamespace), '')
  || E'\n\nINDEXES:\n' || coalesce((
    select string_agg(indexdef, E'\n' order by tablename, indexname)
      from pg_indexes where schemaname = 'public'), '')
  || E'\n\nTRIGGERS:\n' || coalesce((
    select string_agg(pg_get_triggerdef(t.oid), E'\n' order by t.tgname)
      from pg_trigger t
      join pg_class c on c.oid = t.tgrelid
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and not t.tgisinternal), 'none')
  || E'\n\nRLS:\n' || coalesce((
    select string_agg(c.relname || ' rls=' || c.relrowsecurity, E'\n')
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind = 'r'), '')
  || E'\n\nPOLICIES:\n' || coalesce((
    select string_agg(tablename || ': ' || policyname || ' ' || cmd || ' to ' ||
                      array_to_string(roles, ',') || ' using ' || coalesce(qual, '-') ||
                      ' check ' || coalesce(with_check, '-'), E'\n')
      from pg_policies where schemaname = 'public'), 'none')
  || E'\n\nTABLE GRANTS (anon, authenticated):\n' || coalesce((
    select string_agg(grantee || ' ' || table_name || ' ' || privilege_type, E'\n')
      from information_schema.role_table_grants
     where table_schema = 'public' and grantee in ('anon', 'authenticated')), 'none')
  as dump;

-- 2. Functions whose name starts with get_ (without the secret versions) ------------------------
select string_agg(pg_get_functiondef(p.oid), E'\n\n' order by p.proname, p.oid) as dump
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prokind = 'f'
   and p.proname ~ '^get_'
   and not ('p_secret' = any (coalesce(p.proargnames, '{}'::text[])));

-- 3. All other functions except admin_ (without the secret versions) ---------------------------
select string_agg(pg_get_functiondef(p.oid), E'\n\n' order by p.proname, p.oid) as dump
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prokind = 'f'
   and p.proname !~ '^(get_|admin_)'
   and not ('p_secret' = any (coalesce(p.proargnames, '{}'::text[])));

-- 4. The admin_ functions -----------------------------------------------------------------------
select string_agg(pg_get_functiondef(p.oid), E'\n\n' order by p.proname, p.oid) as dump
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prokind = 'f'
   and p.proname ~ '^admin_';

-- 5. The secret versions (functions that take p_secret) -----------------------------------------
select string_agg(pg_get_functiondef(p.oid), E'\n\n' order by p.proname, p.oid) as dump
  from pg_proc p join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prokind = 'f'
   and 'p_secret' = any (coalesce(p.proargnames, '{}'::text[]));

-- 6. Scheduled jobs (pg_cron) -------------------------------------------------------------------
select jobid, jobname, schedule, command from cron.job order by jobid;
