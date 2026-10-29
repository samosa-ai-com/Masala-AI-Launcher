package com.samosaai.masala.layout

import com.samosaai.masala.MasalaApp
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.contract.ContextSnapshot
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaSkill

/**
 * Assembles a generator request from live app state (Lane C wiring). The mapping itself is pure
 * ([buildGenerateRequest]) so tests cover it without an [MasalaApp]; the wiki slot stays empty
 * until Phase 4.
 */
suspend fun MasalaApp.generateRequest(
    userText: String,
    imageBase64: String? = null,
    imageFormat: String = "png",
    temperature: Float? = null,
    revise: LayoutDoc? = null,
): LayoutGenerator.Request = buildGenerateRequest(
    apps = launcher.apps.value,
    layout = layouts.base.value,
    snapshot = contextService.snapshot.value,
    skills = skills.getAllSkills(),
    miniApps = miniApps.list().map { PromptMiniApp(it.id, it.title) },
    userText = userText,
    imageBase64 = imageBase64,
    imageFormat = imageFormat,
    temperature = temperature,
    revise = revise,
)

fun buildGenerateRequest(
    apps: List<AppEntry>,
    layout: LayoutDoc?,
    snapshot: ContextSnapshot,
    skills: List<MasalaSkill>,
    miniApps: List<PromptMiniApp> = emptyList(),
    userText: String,
    imageBase64: String? = null,
    imageFormat: String = "png",
    temperature: Float? = null,
    revise: LayoutDoc? = null,
): LayoutGenerator.Request = LayoutGenerator.Request(
    userText = userText,
    context = PromptContextInput(
        apps = apps,
        layout = layout,
        calendar = CalendarCards.summary(snapshot),
        skills = skills.map { PromptSkill(it.id, it.title.ifBlank { it.id }) },
        miniApps = miniApps,
    ),
    imageBase64 = imageBase64,
    imageFormat = imageFormat,
    temperature = temperature,
    revise = revise,
)
