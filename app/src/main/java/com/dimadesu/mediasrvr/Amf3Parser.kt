package com.dimadesu.mediasrvr

/**
 * Minimal AMF3 parser sufficient for common NetConnection/NetStream uses in RTMP connect/publish flows.
 * This is intentionally small: it supports Undefined(0), Null(1), False/True(2/3), Integer(4), Double(5),
 * String(6), Array(7), Object(8) in their simplest forms and will fallback to raw bytes for complex refs.
 * It tracks bytesRead so callers can advance the parent buffer position.
 */
class Amf3Parser(private val data: ByteArray, private var startPos: Int = 0) {
    var pos = startPos
    var bytesRead = 0

    // Reference tables
    private val stringRefs = mutableListOf<String>()
    private val objectRefs = mutableListOf<Any?>()
    private val traitRefs = mutableListOf<Trait>()

    private data class Trait(
        val typeName: String,
        val propNames: List<String>,
        val externalizable: Boolean,
        val dynamic: Boolean
    )

    private fun readU29(): Int {
        var value = 0
        var b: Int
        var i = 0
        do {
            if (pos >= data.size) throw IndexOutOfBoundsException("readU29 out of range")
            b = data[pos++].toInt() and 0xff
            if (i < 3) {
                value = (value shl 7) or (b and 0x7f)
            } else {
                value = (value shl 8) or b
                break
            }
            i++
        } while ((b and 0x80) != 0)
        return value
    }

    /**
     * Read a U29 integer and convert to signed 29-bit value (AMF3 U29S interpretation).
     * This is useful when a protocol field expects signed interpretation (negative values).
     *
     * Note: the AMF3 integer marker (0x04) in this parser currently returns the raw U29 value
     * to preserve full 29-bit range for callers that expect unsigned-like behavior. Use
     * this function when you need the signed conversion (U29S) semantics.
     */
    internal fun readU29Signed(): Int {
        val raw = readU29()
        // convert to signed 29-bit
        return if (raw and 0x10000000 != 0) raw - 0x20000000 else raw
    }

    private fun readDouble(): Double {
        if (pos + 8 > data.size) throw IndexOutOfBoundsException("readDouble out of range")
        val b = data.copyOfRange(pos, pos + 8)
        pos += 8
        return java.nio.ByteBuffer.wrap(b).double
    }

    private fun readAmf3String(): String {
        val header = readU29()
        val isRef = (header and 1) == 0
        val value = header shr 1
        if (isRef) {
            val idx = value
            if (idx < 0 || idx >= stringRefs.size) return "<str-ref:$idx>"
            return stringRefs[idx]
        }
        if (value == 0) {
            stringRefs.add("")
            return ""
        }
        val len = value
        if (pos + len > data.size) throw IndexOutOfBoundsException("readString len out of range")
        val s = String(data, pos, len, Charsets.UTF_8)
        pos += len
        stringRefs.add(s)
        return s
    }

    fun readAmf3(): Any? {
        if (pos >= data.size) return null
        val marker = data[pos++].toInt() and 0xff
        when (marker) {
            0x00 -> { bytesRead = pos - startPos; return null } // undefined
            0x01 -> { bytesRead = pos - startPos; return null } // null
            0x02 -> { bytesRead = pos - startPos; return false }
            0x03 -> { bytesRead = pos - startPos; return true }
            0x04 -> { // integer (U29)
                // Use raw U29 here to match encoder/tests which expect the full 29-bit value.
                val v = readU29()
                bytesRead = pos - startPos
                return v
            }
            0x05 -> { // double
                val d = readDouble()
                bytesRead = pos - startPos
                return d
            }
            0x06 -> { // string
                val s = readAmf3String()
                bytesRead = pos - startPos
                return s
            }
            0x07 -> { // xml doc - treat as string
                val s = readAmf3String()
                bytesRead = pos - startPos
                return s
            }
            0x08 -> { // object
                val u29o = readU29()
                if ((u29o and 1) == 0) {
                    // object reference
                    val refIdx = u29o shr 1
                    val out = if (refIdx in objectRefs.indices) objectRefs[refIdx] else "<obj-ref:$refIdx>"
                    bytesRead = pos - startPos
                    return out
                }
                var u = u29o shr 1
                // trait reference?
                if ((u and 1) == 0) {
                    val traitRefIdx = u shr 1
                    val trait = if (traitRefIdx in traitRefs.indices) traitRefs[traitRefIdx] else null
                    // create object placeholder and add to refs before populating (to allow circular refs)
                    val obj = mutableMapOf<String, Any?>()
                    objectRefs.add(obj)
                    if (trait != null) {
                        // sealed properties
                        for (prop in trait.propNames) {
                            val value = readAmf3()
                            obj[prop] = value
                        }
                        if (trait.dynamic) {
                            while (true) {
                                val key = readAmf3String()
                                if (key.isEmpty()) break
                                val v = readAmf3()
                                obj[key] = v
                            }
                        }
                    }
                    bytesRead = pos - startPos
                    return obj
                }
                // inline traits
                u = u shr 1
                val externalizable = (u and 1) != 0
                val dynamic = (u and 2) != 0
                val propCount = u shr 2
                val typeName = readAmf3String()
                val propNames = mutableListOf<String>()
                for (i in 0 until propCount) {
                    propNames.add(readAmf3String())
                }
                val trait = Trait(typeName, propNames.toList(), externalizable, dynamic)
                traitRefs.add(trait)
                // create object placeholder and add to refs before reading values
                val obj = mutableMapOf<String, Any?>()
                objectRefs.add(obj)
                if (externalizable) {
                    // cannot parse externalizable here; read as placeholder
                    obj["<externalizable>"] = "<externalizable:${trait.typeName}>"
                    bytesRead = pos - startPos
                    return obj
                }
                // sealed properties
                for (prop in trait.propNames) {
                    val v = readAmf3()
                    obj[prop] = v
                }
                if (trait.dynamic) {
                    while (true) {
                        val key = readAmf3String()
                        if (key.isEmpty()) break
                        val v = readAmf3()
                        obj[key] = v
                    }
                }
                bytesRead = pos - startPos
                return obj
            }
            0x09 -> { // array
                val header = readU29()
                if ((header and 1) == 0) {
                    val refIdx = header shr 1
                    val out = if (refIdx in objectRefs.indices) objectRefs[refIdx] else listOf("<arr-ref:$refIdx>")
                    bytesRead = pos - startPos
                    return out
                }
                val denseCount = header shr 1
                val assoc = mutableMapOf<String, Any?>()
                while (true) {
                    val key = readAmf3String()
                    if (key.isEmpty()) break
                    val v = readAmf3()
                    assoc[key] = v
                }
                val list = mutableListOf<Any?>()
                for (i in 0 until denseCount) {
                    list.add(readAmf3())
                }
                // store in object refs
                val out = mutableMapOf<String, Any?>()
                out["assoc"] = assoc
                out["dense"] = list
                objectRefs.add(out)
                bytesRead = pos - startPos
                return out
            }
            else -> {
                // Unknown marker
                val remain = data.copyOfRange(pos, kotlin.math.min(data.size, pos + 64))
                bytesRead = pos - startPos
                return "<amf3-unknown:$marker:${remain.joinToString(",") { "%02x".format(it) }}>"
            }
        }
    }
}
