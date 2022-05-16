package com.segment.analytics.edgefn.kotlin

import android.content.Context
import android.content.SharedPreferences
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Settings
import com.segment.analytics.kotlin.core.platform.EventPlugin
import com.segment.analytics.kotlin.core.platform.Plugin
import com.segment.analytics.kotlin.core.utilities.getInt
import com.segment.analytics.kotlin.core.utilities.getString
import com.segment.analytics.substrata.kotlin.j2v8.J2V8Engine
import com.segment.analytics.substrata.kotlin.wrapAsJSValue
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileInputStream

class EdgeFunctions(
    val fallbackFileURL: String? = null
): EventPlugin {

    companion object {
        const val USER_DEFAULTS_KEY = "EdgeFunction"
        const val VERSION_KEY = "version"
        const val DOWNLOAD_URL_KEY = "downloadURL"

        const val EDGE_FUNCTION_FILE_NAME = "edgeFunction.js"
    }

    override lateinit var analytics: Analytics

    override val type: Plugin.Type = Plugin.Type.Utility

    internal val engine = J2V8Engine()

    internal var loaded = false

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var edgeFnFile: File

    override fun setup(analytics: Analytics) {
        super.setup(analytics)

        require (analytics.configuration.application is Context) {
            "Incompatible Android Context!"
        }
        val context = analytics.configuration.application as Context
        sharedPreferences = context.getSharedPreferences(
            "analytics-edgefn-${analytics.configuration.writeKey}",
                Context.MODE_PRIVATE)
        val storageDirectory = context.getDir("segment-data", Context.MODE_PRIVATE)
        edgeFnFile = File(storageDirectory, EDGE_FUNCTION_FILE_NAME)
    }

    override fun update(settings: Settings, type: Plugin.UpdateType) {
        if (type != Plugin.UpdateType.Initial || loaded) {
            return
        }

        loaded = true

        val edgeFnData = settings.edgeFunction
        setEdgeFnData(edgeFnData)

        loadEdgeFn(edgeFnFile)
    }

    fun loadEdgeFn(file: File) {
        engine.errorHandler = {
            println(it)
        }

        engine.expose(JSAnalytics::class, "Analytics")

        val jsAnalytics = JSAnalytics(analytics, engine)
        engine.expose("analytics", jsAnalytics)

        engine.execute(EmbeddedJS.ENUM_SETUP_SCRIPT)
        engine.execute(EmbeddedJS.EDGE_FN_BASE_SETUP_SCRIPT)

        if (!file.exists() && fallbackFileURL != null) {
            val backup = File(fallbackFileURL)
            if (backup.exists()) {
                backup.copyTo(file)
            }
        }

        engine.loadBundle(FileInputStream(file)) { error ->
            error?.let {
                println(error.message)
            }
        }
    }

    fun setEdgeFnData(data: JsonObject) {
        val versionExists = data.containsKey(VERSION_KEY)
        val downloadURLExists = data.containsKey(DOWNLOAD_URL_KEY)

        if (versionExists && downloadURLExists) {
            currentData()?.let { currData ->
                val newVersion = data.getInt(VERSION_KEY)
                val currVersion = currData.getInt(VERSION_KEY)

                if (newVersion != null && currVersion != null && newVersion > currVersion) {
                    update(data)
                }
            } ?: update(data)
        }
    }

    private fun currentData() =
        sharedPreferences.getString(USER_DEFAULTS_KEY, null)?.let {
            Json.decodeFromString<JsonObject>(it)
        }

    private fun update(data: JsonObject) {
        val urlString = data.getString(DOWNLOAD_URL_KEY) ?: return

        sharedPreferences.edit().putString(USER_DEFAULTS_KEY, Json.encodeToString(data)).apply()

        analytics.analyticsScope.launch(analytics.fileIODispatcher) {
            if (urlString.isNotEmpty()) {
                download(urlString, edgeFnFile)
                println("New EdgeFunction installed.  Will be used on next app launch.")
            }
            else {
                disableBundleURL(edgeFnFile)
            }
        }
    }
}