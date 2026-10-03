-- wipe_test_data.sql  (OPTIONAL, run BEFORE launch, once)
-- Deletes EVERY user and what hangs off them: group memberships, groups, reposts.
-- Use it only when all current accounts are your own test accounts. This cannot be undone.
-- It keeps: app_settings, app_releases, banned_identities, broadcast notifications.
-- Step 1 only looks. Step 2 is one block: if any line errors, nothing is deleted.

-- 1. LOOK FIRST
select (select count(*) from public.users)                 as users,
       (select count(*) from public.contact_groups)        as groups,
       (select count(*) from public.contact_group_members) as group_members,
       (select count(*) from public.daily_reposts)         as reposts;

-- 2. DELETE (remove the two "--" at the start of each line below to run)
-- begin;
--   delete from public.daily_reposts;
--   delete from public.contact_group_members;
--   delete from public.users;
--   delete from public.contact_groups;
-- commit;
