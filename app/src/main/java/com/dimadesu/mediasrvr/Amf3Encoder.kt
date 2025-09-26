package com.dimadesu.mediasrvr

import java.io.ByteArrayOutputStream

class Amf3Encoder {
    private val out = ByteArrayOutputStream()
    private val stringRefs = mutableListOf<String>()
    private val objectRefs = mutableListOf<Any?>()
    private val traitRefs = mutableListOf<Trait>()

    private data class Trait(val typeName: String, val propNames: List<String>, val externalizable: Boolean, val dynamic: Boolean)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeU29(v0: Int) {
        var v = v0
        when {
            v < 0x80 -> out.write(v)
            v < 0x4000 -> {
                out.write(((v shr 7) and 0x7f) or 0x80)
                out.write(v and 0x7f)
            }
            v < 0x200000 -> {
                out.write(((v shr 14) and 0x7f) or 0x80)
                out.write(((v shr 7) and 0x7f) or 0x80)
                out.write(v and 0x7f)
            }
            else -> {
                out.write(((v shr 22) and 0x7f) or 0x80)
                out.write(((v shr 15) and 0x7f) or 0x80)
                out.write(((v shr 8) and 0x7f) or 0x80)
                out.write(v and 0xff)
            }
        }
    }

    private fun writeAmf3StringInline(s: String) {
        // header: (len<<1)|1 ; if empty, still write header 1 (len 0) and add empty to refs
        val b = s.toByteArray(Charsets.UTF_8)
        val header = (b.size shl 1) or 1
        writeU29(header)
        if (b.isNotEmpty()) out.write(b)
        stringRefs.add(s)
    }

    private fun writeAmf3StringValue(s: String) {
        out.write(0x06)
        writeAmf3StringInline(s)
    }

    private fun writeAmf3Integer(v: Int) {
        out.write(0x04)
        writeU29(v and 0x1fffffff)
    }

    private fun writeAmf3Double(d: Double) {
        out.write(0x05)
        val bb = java.nio.ByteBuffer.allocate(8).putDouble(d).array()
        out.write(bb)
    }

    fun writeValue(v: Any?) {
        when (v) {
            null -> out.write(0x01)
            is Boolean -> out.write(if (v) 0x03 else 0x02)
            is Int -> writeAmf3Integer(v)
            is Double -> writeAmf3Double(v)
            is String -> writeAmf3StringValue(v)
            is Map<*, *> -> {
                // Safely convert keys to String (use toString() for non-string keys)
                val typed = mutableMapOf<String, Any?>()
                for ((kk, vv) in v) {
                    val key = when (kk) {
                        is String -> kk
                        null -> "null"
                        else -> kk.toString()
                    }
                    typed[key] = vv
                }
                writeAmf3Object(typed)
            }
            is List<*> -> writeAmf3Array(v)
            else -> {
                // fallback to string representation
                writeAmf3StringValue(v.toString())
            }
        }
    }

    private fun writeAmf3Object(map: Map<String, Any?>) {
        out.write(0x08)
        // check if object already referenced
        val existingIdx = objectRefs.indexOfFirst { it === map }
        if (existingIdx >= 0) {
            // write object reference
            writeU29(existingIdx shl 1)
            return
        }
        // inline trait: compute u29o as (propCount<<4) | (dynamic?8:0) | (externalizable?4:0) | 3
        val propNames = map.keys.toList()
        val propCount = propNames.size
    // detect dynamic/externalizable markers from special keys if provided
    val externalizable = map.containsKey("<externalizable>")
    val dynamic = map.containsKey("<dynamic>")
        val u29o = (propCount shl 4) or (if (dynamic) 8 else 0) or (if (externalizable) 4 else 0) or 3
        writeU29(u29o)
        // typeName: empty
        writeAmf3StringInline("")
        // property names
        for (pn in propNames) writeAmf3StringInline(pn)
        // register trait
        traitRefs.add(Trait("", propNames, externalizable, dynamic))
        // register object placeholder before writing values (allow references)
        val placeholder = mutableMapOf<String, Any?>()
        objectRefs.add(placeholder)
        if (externalizable) {
            // write externalizable placeholder as empty for now (server-side encoding might not produce externalizable normally)
            // leave placeholder entry
            placeholder["<externalizable>"] = map["<externalizable>"] ?: "<externalizable>"
            // no further payload written here
        } else {
            for (pn in propNames) {
                writeValue(map[pn])
                placeholder[pn] = map[pn]
            }
            if (dynamic) {
                // write dynamic members (keys not in propNames)
                for ((k, v) in map) {
                    if (k in propNames) continue
                    if (k == "<dynamic>" || k == "<externalizable>") continue
                    writeAmf3StringInline(k)
                    writeValue(v)
                    placeholder[k] = v
                }
                // end dynamic with empty string
                writeU29(1) // empty string header (0<<1 | 1)
            }
        }
    }

    private fun writeAmf3Array(list: List<*>) {
        out.write(0x09)
        val denseCount = list.size
        val header = (denseCount shl 1) or 1
        writeU29(header)
        // associative empty -> empty string
        writeAmf3StringInline("")
        for (e in list) writeValue(e)
    }
}
