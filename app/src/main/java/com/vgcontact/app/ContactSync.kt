package com.vgcontact.app

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Sync contacts. The database is the mirror: the server returns every
 * contact this user should have (admin number, anonymous number, and the
 * members of every group they belong to), already named. This adds
 * whichever of those are missing from the phone. It never deletes and
 * never renames anything on the phone.
 *
 * Call run() from a background thread.
 */
object ContactSync {

    data class Result(val added: Int, val failed: Int, val error: String?)

    const val ERR_NO_INTERNET = "NO_INTERNET"
    const val ERR_FETCH = "FETCH_FAILED"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // Numbers are compared on their last 9 digits so 0803..., 234803...
    // and +234803... all count as the same person.
    private fun key(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun numbersOnPhone(context: Context): Set<String> {
        val keys = HashSet<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cursor ->
            val col = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val k = key(cursor.getString(col) ?: "")
                if (k.isNotEmpty()) keys.add(k)
            }
        }
        return keys
    }

    fun run(context: Context, userId: String): Result {
        if (!SupabaseClient.isOnline(context)) return Result(0, 0, ERR_NO_INTERNET)
        val wanted = SupabaseClient.fetchSyncContacts(userId) ?: return Result(0, 0, ERR_FETCH)

        val onPhone = numbersOnPhone(context)
        val missing = wanted.filter {
            it.phone.isNotBlank() && it.name.isNotBlank() && key(it.phone) !in onPhone
        }.distinctBy { key(it.phone) }

        var added = 0
        var failed = 0
        for (batch in missing.chunked(50)) {
            val ops = ArrayList<ContentProviderOperation>()
            for (c in batch) {
                val index = ops.size
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, c.name)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, index)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, c.phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                added += batch.size
            } catch (e: Exception) {
                failed += batch.size
            }
        }
        return Result(added, failed, if (failed > 0) "$failed contact(s) failed to save" else null)
    }
}
