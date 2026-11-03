package com.samosaai.masala

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samosaai.masala.permissions.PermissionGate
import com.samosaai.masala.permissions.hasMarker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `PermissionGate` must carry a real answer back from the dialog host activity. Granted up front,
 * the request returns true through the whole chain (activity → ActivityResult → CompletableDeferred)
 * without touching a system dialog, which is exactly the plumbing that failed before.
 */
@RunWith(AndroidJUnit4::class)
class PermissionGateTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test
    fun `a held permission comes back true through the dialog host`() = runBlocking {
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        assertTrue(hasMarker(app, Manifest.permission.RECORD_AUDIO))
        val answer = withTimeoutOrNull(20_000) { PermissionGate.request(Manifest.permission.RECORD_AUDIO) }
        assertTrue("the gate never answered (timeout)", answer == true)
    }

    @Test
    fun `a permission that cannot be asked for answers false without hanging`() = runBlocking {
        val answer = withTimeoutOrNull(5_000) { PermissionGate.request("special:not_a_real_capability") }
        assertFalse(answer == true)
    }
}
