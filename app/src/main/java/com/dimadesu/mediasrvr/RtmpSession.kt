package com.dimadesu.mediasrvr

import kotlinx.coroutines.*
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

open class RtmpSession(
    val sessionId: Int,
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val serverScope: CoroutineScope,
    private val streams: MutableMap<String, RtmpSession>,
    private val waitingPlayers: MutableMap<String, MutableList<RtmpSession>>,
    private val delegate: RtmpServerDelegate? = null
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
    var playStreamName: String? = null
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
    // temporary diagnostic: after a publish starts, log the next N chunk headers to see what the client sends
    private var expectPostPublishHeaderCount: Int = 0
    private var expectPostPublishPayloadCount: Int = 0
    // raw TCP dump: when >0, log previews of raw bytes read from the socket (decrements as bytes are logged)
    private var expectRawDumpBytesRemaining: Int = 0
    // timestamp and counters for aggressive raw logging
    private var rawDumpStartTimeMs: Long = 0L
    private var rawDumpBytesLogged: Int = 0

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
                // chunk reassembly: keep per-cid chunk stream helpers
                val chunkStreams = mutableMapOf<Int, RtmpChunkStream>()

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

                    // read message header according to fmt using the chunk stream helper
                    val prevHeader = chunkStreams[cid]?.header
                    val cs = chunkStreams.getOrPut(cid) { RtmpChunkStream(cid, this@RtmpSession) }
                    val timestamp = try {
                        cs.readAndUpdateHeader(fmt, input, prevHeader)
                    } catch (e: Exception) {
                        Log.e(TAGS, "Error reading chunk header for cid=$cid fmt=$fmt: ${e.message}")
                        continue
                    }
                    // diagnostic: log the parsed message header (fmt/cid/timestamp/length/type/streamId)
                    try {
                        Log.i(TAGS, "ChunkHdr fmt=$fmt cid=$cid ts=${cs.header.timestamp} len=${cs.header.length} type=${cs.header.type} streamId=${cs.header.streamId}")
                        if (expectPostPublishHeaderCount > 0) {
                            try {
                                Log.i(TAGS, "POST_PUBLISH_HDR remaining=${expectPostPublishHeaderCount} fmt=$fmt cid=$cid ts=${cs.header.timestamp} len=${cs.header.length} type=${cs.header.type} streamId=${cs.header.streamId}")
                            } catch (_: Exception) {}
                            expectPostPublishHeaderCount -= 1
                        }
                    } catch (e: Exception) { /* ignore */ }

                    // read chunk payload (could be partial)
                    val toRead = cs.getChunkDataSize(inChunkSize)
                    var got = 0
                    if (toRead <= 0) {
                        throw java.io.EOFException("Unexpected data or zero toRead for cid=$cid")
                    }
                    val tmp = ByteArray(toRead)
                    while (got < toRead) {
                        val r = input.read(tmp, got, toRead - got)
                        if (r <= 0) throw java.io.EOFException("Unexpected EOF while reading chunk payload")
                        // Raw TCP diagnostic: if enabled, log a small hex preview of the newly read bytes
                        try {
                            if (expectRawDumpBytesRemaining > 0) {
                                val now = System.currentTimeMillis()
                                if (rawDumpStartTimeMs == 0L) rawDumpStartTimeMs = now
                                val delta = now - rawDumpStartTimeMs
                                val previewLen = minOf(64, r, expectRawDumpBytesRemaining)
                                val preview = tmp.slice(got until got + previewLen).joinToString(" ") { String.format("%02x", it) }
                                rawDumpBytesLogged += r
                                Log.i(TAGS, "RAW_DUMP ts=${now} deltaMs=${delta} logged=${rawDumpBytesLogged} remaining=${expectRawDumpBytesRemaining} read=${r} preview=$preview")
                                expectRawDumpBytesRemaining -= r
                                if (expectRawDumpBytesRemaining <= 0) {
                                    Log.i(TAGS, "RAW_DUMP_COMPLETE totalLogged=${rawDumpBytesLogged} durationMs=${now - rawDumpStartTimeMs}")
                                    expectRawDumpBytesRemaining = 0
                                }
                            }
                        } catch (e: Exception) { /* ignore raw dump errors */ }
                        // copy the newly read bytes into recentBuf
                        try {
                            var copied = 0
                            while (copied < r) {
                                recentBuf[recentPos] = tmp[got + copied]
                                recentPos += 1
                                if (recentPos >= recentBuf.size) { recentPos = 0; recentFull = true }
                                copied += 1
                            }
                        } catch (e: Exception) { /* ignore */ }
                        got += r
                    }
                    // append bytes into chunk stream's packet
                    cs.appendBytes(tmp, 0, got)

                    // update per-chunk ack counters (Moblin-style fields on InPacket)
                    try {
                        // update per-chunk counters for diagnostics; do not emit per-chunk ACKs here
                        cs.pkt.bytesReadSinceStart += got
                        if (cs.pkt.ackWindow == 0 && ackWindowSize > 0) {
                            cs.pkt.ackWindow = ackWindowSize
                        }
                        // we intentionally do not send per-chunk ACK messages. The server will
                        // use session-level Window Acknowledgement semantics (type 3) per spec.
                    } catch (e: Exception) { /* ignore per-chunk ack counter errors */ }

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

                    // if message complete, hand to session logic using CompletedPacket
                    val cp = cs.getCompletedPacketIfComplete(timestamp)
                    if (cp != null) {
                        if (expectPostPublishPayloadCount > 0) {
                            try {
                                val previewLen = minOf(64, cp.payload.size)
                                val preview = cp.payload.take(previewLen).joinToString(" ") { String.format("%02x", it) }
                                Log.i(TAGS, "POST_PUBLISH_PAYLOAD remaining=${expectPostPublishPayloadCount} type=${cp.type} streamId=${cp.streamId} len=${cp.payload.size} preview=$preview")
                            } catch (_: Exception) { }
                            expectPostPublishPayloadCount -= 1
                        }
                        // call the original handler
                        handleMessage(cp.type, cp.streamId, cp.timestamp, cp.payload)
                        // reset chunk stream state
                        chunkStreams.remove(cid)
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

    // helper to send user control events (type 4)
    private fun sendUserControl(eventType: Int, streamId: Int) {
        val buf = ByteArray(6)
        buf[0] = ((eventType shr 8) and 0xff).toByte()
        buf[1] = (eventType and 0xff).toByte()
        buf[2] = ((streamId shr 24) and 0xff).toByte()
        buf[3] = ((streamId shr 16) and 0xff).toByte()
        buf[4] = ((streamId shr 8) and 0xff).toByte()
        buf[5] = (streamId and 0xff).toByte()
        sendRtmpMessage(4, 0, buf)
    }

    private fun cleanup() {
        // if publishing, remove from streams
        publishStreamName?.let { key ->
            // notify players that the publisher is ending
            try {
                val pub = this
                val notify = buildOnStatus("status", "NetStream.Unpublish.Notify", "Publisher ended")
                val stopPub = buildOnStatus("status", "NetStream.Publish.Stop", "Stopped")
                // send Unpublish.Notify to all attached players
                for (p in players) {
                    try {
                        val outStreamId = if (p.playStreamId != 0) p.playStreamId else 1
                        p.sendRtmpMessage(18, outStreamId, notify)
                        // send StreamEOF user control to players
                        p.sendUserControl(1, outStreamId)
                    } catch (e: Exception) { /* ignore per-client errors */ }
                }
                // send Publish.Stop to the publisher session before removal
                try {
                    val pubStream = if (publishStreamId != 0) publishStreamId else 1
                    sendRtmpMessage(18, pubStream, stopPub)
                } catch (_: Exception) { }

            } catch (e: Exception) {
                Log.i(TAGS, "Error notifying players on publish end: ${e.message}")
            }

            streams.remove(key)
            RtmpServerState.unregisterStream(key)
            // notify delegate that publish stopped
            try {
                delegate?.onPublishStop(key, sessionId, null)
            } catch (_: Exception) { }
            // notify listeners that this publish has ended
            NodeEventBus.emit("donePublish", sessionId, key, publishStreamKey)
        }
        // remove from any players
        for ((_, s) in streams) {
            s.players.remove(this)
        }
        RtmpServerState.unregisterSession(sessionId)
        // emit donePlay if this session was a player
        playStreamName?.let { ps ->
            NodeEventBus.emit("donePlay", sessionId, ps, null)
        }
        // emit doneConnect like Node
        NodeEventBus.emit("doneConnect", sessionId, mapOf("app" to appName))
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

    internal open fun handleMessage(type: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
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
                                sendUserControl(7, time)
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
                                    // notify delegate about sequence header (with parsed metadata)
                                    try {
                                        val info = try { AvUtils.parseAacSequenceHeader(aacSequenceHeader!!) } catch (_: Exception) { null }
                                        val meta = AudioMetadata(true, 10, info?.profile, info?.sampleRate, info?.channels)
                                        delegate?.onAudioBuffer(sessionId, aacSequenceHeader!!, meta)
                                    } catch (_: Exception) { }
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
                                    // notify delegate about sequence header (with parsed metadata)
                                    try {
                                        val info = try { AvUtils.parseAvcSequenceHeader(avcSequenceHeader!!) } catch (_: Exception) { null }
                                        val meta = VideoMetadata(true, 7, 0, info?.profile, info?.level, info?.width, info?.height)
                                        delegate?.onVideoBuffer(sessionId, avcSequenceHeader!!, meta)
                                    } catch (_: Exception) { }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.i(TAGS, "Error detecting sequence header: ${e.message}")
                    }
                    forwardToPlayers(type, timestamp, payload)
                    // notify delegate of raw media frames as well (provide best-effort metadata)
                    try {
                        if (type == 8) {
                            val soundFormat = if (payload.isNotEmpty()) ((payload[0].toInt() shr 4) and 0x0f) else -1
                            val isSeq = (soundFormat == 10 && payload.size > 1 && payload[1].toInt() == 0)
                            val meta = if (soundFormat >= 0) AudioMetadata(isSeq, soundFormat) else null
                            delegate?.onAudioBuffer(sessionId, payload, meta)
                        }
                        if (type == 9) {
                            val codecId = if (payload.isNotEmpty()) (payload[0].toInt() and 0x0f) else -1
                            val avcPacketType = if (payload.size > 1) (payload[1].toInt() and 0xff) else null
                            val meta = if (codecId >= 0) VideoMetadata(avcPacketType == 0, codecId, avcPacketType) else null
                            delegate?.onVideoBuffer(sessionId, payload, meta)
                        }
                    } catch (_: Exception) { }
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
                    // emit pre events like Node (supply the parsed AMF parser for listeners)
                    when (cmd) {
                        "connect" -> NodeEventBus.emit("preConnect", sessionId, amf)
                        "publish" -> NodeEventBus.emit("prePublish", sessionId, amf)
                        "play" -> NodeEventBus.emit("prePlay", sessionId, amf)
                    }
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
                            // For inbound payloads, only compare against client-payload-style goldens
                            // (e.g. connect_amf0.hex). Server-response goldens (connect_result_*) are
                            // compared when the server actually sends those responses.
                            val candidates = when (cmd) {
                                "connect" -> listOf("connect_$suffix")
                                "createStream" -> listOf("create_stream_$suffix")
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
            22 -> { // Aggregate (FLV Aggregate)
                try {
                    val preview = payload.take(32).joinToString(" ") { String.format("%02x", it) }
                    Log.i(TAGS, "Aggregate message received len=${payload.size} preview=$preview")
                    // Parse FLV tags inside aggregate payload: tag header = 11 bytes, then data, then previousTagSize (4 bytes)
                    var idx = 0
                    var tagCount = 0
                    while (idx + 11 <= payload.size) {
                        val tagType = payload[idx].toInt() and 0xff
                        val dataSize = ((payload[idx + 1].toInt() and 0xff) shl 16) or
                                ((payload[idx + 2].toInt() and 0xff) shl 8) or
                                (payload[idx + 3].toInt() and 0xff)
                        val ts = ((payload[idx + 4].toInt() and 0xff) shl 16) or
                                ((payload[idx + 5].toInt() and 0xff) shl 8) or
                                (payload[idx + 6].toInt() and 0xff)
                        val tsExt = payload[idx + 7].toInt() and 0xff
                        val fullTs = (tsExt shl 24) or ts
                        // streamId at idx+8..idx+10 (ignored)
                        val tagHeaderTotal = 11
                        val prevTagSizeTotal = 4
                        val tagTotal = tagHeaderTotal + dataSize + prevTagSizeTotal
                        if (idx + tagTotal > payload.size) break
                        val tagPayloadStart = idx + tagHeaderTotal
                        val tagPayloadEnd = tagPayloadStart + dataSize
                        val tagPayload = payload.copyOfRange(tagPayloadStart, tagPayloadEnd)
                        // forward known tag types to players
                        when (tagType) {
                            8 -> { // audio
                                if (isPublishing) forwardToPlayers(8, fullTs, tagPayload)
                            }
                            9 -> { // video
                                if (isPublishing) forwardToPlayers(9, fullTs, tagPayload)
                            }
                            18 -> { // script/data
                                if (isPublishing) forwardToPlayers(18, fullTs, tagPayload)
                            }
                            else -> { /* ignore other tag types */ }
                        }
                        tagCount += 1
                        idx += tagTotal
                    }
                    Log.i(TAGS, "Aggregate parsed tagCount=${tagCount}")
                } catch (e: Exception) {
                    Log.i(TAGS, "Error parsing aggregate message: ${e.message}")
                }
            }
            else -> {
                Log.d(TAGS, "Unhandled msg type=$type len=${payload.size}")
            }
        }
    }

    // Public wrapper used by RtmpChunkStream to hand completed packets back to the session.
    fun processCompletedPacket(type: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
        handleMessage(type, streamId, timestamp, payload)
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
                // send _result for connect (use helper for easier testing)
                val trans = transId
                performConnectResponse(trans, useAmf3)
                // mirror Node-Media-Server: emit postConnect
                NodeEventBus.emit("postConnect", sessionId, mapOf("app" to appName))
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
                try {
                    val suffixResp = if (useAmf3) "amf3.hex" else "amf0.hex"
                    val candidatesResp = listOf("create_stream_result_$suffixResp", "create_stream_$suffixResp")
                    val goldenResp = GoldenComparator.resolveExistingGoldenName(candidatesResp)
                    if (goldenResp != null) GoldenComparator.compare(sessionId, "createStream_result", goldenResp, resp)
                } catch (e: Exception) { Log.i(TAGS, "Golden comparator (outbound) error: ${e.message}") }
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
                    playStreamName = full
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
                    try {
                        val suffixResp = if (useAmf3) "amf3.hex" else "amf0.hex"
                        val candidatesResp = listOf("onstatus_$suffixResp", "onstatus_result_$suffixResp")
                        val goldenResp = GoldenComparator.resolveExistingGoldenName(candidatesResp)
                        if (goldenResp != null) GoldenComparator.compare(sessionId, "onStatus", goldenResp, notif)
                    } catch (e: Exception) { Log.i(TAGS, "Golden comparator (outbound) error: ${e.message}") }
                    sendRtmpMessage(18, pubStream, notif) // data message
                    // Send User Control StreamBegin to the publisher (event type 0 + streamId)
                    try {
                        sendUserControl(0, pubStream)
                        Log.i(TAGS, "Sent StreamBegin to publisher streamId=$pubStream")
                    } catch (e: Exception) {
                        Log.i(TAGS, "Error sending StreamBegin to publisher: ${e.message}")
                    }
                    // emit postPublish so other components (relay/trans) can react
                    NodeEventBus.emit("postPublish", sessionId, full, publishStreamKey)
                    // notify delegate that a publish started
                    try {
                        delegate?.onPublishStart(full, sessionId)
                    } catch (_: Exception) { }
                    // Client sanity nudge: resend a few control messages (some clients react to these)
                    try {
                        // resend SetChunkSize (server->client)
                        val setChunkPayload = ByteArray(4)
                        setChunkPayload[0] = ((outChunkSize shr 24) and 0xff).toByte()
                        setChunkPayload[1] = ((outChunkSize shr 16) and 0xff).toByte()
                        setChunkPayload[2] = ((outChunkSize shr 8) and 0xff).toByte()
                        setChunkPayload[3] = (outChunkSize and 0xff).toByte()
                        sendRtmpMessage(1, 0, setChunkPayload)
                        // resend Window Acknowledgement Size
                        val windowAck = if (ackWindowSize > 0) ackWindowSize else (2 * 1024 * 1024)
                        val winPayload = ByteArray(4)
                        winPayload[0] = ((windowAck shr 24) and 0xff).toByte()
                        winPayload[1] = ((windowAck shr 16) and 0xff).toByte()
                        winPayload[2] = ((windowAck shr 8) and 0xff).toByte()
                        winPayload[3] = (windowAck and 0xff).toByte()
                        sendRtmpMessage(5, 0, winPayload)
                        // resend Set Peer Bandwidth
                        val pb = ByteArray(5)
                        pb[0] = ((windowAck shr 24) and 0xff).toByte()
                        pb[1] = ((windowAck shr 16) and 0xff).toByte()
                        pb[2] = ((windowAck shr 8) and 0xff).toByte()
                        pb[3] = (windowAck and 0xff).toByte()
                        pb[4] = 2
                        sendRtmpMessage(6, 0, pb)
                        Log.i(TAGS, "Client sanity nudge sent: SetChunkSize/WindowAck/SetPeerBW")
                    } catch (e: Exception) {
                        Log.i(TAGS, "Error sending client sanity nudge: ${e.message}")
                    }
                    // kick diagnostics: log the next few chunk headers from this publisher
                    // Increase diagnostic windows: more headers and payload previews for stubborn clients
                    expectPostPublishHeaderCount = 32
                    expectPostPublishPayloadCount = 16
                    // enable aggressive raw TCP dump for a short burst (64KB)
                    expectRawDumpBytesRemaining = 64 * 1024
                    rawDumpStartTimeMs = 0L
                    rawDumpBytesLogged = 0
                    Log.i(TAGS, "AGGRESSIVE_RAW_LOGGING started: headers=${expectPostPublishHeaderCount} payloads=${expectPostPublishPayloadCount} rawBytes=${expectRawDumpBytesRemaining}")
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
                                // send StreamBegin to the player as Node does
                                try {
                                    p.sendUserControl(0, p.playStreamId)
                                } catch (e: Exception) { Log.i(TAGS, "Error sending StreamBegin to queued player: ${e.message}") }
                                // send cached metadata first (matches Node-Media-Server ordering), then sequence headers
                                this.metaData?.let { md ->
                                    p.sendRtmpMessage(18, p.playStreamId, md)
                                }
                                this.aacSequenceHeader?.let { sh -> p.sendRtmpMessage(8, p.playStreamId, sh) }
                                this.avcSequenceHeader?.let { sh -> p.sendRtmpMessage(9, p.playStreamId, sh) }
                                // mirror Node-Media-Server: emit postPlay for the queued player after playback notifications
                                NodeEventBus.emit("postPlay", p.sessionId, full, null)
                            } catch (e: Exception) {
                                Log.e(TAGS, "Error attaching queued player", e)
                            }
                        }
                    }

                    // start monitor: if no media frames or metadata arrive within N seconds, nudge and dump recent bytes
                    lastMediaTimestampMs = System.currentTimeMillis()
                    publishMonitorJob?.cancel()
                    publishMonitorJob = serverScope.launch {
                        delay(5000)
                        val now = System.currentTimeMillis()
                        if (now - lastMediaTimestampMs >= 4000) {
                            Log.i(TAGS, "No AV/data received from publisher session#${sessionId} within timeout — dumping recent inbound bytes (log-only) and sending diagnostic onStatus")
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
                                // Heuristic RTMP scan: try to locate RTMP basic headers and extract message-type
                                // for chunks with fmt 0 or 1 (where the type byte is present). This is log-only.
                                try {
                                    var audioCount = 0
                                    var videoCount = 0
                                    var dataCount = 0
                                    val foundOffsets = mutableListOf<Int>()
                                    var i = 0
                                    while (i < copy.size) {
                                        val remaining = copy.size - i
                                        val b0 = copy[i].toInt() and 0xff
                                        val fmt = (b0 shr 6) and 0x03
                                        var cid = b0 and 0x3f
                                        var basicLen = 1
                                        if (cid == 0) {
                                            if (remaining < 2) break
                                            cid = 64 + (copy[i + 1].toInt() and 0xff)
                                            basicLen = 2
                                        } else if (cid == 1) {
                                            if (remaining < 3) break
                                            cid = 64 + (copy[i + 1].toInt() and 0xff) + ((copy[i + 2].toInt() and 0xff) shl 8)
                                            basicLen = 3
                                        }

                                        try {
                                            if (fmt == 0) {
                                                val need = basicLen + 11
                                                if (remaining < need) { i += 1; continue }
                                                val ts = ((copy[i + basicLen].toInt() and 0xff) shl 16) or
                                                        ((copy[i + basicLen + 1].toInt() and 0xff) shl 8) or
                                                        (copy[i + basicLen + 2].toInt() and 0xff)
                                                val msgLen = ((copy[i + basicLen + 3].toInt() and 0xff) shl 16) or
                                                        ((copy[i + basicLen + 4].toInt() and 0xff) shl 8) or
                                                        (copy[i + basicLen + 5].toInt() and 0xff)
                                                val t = (copy[i + basicLen + 6].toInt() and 0xff)
                                                var headerExtra = 0
                                                if (ts == 0xffffff) {
                                                    if (remaining < need + 4) { i += 1; continue }
                                                    headerExtra = 4
                                                }
                                                val totalLen = basicLen + 11 + headerExtra + msgLen
                                                if (remaining < totalLen) { i += 1; continue }
                                                when (t) {
                                                    8 -> { audioCount += 1; foundOffsets.add(i) }
                                                    9 -> { videoCount += 1; foundOffsets.add(i) }
                                                    18 -> { dataCount += 1; foundOffsets.add(i) }
                                                }
                                                i += totalLen
                                                continue
                                            } else if (fmt == 1) {
                                                val need = basicLen + 7
                                                if (remaining < need) { i += 1; continue }
                                                val ts = ((copy[i + basicLen].toInt() and 0xff) shl 16) or
                                                        ((copy[i + basicLen + 1].toInt() and 0xff) shl 8) or
                                                        (copy[i + basicLen + 2].toInt() and 0xff)
                                                val msgLen = ((copy[i + basicLen + 3].toInt() and 0xff) shl 16) or
                                                        ((copy[i + basicLen + 4].toInt() and 0xff) shl 8) or
                                                        (copy[i + basicLen + 5].toInt() and 0xff)
                                                val t = (copy[i + basicLen + 6].toInt() and 0xff)
                                                var headerExtra = 0
                                                if (ts == 0xffffff) {
                                                    if (remaining < need + 4) { i += 1; continue }
                                                    headerExtra = 4
                                                }
                                                val totalLen = basicLen + 7 + headerExtra + msgLen
                                                if (remaining < totalLen) { i += 1; continue }
                                                when (t) {
                                                    8 -> { audioCount += 1; foundOffsets.add(i) }
                                                    9 -> { videoCount += 1; foundOffsets.add(i) }
                                                    18 -> { dataCount += 1; foundOffsets.add(i) }
                                                }
                                                i += totalLen
                                                continue
                                            }
                                        } catch (e: Exception) {
                                            i += 1
                                            continue
                                        }

                                        i += 1
                                    }
                                    val previewLen = minOf(200, copy.size)
                                    val hexPreview = copy.take(previewLen).joinToString(" ") { String.format("%02x", it) }
                                    Log.i(TAGS, "Publish monitor RTMP scan: audio=$audioCount video=$videoCount data=$dataCount detections=${foundOffsets.size} hexPreview(len=${previewLen})=$hexPreview")
                                } catch (e: Exception) {
                                    Log.i(TAGS, "Error scanning recent inbound bytes: ${e.message}")
                                }
                            } catch (e: Exception) {
                                Log.i(TAGS, "Error in publish monitor dump: ${e.message}")
                            }
                            // send a diagnostic onStatus to the publisher (log-only, no files written)
                            val diag = buildOnStatus("error", "NetStream.Publish.NoMedia", "No media frames received")
                            try {
                                val db64 = android.util.Base64.encodeToString(diag, android.util.Base64.NO_WRAP)
                                Log.i(TAGS, "Sending diagnostic onStatus base64(len=${diag.size})=$db64")
                            } catch (e: Exception) { /* ignore */ }
                            sendRtmpMessage(18, pubStream, diag)
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
                        // Use the client's message stream id for play responses (RTMP semantics)
                        playStreamId = if (msgStreamId != 0) msgStreamId else {
                            // fallback to allocating if none provided
                            lastStreamIdAllocated += 1
                            lastStreamIdAllocated
                        }
                        // record the stream name for cleanup and diagnostics
                        playStreamName = full
                        // update publisher session state (publisher is active)
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
                            // mirror Node-Media-Server: emit postPlay after the player has been sent Play.Start and cached headers
                            NodeEventBus.emit("postPlay", sessionId, full, null)
                        } catch (e: Exception) {
                            Log.i(TAGS, "Error sending cached seq headers: ${e.message}")
                        }
                    } else {
                        Log.i(TAGS, "No publisher for $full — queuing player until publisher appears")
                        // preserve the client's requested play stream id so publisher can reply on it later
                        playStreamName = full
                        if (msgStreamId != 0) playStreamId = msgStreamId
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
            "closeStream" -> {
                // client wants to close an outgoing stream
                Log.i(TAGS, "closeStream received for session#$sessionId")
                // if we were publishing, tidy up
                if (isPublishing) {
                    publishStreamName?.let { key ->
                        // notify attached players
                        try {
                            val notify = buildOnStatus("status", "NetStream.Unpublish.Notify", "Publisher ended")
                            val stopPub = buildOnStatus("status", "NetStream.Publish.Stop", "Stopped")
                            val pubStream = if (publishStreamId != 0) publishStreamId else 1
                            for (p in players) {
                                try {
                                    val outStreamId = if (p.playStreamId != 0) p.playStreamId else 1
                                    p.sendRtmpMessage(18, outStreamId, notify)
                                } catch (_: Exception) { }
                            }
                            // send Publish.Stop to publisher
                            sendRtmpMessage(18, pubStream, stopPub)
                        } catch (_: Exception) { }
                        streams.remove(key)
                        RtmpServerState.unregisterStream(key)
                        NodeEventBus.emit("donePublish", sessionId, key, publishStreamKey)
                    }
                    isPublishing = false
                }
                // reply _result
                val transId = amf.readAmf0() as? Double ?: 0.0
                val resp = NodeCoreAmf.encodeAmf0Cmd(mapOf("cmd" to "_result", "transId" to transId, "cmdObj" to null))
                sendRtmpMessage(20, 0, resp)
            }
            "deleteStream" -> {
                Log.i(TAGS, "deleteStream received for session#$sessionId")
                // read possible stream id argument
                try {
                    val sid = amf.readAmf0()
                    // best-effort cleanup if it matches our publishStreamId
                    if (sid is Double && sid.toInt() == publishStreamId) {
                        publishStreamName?.let { key ->
                            try {
                                val notify = buildOnStatus("status", "NetStream.Unpublish.Notify", "Publisher ended")
                                val stopPub = buildOnStatus("status", "NetStream.Publish.Stop", "Stopped")
                                val pubStream = if (publishStreamId != 0) publishStreamId else 1
                                for (p in players) {
                                    try { p.sendRtmpMessage(18, if (p.playStreamId != 0) p.playStreamId else 1, notify) } catch (_: Exception) {}
                                }
                                sendRtmpMessage(18, pubStream, stopPub)
                            } catch (_: Exception) { }
                            streams.remove(key)
                            RtmpServerState.unregisterStream(key)
                            NodeEventBus.emit("donePublish", sessionId, key, publishStreamKey)
                        }
                        isPublishing = false
                    }
                } catch (_: Exception) { }
                val transId = amf.readAmf0() as? Double ?: 0.0
                val resp = NodeCoreAmf.encodeAmf0Cmd(mapOf("cmd" to "_result", "transId" to transId, "cmdObj" to null))
                sendRtmpMessage(20, 0, resp)
            }
            "releaseStream" -> {
                Log.i(TAGS, "releaseStream received for session#$sessionId")
                val transId = amf.readAmf0() as? Double ?: 0.0
                val resp = NodeCoreAmf.encodeAmf0Cmd(mapOf("cmd" to "_result", "transId" to transId, "cmdObj" to null))
                sendRtmpMessage(20, 0, resp)
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

    internal fun sendRtmpMessage(type: Int, streamId: Int, payload: ByteArray, timestamp: Int = 0) {
        // choose channel id similar to Node-Media-Server conventions
        // invoke -> channel 3, audio -> 4, video -> 5, data -> 6
        val cid = when (type) {
            20 -> RtmpChannels.INVOKE
            8 -> RtmpChannels.AUDIO
            9 -> RtmpChannels.VIDEO
            // protocol/control messages map to protocol channel
            1, 3, 4, 5, 6 -> RtmpChannels.PROTOCOL
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

    // Helper to perform the standard connect response sequence (control frames then AMF _result).
    internal fun performConnectResponse(transId: Double, useAmf3: Boolean) {
        val resp = if (useAmf3) {
            RtmpServerState.recordAmf3Usage(sessionId)
            buildConnectResultAmf3(transId)
        } else buildConnectResult(transId)

        try {
            outChunkSize = 8192
            val setChunkPayload = ByteArray(4)
            setChunkPayload[0] = ((outChunkSize shr 24) and 0xff).toByte()
            setChunkPayload[1] = ((outChunkSize shr 16) and 0xff).toByte()
            setChunkPayload[2] = ((outChunkSize shr 8) and 0xff).toByte()
            setChunkPayload[3] = (outChunkSize and 0xff).toByte()
            // send control frames first
            sendRtmpMessage(1, 0, setChunkPayload)

            val windowAck = 2 * 1024 * 1024
            val winPayload = ByteArray(4)
            winPayload[0] = ((windowAck shr 24) and 0xff).toByte()
            winPayload[1] = ((windowAck shr 16) and 0xff).toByte()
            winPayload[2] = ((windowAck shr 8) and 0xff).toByte()
            winPayload[3] = (windowAck and 0xff).toByte()
            sendRtmpMessage(5, 0, winPayload)

            val pb = ByteArray(5)
            pb[0] = ((windowAck shr 24) and 0xff).toByte()
            pb[1] = ((windowAck shr 16) and 0xff).toByte()
            pb[2] = ((windowAck shr 8) and 0xff).toByte()
            pb[3] = (windowAck and 0xff).toByte()
            pb[4] = 2
            sendRtmpMessage(6, 0, pb)
        } catch (e: Exception) {
            Log.i(TAGS, "Error sending server-side control messages: ${e.message}")
        }

        // outbound golden compare (best-effort)
        try {
            val suffixResp = if (useAmf3) "amf3.hex" else "amf0.hex"
            val candidatesResp = listOf("connect_result_$suffixResp")
            val goldenResp = GoldenComparator.resolveExistingGoldenName(candidatesResp)
            if (goldenResp != null) GoldenComparator.compare(sessionId, "connect_result", goldenResp, resp)
        } catch (e: Exception) { Log.i(TAGS, "Golden comparator (outbound) error: ${e.message}") }

        sendRtmpMessage(20, 0, resp)
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
