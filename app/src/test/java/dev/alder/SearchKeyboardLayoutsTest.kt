package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeyboardLayoutsTest {
    @Test fun arabicLayoutContainsEveryBaseArabicLetter() {
        val keys = SearchKeyboardLayouts.rows("ar").flatten().joinToString("")
        val required = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
        required.forEach { letter -> assertTrue("Missing Arabic letter: $letter", keys.contains(letter)) }
        assertEquals(3, SearchKeyboardLayouts.rows("ar").size)
        assertTrue(SearchKeyboardLayouts.rows("ar").all { it.isNotEmpty() })
    }

    @Test fun secondLanguageLayoutsAreAvailableAndDistinct() {
        assertEquals(listOf("ar", "es", "fr", "de", "it", "pt-BR"), SearchKeyboardLayouts.supportedLanguages)
        assertEquals("ñ", SearchKeyboardLayouts.rows("es")[1].last())
        assertEquals("a", SearchKeyboardLayouts.rows("fr")[0].first())
        assertEquals("z", SearchKeyboardLayouts.rows("de")[0][5])
    }

    @Test fun symbolModeAlwaysFillsTheVisibleKeyCount() {
        val count = SearchKeyboardLayouts.rows("ar").flatten().size
        assertEquals(count, SearchKeyboardLayouts.symbols(count).size)
    }
}
