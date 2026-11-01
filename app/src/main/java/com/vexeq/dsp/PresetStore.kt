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

    @Synchronized
    fun save(name: String, settings: DspSettings): Boolean {
        val cleanName = name.trim().take(40)
        if (cleanName.isEmpty()) return false
        val presets = list().filterNot { it.name.equals(cleanName, ignoreCase = true) }
            .toMutableList()
        presets += EqPreset(cleanName, settings.sanitized(), System.currentTimeMillis())
        return persist(presets)
    }

    @Synchronized
    fun delete(name: String): Boolean =
        persist(list().filterNot { it.name.equals(name, ignoreCase = true) })

    private fun persist(presets: List<EqPreset>): Boolean = runCatching {
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
            performanceMode = mode
        ).sanitized()
    }

    companion object { private const val KEY_PRESETS = "presets" }
}
