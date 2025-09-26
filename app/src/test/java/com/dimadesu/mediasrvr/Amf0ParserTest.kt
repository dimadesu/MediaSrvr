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

    @Test
    fun testMultibyteLongString() {
        // Long UTF-8 multibyte string (>65535 bytes). Use Japanese Hiragana 'あ' (3 bytes) repeated.
        val count = 30000 // ~90k bytes
        val long = "あ".repeat(count)
        val baos = java.io.ByteArrayOutputStream()
        baos.write(12) // long string marker
        val lb = long.toByteArray(Charsets.UTF_8)
        val lenBuf = java.nio.ByteBuffer.allocate(4).putInt(lb.size).array()
        baos.write(lenBuf)
        baos.write(lb)

        val parser = Amf0Parser(baos.toByteArray())
        val s = parser.readAmf0() as? String
        assertNotNull(s)
        assertEquals(count, s!!.length)
    }

    @Test
    fun testEcmaArrayEmptyEnds() {
        // ECMA array with declared length but immediate end marker (0 0 9) should yield empty map
        val baos = java.io.ByteArrayOutputStream()
        baos.write(8) // ECMA array
        baos.write(java.nio.ByteBuffer.allocate(4).putInt(1).array())
        // immediately write object end marker (empty key + 9)
        baos.write(0)
        baos.write(0)
        baos.write(9)

        val parser = Amf0Parser(baos.toByteArray())
        val map = parser.readAmf0() as? Map<*, *>
        assertNotNull(map)
        assertTrue(map!!.isEmpty())
    }

    @Test
    fun testObjectPropertyOrdering() {
        // Object with properties in order: a=1, b=2. Verify ordering preserved in map keys.
        val baos = java.io.ByteArrayOutputStream()
        baos.write(3) // object
        // key 'a'
        val k1 = "a".toByteArray(Charsets.UTF_8)
        baos.write((k1.size shr 8) and 0xff)
        baos.write(k1.size and 0xff)
        baos.write(k1)
        baos.write(0)
        baos.write(java.nio.ByteBuffer.allocate(8).putDouble(1.0).array())
        // key 'b'
        val k2 = "b".toByteArray(Charsets.UTF_8)
        baos.write((k2.size shr 8) and 0xff)
        baos.write(k2.size and 0xff)
        baos.write(k2)
        baos.write(0)
        baos.write(java.nio.ByteBuffer.allocate(8).putDouble(2.0).array())
        // end marker
        baos.write(0)
        baos.write(0)
        baos.write(9)

        val parser = Amf0Parser(baos.toByteArray())
        val map = parser.readAmf0() as? Map<*, *>
        assertNotNull(map)
        val keys = map!!.keys.map { it as String }
        assertEquals(listOf("a", "b"), keys)
    }

    @Test
    fun testMultibyteBoundarySplit() {
        // Create a short string where multibyte chars are present and verify byte-based length handling
        // Use emoji characters which are 4 bytes in UTF-8
        val s = "🙂🙂" // two emojis, each 4 bytes in UTF-8
        val bs = s.toByteArray(Charsets.UTF_8)
        // build short string (marker 2) with length in bytes
        val baos = java.io.ByteArrayOutputStream()
        baos.write(2)
        baos.write((bs.size shr 8) and 0xff)
        baos.write(bs.size and 0xff)
        baos.write(bs)

        val parser = Amf0Parser(baos.toByteArray())
        val out = parser.readAmf0() as? String
        assertNotNull(out)
    // characters count should be 2 (use codePointCount to handle surrogate pairs)
    val charCount = out!!.codePointCount(0, out.length)
    assertEquals(2, charCount)
        // byte-length should match
        assertEquals(bs.size, out.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun testNestedStrictArraysAndObjects() {
        // Build strict array [ {"a": [1.1, {"b":"x"}] }, ["y", null] ]
        val baos = java.io.ByteArrayOutputStream()
        baos.write(10) // strict array
        baos.write(java.nio.ByteBuffer.allocate(4).putInt(2).array())

        // element 0: object
        baos.write(3)
        // key 'a'
        val ka = "a".toByteArray(Charsets.UTF_8)
        baos.write((ka.size shr 8) and 0xff)
        baos.write(ka.size and 0xff)
        baos.write(ka)
        // value: strict array of 2 elements
        baos.write(10)
        baos.write(java.nio.ByteBuffer.allocate(4).putInt(2).array())
        // number 1.1
        baos.write(0)
        baos.write(java.nio.ByteBuffer.allocate(8).putDouble(1.1).array())
        // object {"b":"x"}
        baos.write(3)
        val kb = "b".toByteArray(Charsets.UTF_8)
        baos.write((kb.size shr 8) and 0xff)
        baos.write(kb.size and 0xff)
        baos.write(kb)
        val xb = "x".toByteArray(Charsets.UTF_8)
        baos.write(2)
        baos.write((xb.size shr 8) and 0xff)
        baos.write(xb.size and 0xff)
        baos.write(xb)
        // end object
        baos.write(0)
        baos.write(0)
        baos.write(9)
        // end of strict array inside object value

        // end of object (key length 0 + 9)
        baos.write(0)
        baos.write(0)
        baos.write(9)

        // element 1: strict array ["y", null]
        baos.write(10)
        baos.write(java.nio.ByteBuffer.allocate(4).putInt(2).array())
        val yb = "y".toByteArray(Charsets.UTF_8)
        baos.write(2)
        baos.write((yb.size shr 8) and 0xff)
        baos.write(yb.size and 0xff)
        baos.write(yb)
        baos.write(5) // null

        val parser = Amf0Parser(baos.toByteArray())
        val arr = parser.readAmf0() as? List<*>
        assertNotNull(arr)
        assertEquals(2, arr!!.size)
        val obj0 = arr[0] as? Map<*, *>
        assertNotNull(obj0)
        val aVal = obj0!!["a"] as? List<*>
        assertNotNull(aVal)
        assertEquals(2, aVal!!.size)
        assertEquals(1.1, aVal[0] as Double, 0.0001)
        val innerObj = aVal[1] as? Map<*, *>
        assertNotNull(innerObj)
        assertEquals("x", innerObj!!["b"])
        val arr1 = arr[1] as? List<*>
        assertNotNull(arr1)
        assertEquals("y", arr1!![0])
        assertNull(arr1[1])
    }

    @Test
    fun testNestedEmptyInnerObject() {
        // Object { "outer": { } }
        val baos = java.io.ByteArrayOutputStream()
        baos.write(3) // outer object
        val kout = "outer".toByteArray(Charsets.UTF_8)
        baos.write((kout.size shr 8) and 0xff)
        baos.write(kout.size and 0xff)
        baos.write(kout)
        // inner object (empty): marker 3 then immediate end marker
        baos.write(3)
        baos.write(0)
        baos.write(0)
        baos.write(9)
        // end outer object
        baos.write(0)
        baos.write(0)
        baos.write(9)

        val parser = Amf0Parser(baos.toByteArray())
        val map = parser.readAmf0() as? Map<*, *>
        assertNotNull(map)
        val inner = map!!["outer"] as? Map<*, *>
        assertNotNull(inner)
        assertTrue(inner!!.isEmpty())
    }

    @Test
    fun testLongMultiByteStringWithSurroundingValues() {
        // Build: number(2.2), long-string(multi-byte >65k), number(3.3)
        val baos = java.io.ByteArrayOutputStream()
        // number 2.2
        baos.write(0)
        baos.write(java.nio.ByteBuffer.allocate(8).putDouble(2.2).array())

        // long string marker
        baos.write(12)
        val payload = "к".repeat(40000) // Cyrillic small ka (2 bytes in UTF-8) -> ~80k bytes
        val pb = payload.toByteArray(Charsets.UTF_8)
        baos.write(java.nio.ByteBuffer.allocate(4).putInt(pb.size).array())
        baos.write(pb)

        // trailing number 3.3
        baos.write(0)
        baos.write(java.nio.ByteBuffer.allocate(8).putDouble(3.3).array())

        val parser = Amf0Parser(baos.toByteArray())
        val n1 = parser.readAmf0() as? Double
        assertNotNull(n1)
        assertEquals(2.2, n1!!, 0.0001)
        val s = parser.readAmf0() as? String
        assertNotNull(s)
        assertEquals(payload.length, s!!.length)
        val n2 = parser.readAmf0() as? Double
        assertNotNull(n2)
        assertEquals(3.3, n2!!, 0.0001)
    }
}
