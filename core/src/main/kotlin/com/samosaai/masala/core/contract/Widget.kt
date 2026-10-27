package com.samosaai.masala.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A generated widget (arch §3.7): data, never code. The renderer draws [components] top to bottom.
 */
@Serializable
data class WidgetSpec(
    val id: String,
    val title: String? = null,
    val components: List<WidgetComponent>,
    val size: SizeRange = SizeRange(),
    val rules: List<Rule> = emptyList(),
)

/** Height in rows. */
@Serializable
data class SizeRange(val min: Int = 1, val default: Int = 2, val max: Int = 4)

/**
 * Where a component's value comes from. `storage` reads and writes the widget's own storage under
 * [key]; the others read the context snapshot or a system source.
 */
@Serializable
data class Binding(val source: BindingSource, val key: String? = null)

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
}

@Serializable
sealed interface WidgetComponent {
    /** Fixed [text], or the value of [binding] if set. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String? = null, val binding: Binding? = null) : WidgetComponent

    @Serializable
    @SerialName("counter")
    data class Counter(val key: String, val label: String, val step: Int = 1, val goal: Int? = null) :
        WidgetComponent

    @Serializable
    @SerialName("checklist")
    data class Checklist(val key: String, val items: List<String>) : WidgetComponent

    @Serializable
    @SerialName("timer")
    data class Timer(val key: String, val label: String, val seconds: Int) : WidgetComponent

    @Serializable
    @SerialName("progress")
    data class Progress(val label: String, val binding: Binding, val max: Double) : WidgetComponent

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
}

@Serializable
enum class ChartKind {
    @SerialName("bar") BAR,
    @SerialName("line") LINE,
}
