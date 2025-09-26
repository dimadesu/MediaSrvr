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

    @Test
    fun testLongStringAndUndefined() {
        // Long string (marker 11) with length > 65535 and undefined marker (1)
        val long = "a".repeat(70000)
        val baos = java.io.ByteArrayOutputStream()
    baos.write(12) // long string marker
        val lb = long.toByteArray(Charsets.UTF_8)
        val lenBuf = java.nio.ByteBuffer.allocate(4).putInt(lb.size).array()
        baos.write(lenBuf)
        baos.write(lb)
    // undefined
    baos.write(6)

        val parser = Amf0Parser(baos.toByteArray())
        val s = parser.readAmf0() as? String
        assertNotNull(s)
        assertEquals(70000, s!!.length)
        val u = parser.readAmf0()
        assertNull(u)
    }

    @Test
    fun testStrictArray() {
        // Strict array with 3 elements: number, string, null
        val baos = java.io.ByteArrayOutputStream()
        baos.write(10) // strict array marker
        baos.write(java.nio.ByteBuffer.allocate(4).putInt(3).array())
        // number 1.5
        baos.write(0)
        baos.write(java.nio.ByteBuffer.allocate(8).putDouble(1.5).array())
        // string "x"
        val s = "x".toByteArray(Charsets.UTF_8)
        baos.write(2)
        baos.write((s.size shr 8) and 0xff)
        baos.write(s.size and 0xff)
        baos.write(s)
        // null
        baos.write(5)

        val parser = Amf0Parser(baos.toByteArray())
        val arr = parser.readAmf0() as? List<*>
        assertNotNull(arr)
        assertEquals(3, arr!!.size)
        assertEquals(1.5, arr[0] as Double, 0.0001)
        assertEquals("x", arr[1] as String)
        assertNull(arr[2])
    }
}
