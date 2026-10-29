package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Deleting a pinned mini app takes its pin off home too; the layout history is put back afterwards. */
@RunWith(AndroidJUnit4::class)
class MiniAppDeleteTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    @Test
    fun deletingAPinnedAppUnpinsIt() = runBlocking {
        // Right after install the saved layout may still be loading, and pinning then does nothing.
        withTimeout(10_000) { app.layouts.base.filterNotNull().first() }
        val before = app.layouts.historyState.value.versions.map { it.id }.toSet()
        val current = app.layouts.historyState.value.versions.firstOrNull { it.current }?.id
        val id = checkNotNull(app.miniApps.create("Delete test", "<html><body>x</body></html>"))
        try {
            assertEquals(emptyList<Any>(), app.layouts.pinMiniApp(id, "Delete test"))
            assertTrue(app.layouts.isMiniAppPinned(id))
            app.deleteMiniApp(id)
            assertNull(app.miniApps.get(id))
            assertFalse("the pin stayed on home", app.layouts.isMiniAppPinned(id))
        } finally {
            app.miniApps.delete(id)
            current?.let { app.layouts.select(it) }
            app.layouts.historyState.value.versions.map { it.id }.filterNot { it in before }.forEach { app.layouts.deleteVersion(it) }
        }
    }
}
