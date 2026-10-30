package com.samosaai.masala.ui.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.samosaai.masala.R
import com.samosaai.masala.data.LauncherSettings
import com.samosaai.masala.launcher.HomeRole
import com.samosaai.masala.masala
import com.samosaai.masala.ui.brand.MasalaWordmark
import com.samosaai.masala.ui.character.SamHello
import com.samosaai.masala.ui.theme.MasalaColors
import com.samosaai.masala.ui.theme.MasalaTheme
import com.samosaai.masala.ui.theme.SpaceGrotesk
import com.samosaai.masala.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

/**
 * First run (plan UI refresh U1, first-run board): the logo, Sam says hello, then "Make Masala my home
 * screen" or "Look around first". Either way it isn't shown again. Always on charcoal, whatever the
 * theme, like the app icon.
 */
class WelcomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            val settings by masala.settings.settings.collectAsStateWithLifecycle(LauncherSettings())
            MasalaTheme(settings.copy(theme = com.samosaai.masala.data.ThemeMode.DARK), systemBars = false) {
                Welcome(onDone = ::done)
            }
        }
    }

    private fun done() {
        lifecycleScope.launch {
            masala.settings.update { it.copy(welcomeDone = true) }
            finish()
        }
    }

    @Composable
    private fun Welcome(onDone: () -> Unit) {
        val haptics = rememberHaptics()
        val scroll = rememberScrollState()
        // System Back finishes the activity without a button press; count that as done too,
        // or the welcome screen would come back on the next launch.
        BackHandler(onBack = onDone)
        val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!HomeRole.isDefault(this)) HomeRole.openSettings(this)
            onDone()
        }
        Box(Modifier.fillMaxSize().background(MasalaColors.Charcoal)) {
            Box(Modifier.fillMaxSize().systemBarsPadding().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp)) {
                // Centered when everything fits, scrollable on small screens, landscape or split
                // screen. The min height gives Arrangement.Center something to center in: a bare
                // scrolling column wraps its content, which would pin everything to the top.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.verticalScroll(scroll).defaultMinSize(minHeight = maxHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MasalaWordmark(markSize = 40.dp, grinding = true)
                        SamHello(
                            Modifier.widthIn(max = 290.dp).fillMaxWidth().aspectRatio(290f / 265f).padding(vertical = 16.dp),
                            contentDescription = stringResource(R.string.welcome_sam_description),
                        )
                        Text(
                            stringResource(R.string.welcome_title),
                            style = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-0.03).em),
                            color = MasalaColors.Text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.welcome_body),
                            fontSize = 15.5.sp,
                            lineHeight = 23.sp,
                            color = MasalaColors.Text.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp).widthIn(max = 300.dp),
                        )
                        Spacer(Modifier.height(26.dp))
                        Button(
                            onClick = {
                                haptics.confirm()
                                HomeRole.requestIntent(this@WelcomeActivity)?.let(roleRequest::launch) ?: run {
                                    HomeRole.openSettings(this@WelcomeActivity)
                                    onDone()
                                }
                            },
                            shape = RoundedCornerShape(percent = 50),
                            colors = ButtonDefaults.buttonColors(containerColor = MasalaColors.Gold, contentColor = MasalaColors.Charcoal),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) { Text(stringResource(R.string.welcome_make_home), fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = onDone,
                            shape = RoundedCornerShape(percent = 50),
                            border = BorderStroke(1.dp, MasalaColors.Text.copy(alpha = 0.18f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MasalaColors.Text),
                            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(50.dp),
                        ) { Text(stringResource(R.string.welcome_look_around), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
