package com.dimadesu.mediasrvr

/**
 * Small per-chunk-stream helper that owns HeaderState and InPacket for a given chunk stream (cid).
 * The class exposes methods used by the session read loop to update header state, append bytes,
 * and deliver a completed message back to the owning session.
 */
class RtmpChunkStream(val cid: Int, private val session: RtmpSession? = null, private val onComplete: ((Int, Int, Int, ByteArray) -> Unit)? = null) {
    val header: HeaderState = HeaderState(0, 0, 0, 0)
    var pkt: InPacket = InPacket(0, 0, 0)

    fun updateHeader(timestamp: Int, length: Int, type: Int, streamId: Int) {
        header.timestamp = timestamp
        header.length = length
        header.type = type
        header.streamId = streamId
        if (pkt.totalLength != length) {
            pkt = InPacket(length, type, streamId)
            pkt.buffer = ByteArray(maxOf(0, length))
            pkt.received = 0
        }
    }

    /**
     * Read the RTMP message header for the provided fmt from the input stream and update
     * this chunk stream's header/pkt accordingly. Returns the resolved timestamp value.
     *
     * The method expects the session to have already read the basic header byte(s) and
     * resolved fmt and cid. "prev" is the previous HeaderState for fmt=1/2/3 fallbacks.
     */
    fun readAndUpdateHeader(fmt: Int, input: java.io.DataInputStream, prev: HeaderState?): Int {
        var timestamp = 0
        var msgLength = 0
        var msgType = 0
        var msgStreamId = 0

        if (fmt == 0) {
            val buf = ByteArray(11)
            input.readFully(buf)
            timestamp = ((buf[0].toInt() and 0xff) shl 16) or
                    ((buf[1].toInt() and 0xff) shl 8) or
                    (buf[2].toInt() and 0xff)
            msgLength = ((buf[3].toInt() and 0xff) shl 16) or
                    ((buf[4].toInt() and 0xff) shl 8) or
                    (buf[5].toInt() and 0xff)
            msgType = buf[6].toInt() and 0xff
            msgStreamId = (buf[7].toInt() and 0xff) or
                    ((buf[8].toInt() and 0xff) shl 8) or
                    ((buf[9].toInt() and 0xff) shl 16) or
                    ((buf[10].toInt() and 0xff) shl 24)
        } else if (fmt == 1) {
            val buf = ByteArray(7)
            input.readFully(buf)
            timestamp = ((buf[0].toInt() and 0xff) shl 16) or
                    ((buf[1].toInt() and 0xff) shl 8) or
                    (buf[2].toInt() and 0xff)
            msgLength = ((buf[3].toInt() and 0xff) shl 16) or
                    ((buf[4].toInt() and 0xff) shl 8) or
                    (buf[5].toInt() and 0xff)
            msgType = buf[6].toInt() and 0xff
            if (prev != null) msgStreamId = prev.streamId
        } else if (fmt == 2) {
            val buf = ByteArray(3)
            input.readFully(buf)
            timestamp = ((buf[0].toInt() and 0xff) shl 16) or
                    ((buf[1].toInt() and 0xff) shl 8) or
                    (buf[2].toInt() and 0xff)
            if (prev != null) {
                msgLength = prev.length
                msgType = prev.type
                msgStreamId = prev.streamId
            }
        } else { // fmt == 3
            if (prev != null) {
                timestamp = prev.timestamp
                msgLength = prev.length
                msgType = prev.type
                msgStreamId = prev.streamId
            } else {
                throw java.io.IOException("fmt=3 with no previous header for cid=$cid")
            }
        }

        // extended timestamp
        if (timestamp == 0xffffff) {
            val ext = input.readInt()
            timestamp = ext
        }

        // update header state and packet allocation
        updateHeader(timestamp, msgLength, msgType, msgStreamId)
        return timestamp
    }

    fun getChunkDataSize(inChunkSize: Int): Int {
        val remain = pkt.totalLength - pkt.received
        if (remain <= 0) return 0
        val mod = pkt.received % inChunkSize
        val space = inChunkSize - mod
        return minOf(space, remain)
    }

    fun appendBytes(src: ByteArray, srcOffset: Int, len: Int) {
        if (len <= 0) return
        // ensure buffer size
        if (pkt.buffer.size < pkt.totalLength) pkt.buffer = pkt.buffer.copyOf(pkt.totalLength)
        System.arraycopy(src, srcOffset, pkt.buffer, pkt.received, len)
        pkt.received += len
    }

    fun isComplete(): Boolean = pkt.received >= pkt.totalLength

    fun getCompletedPacketIfComplete(timestamp: Int): CompletedPacket? {
        if (!isComplete()) return null
        val cp = CompletedPacket(pkt.type, pkt.streamId, timestamp, pkt.buffer, header.copy())
        // reset packet state so subsequent messages will allocate fresh buffers
        pkt = InPacket(0, 0, 0)
        return cp
    }

    fun deliverIfComplete(timestamp: Int) {
        val cp = getCompletedPacketIfComplete(timestamp) ?: return
        try {
            if (onComplete != null) {
                onComplete.invoke(cp.type, cp.streamId, cp.timestamp, cp.payload)
            } else {
                session?.processCompletedPacket(cp.type, cp.streamId, cp.timestamp, cp.payload)
            }
        } catch (e: Exception) {
            // session will log
        }
    }

    /**
     * Read extended timestamp (4 bytes) from the provided input stream if header.timestamp == 0xFFFFFF.
     * Returns the resolved timestamp value.
     */
    fun readExtendedTimestamp(input: java.io.DataInputStream): Int {
        if (header.timestamp == 0xFFFFFF) {
            val ext = input.readInt()
            header.timestamp = ext
            return ext
        }
        return header.timestamp
    }
}
