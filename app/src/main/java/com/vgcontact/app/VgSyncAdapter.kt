package com.vgcontact.app

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.util.Log

/**
 * Android "sync adapter": an EXTRA trigger for the contact sync. Nothing else changes.
 *
 * The app registers a small login-free account called "VGContact" (Settings > Accounts) and asks
 * Android to run a periodic sync for it. Android's own sync scheduler then wakes
 * [VgSyncAdapter.onPerformSync], which runs the SAME ContactSync the Sync button and the
 * WorkManager job run. Contacts are still saved loose on the phone exactly as before: no contact
 * belongs to this account, so nothing is moved and WhatsApp sees contacts as it always did.
 *
 * Why: some phone brands (Tecno, Infinix, itel ...) silence the app's own WorkManager alarm but
 * treat account syncing more gently. If one trigger is blocked the other may still run. Two
 * triggers firing together is safe: ContactSync.run lets only one run at a time.
 *
 * It is controlled by the admin setting `sync_adapter_enabled` (default OFF). The phone checks it
 * on every Home open and again every time the adapter wakes. OFF removes the account.
 * It is also removed on logout and when the account is banned.
 */
object SyncAdapterSetup {
    const val ACCOUNT_TYPE = "com.vgcontact.app.account"
    private const val ACCOUNT_NAME = "VGContact"
    private const val TAG = "VGC"
    private val AUTHORITY = ContactsContract.AUTHORITY

    private fun account() = Account(ACCOUNT_NAME, ACCOUNT_TYPE)

    /** Creates the account (once) and the periodic sync. Safe to call on every app open. */
    fun enable(context: Context) {
        try {
            val acc = account()
            // true only when the account did not exist yet (false if it is already there).
            val created = AccountManager.get(context).addAccountExplicitly(acc, null, null)
            if (created) {
                ContentResolver.setIsSyncable(acc, AUTHORITY, 1)
                // Only on creation: if the user later switches Auto-sync off for this account
                // in Settings, that choice is respected.
                ContentResolver.setSyncAutomatically(acc, AUTHORITY, true)
            }
            val hours = SyncPrefs.getIntervalHours(context)
            if (created || SyncPrefs.getAdapterPeriodHours(context) != hours) {
                ContentResolver.addPeriodicSync(acc, AUTHORITY, Bundle.EMPTY, hours * 3600L)
                SyncPrefs.setAdapterPeriodHours(context, hours)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync adapter enable failed", e)
        }
    }

    /** Removes the account (and with it the periodic sync). Safe to call when it does not exist. */
    fun disable(context: Context) {
        try {
            AccountManager.get(context).removeAccountExplicitly(account())
        } catch (e: Exception) {
            Log.w(TAG, "sync adapter disable failed", e)
        }
        SyncPrefs.setAdapterPeriodHours(context, 0)
    }

    /** Makes the phone match the admin switch: on and logged in and not banned = account present. */
    fun apply(context: Context, enabled: Boolean) {
        SyncPrefs.setAdapterEnabled(context, enabled)
        if (enabled && SessionManager(context).isLoggedIn() && !BanPrefs.isBanned(context)) {
            enable(context)
        } else {
            disable(context)
        }
    }

    /** Asks the server for the admin switch (falls back to what the phone remembers), then applies it. Blocking. */
    fun refreshFromServer(context: Context) {
        val userId = SessionManager(context).getUserId() ?: return
        val on = SupabaseClient.fetchSyncAdapterEnabled(userId) ?: SyncPrefs.getAdapterEnabled(context)
        apply(context, on)
    }

    /** The user picked a new "Sync every N hours": move the adapter's schedule too. */
    fun refreshInterval(context: Context) {
        if (SyncPrefs.getAdapterEnabled(context)) enable(context)
    }
}

/** Runs when Android wakes the "VGContact" account. Same sync as the button. */
class VgSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val ctx = context.applicationContext
        val session = SessionManager(ctx)
        val userId = session.getUserId()
        if (!session.isLoggedIn() || userId.isNullOrBlank()) {
            SyncAdapterSetup.disable(ctx)
            return
        }
        if (BanPrefs.isBanned(ctx) || SyncPrefs.isPaused(ctx)) return

        // Admin switch: ask the server, so turning it OFF also stops phones that are not opened.
        // Offline: use what the phone remembered.
        val fromServer = SupabaseClient.fetchSyncAdapterEnabled(userId)
        val on = fromServer ?: SyncPrefs.getAdapterEnabled(ctx)
        if (fromServer != null) SyncPrefs.setAdapterEnabled(ctx, fromServer)
        if (!on) {
            SyncAdapterSetup.disable(ctx)
            return
        }

        if (!ContactSync.hasPermission(ctx)) return
        val result = ContactSync.run(ctx, userId, background = true, source = "adapter")
        // An automatic run that finished cleanly also clears the "sync looks stalled" banner.
        if (result.error == null) SyncPrefs.recordBackgroundSyncSuccess(ctx)
        // Offline or the server failed: tell Android so it retries with its own back-off.
        if (result.error == ContactSync.ERR_NO_INTERNET || result.error == ContactSync.ERR_FETCH) {
            syncResult.stats.numIoExceptions++
        }
    }
}

class VgSyncService : Service() {
    override fun onCreate() {
        synchronized(lock) {
            if (adapter == null) adapter = VgSyncAdapter(applicationContext)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = adapter?.syncAdapterBinder

    companion object {
        private val lock = Any()
        private var adapter: VgSyncAdapter? = null
    }
}

/**
 * Android requires an "authenticator" for any account type. This one only exists so the
 * "VGContact" account can exist: the app adds it itself, there is no sign-in, and adding it by
 * hand from Settings is refused.
 */
class VgAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<String>?,
        options: Bundle?
    ): Bundle? = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "VGContact adds this account by itself.")
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?, account: Account?, features: Array<String>?
    ): Bundle? = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

class VgAccountService : Service() {
    private var authenticator: VgAuthenticator? = null

    override fun onCreate() {
        authenticator = VgAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder? = authenticator?.iBinder
}
