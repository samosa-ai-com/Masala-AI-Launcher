package com.samosaai.masala

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.samosaai.masala.miniapps.MiniAppConsole
import com.samosaai.masala.miniapps.MiniAppProbe
import com.samosaai.masala.miniapps.MiniAppView
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.data.LauncherSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lane D's instrumented check: a hand-written HTML app actually runs in the host — its script
 * executes, localStorage reads and writes inside its own origin, and console output (including
 * errors) is captured for the repair turn.
 */
@RunWith(AndroidJUnit4::class)
class MiniAppRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<MasalaApp>()

    private val gameHtml = """
        <!doctype html><html><head><meta charset="utf-8"><title>game</title></head>
        <body style="margin:0;background:#111;color:#0f0">
          <canvas id="c" width="200" height="200"></canvas>
          <div id="score">0</div>
          <script>
            var score = 0;
            document.getElementById('score').textContent = '42';
            localStorage.setItem('best', '7');
            console.log('booted score=' + document.getElementById('score').textContent +
              ' best=' + localStorage.getItem('best'));
            console.error('deliberate test error');
          </script>
        </body></html>
    """.trimIndent()

    @Test
    fun htmlAppRunsInHostAndConsoleIsCaptured() {
        val id = "testgame"
        MiniAppConsole.clear(id)
        compose.setContent {
            MasalaTheme(LauncherSettings()) {
                MiniAppView(id, gameHtml, networkAllowed = false)
            }
        }
        compose.waitForIdle()

        // Poll until the page's own script has run: proves JS executed in the host.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (MiniAppConsole.errors(id).any { it.contains("booted score=42 best=7") }) break
            Thread.sleep(250)
        }
        val log = MiniAppConsole.errors(id)
        assertTrue("page never booted; console=$log", log.any { it.contains("booted score=42 best=7") })
        assertTrue("console error not captured; console=$log", log.any { it.contains("deliberate test error") })
    }

    @Test
    fun probeReportsLoadErrorsAndNothingForACleanApp() = runBlocking {
        val probe = MiniAppProbe(app)
        val broken = "<html><body><script>var a = 1; missingFunction(a);</script></body></html>"
        val errors = probe.errors(broken)
        assertTrue("expected a ReferenceError; got $errors", errors.orEmpty().any { "missingFunction" in it })
        val timer = "<html><body><script>setTimeout(function () { null.x; }, 200);</script></body></html>"
        assertTrue("timer error missed; got ${probe.errors(timer)}", probe.errors(timer).orEmpty().isNotEmpty())
        assertEquals(emptyList<String>(), probe.errors(gameHtml.replace("console.error('deliberate test error');", "")))
    }
}
