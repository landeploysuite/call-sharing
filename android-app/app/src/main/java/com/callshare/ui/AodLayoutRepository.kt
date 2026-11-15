package com.callshare.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * AodLayoutRepository
 *
 * Persists and serializes the modular AOD widgets into JSON with dual-orientation
 * coordinate profiles (portX, portY vs landX, landY).
 * Allows dynamic injection of live telemetry widgets and user-imported stickers.
 */
class AodLayoutRepository(private val context: Context) {

    companion object {
        private const val TAG = "AodLayoutRepository"
        private const val PREFS_NAME = "callshare_aod_layout_prefs"
        private const val KEY_WIDGETS_JSON = "key_aod_widgets_json"

        const val TYPE_CLOCK = "CLOCK"
        const val TYPE_BATTERY = "BATTERY"
        const val TYPE_JITTER = "JITTER"
        const val TYPE_SIM = "SIM"
        const val TYPE_MUTE = "MUTE"
        const val TYPE_TEXT = "TEXT"
        const val TYPE_STICKER = "STICKER"
    }

    data class AodWidget(
        val id: String,
        val type: String,
        var label: String,
        var imageUri: String? = null,
        var scale: Float = 1.0f,
        var zIndex: Float = 1.0f,
        var portX: Float, // Normalized (0.0f - 1.0f)
        var portY: Float,
        var landX: Float,
        var landY: Float
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("id", id)
                put("type", type)
                put("label", label)
                put("imageUri", imageUri ?: "")
                put("scale", scale.toDouble())
                put("zIndex", zIndex.toDouble())
                put("portX", portX.toDouble())
                put("portY", portY.toDouble())
                put("landX", landX.toDouble())
                put("landY", landY.toDouble())
            }
        }

        companion object {
            fun fromJsonObject(obj: JSONObject): AodWidget {
                return AodWidget(
                    id = obj.getString("id"),
                    type = obj.getString("type"),
                    label = obj.optString("label", ""),
                    imageUri = obj.optString("imageUri").takeIf { it.isNotBlank() },
                    scale = obj.optDouble("scale", 1.0).toFloat(),
                    zIndex = obj.optDouble("zIndex", 1.0).toFloat(),
                    portX = obj.optDouble("portX", 0.5).toFloat(),
                    portY = obj.optDouble("portY", 0.5).toFloat(),
                    landX = obj.optDouble("landX", 0.5).toFloat(),
                    landY = obj.optDouble("landY", 0.5).toFloat()
                )
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Loads the current list of AOD widgets from SharedPreferences or seeds default layout.
     */
    fun getWidgets(): MutableList<AodWidget> {
        val jsonStr = prefs.getString(KEY_WIDGETS_JSON, null)
        if (jsonStr.isNullOrBlank()) {
            val defaults = createDefaultWidgets()
            saveWidgets(defaults)
            return defaults
        }

        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<AodWidget>()
            for (i in 0 until arr.length()) {
                list.add(AodWidget.fromJsonObject(arr.getJSONObject(i)))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AOD widgets JSON: ${e.message}")
            createDefaultWidgets()
        }
    }

    /**
     * Serializes and commits the widget list to SharedPreferences.
     */
    fun saveWidgets(widgets: List<AodWidget>) {
        val arr = JSONArray()
        for (w in widgets) {
            arr.put(w.toJsonObject())
        }
        prefs.edit().putString(KEY_WIDGETS_JSON, arr.toString()).apply()
        Log.d(TAG, "Saved ${widgets.size} widgets to layout repository")
    }

    fun addWidget(widget: AodWidget) {
        val current = getWidgets()
        current.add(widget)
        saveWidgets(current)
    }

    fun removeWidget(id: String) {
        val current = getWidgets()
        current.removeAll { it.id == id }
        saveWidgets(current)
    }

    fun updateWidgetCoordinates(id: String, isLandscape: Boolean, normX: Float, normY: Float) {
        val current = getWidgets()
        val target = current.find { it.id == id } ?: return
        if (isLandscape) {
            target.landX = normX.coerceIn(0.0f, 1.0f)
            target.landY = normY.coerceIn(0.0f, 1.0f)
        } else {
            target.portX = normX.coerceIn(0.0f, 1.0f)
            target.portY = normY.coerceIn(0.0f, 1.0f)
        }
        saveWidgets(current)
    }

    fun updateWidgetScale(id: String, scale: Float) {
        val current = getWidgets()
        val target = current.find { it.id == id } ?: return
        target.scale = scale.coerceIn(0.6f, 1.8f)
        saveWidgets(current)
    }

    fun updateWidgetZIndex(id: String, zIndex: Float) {
        val current = getWidgets()
        val target = current.find { it.id == id } ?: return
        target.zIndex = zIndex
        saveWidgets(current)
    }

    fun resetToDefaults(): MutableList<AodWidget> {
        val defaults = createDefaultWidgets()
        saveWidgets(defaults)
        return defaults
    }

    /**
     * Seeds the factory default layout for portrait & landscape nightstand profiles.
     */
    private fun createDefaultWidgets(): MutableList<AodWidget> {
        return mutableListOf(
            AodWidget(
                id = "default_clock",
                type = TYPE_CLOCK,
                label = "Ambient Clock",
                scale = 1.0f,
                zIndex = 10f,
                portX = 0.50f,
                portY = 0.22f,
                landX = 0.28f,
                landY = 0.35f
            ),
            AodWidget(
                id = "default_battery",
                type = TYPE_BATTERY,
                label = "Peer Battery Pill",
                scale = 1.0f,
                zIndex = 8f,
                portX = 0.50f,
                portY = 0.36f,
                landX = 0.28f,
                landY = 0.60f
            ),
            AodWidget(
                id = "default_sim",
                type = TYPE_SIM,
                label = "SIM Carrier Badge",
                scale = 1.0f,
                zIndex = 7f,
                portX = 0.50f,
                portY = 0.44f,
                landX = 0.72f,
                landY = 0.35f
            ),
            AodWidget(
                id = "default_jitter",
                type = TYPE_JITTER,
                label = "Live Jitter Waveform",
                scale = 1.0f,
                zIndex = 6f,
                portX = 0.50f,
                portY = 0.52f,
                landX = 0.72f,
                landY = 0.55f
            )
        )
    }

    /**
     * Copies an imported visual media sticker (PNG/SVG/WebP) to private app storage
     * so it persists offline permanently without content resolver permission expiration.
     */
    fun saveStickerToInternalStorage(sourceUri: Uri): String? {
        return try {
            val stickerDir = File(context.filesDir, "stickers")
            if (!stickerDir.exists()) stickerDir.mkdirs()

            val fileName = "sticker_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
            val targetFile = File(stickerDir, fileName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Sticker imported and cached at: ${targetFile.absolutePath}")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache sticker: ${e.message}")
            null
        }
    }
}
