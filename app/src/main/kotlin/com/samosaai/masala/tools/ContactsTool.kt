package com.samosaai.masala.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.tool.ActionResult

/**
 * Contacts lookup by name (plan: `READ_CONTACTS` was allowlisted for speech hints but nothing
 * could read it). Phone numbers and email addresses only — enough to prefill the dialer or a
 * message, never to send anything.
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
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val selection = if (needle.isEmpty()) null else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        }
        val args = if (needle.isEmpty()) null else arrayOf("%$needle%")
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
}
