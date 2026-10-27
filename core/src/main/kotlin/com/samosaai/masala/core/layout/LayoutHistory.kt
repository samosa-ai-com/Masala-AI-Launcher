package com.samosaai.masala.core.layout

import com.samosaai.masala.core.contract.LayoutDoc

/**
 * The saved versions of the home layout, with undo and redo (arch §3.2: "so every AI change can be
 * undone"). At most [limit] versions are kept, oldest dropped first. Pure and in memory; the app saves
 * each change to Room. A change made after an undo drops the versions that were undone.
 */
class LayoutHistory(
    versions: List<Version> = emptyList(),
    current: Long? = null,
    private val limit: Int = MAX_VERSIONS,
) {
    data class Version(val id: Long, val label: String, val layout: LayoutDoc, val createdAt: Long)

    private val list = versions.sortedBy { it.id }.toMutableList()
    private var nextId = (list.lastOrNull()?.id ?: 0L) + 1
    private var index = (current?.let { id -> list.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }) ?: list.lastIndex

    /** The version in use, or null for a history with nothing in it yet. */
    val current: Version? get() = list.getOrNull(index)

    /** Oldest first. */
    val versions: List<Version> get() = list.toList()

    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index in 0 until list.lastIndex

    /** The result of a change: what to save and what to delete. */
    data class Change(val added: Version?, val removedIds: List<Long>)

    fun push(label: String, layout: LayoutDoc, now: Long): Change {
        val removed = mutableListOf<Long>()
        while (list.lastIndex > index) removed += list.removeAt(list.lastIndex).id
        val version = Version(nextId++, label, layout, now)
        list += version
        while (list.size > limit) removed += list.removeAt(0).id
        index = list.lastIndex
        return Change(version, removed)
    }

    /** Steps back; returns the version now in use, or null at the oldest. */
    fun undo(): Version? = if (canUndo) list[--index] else null

    fun redo(): Version? = if (canRedo) list[++index] else null

    /** Jumps to [id], for the version list. */
    fun select(id: Long): Version? = list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.also { index = it }?.let { list[it] }

    companion object {
        const val MAX_VERSIONS = 50
    }
}
