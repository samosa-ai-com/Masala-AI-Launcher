package com.samosaai.masala.notifications

import com.samosaai.masala.chat.ChatResult
import com.samosaai.masala.chat.TurnRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reply notice fires only for watched-away finished turns, never twice for an opened app. */
class ReplyDoneGateTest {
    private fun miniApp(ref: String) = ChatResult("mini_app", "Snake", ref)

    @Test fun `masala reply while away notifies`() {
        assertTrue(shouldNotifyReply(TurnRole.MASALA, "Done.", emptyList(), true, false))
    }

    @Test fun `error while away notifies`() {
        assertTrue(shouldNotifyReply(TurnRole.ERROR, "Failed.", emptyList(), true, false))
    }

    @Test fun `user turns never notify`() {
        assertFalse(shouldNotifyReply(TurnRole.USER, "Make me snake", emptyList(), true, false))
    }

    @Test fun `watching it finish never notifies`() {
        assertFalse(shouldNotifyReply(TurnRole.MASALA, "Done.", emptyList(), true, true))
    }

    @Test fun `minimized reply on home notifies`() {
        assertTrue(shouldNotifyReply(TurnRole.MASALA, "Done.", emptyList(), true, true, minimized = true))
    }

    @Test fun `opted out never notifies`() {
        assertFalse(shouldNotifyReply(TurnRole.MASALA, "Done.", emptyList(), false, false))
    }

    @Test fun `blank text never notifies`() {
        assertFalse(shouldNotifyReply(TurnRole.MASALA, "  ", emptyList(), true, false))
    }

    @Test fun `auto-opened mini app does not double notify`() {
        assertFalse(
            shouldNotifyReply(TurnRole.MASALA, "Made Snake.", listOf(miniApp("s")), true, false) { it == "s" },
        )
    }

    @Test fun `mini app opened long ago still notifies`() {
        assertTrue(
            shouldNotifyReply(TurnRole.MASALA, "Made Snake.", listOf(miniApp("s")), true, false) { false },
        )
    }
}
