package com.dimadesu.mediasrvr

import org.junit.Assert.*
import org.junit.Test

class Amf0ParserTest {
    @Test
    fun testReadNumberStringNull() {
        // Number (8.5), String("hello"), Null
        val baos = java.io.ByteArrayOutputStream()
        // number marker + 8 bytes
        baos.write(0)
        val bb = java.nio.ByteBuffer.allocate(8).putDouble(8.5).array()
        baos.write(bb)
        // string marker + len + bytes
        val s = "hello".toByteArray(Charsets.UTF_8)
        baos.write(2)
        baos.write((s.size shr 8) and 0xff)
        baos.write(s.size and 0xff)
        baos.write(s)
        // null marker
        baos.write(5)

        val parser = Amf0Parser(baos.toByteArray())
        val n = parser.readAmf0() as? Double
        assertNotNull(n)
        assertEquals(8.5, n!!, 0.0001)
        val str = parser.readAmf0() as? String
        assertEquals("hello", str)
        val nn = parser.readAmf0()
        assertNull(nn)
    }

    @Test
    fun testReadEcmaArray() {
        // ECMA array with two entries: name->"cam", id->123
        val baos = java.io.ByteArrayOutputStream()
        baos.write(8) // ECMA array marker
        // length (we'll write 2)
        baos.write(0)
        baos.write(0)
        baos.write(0)
        baos.write(2)
        // first key "name"
        val k1 = "name".toByteArray(Charsets.UTF_8)
        baos.write((k1.size shr 8) and 0xff)
        baos.write(k1.size and 0xff)
        baos.write(k1)
        // string value "cam"
        val v1 = "cam".toByteArray(Charsets.UTF_8)
        baos.write(2)
        baos.write((v1.size shr 8) and 0xff)
        baos.write(v1.size and 0xff)
        baos.write(v1)
        // second key "id"
        val k2 = "id".toByteArray(Charsets.UTF_8)
        baos.write((k2.size shr 8) and 0xff)
        baos.write(k2.size and 0xff)
        baos.write(k2)
        // number value 123
        baos.write(0)
        val bb = java.nio.ByteBuffer.allocate(8).putDouble(123.0).array()
        baos.write(bb)
        // object end marker
        baos.write(0)
        baos.write(0)
        baos.write(9)

        val parser = Amf0Parser(baos.toByteArray())
        val map = parser.readAmf0() as? Map<*, *>
        assertNotNull(map)
        assertEquals("cam", map!!["name"])
        val id = map["id"] as? Double
        assertEquals(123.0, id!!, 0.0001)
    }
}
