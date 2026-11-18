package com.mrjdm2007.analog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneEqProfilesTest {
    @Test
    fun exportedProfilesRoundTripEveryDspSetting() {
        val original = HeadphoneEqProfile(
            name = "USB DAC",
            gainsDb = List(10) { (it - 5).toFloat() },
            bandwidths = List(10) { 0.5f + it * 0.1f },
            preampDb = -3.5f,
            eqEnabled = true,
            crossfeedEnabled = true,
            crossfeedAmount = 0.22f,
            peakGuardEnabled = true,
            peakGuardThresholdDb = -2.5f,
            stereoBalance = 0.35f
        )

        val imported = HeadphoneEqProfiles.importJson(HeadphoneEqProfiles.exportJson(listOf(original)))
        assertEquals(1, imported.size)
        val actual = imported.single()
        assertEquals(original.name, actual.name)
        assertEquals(original.gainsDb, actual.gainsDb)
        assertEquals(original.bandwidths, actual.bandwidths)
        assertEquals(original.preampDb, actual.preampDb, 0.0001f)
        assertEquals(original.eqEnabled, actual.eqEnabled)
        assertEquals(original.crossfeedEnabled, actual.crossfeedEnabled)
        assertEquals(original.crossfeedAmount, actual.crossfeedAmount, 0.0001f)
        assertEquals(original.peakGuardEnabled, actual.peakGuardEnabled)
        assertEquals(original.peakGuardThresholdDb, actual.peakGuardThresholdDb, 0.0001f)
        assertEquals(original.stereoBalance, actual.stereoBalance, 0.0001f)
    }

    @Test
    fun legacyFieldNamesRemainImportableAndBlankProfilesAreSkipped() {
        val gains = (0 until 10).joinToString(",") { "0.5" }
        val widths = (0 until 10).joinToString(",") { "1.25" }
        val json = """
            [
              {"name":"Legacy DAC","gains":[$gains],"bandwidths":[$widths],
               "preampDb":-2.0,"eqEnabled":true,"crossfeedEnabled":true,
               "crossfeedAmount":0.15,"peakGuardEnabled":true,
               "peakGuardThresholdDb":-3.0,"stereoBalance":-0.2},
              {"name":"   ","gains":[$gains],"bandwidths":[$widths]}
            ]
        """.trimIndent()

        val imported = HeadphoneEqProfiles.importJson(json)
        assertEquals(1, imported.size)
        val profile = imported.single()
        assertEquals("Legacy DAC", profile.name)
        assertEquals(-2f, profile.preampDb, 0.0001f)
        assertEquals(-3f, profile.peakGuardThresholdDb, 0.0001f)
        assertEquals(-0.2f, profile.stereoBalance, 0.0001f)
        assertTrue(profile.crossfeedEnabled)
    }
}
