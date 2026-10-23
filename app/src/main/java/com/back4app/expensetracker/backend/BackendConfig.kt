package com.back4app.expensetracker.backend

import android.content.Context
import com.back4app.expensetracker.BuildConfig
import java.util.Properties

/**
 * Where the two public keys come from, in order:
 *  1. BuildConfig (filled from local.properties: parse.appId / parse.clientKey)
 *  2. assets/backend.properties (copied from backend.properties.example)
 * Only the Application ID and the Client key are ever used; the master key never leaves the server side.
 */
data class BackendConfig(val appId: String, val clientKey: String, val serverUrl: String) {
    companion object {
        const val DEFAULT_SERVER_URL = "https://parseapi.back4app.com/"

        fun load(context: Context): BackendConfig? {
            fromBuildConfig()?.let { return it }
            return fromAssets(context)
        }

        private fun fromBuildConfig(): BackendConfig? {
            val id = BuildConfig.PARSE_APP_ID.trim()
            val key = BuildConfig.PARSE_CLIENT_KEY.trim()
            if (!valid(id) || !valid(key)) return null
            return BackendConfig(id, key, BuildConfig.PARSE_SERVER_URL.ifBlank { DEFAULT_SERVER_URL })
        }

        private fun fromAssets(context: Context): BackendConfig? = runCatching {
            context.assets.open("backend.properties").use { stream ->
                val p = Properties().apply { load(stream) }
                val id = p.getProperty("appId", "").trim()
                val key = p.getProperty("clientKey", "").trim()
                if (!valid(id) || !valid(key)) return null
                BackendConfig(id, key, p.getProperty("serverUrl", DEFAULT_SERVER_URL).trim().ifBlank { DEFAULT_SERVER_URL })
            }
        }.getOrNull()

        private fun valid(v: String) = v.isNotBlank() && !v.startsWith("PASTE_")
    }
}
