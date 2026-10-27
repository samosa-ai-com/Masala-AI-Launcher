package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.ItemSection
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.ModePatch
import com.samosaai.masala.core.contract.Page
import com.samosaai.masala.core.contract.Section

/**
 * Applies active modes to the base layout (arch §3.2). Patches run in a fixed order ([ModePatch.order],
 * then id); within a patch, adds run before hides. IDs never change, so a later patch can hide what an
 * earlier one added. Anything that can't be applied is skipped and reported, never thrown.
 */
object OverlayMerge {

    data class Result(val layout: LayoutDoc, val conflicts: List<Conflict>)

    data class Conflict(val modeId: String, val message: String)

    fun merge(base: LayoutDoc, patches: List<ModePatch>): Result {
        val conflicts = mutableListOf<Conflict>()
        var pages = base.pages
        for (patch in patches.sortedWith(compareBy({ it.order }, { it.id }))) {
            pages = apply(pages, patch) { conflicts += Conflict(patch.id, it) }
        }
        return Result(base.copy(pages = pages), conflicts)
    }

    private fun apply(pages: List<Page>, patch: ModePatch, conflict: (String) -> Unit): List<Page> {
        var result = pages

        for (add in patch.addSections) {
            if (result.none { it.id == add.pageId }) {
                conflict("No page ${add.pageId} for section ${add.section.id}")
                continue
            }
            if (result.allIds().contains(add.section.id)) {
                conflict("ID ${add.section.id} is already used")
                continue
            }
            result = result.map { page ->
                if (page.id != add.pageId) page else page.copy(sections = page.sections.insertAfter(add.section, add.after) { it.id })
            }
        }

        for (add in patch.addItems) {
            val target = result.flatMap { it.sections }.firstOrNull { it.id == add.sectionId }
            when {
                target == null -> conflict("No section ${add.sectionId} for item ${add.item.id}")
                target !is ItemSection -> conflict("Section ${add.sectionId} can't hold items")
                result.allIds().contains(add.item.id) -> conflict("ID ${add.item.id} is already used")
                else -> result = result.mapSections { section ->
                    if (section.id != add.sectionId || section !is ItemSection) section
                    else section.withItems(section.items.insertAfter(add.item, add.after) { it.id })
                }
            }
        }

        val hiddenSections = patch.hideSections.toSet()
        val hiddenItems = patch.hideItems.toSet()
        if (hiddenSections.isNotEmpty() || hiddenItems.isNotEmpty()) {
            result = result.map { page ->
                page.copy(
                    sections = page.sections
                        .filter { it.id !in hiddenSections }
                        .map { section ->
                            if (section is ItemSection) section.withItems(section.items.filter { it.id !in hiddenItems })
                            else section
                        },
                )
            }
        }
        return result
    }

    private fun List<Page>.mapSections(transform: (Section) -> Section) =
        map { page -> page.copy(sections = page.sections.map(transform)) }

    private fun <T> List<T>.insertAfter(element: T, after: String?, id: (T) -> String): List<T> {
        val index = after?.let { a -> indexOfFirst { id(it) == a } } ?: -1
        return if (index < 0) this + element else toMutableList().apply { add(index + 1, element) }
    }
}

/** Every page, section and item ID in the layout. */
fun List<Page>.allIds(): Set<String> = buildSet {
    for (page in this@allIds) {
        add(page.id)
        for (section in page.sections) {
            add(section.id)
            if (section is ItemSection) section.items.forEach { add(it.id) }
        }
    }
}
