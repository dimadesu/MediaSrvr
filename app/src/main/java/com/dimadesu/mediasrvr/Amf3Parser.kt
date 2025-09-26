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

    private fun readU29(): Int {
        // U29 variable length unsigned integer (up to 29 bits)
        var value = 0
        var b: Int
        var i = 0
        do {
            b = data[pos++].toInt() and 0xff
            i++
            if (i < 4) {
                value = (value shl 7) or (b and 0x7f)
            } else {
                value = (value shl 8) or b
                break
            }
        } while ((b and 0x80) != 0)
        return value
    }

    private fun readUInt16(): Int {
        val v = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
        pos += 2
        return v
    }

    private fun readDouble(): Double {
        val b = data.copyOfRange(pos, pos + 8)
        pos += 8
        return java.nio.ByteBuffer.wrap(b).double
    }

    private fun readString(): String {
        val header = readU29()
        val isRef = (header and 1) == 0
        val len = header shr 1
        if (isRef) {
            // reference indexes not supported in this minimal parser
            return "<amf3-string-ref>"
        }
        val s = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun readAmf3(): Any? {
        if (pos >= data.size) return null
        val marker = data[pos++].toInt() and 0xff
        when (marker) {
            0x00 -> return null // undefined
            0x01 -> return null // null
            0x02 -> return false
            0x03 -> return true
            0x04 -> { // integer (U29S)
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
                val s = readString()
                bytesRead = pos - startPos
                return s
            }
            0x07 -> { // xml doc - treat as string
                val s = readString()
                bytesRead = pos - startPos
                return s
            }
            0x08 -> { // typed object / object
                // minimal: read trait header and then string keys and values until done
                val header = readU29()
                val isRef = (header and 1) == 0
                if (isRef) {
                    bytesRead = pos - startPos
                    return "<amf3-object-ref>"
                }
                val externalizable = (header and 0x02) != 0
                val dynamic = (header and 0x08) != 0
                val propCount = header shr 4
                val typeName = readString()
                val map = mutableMapOf<String, Any?>()
                for (i in 0 until propCount) {
                    val key = readString()
                    val value = readAmf3()
                    map[key] = value
                }
                if (dynamic) {
                    while (true) {
                        val key = readString()
                        if (key.isEmpty()) break
                        val value = readAmf3()
                        map[key] = value
                    }
                }
                bytesRead = pos - startPos
                return map
            }
            0x09 -> { // array
                val header = readU29()
                val isRef = (header and 1) == 0
                if (isRef) {
                    bytesRead = pos - startPos
                    return listOf<Any?>("<amf3-array-ref>")
                }
                val denseCount = header shr 1
                val assoc = mutableMapOf<String, Any?>()
                // associative part
                while (true) {
                    val key = readString()
                    if (key.isEmpty()) break
                    val v = readAmf3()
                    assoc[key] = v
                }
                val list = mutableListOf<Any?>()
                for (i in 0 until denseCount) {
                    list.add(readAmf3())
                }
                // merge associative into list? Return as pair-like structure
                val out = mutableMapOf<String, Any?>()
                out["assoc"] = assoc
                out["dense"] = list
                bytesRead = pos - startPos
                return out
            }
            else -> {
                // Unknown/unsupported marker - return a hex preview of next bytes
                val remain = data.copyOfRange(pos, kotlin.math.min(data.size, pos + 64))
                bytesRead = pos - startPos
                return "<amf3-unknown:$marker:${remain.joinToString(",") { "%02x".format(it) }}>"
            }
        }
    }
}
