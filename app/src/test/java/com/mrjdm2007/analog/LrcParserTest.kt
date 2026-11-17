package com.mrjdm2007.analog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test fun parsesCentisecondsAndMultipleTimestamps() {
        val lines = LrcParser.parse("[00:01.25][00:02.50]First line\n[00:03.000]Second line")
        assertEquals(3, lines.size)
        assertEquals(1250L, lines[0].timeMs)
        assertEquals(2500L, lines[1].timeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(3000L, lines[2].timeMs)
    }

    @Test fun appliesGlobalOffsetAndClampsNegativeTimes() {
        val lines = LrcParser.parse("[offset:-2500]\n[00:01.00]Early\n[00:04.00]Later")
        assertEquals(0L, lines[0].timeMs)
        assertEquals(1500L, lines[1].timeMs)
    }

    @Test fun ignoresInvalidTimestampsAndMetadataTags() {
        val lines = LrcParser.parse("[ar:Artist]\n[00:61.00]Invalid\n[00:05.00]Valid")
        assertEquals(1, lines.size)
        assertEquals("Valid", lines.single().text)
    }

    @Test fun findsTheCurrentLineByBinarySearch() {
        val lines = LrcParser.parse("[00:01.00]One\n[00:03.00]Two\n[00:05.00]Three")
        assertEquals(-1, LrcParser.activeIndex(lines, 999L))
        assertEquals(0, LrcParser.activeIndex(lines, 1000L))
        assertEquals(1, LrcParser.activeIndex(lines, 4999L))
        assertEquals(2, LrcParser.activeIndex(lines, 5000L))
    }

    @Test fun emptyAndUnformattedLyricsAreSafe() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("plain text\nwithout timestamps").isEmpty())
    }
}
