package com.dimadesu.mediasrvr

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class Amf3ParserTest {

    private fun writeU29(out: ByteArrayOutputStream, value: Int) {
        var v = value
        if (v < 0x80) {
            out.write(v)
        } else if (v < 0x4000) {
            out.write(((v shr 7) and 0x7f) or 0x80)
            out.write(v and 0x7f)
        } else if (v < 0x200000) {
            out.write(((v shr 14) and 0x7f) or 0x80)
            out.write(((v shr 7) and 0x7f) or 0x80)
            out.write(v and 0x7f)
        } else {
            out.write(((v shr 22) and 0x7f) or 0x80)
            out.write(((v shr 15) and 0x7f) or 0x80)
            out.write(((v shr 8) and 0x7f) or 0x80)
            out.write(v and 0xff)
        }
    }

    private fun writeAmf3StringInline(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val header = (b.size shl 1) or 1
        writeU29(out, header)
        if (b.isNotEmpty()) out.write(b)
    }

    private fun writeAmf3StringValue(out: ByteArrayOutputStream, s: String) {
        out.write(0x06) // AMF3 string marker
        val b = s.toByteArray(Charsets.UTF_8)
        val header = (b.size shl 1) or 1
        writeU29(out, header)
        if (b.isNotEmpty()) out.write(b)
    }

    private fun writeAmf3Integer(out: ByteArrayOutputStream, v: Int) {
        out.write(0x04)
        writeU29(out, v and 0x1fffffff)
    }

    @Test
    fun testStringRefs() {
        val out = ByteArrayOutputStream()
        // first: marker + inline string "hello"
        out.write(0x06)
        writeAmf3StringInline(out, "hello")
        // second: string reference to index 0 -> as AMF3 string value, marker then header with ref
        out.write(0x06)
        // ref header: (index << 1) | 0 -> low bit 0 signals reference
        writeU29(out, (0 shl 1))

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val a = p.readAmf3() as? String
        assertEquals("hello", a)
        val b = p.readAmf3() as? String
        assertEquals("hello", b)
    }

    @Test
    fun testObjectTraitInlineAndRef() {
        val out = ByteArrayOutputStream()
        // object 1: marker
        out.write(0x08)
        // U29O: inline trait with propCount=1, externalizable=0, dynamic=0
        // computed raw (see parser expectations) -> 19 (0x13)
        writeU29(out, 0x13)
        // typeName: empty string (AMF3 string inline)
        writeAmf3StringInline(out, "")
        // prop name 'a'
        writeAmf3StringInline(out, "a")
        // value for 'a' -> integer 42
        writeAmf3Integer(out, 42)

        // object 2: reference to object 0
        out.write(0x08)
        // object ref header: (refIndex << 1) with low bit 0
        writeU29(out, (0 shl 1))

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val obj1 = p.readAmf3() as? Map<*, *>
        assertNotNull(obj1)
        assertEquals(42, obj1!!["a"])
        val obj2 = p.readAmf3()
        // obj2 should be the same instance (object reference)
        assertTrue(obj2 === obj1 || (obj2 as? Map<*, *>)?.get("a") == 42)
    }

    @Test
    fun testArrayAssocAndDense() {
        val out = ByteArrayOutputStream()
        out.write(0x09)
        // header: denseCount=1 -> (1<<1)|1 = 3
        writeU29(out, 3)
        // associative key 'k'
        writeAmf3StringInline(out, "k")
        // value for 'k' -> integer 7
        writeAmf3Integer(out, 7)
        // end assoc: empty string
        writeAmf3StringInline(out, "")
        // dense element -> integer 9
        writeAmf3Integer(out, 9)

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val arr = p.readAmf3() as? Map<*, *>
        assertNotNull(arr)
        val assoc = arr!!["assoc"] as? Map<*, *>
        val dense = arr["dense"] as? List<*>
        assertNotNull(assoc)
        assertNotNull(dense)
        assertEquals(7, assoc!!["k"])
        assertEquals(9, dense!![0])
    }

    @Test
    fun testExternalizableTraitPlaceholder() {
        val out = ByteArrayOutputStream()
        out.write(0x08)
        // U29O for inline trait externalizable=true, propCount=0, dynamic=false
        // computed raw -> 7
        writeU29(out, 7)
        // typeName: 'Ext'
        writeAmf3StringInline(out, "Ext")
        // externalizable payload would follow, but our parser records a placeholder and returns

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val obj = p.readAmf3() as? Map<*, *>
        assertNotNull(obj)
        assertTrue(obj!!.containsKey("<externalizable>"))
    }

    @Test
    fun testTraitRefs() {
        // Build two objects with same trait (same props) and ensure trait refs are used by parser
        val out = ByteArrayOutputStream()
        // object 1
        out.write(0x08)
        writeU29(out, 0x13) // inline trait propCount=1
        writeAmf3StringInline(out, "")
        writeAmf3StringInline(out, "a")
        writeAmf3Integer(out, 1)
        // object 2 uses trait ref (we simulate: write object marker then trait ref header)
        out.write(0x08)
        // trait ref: (traitIndex<<2)|1 -> trait index 0 -> header 1
        writeU29(out, 1)
        // value for sealed prop 'a'
        writeAmf3Integer(out, 2)

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val o1 = p.readAmf3() as? Map<*, *>
        val o2 = p.readAmf3() as? Map<*, *>
        assertNotNull(o1)
        assertNotNull(o2)
        assertEquals(1, o1!!["a"])
        assertEquals(2, o2!!["a"])
    }

    @Test
    fun testStringRefsEncodeDecode() {
        val enc = Amf3Encoder()
        enc.writeValue("dup")
        enc.writeValue("dup")
        val data = enc.toByteArray()
        val p = Amf3Parser(data, 0)
        val s1 = p.readAmf3() as String
        val s2 = p.readAmf3() as String
        assertEquals("dup", s1)
        assertEquals("dup", s2)
    }

    @Test
    fun testNestedCircularGraphIdentity() {
        val enc = Amf3Encoder()
        val a = mutableMapOf<String, Any?>()
        val b = mutableMapOf<String, Any?>()
        a["b"] = b
        b["a"] = a
        enc.writeValue(a)
        val data = enc.toByteArray()
        val p = Amf3Parser(data, 0)
        val outA = p.readAmf3() as? Map<*, *>
        assertNotNull(outA)
        val outB = outA!!["b"] as? Map<*, *>
        assertNotNull(outB)
        val outA2 = outB!!["a"]
        // outA2 should be same instance as outA
        assertTrue(outA2 === outA)
    }

    @Test
    fun testExternalizableEncoding() {
        // Build an externalizable inline trait with type name
        val out = ByteArrayOutputStream()
        out.write(0x08)
        // U29O for inline trait externalizable=true, propCount=0, dynamic=false -> 7
        writeU29(out, 7)
        writeAmf3StringInline(out, "MyExt")
        // no payload for externalizable in this minimal test

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val obj = p.readAmf3() as? Map<*, *>
        assertNotNull(obj)
        val ext = obj!!["<externalizable>"] as? String
        assertTrue(ext?.contains("MyExt") == true)
    }

    @Test
    fun testLargeU29Integer() {
        val out = ByteArrayOutputStream()
        // encode integer marker and a large 29-bit value near the top: 0x1fffffff
        out.write(0x04)
        writeU29(out, 0x1fffffff)
        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val v = p.readAmf3() as Int
        assertEquals(0x1fffffff, v)
    }

    @Test
    fun testU29SignedConversion() {
        // We'll encode a U29 raw value whose signed interpretation should be negative.
        val out = java.io.ByteArrayOutputStream()
        // use integer marker
        out.write(0x04)
        // raw U29 with top bit set: 0x10000000 (this is bit 28 set) -> as signed should be -0x10000000
        // but we craft a value that when converted gives a negative small value: take raw = 0x1ffffff0
        val raw = 0x1ffffff0
        // helper to write U29 as in this test file
        fun writeU29Local(o: java.io.ByteArrayOutputStream, v: Int) {
            var vv = v
            if (vv < 0x80) {
                o.write(vv)
            } else if (vv < 0x4000) {
                o.write(((vv shr 7) and 0x7f) or 0x80)
                o.write(vv and 0x7f)
            } else if (vv < 0x200000) {
                o.write(((vv shr 14) and 0x7f) or 0x80)
                o.write(((vv shr 7) and 0x7f) or 0x80)
                o.write(vv and 0x7f)
            } else {
                o.write(((vv shr 22) and 0x7f) or 0x80)
                o.write(((vv shr 15) and 0x7f) or 0x80)
                o.write(((vv shr 8) and 0x7f) or 0x80)
                o.write(vv and 0xff)
            }
        }
        writeU29Local(out, raw)
        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        // read marker/byte
        val marker = data[0].toInt() and 0xff
        assertEquals(0x04, marker)
        // advance parser past marker to use readU29Signed directly
        p.pos = 1
        val signed = p.readU29Signed()
        // manual expected signed conversion
        val expected = if (raw and 0x10000000 != 0) raw - 0x20000000 else raw
        assertEquals(expected, signed)
    }

    @Test
    fun testTraitRefIndexing() {
        val out = ByteArrayOutputStream()
        // object 1 inline trait with prop 'x'
        out.write(0x08)
        writeU29(out, 0x13)
        writeAmf3StringInline(out, "")
        writeAmf3StringInline(out, "x")
        writeAmf3Integer(out, 9)
        // object 2: inline trait different props 'y'
        out.write(0x08)
        writeU29(out, 0x13)
        writeAmf3StringInline(out, "")
        writeAmf3StringInline(out, "y")
        writeAmf3Integer(out, 10)
        // object 3: reuse first trait by trait reference header (traitIdx 0 -> header 1)
        out.write(0x08)
        writeU29(out, 1)
        writeAmf3Integer(out, 11)

        val data = out.toByteArray()
        val p = Amf3Parser(data, 0)
        val o1 = p.readAmf3() as Map<*, *>
        val o2 = p.readAmf3() as Map<*, *>
        val o3 = p.readAmf3() as Map<*, *>
        assertEquals(9, o1["x"])
        assertEquals(10, o2["y"])
        assertEquals(11, o3["x"])
    }
}
