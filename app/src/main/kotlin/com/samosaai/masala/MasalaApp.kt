package com.samosaai.masala

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import com.samosaai.masala.core.files.FileIndex
import com.samosaai.masala.core.tool.ToolFiles
import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.actions.ConfirmationGate
import com.samosaai.masala.agent.AgentModel
import com.samosaai.masala.i18n.Language
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.apps.AppSearch
import com.samosaai.masala.audio.TtsEngine
import com.samosaai.masala.audio.SttEngine
import com.samosaai.masala.agent.CommandController
import com.samosaai.masala.agent.ConfiguredSpeechInput
import com.samosaai.masala.agent.SpeechStrings
import com.samosaai.masala.audio.SpeechConfig
import com.samosaai.masala.agent.AgentPrompt
import com.samosaai.masala.agent.AgentTools
import com.samosaai.masala.agent.LlmAgentModel
import com.samosaai.masala.agent.MasalaAgent
import com.samosaai.masala.agent.MemoryTools
import com.samosaai.masala.connectors.mcp.McpServerStore
import com.samosaai.masala.connectors.mcp.PrefsMcpStorage
import com.samosaai.masala.core.tool.ToolResult
import com.samosaai.masala.secondbrain.SecondBrainRepository
import com.samosaai.masala.secondbrain.SecondBrainStore
import com.samosaai.masala.core.contract.MasalaSkill
import com.samosaai.masala.core.tool.ActionResult
import com.samosaai.masala.llm.LlmConfig
import com.samosaai.masala.skills.AiReports
import com.samosaai.masala.skills.PrefsDeclinedSchedules
import com.samosaai.masala.skills.SkillRunner
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

/** Holds the launcher's long-lived parts. Home and settings both read them through [masala]. */
class MasalaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database by lazy { MasalaDatabase.create(this) }
    val miniApps by lazy { MiniAppStore(database.miniApps()) }
    val settings by lazy { SettingsStore(this) }
    val actionLog by lazy { ActionLog(this) }

    /** Bundled personas and skills, then the ones the user imported. */
    val skills by lazy { SkillRegistry(this) }

    val scheduledTasks by lazy { ScheduledTaskStore(PrefsTaskStorage(this)) }

    /** Starts a scheduled task's run as a foreground job (plan Phase 4 M6). */
    @Volatile var taskRunner: TaskRunner? = null

    /** `:app`'s tools, then `:pro`'s, which also swaps in all-files access (arch §3.1). */
    val tools by lazy {
        MasalaToolRegistry(AppStorageFileAccess(this)).also { registry ->
            MasalaTools.create(this, ToolFiles { registry.fileAccess }, miniApps).forEach(registry::register)
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
        maxSteps: Int = MasalaAgent.DEFAULT_MAX_STEPS,
    ): MasalaAgent {
        val executor = toolExecutor(gate)
        val infos = tools.tools.map { ToolInfo(it.name, it.sensitivity, it.readsPrivateData, it.opensScreen) }
        return MasalaAgent(
            model = model,
            tools = { name, args -> executor.execute(name, args) },
            definitions = tools.tools.map { it.definition },
            scope = scope(infos),
            systemPrompt = {
                val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date())
                AgentPrompt.build("$now (${java.util.TimeZone.getDefault().id})", skills.getAllSkills(), secondBrain.promptNow(), instructions)
            },
            maxSteps = maxSteps,
        )
    }

    /** Runs skills; app-scoped, so a run goes on after its sheet closes. */
    val skillRunner by lazy {
        SkillRunner(scope, database.skillRuns(), database.skillArtifacts(), TranscriptFiles(java.io.File(filesDir, "skill_runs")), { if (it == COMMAND_SKILL_ID) COMMAND_SKILL else skills.getSkillById(it) }, ::skillAgent)
    }

    val aiReports by lazy { AiReports(this) }

    /** The user's MCP servers; their tools join the registry as `mcp_…`. Touched in [onCreate] so saved servers register at startup. */
    val mcp by lazy { McpServerStore(PrefsMcpStorage(this), tools::setDynamic) }

    /** Skill widgets and their default schedules. */
    val skillScheduling by lazy { SkillScheduling(scheduledTasks, PrefsDeclinedSchedules(this)) }

    /** The Second Brain; approved writes are recorded in the action log. */
    val secondBrain by lazy {
        SecondBrainRepository(
            scope,
            SecondBrainStore(java.io.File(filesDir, "secondbrain")),
            database.wikiPages(),
            audit = { action, detail ->
                actionLog.record(action, detail, ToolResult.Success("The user approved the entry"))
            },
        ).also { repo -> scope.launch(Dispatchers.IO) { repo.ensure() } }
    }

    private val stt by lazy {
        SttEngine(this, SpeechConfig.default.baseUrl, SpeechConfig.default.apiKey)
    }
    private val tts by lazy { TtsEngine(this) }

    /** Deletes a mini app with its home pins and console log; every Delete (screen, settings, tool) goes through here. */
    suspend fun deleteMiniApp(id: String) {
        miniApps.delete(id)
        layouts.unpinMiniApp(id)
        MiniAppConsole.clear(id)
    }

    /** Reads [text] aloud on the phone's own voice. Null when it played to the end (or the user stopped it), else the user-facing reason. */
    suspend fun speakReply(text: String): String? {
        if (tts.speak(text, language = Language.entries.firstOrNull { it.matchesCode(java.util.Locale.getDefault().language) } ?: Language.ENGLISH)) {
            return null
        }
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
            ),
        )
    }

    companion object {
        /** A scheduled task with no skill runs a plain command under this id. */
        const val COMMAND_SKILL_ID = "command"
        private val COMMAND_SKILL = MasalaSkill(id = COMMAND_SKILL_ID, title = "Command", instructions = "")
    }

    private object NoScreen : ActionDispatcher.Screen {
        override fun openDrawer(query: String?) = Unit
        override fun ask(prompt: String) = Unit
    }

    /** The agent for one skill run: the persona's and skill's instructions, the skill's tools, more steps. */
    private fun skillAgent(skill: MasalaSkill, unattended: Boolean) = agent(
        model = LlmAgentModel(LlmConfig.default.client(this)),
        // A run nobody watches can't ask, so it only gets tools that never need a confirmation.
        scope = { infos -> ToolScope.skill(skill, infos).let { if (unattended) ToolScope.unattended(it, infos) else it } },
        instructions = listOfNotNull(skill.persona?.systemPrompt, skill.instructions).joinToString("\n\n"),
        maxSteps = MasalaAgent.SKILL_MAX_STEPS,
    )

    /** The `run_skill` tool: opens the skill sheet on a prompt. */
    private fun openSkillSheet(skillId: String, prompt: String?): ActionResult {
        if (skills.getSkillById(skillId) == null) return ActionResult.error("There is no skill '$skillId'.")
        startActivity(SkillSheetActivity.intent(this, skillId, prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ActionResult.ok("Opened the $skillId skill for the user.")
    }

    /** Runs tools for one caller; [gate] confirms anything that isn't read-only. */
    fun toolExecutor(gate: ToolGate) = MasalaToolExecutor(this, tools, actionLog, gate)

    val modes by lazy { ModeStore(this) }

    /** The one gate for taps, gestures and widget buttons; the home screen shows its question. */
    val confirmationGate by lazy { ConfirmationGate(actionLog) }

    lateinit var catalog: AppCatalog
        private set
    lateinit var icons: IconCache
        private set
    lateinit var actions: AppActions
        private set
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
        actions = AppActions(this, catalog)
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
        )
        AgentTools.create(this, layouts, modes, runSkill = ::openSkillSheet).forEach(tools::register)
        MemoryTools.create(secondBrain).forEach(tools::register)
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
        // Icon mask, style and tint follow Appearance; the tint is the accent.
        scope.launch {
            settings.settings.collect { s ->
                val tint = com.samosaai.masala.ui.theme.accentColor(this@MasalaApp, s.accent).toArgb()
                icons.applyStyle(IconCache.Style(s.iconShape, s.iconStyle, tint))
            }
        }
    }
}

val Context.masala: MasalaApp get() = applicationContext as MasalaApp
