package com.jemcik.jemrec.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageNamesTest {

    @Test fun eachLanguageIsNamedInItself() {
        assertEquals("Norsk", LanguageNames.own("no"))
        assertEquals("Українська", LanguageNames.own("uk"))
        assertEquals("Norsk (Norwegian)", LanguageNames.both("no"))
        assertEquals("English", LanguageNames.both("en"))
    }

    @Test fun detectHasOneName() {
        assertEquals("Detect automatically", LanguageNames.both("auto"))
        assertNull(LanguageNames.english("auto"))
    }

    /** Every offered language, once, with a name of its own rather than its code. */
    @Test fun allSeventeenAreOfferedWithNames() {
        assertEquals(17, LanguageNames.offered.size)
        assertEquals(17, LanguageNames.offered.toSet().size)
        LanguageNames.offered.forEach { assertTrue(it, LanguageNames.own(it).isNotBlank() && LanguageNames.own(it) != it) }
    }
}
