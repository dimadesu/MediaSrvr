package com.dimadesu.mediasrvr

object NodeCoreAmf {
    val rtmpCmdCode: Map<String, List<String>> = mapOf(
        "_result" to listOf("transId", "cmdObj", "info"),
        "_error" to listOf("transId", "cmdObj", "info", "streamId"),
        "onStatus" to listOf("transId", "cmdObj", "info"),
        "releaseStream" to listOf("transId", "cmdObj", "streamName"),
        "getStreamLength" to listOf("transId", "cmdObj", "streamId"),
        "getMovLen" to listOf("transId", "cmdObj", "streamId"),
        "FCPublish" to listOf("transId", "cmdObj", "streamName"),
        "FCUnpublish" to listOf("transId", "cmdObj", "streamName"),
        "FCSubscribe" to listOf("transId", "cmdObj", "streamName"),
        "onFCPublish" to listOf("transId", "cmdObj", "info"),
        "connect" to listOf("transId", "cmdObj", "args"),
        "call" to listOf("transId", "cmdObj", "args"),
        "createStream" to listOf("transId", "cmdObj"),
        "close" to listOf("transId", "cmdObj"),
        "play" to listOf("transId", "cmdObj", "streamName", "start", "duration", "reset"),
        "play2" to listOf("transId", "cmdObj", "params"),
        "deleteStream" to listOf("transId", "cmdObj", "streamId"),
        "closeStream" to listOf("transId", "cmdObj"),
        "receiveAudio" to listOf("transId", "cmdObj", "bool"),
        "receiveVideo" to listOf("transId", "cmdObj", "bool"),
        "publish" to listOf("transId", "cmdObj", "streamName", "type"),
        "seek" to listOf("transId", "cmdObj", "ms"),
        "pause" to listOf("transId", "cmdObj", "pause", "ms")
    )

    val rtmpDataCode: Map<String, List<String>> = mapOf(
        "@setDataFrame" to listOf("method", "dataObj"),
        "onFI" to listOf("info"),
        "onMetaData" to listOf("dataObj"),
        "|RtmpSampleAccess" to listOf("bool1", "bool2")
    )

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    fun amf0encUString(str: String): ByteArray {
        val data = str.toByteArray(Charsets.UTF_8)
        val header = ByteArray(2)
        header[0] = ((data.size shr 8) and 0xff).toByte()
        header[1] = (data.size and 0xff).toByte()
        return concat(header, data)
    }

    fun amf0encString(str: String): ByteArray {
        val bs = str.toByteArray(Charsets.UTF_8)
        val out = ByteArray(3 + bs.size)
        out[0] = 0x02 // string marker
        out[1] = ((bs.size shr 8) and 0xff).toByte()
        out[2] = (bs.size and 0xff).toByte()
        System.arraycopy(bs, 0, out, 3, bs.size)
        return out
    }

    fun amf0encNumber(num: Double): ByteArray {
        val out = ByteArray(9)
        out[0] = 0x00
        val bb = java.nio.ByteBuffer.allocate(8).putDouble(num).array()
        System.arraycopy(bb, 0, out, 1, 8)
        return out
    }

    fun amf0encBool(b: Boolean): ByteArray = byteArrayOf(0x01, if (b) 1 else 0)

    fun amf0encNull(): ByteArray = byteArrayOf(0x05)

    fun amf0encUndefined(): ByteArray = byteArrayOf(0x06)

    fun amf0encObject(o: Map<String, Any?>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(0x03)
        for ((k, v) in o) {
            baos.write(amf0encUString(k))
            baos.write(amf0EncodeOne(v))
        }
        // object end marker
        baos.write(0)
        baos.write(0)
        baos.write(9)
        return baos.toByteArray()
    }

    fun amf0encLongString(str: String): ByteArray {
        val bs = str.toByteArray(Charsets.UTF_8)
        val out = ByteArray(5 + bs.size)
        out[0] = 0x0C
        out[1] = ((bs.size shr 24) and 0xff).toByte()
        out[2] = ((bs.size shr 16) and 0xff).toByte()
        out[3] = ((bs.size shr 8) and 0xff).toByte()
        out[4] = (bs.size and 0xff).toByte()
        System.arraycopy(bs, 0, out, 5, bs.size)
        return out
    }

    fun amf0EncodeOne(o: Any?): ByteArray {
        return when (o) {
            null -> amf0encNull()
            is Double -> amf0encNumber(o)
            is Int -> amf0encNumber(o.toDouble())
            is String -> amf0encString(o)
            is Boolean -> amf0encBool(o)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                amf0encObject(o as Map<String, Any?>)
            }
            is List<*> -> {
                // encode as strict array
                val baos = java.io.ByteArrayOutputStream()
                baos.write(0x0A)
                val count = o.size
                val cnt = ByteArray(4)
                cnt[0] = ((count shr 24) and 0xff).toByte()
                cnt[1] = ((count shr 16) and 0xff).toByte()
                cnt[2] = ((count shr 8) and 0xff).toByte()
                cnt[3] = (count and 0xff).toByte()
                baos.write(cnt)
                for (e in o) baos.write(amf0EncodeOne(e))
                return baos.toByteArray()
            }
            else -> amf0encString(o.toString())
        }
    }

    fun amf0Encode(values: List<Any?>): ByteArray {
        var buf = ByteArray(0)
        for (v in values) buf = concat(buf, amf0EncodeOne(v))
        return buf
    }

    fun encodeAmf0Cmd(opt: Map<String, Any?>): ByteArray {
        val cmd = opt["cmd"] as? String ?: throw IllegalArgumentException("cmd missing")
        var data = amf0EncodeOne(cmd)
        val fields = rtmpCmdCode[cmd]
        if (fields != null) {
            for (n in fields) {
                if (opt.containsKey(n)) data = concat(data, amf0EncodeOne(opt[n]))
            }
        }
        return data
    }

    fun encodeAmf3Cmd(opt: Map<String, Any?>): ByteArray {
        val cmd = opt["cmd"] as? String ?: throw IllegalArgumentException("cmd missing")
        var data = amf0EncodeOne(cmd)
        val fields = rtmpCmdCode[cmd]
        if (fields != null) {
            for (n in fields) {
                if (opt.containsKey(n)) {
                    val e = Amf3Encoder()
                    e.writeValue(opt[n])
                    data = concat(data, e.toByteArray())
                }
            }
        }
        return data
    }
}
