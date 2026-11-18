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
    val crossfeedAmount: Float
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
                    val bandwidths = item.optJSONArray("bandwidths")?.toFloatList() ?: List(10) { 1f }
                    if (gains.size != 10) continue
                    add(HeadphoneEqProfile(
                        name = item.optString("name"),
                        gainsDb = gains.map { it.coerceIn(-12f, 12f) },
                        bandwidths = List(10) { bandwidths.getOrElse(it) { 1f }.coerceIn(0.1f, 4f) },
                        preampDb = item.optDouble("preampDb", 0.0).toFloat().coerceIn(-12f, 12f),
                        eqEnabled = item.optBoolean("eqEnabled", true),
                        crossfeedEnabled = item.optBoolean("crossfeedEnabled", false),
                        crossfeedAmount = item.optDouble("crossfeedAmount", 0.12).toFloat().coerceIn(0f, 0.35f)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun write(context: Context, profiles: List<HeadphoneEqProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("name", profile.name)
                put("gains", JSONArray(profile.gainsDb))
                put("bandwidths", JSONArray(profile.bandwidths))
                put("preampDb", profile.preampDb)
                put("eqEnabled", profile.eqEnabled)
                put("crossfeedEnabled", profile.crossfeedEnabled)
                put("crossfeedAmount", profile.crossfeedAmount)
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
