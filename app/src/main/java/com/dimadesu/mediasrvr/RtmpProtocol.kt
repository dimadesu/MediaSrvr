package com.dimadesu.mediasrvr

/**
 * Small helpers for RTMP chunk/message state used by RtmpSession.
 */
data class HeaderState(var timestamp: Int, var length: Int, var type: Int, var streamId: Int)

class InPacket(var totalLength: Int, var type: Int, var streamId: Int) {
    var buffer: ByteArray = ByteArray(totalLength)
    var received: Int = 0
    var bytesReadSinceStart: Int = 0
    var lastAck: Int = 0
    var ackWindow: Int = 0
}

object RtmpTypes {
    const val TYPE_CHUNK_SIZE = 1
    const val TYPE_ACK = 3
    const val TYPE_USER = 4
    const val TYPE_WINDOW_ACK_SIZE = 5
    const val TYPE_SET_PEER_BW = 6
    const val TYPE_AUDIO = 8
    const val TYPE_VIDEO = 9
    const val TYPE_AMF0 = 20
}
