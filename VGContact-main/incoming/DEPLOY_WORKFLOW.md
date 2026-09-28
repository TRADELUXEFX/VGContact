# How I deploy code changes to this repo (VGContact)

I work entirely from my phone — no laptop, no desktop git client. This file
explains my setup so that anyone (including a future AI assistant) helping
me with this repo understands how changes actually get from "edited code"
into the live repo and built APK.

## The problem this solves

Editing files one-by-one in the GitHub mobile web editor is slow and
error-prone on a small screen, especially when many files change at once.
Instead, I use a **zip-and-drop** system: I get a zip file containing all
the changed/added files (with the correct folder paths already matching
the repo structure), upload that single zip through GitHub's normal
"Upload files" web page into the `incoming/` folder, and a GitHub Action
automatically unzips it into the right places, verifies it's a real
change, commits it, and builds the APK.

## The pieces involved

### 1. `.github/workflows/deploy.yml`
This is the one and only GitHub Action in this repo. There used to be a
plan for a separate `deploy-from-zip.yml` that triggered only on a
specific filename (`incoming/deploy.zip`), but that file was never
actually committed - only this doc referenced it. `deploy.yml` is what
really runs, and it triggers on **any push to `main`**, not a specific
filename. On each run it:
1. Extracts every `.zip` file found in `incoming/` into the repo root,
   overwriting any existing files at matching paths - EXCEPT anything
   under `.github/workflows/`, which is always skipped (GitHub blocks
   the default token from touching that folder, so any workflow file
   inside your zip is silently ignored rather than breaking the deploy).
2. Refuses to proceed (fails loudly, red X) if the zip didn't actually
   contain a real update - specifically, if it detects a wrapper folder
   (e.g. `VGContact-main/app/...` instead of `app/...`), or if
   `app/build.gradle` wasn't part of the changes. This exists because
   both of those failure modes used to silently keep building the OLD
   app while still reporting a green checkmark.
3. Deletes the zip(s) and commits the unpacked result directly to `main`.
4. Immediately builds the APK (`gradle assembleDebug`) from that same
   commit and publishes it as a GitHub Release.

### 2. `incoming/` folder
The "drop zone" for deploy zips. Any zip filename works - it does NOT
need to be named `deploy.zip` specifically (that was only true of the
never-committed `deploy-from-zip.yml` plan). This folder should always
exist in the repo - don't delete it.

## My actual step-by-step workflow

Whenever I have code changes to ship (usually generated/edited with help
from an AI assistant):

1. **Get a zip.** I ask for a zip file where the internal folder structure
   exactly matches where each file belongs in the repo, starting from the
   repo root - e.g. `app/src/main/res/layout/activity_repost.xml`, not
   wrapped in an extra top-level folder like `VGContact-main/app/...`.
2. **Go to the repo on github.com** in my phone's browser (not the app,
   the mobile browser works fine).
3. Navigate into the `incoming/` folder, tap **Add file -> Upload files**.
4. Upload that zip - any filename is fine.
5. Tap **Commit changes** (committing straight to `main`).
6. Go to the **Actions** tab and wait ~1 minute. A run called **"Deploy"**
   should appear. Green checkmark = the update really landed AND the APK
   built from it. Red X = it did NOT update (check the Annotations for
   the specific reason - wrapper folder, no real changes detected, etc.)
   and the old app was NOT rebuilt or re-released.
7. Once green, check the Profile screen's "APP VERSION" row in the new
   APK to visually confirm which build you're actually running.
8. If anything fails, open the failed run and check the log / send a
   screenshot for help debugging.

## Important rules to remember

- Any zip filename works in `incoming/` - it does not need to be named
  `deploy.zip`.
- Folder structure inside the zip must mirror the repo exactly, starting
  from the repo root (e.g. starts with `app/...`, not wrapped in an extra
  folder).
- `app/build.gradle`'s versionCode/versionName must actually change for
  the deploy step to accept the update as real - bump it every time.
- `.github/workflows/` files inside an uploaded zip are always ignored;
  that file only gets updated by editing it directly on github.com.
- I do not manually retype or hand-edit file contents on my phone anymore
  - files are given to me pre-written and I just place them via this zip
  system.
- The `incoming/` folder and `.github/workflows/deploy.yml` must never be
  deleted, since the whole system depends on both existing.

## Summary for anyone assisting me

> Give me complete, ready-to-use files with their correct repo-relative
> paths, including a bumped versionCode/versionName in app/build.gradle.
> Package them into a single zip with that folder structure preserved,
> starting from the repo root (no wrapper folder). I will upload that zip
> through GitHub's web "Upload files" page into `incoming/` (any
> filename) and commit it directly. The existing GitHub Action
> (`deploy.yml`) unpacks it into place, verifies it's a real change,
> commits, builds, and releases the APK automatically - I don't need to
> touch individual files by hand, and I don't need a specific filename.
