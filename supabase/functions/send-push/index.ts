// send-push
//
// Called by the `on_notification_created` Postgres trigger right after a
// row is inserted into `notifications`. Looks up that row, finds the
// target user's current fcm_token, and sends the push via Firebase's
// HTTP v1 API. Marks the row is_sent = true on success.
//
// ---------------------------------------------------------------------
// ONE-TIME SETUP
//
// 1. Firebase Console -> Project settings -> Service accounts ->
//    "Generate new private key". This downloads a JSON file - do NOT
//    commit it anywhere. It contains a private key that can send pushes
//    to every device registered to this Firebase project.
//
// 2. Set it as a secret on this function (from that downloaded JSON):
//      supabase secrets set FIREBASE_PROJECT_ID=<project_id from the json>
//      supabase secrets set FIREBASE_CLIENT_EMAIL=<client_email from the json>
//      supabase secrets set FIREBASE_PRIVATE_KEY="<private_key from the json>"
//
//    The private key contains literal newlines (\n) - if your shell
//    mangles them, set it via the Supabase Dashboard's Edge Function
//    secrets UI instead of the CLI, pasting the value exactly as it
//    appears in the JSON file (including the \n escape sequences).
//
// 3. This function also needs its own Supabase credentials to read the
//    notifications/users tables and write is_sent - these are provided
//    automatically by the Supabase runtime as SUPABASE_URL and
//    SUPABASE_SERVICE_ROLE_KEY, no setup needed.
//
// 4. Deploy:
//      supabase functions deploy send-push
// ---------------------------------------------------------------------

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const FIREBASE_PROJECT_ID = Deno.env.get("FIREBASE_PROJECT_ID")!;
const FIREBASE_CLIENT_EMAIL = Deno.env.get("FIREBASE_CLIENT_EMAIL")!;
const FIREBASE_PRIVATE_KEY = Deno.env.get("FIREBASE_PRIVATE_KEY")!.replace(/\\n/g, "\n");

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

// --- Minting a short-lived Google OAuth access token from the service
// account key, so we can call FCM's HTTP v1 send endpoint. This is the
// standard "JWT bearer" flow for server-to-server Google APIs. ---

function base64url(input: ArrayBuffer | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let str = "";
  for (const b of bytes) str += String.fromCharCode(b);
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function getAccessToken(): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const now = Math.floor(Date.now() / 1000);
  const claimSet = {
    iss: FIREBASE_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };

  const unsigned = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claimSet))}`;

  const pemBody = FIREBASE_PRIVATE_KEY
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const keyData = Uint8Array.from(atob(pemBody), (c) => c.charCodeAt(0));

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    keyData,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsigned),
  );

  const jwt = `${unsigned}.${base64url(signature)}`;

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  if (!tokenResponse.ok) {
    throw new Error(`Failed to mint access token: ${await tokenResponse.text()}`);
  }

  const tokenJson = await tokenResponse.json();
  return tokenJson.access_token;
}

Deno.serve(async (req) => {
  try {
    const { notification_id } = await req.json();
    if (!notification_id) {
      return new Response(JSON.stringify({ error: "notification_id required" }), { status: 400 });
    }

    // 1. Load the notification row.
    const { data: notif, error: notifError } = await supabase
      .from("notifications")
      .select("id, user_id, title, body, is_sent")
      .eq("id", notification_id)
      .single();

    if (notifError || !notif) {
      return new Response(JSON.stringify({ error: "notification not found" }), { status: 404 });
    }

    if (notif.is_sent) {
      return new Response(JSON.stringify({ skipped: "already sent" }), { status: 200 });
    }

    // 2. Look up the target user's current FCM token.
    const { data: user, error: userError } = await supabase
      .from("users")
      .select("fcm_token")
      .eq("id", notif.user_id)
      .single();

    if (userError || !user?.fcm_token) {
      // No token on file (never launched app post-login, or push not
      // supported on this build) - nothing to send. Not an error case;
      // the in-app notification feed (fetch_notifications) still works
      // regardless.
      return new Response(JSON.stringify({ skipped: "no fcm_token for user" }), { status: 200 });
    }

    // 3. Send via FCM HTTP v1.
    const accessToken = await getAccessToken();

    const fcmResponse = await fetch(
      `https://fcm.googleapis.com/v1/projects/${FIREBASE_PROJECT_ID}/messages:send`,
      {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: user.fcm_token,
            notification: {
              title: notif.title,
              body: notif.body,
            },
          },
        }),
      },
    );

    const fcmResult = await fcmResponse.json();

    if (!fcmResponse.ok) {
      // Common cause: fcm_token is stale (app uninstalled, token expired).
      // Leave is_sent = false so this is visible/retryable rather than
      // silently swallowed; the in-app notification still shows regardless.
      return new Response(JSON.stringify({ error: "fcm send failed", detail: fcmResult }), { status: 502 });
    }

    // 4. Mark as sent.
    await supabase
      .from("notifications")
      .update({ is_sent: true })
      .eq("id", notif.id);

    return new Response(JSON.stringify({ success: true }), { status: 200 });
  } catch (err) {
    return new Response(JSON.stringify({ error: String(err) }), { status: 500 });
  }
});
