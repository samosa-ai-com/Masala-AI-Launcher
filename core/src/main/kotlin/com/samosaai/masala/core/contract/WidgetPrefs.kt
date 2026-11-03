package com.samosaai.masala.core.contract

import kotlinx.serialization.Serializable

/**
 * A single widget's own settings (plan `docs/plans/widgets-functional-pass.md` §2.1). Templates are
 * shared library rows, so a goal, a duration or a place belongs to the *placement*, not the
 * template: this is stored in the widget's state map and applied to a copy of the spec before it is
 * drawn ([WidgetSpec.resolved]). Nothing here changes the widget's id, keys or actions, so the
 * state a card already holds survives every edit.
 */
@Serializable
data class WidgetPrefs(
    /** A title just for this widget; the template's title is used otherwise. */
    val title: String? = null,
    /**
     * Overrides by component path: `counter:<key>`, `timer:<key>`, `checklist:<key>`, or
     * `index:<n>` for components without a key. Keyed paths survive reordering and nesting.
     */
    val components: Map<String, ComponentPrefs> = emptyMap(),
)

/**
 * What a form can change on one component. Every field is optional: null keeps the spec's value, so
 * a template update still reaches a widget only for the settings the user did not override.
 */
@Serializable
data class ComponentPrefs(
    val label: String? = null,
    val goal: Int? = null,
    val step: Int? = null,
    val max: Double? = null,
    val seconds: Int? = null,
    val sound: Boolean? = null,
    /**
     * When a checklist or a counter starts over: `daily`, `weekly`, `monthly`; null/`never` keeps
     * the value until the user resets it.
     */
    val reset: String? = null,
    /** A checklist's own items, overriding the spec's list. */
    val items: List<String>? = null,
    /** The target date of a countdown counter, ISO `yyyy-MM-dd`. */
    val date: String? = null,
    /** `monthly`: a countdown advances to the next month after its date passes. */
    val repeat: String? = null,
    val httpUrl: String? = null,
    val jsonPath: String? = null,
    val every: Int? = null,
    val suffix: String? = null,
    /** A stream source for a camera tile; null keeps the spec's id. */
    val sourceId: String? = null,
    /** The timer's next phase in this widget (a Pomodoro's break); null stops at the end. */
    val autoStartKey: String? = null,
)

/** [this] widget with the user's overrides applied; components keep their order and count. */
fun WidgetSpec.resolved(prefs: WidgetPrefs): WidgetSpec {
    var index = 0
    fun apply(component: WidgetComponent): WidgetComponent {
        val path = prefsKey(component, index++)
        val base = prefs.components[path]?.let(component::withPrefs) ?: component
        return when (base) {
            is WidgetComponent.Row -> base.copy(children = base.children.map(::apply))
            is WidgetComponent.Column -> base.copy(children = base.children.map(::apply))
            else -> base
        }
    }
    return copy(title = prefs.title ?: title, components = components.map(::apply))
}

/** Every timer in the widget, nested rows and columns included (a Pomodoro puts its pair in a row). */
fun WidgetSpec.timerComponents(): List<WidgetComponent.Timer> =
    componentsWithPaths().mapNotNull { it.second as? WidgetComponent.Timer }

/** Every component in draw order with the path its settings live under. */
fun WidgetSpec.componentsWithPaths(): List<Pair<String, WidgetComponent>> {
    val out = mutableListOf<Pair<String, WidgetComponent>>()
    var index = 0
    fun walk(component: WidgetComponent) {
        out += prefsKey(component, index++) to component
        when (component) {
            is WidgetComponent.Row -> component.children.forEach(::walk)
            is WidgetComponent.Column -> component.children.forEach(::walk)
            else -> Unit
        }
    }
    components.forEach(::walk)
    return out
}

/** The path a component's settings are stored under (see [WidgetPrefs.components]). */
fun prefsKey(component: WidgetComponent, index: Int): String = when (component) {
    is WidgetComponent.Counter -> "counter:${component.key}"
    is WidgetComponent.Timer -> "timer:${component.key}"
    is WidgetComponent.Checklist -> "checklist:${component.key}"
    else -> "index:$index"
}

private fun WidgetComponent.withPrefs(p: ComponentPrefs): WidgetComponent = when (this) {
    is WidgetComponent.Counter -> copy(
        label = p.label ?: label,
        goal = p.goal ?: goal,
        step = p.step ?: step,
        date = p.date ?: date,
        reset = p.reset ?: reset,
        repeat = p.repeat ?: repeat,
    )
    is WidgetComponent.Checklist -> copy(items = p.items ?: items, reset = p.reset ?: reset)
    is WidgetComponent.Timer -> copy(
        label = p.label ?: label,
        seconds = p.seconds ?: seconds,
        sound = p.sound ?: sound,
        autoStartKey = p.autoStartKey ?: autoStartKey,
    )
    is WidgetComponent.Progress -> copy(label = p.label ?: label, max = p.max ?: max, reset = p.reset ?: reset)
    is WidgetComponent.Gauge -> copy(label = p.label ?: label, max = p.max ?: max)
    is WidgetComponent.Globe -> copy(label = p.label ?: label)
    is WidgetComponent.WebValue -> copy(
        label = p.label ?: label,
        suffix = p.suffix ?: suffix,
        http = http.copy(
            url = p.httpUrl ?: http.url,
            jsonPath = p.jsonPath ?: http.jsonPath,
            every = p.every ?: http.every,
        ),
    )
    is WidgetComponent.Snapshot -> copy(sourceId = p.sourceId ?: sourceId)
    is WidgetComponent.Video -> copy(stream = stream.copy(sourceId = p.sourceId ?: stream.sourceId))
    else -> this
}
