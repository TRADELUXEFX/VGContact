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
"Upload files" web page, and a GitHub Action automatically unzips it into
the right places and commits it for me.

## The pieces involved

### 1. `.github/workflows/deploy-from-zip.yml`
This is a GitHub Action already committed to this repo. It watches for
any push that adds or changes the file `incoming/deploy.zip`. When that
happens, it automatically:
1. Unzips `incoming/deploy.zip` into the repo root, overwriting any
   existing files at matching paths.
2. Deletes `incoming/deploy.zip` (so it doesn't stay in the repo).
3. Commits the unpacked result directly to `main`.

### 2. `incoming/` folder
A placeholder folder (kept alive with an empty `incoming/.gitkeep` file)
that exists purely as the "drop zone" for deploy zips. This folder should
always exist in the repo — don't delete it.

### 3. The build workflow (`build.yml`)
This is the normal Android build (Gradle, builds the APK). It's unrelated
to the deploy-from-zip system, but it runs automatically on every push —
including the commit that the deploy-from-zip Action makes. So the usual
sequence after a deploy is:
`deploy.zip uploaded -> unpacked & committed -> build.yml runs on that commit -> APK built`

## My actual step-by-step workflow

Whenever I have code changes to ship (usually generated/edited with help
from an AI assistant):

1. **Get a zip.** I ask for a zip file where the internal folder structure
   exactly matches where each file belongs in the repo — for example, a
   changed layout file must be zipped at the path
   `app/src/main/res/layout/activity_repost.xml`, not just loose as
   `activity_repost.xml` at the top of the zip.
2. **Go to the repo on github.com** in my phone's browser (not the app,
   the mobile browser works fine).
3. Tap **Add file -> Upload files**.
4. Upload that zip, but **rename it in the upload box** to exactly:
   ```
   incoming/deploy.zip
   ```
5. Tap **Commit changes** (committing straight to `main`).
6. Go to the **Actions** tab and wait ~1 minute. A new run called
   **"Deploy from uploaded zip"** should appear and finish with a green
   checkmark.
7. Right after that, the normal **build** workflow triggers on the new
   commit and builds the APK as usual.
8. If anything fails, I open the failed run and check the log / send a
   screenshot for help debugging.

## Important rules to remember

- The zip file uploaded must always be named `incoming/deploy.zip` — the
  workflow only triggers on that exact path.
- Folder structure inside the zip must mirror the repo exactly, starting
  from the repo root (e.g. starts with `app/...`, not wrapped in an extra
  folder).
- I do not manually retype or hand-edit file contents on my phone anymore
  — files are given to me pre-written and I just place them via this zip
  system.
- The `incoming/` folder and `.github/workflows/deploy-from-zip.yml` must
  never be deleted, since the whole system depends on both existing.

## Summary for anyone assisting me

> Give me complete, ready-to-use files with their correct repo-relative
> paths. Package them into a single zip with that folder structure
> preserved. I will upload that zip through GitHub's web "Upload files"
> page, renamed to `incoming/deploy.zip`, and commit it directly. A GitHub
> Action already in this repo (`deploy-from-zip.yml`) takes care of
> unpacking it into place and committing the result — I don't need to
> touch individual files by hand.
