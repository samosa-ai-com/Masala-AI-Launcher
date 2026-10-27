package com.samosaai.masala.layout

import com.samosaai.masala.core.apps.AppKey
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.layout.LayoutBundle
import com.samosaai.masala.core.layout.LayoutBundle.AppOverrideExport
import com.samosaai.masala.core.layout.LayoutChecker
import com.samosaai.masala.data.AppOverrideDao
import com.samosaai.masala.data.AppOverrideEntity
import kotlinx.coroutines.flow.first

/**
 * Export and import of the home layout with the user's app overrides (plan Phase 2). Import runs the
 * layout checker first and changes nothing if it finds anything: a layout from another phone names apps
 * this one doesn't have, and those come back as problems with their JSON paths.
 */
class LayoutTransfer(private val layouts: LayoutRepository, private val overrides: AppOverrideDao) {

    sealed interface Result {
        data object Imported : Result
        data class Rejected(val problems: List<LayoutChecker.Problem>) : Result
    }

    /** The JSON for the file: the current base layout and every rename and visibility the user set. */
    suspend fun export(): String? {
        val layout: LayoutDoc = layouts.base.value ?: return null
        val rows = overrides.all().first().filter { it.label != null || it.visibility != com.samosaai.masala.core.apps.Visibility.DEFAULT }
        return LayoutBundle.of(layout, rows.map { AppOverrideExport(it.key, it.label, it.visibility) }).encode()
    }

    suspend fun import(text: String): Result {
        val imported = LayoutBundle.import(text, layouts.environment())
        val layout = imported.layout
        if (layout == null || imported.problems.isNotEmpty()) return Result.Rejected(imported.problems)
        val problems = layouts.apply("Imported layout", layout)
        if (problems.isNotEmpty()) return Result.Rejected(problems)
        for (o in imported.appOverrides) {
            val key = runCatching { AppKey.parse(o.key) }.getOrNull() ?: continue
            val next = (overrides.get(o.key) ?: AppOverrideEntity.empty(key)).copy(label = o.label, visibility = o.visibility)
            if (next.isEmpty) overrides.delete(o.key) else overrides.upsert(next)
        }
        return Result.Imported
    }
}
