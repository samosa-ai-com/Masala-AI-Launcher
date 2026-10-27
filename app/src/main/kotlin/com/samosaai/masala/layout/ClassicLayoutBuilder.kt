package com.samosaai.masala.layout

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import com.samosaai.masala.core.apps.AppEntry
import com.samosaai.masala.core.contract.DockSection
import com.samosaai.masala.core.contract.GridSection
import com.samosaai.masala.core.contract.HeaderSection
import com.samosaai.masala.core.contract.IconRef
import com.samosaai.masala.core.contract.Item
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaAction
import com.samosaai.masala.core.contract.Page

/**
 * The layout a fresh install starts with (plan Phase 2): a clock header, a grid of favorites and a
 * dock with the phone's default dialer, messages and browser plus the drawer. The defaults come from
 * what the phone says handles each job (Fossify's `getDefaultAppPackages`), so it works on any phone
 * and needs no permission. Camera and the store go in the favorites with the apps pinned in Phase 1.
 */
object ClassicLayoutBuilder {
    const val PAGE = "home"
    const val FAVORITES = "favorites"
    const val DOCK = "dock"

    /** Only the drawer, for the first frame before anything else is known. */
    val minimal = LayoutDoc(
        pages = listOf(Page(PAGE, sections = listOf(HeaderSection("clock"), DockSection(DOCK, listOf(drawerItem())))))
    )

    fun drawerItem() = Item("drawer", "All apps", IconRef.Symbol("apps"), MasalaAction.OpenDrawer)

    /** [apps] are all launchable apps; [pinned] are the apps the user pinned before layouts existed. */
    fun build(context: Context, apps: List<AppEntry>, pinned: List<AppEntry>): LayoutDoc {
        val pm = context.packageManager
        val personal = apps.filter { it.key.user == 0L || it.profile == com.samosaai.masala.core.apps.ProfileKind.PERSONAL }
        fun defaultApp(intent: Intent): AppEntry? {
            val pkg = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                ?.takeUnless { it == "android" } ?: return null
            return personal.firstOrNull { it.key.packageName == pkg }
        }

        val phone = defaultApp(Intent(Intent.ACTION_DIAL))
        val messages = defaultApp(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        val browser = defaultApp(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        val camera = defaultApp(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val store = defaultApp(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.samosaai.masala")))

        val dock = listOfNotNull(
            phone?.let { appItem("dock-phone", it) },
            messages?.let { appItem("dock-messages", it) },
            browser?.let { appItem("dock-browser", it) },
        ) + drawerItem()

        val taken = mutableSetOf<String>()
        val favorites = (listOfNotNull(camera, store) + pinned).filter { taken.add(it.key.id) }.map { appItem(itemId(it), it) }

        return LayoutDoc(
            pages = listOf(
                Page(
                    PAGE,
                    sections = listOf(
                        HeaderSection("clock"),
                        GridSection(FAVORITES, columns = 4, items = favorites),
                        DockSection(DOCK, dock),
                    ),
                ),
            ),
        )
    }

    fun itemId(entry: AppEntry) = "app:${entry.key.id}"

    fun appItem(id: String, entry: AppEntry) = Item(id, entry.label, entry.key.icon, entry.key.action)
}
