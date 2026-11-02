package com.samosaai.masala.miniapps

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The create/update auto-open dedupe: tool-open + reply effect must not stack two. */
class MiniAppOpenGuardTest {
    private var now = 1_000_000L

    @AfterTest fun reset() {
        MiniAppOpenGuard.clock = android.os.SystemClock::uptimeMillis
        MiniAppOpenGuard.clear("snake")
        MiniAppOpenGuard.clear("dice")
    }

    private fun useFakeClock() {
        MiniAppOpenGuard.clock = { now }
    }

    @Test fun `unknown id is not freshly opened`() {
        useFakeClock()
        assertFalse(MiniAppOpenGuard.isFreshlyOpened("snake"))
    }

    @Test fun `noted id stays fresh inside the window`() {
        useFakeClock()
        MiniAppOpenGuard.noteOpened("snake")
        now += MiniAppOpenGuard.WINDOW_MS - 1
        assertTrue(MiniAppOpenGuard.isFreshlyOpened("snake"))
    }

    @Test fun `note expires past the window`() {
        useFakeClock()
        MiniAppOpenGuard.noteOpened("snake")
        now += MiniAppOpenGuard.WINDOW_MS + 1
        assertFalse(MiniAppOpenGuard.isFreshlyOpened("snake"))
    }

    @Test fun `clear drops the note at once`() {
        useFakeClock()
        MiniAppOpenGuard.noteOpened("snake")
        MiniAppOpenGuard.clear("snake")
        assertFalse(MiniAppOpenGuard.isFreshlyOpened("snake"))
    }

    @Test fun `notes are per app id`() {
        useFakeClock()
        MiniAppOpenGuard.noteOpened("snake")
        assertFalse(MiniAppOpenGuard.isFreshlyOpened("dice"))
        assertTrue(MiniAppOpenGuard.isFreshlyOpened("snake"))
    }

    @Test fun `blank ids are never noted`() {
        useFakeClock()
        MiniAppOpenGuard.noteOpened("")
        assertFalse(MiniAppOpenGuard.isFreshlyOpened(""))
    }
}
