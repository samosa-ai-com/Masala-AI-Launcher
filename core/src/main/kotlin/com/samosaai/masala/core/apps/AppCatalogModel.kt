package com.samosaai.masala.core.apps

import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.MasalaAction

/**
 * One launcher activity in one profile. The same app in the personal and work profiles is two keys
 * (Unlauncher, Slim), so renames and visibility apply to one of them only. [user] is the profile's
 * user serial, as in [IconRef.AppIcon] and [MasalaAction.LaunchApp]; 0 is the main profile.
 */
data class AppKey(val packageName: String, val activity: String, val user: Long) {
    /** Stable text form, used as the Room key: `package/activity#user`. */
    val id: String get() = "$packageName/$activity#$user"

    val action get() = MasalaAction.LaunchApp(packageName, activity, user)
    val icon get() = IconRef.AppIcon(packageName, activity, user)

    companion object {
        fun parse(id: String): AppKey {
            val component = id.substringBeforeLast('#')
            require('/' in component && '#' in id) { "Not an app key: $id" }
            return AppKey(component.substringBefore('/'), component.substringAfter('/'), id.substringAfterLast('#').toLong())
        }
    }
}

/** Three levels: shown everywhere, only in search results, or nowhere. */
enum class Visibility { DEFAULT, SEARCH_ONLY, HIDDEN }

enum class ProfileKind { PERSONAL, WORK }

/** What the user changed about one app. Missing fields mean "as the app says". */
data class AppOverride(
    val key: AppKey,
    val label: String? = null,
    val visibility: Visibility = Visibility.DEFAULT,
    val pinned: Boolean = false,
)

/** An activity as `LauncherApps` reports it, before overrides. */
data class InstalledActivity(val key: AppKey, val label: String, val profile: ProfileKind)

data class AppEntry(
    val key: AppKey,
    /** The label to show: the user's rename, or the app's own label. */
    val label: String,
    val originalLabel: String,
    val profile: ProfileKind,
    val visibility: Visibility,
    val pinned: Boolean,
) {
    val renamed get() = label != originalLabel
}

object AppCatalogMerge {
    /**
     * Applies [overrides] to [installed] and sorts by label with [order] (a locale collator on the
     * phone), personal profile first. Overrides for apps that are no longer installed are ignored;
     * the caller prunes them.
     */
    fun merge(
        installed: List<InstalledActivity>,
        overrides: Map<AppKey, AppOverride>,
        order: Comparator<String> = String.CASE_INSENSITIVE_ORDER,
    ): List<AppEntry> = installed
        .map { app ->
            val o = overrides[app.key]
            AppEntry(
                key = app.key,
                label = o?.label?.takeIf { it.isNotBlank() } ?: app.label,
                originalLabel = app.label,
                profile = app.profile,
                visibility = o?.visibility ?: Visibility.DEFAULT,
                pinned = o?.pinned ?: false,
            )
        }
        .sortedWith(compareBy<AppEntry> { it.profile }.thenBy(order) { it.label }.thenBy { it.key.id })
}
