package com.samosaai.masala

import android.app.Application
import android.content.Context
import com.samosaai.masala.audio.AudioProvider
import com.samosaai.masala.llm.KeyCipher
import com.samosaai.masala.llm.AiSettingsStore
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import com.samosaai.masala.core.files.FileIndex
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.actions.ConfirmationGate
import com.samosaai.masala.actions.QuestionGate
import com.samosaai.masala.agent.AgentModel
import com.samosaai.masala.i18n.Language
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.apps.AppSearch
import com.samosaai.masala.audio.TtsEngine
import com.samosaai.masala.audio.SttEngine
import com.samosaai.masala.agent.CommandController
import com.samosaai.masala.agent.ConsultService
import com.samosaai.masala.agent.ConfiguredSpeechInput
import com.samosaai.masala.agent.LlmTranscriptCleaner
import com.samosaai.masala.agent.SpeechStrings
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.agent.AgentPrompt
import com.samosaai.masala.agent.AgentTools
import com.samosaai.masala.agent.AgentToolRunner
import com.samosaai.masala.agent.TodoTools
import com.samosaai.masala.skills.SkillSearchTool
import com.samosaai.masala.agent.LlmAgentModel
import com.samosaai.masala.agent.MasalaAgent
import com.samosaai.masala.agent.MemoryRawTool
import com.samosaai.masala.agent.MemoryTools
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.PrefsMcpStorage
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.secondbrain.SecondBrainRepository
import com.samosaai.masala.secondbrain.TaskNoteLinks
import com.samosaai.masala.secondbrain.SecondBrainStore
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.llm.LlmConfig
import com.samosaai.masala.skills.AiReports
import com.samosaai.masala.skills.PrefsDeclinedSchedules
import com.samosaai.masala.skills.SkillRunner
import com.samosaai.masala.brief.BriefInputs
import com.samosaai.masala.skills.SkillScheduling
import com.samosaai.masala.skills.TranscriptFiles
import com.samosaai.masala.scheduled.ScheduledSkillRunner
import com.samosaai.masala.ui.skills.SkillSheetActivity
import com.samosaai.masala.core.agent.ToolInfo
import com.samosaai.masala.core.agent.ToolScope
import com.samosaai.masala.context.ContextService
import com.samosaai.masala.data.MasalaDatabase
import com.samosaai.masala.data.SettingsStore
import com.samosaai.masala.files.AppStorageFileAccess
import com.samosaai.masala.launcher.AppActions
import com.samosaai.masala.launcher.AppCatalog
import com.samosaai.masala.launcher.IconCache
import com.samosaai.masala.launcher.LauncherModel
import com.samosaai.masala.layout.LayoutRepository
import com.samosaai.masala.layout.ModeStore
import com.samosaai.masala.miniapps.MiniAppConsole
import com.samosaai.masala.miniapps.MiniAppStore
import com.samosaai.masala.permissions.PermissionGate
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.pro.ProTools
import com.samosaai.masala.scheduled.PrefsTaskStorage
import com.samosaai.masala.scheduled.ScheduledTaskScheduler
import com.samosaai.masala.scheduled.TaskRunner
import com.samosaai.masala.skills.SkillRegistry
import com.samosaai.masala.tools.ActionLog
import com.samosaai.masala.tools.DocumentText
import com.samosaai.masala.tools.MasalaToolExecutor
import com.samosaai.masala.tools.MasalaToolRegistry
import com.samosaai.masala.tools.MasalaTools
import com.samosaai.masala.tools.ToolGate
import com.samosaai.masala.core.tool.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Holds the launcher's long-lived parts. Home and settings both read them through [masala]. */
class MasalaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database by lazy { MasalaDatabase.create(this) }
    val miniApps by lazy { MiniAppStore(database.miniApps()) }
    val streamSources by lazy {
        com.samosaai.masala.widgets.StreamSourceStore(database.streamSources(), com.samosaai.masala.llm.KeyCipher.androidKeystore("masala_stream_secrets"))
    }
    /** Every generated widget's own values (counters, ticks); shared by home and the manager. */
    val widgetStates by lazy { com.samosaai.masala.widgets.WidgetStateStore(scope, database.widgetStates()) }
    val widgetLibrary by lazy { com.samosaai.masala.widgets.WidgetLibrary(database.widgets(), database.widgetVersions()) }
    val settings by lazy { SettingsStore(this) }

    /** Open-Meteo for the daily brief and the `weather` tool. */
    val weather by lazy { com.samosaai.masala.weather.WeatherClient() }
    val actionLog by lazy { ActionLog(this) }

    /** Bundled personas and skills, then the ones the user imported. */
    val skills by lazy { SkillRegistry(this) }

    val scheduledTasks by lazy { ScheduledTaskStore(PrefsTaskStorage(this)) }

    /** The experts consulted inside each chat; hidden from the list and deleted with the chat. */
    val consults by lazy {
        com.samosaai.masala.chat.ConsultSessions(java.io.File(filesDir, com.samosaai.masala.chat.ConsultSessions.DIR))
    }

    /** Conversations, stored as one JSON file each; the chat surface reads and writes them. */
    val chats by lazy {
        com.samosaai.masala.chat.ChatSessions(
            java.io.File(filesDir, com.samosaai.masala.chat.ChatSessions.DIR),
            onDeleted = { id -> consults.deleteChat(id) },
        )
    }

    /** The conversation turns are being added to; created on the first turn of a new chat. */
    val activeChatId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Starts a scheduled task's run as a foreground job (plan Phase 4 M6). */
    @Volatile var taskRunner: TaskRunner? = null

    /** `:app`'s tools, then `:pro`'s, which also swaps in all-files access (arch §3.1). */
    val tools by lazy {
        MasalaToolRegistry(AppStorageFileAccess(this)).also { registry ->
            MasalaTools.create(this, ToolFiles { registry.fileAccess }, miniApps).forEach(registry::register)
            com.samosaai.masala.widgets.WidgetTools.create(this).forEach(registry::register)
            fileIndex = ProTools.register(registry, this, DocumentText(this))
        }
    }

    /** The index of shared storage behind `file_search` (Lane B); null in a build without `:pro`. */
    var fileIndex: FileIndex? = null
        private set

    /**
     * A new agent conversation over [model]. [scope] picks the tools from what is registered (a typed
     * command sees all of them); [instructions] is a persona's or skill's own text.
     */
    fun agent(
        model: AgentModel,
        gate: ToolGate = confirmationGate,
        scope: (List<ToolInfo>) -> ToolScope = { ToolScope.command(it) },
        instructions: String? = null,
        maxSteps: Int = aiSettings.profile.value.maxToolRounds,
        maxRepeatedToolCalls: Int = aiSettings.profile.value.maxRepeatedToolCalls,
        fetchOnlyResults: Boolean = false,
        /** Skills listed in the prompt. An expert's run lists none: it cannot start or consult them. */
        listSkills: Boolean = true,
    ): MasalaAgent {
        val executor = toolExecutor(gate)
        // The run's own scratchpad: one list per agent, answered locally so it never leaves the run.
        val todos = TodoTools.TodoList()
        val runner: AgentToolRunner = { name, args ->
            if (name in TodoTools.names) todos.handle(name, args).toToolResult()
            else executor.execute(name, args)
        }
        val infos = tools.tools.map { ToolInfo(it.name, it.sensitivity, it.readsPrivateData, it.opensScreen) } + TodoTools.infos
        return MasalaAgent(
            model = model,
            tools = runner,
            definitions = tools.tools.map { it.definition } + TodoTools.definitions,
            scope = scope(infos),
            systemPrompt = { ctx ->
                val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date())
                AgentPrompt.build(
                    "$now (${java.util.TimeZone.getDefault().id})",
                    if (listSkills) effectiveSkills() else emptyList(),
                    secondBrain.promptNow(),
                    instructions,
                    ctx,
                )
            },
            maxSteps = maxSteps,
            maxRepeatedToolCalls = maxRepeatedToolCalls,
            fetchOnlyResults = fetchOnlyResults,
        )
    }

    /** Runs skills; app-scoped, so a run goes on after its sheet closes. */
    val skillRunner by lazy {
        SkillRunner(
            scope, database.skillRuns(), database.skillArtifacts(), TranscriptFiles(java.io.File(filesDir, "skill_runs")),
            { if (it == COMMAND_SKILL_ID) COMMAND_SKILL else skills.getSkillById(it) }, ::skillAgent,
            runData = { skill -> if (skill.id == BriefInputs.SKILL_ID) BriefInputs(this).collect().toPrompt() else null },
            fallback = { skill, error ->
                if (skill.id != BriefInputs.SKILL_ID) null
                else com.samosaai.masala.core.brief.BriefFallback.render(BriefInputs(this).collect(), com.samosaai.masala.core.brief.BriefFallback.reasonFor(error))
            },
        )
    }

    val aiReports by lazy { AiReports(this) }

    /** The user's MCP servers; their tools join the registry as `mcp_…`. Touched in [onCreate] so saved servers register at startup. */
    val mcp by lazy { McpServerStore(PrefsMcpStorage(this), tools::setDynamic) }

    /** Skill widgets and their default schedules. */
    val skillScheduling by lazy { SkillScheduling(scheduledTasks, PrefsDeclinedSchedules(this)) }

    /** The Second Brain; approved writes are recorded in the action log. */
    val secondBrain: SecondBrainRepository by lazy {
        SecondBrainRepository(
            scope,
            SecondBrainStore(java.io.File(filesDir, "secondbrain")),
            database.wikiPages(),
            audit = { action, detail ->
                actionLog.record(action, detail, ToolResult.Success("Second Brain changed"))
            },
        ).also { repo ->
            repo.onNoteDeleted = { taskNoteLinks.noteDeleted(it) }
            scope.launch(Dispatchers.IO) { repo.ensure() }
        }
    }

    /** Scheduled tasks and Second Brain notes, linked both ways (plan `second-brain-robust.md`, S5). */
    val taskNoteLinks: TaskNoteLinks by lazy { TaskNoteLinks(scheduledTasks, secondBrain) }

    /** Models, keys and voice choices (Settings → Masala AI); every model call reads it. */
    val aiSettings by lazy { AiSettingsStore(this, KeyCipher.androidKeystore()) }

    /** Fetch list and Test connection in Settings → Models & keys. */
    val aiConnection by lazy { com.samosaai.masala.llm.AiConnection() }

    /** Voices for the picker: the phone's for [language], or a provider's (from its speech models,
     *  else the preset's fallback list for servers that publish none, like OpenAI). */
    suspend fun voicesFor(out: com.samosaai.masala.core.ai.Endpoint?, language: Language): List<String> =
        if (out == null) {
            tts.androidVoices(language).map { it.name }
        } else {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    com.samosaai.masala.audio.AudioApi(out.baseUrl, aiSettings.keyFor(out)).listAudioModels()
                        .filter { it.category == com.samosaai.masala.audio.ModelCategory.TTS }
                        .flatMap { m -> m.voices.map { it.id } }
                        .distinct()
                }.getOrDefault(emptyList())
                    .ifEmpty { com.samosaai.masala.core.ai.ProviderPresets.byId(out.presetId).fallbackVoices }
            }
        }

    private val stt by lazy {
        SttEngine(this, SpeechConfig.default.baseUrl, SpeechConfig.default.apiKey)
    }
    private val tts by lazy { TtsEngine(this) }

    /** The speech-to-text engine, for the Voice settings test (same engine the mic uses). */
    internal fun sttEngine(): SttEngine = stt

    /** Deletes a mini app with its home pins and console log; every Delete (screen, settings, tool) goes through here. */
    suspend fun deleteMiniApp(id: String) {
        miniApps.delete(id)
        layouts.unpinMiniApp(id)
        MiniAppConsole.clear(id)
    }

    /** The language Masala speaks and writes spoken text in: Settings → Voice, else the phone's. */
    fun voiceLanguage(): Language =
        aiSettings.profile.value.voiceLanguage.takeIf { it.isNotBlank() }?.let { code -> Language.entries.firstOrNull { it.matchesCode(code) } }
            ?: Language.entries.firstOrNull { it.matchesCode(java.util.Locale.getDefault().language) } ?: Language.ENGLISH

    /**
     * Reads [text] aloud with the voice chosen in Settings → Voice (the phone's, or an API voice). Null when it
     * played to the end (or the user stopped it), else the user-facing reason.
     */
    suspend fun speakReply(text: String): String? {
        val profile = aiSettings.profile.value
        val language = voiceLanguage()
        val out = profile.voiceOut
        val spoke = if (out != null) {
            // A blank model or voice would make speakApi return false (or 400 on OpenAI): the
            // preset's defaults fill in before the call, so an untouched toggle still speaks.
            val preset = com.samosaai.masala.core.ai.ProviderPresets.byId(out.presetId)
            tts.speak(
                text, AudioProvider.API,
                apiModel = out.model.ifBlank { preset.defaultTtsModel },
                voice = profile.voice.ifBlank { preset.fallbackVoices.firstOrNull().orEmpty() },
                language = language, rate = profile.speechRate,
            )
        } else {
            tts.speak(text, language = language, androidVoice = profile.voice, rate = profile.speechRate)
        }
        if (spoke) return null
        if (tts.androidInterrupted) return null
        val missing = tts.lastLanguageUnavailable
        android.util.Log.w("MasalaApp", "speakReply failed; languageUnavailable=$missing")
        return if (missing != null) {
            getString(R.string.read_aloud_no_voice, missing.label)
        } else {
            getString(R.string.read_aloud_no_engine)
        }
    }

    fun stopSpeaking() = tts.stop()

    /** The widget's Run now: starts [skillId]'s scheduled task; null when it started, else why not. */
    suspend fun runSkillTaskNow(skillId: String): String? {
        val task = kotlinx.coroutines.withContext(Dispatchers.IO) { scheduledTasks.all().firstOrNull { it.skillId == skillId } }
            ?: return getString(R.string.skill_widget_no_task)
        return when (val start = com.samosaai.masala.scheduled.ScheduledTaskRuns(this).runNow(task.id)) {
            is com.samosaai.masala.scheduled.ScheduledStart.Started -> null
            com.samosaai.masala.scheduled.ScheduledStart.Busy -> getString(R.string.skill_widget_busy)
            is com.samosaai.masala.scheduled.ScheduledStart.Failed -> start.detail
        }
    }

    /**
     * Whether [run] is read aloud now without a tap (plan `phase5-daily-brief.md` D5): once per run, and
     * only when read-aloud is on and the phone isn't silenced or in Do Not Disturb.
     */
    suspend fun claimAutoRead(run: com.samosaai.masala.data.SkillRunEntity): Boolean {
        val prefs = getSharedPreferences("auto_read", MODE_PRIVATE)
        val ringerMode = getSystemService(android.media.AudioManager::class.java)?.ringerMode
        val interruptionFilter = getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter
        val quiet = com.samosaai.masala.brief.BriefWidget.isQuiet(ringerMode, interruptionFilter)
        val due = com.samosaai.masala.brief.BriefWidget.autoReadDue(
            run.id, run.status, run.finishedAt, System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
            settings.current().readRepliesAloud, quiet, prefs.getLong(run.skillId, -1),
        )
        if (due) prefs.edit().putLong(run.skillId, run.id).apply()
        return due
    }

    /** True while a reply is being read aloud (Sam's speaking state). */
    val speaking get() = tts.isSpeaking

    // The transcript cleaner's client, rebuilt only when the model settings change.
    private var cleanerClient: Pair<LlmConfig, com.samosaai.masala.llm.LLMClient>? = null

    private fun cleanerClient(): com.samosaai.masala.llm.LLMClient = synchronized(this) {
        val config = LlmConfig.default
        cleanerClient?.takeIf { it.first == config }?.second
            ?: config.client(this).also { cleanerClient = config to it }
    }

    /** Typed and spoken commands: the fast path, persona routing, then the agent. */
    val commands by lazy {
        CommandController(
            scope = scope,
            // Exact or leading words only: a command that happens to start with "open" must not launch a guess.
            resolveApp = { label ->
                val wanted = AppSearch.normalize(label)
                val entry = launcher.apps.value.let { apps ->
                    apps.firstOrNull { AppSearch.normalize(it.label) == wanted }
                        ?: apps.firstOrNull { AppSearch.normalize(it.label).startsWith("$wanted ") }
                }
                entry?.let { MasalaAction.LaunchApp(it.key.packageName, it.key.activity, it.key.user) }
            },
            names = { launcher.apps.value.map { it.label } + modes.patches.value.map { it.id } + skills.getAllSkills().map { it.title } },
            run = { dispatcher.dispatch(it, NoScreen) },
            skills = { skills.getAllSkills() },
            newAgent = { agent(LlmAgentModel(LlmConfig.default.client(this))) },
            speech = ConfiguredSpeechInput(
                stt,
                { SpeechConfig.default },
                stt::isLocalRecognitionAvailable,
                SpeechStrings(
                    localUnavailable = getString(R.string.speech_local_unavailable),
                    recordFailed = getString(R.string.speech_record_failed),
                ),
                live = stt,
            ),
            speechStrings = SpeechStrings(
                localUnavailable = getString(R.string.speech_local_unavailable),
                recordFailed = getString(R.string.speech_record_failed),
                noSpeech = getString(R.string.stt_no_speech),
                didntCatch = getString(R.string.stt_didnt_catch),
                busy = getString(R.string.stt_error_busy),
                server = getString(R.string.stt_error_server),
                auth = getString(R.string.stt_error_auth),
                recordError = getString(R.string.stt_error_record),
                generic = getString(R.string.stt_error_generic),
            ),
            summarize = { transcript -> summarizeHistory(transcript) },
            onTurnStart = { consultCalls.set(0) },
            // Every transcript, on-device or server, is tidied by the model before it is sent.
            cleaner = LlmTranscriptCleaner(
                complete = { messages ->
                    cleanerClient().chat(messages, temperature = 0f)
                        .choices.firstOrNull()?.message?.textContent.orEmpty()
                },
                // Auto (no language set) stays null: the phone's language would translate.
                language = {
                    SpeechConfig.default.sttLanguage.takeIf { it.isNotBlank() }
                        ?.let { code -> Language.entries.firstOrNull { it.matchesCode(code) } }
                },
            ),
        ).also { controller ->
            controller.onTurn = ::recordTurn
            scope.launch {
                chats.load()
                // Consults whose chat is gone: the app was killed between the two deletes.
                consults.pruneOrphans(chats.summaries.value.mapTo(HashSet()) { it.id })
            }
        }
    }

    /**
     * Appends a turn to the active conversation, starting one when there is none. Turns are
     * serialized: a fast command can produce the user turn and its reply before the first
     * append has left the main thread, and two unguarded launches would each create their
     * own session, splitting one exchange across two chats.
     */
    private val turnLock = kotlinx.coroutines.sync.Mutex()

    internal fun recordTurn(turn: com.samosaai.masala.chat.ChatTurn) {
        scope.launch {
            turnLock.withLock {
                val id = activeChatId.value ?: chats.create().id.also { activeChatId.value = it }
                chats.append(id, turn)
            }
        }
    }

    /**
     * Condenses a long conversation so the model keeps the thread without the transcript:
     * what was asked, what was decided or built (names, ids, paths), preferences, open ends.
     */
    private suspend fun summarizeHistory(transcript: String): String? = runCatching {
        val model = LlmAgentModel(LlmConfig.default.client(this))
        val prompt = listOf(
            com.samosaai.masala.llm.ChatMessage(
                "system",
                kotlinx.serialization.json.JsonPrimitive(
                    "Summarise this conversation so it can be continued. Keep: what the user wanted, " +
                        "decisions and preferences, anything created or changed (app names, ids, file paths, code), " +
                        "and what is still open. Be compact and factual; no greetings, no advice.",
                ),
            ),
            com.samosaai.masala.llm.ChatMessage("user", kotlinx.serialization.json.JsonPrimitive(transcript.take(60_000))),
        )
        model.next(prompt, emptyList()).textContent.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        /** A scheduled task with no skill runs a plain command under this id. */
        const val COMMAND_SKILL_ID = "command"
        val COMMAND_SKILL = MasalaSkill(id = COMMAND_SKILL_ID, title = "Command", instructions = "")

        /** An expert gets a short run: it answers, it doesn't build things. */
        const val MAX_CONSULT_STEPS = 15

        /** Consults one turn may make, so one question can't spend the chat's whole allowance. */
        const val MAX_CONSULT_CALLS_PER_TURN = 6

        /** An expert can't consult, open a skill sheet, or wait out the clock from inside its own answer. */
        val CONSULT_EXCLUDED = setOf("consult_expert", "run_skill", "sleep")
    }

    private object NoScreen : ActionDispatcher.Screen {
        override fun openDrawer(query: String?) = Unit
        override fun ask(prompt: String) = Unit
    }

    /** The agent for one skill run: the persona's and skill's instructions, the skill's tools, more steps. */
    private fun skillAgent(skill: MasalaSkill, unattended: Boolean) = agent(
        model = LlmAgentModel(LlmConfig.default.client(this)),
        // A run nobody watches can't ask, so it only gets tools that never need a confirmation.
        // Skill discovery stays out: a scheduled run does its own skill, not someone else's.
        // Waiting stays out too: a 300 s sleep would hold the worker past its budget.
        // Location stays out: background location needs a Play declaration a launcher won't get.
        scope = { infos -> ToolScope.skill(skill, infos).let { if (unattended) ToolScope.unattended(it, infos, setOf("search_skills", "sleep", "get_location")) else it } },
        instructions = listOfNotNull(skill.persona?.systemPrompt, skill.instructions).joinToString("\n\n"),
        maxSteps = aiSettings.profile.value.maxToolRounds,
        maxRepeatedToolCalls = aiSettings.profile.value.maxRepeatedToolCalls,
        // It holds private data and nobody sees what it opens, so it opens only what its searches returned.
        fetchOnlyResults = unattended,
    )

    /** What the user changed about an expert: its prompt, when it is called, and its tools. */
    val personas by lazy { com.samosaai.masala.skills.PersonaStore(java.io.File(filesDir, "personas")) }

    /** The experts on offer: installed personas minus any the user removed. */
    fun experts(): List<MasalaSkill> = skills.getPersonas().filterNot { personas.isHidden(it.id) }

    /**
     * The installed skills with the user's persona edits applied. Everything that describes or runs
     * an expert reads this, so nothing about a persona is fixed in the prompt.
     */
    private fun effectiveSkills(): List<MasalaSkill> = skills.getAllSkills()
        .filterNot { it.persona != null && personas.isHidden(it.id) }
        .map { skill ->
        val persona = skill.persona ?: return@map skill
        skill.copy(
            persona = persona.copy(
                systemPrompt = personas.promptFor(skill.id) ?: persona.systemPrompt,
                whenToUse = personas.whenToUseFor(skill.id) ?: persona.whenToUse,
            ),
        )
    }

    /** The agent for one expert consult: its persona's own instructions, its tools, a bounded run. */
    private fun consultAgent(skill: MasalaSkill) = agent(
        model = LlmAgentModel(LlmConfig.default.client(this)),
        // The main agent's tools, minus a consult inside a consult, a skill sheet, and anything
        // that would take the phone's screen away from the chat — plus the user's own switches.
        scope = { infos -> ToolScope.consult(infos, CONSULT_EXCLUDED + personas.toolsFor(skill.id)) },
        // [skill] already carries the user's prompt and "when to use" edits.
        instructions = listOfNotNull(skill.persona?.systemPrompt, skill.instructions).joinToString("\n\n"),
        maxSteps = MAX_CONSULT_STEPS,
        listSkills = false,
    )

    private val consultService by lazy {
        ConsultService(
            sessions = consults,
            skill = { id -> effectiveSkills().firstOrNull { it.id == id } },
            agentFor = ::consultAgent,
        )
    }

    /** Consult calls spent in the current chat turn; reset when the turn starts. */
    private val consultCalls = java.util.concurrent.atomic.AtomicInteger()

    /** The `consult_expert` tool: one exchange with a persona, inside the chat that asked. */
    private suspend fun consultExpert(skillId: String, question: String, consultId: String?): ActionResult {
        val chatId = activeChatId.value
            ?: return ActionResult.error("Experts can only be consulted inside a chat.")
        if (consultCalls.incrementAndGet() > MAX_CONSULT_CALLS_PER_TURN) {
            return ActionResult.error(
                "That is enough experts for this turn ($MAX_CONSULT_CALLS_PER_TURN). Answer from what you have.",
            )
        }
        return when (val outcome = consultService.consult(chatId, skillId, question, consultId)) {
            is ConsultService.Outcome.Answered -> ActionResult.ok(outcome.text, outcome.produced)
            is ConsultService.Outcome.Refused -> ActionResult.error(outcome.reason)
        }
    }

    /** The `run_skill` tool: opens the skill sheet on a prompt. Experts are consulted, not opened. */
    private fun openSkillSheet(skillId: String, prompt: String?): ActionResult {
        val skill = skills.getSkillById(skillId) ?: return ActionResult.error("There is no skill '$skillId'.")
        if (skill.persona != null) {
            return ActionResult.error(
                "'${skill.title.ifBlank { skill.id }}' is an expert. Ask it with consult_expert instead.",
            )
        }
        startActivity(SkillSheetActivity.intent(this, skillId, prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult.ok("Opened the $skillId skill for the user.")
    }

    /** Runs tools for one caller; [gate] confirms anything that isn't read-only. */
    fun toolExecutor(gate: ToolGate) = MasalaToolExecutor(this, tools, actionLog, gate)

    val modes by lazy { ModeStore(this) }

    /** The one gate for taps, gestures and widget buttons; the home screen shows its question. */
    val confirmationGate by lazy { ConfirmationGate(actionLog) }

    /** The current `question` call, if any; the chat draws it as a sheet with the options. */
    val questionGate by lazy { QuestionGate() }

    lateinit var catalog: AppCatalog
        private set
    lateinit var icons: IconCache
        private set
    lateinit var actions: AppActions
        private set

    /** Launch counts for the globe drawer and "Suggested now" (UI refresh U3). */
    val usage by lazy { com.samosaai.masala.launcher.AppUsage(this, database.appLaunches(), scope) }
    lateinit var launcher: LauncherModel
        private set
    lateinit var contextService: ContextService
        private set
    lateinit var layouts: LayoutRepository
        private set
    lateinit var dispatcher: ActionDispatcher
        private set

    override fun onCreate() {
        super.onCreate()
        // Tools ask for permissions from background threads; the gate needs a context for that.
        PermissionGate.context = this
        catalog = AppCatalog(this, scope)
        icons = IconCache(this, catalog)
        actions = AppActions(this, catalog) { usage.record(it) }
        launcher = LauncherModel(scope, catalog, database.appOverrides(), actions)
        catalog.start()

        contextService = ContextService(this, scope, modes).also { it.start() }
        layouts = LayoutRepository(
            context = this,
            scope = scope,
            dao = database.layoutVersions(),
            catalog = catalog,
            launcher = launcher,
            modes = modes,
            contextService = contextService,
            skills = { skills },
            toolNames = { tools.tools.map { it.name }.toSet() },
            widgets = widgetLibrary,
            streams = streamSources,
        )
        AgentTools.create(
            this, layouts, modes,
            runSkill = ::openSkillSheet,
            consult = ::consultExpert,
            ask = { question, options -> QuestionGate.actionResultOf(questionGate.ask(question, options)) },
        ).forEach(tools::register)
        tools.register(SkillSearchTool.create(skills::searchSkills, personas::hiddenIds))
        MemoryTools.create(secondBrain).forEach(tools::register)
        tools.register(MemoryRawTool.create(this, secondBrain) { tools.fileAccess })
        tools.register(MemoryTools.linkTask(taskNoteLinks))
        mcp.servers
        dispatcher = ActionDispatcher(this, actions, launcher, modes, confirmationGate, tools, { skills }) { toolExecutor(confirmationGate) }

        taskRunner = ScheduledSkillRunner(this)

        // A force-stop drops the alarm; the store still has the tasks.
        scope.launch(Dispatchers.IO) { ScheduledTaskScheduler.reschedule(this@MasalaApp) }

        scope.launch {
            catalog.removals.collect { database.appOverrides().deletePackage(it.packageName, it.user) }
        }
        // Updated apps may have new icons.
        scope.launch { catalog.state.drop(1).collect { icons.clear() } }
        // Model calls read the AI settings; the speech engines follow them when they change.
        LlmConfig.source = { aiSettings.chatConfig() }
        SpeechConfig.source = { aiSettings.speechConfig() }
        // Off the main thread: the store's first read and key decryption use DataStore and the Keystore.
        scope.launch(Dispatchers.Default) {
            aiSettings.warmKeys()
            aiSettings.profile.collect { p ->
                val speech = aiSettings.speechConfig()
                stt.configureApi(speech.baseUrl, speech.apiKey)
                p.voiceOut.let { out -> tts.configureApi(out?.baseUrl.orEmpty(), out?.let(aiSettings::keyFor).orEmpty()) }
            }
        }
        // A chosen icon lives on the app's override row; the cache keeps the current set so every
        // tile (home, drawer, the app menu) draws it without being told.
        scope.launch(Dispatchers.Default) {
            launcher.apps.collect { apps -> icons.applyOverrides(apps.mapNotNull { app -> app.icon?.let { app.key.id to it } }.toMap()) }
        }
        // Icon mask, style and tint follow Appearance; the tint is the accent.
        scope.launch(Dispatchers.Default) {
            settings.settings.collect { s ->
                if (s.accent == com.samosaai.masala.data.Accent.WALLPAPER) com.samosaai.masala.ui.theme.WallpaperAccent.refresh(this@MasalaApp)
                val tint = com.samosaai.masala.ui.theme.accentColor(this@MasalaApp, s.accent).toArgb()
                icons.applyStyle(IconCache.Style(s.iconShape, s.iconStyle, tint))
            }
        }
    }
}

val Context.masala: MasalaApp get() = applicationContext as MasalaApp
