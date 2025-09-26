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
}
