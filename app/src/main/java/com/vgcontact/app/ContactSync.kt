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
 * Naming: every group member / referral contact this app saves is named
 * "<name> VGC<N>" (for example "Chidera VGC3"). N is the lowest number not
 * already used on the phone. The VGC<N> ending is how the app recognises its
 * own contacts by reading the phone itself, so nothing is lost if app data is
 * cleared. The two SYSTEM contacts (admin number and status number, flagged
 * is_system by the server) are saved with their plain name and NO tag, so
 * they are never removed by "Delete My Contacts" or by a sync clean-up.
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

    // Raw contacts that still exist. A contact deleted in the phone's Contacts app can
    // linger flagged as deleted for a while, and its data rows can still show up in
    // phone-number queries, which made sync think the number was still saved.
    private fun liveRawIds(context: Context): Set<Long> {
        val ids = HashSet<Long>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.DELETED} = 0",
            null, null
        )?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    private fun numbersOnPhone(context: Context): Set<String> {
        val keys = HashSet<String>()
        val live = liveRawIds(context)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            val rawCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            val col = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                if (cursor.getLong(rawCol) !in live) continue
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
        val live = liveRawIds(context)
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
                if (rawId !in live) continue
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

    /** The insert steps for contacts saved as "<name> VGC<n>": 3 steps per contact. */
    private fun buildInsertOps(items: List<Pair<SupabaseClient.SyncContact, Int>>): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        for ((c, n) in items) {
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
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        if (c.isSystem) c.name else "${c.name} $TAG$n"
                    )
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
        return ops
    }

    /** Removes every contact this app saved (name ends in VGC<N>). Returns how many were removed. */
    fun deleteAll(context: Context): Int {
        if (!hasPermission(context)) return 0
        SyncPrefs.resetToday(context)
        return deleteRawContacts(context, scanVgcContacts(context).map { it.rawId })
    }

    // Only one sync at a time (permission screen, Home, background worker): two runs at once
    // could both see a number as missing and save it twice. The second run waits, then finds
    // the contacts already on the phone.
    private val runLock = Any()

    fun run(context: Context, userId: String): Result = synchronized(runLock) { runLocked(context, userId) }

    private fun runLocked(context: Context, userId: String): Result {
        if (BanPrefs.isBanned(context)) return Result(0, 0, ERR_BANNED)
        if (SyncPrefs.isPaused(context)) return Result(0, 0, ERR_PAUSED)
        if (!SupabaseClient.isOnline(context)) return Result(0, 0, ERR_NO_INTERNET)
        val wanted = SupabaseClient.fetchSyncContacts(userId) ?: return Result(0, 0, ERR_FETCH)
        // The server answered: this counts as a sync. Stamps last_synced_at and restarts
        // the 5-day inactivity reminder (app opens alone do not count).
        SupabaseClient.recordSync(context, userId)
        SyncPrefs.recordSyncSuccess(context)

        // Safety: the list always holds at least the admin number, so an empty
        // list means something went wrong, not "everyone left". Never delete
        // or add anything on an empty list.
        if (wanted.none { it.phone.isNotBlank() }) return Result(0, 0, null)

        val wantedKeys = wanted.map { key(it.phone) }.filter { it.isNotEmpty() }.toSet()

        // 1. Remove VGC contacts that dropped out of the server list.
        val existing = scanVgcContacts(context)
        // Also treated as stale: a TAGGED copy of the admin/status number saved by an older build.
        // It is removed here and saved again below with the plain name.
        val systemKeys = wanted.filter { it.isSystem }.map { key(it.phone) }.filter { it.isNotEmpty() }.toSet()
        val stale = existing.filter { row ->
            row.keys.none { it in wantedKeys } || row.keys.any { it in systemKeys }
        }
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
            // Give each contact its VGC number first, then save the whole batch in one go.
            val numbered = ArrayList<Pair<SupabaseClient.SyncContact, Int>>()
            for (c in batch) {
                if (c.isSystem) {
                    numbered.add(c to 0) // plain name, no number used
                } else {
                    val n = lowestFreeNumber(numbersInUse)
                    numbersInUse.add(n)
                    numbered.add(c to n)
                }
            }
            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, buildInsertOps(numbered))
                added += numbered.size
            } catch (e: Exception) {
                // The batch failed: one bad number must not cost the other contacts.
                // Retry them one by one so only the bad one is lost.
                for (item in numbered) {
                    try {
                        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, buildInsertOps(listOf(item)))
                        added += 1
                    } catch (e2: Exception) {
                        failed += 1
                        if (item.second > 0) numbersInUse.remove(item.second)
                    }
                }
            }
        }
        SyncPrefs.recordAdded(context, added)
        return Result(added, failed, if (failed > 0) "$failed contact(s) failed to save" else null, removed)
    }
}
