package com.samosaai.masala.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.samosaai.masala.tools.Capability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The one way a tool asks for a permission and waits for the answer (plan: a grant must never
 * arrive after the call has already failed).
 *
 * No Compose and no lifecycle: a tiny transparent activity shows the system dialog and reports
 * back here, so a request works from any thread and any screen — including when no screen has
 * registered anything, which is what silently broke the earlier attempt.
 */
object PermissionGate : CapabilityPrompt {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** How long a call waits for the user: long enough to read the dialog, short enough to end. */
    private const val TIMEOUT_MS = 120_000L

    const val EXTRA_MARKER = "marker"

    /** The application context, set once in `MasalaApp`. */
    @Volatile
    var context: Context? = null

    override suspend fun request(marker: String): Boolean {
        val app = context ?: return false

        // Special access has its own settings page, and its result can't be observed: open it and
        // let the tool report what is still missing rather than pretending it was granted.
        val capability = Capability.byMarker(marker)
        if (capability != null) {
            capability.settingsIntent(app)?.let { intent -> runCatching { app.startActivity(intent) } }
            return false
        }
        if (!isRuntimePermission(marker)) return false

        val own = CompletableDeferred<Boolean>()
        val inFlight = pending.putIfAbsent(marker, own)
        if (inFlight != null) return inFlight.await()
        return try {
            val started = runCatching {
                app.startActivity(
                    Intent(app, PermissionRequestActivity::class.java)
                        .putExtra(EXTRA_MARKER, marker)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!started) false else withTimeoutOrNull(TIMEOUT_MS) { own.await() } ?: false
        } finally {
            pending.remove(marker)
        }
    }

    /** Called by [PermissionRequestActivity] with the user's answer. */
    fun complete(marker: String, granted: Boolean) {
        pending[marker]?.complete(granted)
    }

    /** Used by tests to start from a known state. */
    internal fun resetForTest() {
        pending.clear()
    }

    fun isRuntimePermission(marker: String): Boolean =
        marker.startsWith("android.permission.") || marker.startsWith("com.termux.permission.")
}

/** Whether the phone actually holds what [marker] asked for, checked after the dialog closes. */
fun hasMarker(context: Context, marker: String): Boolean = when {
    PermissionGate.isRuntimePermission(marker) ->
        ContextCompat.checkSelfPermission(context, marker) == PackageManager.PERMISSION_GRANTED
    else -> Capability.byMarker(marker)?.isAvailable(context) ?: false
}
