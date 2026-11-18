package com.mrjdm2007.analog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

data class HeadphoneEqProfile(
    val name: String,
    val gainsDb: List<Float>,
    val bandwidths: List<Float>,
    val preampDb: Float,
    val eqEnabled: Boolean,
    val crossfeedEnabled: Boolean,
    val crossfeedAmount: Float,
    val peakGuardEnabled: Boolean = false,
    val peakGuardThresholdDb: Float = -1f,
    val stereoBalance: Float = 0f
)

object HeadphoneEqProfiles {
    private const val PREFS = "analog_library"
    private const val KEY = "headphone_eq_profiles"

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)

    fun read(context: Context): List<HeadphoneEqProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val gains = item.optJSONArray("gains")?.toFloatList() ?: continue
                    if (gains.size != 10 || gains.any { !it.isFinite() }) continue
                    val rawBandwidths = item.optJSONArray("bandwidths")?.toFloatList() ?: List(10) { 1f }
                    val bandwidths = List(10) { index ->
                        rawBandwidths.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(0.1f, 4f) ?: 1f
                    }
                    val name = item.optString("name").trim()
                    if (name.isBlank()) continue
                    add(HeadphoneEqProfile(
                        name = name,
                        gainsDb = gains.map { it.coerceIn(-12f, 12f) },
                        bandwidths = List(10) { bandwidths.getOrElse(it) { 1f }.coerceIn(0.1f, 4f) },
                        preampDb = item.optDouble("preampDB", item.optDouble("preampDb", 0.0)).takeIf { it.isFinite() }?.toFloat()?.coerceIn(-12f, 12f) ?: 0f,
                        eqEnabled = item.optBoolean("eqEnabled", true),
                        crossfeedEnabled = item.optBoolean("crossfeedEnabled", false),
                        crossfeedAmount = item.optDouble("crossfeedAmount", 0.12).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 0.35f) ?: 0.12f,
                        peakGuardEnabled = item.optBoolean("peakGuardEnabled", false),
                        peakGuardThresholdDb = item.optDouble("peakGuardThresholdDB", item.optDouble("peakGuardThresholdDb", -1.0)).takeIf { it.isFinite() }?.toFloat()?.coerceIn(-12f, -0.1f) ?: -1f,
                        stereoBalance = item.optDouble("stereoPan", item.optDouble("stereoBalance", 0.0)).takeIf { it.isFinite() }?.toFloat()?.coerceIn(-1f, 1f) ?: 0f
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun exportJson(profiles: List<HeadphoneEqProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("name", profile.name)
                put("gains", JSONArray(profile.gainsDb))
                put("bandwidths", JSONArray(profile.bandwidths))
                put("preampDB", profile.preampDb)
                put("eqEnabled", profile.eqEnabled)
                put("crossfeedEnabled", profile.crossfeedEnabled)
                put("crossfeedAmount", profile.crossfeedAmount)
                put("peakGuardEnabled", profile.peakGuardEnabled)
                put("peakGuardThresholdDB", profile.peakGuardThresholdDb)
                put("stereoPan", profile.stereoBalance)
            })
        }
        return array.toString(2)
    }

    fun importJson(raw: String): List<HeadphoneEqProfile> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val gains = item.optJSONArray("gains")?.toFloatList() ?: continue
                if (gains.size != 10) continue
                val widths = item.optJSONArray("bandwidths")?.toFloatList() ?: List(10) { 1f }
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(HeadphoneEqProfile(
                    name,
                    gains.map { it.coerceIn(-12f, 12f) },
                    List(10) { widths.getOrElse(it) { 1f }.coerceIn(.1f, 4f) },
                    item.optDouble("preampDB", item.optDouble("preampDb", 0.0)).toFloat().coerceIn(-12f, 12f),
                    item.optBoolean("eqEnabled", true),
                    item.optBoolean("crossfeedEnabled", false),
                    item.optDouble("crossfeedAmount", .12).toFloat().coerceIn(0f, .35f),
                    item.optBoolean("peakGuardEnabled", false),
                    item.optDouble("peakGuardThresholdDB", item.optDouble("peakGuardThresholdDb", -1.0)).toFloat().coerceIn(-12f, -.1f),
                    item.optDouble("stereoPan", item.optDouble("stereoBalance", 0.0)).toFloat().coerceIn(-1f, 1f)
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun write(context: Context, profiles: List<HeadphoneEqProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("name", profile.name)
                put("gains", JSONArray(profile.gainsDb))
                put("bandwidths", JSONArray(profile.bandwidths))
                put("preampDB", profile.preampDb)
                put("eqEnabled", profile.eqEnabled)
                put("crossfeedEnabled", profile.crossfeedEnabled)
                put("crossfeedAmount", profile.crossfeedAmount)
                put("peakGuardEnabled", profile.peakGuardEnabled)
                put("peakGuardThresholdDB", profile.peakGuardThresholdDb)
                put("stereoPan", profile.stereoBalance)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun findExact(profiles: List<HeadphoneEqProfile>, outputName: String): HeadphoneEqProfile? {
        val key = normalize(outputName)
        if (key.isBlank()) return null
        return profiles.firstOrNull { normalize(it.name) == key }
    }

    private fun JSONArray.toFloatList(): List<Float> = List(length()) { getDouble(it).toFloat() }
}
