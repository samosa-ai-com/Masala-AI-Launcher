package com.samosaai.masala.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.samosaai.masala.BuildConfig
import com.samosaai.masala.R
import com.samosaai.masala.core.update.UpdateManifest
import com.samosaai.masala.pro.ProTools
import com.samosaai.masala.ui.components.Markdown
import com.samosaai.masala.update.UpdateCheckWorker
import com.samosaai.masala.update.UpdateChecker
import com.samosaai.masala.update.UpdatePrefs
import kotlinx.coroutines.launch
import com.samosaai.masala.ui.brand.MasalaWordmark
import com.samosaai.masala.ui.components.SectionCard
import com.samosaai.masala.ui.components.SettingsRow
import com.samosaai.masala.ui.theme.MasalaMono

/** What a licence file in `assets/licenses` covers, by its name. */
internal fun licenceTitle(file: String): String = when (file.substringBeforeLast('.')) {
    "OFL_inter" -> "Inter · SIL Open Font License 1.1"
    "OFL_space_grotesk" -> "Space Grotesk · SIL Open Font License 1.1"
    "OFL_figtree" -> "Figtree · SIL Open Font License 1.1"
    else -> file.substringBeforeLast('.').replace('_', ' ')
}

/** Settings → About (plan UI refresh U5): version and build, and the licences shipped in `assets/licenses`. */
@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val files = remember { context.assets.list("licenses").orEmpty().sorted() }
    var open by remember { mutableStateOf<String?>(null) }
    SettingsScaffold(stringResource(R.string.about_title), onBack) {
        MasalaWordmark(markSize = 48.dp)
        SectionCard {
            SettingsRow(Icons.Rounded.Info, stringResource(R.string.about_version), summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}")
        }
        UpdatesCard()
        SectionCard(title = stringResource(R.string.about_licences)) {
            files.forEach { f -> SettingsRow(Icons.Rounded.Description, licenceTitle(f), onClick = { open = f }) }
            SettingsRow(Icons.Rounded.Description, "compose-richtext · Apache License 2.0", summary = stringResource(R.string.about_richtext))
        }
    }
    open?.let { f ->
        val text = remember(f) { runCatching { context.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.about_close)) } },
            title = { Text(licenceTitle(f)) },
            text = { Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MasalaMono), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        )
    }
}

/** Where the Updates card is: checking, a result, or an update on its way in. */
private sealed interface UpdateUi {
    data object Checking : UpdateUi
    data class Checked(val result: UpdateChecker.Check) : UpdateUi
    data class Downloading(val manifest: UpdateManifest, val percent: Int) : UpdateUi
    data class Installing(val manifest: UpdateManifest) : UpdateUi
    data class Failed(val manifest: UpdateManifest, val reason: String) : UpdateUi
}

private const val RELEASES_URL = "https://github.com/samosa-ai-com/Masala-AI-Launcher/releases"

/**
 * Settings → About → Updates: checks `update.json` on open, shows what's new, then downloads,
 * verifies and installs the update (Android asks the user to confirm). Release builds only; other
 * builds are signed with another key and couldn't take the update.
 */
@Composable
private fun UpdatesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.about_updates)) {
        if (!BuildConfig.UPDATE_CHECKS) {
            SettingsRow(Icons.Rounded.SystemUpdate, stringResource(R.string.update_release_builds_only, BuildConfig.BUILD_TYPE))
            SettingsRow(Icons.Rounded.OpenInNew, stringResource(R.string.update_all_releases), onClick = { openReleases(context) })
            return@SectionCard
        }
        val checker = remember { UpdateChecker() }
        val prefs = remember { UpdatePrefs(context) }
        var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Checking) }
        var notify by remember { mutableStateOf(prefs.notify) }
        var check by remember { mutableStateOf(0) }
        // Re-read on every resume: the user grants "install unknown apps" in Android's screen and comes back.
        var canInstall by remember { mutableStateOf(ProTools.canInstallApps(context)) }
        LifecycleResumeEffect(Unit) {
            canInstall = ProTools.canInstallApps(context)
            onPauseOrDispose { }
        }
        LaunchedEffect(check) {
            ui = UpdateUi.Checking
            ui = UpdateUi.Checked(checker.check())
        }

        fun install(manifest: UpdateManifest) {
            if (!canInstall) {
                context.startActivity(ProTools.installPermissionIntent(context))
                return
            }
            scope.launch {
                ui = UpdateUi.Downloading(manifest, 0)
                val apk = checker.download(context, manifest) { ui = UpdateUi.Downloading(manifest, it) }
                    .getOrElse { ui = UpdateUi.Failed(manifest, it.message ?: it.javaClass.simpleName); return@launch }
                ui = UpdateUi.Installing(manifest)
                // On success Android replaces Masala and this process ends.
                ProTools.installApk(context, apk)?.let { ui = UpdateUi.Failed(manifest, it) }
            }
        }

        val manifest = when (val s = ui) {
            is UpdateUi.Checked -> (s.result as? UpdateChecker.Check.Available)?.manifest
            is UpdateUi.Downloading -> s.manifest
            is UpdateUi.Installing -> s.manifest
            is UpdateUi.Failed -> s.manifest
            UpdateUi.Checking -> null
        }
        if (manifest == null) {
            val status = when (val s = ui) {
                is UpdateUi.Checked -> when (val r = s.result) {
                    UpdateChecker.Check.UpToDate -> stringResource(R.string.update_up_to_date)
                    UpdateChecker.Check.NothingPublished -> stringResource(R.string.update_nothing_published)
                    is UpdateChecker.Check.Failed -> stringResource(R.string.update_check_failed, r.reason)
                    is UpdateChecker.Check.Available -> ""
                }
                else -> stringResource(R.string.update_checking)
            }
            SettingsRow(
                Icons.Rounded.Refresh, stringResource(R.string.update_check_now), summary = status,
                onClick = { if (ui != UpdateUi.Checking) check++ },
            )
        } else {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_available, manifest.versionName), style = MaterialTheme.typography.titleMedium)
                if (manifest.releaseNotes.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Markdown(manifest.releaseNotes)
                    }
                }
                when (val s = ui) {
                    is UpdateUi.Downloading -> {
                        Text(stringResource(R.string.update_downloading, s.percent), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateUi.Installing -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        if (s is UpdateUi.Failed) {
                            Text(stringResource(R.string.update_failed, s.reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (!canInstall) {
                            Text(stringResource(R.string.update_allow_installs_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { install(manifest) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (canInstall) R.string.update_install else R.string.update_allow_installs))
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            SettingsToggleRow(stringResource(R.string.update_notify), notify, summary = stringResource(R.string.update_notify_summary)) {
                notify = it
                prefs.notify = it
                UpdateCheckWorker.ensure(context)
            }
        }
        SettingsRow(Icons.Rounded.OpenInNew, stringResource(R.string.update_all_releases), onClick = { openReleases(context) })
    }
}

private fun openReleases(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
