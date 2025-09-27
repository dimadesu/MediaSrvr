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
    // NOTE: enable this only for debugging; it may produce large logs
    private val debugDumpPayloads = true
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

    // simple GOP cache: store outgoing chunks for a small recent set
    private val rtmpGopCache = mutableListOf<ByteArray>()

    // recent inbound bytes (ring buffer) for post-mortem debugging
    private val recentBuf = ByteArray(8 * 1024)
    private var recentPos = 0
    private var recentFull = false

    var appName: String = ""
    var publishStreamName: String? = null
    var isPublishing = false
    var publishStreamKey: String? = null

    // players subscribed to a stream name -> list of sessions
    val players = mutableSetOf<RtmpSession>()

    // monitor and activity tracking for publishers: if no AV/data arrives after this timeout, log and nudge
    private var lastMediaTimestampMs: Long = 0L
    private var publishMonitorJob: Job? = null
    private var lastPublishUsedAmf3: Boolean = false
    private var createdStreamSeen: Boolean = false
    private var connectMonitorJob: Job? = null

    fun run(): Job {
        return serverScope.launch {
            try {
                // register in global state
                val remoteAddr = socket.inetAddress?.hostAddress ?: "unknown"
                RtmpServerState.registerSession(RtmpSessionInfo(sessionId, remoteAddr, false, null))
                // start a connect monitor: if no createStream/publish within 3s, dump recent inbound for diagnosis
                connectMonitorJob?.cancel()
                connectMonitorJob = serverScope.launch {
                    delay(3_000)
                    if (!createdStreamSeen) {
                        Log.i(TAGS, "No createStream/publish observed within 3s for session#$sessionId — dumping recent inbound bytes for diagnosis")
                        try {
                            val len = if (recentFull) recentBuf.size else recentPos
                            val copy = ByteArray(len)
                            if (recentFull) {
                                val tail = recentBuf.size - recentPos
                                System.arraycopy(recentBuf, recentPos, copy, 0, tail)
                                System.arraycopy(recentBuf, 0, copy, tail, recentPos)
                            } else {
                                System.arraycopy(recentBuf, 0, copy, 0, recentPos)
                            }
                            val b64 = android.util.Base64.encodeToString(copy, android.util.Base64.NO_WRAP)
                            Log.i(TAGS, "Connect monitor recent inbound base64(len=${len})=$b64")
                        } catch (e: Exception) {
                            Log.i(TAGS, "Error in connect monitor dump: ${e.message}")
                        }
                    }
                }
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
                        val beforeGot = got
                        val r = input.read(pkt.buffer, pkt.received + got, toRead - got)
                        if (r <= 0) throw java.io.EOFException("Unexpected EOF while reading chunk payload")
                        // copy the newly read bytes into recentBuf
                        try {
                            val start = pkt.received + beforeGot
                            var copied = 0
                            while (copied < r) {
                                recentBuf[recentPos] = pkt.buffer[start + copied]
                                recentPos += 1
                                if (recentPos >= recentBuf.size) { recentPos = 0; recentFull = true }
                                copied += 1
                            }
                        } catch (e: Exception) { /* ignore */ }
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
        // emit recent inbound bytes for debugging
        try {
            val len = if (recentFull) recentBuf.size else recentPos
            if (len > 0) {
                val copy = ByteArray(len)
                if (recentFull) {
                    // copy from recentPos..end then 0..recentPos-1
                    val tail = recentBuf.size - recentPos
                    System.arraycopy(recentBuf, recentPos, copy, 0, tail)
                    System.arraycopy(recentBuf, 0, copy, tail, recentPos)
                } else {
                    System.arraycopy(recentBuf, 0, copy, 0, recentPos)
                }
                val b64 = android.util.Base64.encodeToString(copy, android.util.Base64.NO_WRAP)
                Log.i(TAGS, "Recent inbound bytes base64(len=${len})=$b64")
            }
        } catch (e: Exception) {
            Log.i(TAGS, "Error dumping recent inbound bytes: ${e.message}")
        }
        try { publishMonitorJob?.cancel() } catch (_: Exception) {}
        try { connectMonitorJob?.cancel() } catch (_: Exception) {}
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
                    // record that we've seen media from this publisher and cancel any monitor
                    lastMediaTimestampMs = System.currentTimeMillis()
                    publishMonitorJob?.cancel()
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
                                try {
                                    val info = AvUtils.parseAacSequenceHeader(aacSequenceHeader!!)
                                    if (info != null) {
                                        Log.i(TAGS, "Parsed AAC info profile=${info.profile} sampleRate=${info.sampleRate} channels=${info.channels}")
                                    }
                                } catch (_: Exception) { }
                                    lastMediaTimestampMs = System.currentTimeMillis()
                                    publishMonitorJob?.cancel()
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
                                    try {
                                        val info = AvUtils.parseAvcSequenceHeader(avcSequenceHeader!!)
                                        if (info != null) {
                                            Log.i(TAGS, "Parsed AVC info profile=${info.profile} level=${info.level} width=${info.width} height=${info.height}")
                                        }
                                    } catch (_: Exception) { }
                                        lastMediaTimestampMs = System.currentTimeMillis()
                                        publishMonitorJob?.cancel()
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
                        lastMediaTimestampMs = System.currentTimeMillis()
                        publishMonitorJob?.cancel()
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
                    // optional: compare incoming payload against Node goldens for diagnostics
                    try {
                        if (GoldenComparator.isEnabled()) {
                            // choose golden file name heuristically by command and AMF encoding
                            val suffix = if (useAmf3) "amf3.hex" else "amf0.hex"
                            // Prefer client-sent payload goldens (e.g., connect_amf0.hex) where available.
                            val candidates = when (cmd) {
                                "connect" -> listOf("connect_$suffix", "connect_result_$suffix")
                                "createStream" -> listOf("create_stream_$suffix", "create_stream_result_$suffix")
                                "publish" -> listOf("publish_$suffix")
                                "onStatus", "onstatus" -> listOf("onstatus_$suffix")
                                else -> emptyList()
                            }
                            val goldenName = GoldenComparator.resolveExistingGoldenName(candidates)
                            if (goldenName != null) {
                                GoldenComparator.compare(sessionId, cmd, goldenName, payload)
                            }
                        }
                    } catch (e: Exception) {
                        Log.i(TAGS, "Golden comparator error: ${e.message}")
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
                // human-readable AMF summary for connect
                try {
                    val tcUrl = obj?.get("tcUrl")
                    val flashVer = obj?.get("flashVer")
                    val objectEncoding = obj?.get("objectEncoding")
                    val audioCodecs = obj?.get("audioCodecs")
                    val videoCodecs = obj?.get("videoCodecs")
                    val videoFunction = obj?.get("videoFunction")
                    val pageUrl = obj?.get("pageUrl")
                    val swfUrl = obj?.get("swfUrl")
                    Log.i(TAGS, "Connect summary: app=$appName tcUrl=${tcUrl} flashVer=${flashVer} objectEncoding=${objectEncoding} audioCodecs=${audioCodecs} videoCodecs=${videoCodecs} videoFunction=${videoFunction} pageUrl=${pageUrl} swfUrl=${swfUrl}")
                } catch (e: Exception) { /* ignore */ }
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
                createdStreamSeen = true
                // allocate a stream id local to this session
                lastStreamIdAllocated += 1
                val streamId = lastStreamIdAllocated
                Log.i(TAGS, "Allocated local streamId=$streamId for createStream (session#$sessionId)")
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
                    Log.i(TAGS, "publish received: client msgStreamId=$msgStreamId recorded publishStreamId=$publishStreamId (session#$sessionId)")
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
                    Log.i(TAGS, "Sending NetStream.Publish.Start on streamId=$pubStream for publisher session#$sessionId")
                    try {
                        val b64 = android.util.Base64.encodeToString(notif, android.util.Base64.NO_WRAP)
                        Log.i(TAGS, "OnStatus payload base64(len=${notif.size})=$b64")
                    } catch (e: Exception) { /* ignore */ }
                    sendRtmpMessage(18, pubStream, notif) // data message
                    // start monitor: if no media frames or metadata arrive within N seconds, nudge and dump recent bytes
                    lastMediaTimestampMs = System.currentTimeMillis()
                    publishMonitorJob?.cancel()
                    publishMonitorJob = serverScope.launch {
                        delay(5000)
                        // if no media after timeout, log and dump recent inbound bytes
                        val now = System.currentTimeMillis()
                        if (now - lastMediaTimestampMs >= 4000) {
                            Log.i(TAGS, "No AV/data received from publisher session#$sessionId within timeout — dumping recent inbound bytes and sending diagnostic onStatus")
                            try {
                                val len = if (recentFull) recentBuf.size else recentPos
                                val copy = ByteArray(len)
                                if (recentFull) {
                                    val tail = recentBuf.size - recentPos
                                    System.arraycopy(recentBuf, recentPos, copy, 0, tail)
                                    System.arraycopy(recentBuf, 0, copy, tail, recentPos)
                                } else {
                                    System.arraycopy(recentBuf, 0, copy, 0, recentPos)
                                }
                                val b64 = android.util.Base64.encodeToString(copy, android.util.Base64.NO_WRAP)
                                Log.i(TAGS, "Publish monitor recent inbound base64(len=${len})=$b64")
                            } catch (e: Exception) {
                                Log.i(TAGS, "Error in publish monitor dump: ${e.message}")
                            }
                            // send a diagnostic onStatus to the publisher
                            val diag = buildOnStatus("error", "NetStream.Publish.NoMedia", "No media frames received")
                            try {
                                val db64 = android.util.Base64.encodeToString(diag, android.util.Base64.NO_WRAP)
                                Log.i(TAGS, "Sending diagnostic onStatus base64(len=${diag.size})=$db64")
                            } catch (e: Exception) { /* ignore */ }
                            sendRtmpMessage(18, pubStream, diag)
                        }
                    }
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
                val respOpt = mapOf<String, Any?>(
                    "cmd" to "_result",
                    "transId" to transId,
                    "cmdObj" to null
                )
                val resp = NodeCoreAmf.encodeAmf0Cmd(respOpt)
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
                // cache non-sequence-header AV frames for new players
                if (type == 8 || type == 9) {
                    try {
                        // skip sequence headers when caching
                        val isSequence = when (type) {
                            8 -> (payload.size > 1 && ((payload[0].toInt() shr 4) and 0x0f) == 10 && payload[1].toInt() == 0)
                            9 -> (payload.size > 1 && ((payload[0].toInt() and 0x0f) == 7) && payload[1].toInt() == 0)
                            else -> false
                        }
                        if (!isSequence) {
                            // store chunkized bytes for quick replay (we'll store original payload; sendRtmpMessage will chunk)
                            if (rtmpGopCache.size >= 64) rtmpGopCache.removeAt(0)
                            rtmpGopCache.add(buildOutgoingChunkSnapshot(type, outStreamId, timestamp, payload))
                        }
                    } catch (_: Exception) { }
                }
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
            20 -> RtmpChannels.INVOKE
            8 -> RtmpChannels.AUDIO
            9 -> RtmpChannels.VIDEO
            else -> RtmpChannels.DATA
        }

        // For simplicity handle cid < 64 (single-byte basic header)
        val maxChunk = outChunkSize

        synchronized(output) {
            var remaining = payload.size
            var offset = 0
            var first = true
            while (remaining > 0) {
                val chunkSize = minOf(maxChunk, remaining)
                val fmt = if (first) 0 else 3

                // basic header generation (handle extended cid cases like Node-Media-Server)
                val basicHdr = buildBasicHeader(fmt, cid)
                output.write(basicHdr)

                if (first) {
                    // message header
                    val msgHeader = java.io.ByteArrayOutputStream()
                    // timestamp (3 bytes) or 0xffffff placeholder if extended
                    if (timestamp >= 0xFFFFFF) {
                        msgHeader.write(((0xFFFFFF shr 16) and 0xff))
                        msgHeader.write(((0xFFFFFF shr 8) and 0xff))
                        msgHeader.write((0xFFFFFF and 0xff))
                    } else {
                        msgHeader.write(((timestamp shr 16) and 0xff))
                        msgHeader.write(((timestamp shr 8) and 0xff))
                        msgHeader.write((timestamp and 0xff))
                    }
                    // message length
                    msgHeader.write(((payload.size shr 16) and 0xff))
                    msgHeader.write(((payload.size shr 8) and 0xff))
                    msgHeader.write((payload.size and 0xff))
                    // message type
                    msgHeader.write(type and 0xff)
                    // stream id little endian
                    msgHeader.write(streamId and 0xff)
                    msgHeader.write((streamId shr 8) and 0xff)
                    msgHeader.write((streamId shr 16) and 0xff)
                    msgHeader.write((streamId shr 24) and 0xff)
                    output.write(msgHeader.toByteArray())

                    // extended timestamp if needed
                    if (timestamp >= 0xFFFFFF) {
                        val ext = java.nio.ByteBuffer.allocate(4).putInt(timestamp).array()
                        output.write(ext)
                    }
                }

                // write payload chunk
                output.write(payload, offset, chunkSize)

                // continuation: if more data remains, write fmt=3 basic header for next chunk(s)
                remaining -= chunkSize
                offset += chunkSize
                first = false

                // if more remains and we will write another chunk, write continuation basic header now
                if (remaining > 0) {
                    val contHdr = buildBasicHeader(3, cid)
                    output.write(contHdr)
                    // if extended timestamp used, again write ext timestamp for continuation chunks
                    if (timestamp >= 0xFFFFFF) {
                        val ext = java.nio.ByteBuffer.allocate(4).putInt(timestamp).array()
                        output.write(ext)
                    }
                }
            }
            output.flush()
        }
    }

    // Build RTMP basic header bytes for a given fmt and cid (support single-byte, 2-byte and 3-byte headers)
    private fun buildBasicHeader(fmt: Int, cid: Int): ByteArray {
        return when {
            cid >= 64 + 255 -> {
                val b = ByteArray(3)
                b[0] = (((fmt and 0x03) shl 6) or 1).toByte()
                b[1] = ((cid - 64) and 0xff).toByte()
                b[2] = (((cid - 64) shr 8) and 0xff).toByte()
                b
            }
            cid >= 64 -> {
                val b = ByteArray(2)
                b[0] = (((fmt and 0x03) shl 6) or 0).toByte()
                b[1] = ((cid - 64) and 0xff).toByte()
                b
            }
            else -> byteArrayOf((((fmt and 0x03) shl 6) or (cid and 0x3f)).toByte())
        }
    }

    // Store a small snapshot of outgoing chunk for GOP replay (we store a small header+payload blob)
    private fun buildOutgoingChunkSnapshot(type: Int, streamId: Int, timestamp: Int, payload: ByteArray): ByteArray {
        try {
            val baos = java.io.ByteArrayOutputStream()
            // prepend a small header so we know type/streamId/timestamp when replaying
            baos.write(((type shr 24) and 0xff))
            baos.write(((type shr 16) and 0xff))
            baos.write(((type shr 8) and 0xff))
            baos.write((type and 0xff))
            baos.write(((streamId shr 24) and 0xff))
            baos.write(((streamId shr 16) and 0xff))
            baos.write(((streamId shr 8) and 0xff))
            baos.write((streamId and 0xff))
            baos.write(((timestamp shr 24) and 0xff))
            baos.write(((timestamp shr 16) and 0xff))
            baos.write(((timestamp shr 8) and 0xff))
            baos.write((timestamp and 0xff))
            baos.write(payload)
            return baos.toByteArray()
        } catch (e: Exception) {
            return payload
        }
    }



    private fun buildConnectResult(transId: Double): ByteArray {
        val opt = mapOf<String, Any?>(
            "cmd" to "_result",
            "transId" to transId,
            "cmdObj" to null,
            "info" to mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded.")
        )
        return NodeCoreAmf.encodeAmf0Cmd(opt)
    }

    // AMF3 encoded variants
    private fun buildConnectResultAmf3(transId: Double): ByteArray {
        val opt = mapOf<String, Any?>(
            "cmd" to "_result",
            "transId" to transId.toInt(),
            "cmdObj" to null,
            "info" to mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded.")
        )
        return NodeCoreAmf.encodeAmf3Cmd(opt)
    }

    private fun buildCreateStreamResult(transId: Double, streamId: Int): ByteArray {
        val opt = mapOf<String, Any?>(
            "cmd" to "_result",
            "transId" to transId,
            "cmdObj" to null,
            "info" to null,
            // Node's rtmpCmdCode expects third param as info but for createStream result we append streamId as last param
            // We'll encode manually using NodeCoreAmf.amf0Encode to preserve ordering: cmd, transId, null, streamId
        )
        // build array of AMF0 values directly
        val parts = listOf<Any?>("_result", transId, null, streamId.toDouble())
        return NodeCoreAmf.amf0Encode(parts)
    }

    private fun buildCreateStreamResultAmf3(transId: Double, streamId: Int): ByteArray {
        val parts = listOf<Any?>("_result", transId.toInt(), null, streamId)
        // encode first element as AMF0 string and rest as AMF3 values (Node style: cmd as AMF0 string then AMF3 values)
        val cmdPart = NodeCoreAmf.amf0encString("_result")
        val enc = Amf3Encoder()
        enc.writeValue(transId.toInt())
        enc.writeValue(null)
        enc.writeValue(streamId)
        return cmdPart + enc.toByteArray()
    }

    private fun buildOnStatus(level: String, code: String, desc: String): ByteArray {
        val opt = mapOf<String, Any?>(
            "cmd" to "onStatus",
            "transId" to 0.0,
            "cmdObj" to null,
            "info" to mapOf("level" to level, "code" to code, "description" to desc)
        )
        return NodeCoreAmf.encodeAmf0Cmd(opt)
    }

    private fun buildOnStatusAmf3(level: String, code: String, desc: String): ByteArray {
        val opt = mapOf<String, Any?>(
            "cmd" to "onStatus",
            "transId" to 0,
            "cmdObj" to null,
            "info" to mapOf("level" to level, "code" to code, "description" to desc)
        )
        return NodeCoreAmf.encodeAmf3Cmd(opt)
    }
}
