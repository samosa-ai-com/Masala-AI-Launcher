package com.samosaai.masala.permissions

import android.content.Context
import android.content.Intent

/**
 * A tool call asks here and **waits** for the user's answer: a permission must never turn into a
 * failure the model apologises for while the dialog is still on screen.
 */
fun interface CapabilityPrompt {
    suspend fun request(marker: String): Boolean
}
