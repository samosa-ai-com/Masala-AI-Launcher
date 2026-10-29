package com.samosaai.masala.permissions

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.tools.Capability
import com.samosaai.masala.tools.HealthTool

/**
 * Asks for whatever a tool just said it needs, instead of telling the user to go hunting in
 * Settings: a runtime permission gets the system dialog, special access jumps to its own screen.
 * The caller decides what to do once the answer is known (the debug screen re-runs the call).
 */
class CapabilityRequester internal constructor(
    private val context: Context,
    private val requestRuntime: (String) -> Unit,
    private val requestHealth: () -> Unit,
    private val onResult: (String, Boolean) -> Unit,
) {
    /** What asking for a marker did, so the caller knows whether to wait for an answer. */
    enum class Ask {
        /** A dialog reports back through [onResult]; retrying then is safe. */
        OBSERVED,

        /** A settings screen was opened. Its result can't be observed, so never retry blindly. */
        SCREEN_ONLY,

        /** Nothing to show (Termux must be installed, for instance). */
        UNSUPPORTED,
    }

    fun request(marker: String): Ask = when {
        marker.startsWith(HEALTH_MARKER) -> {
            requestHealth()
            Ask.OBSERVED
        }
        marker.startsWith("android.permission.") || marker.startsWith("com.termux.permission.") -> {
            requestRuntime(marker)
            Ask.OBSERVED
        }
        else -> {
            val capability = Capability.byMarker(marker) ?: return Ask.UNSUPPORTED
            val intent = capability.settingsIntent(context) ?: return Ask.UNSUPPORTED
            context.startActivity(intent)
            Ask.SCREEN_ONLY
        }
    }

    internal fun deliver(marker: String, granted: Boolean) = onResult(marker, granted)

    internal companion object {
        /** `ActionResult.HEALTH_CONNECT`'s marker; Health Connect has its own grant screen. */
        const val HEALTH_MARKER = "special:health_connect"
    }
}

/**
 * The requester as Compose sees it. [onResult] runs when the user answers a dialog; settings
 * screens report nothing, so those come back as [CapabilityRequester.Ask.SCREEN_ONLY] instead.
 */
@Composable
fun rememberCapabilityRequester(onResult: (marker: String, granted: Boolean) -> Unit): CapabilityRequester {
    val context = LocalContext.current
    val latestResult by rememberUpdatedState(onResult)
    var pending by remember { mutableStateOf<String?>(null) }

    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val marker = pending
        pending = null
        if (marker != null) latestResult(marker, granted)
    }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        // The contract reports which permissions were granted; the tools re-check anyway.
        latestResult(CapabilityRequester.HEALTH_MARKER, true)
    }

    return remember(context) {
        CapabilityRequester(
            context = context,
            requestRuntime = { marker ->
                pending = marker
                runtime.launch(marker)
            },
            requestHealth = { health.launch(HealthTool.PERMISSIONS) },
            onResult = { marker, granted -> latestResult(marker, granted) },
        )
    }
}

/** The marker a tool result is waiting on, if any. */
fun ToolResult.capabilityNeeded(): String? = (this as? ToolResult.NeedsCapability)?.capability
