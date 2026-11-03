package com.samosaai.masala.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.samosaai.masala.core.tool.ActionResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The phone's coarse location, rounded to about a kilometre. Asks the phone for a fresh fix
 * first (a few seconds), falling back to the last known one. Only answers when the location
 * permission is granted, and never runs unattended: background location needs a Play declaration
 * a launcher won't get.
 */
class LocationTool(private val context: Context) {

    suspend fun getLocation(): ActionResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.permissionNeeded(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                "The Location permission is not granted. Go to Settings → Permissions → Location " +
                    "and enable it, then ask again.",
            )
        }
        return try {
            val manager = context.getSystemService(LocationManager::class.java)
                ?: return ActionResult.error("This phone has no location service to ask.")
            // GPS needs FINE location, which Masala never asks for: network and passive only,
            // like the weather's place lookup.
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { p -> runCatching { manager.isProviderEnabled(p) }.getOrDefault(false) }
            val fix = freshFix(manager, providers) ?: providers
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time } ?: return ActionResult.error(
                "No recent location fix. Move somewhere with signal and ask again — it can take a minute on a fresh boot.",
            )
            ActionResult.ok(describe(fix))
        } catch (e: SecurityException) {
            ActionResult.permissionNeeded(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                "Reading the location was refused: ${e.message}",
            )
        }
    }

    /** One fresh single fix, or null after the timeout (the caller falls back to last-known). */
    private suspend fun freshFix(manager: LocationManager, providers: List<String>): Location? {
        val provider = providers.firstOrNull() ?: return null
        return withTimeoutOrNull(FRESH_FIX_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                // One thread, shut down after: the callback fires at most once.
                val exec = Executors.newSingleThreadExecutor()
                try {
                    manager.getCurrentLocation(provider, signal, exec) {
                        if (cont.isActive) cont.resume(it)
                        exec.shutdown()
                    }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                    exec.shutdown()
                }
            }
        }
    }

    private fun describe(fix: Location): String {
        // Rounded to about a kilometre: the forecast needs no more, and less leaves the phone.
        val lat = Math.round(fix.latitude * 100) / 100.0
        val lon = Math.round(fix.longitude * 100) / 100.0
        val accuracy = if (fix.hasAccuracy()) " (±${fix.accuracy.toInt()}m)" else ""
        return "The phone is near $lat, $lon$accuracy (coarse fix, rounded to about a kilometre)."
    }

    internal companion object {
        const val FRESH_FIX_MS = 8_000L
    }
}
