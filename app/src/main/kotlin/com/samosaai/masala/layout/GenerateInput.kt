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
fun MasalaApp.generateRequest(
    userText: String,
    imageBase64: String? = null,
    imageFormat: String = "png",
    temperature: Float? = null,
): LayoutGenerator.Request = buildGenerateRequest(
    apps = launcher.apps.value,
    layout = layouts.base.value,
    snapshot = contextService.snapshot.value,
    skills = skills.getAllSkills(),
    userText = userText,
    imageBase64 = imageBase64,
    imageFormat = imageFormat,
    temperature = temperature,
)

fun buildGenerateRequest(
    apps: List<AppEntry>,
    layout: LayoutDoc?,
    snapshot: ContextSnapshot,
    skills: List<MasalaSkill>,
    userText: String,
    imageBase64: String? = null,
    imageFormat: String = "png",
    temperature: Float? = null,
): LayoutGenerator.Request = LayoutGenerator.Request(
    userText = userText,
    context = PromptContextInput(
        apps = apps,
        layout = layout,
        calendar = CalendarCards.summary(snapshot),
        skills = skills.map { PromptSkill(it.id, it.title.ifBlank { it.id }) },
    ),
    imageBase64 = imageBase64,
    imageFormat = imageFormat,
    temperature = temperature,
)
