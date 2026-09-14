package com.agentdesk.tools.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of resolving a contact input string.
 */
sealed class Resolution {
    /** Input was already a phone number (E.164 or local format). */
    data class IsNumber(val number: String) : Resolution()

    /** Exactly one contact matched by display name. */
    data class Single(val number: String, val label: String) : Resolution()

    /** Several contacts matched — ambiguous, user must disambiguate. */
    data class Multiple(val labels: List<String>) : Resolution()

    /** Nothing matched (or permission missing). */
    object None : Resolution()
}

/**
 * Resolves a user-provided contact string into a callable/textable phone
 * number.
 *
 * - If the input matches an E.164/local phone pattern it is returned as
 *   [Resolution.IsNumber] directly (no ContactsContract access).
 * - Otherwise the ContactsContract Phone table is queried by display name
 *   (requires READ_CONTACTS). One match -> [Resolution.Single], several
 *   distinct matches -> [Resolution.Multiple].
 * - Missing permission / no match -> [Resolution.None]; callers surface a
 *   friendly error pointing the user to Settings -> Permissions.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun resolve(input: String): Resolution = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (looksLikePhoneNumber(trimmed)) {
            return@withContext Resolution.IsNumber(trimmed)
        }
        if (!hasContactsPermission()) return@withContext Resolution.None
        queryContactsByName(trimmed)
    }

    private fun queryContactsByName(name: String): Resolution {
        val rows = mutableListOf<Triple<String, String, String>>() // (displayName, number, typeLabel)
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (cursor.moveToNext()) {
                    if (nameIdx < 0 || numberIdx < 0) continue
                    val displayName = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    val typeLabel = if (typeIdx >= 0) {
                        ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, cursor.getInt(typeIdx), ""
                        ).toString()
                    } else ""
                    rows.add(Triple(displayName, number, typeLabel))
                }
            }
        } catch (_: Exception) {
            return Resolution.None
        }

        val distinctNames = rows.map { it.first }.distinct()
        return when {
            distinctNames.isEmpty() -> Resolution.None
            distinctNames.size > 1 -> Resolution.Multiple(distinctNames)
            else -> {
                // Single display name — ambiguous only if it holds several numbers
                val numbers = rows.map { it.second }.distinct()
                if (numbers.size > 1) {
                    Resolution.Multiple(rows.map { it.third }.filter { it.isNotBlank() }.distinct())
                } else {
                    Resolution.Single(numbers.first(), distinctNames.first())
                }
            }
        }
    }
}

/**
 * Returns true if [input] matches an E.164 or local phone number pattern.
 * Pure function so it can be unit tested on the JVM.
 *
 * Examples: "+15551234567", "555-123-4567", "(555) 123-4567".
 * Display names like "Mom" or "John Smith" do not match.
 */
internal fun looksLikePhoneNumber(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.length < MIN_PHONE_LENGTH) return false
    if (!trimmed.first().isDigit() && trimmed.first() != '+') return false
    if (!trimmed.last().isDigit()) return false
    if (!trimmed.all { it.isDigit() || it in "+-(). " }) return false
    val digitCount = trimmed.count { it.isDigit() }
    return digitCount in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS
}

private const val MIN_PHONE_LENGTH = 7
private const val MIN_PHONE_DIGITS = 5
private const val MAX_PHONE_DIGITS = 15
