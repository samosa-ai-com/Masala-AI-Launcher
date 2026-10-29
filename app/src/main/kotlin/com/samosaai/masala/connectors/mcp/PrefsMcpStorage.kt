package com.samosaai.masala.connectors.mcp

import android.content.Context

/** The server list in app-private preferences (`mcp_servers.xml`, excluded from backups: it holds tokens). */
class PrefsMcpStorage(context: Context) : McpStorage {
    private val prefs = context.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(json: String?) {
        prefs.edit().apply { if (json == null) remove(KEY) else putString(KEY, json) }.apply()
    }

    private companion object {
        const val KEY = "servers"
    }
}
