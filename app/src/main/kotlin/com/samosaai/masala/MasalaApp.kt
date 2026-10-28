package com.samosaai.masala

import com.samosaai.masala.core.tool.ToolFiles
import android.app.Application
import android.content.Context
import com.samosaai.masala.actions.ActionDispatcher
import com.samosaai.masala.actions.ConfirmationGate
import com.samosaai.masala.agent.AgentModel
import com.samosaai.masala.agent.AgentPrompt
import com.samosaai.masala.agent.AgentTools
import com.samosaai.masala.agent.MasalaAgent
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
import com.samosaai.masala.core.scheduled.ScheduledTaskStore
import com.samosaai.masala.pro.ProTools
import com.samosaai.masala.scheduled.PrefsTaskStorage
import com.samosaai.masala.scheduled.ScheduledTaskScheduler
import com.samosaai.masala.scheduled.TaskRunner
import com.samosaai.masala.skills.SkillRegistry
import com.samosaai.masala.tools.ActionLog
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
    val settings by lazy { SettingsStore(this) }
    val actionLog by lazy { ActionLog(this) }

    /** Bundled personas and skills, then the ones the user imported. */
    val skills by lazy { SkillRegistry(this) }

    val scheduledTasks by lazy { ScheduledTaskStore(PrefsTaskStorage(this)) }

    /** Starts a scheduled task's run. Null until Phase 4's skill runner sets it. */
    @Volatile var taskRunner: TaskRunner? = null

    /** `:app`'s tools, then `:pro`'s, which also swaps in all-files access (arch §3.1). */
    val tools by lazy {
        MasalaToolRegistry(AppStorageFileAccess(this)).also { registry ->
            MasalaTools.create(this, ToolFiles { registry.fileAccess }).forEach(registry::register)
            ProTools.register(registry, this)
        }
    }

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
        val infos = tools.tools.map { ToolInfo(it.name, it.sensitivity, it.readsPrivateData) }
        return MasalaAgent(
            model = model,
            tools = { name, args -> executor.execute(name, args) },
            definitions = tools.tools.map { it.definition },
            scope = scope(infos),
            systemPrompt = {
                val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date())
                AgentPrompt.build("$now (${java.util.TimeZone.getDefault().id})", skills.getAllSkills(), instructions = instructions)
            },
            maxSteps = maxSteps,
        )
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
        AgentTools.create(this, layouts, modes).forEach(tools::register)
        dispatcher = ActionDispatcher(this, actions, launcher, modes, confirmationGate, tools) { toolExecutor(confirmationGate) }

        // A force-stop drops the alarm; the store still has the tasks.
        scope.launch(Dispatchers.IO) { ScheduledTaskScheduler.reschedule(this@MasalaApp) }

        scope.launch {
            catalog.removals.collect { database.appOverrides().deletePackage(it.packageName, it.user) }
        }
        // Updated apps may have new icons.
        scope.launch { catalog.state.drop(1).collect { icons.clear() } }
    }
}

val Context.masala: MasalaApp get() = applicationContext as MasalaApp
