package com.samosaai.masala

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.core.contract.HtmlSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.core.layout.LayoutEdits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan `docs/plans/widget-maker.md` W6 on the device: a mini app goes on home at widget size
 * through the real store and layout repository, and deleting it takes it off home.
 */
@RunWith(AndroidJUnit4::class)
class HtmlWidgetTest {
    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    @Test
    fun htmlWidgetCycle() = runBlocking {
        val id = app.miniApps.create(title = "Tiny", html = "<html><body>hi</body></html>")
        assertTrue(id != null)
        try {
            val problems = app.layouts.placeHtml("Add to home: Tiny", id!!).problems
            assertTrue(problems.toString(), problems.isEmpty())
            val base = withTimeout(15_000) { app.layouts.base.first { it != null } }!!
            assertTrue(LayoutEdits.htmlPlacements(base, id).isNotEmpty())

            // Unknown apps and a third HTML widget on a page are rejected.
            val env = app.layouts.environment()
            assertTrue(env.miniAppIds?.contains(id) == true)
            val unknown = LayoutChecker.check(
                LayoutDoc(pages = listOf(Page("p", sections = listOf(HtmlSection("h", "gone"))))), env,
            )
            assertEquals(listOf("$.pages[0].sections[0].appId"), unknown.map { it.path })

            app.layouts.removeHtmlApp("Remove HTML widget", id)
            val off = withTimeout(15_000) { app.layouts.base.first { it != null && LayoutEdits.htmlPlacements(it, id).isEmpty() } }
            assertTrue(LayoutEdits.htmlPlacements(off!!, id).isEmpty())
        } finally {
            app.deleteMiniApp(id!!)
        }
        val base = withTimeout(15_000) { app.layouts.base.first { it != null } }!!
        assertTrue(LayoutEdits.htmlPlacements(base, id).isEmpty())
    }
}
