package com.samosaai.masala.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A generated widget (arch §3.7): data, never code. The renderer draws [components] top to bottom.
 *
 * Since layout schema 3 a widget lives in the widget library and a layout points at it with a
 * [WidgetRef]; an inline spec is still read for one schema so older exports import (plan
 * `docs/plans/widget-maker.md` §6.1).
 */
@Serializable
data class WidgetSpec(
    val id: String,
    val title: String? = null,
    val components: List<WidgetComponent>,
    val size: SizeRange = SizeRange(),
    val rules: List<Rule> = emptyList(),
)

/** Height in rows; width in grid cells ([defaultW] full width unless the widget says otherwise). */
@Serializable
data class SizeRange(
    val min: Int = 1,
    val default: Int = 2,
    val max: Int = 4,
    val minW: Int = 1,
    val defaultW: Int = 4,
    val maxW: Int = 4,
)

/**
 * A layout's pointer at a library widget. [rowsOverride] replaces the widget's default height on
 * this placement (kept inside the widget's own range); a rule's size effect still wins.
 */
@Serializable
data class WidgetRef(val widgetId: String, val rowsOverride: Int? = null)

/**
 * Where a component's value comes from. `storage` reads and writes the widget's own storage under
 * [key]; `stream_status` reads the stream source [key]; `skill_result` reads the last run of the
 * skill [key]; `http` reads [http]; the others read the context snapshot or a system source.
 */
@Serializable
data class Binding(val source: BindingSource, val key: String? = null, val http: HttpBinding? = null)

@Serializable
enum class BindingSource {
    @SerialName("storage") STORAGE,
    @SerialName("next_event") NEXT_EVENT,
    @SerialName("battery") BATTERY,
    @SerialName("media") MEDIA,
    @SerialName("sleep") SLEEP,
    @SerialName("steps") STEPS,
    @SerialName("weather") WEATHER,
    @SerialName("urgent_count") URGENT_COUNT,
    @SerialName("http") HTTP,
    @SerialName("stream_status") STREAM_STATUS,
    @SerialName("skill_result") SKILL_RESULT,
}

/**
 * A value fetched from an HTTP JSON endpoint. [jsonPath] is the `$.a.b[0].c` subset
 * ([com.samosaai.masala.core.widgets.JsonPick]). [every] is in seconds while the widget is visible;
 * [background] also refreshes it from a periodic job (at most every 15 minutes). The host is approved
 * per widget when the widget is added.
 */
@Serializable
data class HttpBinding(
    val url: String,
    val jsonPath: String = "$",
    val every: Int = 900,
    val background: Boolean = false,
)

/** A live stream source (`stream_sources`) by id; never a URL, never credentials. */
@Serializable
data class StreamRef(val sourceId: String, val mode: StreamMode = StreamMode.LIVE)

@Serializable
enum class StreamMode {
    /** Plays the stream (muted on home); falls back to snapshots when no player is free. */
    @SerialName("live") LIVE,

    /** Refreshes a still image; cheapest, and what a third live tile falls back to. */
    @SerialName("snapshot") SNAPSHOT,
}

/** Bounded styling: a palette tint, a size emphasis and an alignment. No free colors or fonts. */
@Serializable
data class WidgetStyle(
    val tint: String? = null,
    val emphasis: Emphasis = Emphasis.NORMAL,
    val align: Align = Align.START,
)

@Serializable
enum class Emphasis {
    @SerialName("normal") NORMAL,
    @SerialName("large") LARGE,
}

@Serializable
enum class Align {
    @SerialName("start") START,
    @SerialName("center") CENTER,
    @SerialName("end") END,
}

@Serializable
sealed interface WidgetComponent {
    /** Fixed [text], or the value of [binding] if set. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String? = null, val binding: Binding? = null, val style: WidgetStyle? = null) : WidgetComponent

    @Serializable
    @SerialName("counter")
    data class Counter(
        val key: String,
        val label: String,
        val step: Int = 1,
        val goal: Int? = null,
        val style: WidgetStyle? = null,
    ) : WidgetComponent

    @Serializable
    @SerialName("checklist")
    data class Checklist(val key: String, val items: List<String>) : WidgetComponent

    @Serializable
    @SerialName("timer")
    data class Timer(val key: String, val label: String, val seconds: Int) : WidgetComponent

    @Serializable
    @SerialName("progress")
    data class Progress(val label: String, val binding: Binding, val max: Double, val style: WidgetStyle? = null) :
        WidgetComponent

    @Serializable
    @SerialName("chart")
    data class Chart(val binding: Binding, val kind: ChartKind = ChartKind.BAR, val days: Int = 7) :
        WidgetComponent

    @Serializable
    @SerialName("button")
    data class Button(val label: String, val action: MasalaAction) : WidgetComponent

    @Serializable
    @SerialName("image")
    data class Image(val icon: IconRef) : WidgetComponent

    /** Up to [maxRows] rows from [binding] (next events, a watch list); [rowAction] runs on a tap. */
    @Serializable
    @SerialName("list")
    data class ListRows(
        val binding: Binding,
        val maxRows: Int = 3,
        val rowAction: MasalaAction? = null,
        val style: WidgetStyle? = null,
    ) : WidgetComponent

    /** A ring for a numeric [binding] against [max] (battery, steps against a goal). */
    @Serializable
    @SerialName("gauge")
    data class Gauge(val label: String, val binding: Binding, val max: Double, val style: WidgetStyle? = null) :
        WidgetComponent

    /** Side by side. Rows and columns nest at most two deep. */
    @Serializable
    @SerialName("row")
    data class Row(val children: List<WidgetComponent>) : WidgetComponent

    /** Stacked; mostly useful inside a [Row]. */
    @Serializable
    @SerialName("column")
    data class Column(val children: List<WidgetComponent>) : WidgetComponent

    /** A live stream or clip from a stream source. At most one per widget. */
    @Serializable
    @SerialName("video")
    data class Video(val stream: StreamRef) : WidgetComponent

    /** A still image from a stream source, refreshed every [everySeconds] while visible. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val sourceId: String, val everySeconds: Int = 10) : WidgetComponent

    /** A labelled value pulled from an HTTP JSON endpoint; [suffix] is shown after it ("€", "°C"). */
    @Serializable
    @SerialName("web_value")
    data class WebValue(
        val label: String,
        val http: HttpBinding,
        val suffix: String? = null,
        val style: WidgetStyle? = null,
    ) : WidgetComponent
}

@Serializable
enum class ChartKind {
    @SerialName("bar") BAR,
    @SerialName("line") LINE,
}
