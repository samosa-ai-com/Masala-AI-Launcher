package com.samosaai.masala.core.migration

import com.samosaai.masala.core.contract.LayoutDoc
import com.samosaai.masala.core.contract.MasalaJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Upgrades saved and imported layout JSON to [LayoutDoc.CURRENT_SCHEMA_VERSION] before decoding.
 * A contract change bumps the version and adds one step here (plan §6).
 */
object LayoutMigrations {

    /** `steps[n]` turns version n JSON into version n + 1. */
    private val steps: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // 1 to { json -> ... },
    )

    fun migrate(json: JsonObject): JsonObject {
        var current = json
        var version = current["schemaVersion"]?.jsonPrimitive?.int ?: 1
        require(version <= LayoutDoc.CURRENT_SCHEMA_VERSION) {
            "Layout schema $version is newer than this app (${LayoutDoc.CURRENT_SCHEMA_VERSION})"
        }
        while (version < LayoutDoc.CURRENT_SCHEMA_VERSION) {
            val step = steps[version] ?: error("No migration from layout schema $version")
            current = JsonObject(step(current) + ("schemaVersion" to JsonPrimitive(version + 1)))
            version++
        }
        return current
    }

    fun decode(json: JsonElement): LayoutDoc =
        MasalaJson.strict.decodeFromJsonElement(migrate(json as JsonObject))
}
