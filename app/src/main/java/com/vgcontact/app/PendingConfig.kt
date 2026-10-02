package com.vgcontact.app

import android.content.Context
import kotlin.concurrent.thread

/**
 * The numbers shown on the pending sheet. Live values are rows in the app_settings table,
 * so they can be changed without a new APK:
 *   pending_viewers       what the user unlocks, e.g. 500+
 *   pending_min_views     views the status needs before sending proof, e.g. 30
 *   pending_pay_amount    price to skip, e.g. ₦1,500
 *   pending_verify_hours  how long verification takes, e.g. 24
 *   pay_bank_name         bank shown on the Pay to verify screen
 *   pay_account_name      account name shown there
 *   pay_account_number    account number shown there (no fallback: empty until fetched)
 * The last value fetched is cached on the phone; until the first fetch (or when offline)
 * the fallbacks in strings_pending.xml are used.
 */
object PendingConfig {
    private const val PREFS = "vg_settings"

    private enum class Item(val key: String, val fallback: Int) {
        VIEWERS("pending_viewers", R.string.pending_default_viewers),
        MIN_VIEWS("pending_min_views", R.string.pending_default_min_views),
        PAY_AMOUNT("pending_pay_amount", R.string.pending_default_pay_amount),
        VERIFY_HOURS("pending_verify_hours", R.string.pending_default_verify_hours),
        PAY_BANK("pay_bank_name", R.string.pay_default_empty),
        PAY_ACCOUNT_NAME("pay_account_name", R.string.pay_default_empty),
        PAY_ACCOUNT_NUMBER("pay_account_number", R.string.pay_default_empty)
    }

    /** Call when the sheet opens; new values show the next time it opens. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        thread {
            Item.values().forEach { item ->
                SupabaseClient.fetchSetting(item.key) { value ->
                    if (value != null) {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(item.key, value.trim()).apply()
                    }
                }
            }
        }
    }

    private fun get(context: Context, item: Item): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(item.key, null)
            ?: context.getString(item.fallback)

    fun viewers(context: Context) = get(context, Item.VIEWERS)
    fun minViews(context: Context) = get(context, Item.MIN_VIEWS)
    fun payAmount(context: Context) = get(context, Item.PAY_AMOUNT)
    fun verifyHours(context: Context) = get(context, Item.VERIFY_HOURS)
    fun payBank(context: Context) = get(context, Item.PAY_BANK)
    fun payAccountName(context: Context) = get(context, Item.PAY_ACCOUNT_NAME)
    fun payAccountNumber(context: Context) = get(context, Item.PAY_ACCOUNT_NUMBER)
}
