package com.samosaai.masala

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.miniapps.MiniAppActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Report in the mini-app screen saves a line naming the app, on the phone only. */
@RunWith(AndroidJUnit4::class)
class MiniAppReportTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()
    private var id: String? = null

    @After
    fun cleanUp() = runBlocking { id?.let { app.miniApps.delete(it) } ?: Unit }

    @Test
    fun reportSavesALineForTheApp() {
        val appId = runBlocking { app.miniApps.create("Report test", "<html><body>hi</body></html>")!! }.also { id = it }
        val intent = Intent(app, MiniAppActivity::class.java).putExtra(MiniAppActivity.EXTRA_APP_ID, appId)
        ActivityScenario.launch<MiniAppActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Report").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Report").performClick()
            compose.onNodeWithText("Report this mini app").assertExists()
            compose.onNodeWithText("Offensive").performClick()
            // The dialog's confirm button is the second "Report" on screen.
            compose.onAllNodesWithText("Report")[1].performClick()
            compose.waitForIdle()
        }
        val lines = File(app.filesDir, "ai_reports.txt").readLines()
        assertTrue("no report line; got ${lines.takeLast(3)}", lines.any { "mini_app:$appId" in it && "Offensive" in it && "Report test" in it })
    }
}
