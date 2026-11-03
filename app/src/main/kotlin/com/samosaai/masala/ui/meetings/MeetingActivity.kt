package com.samosaai.masala.ui.meetings

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samosaai.masala.MasalaApp
import com.samosaai.masala.R
import com.samosaai.masala.core.audio.DiarizedTranscript
import com.samosaai.masala.core.meeting.MeetingBookmark
import com.samosaai.masala.core.meeting.MeetingNote
import com.samosaai.masala.core.meeting.MeetingStatus
import com.samosaai.masala.core.meeting.MeetingTemplate
import com.samosaai.masala.data.MeetingChunkEntity
import com.samosaai.masala.data.MeetingSessionEntity
import com.samosaai.masala.data.MeetingTurnEntity
import com.samosaai.masala.masala
import com.samosaai.masala.meetings.MeetingExport
import com.samosaai.masala.meetings.MeetingRecorderService
import com.samosaai.masala.meetings.MeetingWork
import com.samosaai.masala.ui.settings.SettingsActivity
import com.samosaai.masala.ui.theme.MasalaTheme
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI meeting notes (plan `docs/plans/meeting-notes.md` §6): the list, the live recorder and the
 * finished note. One activity, three destinations — the shape of the reference screens, in Masala's
 * theme.
 */
class MeetingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialId = intent.getStringExtra(EXTRA_MEETING)
        val autoStart = intent.getBooleanExtra(EXTRA_START, false)
        setContent {
            val settings by this@MeetingActivity.masala.settings.settings
                .collectAsStateWithLifecycle(com.samosaai.masala.data.LauncherSettings())
            MasalaTheme(settings) {
                Surface(Modifier.fillMaxSize()) {
                    MeetingHost(initialId = initialId, autoStart = autoStart, finish = { finish() })
                }
            }
        }
    }

    companion object {
        const val EXTRA_MEETING = "meeting_id"
        const val EXTRA_START = "start"

        fun intent(context: Context, meetingId: String? = null, start: Boolean = false): Intent =
            Intent(context, MeetingActivity::class.java).apply {
                meetingId?.let { putExtra(EXTRA_MEETING, it) }
                putExtra(EXTRA_START, start)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

private enum class Dest { LIST, LIVE, DETAIL }

@Composable
private fun MeetingHost(initialId: String?, autoStart: Boolean, finish: () -> Unit) {
    val app = LocalContext.current.masala
    val context = LocalContext.current
    var dest by rememberSaveable { mutableStateOf(if (initialId != null) Dest.DETAIL else Dest.LIST) }
    var openId by rememberSaveable { mutableStateOf(initialId) }
    val recorder by remember { MeetingRecorderService.state.map { RecorderTick(it.meetingId, it.active) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(RecorderTick(null, false))
    val activeRow by app.meetings.active.collectAsStateWithLifecycle(null)

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ContextCompat.startForegroundService(context, MeetingRecorderService.startIntent(context))
        } else {
            Toast.makeText(context, R.string.meeting_needs_mic, Toast.LENGTH_LONG).show()
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startMeeting(title: String = "") {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(context, MeetingRecorderService.startIntent(context, title = title))
        } else {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A meeting that is already running is the live screen, wherever the activity was opened.
    LaunchedEffect(recorder.meetingId, recorder.active) {
        if (recorder.active && recorder.meetingId != null) {
            openId = recorder.meetingId
            dest = Dest.LIVE
        }
    }
    LaunchedEffect(autoStart) {
        if (autoStart && !recorder.active && activeRow == null) {
            startMeeting()
        }
    }

    when (dest) {
        Dest.LIST -> MeetingListScreen(
            onRecord = { startMeeting() },
            onContinue = {
                openId = recorder.meetingId ?: activeRow?.id
                dest = Dest.LIVE
            },
            hasLive = recorder.active || activeRow != null,
            onOpen = { id ->
                openId = id
                dest = Dest.DETAIL
            },
            finish = finish,
        )

        Dest.LIVE -> {
            val id = openId ?: recorder.meetingId
            BackHandler {
                if (recorder.active) dest = Dest.LIST else { dest = Dest.LIST; openId = null }
            }
            if (id == null) {
                dest = Dest.LIST
            } else {
                LiveMeetingScreen(
                    meetingId = id,
                    onBack = { dest = Dest.LIST },
                    onFinished = { finishedId ->
                        openId = finishedId
                        dest = Dest.DETAIL
                    },
                )
            }
        }

        Dest.DETAIL -> {
            val id = openId
            BackHandler { dest = Dest.LIST }
            if (id == null) {
                dest = Dest.LIST
            } else {
                MeetingDetailScreen(
                    meetingId = id,
                    onBack = { dest = Dest.LIST },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java)
                                .putExtra(SettingsActivity.EXTRA_PAGE, "MEETINGS"),
                        )
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------- list

@Composable
private fun MeetingListScreen(
    onRecord: () -> Unit,
    onContinue: () -> Unit,
    hasLive: Boolean,
    onOpen: (String) -> Unit,
    finish: () -> Unit,
) {
    val app = LocalContext.current.masala
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meetings by app.meetings.summaries.collectAsStateWithLifecycle(emptyList())
    val nextEvent by app.contextService.snapshot.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val id = importMeeting(context, app, uri)
                importing = false
                if (id != null) onOpen(id) else Toast.makeText(context, R.string.meeting_import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val openSettings = {
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "MEETINGS"))
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Mic, null, tint = MasalaTheme.tokens.accent)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.meeting_app_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = openSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.meeting_settings_title)) }
            IconButton(onClick = finish) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.meeting_back)) }
        }
        Button(
            onClick = if (hasLive) onContinue else onRecord,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Rounded.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (hasLive) R.string.meeting_continue else R.string.meeting_record))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { pickAudio.launch(arrayOf("audio/*")) },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (importing) R.string.meeting_transcribing else R.string.meeting_import))
        }
        nextEvent?.nextEvent?.let { event ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MasalaTheme.tokens.glassFill,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${event.start.toLocalTime()} · ${stringResource(R.string.meeting_app_title)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MasalaTheme.tokens.onWallpaperMedium,
                        )
                    }
                    TextButton(onClick = { onRecord() }) { Text(stringResource(R.string.meeting_record)) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.meeting_recent), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (meetings.isEmpty()) {
            Text(
                stringResource(R.string.meeting_no_meetings),
                style = MaterialTheme.typography.bodyMedium,
                color = MasalaTheme.tokens.onWallpaperMedium,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(meetings, key = { it.id }) { meeting ->
                    MeetingRow(meeting) { onOpen(meeting.id) }
                    HorizontalDivider(color = MasalaTheme.tokens.hairline)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun MeetingRow(meeting: com.samosaai.masala.core.meeting.MeetingSummary, onOpen: () -> Unit) {
    val date = remember(meeting.startedAt) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.startedAt))
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MasalaTheme.tokens.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Mic, null, tint = MasalaTheme.tokens.accent, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meeting.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(date)
                    if (meeting.durationMs > 0) append(" · ${durationText(meeting.durationMs)}")
                    if (meeting.speakers > 0) append(" · " + speakerCountText(meeting.speakers))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MasalaTheme.tokens.onWallpaperMedium,
            )
            if (meeting.oneLine.isNotBlank()) {
                Text(
                    meeting.oneLine,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Copies a picked audio file into a new meeting and queues it; the same pipeline as a recording. */
private suspend fun importMeeting(context: Context, app: MasalaApp, uri: Uri): String? = withContext(Dispatchers.IO) {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: "Imported audio"
    val mime = context.contentResolver.getType(uri).orEmpty()
    val ext = when {
        mime.contains("mpeg") -> "mp3"
        mime.contains("wav") -> "wav"
        mime.contains("ogg") -> "ogg"
        mime.contains("webm") -> "webm"
        mime.contains("flac") -> "flac"
        else -> "m4a"
    }
    val session = app.meetings.create(
        title = name.substringBeforeLast('.'),
        template = app.meetingSettings.template,
        language = app.meetingSettings.language,
    )
    val target = File(app.meetings.dir(session.id), "import.$ext")
    val copied = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        target.length() > 0
    }.getOrDefault(false)
    if (!copied) {
        app.meetings.delete(session.id)
        return@withContext null
    }
    val duration = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(target.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)
    if (duration > 60 * 60_000L) {
        app.meetings.delete(session.id)
        return@withContext null
    }
    // One part: the transcriber cuts its 10-minute windows from this file.
    app.meetings.addChunk(session.id, 0, target, 0)
    app.meetings.setChunkDuration(session.id, 0, duration)
    app.meetings.finish(session.id, MeetingStatus.TRANSCRIBING, duration)
    MeetingWork.enqueue(context, session.id)
    session.id
}

// -------------------------------------------------------------------------------- live

@Composable
private fun LiveMeetingScreen(meetingId: String, onBack: () -> Unit, onFinished: (String) -> Unit) {
    val app = LocalContext.current.masala
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder by remember(meetingId) {
        MeetingRecorderService.state.map { RecorderTick(it.meetingId, it.active, it.status, it.elapsedMs) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(RecorderTick(null, false))
    val row by app.meetings.session(meetingId).collectAsStateWithLifecycle(null)
    val turns by app.meetings.turnsOf(meetingId).collectAsStateWithLifecycle(emptyList())
    val chunks by app.meetings.chunksOf(meetingId).collectAsStateWithLifecycle(emptyList())
    var notes by remember(meetingId) { mutableStateOf(row?.rawNotes.orEmpty()) }
    var notesLoaded by remember(meetingId) { mutableStateOf(false) }
    var title by remember(meetingId) { mutableStateOf("") }
    var editTitle by remember { mutableStateOf(false) }
    var bookmarkAt by remember { mutableStateOf<Long?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(row?.id) {
        if (!notesLoaded && row != null) {
            notes = row?.rawNotes.orEmpty()
            title = row?.title.orEmpty()
            notesLoaded = true
        }
    }
    // Debounced autosave of the user's own notes.
    LaunchedEffect(notes) {
        if (!notesLoaded) return@LaunchedEffect
        delay(600)
        val current = app.meetings.get(meetingId) ?: return@LaunchedEffect
        app.meetings.setRawNotes(meetingId, notes, app.meetings.bookmarks(current))
    }
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty() && listState.firstVisibleItemIndex < 2) {
            listState.animateScrollToItem((turns.size - 1).coerceAtLeast(0))
        }
    }
    val active = recorder.active && recorder.meetingId == meetingId
    val paused = recorder.status == MeetingStatus.PAUSED
    // The service reports its clock every 30 s; the digits need every second, so tick locally from
    // the last report instead of writing widget state once a second.
    var baseAt by remember(recorder.elapsedMs, active) { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(baseAt) }
    LaunchedEffect(recorder.elapsedMs, recorder.status, active) {
        baseAt = System.currentTimeMillis()
        while (active && recorder.status == MeetingStatus.RECORDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = when {
        !active -> row?.activeMs ?: 0
        recorder.status == MeetingStatus.RECORDING -> recorder.elapsedMs + (now - baseAt).coerceAtLeast(0)
        else -> recorder.elapsedMs
    }
    val done = chunks.count { it.status == com.samosaai.masala.core.meeting.ChunkStatus.DONE }

    fun bookmark() {
        val at = elapsed
        bookmarkAt = at
        scope.launch {
            val current = app.meetings.get(meetingId) ?: return@launch
            app.meetings.setRawNotes(
                meetingId,
                notes,
                app.meetings.bookmarks(current) + com.samosaai.masala.core.meeting.MeetingBookmark(at, ""),
            )
            delay(2_500)
            bookmarkAt = null
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.meeting_back)) }
            if (editTitle) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.meeting_title_hint)) },
                )
            } else {
                Text(
                    title.ifBlank { stringResource(R.string.meeting_live_title) },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { editTitle = true },
                )
            }
            TextButton(onClick = {
                editTitle = false
                if (title.isNotBlank()) scope.launch { app.meetings.rename(meetingId, title) }
            }) { Text(stringResource(R.string.meeting_save)) }
        }

        Spacer(Modifier.height(8.dp))
        LiveWaveform(active && !paused)
        Text(
            DiarizedTranscript.clockOf(elapsed),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                when {
                    paused -> stringResource(R.string.meeting_pause)
                    active -> stringResource(R.string.meeting_notif_recording)
                    row?.status == MeetingStatus.TRANSCRIBING -> stringResource(R.string.meeting_transcribing)
                    else -> stringResource(R.string.meeting_widget_idle)
                },
                highlight = active && !paused,
            )
            if (chunks.isNotEmpty()) {
                Text(
                    partCountText(chunks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasalaTheme.tokens.onWallpaperMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (row?.status == MeetingStatus.TRANSCRIBING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(" $done/${chunks.size}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.meeting_consent), style = MaterialTheme.typography.bodySmall, color = MasalaTheme.tokens.onWallpaperMedium)

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    val command = if (paused) MeetingRecorderService.ACTION_RESUME else MeetingRecorderService.ACTION_PAUSE
                    context.startService(MeetingRecorderService.command(context, command))
                },
                enabled = active,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text(
                    stringResource(if (paused) R.string.meeting_resume_short else R.string.meeting_pause),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = { bookmark() },
                enabled = active,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(stringResource(R.string.meeting_mark), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
            Button(
                onClick = { confirmStop = true },
                enabled = active,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(stringResource(R.string.meeting_stop), maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
        }
        if (bookmarkAt != null) {
            Text(
                stringResource(R.string.meeting_bookmarked, DiarizedTranscript.clockOf(bookmarkAt ?: 0)),
                style = MaterialTheme.typography.bodySmall,
                color = MasalaTheme.tokens.accentText,
            )
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.meeting_add_note)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 120.dp),
            maxLines = 4,
        )

        if (row?.error != null) {
            Text(row?.error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.meeting_live_hint), style = MaterialTheme.typography.bodySmall, color = MasalaTheme.tokens.onWallpaperMedium)
        Spacer(Modifier.height(6.dp))
        if (turns.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(turns.takeLast(200), key = { it.id }) { turn -> TurnRow(turn, emptyMap()) }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.meeting_stop)) },
            text = { Text(stringResource(R.string.meeting_stop_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    context.startService(MeetingRecorderService.command(context, MeetingRecorderService.ACTION_STOP))
                    onFinished(meetingId)
                }) { Text(stringResource(R.string.meeting_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.meeting_cancel)) } },
        )
    }
}

@Composable
private fun Waveform(amplitude: Float, running: Boolean) {
    // A short history of levels, drawn as bars: the card's own "is it hearing me" signal.
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(amplitude, running) {
        levels.add(if (running) amplitude else 0f)
        if (levels.size > 48) levels.removeAt(0)
    }
    Row(
        Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until 48) {
            val level = levels.getOrElse(i) { 0f }
            Box(
                Modifier
                    .weight(1f)
                    .height((6 + level * 60).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (running) MasalaTheme.tokens.accent.copy(alpha = 0.35f + level * 0.65f)
                        else MasalaTheme.tokens.accent.copy(alpha = 0.18f),
                    ),
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, highlight: Boolean) {
    Surface(
        color = if (highlight) MasalaTheme.tokens.alert.copy(alpha = 0.18f) else MasalaTheme.tokens.glassFill,
        shape = RoundedCornerShape(50),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    if (highlight) MasalaTheme.tokens.alert else MasalaTheme.tokens.onWallpaperMedium,
                ),
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// -------------------------------------------------------------------------------- detail

@Composable
private fun MeetingDetailScreen(meetingId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.masala
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val row by app.meetings.session(meetingId).collectAsStateWithLifecycle(null)
    val turns by app.meetings.turnsOf(meetingId).collectAsStateWithLifecycle(emptyList())
    val chunks by app.meetings.chunksOf(meetingId).collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingSpeaker by remember { mutableStateOf<String?>(null) }
    var savingBrain by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MeetingPlayer?>(null) }
    val parts = remember(chunks) { chunks.map { MeetingPart(File(it.path), it.startMs, it.durationMs) }.filter { it.file.isFile } }

    val meeting = row
    val askPrompt = meeting?.let { m ->
        stringResource(
            R.string.meeting_ask_prompt,
            m.title,
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.startedAt)),
        )
    }
    val names = remember(meeting?.speakerNames) { meeting?.let { app.meetings.speakerNames(it) } ?: emptyMap() }
    val note = remember(meeting?.noteJson) { meeting?.let { app.meetings.note(it) } }
    val doneActions = remember(meeting?.doneActions) { meeting?.let { app.meetings.doneActions(it) } ?: emptySet() }
    val speakerLabels = remember(turns) { turns.mapNotNull { it.speaker }.distinct() }
    val talk = remember(turns) {
        turns.groupBy { it.speaker ?: "?" }.mapValues { (_, list) -> list.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) } }
    }
    val totalTalk = talk.values.sum().coerceAtLeast(1)

    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player?.positionMs ?: positionMs
            delay(500)
        }
    }

    fun togglePlay(from: Long? = null) {
        if (parts.isEmpty()) return
        if (playing) {
            player?.pause()
            playing = false
            return
        }
        val withPlayer = player ?: MeetingPlayer(parts).also { player = it }
        withPlayer.playFrom(from ?: positionMs)
        playing = true
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.meeting_back)) }
            Column(Modifier.weight(1f)) {
                Text(meeting?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        meeting?.let {
                            append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.startedAt)))
                            append(" · ${durationText(it.activeMs)}")
                        }
                        if (speakerLabels.isNotEmpty()) append(" · " + speakerCountText(speakerLabels.size))
                        if (chunks.isNotEmpty()) append(" · " + partCountText(chunks.size))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MasalaTheme.tokens.onWallpaperMedium,
                )
            }
            IconButton(onClick = { togglePlay() }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(R.string.meeting_play))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.meeting_regenerate)) }, onClick = {
                        menu = false
                        scope.launch {
                            app.meetings.setStatus(meetingId, MeetingStatus.TRANSCRIBING)
                            MeetingWork.enqueue(context, meetingId, forceNotes = true)
                        }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.meeting_share)) }, onClick = {
                        menu = false
                        meeting?.let { MeetingExport.share(context, it, note, names, turns) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.meeting_save_brain)) }, onClick = {
                        menu = false
                        scope.launch {
                            savingBrain = true
                            val path = meeting?.let { MeetingExport.saveToBrain(app.secondBrain, it, note, names, turns) }
                            if (path != null) {
                                app.meetings.setBrainPath(meetingId, path)
                                Toast.makeText(context, R.string.meeting_saved_brain, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.meeting_brain_failed, Toast.LENGTH_LONG).show()
                            }
                            savingBrain = false
                        }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.meeting_ask)) }, onClick = {
                        menu = false
                        askPrompt?.let { app.commands.openInput(it) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.meeting_delete)) }, onClick = {
                        menu = false
                        confirmDelete = true
                    })
                }
            }
        }

        if (playing || positionMs > 0) {
            Text(
                DiarizedTranscript.clockOf(positionMs) + " / " + DiarizedTranscript.clockOf(meeting?.activeMs ?: 0),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (meeting?.status == MeetingStatus.TRANSCRIBING || meeting?.status == MeetingStatus.RECORDING || meeting?.status == MeetingStatus.PAUSED) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (meeting?.status == MeetingStatus.INTERRUPTED) stringResource(R.string.meeting_interrupted) else stringResource(R.string.meeting_transcribing),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { MeetingWork.enqueueAnyNetwork(context, meetingId) }) {
                    Text(stringResource(R.string.meeting_use_data))
                }
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.meeting_notes_tab)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.meeting_transcript_tab)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.meeting_speakers_tab)) })
        }

        when (tab) {
            0 -> NotesTab(
                meeting = meeting,
                note = note,
                done = doneActions,
                onToggle = { index ->
                    val next = doneActions.toMutableSet().apply { if (!add(index)) remove(index) }
                    scope.launch { app.meetings.setDoneActions(meetingId, next) }
                },
                onRemind = { action ->
                    meeting?.let { app.commands.openInput(MeetingExport.reminderPrompt(it, action.text, action.owner)) }
                },
                onRegenerate = {
                    scope.launch {
                        app.meetings.setStatus(meetingId, MeetingStatus.TRANSCRIBING)
                        MeetingWork.enqueue(context, meetingId, forceNotes = true)
                    }
                },
                onSaveBrain = {
                    scope.launch {
                        savingBrain = true
                        val path = meeting?.let { MeetingExport.saveToBrain(app.secondBrain, it, note, names, turns) }
                        if (path != null) {
                            app.meetings.setBrainPath(meetingId, path)
                            Toast.makeText(context, R.string.meeting_saved_brain, Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, R.string.meeting_brain_failed, Toast.LENGTH_LONG).show()
                        }
                        savingBrain = false
                    }
                },
                saving = savingBrain,
                saved = meeting?.brainPath != null,
                template = MeetingTemplate.byId(meeting?.template),
                onTemplate = { template ->
                    scope.launch {
                        app.meetings.rename(meetingId, meeting?.title.orEmpty())
                        // Template change applies to the next regeneration; store it on the session.
                        app.database.meetings().upsert(
                            (app.meetings.get(meetingId) ?: return@launch).copy(template = template.id, updatedAt = System.currentTimeMillis()),
                        )
                    }
                },
            )
            1 -> TranscriptTab(
                turns = turns,
                names = names,
                labels = speakerLabels,
                onSeek = { at ->
                    positionMs = at
                    if (!playing) togglePlay(at)
                },
                onRename = { editingSpeaker = it },
            )
            else -> SpeakersTab(
                labels = speakerLabels,
                names = names,
                talk = talk,
                total = totalTalk,
                onRename = { editingSpeaker = it },
            )
        }
    }

    editingSpeaker?.let { label ->
        SpeakerDialog(
            label = label,
            current = names[label].orEmpty(),
            onDismiss = { editingSpeaker = null },
            onSave = { value ->
                editingSpeaker = null
                scope.launch {
                    val next = names.toMutableMap()
                    if (value.isBlank()) next.remove(label) else next[label] = value.trim()
                    app.meetings.setSpeakerNames(meetingId, next)
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.meeting_delete)) },
            text = { Text(stringResource(R.string.meeting_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        app.meetingTranscriber.forget(meetingId)
                        app.meetings.delete(meetingId)
                        onBack()
                    }
                }) { Text(stringResource(R.string.meeting_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.meeting_cancel)) } },
        )
    }
}

@Composable
private fun NotesTab(
    meeting: MeetingSessionEntity?,
    note: MeetingNote?,
    done: Set<Int>,
    onToggle: (Int) -> Unit,
    onRemind: (com.samosaai.masala.core.meeting.MeetingAction) -> Unit,
    onRegenerate: () -> Unit,
    onSaveBrain: () -> Unit,
    saving: Boolean,
    saved: Boolean,
    template: MeetingTemplate,
    onTemplate: (MeetingTemplate) -> Unit,
) {
    var templateMenu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.meeting_summary), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { templateMenu = true }) { Text(template.name) }
                DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                    MeetingTemplate.all.forEach { option ->
                        DropdownMenuItem(text = { Text(option.name) }, onClick = {
                            templateMenu = false
                            onTemplate(option)
                        })
                    }
                }
            }
        }
        if (note == null || note.isEmpty) {
            Text(
                stringResource(R.string.meeting_no_notes),
                style = MaterialTheme.typography.bodyMedium,
                color = MasalaTheme.tokens.onWallpaperMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            section(note.summary)
            if (note.keyPoints.isNotEmpty()) {
                Text(stringResource(R.string.meeting_key_points), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                section(note.keyPoints)
            }
            if (note.decisions.isNotEmpty()) {
                Text(stringResource(R.string.meeting_decisions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                section(note.decisions)
            }
            if (note.actions.isNotEmpty()) {
                Text(stringResource(R.string.meeting_actions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                note.actions.forEachIndexed { index, action ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckLine(
                            checked = index in done,
                            text = action.text + (action.owner?.let { " — $it" } ?: ""),
                            onChange = { onToggle(index) },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemind(action) }) { Text(stringResource(R.string.meeting_remind)) }
                    }
                }
            }
            if (note.questions.isNotEmpty()) {
                Text(stringResource(R.string.meeting_questions), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                section(note.questions)
            }
            if (note.followUps.isNotEmpty()) {
                Text(stringResource(R.string.meeting_follow_ups), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                section(note.followUps)
            }
            if (note.failedWindows.isNotEmpty()) {
                Text(
                    "Parts that could not be summarised: ${note.failedWindows.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRegenerate, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.meeting_regenerate)) }
            Button(onClick = onSaveBrain, enabled = !saving, modifier = Modifier.weight(1f)) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (saved) R.string.meeting_saved_brain else R.string.meeting_save_brain))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun section(items: List<String>) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        items.forEach { line ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("•", color = MasalaTheme.tokens.accent)
                Spacer(Modifier.width(8.dp))
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TranscriptTab(
    turns: List<MeetingTurnEntity>,
    names: Map<String, String>,
    labels: List<String>,
    onSeek: (Long) -> Unit,
    onRename: (String) -> Unit,
) {
    if (turns.isEmpty()) {
        Text(
            stringResource(R.string.meeting_no_transcript),
            style = MaterialTheme.typography.bodyMedium,
            color = MasalaTheme.tokens.onWallpaperMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    var filter by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            labels.take(6).forEach { label ->
                FilterChip(
                    selected = filter == label,
                    onClick = { filter = label },
                    label = { Text(names[label] ?: "Speaker $label") },
                )
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(turns.filter { filter == null || it.speaker == filter }, key = { it.id }) { turn ->
                TurnRow(turn, names, onClick = { onSeek(turn.startMs) }, onSpeaker = turn.speaker?.let { { onRename(it) } })
                HorizontalDivider(color = MasalaTheme.tokens.hairline)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TurnRow(
    turn: MeetingTurnEntity,
    names: Map<String, String>,
    onClick: (() -> Unit)? = null,
    onSpeaker: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(vertical = 6.dp)) {
        Text(
            DiarizedTranscript.clockOf(turn.startMs),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MasalaTheme.tokens.onWallpaperMedium,
            modifier = Modifier.width(44.dp).padding(top = 3.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                names[turn.speaker] ?: turn.speaker?.let { "Speaker $it" } ?: "Speaker",
                style = MaterialTheme.typography.labelMedium,
                color = MasalaTheme.tokens.accentText,
                modifier = Modifier.clickable(enabled = onSpeaker != null) { onSpeaker?.invoke() },
            )
            Text(turn.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SpeakersTab(
    labels: List<String>,
    names: Map<String, String>,
    talk: Map<String, Long>,
    total: Long,
    onRename: (String) -> Unit,
) {
    if (labels.isEmpty()) {
        Text(
            stringResource(R.string.meeting_no_transcript),
            style = MaterialTheme.typography.bodyMedium,
            color = MasalaTheme.tokens.onWallpaperMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        labels.forEach { label ->
            Row(
                Modifier.fillMaxWidth().clickable { onRename(label) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(MasalaTheme.tokens.accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Text(label.take(1), style = MaterialTheme.typography.titleSmall, color = MasalaTheme.tokens.accentText)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(names[label] ?: "Speaker $label", style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.meeting_talk_time, ((talk[label] ?: 0) * 100 / total).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MasalaTheme.tokens.onWallpaperMedium,
                    )
                }
                TextButton(onClick = { onRename(label) }) { Text(stringResource(R.string.meeting_speaker_rename)) }
            }
            HorizontalDivider(color = MasalaTheme.tokens.hairline)
        }
    }
}

@Composable
private fun SpeakerDialog(label: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meeting_speaker_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Speaker $label") },
                    placeholder = { Text(stringResource(R.string.meeting_speaker_hint)) },
                )
                Text(stringResource(R.string.meeting_speaker_all), style = MaterialTheme.typography.bodySmall, color = MasalaTheme.tokens.onWallpaperMedium)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.meeting_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.meeting_cancel)) } },
    )
}

/** Plays the meeting's chunk files as one timeline, so a turn's timestamp can seek into it. */
private data class MeetingPart(val file: File, val startMs: Long, val durationMs: Long)

private class MeetingPlayer(private val parts: List<MeetingPart>) {
    private var player: MediaPlayer? = null
    private var index = -1
    private var baseMs = 0L

    val positionMs: Long get() = baseMs + (player?.currentPosition ?: 0).toLong()

    fun playFrom(globalMs: Long) {
        val target = parts.indexOfLast { globalMs >= it.startMs }.coerceAtLeast(0)
        play(target, (globalMs - parts[target].startMs).coerceAtLeast(0))
    }

    private fun play(newIndex: Int, offsetMs: Long) {
        if (newIndex == index && player != null) {
            player?.seekTo(offsetMs.toInt())
            player?.start()
            return
        }
        player?.release()
        player = null
        val part = parts.getOrNull(newIndex) ?: return
        baseMs = part.startMs
        index = newIndex
        player = MediaPlayer().apply {
            setDataSource(part.file.absolutePath)
            setOnCompletionListener {
                if (newIndex + 1 < parts.size) play(newIndex + 1, 0)
            }
            prepare()
            seekTo(offsetMs.toInt())
            start()
        }
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }
}

/** A checkbox with a wrapping label, matching the rest of the app's rows. */
@Composable
private fun CheckLine(checked: Boolean, text: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).clickable { onChange(!checked) },
        )
    }
}

/** What the screens need from the recorder, without the amplitude (which changes 8 times a second). */
private data class RecorderTick(
    val meetingId: String?,
    val active: Boolean,
    val status: String = MeetingStatus.READY,
    val elapsedMs: Long = 0,
)

private fun durationText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60} min"
}

private fun speakerCountText(count: Int): String = if (count == 1) "1 speaker" else "$count speakers"

private fun partCountText(count: Int): String = if (count == 1) "1 part" else "$count parts"

@Composable
private fun LiveWaveform(running: Boolean) {
    val amplitude by remember {
        MeetingRecorderService.state.map { it.amplitude }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(0f)
    Waveform(amplitude, running)
}
