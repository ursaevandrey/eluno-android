package ru.eluno.core.chip

import com.google.gson.JsonParser
import matter.jsontlv.fromJsonString
import matter.jsontlv.toJsonString
import matter.tlv.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the pinned SDK's real Gson integration, not a replacement codec. */
class MatterJsonTlvCompatibilityTest {
    @Test fun `updated Gson preserves Matter TLV types and nested values`() {
        val json = """{"0:BOOL":true,"1:INT":-42,"2:UINT":254,"3:STRING":"Свет","4:BYTES":"AQID","5:NULL":null,"6:FLOAT":1.5,"7:DOUBLE":2.25,"8:STRUCT":{"0:BOOL":false},"9:ARRAY-UINT":[1,2]}"""
        val expected = TlvWriter().apply {
            startStructure(AnonymousTag)
            put(ContextSpecificTag(0), true)
            put(ContextSpecificTag(1), -42)
            put(ContextSpecificTag(2), 254u)
            put(ContextSpecificTag(3), "Свет")
            put(ContextSpecificTag(4), byteArrayOf(1, 2, 3))
            putNull(ContextSpecificTag(5))
            put(ContextSpecificTag(6), 1.5f)
            put(ContextSpecificTag(7), 2.25)
            startStructure(ContextSpecificTag(8)); put(ContextSpecificTag(0), false); endStructure()
            startArray(ContextSpecificTag(9)); put(AnonymousTag, 1u); put(AnonymousTag, 2u); endArray()
            endStructure()
        }.getEncoded()
        assertArrayEquals(expected, TlvWriter().fromJsonString(json))
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(TlvReader(expected).toJsonString()))
    }

    @Test fun `signed and unsigned 64 bit values retain every bit`() {
        val json = """{"0:INT":"-9223372036854775808","1:UINT":"18446744073709551615"}"""
        val encoded = TlvWriter().fromJsonString(json)
        val reader = TlvReader(encoded)
        reader.enterStructure(AnonymousTag)
        assertEquals(Long.MIN_VALUE, reader.getLong(ContextSpecificTag(0)))
        assertEquals(ULong.MAX_VALUE, reader.getULong(ContextSpecificTag(1)))
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(TlvReader(encoded).toJsonString()))
    }

    @Test fun `invalid values are rejected by the SDK with updated Gson`() {
        listOf("""{"0:BOOL":"true"}""", """{"0:UINT":null}""", """{"0:UNKNOWN":1}""").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { TlvWriter().fromJsonString(json) }
        }
    }
}
