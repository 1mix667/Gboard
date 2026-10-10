package io.github.kvmy666.gboardplus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the flat String→String prefs parser shared by the app and the hook.
 * Robolectric supplies a real org.json and a manifest-free runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GbpPrefsJsonTest {

    @Test
    fun parsesFlatObject() {
        val map = GbpPrefsJson.parse("""{"keyboard_enhancer_enabled":"1","clipboard_max_entries":"500"}""")
        assertEquals("1", map["keyboard_enhancer_enabled"])
        assertEquals("500", map["clipboard_max_entries"])
        assertEquals(2, map.size)
    }

    @Test
    fun emptyObjectYieldsEmptyMap() {
        assertTrue(GbpPrefsJson.parse("{}").isEmpty())
    }

    @Test
    fun preservesEmptyStringValues() {
        val map = GbpPrefsJson.parse("""{"shortcut_text_1":"","shortcut_text_2":""}""")
        assertEquals("", map["shortcut_text_1"])
        assertEquals("", map["shortcut_text_2"])
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedJsonThrows() {
        GbpPrefsJson.parse("not json")
    }
}
