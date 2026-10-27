package com.samosaai.masala.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * the private prototype's `Skill` plus Masala's optional fields (arch §3.9). the private prototype reads skills with
 * `ignoreUnknownKeys = true`, so the extra fields don't break it. Decode with [MasalaJson.lenient].
 */
@Serializable
data class MasalaSkill(
    // the private prototype's fields
    val id: String,
    val title: String = "",
    val description: String = "",
    val instructions: String,
    val requiresTools: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val targetPackageNames: List<String> = emptyList(),
    // Masala's fields
    /** `prototype`, `masala`. Empty means unknown: hub skills from before the field existed. */
    val platforms: List<String> = emptyList(),
    val tier: SkillTier = SkillTier.FREE,
    val persona: Persona? = null,
    val widget: SkillWidget? = null,
    val schedule: SkillSchedule? = null,
)

@Serializable
enum class SkillTier {
    @SerialName("free") FREE,
    @SerialName("pro") PRO,
}

@Serializable
data class Persona(
    val systemPrompt: String,
    val starters: List<String> = emptyList(),
    val disclaimer: String? = null,
)

@Serializable
data class SkillWidget(
    val icon: String,
    val tint: String? = null,
    val inputs: List<SkillInput> = emptyList(),
    val output: SkillOutput = SkillOutput.MARKDOWN,
)

@Serializable
enum class SkillInput {
    @SerialName("text") TEXT,
    @SerialName("photo") PHOTO,
    @SerialName("video") VIDEO,
    @SerialName("audio_file") AUDIO_FILE,
    @SerialName("document") DOCUMENT,
    @SerialName("folder") FOLDER,
    @SerialName("path") PATH,
    @SerialName("record_audio") RECORD_AUDIO,
}

@Serializable
enum class SkillOutput {
    @SerialName("markdown") MARKDOWN,
    @SerialName("file") FILE,
    @SerialName("audio") AUDIO,
    @SerialName("video") VIDEO,
    @SerialName("mini_app") MINI_APP,
    @SerialName("installed_app") INSTALLED_APP,
}

/** `default` is a schedule string such as `"daily 07:30"`; the user can change it from the widget. */
@Serializable
data class SkillSchedule(val default: String)
