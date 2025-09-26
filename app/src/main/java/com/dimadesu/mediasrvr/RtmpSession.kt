package com.dimadesu.mediasrvr

import kotlinx.coroutines.*
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class RtmpSession(
    val sessionId: Int,
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val serverScope: CoroutineScope,
    private val streams: MutableMap<String, RtmpSession>,
    private val waitingPlayers: MutableMap<String, MutableList<RtmpSession>>
) {
    private val TAGS = "RtmpSession"
    // set to true temporarily to emit full payload base64 dumps for AV/data messages
    private val debugDumpPayloads = false
    private var inChunkSize = 128
    private var outChunkSize = 128
    private var streamIdCounter = 1
    var lastStreamIdAllocated = 0

    var publishStreamId: Int = 0
    var playStreamId: Int = 0
    // ACK/window tracking
    private var ackWindowSize: Int = 0
    private var inBytesSinceStart: Long = 0L
    private var lastAckSent: Long = 0L

    // cached sequence headers
    var aacSequenceHeader: ByteArray? = null
    var avcSequenceHeader: ByteArray? = null
    var metaData: ByteArray? = null

    var appName: String = ""
    var publishStreamName: String? = null
    var isPublishing = false
    var publishStreamKey: String? = null

    // players subscribed to a stream name -> list of sessions
    val players = mutableSetOf<RtmpSession>()

    fun run(): Job {
        return serverScope.launch {
            try {
                // register in global state
                val remoteAddr = socket.inetAddress?.hostAddress ?: "unknown"
                RtmpServerState.registerSession(RtmpSessionInfo(sessionId, remoteAddr, false, null))
                // chunk reassembly: keep per-cid header state and buffers
                val headerStates = mutableMapOf<Int, HeaderState>()
                val inPackets = mutableMapOf<Int, InPacket>()

                var sessionBytes: Long = 0L
                while (!socket.isClosed) {
                    // read basic header
                    val header0 = input.readUnsignedByte()
                    var fmt = header0 shr 6
                    var cid = header0 and 0x3f
                    if (cid == 0) {
                        val b = input.readUnsignedByte()
                        cid = 64 + b
                    } else if (cid == 1) {
                        val b1 = input.readUnsignedByte()
                        val b2 = input.readUnsignedByte()
                        cid = 64 + b1 + (b2 shl 8)
                    }

                    // read message header according to fmt, using last header state when required
                    val prev = headerStates[cid]
                    var timestamp = 0
                    var msgLength = 0
                    var msgType = 0
                    var msgStreamId = 0

                    if (fmt == 0) {
                        // 11 bytes
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
                        if (prev != null) {
                            msgStreamId = prev.streamId
                        }
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
                            Log.e(TAGS, "fmt=3 with no previous header for cid=$cid")
                            continue
                        }
                    }

                    // extended timestamp
                    if (timestamp == 0xffffff) {
                        timestamp = input.readInt()
                    }

                    // diagnostic: log the parsed message header (fmt/cid/timestamp/length/type/streamId)
                    try {
                        Log.i(TAGS, "ChunkHdr fmt=$fmt cid=$cid ts=$timestamp len=$msgLength type=$msgType streamId=$msgStreamId")
                    } catch (e: Exception) { /* ignore */ }

                    // update header state
                    headerStates[cid] = HeaderState(timestamp, msgLength, msgType, msgStreamId)

                    // packet buffer for this cid
                    val pkt = inPackets.getOrPut(cid) { InPacket(msgLength, msgType, msgStreamId) }
                    // if new message, reset buffer
                    if (pkt.totalLength != msgLength) {
                        pkt.totalLength = msgLength
                        pkt.type = msgType
                        pkt.streamId = msgStreamId
                        pkt.buffer = ByteArray(msgLength)
                        pkt.received = 0
                    }

                    // read chunk payload (could be partial)
                    val toRead = minOf(inChunkSize - (pkt.received % inChunkSize), msgLength - pkt.received)
                    var got = 0
                    while (got < toRead) {
                        val r = input.read(pkt.buffer, pkt.received + got, toRead - got)
                        if (r <= 0) throw java.io.EOFException("Unexpected EOF while reading chunk payload")
                        got += r
                    }
                    pkt.received += got

                    // update ack counters (session-level)
                    inBytesSinceStart += got.toLong()
                    sessionBytes += got.toLong()
                    // update session-level bytes transferred in global state
                    RtmpServerState.updateSessionStats(sessionId, sessionBytes)
                    if (ackWindowSize > 0 && inBytesSinceStart - lastAckSent >= ackWindowSize) {
                        lastAckSent = inBytesSinceStart
                        // send acknowledgement (type 3)
                        val ackBuf = ByteArray(4)
                        val v = lastAckSent.toInt()
                        ackBuf[0] = ((v shr 24) and 0xff).toByte()
                        ackBuf[1] = ((v shr 16) and 0xff).toByte()
                        ackBuf[2] = ((v shr 8) and 0xff).toByte()
                        ackBuf[3] = (v and 0xff).toByte()
                        sendRtmpMessage(3, 0, ackBuf)
                    }

                    if (pkt.received >= pkt.totalLength) {
                        // full message received
                        val full = pkt.buffer
                        handleMessage(pkt.type, pkt.streamId, timestamp, full)
                        // reset for next message
                        inPackets.remove(cid)
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is java.net.SocketException,
                    is java.io.EOFException -> {
                        // Normal client disconnect - log at info level
                        Log.i(TAGS, "Client disconnected: ${e.message}")
                    }
                    else -> {
                        Log.e(TAGS, "session error", e)
                    }
                }
            } finally {
                cleanup()
            }
        }
    }

    private fun cleanup() {
        // if publishing, remove from streams
        publishStreamName?.let { key ->
            streams.remove(key)
            RtmpServerState.unregisterStream(key)
        }
        // remove from any players
        for ((_, s) in streams) {
            s.players.remove(this)
        }
        RtmpServerState.unregisterSession(sessionId)
        try { socket.close() } catch (ignored: Exception) {}
    }

    private fun handleMessage(type: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
        when (type) {
            1 -> { // set chunk size
                if (payload.size >= 4) {
                    val size = ((payload[0].toInt() and 0xff) shl 24) or
                            ((payload[1].toInt() and 0xff) shl 16) or
                            ((payload[2].toInt() and 0xff) shl 8) or
                            (payload[3].toInt() and 0xff)
                    inChunkSize = size
                    Log.i(TAGS, "Set inChunkSize=$inChunkSize")
                }
            }
            3 -> { // acknowledgement
                // ignore
            }
            5 -> { // window acknowledgement size
                if (payload.size >= 4) {
                    val size = ((payload[0].toInt() and 0xff) shl 24) or
                            ((payload[1].toInt() and 0xff) shl 16) or
                            ((payload[2].toInt() and 0xff) shl 8) or
                            (payload[3].toInt() and 0xff)
                    ackWindowSize = size
                    Log.i(TAGS, "Set ackWindowSize=$ackWindowSize")
                }
            }
            6 -> { // set peer bandwidth
                // ignore
            }
            4 -> { // user control event
                if (payload.size >= 2) {
                    val eventType = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                    when (eventType) {
                        0 -> { // StreamBegin
                        }
                        1 -> { // StreamEOF
                        }
                        3 -> { // Set buffer length
                        }
                        6 -> { // Ping (client -> server)
                            // payload: eventType(2) + time(4)
                            if (payload.size >= 6) {
                                val time = ((payload[2].toInt() and 0xff) shl 24) or ((payload[3].toInt() and 0xff) shl 16) or ((payload[4].toInt() and 0xff) shl 8) or (payload[5].toInt() and 0xff)
                                // respond with PingResponse (event type 7)
                                val resp = ByteArray(6)
                                resp[0] = 0
                                resp[1] = 7
                                resp[2] = ((time shr 24) and 0xff).toByte()
                                resp[3] = ((time shr 16) and 0xff).toByte()
                                resp[4] = ((time shr 8) and 0xff).toByte()
                                resp[5] = (time and 0xff).toByte()
                                sendRtmpMessage(4, 0, resp)
                            }
                        }
                    }
                }
            }
            8, 9 -> { // audio(8) or video(9)
                // diagnostic: always log incoming audio/video so we can see if frames arrive
                try {
                    val preview = payload.take(32).joinToString(" ") { String.format("%02x", it) }
                    Log.i(TAGS, "Received av type=$type ts=$timestamp len=${payload.size} isPublishing=$isPublishing publishName=$publishStreamName preview=$preview")
                    if (debugDumpPayloads) {
                        try {
                            val b64 = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
                            Log.i(TAGS, "AV payload base64(len=${payload.size})=$b64")
                        } catch (e: Exception) { /* ignore */ }
                    }
                } catch (e: Exception) { /* ignore */ }

                // forward to players if this session is publisher
                if (isPublishing && publishStreamName != null) {
                    // detect sequence headers and cache
                    try {
                        if (type == 8 && payload.isNotEmpty()) {
                            // audio: check AAC sequence header
                            val soundFormat = (payload[0].toInt() shr 4) and 0x0f
                            if (soundFormat == 10 && payload.size > 1 && payload[1].toInt() == 0) {
                                // AAC sequence header
                                aacSequenceHeader = payload.copyOf()
                                Log.i(TAGS, "Cached AAC sequence header, len=${aacSequenceHeader?.size} preview=${aacSequenceHeader?.take(32)?.joinToString(" ") { String.format("%02x", it) }}")
                            }
                        } else if (type == 9 && payload.isNotEmpty()) {
                            // video: check AVC sequence header
                            val codecId = payload[0].toInt() and 0x0f
                            // AVC (H.264) codec id = 7
                            if (codecId == 7 && payload.size > 1) {
                                val avcPacketType = payload[1].toInt() and 0xff
                                if (avcPacketType == 0) {
                                    avcSequenceHeader = payload.copyOf()
                                    Log.i(TAGS, "Cached AVC sequence header, len=${avcSequenceHeader?.size} preview=${avcSequenceHeader?.take(32)?.joinToString(" ") { String.format("%02x", it) }}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.i(TAGS, "Error detecting sequence header: ${e.message}")
                    }
                    forwardToPlayers(type, timestamp, payload)
                }
            }
            18 -> { // data (onMetaData / @setDataFrame)
                // Cache metadata if this is a publisher sending it, and forward to players
                try {
                    val amf = Amf0Parser(payload)
                    val name = amf.readAmf0() as? String
                    if (name != null && (name == "onMetaData" || name == "@setDataFrame")) {
                        // cache raw payload for new players
                        metaData = payload.copyOf()
                        val preview = metaData?.take(128)?.joinToString(" ") { String.format("%02x", it) }
                        Log.i(TAGS, "Cached metadata from publisher=${publishStreamName} name=$name len=${metaData?.size} preview=${preview}")
                            if (debugDumpPayloads) {
                                try {
                                    val b64 = android.util.Base64.encodeToString(metaData, android.util.Base64.NO_WRAP)
                                    Log.i(TAGS, "Metadata payload base64(len=${metaData?.size})=$b64")
                                } catch (e: Exception) { /* ignore */ }
                            }
                    } else {
                        Log.i(TAGS, "Data message received name=$name len=${payload.size} preview=${payload.take(64).joinToString(" ") { String.format("%02x", it) }}")
                    }
                } catch (e: Exception) {
                    Log.i(TAGS, "Error parsing data message: ${e.message} preview=${payload.take(64).joinToString(" ") { String.format("%02x", it) }}")
                }

                if (isPublishing && publishStreamName != null) {
                    forwardToPlayers(type, timestamp, payload)
                }
            }
            20, 17 -> { // invoke (AMF0/AMF3) - type 20 is AMF0 invoke
                val amf = Amf0Parser(payload)
                val cmd = amf.readAmf0()
                // diagnostic: log invoke command and a small hex preview of the payload
                try {
                    val preview = payload.take(32).joinToString(" ") { String.format("%02x", it) }
                    Log.i(TAGS, "Invoke cmd=$cmd payloadLen=${payload.size} preview=$preview")
                } catch (e: Exception) {
                    Log.i(TAGS, "Invoke cmd=$cmd payloadLen=${payload.size}")
                }
                // dump remaining AMF0 values for diagnostics
                try {
                    val dump = amf.dumpRemaining()
                    Log.i(TAGS, "AMF args dump=${dump}")
                } catch (e: Exception) {
                    Log.i(TAGS, "AMF dump error: ${e.message}")
                }
                if (cmd is String) {
                    // detect if the remaining args are AMF3-wrapped (AMF0 marker 0x11)
                        val useAmf3 = when (amf.nextMarker()) {
                            0x11 -> true
                            else -> false
                        }
                    handleCommand(cmd, amf, streamId, useAmf3)
                }
            }
            else -> {
                Log.d(TAGS, "Unhandled msg type=$type len=${payload.size}")
            }
        }
    }

    private fun handleCommand(cmd: String, amf: Amf0Parser, msgStreamId: Int, useAmf3: Boolean) {
        when (cmd) {
            "connect" -> {
                val transId = amf.readAmf0() as? Double ?: 0.0
                val obj = amf.readAmf0() as? Map<*, *>
                if (obj != null && obj["app"] is String) {
                    appName = obj["app"] as String
                }
                // send _result for connect
                val trans = transId
                val resp = if (useAmf3) {
                    Log.i(TAGS, "Using AMF3 response for connect (session#$sessionId)")
                    RtmpServerState.recordAmf3Usage(sessionId)
                    buildConnectResultAmf3(trans)
                } else buildConnectResult(trans)
                sendRtmpMessage(20, 0, resp)
                Log.i(TAGS, "Handled connect, app=$appName")
            }
            "createStream" -> {
                val transId = amf.readAmf0() as? Double ?: 0.0
                // allocate a stream id local to this session
                lastStreamIdAllocated += 1
                val streamId = lastStreamIdAllocated
                // track publish/play stream ids accordingly (createStream is typically used by both)
                val resp = if (useAmf3) {
                    Log.i(TAGS, "Using AMF3 response for createStream (session#$sessionId)")
                    RtmpServerState.recordAmf3Usage(sessionId)
                    buildCreateStreamResultAmf3(transId, streamId)
                } else buildCreateStreamResult(transId, streamId)
                sendRtmpMessage(20, 0, resp)
            }
            "publish" -> {
                // publish(streamName)
                val transIdObj = amf.readAmf0()
                // Some clients send null or extra args before the actual stream name.
                // Scan next few AMF0 values and pick the first String as the stream name.
                var name: String? = null
                val maxScan = 4
                val scanned = mutableListOf<Any?>()
                for (i in 0 until maxScan) {
                    val v = amf.readAmf0()
                    scanned.add(v)
                    if (v is String) {
                        name = v
                        break
                    }
                    if (v == null) continue
                }
                Log.i(TAGS, "[session#$sessionId] publish scannedArgs=$scanned")
                if (name != null) {
                    val full = "/$appName/$name"
                    publishStreamName = full
                    isPublishing = true
                    streams[full] = this
                    // record the message stream id the publisher used locally
                    publishStreamId = msgStreamId
                    // Diagnostic logging
                    Log.i(TAGS, "[session#$sessionId] publish parsed name=$name full=$full transIdObj=$transIdObj")
                    RtmpServerState.registerStream(full, sessionId)
                    RtmpServerState.updateSession(sessionId, true, full)
                    Log.i(TAGS, "[session#$sessionId] Client started publishing: $full")
                    // send onStatus NetStream.Publish.Start to publisher
                    val notif = if (useAmf3) {
                        Log.i(TAGS, "Using AMF3 onStatus for publish (session#$sessionId)")
                        RtmpServerState.recordAmf3Usage(sessionId)
                        buildOnStatusAmf3("status", "NetStream.Publish.Start", "Publishing")
                    } else buildOnStatus("status", "NetStream.Publish.Start", "Publishing")
                    // send onStatus back on the publisher's message stream id (msgStreamId)
                    val pubStream = if (publishStreamId != 0) publishStreamId else 1
                    sendRtmpMessage(18, pubStream, notif) // data message
                    // attach any waiting players who tried to play before the publisher existed
                    val queued = waitingPlayers.remove(full)
                    if (queued != null) {
                        for (p in queued) {
                            try {
                                players.add(p)
                                // allocate a playStreamId for this player session
                                p.lastStreamIdAllocated += 1
                                p.playStreamId = p.lastStreamIdAllocated
                                Log.i(TAGS, "Attached queued player #${p.sessionId} to $full playStreamId=${p.playStreamId}")
                                // notify player
                                val pn = buildOnStatus("status", "NetStream.Play.Start", "Playing")
                                p.sendRtmpMessage(18, p.playStreamId, pn)
                                // send cached metadata first (matches Node-Media-Server ordering), then sequence headers
                                this.metaData?.let { md ->
                                    p.sendRtmpMessage(18, p.playStreamId, md)
                                }
                                this.aacSequenceHeader?.let { sh -> p.sendRtmpMessage(8, p.playStreamId, sh) }
                                this.avcSequenceHeader?.let { sh -> p.sendRtmpMessage(9, p.playStreamId, sh) }
                            } catch (e: Exception) {
                                Log.e(TAGS, "Error attaching queued player", e)
                            }
                        }
                    }
                } else {
                    Log.i(TAGS, "[session#$sessionId] publish with null name after scanning AMF args")
                }
            }
            "play" -> {
                val transIdObj = amf.readAmf0()
                // Some clients include null or extra args before the actual stream name.
                var name: String? = null
                val maxScan = 4
                val scanned = mutableListOf<Any?>()
                for (i in 0 until maxScan) {
                    val v = amf.readAmf0()
                    scanned.add(v)
                    if (v is String) {
                        name = v
                        break
                    }
                    if (v == null) continue
                }
                Log.i(TAGS, "[session#$sessionId] play scannedArgs=$scanned")
                if (name != null) {
                    val full = "/$appName/$name"
                    Log.i(TAGS, "[session#$sessionId] play parsed name=$name full=$full transIdObj=$transIdObj")
                    val pub = streams[full]
                    if (pub != null) {
                        pub.players.add(this)
                        // set my local playStreamId to a newly allocated id for this session
                        lastStreamIdAllocated += 1
                        playStreamId = lastStreamIdAllocated
                        RtmpServerState.updateSession(pub.sessionId, true, pub.publishStreamName)
                        Log.i(TAGS, "[session#$sessionId] Client joined as player for $full (publisher=#${pub.sessionId}) playStreamId=$playStreamId")
                        // send onStatus Play.Start
                        val notif = if (useAmf3) {
                            Log.i(TAGS, "Using AMF3 onStatus for play (session#$sessionId)")
                            RtmpServerState.recordAmf3Usage(sessionId)
                            buildOnStatusAmf3("status", "NetStream.Play.Start", "Playing")
                        } else buildOnStatus("status", "NetStream.Play.Start", "Playing")
                        sendRtmpMessage(18, playStreamId, notif)
                        // send cached metadata first, then sequence headers (match Node-Media-Server)
                        try {
                            pub.metaData?.let { md ->
                                Log.i(TAGS, "Sending cached metadata to player=#$sessionId len=${md.size}")
                                sendRtmpMessage(18, playStreamId, md)
                            }
                            pub.aacSequenceHeader?.let { sh ->
                                Log.i(TAGS, "Sending cached AAC seq header to player=#$sessionId len=${sh.size}")
                                sendRtmpMessage(8, playStreamId, sh)
                            }
                            pub.avcSequenceHeader?.let { sh ->
                                Log.i(TAGS, "Sending cached AVC seq header to player=#$sessionId len=${sh.size}")
                                sendRtmpMessage(9, playStreamId, sh)
                            }
                        } catch (e: Exception) {
                            Log.i(TAGS, "Error sending cached seq headers: ${e.message}")
                        }
                    } else {
                        Log.i(TAGS, "No publisher for $full — queuing player until publisher appears")
                        val q = waitingPlayers.getOrPut(full) { mutableListOf() }
                        q.add(this)
                    }
                } else {
                    Log.i(TAGS, "[session#$sessionId] play with null name after scanning AMF args")
                }
            }
            "FCPublish" -> {
                // Some clients send FCPublish before publish; reply with a minimal _result
                val transId = amf.readAmf0() as? Double ?: 0.0
                val respBaos = java.io.ByteArrayOutputStream()
                respBaos.write(buildStringAmf("_result"))
                respBaos.write(buildNumberAmf(transId))
                // null
                respBaos.write(5)
                val resp = respBaos.toByteArray()
                sendRtmpMessage(20, 0, resp)
                Log.i(TAGS, "Handled FCPublish (trans=$transId)")
            }
            else -> {
                Log.i(TAGS, "Unhandled command: $cmd")
            }
        }
    }

    private fun forwardToPlayers(type: Int, timestamp: Int, payload: ByteArray) {
        val list = players.toList() // snapshot
        for (p in list) {
            try {
                val outStreamId = if (p.playStreamId != 0) p.playStreamId else 1
                // small preview of payload
                val preview = payload.take(32).joinToString(" ") { String.format("%02x", it) }
                Log.i(TAGS, "Forwarding type=$type ts=$timestamp len=${payload.size} -> player#${p.sessionId} outStreamId=$outStreamId preview=$preview publisher=${this.sessionId}")
                p.sendRtmpMessage(type, outStreamId, payload, timestamp)
            } catch (e: Exception) {
                Log.e(TAGS, "Error forwarding to player", e)
            }
        }
    }

    private fun sendRtmpMessage(type: Int, streamId: Int, payload: ByteArray, timestamp: Int = 0) {
        // choose channel id similar to Node-Media-Server conventions
        // invoke -> channel 3, audio -> 4, video -> 5, data -> 6
        val cid = when (type) {
            20 -> 3
            8 -> 4
            9 -> 5
            else -> 6
        }

        // For simplicity handle cid < 64 (single-byte basic header)
        val maxChunk = outChunkSize

        synchronized(output) {
            var remaining = payload.size
            var offset = 0
            var first = true
            while (remaining > 0) {
                val chunkSize = if (first) minOf(maxChunk, remaining) else minOf(maxChunk, remaining)
                // basic header: fmt = 0 for first chunk, fmt = 3 for continuation
                val fmt = if (first) 0 else 3
                val basic = ((fmt shl 6) or (cid and 0x3f)).toByte()
                output.writeByte(basic.toInt())

                if (first) {
                    // 11-byte message header for fmt=0
                    val msgHeader = ByteArray(11)
                    msgHeader[0] = ((timestamp shr 16) and 0xff).toByte()
                    msgHeader[1] = ((timestamp shr 8) and 0xff).toByte()
                    msgHeader[2] = (timestamp and 0xff).toByte()
                    msgHeader[3] = ((payload.size shr 16) and 0xff).toByte()
                    msgHeader[4] = ((payload.size shr 8) and 0xff).toByte()
                    msgHeader[5] = (payload.size and 0xff).toByte()
                    msgHeader[6] = (type and 0xff).toByte()
                    // stream id little-endian
                    msgHeader[7] = (streamId and 0xff).toByte()
                    msgHeader[8] = ((streamId shr 8) and 0xff).toByte()
                    msgHeader[9] = ((streamId shr 16) and 0xff).toByte()
                    msgHeader[10] = ((streamId shr 24) and 0xff).toByte()
                    output.write(msgHeader)
                }

                // write payload chunk
                output.write(payload, offset, chunkSize)

                // log outgoing chunk preview for debugging
                try {
                    val pview = payload.take(8).joinToString(" ") { String.format("%02x", it) }
                    Log.i(TAGS, "sendRtmpMessage type=$type streamId=$streamId cid=$cid chunkSize=$chunkSize remainingAfter=${remaining - chunkSize} preview=$pview")
                } catch (e: Exception) { /* ignore */ }

                remaining -= chunkSize
                offset += chunkSize
                first = false
            }
            output.flush()
        }
    }

    // build AMF0 encoded responses
    private fun buildStringAmf(name: String): ByteArray {
        val bs = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(3 + bs.size)
        out[0] = 2 // string marker
        out[1] = ((bs.size shr 8) and 0xff).toByte()
        out[2] = (bs.size and 0xff).toByte()
        System.arraycopy(bs, 0, out, 3, bs.size)
        return out
    }

    private fun buildNumberAmf(v: Double): ByteArray {
        val out = ByteArray(9)
        out[0] = 0
        val bb = java.nio.ByteBuffer.allocate(8).putDouble(v).array()
        System.arraycopy(bb, 0, out, 1, 8)
        return out
    }

    private fun buildObjectAmf(map: Map<String, Any>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(3) // object marker
        for ((k, v) in map) {
            val keyb = k.toByteArray(Charsets.UTF_8)
            baos.write((keyb.size shr 8) and 0xff)
            baos.write(keyb.size and 0xff)
            baos.write(keyb)
            when (v) {
                is String -> baos.write(buildStringAmf(v))
                is Double -> baos.write(buildNumberAmf(v))
                is Int -> baos.write(buildNumberAmf(v.toDouble()))
                is Boolean -> baos.write(if (v) byteArrayOf(1, 1) else byteArrayOf(1, 0))
                else -> baos.write(5) // null
            }
        }
        // object end marker
        baos.write(0)
        baos.write(0)
        baos.write(9)
        return baos.toByteArray()
    }

    private fun buildConnectResult(transId: Double): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(buildStringAmf("_result"))
        baos.write(buildNumberAmf(transId))
        val props = mapOf("fmsVer" to "FMS/3,5,7,7009", "capabilities" to 31)
        baos.write(buildObjectAmf(props))
        val info = mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded.")
        baos.write(buildObjectAmf(info))
        return baos.toByteArray()
    }

    // AMF3 encoded variants
    private fun buildConnectResultAmf3(transId: Double): ByteArray {
        val enc = Amf3Encoder()
        // _result (string)
        enc.writeValue("_result")
        enc.writeValue(transId.toInt())
        val props = mapOf("fmsVer" to "FMS/3,5,7,7009", "capabilities" to 31)
        enc.writeValue(props)
        val info = mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded.")
        enc.writeValue(info)
        return enc.toByteArray()
    }

    private fun buildCreateStreamResult(transId: Double, streamId: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(buildStringAmf("_result"))
        baos.write(buildNumberAmf(transId))
        baos.write(5) // null
        baos.write(buildNumberAmf(streamId.toDouble()))
        return baos.toByteArray()
    }

    private fun buildCreateStreamResultAmf3(transId: Double, streamId: Int): ByteArray {
        val enc = Amf3Encoder()
        enc.writeValue("_result")
        enc.writeValue(transId.toInt())
        enc.writeValue(null)
        enc.writeValue(streamId)
        return enc.toByteArray()
    }

    private fun buildOnStatus(level: String, code: String, desc: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        baos.write(buildStringAmf("onStatus"))
        baos.write(buildNumberAmf(0.0))
        baos.write(5) // null
        val info = mapOf("level" to level, "code" to code, "description" to desc)
        baos.write(buildObjectAmf(info))
        return baos.toByteArray()
    }

    private fun buildOnStatusAmf3(level: String, code: String, desc: String): ByteArray {
        val enc = Amf3Encoder()
        enc.writeValue("onStatus")
        enc.writeValue(0)
        enc.writeValue(null)
        val info = mapOf("level" to level, "code" to code, "description" to desc)
        enc.writeValue(info)
        return enc.toByteArray()
    }
}
