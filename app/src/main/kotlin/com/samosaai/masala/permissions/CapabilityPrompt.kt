package com.samosaai.masala.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.samosaai.masala.tools.Capability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A tool call asks here and **waits** for the user's answer (plan: a permission must never turn
 * into a failure the model then apologises for). The tool runs only once it comes back true.
 */
fun interface CapabilityPrompt {
    suspend fun request(marker: String): Boolean
}

/**
 * Routes a request to whichever screen is in front: the debug screen and home each register
 * their own prompt while they are resumed, so the dialog always comes from a visible activity.
 */
class PermissionBroker : CapabilityPrompt {
    private val prompts = ArrayDeque<CapabilityPrompt>()

    fun register(prompt: CapabilityPrompt) {
        if (prompt !in prompts) prompts.addLast(prompt)
    }

    fun unregister(prompt: CapabilityPrompt) {
        prompts.remove(prompt)
    }

    override suspend fun request(marker: String): Boolean = prompts.lastOrNull()?.request(marker) ?: false
}

/** One outstanding ask; the answer arrives from the ActivityResult callback. */
private class Ask(val marker: String, val answer: CompletableDeferred<Boolean>)

/**
 * The prompt as Compose sees it. [PermissionBroker.register] it while the screen is resumed so a
 * tool running in the background still finds a visible activity to show the dialog.
 */
@Composable
fun rememberCapabilityPrompt(broker: PermissionBroker): CapabilityPrompt {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Ask?>(null) }
    val requester = rememberCapabilityRequester { marker, granted ->
        val ask = pending
        if (ask != null && ask.marker == marker) {
            // The callback says what the user chose; the system is the source of truth either way.
            ask.answer.complete(granted && hasMarker(context, marker))
            pending = null
        }
    }
    // Ask from the composition (main thread), never from the tool's IO thread.
    LaunchedEffect(pending) {
        pending?.let { ask ->
            if (requester.request(ask.marker) == CapabilityRequester.Ask.SCREEN_ONLY) {
                // A settings page reports nothing back, so let the tool report what is missing.
                ask.answer.complete(false)
                pending = null
            }
        }
    }
    val prompt = remember {
        CapabilityPrompt { marker ->
            val ask = Ask(marker, CompletableDeferred())
            withContext(Dispatchers.Main) { pending = ask }
            ask.answer.await()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { broker.register(prompt) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { broker.unregister(prompt) }
    return prompt
}

/** Whether the phone now actually holds what [marker] asked for. */
fun hasMarker(context: Context, marker: String): Boolean = when {
    marker.startsWith("android.permission.") ->
        ContextCompat.checkSelfPermission(context, marker) == PackageManager.PERMISSION_GRANTED
    marker.startsWith("com.termux.permission.") ->
        ContextCompat.checkSelfPermission(context, marker) == PackageManager.PERMISSION_GRANTED
    else -> Capability.byMarker(marker)?.isAvailable(context) ?: false
}

