package com.samosaai.masala.core.layout

import com.samosaai.masala.core.apps.Visibility
import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The file "Export layout" writes and "Import layout" reads: the home layout and the user's app
 * overrides (renames, visibility), so a setup can move to another phone. [layout] is kept as JSON so
 * an older layout schema is migrated and checked on the way in, like any other layout.
 */
@Serializable
data class LayoutBundle(
    val bundleVersion: Int = 1,
    val layout: JsonObject,
    val appOverrides: List<AppOverrideExport> = emptyList(),
) {
    @Serializable
    data class AppOverrideExport(
        /** `AppKey.id`: `package/activity#user`. */
        val key: String,
        val label: String? = null,
        val visibility: Visibility = Visibility.DEFAULT,
        /** A chosen icon (`IconSpec`); absent in bundles written before icons existed. */
        val icon: String? = null,
    )

    fun encode(): String = MasalaJson.strict.encodeToString(serializer(), this)

    companion object {
        fun of(layout: LayoutDoc, overrides: List<AppOverrideExport>) = LayoutBundle(
            layout = MasalaJson.strict.encodeToJsonElement(LayoutDoc.serializer(), layout).jsonObject,
            appOverrides = overrides,
        )

        data class Imported(val layout: LayoutDoc?, val appOverrides: List<AppOverrideExport>, val problems: List<LayoutChecker.Problem>)

        /**
         * Reads an exported bundle, or a bare layout file, and checks the layout against [env]. A bundle
         * from another phone names apps this one doesn't have, so those show up as problems, never
         * silently dropped.
         */
        fun import(text: String, env: LayoutChecker.Environment): Imported {
            val bundle = runCatching { MasalaJson.lenient.decodeFromString(serializer(), text) }.getOrNull()
            val layoutText = bundle?.let { MasalaJson.strict.encodeToString(JsonObject.serializer(), it.layout) } ?: text
            val parsed = LayoutChecker.parse(layoutText)
            val layout = parsed.layout ?: return Imported(null, emptyList(), parsed.problems)
            return Imported(layout, bundle?.appOverrides.orEmpty(), LayoutChecker.check(layout, env))
        }
    }
}
