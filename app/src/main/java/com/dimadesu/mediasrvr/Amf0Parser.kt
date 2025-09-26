package com.dimadesu.mediasrvr

class Amf0Parser(private val data: ByteArray) {
    var pos = 0

    private fun readUInt16(): Int {
        val v = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
        pos += 2
        return v
    }

    private fun readUInt32(): Long {
        val v = ((data[pos].toLong() and 0xffL) shl 24) or
                ((data[pos + 1].toLong() and 0xffL) shl 16) or
                ((data[pos + 2].toLong() and 0xffL) shl 8) or
                (data[pos + 3].toLong() and 0xffL)
        pos += 4
        return v
    }

    fun readAmf0(): Any? {
        if (pos >= data.size) return null
        val marker = data[pos++].toInt() and 0xff
        return when (marker) {
            0x11 -> { // AMF3 data (object/value encoded with AMF3)
                // Delegate to minimal AMF3 parser. It will consume bytes starting at current pos.
                val amf3 = Amf3Parser(data, pos)
                val v = amf3.readAmf3()
                pos += amf3.bytesRead
                v
            }
            0 -> { // number (8 bytes)
                val b = data.copyOfRange(pos, pos + 8)
                pos += 8
                java.nio.ByteBuffer.wrap(b).double
            }
            1 -> { // boolean
                val v = data[pos++].toInt() != 0
                v
            }
            2 -> { // string (short)
                val len = readUInt16()
                val s = String(data, pos, len, Charsets.UTF_8)
                pos += len
                s
            }
            3 -> { // object
                val map = mutableMapOf<String, Any?>()
                while (true) {
                    if (pos + 2 > data.size) break
                    val keyLen = readUInt16()
                    if (keyLen == 0) {
                        if (pos >= data.size) break
                        val endMarker = data[pos++].toInt() and 0xff
                        if (endMarker == 9) break
                        else continue
                    }
                    val key = String(data, pos, keyLen, Charsets.UTF_8)
                    pos += keyLen
                    val v = readAmf0()
                    map[key] = v
                }
                map
            }
            8 -> { // ECMA array
                if (pos + 4 > data.size) return null
                val _count = readUInt32()
                val map = mutableMapOf<String, Any?>()
                while (true) {
                    if (pos + 2 > data.size) break
                    val keyLen = readUInt16()
                    if (keyLen == 0) {
                        if (pos >= data.size) break
                        val endMarker = data[pos++].toInt() and 0xff
                        if (endMarker == 9) break
                        else continue
                    }
                    val key = String(data, pos, keyLen, Charsets.UTF_8)
                    pos += keyLen
                    val v = readAmf0()
                    map[key] = v
                }
                map
            }
            10 -> { // strict array
                if (pos + 4 > data.size) return null
                val count = readUInt32().toInt()
                val list = mutableListOf<Any?>()
                for (i in 0 until count) {
                    list.add(readAmf0())
                }
                list
            }
            12 -> { // long string
                if (pos + 4 > data.size) return null
                val len = readUInt32().toInt()
                if (pos + len > data.size) return null
                val s = String(data, pos, len, Charsets.UTF_8)
                pos += len
                s
            }
            5, 6 -> null // null or undefined
            else -> null
        }
    }

    fun dumpRemaining(): List<Any?> {
        val rem = data.copyOfRange(pos, data.size)
        val p = Amf0Parser(rem)
        val out = mutableListOf<Any?>()
        while (p.pos < p.data.size) {
            try {
                out.add(p.readAmf0())
            } catch (e: Exception) {
                out.add("<error:${e.message}>")
                break
            }
        }
        return out
    }

    /**
     * Peek the next AMF0 marker byte (without advancing). Returns null if no more data.
     */
    fun nextMarker(): Int? {
        return if (pos < data.size) data[pos].toInt() and 0xff else null
    }
}
