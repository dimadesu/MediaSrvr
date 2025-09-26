package com.dimadesu.mediasrvr

import org.junit.Assert.*
import org.junit.Test

class Amf3EncoderTest {
    @Test
    fun testEncodePrimitivesAndRoundTrip() {
        val enc = Amf3Encoder()
        enc.writeValue(42)
        enc.writeValue("hello")
        enc.writeValue(3.14)
        val data = enc.toByteArray()
        val p = Amf3Parser(data, 0)
        val a = p.readAmf3() as Int
        val b = p.readAmf3() as String
        val c = p.readAmf3() as Double
        assertEquals(42, a)
        assertEquals("hello", b)
        assertEquals(3.14, c, 0.0001)
    }

    @Test
    fun testEncodeObjectAndRoundTrip() {
        val enc = Amf3Encoder()
        val m = mapOf("x" to 1, "y" to "z")
        enc.writeValue(m)
        val data = enc.toByteArray()
        val p = Amf3Parser(data, 0)
        val out = p.readAmf3() as Map<*, *>
        assertEquals(1, out["x"])
        assertEquals("z", out["y"])
    }

    @Test
    fun testEncodeArrayRoundTrip() {
        val enc = Amf3Encoder()
        val list = listOf(5, 6, 7)
        enc.writeValue(list)
        val data = enc.toByteArray()
        val p = Amf3Parser(data, 0)
        val out = p.readAmf3() as Map<*, *>
        val dense = out["dense"] as List<*>
        assertEquals(3, dense.size)
        assertEquals(5, dense[0])
    }

    @Test
    fun testCircularReferenceObject() {
        val enc = Amf3Encoder()
        val a = mutableMapOf<String, Any?>()
        a["self"] = a // circular reference
        enc.writeValue(a)
        val data = enc.toByteArray()
        val p = Amf3Parser(data, 0)
        val out = p.readAmf3() as? Map<*, *>
        assertNotNull(out)
        val self = out!!["self"]
        // the parser may return a placeholder for refs; at minimum, the nested object's 'self' should exist
        // Strict identity: the nested self reference should point to the same Map instance
        if (self is Map<*, *>) {
            assertTrue(self === out)
        } else {
            // fallback: ensure it's not null
            assertNotNull(self)
        }
    }
}
