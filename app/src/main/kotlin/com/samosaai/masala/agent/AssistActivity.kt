package com.samosaai.masala.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.masala
import com.samosaai.masala.ui.home.ConfirmationSheet
import com.samosaai.masala.ui.home.ReplyCard
import com.samosaai.masala.ui.theme.MasalaTheme

/**
 * The assist gesture (long-press home, corner swipe) once Masala is the phone's assistant app: the same
 * reply card as on home, over whatever was on screen. Draws the confirmation sheet, since commands run
 * from here too.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only from idle: a command still running (or this screen recreated on rotation) must not be cancelled.
        if (masala.commands.state.value == CommandState.Idle) masala.commands.openInput()
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings, systemBars = false) {
                // Tapping outside the card closes it.
                Box(
                    Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }
                        .statusBarsPadding().navigationBarsPadding().imePadding(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ReplyCard(masala, settings.readRepliesAloud)
                    CloseWhenDismissed()
                }
                ConfirmationSheet(masala.confirmationGate)
            }
        }
    }

    @Composable
    private fun CloseWhenDismissed() {
        val state by masala.commands.state.collectAsStateWithLifecycle()
        LaunchedEffect(state) { if (state == CommandState.Idle) finish() }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) masala.commands.dismiss()
    }
}
