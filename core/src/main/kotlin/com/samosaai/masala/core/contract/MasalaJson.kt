package com.samosaai.masala.core.contract

import kotlinx.serialization.json.Json

/** JSON settings shared by every contract. Sealed types are tagged with a `"type"` field. */
object MasalaJson {
    /**
     * Strict: unknown keys are errors. Use for layouts, widgets, modes and anything a model writes,
     * so a misspelled field is caught by the checker instead of silently dropped.
     */
    val strict: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Lenient: unknown keys are ignored. Use for skills, which the private prototype and the Skill Hub also write. */
    val lenient: Json = Json(strict) {
        ignoreUnknownKeys = true
    }
}
