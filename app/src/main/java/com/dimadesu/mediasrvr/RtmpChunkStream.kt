package com.dimadesu.mediasrvr

/**
 * Small per-chunk-stream helper that owns HeaderState and InPacket for a given chunk stream (cid).
 * The class exposes methods used by the session read loop to update header state, append bytes,
 * and deliver a completed message back to the owning session.
 */
class RtmpChunkStream(val cid: Int, private val session: RtmpSession) {
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

    fun deliverIfComplete(timestamp: Int) {
        if (!isComplete()) return
        try {
            // hand back to session for processing
            session.processCompletedPacket(pkt.type, pkt.streamId, timestamp, pkt.buffer)
        } catch (e: Exception) {
            // session will log
        }
        // reset packet state so subsequent messages will allocate fresh buffers
        pkt = InPacket(0, 0, 0)
    }
}
