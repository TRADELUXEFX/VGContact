# VGContact

Android app for the VGContact repost-to-grow-viewers platform. Backend is Supabase.
See VGCONTACT_SYSTEM.md for how everything works.

## Tech Stack
- Kotlin + XML layouts
- Material Components
- Supabase backend (database functions called with the anon key)
- OkHttp3 for API calls

## Setup
1. Create `local.properties` from `local.properties.example` with your Supabase credentials
2. Run `./gradlew assembleDebug`

The database lives in Supabase and is the source of truth. Files in `supabase/migrations` are history, not a full copy of it.
