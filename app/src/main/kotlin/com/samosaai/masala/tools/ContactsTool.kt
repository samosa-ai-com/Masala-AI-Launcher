package com.samosaai.masala.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.tool.ActionResult

/**
 * Contacts lookup by name or number, plus adding through the Contacts app (the user confirms
 * there, so adding needs no permission). Phone numbers and email addresses only — enough to
 * prefill the dialer or a message, never to send anything.
 */
class ContactsTool(private val context: Context) {

    fun search(query: String?, limit: Int? = null): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.READ_CONTACTS,
                "The Contacts permission is not granted. Go to Settings → Permissions → Contacts " +
                    "and enable it, then ask again.",
            )
        }
        val max = (limit ?: 10).coerceIn(1, 50)
        val needle = query?.trim().orEmpty()
        val digits = digitsOf(needle)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        // Names match on text; numbers match on text or on digits with wildcards between them,
        // so "+91 98765" finds "+91-98765-43210" however the phone stored the separators.
        val digitPattern = digitsPattern(digits)
        val (selection, args) = when {
            needle.isEmpty() -> null to null
            digitPattern != null && digits != needle -> (
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                ) to arrayOf("%$needle%", "%$needle%", digitPattern)
            else -> (
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                ) to arrayOf("%$needle%", "%$needle%")
        }
        val rows = mutableListOf<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<String>()
                while (cursor.moveToNext() && rows.size < max) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    // The same number appears once per phone type; keep the first.
                    if (!seen.add("$name|$number")) continue
                    rows += "$name — $number"
                }
            }
        } catch (e: SecurityException) {
            return ActionResult.error("Contacts access was refused: ${e.message}")
        }
        if (rows.isEmpty()) {
            return ActionResult.ok(if (needle.isEmpty()) "No contacts on this phone." else "No contact matches '$needle'.")
        }
        return ActionResult.ok(rows.joinToString("\n"))
    }

    /** Opens the Contacts app with a prefilled form; the user reviews and saves it there. */
    fun addContact(name: String, phone: String? = null, email: String? = null): ActionResult {
        if (name.isBlank()) return ActionResult.error("The contact needs a name.")
        return try {
            val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                putExtra(ContactsContract.Intents.Insert.NAME, name.trim())
                phone?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it.trim()) }
                email?.takeIf { it.isNotBlank() }?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it.trim()) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // No resolveActivity check: on Android 11+ it false-negatives without a <queries>
            // entry, while startActivity still works; a missing handler throws below.
            context.startActivity(intent)
            ActionResult.ok("Opened the contacts app to save '$name'. The user reviews it before anything is added.")
        } catch (_: android.content.ActivityNotFoundException) {
            ActionResult.error("No contacts app on this phone handles new contacts.")
        } catch (_: SecurityException) {
            ActionResult.error("The contacts app refused the request.")
        }
    }

    internal companion object {
        /** Digits only, so numbers match however the phone stored the separators. */
        fun digitsOf(text: String): String = text.filter { it.isDigit() }

        /** Number digits with wildcards between them, so stored separators can't break the match. */
        fun digitsPattern(digits: String): String? {
            if (digits.length < 3 || digits.any { !it.isDigit() }) return null
            return digits.toCharArray().joinToString(separator = "%", prefix = "%", postfix = "%")
        }
    }
}
