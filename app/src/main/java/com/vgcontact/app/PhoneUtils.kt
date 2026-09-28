package com.vgcontact.app

/**
 * One place that cleans and checks a typed phone number, used by BOTH
 * RegisterActivity and LoginActivity so the same number always matches.
 *
 * Rule: exactly 11 digits, digits only, starting with 0 (e.g. 09110321143).
 * The database enforces the same rule (see supabase/migrations/
 * enforce_phone_11_digits.sql), so app and server always agree.
 */
object PhoneUtils {

    const val LENGTH = 11
    const val ERROR_MESSAGE = "Enter your 11-digit phone number, e.g. 09110321143"

    /** Keeps digits only (drops spaces, dashes, brackets, "+" ...). */
    fun clean(raw: String?): String = raw.orEmpty().filter { it.isDigit() }

    /** True only for exactly 11 digits that start with 0. */
    fun isValid(cleaned: String): Boolean =
        cleaned.length == LENGTH && cleaned.all { it.isDigit() } && cleaned.startsWith("0")
}
