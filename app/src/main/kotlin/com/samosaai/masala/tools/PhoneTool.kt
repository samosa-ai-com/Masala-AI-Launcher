package com.samosaai.masala.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

/** From the private prototype's PhoneTool: only [dialNumber], which opens the dialer and never calls (plan Phase 1). */
class PhoneTool(private val context: Context) {

    private val numberPattern = Regex("^[+]?[0-9()\\-\\s#*]{2,20}$")

    fun dialNumber(number: String): ActionResult {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(numberPattern)) {
            return ActionResult.error(
                "'$number' does not look like a valid phone number."
            )
        }
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) {
            return ActionResult.error(
                "'$number' has no digits — a phone number needs at least one digit."
            )
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$trimmed")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ActionResult.error(
                    "No dialer app is available on this device."
                )
            }
            context.startActivity(intent)
            ActionResult.ok("Opened the dialer with $trimmed. The user must press call themselves.")
        } catch (e: Exception) {
            ActionResult.error("Could not open the dialer: ${e.message}")
        }
    }
}
