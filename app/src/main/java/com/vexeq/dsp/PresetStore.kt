package com.vexeq.dsp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EqPreset(
    val name: String,
    val settings: DspSettings,
    val updatedAt: Long
)

/** Small, offline-only preset store. No network access or account is required. */
class PresetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "vex_eq_presets_v1", Context.MODE_PRIVATE
    )

    @Synchronized
    fun list(): List<EqPreset> = runCatching {
        val root = JSONArray(preferences.getString(KEY_PRESETS, "[]"))
        buildList {
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val settings = item.optJSONObject("settings") ?: continue
                if (name.isNotEmpty()) add(EqPreset(name, settings.decodeSettings(), item.optLong("updatedAt")))
            }
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun loadLastSettings(): DspSettings = runCatching {
        val json = preferences.getString(KEY_LAST_SETTINGS, null) ?: return DspSettings()
        JSONObject(json).decodeSettings()
    }.getOrDefault(DspSettings())

    fun saveLastSettings(settings: DspSettings) {
        runCatching {
            preferences.edit()
                .putString(KEY_LAST_SETTINGS, settings.sanitized().encodeSettings().toString())
                .apply()
        }
    }

    @Synchronized
    fun save(name: String, settings: DspSettings): Boolean {
        val cleanName = name.trim().take(40)
        if (cleanName.isEmpty()) return false
        val existing = list()
        val isNewName = existing.none { it.name.equals(cleanName, ignoreCase = true) }
        if (isNewName && existing.size >= MAX_SAVED_PRESETS) return false
        val presets = existing.filterNot { it.name.equals(cleanName, ignoreCase = true) }
            .toMutableList()
        presets += EqPreset(cleanName, settings.sanitized(), System.currentTimeMillis())
        return persist(presets)
    }

    @Synchronized
    fun delete(name: String): Boolean =
        persist(list().filterNot { it.name.equals(name, ignoreCase = true) })

    @Synchronized
    fun exportJson(): String {
        val root = JSONObject().put("format", "vex-eq-presets")
            .put("version", EXPORT_VERSION)
            .put("presets", JSONArray().apply {
                list().forEach { preset ->
                    put(JSONObject().put("name", preset.name)
                        .put("updatedAt", preset.updatedAt)
                        .put("settings", preset.settings.encodeSettings()))
                }
            })
        return root.toString(2)
    }

    @Synchronized
    fun importJson(json: String): Int {
        require(json.length <= MAX_IMPORT_CHARS) { "Preset file is too large" }
        val root = JSONObject(json)
        require(root.optString("format") == "vex-eq-presets") { "Not a VEX EQ preset file" }
        require(root.optInt("version", -1) == EXPORT_VERSION) { "Unsupported preset file version" }
        val incoming = root.optJSONArray("presets") ?: throw IllegalArgumentException("Missing presets")
        require(incoming.length() <= MAX_IMPORTED_PRESETS) { "Preset file contains too many entries" }
        val merged = list().associateBy { it.name.lowercase() }.toMutableMap()
        var imported = 0
        for (index in 0 until incoming.length()) {
            val item = incoming.optJSONObject(index) ?: continue
            val name = item.optString("name").trim().take(40)
            val settings = item.optJSONObject("settings") ?: continue
            if (name.isBlank()) continue
            merged[name.lowercase()] = EqPreset(name, settings.decodeSettings(), item.optLong("updatedAt"))
            imported++
        }
        require(merged.size <= MAX_SAVED_PRESETS) {
            "Import would exceed the $MAX_SAVED_PRESETS preset limit"
        }
        check(persist(merged.values.toList())) { "Could not save imported presets" }
        return imported
    }

    private fun persist(presets: List<EqPreset>): Boolean = runCatching {
        require(presets.size <= MAX_SAVED_PRESETS)
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().put("name", preset.name)
                .put("updatedAt", preset.updatedAt)
                .put("settings", preset.settings.encodeSettings()))
        }
        preferences.edit().putString(KEY_PRESETS, array.toString()).commit()
    }.getOrDefault(false)

    private fun DspSettings.encodeSettings(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("graphicGainsDb", JSONArray(graphicGainsDb))
        put("parametricBands", JSONArray().apply {
            parametricBands.forEach { band ->
                put(JSONObject().put("frequencyHz", band.frequencyHz)
                    .put("gainDb", band.gainDb).put("q", band.q))
            }
        })
        put("bassBoost", bassBoost)
        put("stereoWidth", stereoWidth)
        put("preampDb", preampDb)
        put("autoHeadroom", autoHeadroom)
        put("limiterEnabled", limiterEnabled)
        put("compressorEnabled", compressorEnabled)
        put("compressorThresholdDb", compressorThresholdDb)
        put("compressorRatio", compressorRatio)
        put("compressorAttackMs", compressorAttackMs)
        put("compressorReleaseMs", compressorReleaseMs)
        put("softClipperEnabled", softClipperEnabled)
        put("performanceMode", performanceMode.name)
    }

    private fun JSONObject.decodeSettings(): DspSettings {
        val gainsJson = optJSONArray("graphicGainsDb") ?: JSONArray()
        val gains = buildList {
            for (index in 0 until gainsJson.length()) add(gainsJson.optDouble(index, 0.0).toFloat())
        }
        val bandsJson = optJSONArray("parametricBands") ?: JSONArray()
        val bands = buildList {
            for (index in 0 until bandsJson.length()) {
                val item = bandsJson.optJSONObject(index) ?: continue
                add(ParametricBand(
                    frequencyHz = item.optDouble("frequencyHz", 1_000.0).toFloat(),
                    gainDb = item.optDouble("gainDb", 0.0).toFloat(),
                    q = item.optDouble("q", 1.0).toFloat()
                ))
            }
        }
        val mode = runCatching {
            DspPerformanceMode.valueOf(optString("performanceMode", DspPerformanceMode.HIGH.name))
        }.getOrDefault(DspPerformanceMode.HIGH)
        return DspSettings(
            enabled = optBoolean("enabled", false),
            graphicGainsDb = gains,
            parametricBands = bands,
            bassBoost = optDouble("bassBoost", 0.0).toFloat(),
            stereoWidth = optDouble("stereoWidth", 0.0).toFloat(),
            preampDb = optDouble("preampDb", 0.0).toFloat(),
            autoHeadroom = optBoolean("autoHeadroom", true),
            limiterEnabled = optBoolean("limiterEnabled", true),
            compressorEnabled = optBoolean("compressorEnabled", false),
            compressorThresholdDb = optDouble("compressorThresholdDb", -18.0).toFloat(),
            compressorRatio = optDouble("compressorRatio", 4.0).toFloat(),
            compressorAttackMs = optDouble("compressorAttackMs", 10.0).toFloat(),
            compressorReleaseMs = optDouble("compressorReleaseMs", 120.0).toFloat(),
            softClipperEnabled = optBoolean("softClipperEnabled", false),
            performanceMode = mode
        ).sanitized()
    }

    companion object {
        private const val KEY_PRESETS = "presets"
        private const val KEY_LAST_SETTINGS = "last_settings"
        private const val EXPORT_VERSION = 1
        private const val MAX_IMPORTED_PRESETS = 100
        private const val MAX_SAVED_PRESETS = 100
        private const val MAX_IMPORT_CHARS = 1_048_576
    }
}
