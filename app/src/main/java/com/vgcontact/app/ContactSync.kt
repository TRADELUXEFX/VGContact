package com.vgcontact.app

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Sync contacts. The database is the mirror: the server returns every
 * contact this user should have (admin number, anonymous number, and the
 * members of every group they belong to), already named.
 *
 * Naming: every contact this app saves is named "<name> VGC<N>" (for
 * example "Chidera VGC3"). N is the lowest number not already used on the
 * phone. The VGC<N> ending is how the app recognises its own contacts by
 * reading the phone itself, so nothing is lost if app data is cleared.
 *
 * A sync does three things:
 *  1. Removes VGC contacts whose number is no longer in the server list
 *     (member banned or removed). Never touches contacts without the tag.
 *  2. Adds server numbers that are not on the phone yet. A number already
 *     saved on the phone under any other name is left alone, not duplicated.
 *  3. Never renames anything.
 *
 * Call run() from a background thread.
 */
object ContactSync {

    data class Result(val added: Int, val failed: Int, val error: String?, val removed: Int = 0)

    const val ERR_NO_INTERNET = "NO_INTERNET"
    const val ERR_FETCH = "FETCH_FAILED"
    const val ERR_PAUSED = "PAUSED"
    const val ERR_BANNED = "BANNED"

    private const val TAG = "VGC"
    private val TAG_REGEX = Regex("VGC(\\d+)$")

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

    /** One saved VGC contact on the phone: its raw id, tag number and phone-number keys. */
    private class VgcContact(val rawId: Long, val number: Int, val keys: MutableSet<String>)

    /** Every contact on the phone whose name ends in VGC<digits>, grouped by raw contact. */
    private fun scanVgcContacts(context: Context): List<VgcContact> {
        val byRaw = LinkedHashMap<Long, VgcContact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%$TAG%"),
            null
        )?.use { c ->
            val rawCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = c.getString(nameCol)?.trim() ?: continue
                val n = TAG_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val k = key(c.getString(numCol) ?: "")
                val rawId = c.getLong(rawCol)
                val entry = byRaw.getOrPut(rawId) { VgcContact(rawId, n, HashSet()) }
                if (k.isNotEmpty()) entry.keys.add(k)
            }
        }
        return byRaw.values.toList()
    }

    private fun deleteRawContacts(context: Context, rawIds: List<Long>): Int {
        if (rawIds.isEmpty()) return 0
        var deleted = 0
        for (batch in rawIds.chunked(100)) {
            val ops = ArrayList<ContentProviderOperation>()
            for (id in batch) {
                ops.add(
                    ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                        .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(id.toString()))
                        .build()
                )
            }
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                deleted += batch.size
            } catch (e: Exception) {
                Log.w(TAG, "deleteRawContacts batch failed", e)
            }
        }
        return deleted
    }

    private fun lowestFreeNumber(inUse: Set<Int>): Int {
        var n = 1
        while (n in inUse) n++
        return n
    }

    /** Removes every contact this app saved (name ends in VGC<N>). Returns how many were removed. */
    fun deleteAll(context: Context): Int {
        if (!hasPermission(context)) return 0
        SyncPrefs.resetToday(context)
        return deleteRawContacts(context, scanVgcContacts(context).map { it.rawId })
    }

    fun run(context: Context, userId: String): Result {
        if (BanPrefs.isBanned(context)) return Result(0, 0, ERR_BANNED)
        if (SyncPrefs.isPaused(context)) return Result(0, 0, ERR_PAUSED)
        if (!SupabaseClient.isOnline(context)) return Result(0, 0, ERR_NO_INTERNET)
        val wanted = SupabaseClient.fetchSyncContacts(userId) ?: return Result(0, 0, ERR_FETCH)
        // The server answered: this counts as a sync. Stamps last_synced_at and restarts
        // the 5-day inactivity reminder (app opens alone do not count).
        SupabaseClient.recordSync(context, userId)

        // Safety: the list always holds at least the admin number, so an empty
        // list means something went wrong, not "everyone left". Never delete
        // or add anything on an empty list.
        if (wanted.none { it.phone.isNotBlank() }) return Result(0, 0, null)

        val wantedKeys = wanted.map { key(it.phone) }.filter { it.isNotEmpty() }.toSet()

        // 1. Remove VGC contacts that dropped out of the server list.
        val existing = scanVgcContacts(context)
        val stale = existing.filter { row -> row.keys.none { it in wantedKeys } }
        val removed = deleteRawContacts(context, stale.map { it.rawId })
        val staleIds = stale.map { it.rawId }.toSet()
        val numbersInUse = existing.filter { it.rawId !in staleIds }.map { it.number }.toMutableSet()

        // 2. Add what is missing.
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
                val n = lowestFreeNumber(numbersInUse)
                numbersInUse.add(n)
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
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "${c.name} $TAG$n")
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
        SyncPrefs.recordAdded(context, added)
        return Result(added, failed, if (failed > 0) "$failed contact(s) failed to save" else null, removed)
    }
}
