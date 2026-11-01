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

    /**
     * Replaces the current version's layout in place (repair only: an undo that restored a deleted
     * widget's placement): no new version, redo intact. Returns the fixed version, or null when
     * nothing changed.
     */
    fun repairCurrent(layout: LayoutDoc): Version? {
        val cur = current ?: return null
        if (cur.layout == layout) return null
        val fixed = cur.copy(layout = layout)
        list[index] = fixed
        return fixed
    }

    /** Steps back; returns the version now in use, or null at the oldest. */
    fun undo(): Version? = if (canUndo) list[--index] else null

    fun redo(): Version? = if (canRedo) list[++index] else null

    /** Jumps to [id], for the version list. */
    fun select(id: Long): Version? = list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.also { index = it }?.let { list[it] }

    /** Renames one version; returns it, or null when [id] is unknown. Blank labels are rejected upstream. */
    fun rename(id: Long, label: String): Version? {
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val renamed = list[i].copy(label = label)
        list[i] = renamed
        return renamed
    }

    /**
     * Deletes one version and moves current to the predecessor (else the successor).
     * Returns the version now in use, or null when [id] is unknown or it is the last row.
     */
    fun delete(id: Long): Version? {
        if (list.size <= 1) return null
        val i = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        list.removeAt(i)
        index = when {
            i < index -> index - 1
            i == index -> (i - 1).coerceAtLeast(0).coerceAtMost(list.lastIndex)
            else -> index
        }
        return list.getOrNull(index)
    }

    companion object {
        const val MAX_VERSIONS = 50
    }
}
