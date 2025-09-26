package com.dimadesu.mediasrvr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.experimental.and

/**
 * Minimal RTMP server service. Accepts TCP connections and performs the RTMP complex handshake
 * (S0 S1 S2) similarly to Node-Media-Server's handshake implementation.
 *
 * This implementation focuses on the handshake only (no RTMP message parsing yet).
 */
class RtmpServerService : Service() {
    private val TAG = "RtmpServerService"
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val rtmpPort = 1935

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serverScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val channelId = "rtmp_server_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "RTMP Server", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(chan)
        }
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("RTMP Server")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n)
    }

    private fun startServer() {
        serverJob = serverScope.launch {
            try {
                serverSocket = ServerSocket(rtmpPort)
                Log.i(TAG, "RTMP server listening on port $rtmpPort")
                while (isActive) {
                    try {
                        val client = serverSocket!!.accept()
                        Log.i(TAG, "Accepted connection from ${client.inetAddress.hostAddress}")
                        handleClient(client)
                    } catch (e: Exception) {
                        // Accept will throw when the server socket is closed during shutdown.
                        // Treat that as a normal shutdown signal and exit the loop quietly.
                        if (serverSocket == null || serverSocket!!.isClosed || !isActive) {
                            Log.i(TAG, "Server socket closed, stopping accept loop")
                            break
                        } else {
                            Log.e(TAG, "Accept failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
    }

    private fun stopServer() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverJob?.cancel()
    }

    private fun handleClient(client: Socket) {
        serverScope.launch {
            client.use { sock ->
                try {
                    sock.soTimeout = 0
                    val input = DataInputStream(sock.getInputStream())
                    val output = DataOutputStream(sock.getOutputStream())

                    // Read C0 (1 byte) + C1 (1536 bytes)
                    val c0 = input.readUnsignedByte()
                    val c1 = ByteArray(1536)
                    input.readFully(c1)
                    Log.i(TAG, "Received C0=${c0}")

                    // Build S0+S1+S2
                    val s0s1s2 = Handshake.generateS0S1S2(c1)

                    // Write S0 S1 S2
                    output.write(s0s1s2)
                    output.flush()
                    Log.i(TAG, "Sent S0 S1 S2 to client")

                    // Read C2 (1536)
                    val c2 = ByteArray(1536)
                    input.readFully(c2)
                    Log.i(TAG, "Received C2 from client. Handshake complete.")

                    // Keep connection open - for now just sleep and log
                    // Start RTMP session handler and wait until it finishes before closing socket
                    val sessionId = connectionIdCounter.getAndIncrement()
                    val session = RtmpSession(sessionId, sock, input, output)
                    val job = session.run()
                    // wait for the session to complete (suspends here)
                    job.join()
                } catch (e: Exception) {
                    Log.e(TAG, "Client handler error", e)
                }
            }
        }
    }

    private val connectionIdCounter = AtomicInteger(1)

    // Global registry of streams -> publisher session
    private val streams = mutableMapOf<String, RtmpSession>()
    // If a player connects before a publisher, queue players here and attach when publisher arrives
    private val waitingPlayers = mutableMapOf<String, MutableList<RtmpSession>>()

    // helper classes for chunk reassembly
    private class HeaderState(var timestamp: Int, var length: Int, var type: Int, var streamId: Int)
    private class InPacket(var totalLength: Int, var type: Int, var streamId: Int) {
        var buffer: ByteArray = ByteArray(totalLength)
        var received: Int = 0
        var bytesReadSinceStart: Int = 0
        var lastAck: Int = 0
        var ackWindow: Int = 0
    }

    inner class RtmpSession(
        private val sessionId: Int,
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: DataOutputStream
    ) {
    private val TAGS = "RtmpSession"
    private var inChunkSize = 128
    private var outChunkSize = 128
    private var streamIdCounter = 1
    private var lastStreamIdAllocated = 0

    var publishStreamId: Int = 0
    var playStreamId: Int = 0
        // ACK/window tracking
        private var ackWindowSize: Int = 0
        private var inBytesSinceStart: Long = 0L
        private var lastAckSent: Long = 0L

        // cached sequence headers
        private var aacSequenceHeader: ByteArray? = null
        private var avcSequenceHeader: ByteArray? = null

        var appName: String = ""
        var publishStreamName: String? = null
        var isPublishing = false
        var publishStreamKey: String? = null

        // players subscribed to a stream name -> list of sessions
        private val players = mutableSetOf<RtmpSession>()

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
                        val preview = payload.take(12).joinToString(" ") { String.format("%02x", it) }
                        Log.i(TAGS, "Received av type=$type ts=$timestamp len=${payload.size} isPublishing=$isPublishing publishName=$publishStreamName preview=$preview")
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
                                        Log.i(TAGS, "Cached AAC sequence header, len=${aacSequenceHeader?.size}")
                                    }
                                } else if (type == 9 && payload.isNotEmpty()) {
                                    // video: check AVC sequence header
                                    val codecId = payload[0].toInt() and 0x0f
                                    // AVC (H.264) codec id = 7
                                    if (codecId == 7 && payload.size > 1) {
                                        val avcPacketType = payload[1].toInt() and 0xff
                                        if (avcPacketType == 0) {
                                            avcSequenceHeader = payload.copyOf()
                                            Log.i(TAGS, "Cached AVC sequence header, len=${avcSequenceHeader?.size}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.i(TAGS, "Error detecting sequence header: ${e.message}")
                            }
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
                        handleCommand(cmd, amf, streamId)
                    }
                }
                else -> {
                    Log.d(TAGS, "Unhandled msg type=$type len=${payload.size}")
                }
            }
        }

        private fun handleCommand(cmd: String, amf: Amf0Parser, msgStreamId: Int) {
            when (cmd) {
                "connect" -> {
                    val transId = amf.readAmf0() as? Double ?: 0.0
                    val obj = amf.readAmf0() as? Map<*, *>
                    if (obj != null && obj["app"] is String) {
                        appName = obj["app"] as String
                    }
                    // send _result for connect
                    val trans = transId
                    val resp = buildConnectResult(trans)
                    sendRtmpMessage(20, 0, resp)
                    Log.i(TAGS, "Handled connect, app=$appName")
                }
                "createStream" -> {
                    val transId = amf.readAmf0() as? Double ?: 0.0
                    // allocate a stream id local to this session
                    lastStreamIdAllocated += 1
                    val streamId = lastStreamIdAllocated
                    // track publish/play stream ids accordingly (createStream is typically used by both)
                    val resp = buildCreateStreamResult(transId, streamId)
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
                        val notif = buildOnStatus("status", "NetStream.Publish.Start", "Publishing")
                        sendRtmpMessage(18, 1, notif) // data message
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
                                    // send cached seq headers
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
                            val notif = buildOnStatus("status", "NetStream.Play.Start", "Playing")
                            sendRtmpMessage(18, playStreamId, notif)
                                // send cached sequence headers (if any) to the newly joined player
                                try {
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
                    val preview = payload.take(16).joinToString(" ") { String.format("%02x", it) }
                    Log.i(TAGS, "Forwarding type=$type ts=$timestamp len=${payload.size} -> player#${p.sessionId} outStreamId=$outStreamId preview=$preview")
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

        private fun buildCreateStreamResult(transId: Double, streamId: Int): ByteArray {
            val baos = java.io.ByteArrayOutputStream()
            baos.write(buildStringAmf("_result"))
            baos.write(buildNumberAmf(transId))
            baos.write(5) // null
            baos.write(buildNumberAmf(streamId.toDouble()))
            return baos.toByteArray()
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
    }

    // Minimal AMF0 parser used for the subset of messages we need
    class Amf0Parser(private val data: ByteArray) {
        private var pos = 0

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

        /**
         * Return a list of decoded AMF0 values for the remaining bytes without
         * advancing this parser's position (uses a copy of the remaining buffer).
         */
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
    }

    object Handshake {
        private const val SIG_SIZE = 1536
        private const val SHA256DL = 32
        private val randomCrud = byteArrayOf(
            0xf0.toByte(), 0xee.toByte(), 0xc2.toByte(), 0x4a.toByte(), 0x80.toByte(), 0x68.toByte(), 0xbe.toByte(), 0xe8.toByte(),
            0x2e.toByte(), 0x00.toByte(), 0xd0.toByte(), 0xd1.toByte(), 0x02.toByte(), 0x9e.toByte(), 0x7e.toByte(), 0x57.toByte(),
            0x6e.toByte(), 0xec.toByte(), 0x5d.toByte(), 0x2d.toByte(), 0x29.toByte(), 0x80.toByte(), 0x6f.toByte(), 0xab.toByte(),
            0x93.toByte(), 0xb8.toByte(), 0xe6.toByte(), 0x36.toByte(), 0xcf.toByte(), 0xeb.toByte(), 0x31.toByte(), 0xae.toByte()
        )

        private val genuineFmsConst = ("Genuine Adobe Flash Media Server 001").toByteArray(Charsets.UTF_8)
        private val genuineFpConst = ("Genuine Adobe Flash Player 001").toByteArray(Charsets.UTF_8)
        private val genuineFmsConstCrud = genuineFmsConst + randomCrud
        private val genuineFpConstCrud = genuineFpConst + randomCrud

        private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            val sk = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
            mac.init(sk)
            return mac.doFinal(data)
        }

        private fun uInt32ToInt(b: Int): Int = b and 0xFFFFFFFF.toInt()

        private fun getClientDigestOffset(buf: ByteArray): Int {
            val offset = (buf[0].toInt() and 0xFF) + (buf[1].toInt() and 0xFF) + (buf[2].toInt() and 0xFF) + (buf[3].toInt() and 0xFF)
            return (offset % 728) + 12
        }

        private fun getServerDigestOffset(buf: ByteArray): Int {
            val offset = (buf[0].toInt() and 0xFF) + (buf[1].toInt() and 0xFF) + (buf[2].toInt() and 0xFF) + (buf[3].toInt() and 0xFF)
            return (offset % 728) + 776
        }

        private fun detectClientFormat(clientsig: ByteArray): Int {
            // Try server digest at 772..776
            val sliceForServer = clientsig.sliceArray(772 until 776)
            val sdl = getServerDigestOffset(sliceForServer)
            val msg = ByteArray(SIG_SIZE - SHA256DL)
            System.arraycopy(clientsig, 0, msg, 0, sdl)
            System.arraycopy(clientsig, sdl + SHA256DL, msg, sdl, SIG_SIZE - SHA256DL - sdl)
            val computed = hmacSha256(msg, genuineFpConst)
            val provided = clientsig.sliceArray(sdl until sdl + SHA256DL)
            if (computed.contentEquals(provided)) return 2

            val sliceForClient = clientsig.sliceArray(8 until 12)
            val sdl2 = getClientDigestOffset(sliceForClient)
            val msg2 = ByteArray(SIG_SIZE - SHA256DL)
            System.arraycopy(clientsig, 0, msg2, 0, sdl2)
            System.arraycopy(clientsig, sdl2 + SHA256DL, msg2, sdl2, SIG_SIZE - SHA256DL - sdl2)
            val computed2 = hmacSha256(msg2, genuineFpConst)
            val provided2 = clientsig.sliceArray(sdl2 until sdl2 + SHA256DL)
            if (computed2.contentEquals(provided2)) return 1

            return 0
        }

        private fun generateS1(messageFormat: Int): ByteArray {
            val randomBytes = ByteArray(SIG_SIZE - 8)
            java.util.Random().nextBytes(randomBytes)
            val handshake = ByteArray(SIG_SIZE)
            // time(4) + zero(4)
            // use zeros for time/zeros are acceptable
            // set time to 0
            System.arraycopy(byteArrayOf(0, 0, 0, 0, 1, 2, 3, 4), 0, handshake, 0, 8)
            System.arraycopy(randomBytes, 0, handshake, 8, randomBytes.size)

            val serverDigestOffset = if (messageFormat == 1) getClientDigestOffset(handshake.sliceArray(8 until 12)) else getServerDigestOffset(handshake.sliceArray(772 until 776))
            val msg = ByteArray(SIG_SIZE - SHA256DL)
            System.arraycopy(handshake, 0, msg, 0, serverDigestOffset)
            System.arraycopy(handshake, serverDigestOffset + SHA256DL, msg, serverDigestOffset, SIG_SIZE - SHA256DL - serverDigestOffset)
            val hash = hmacSha256(msg, genuineFmsConst)
            System.arraycopy(hash, 0, handshake, serverDigestOffset, SHA256DL)
            return handshake
        }

        private fun generateS2(messageFormat: Int, clientsig: ByteArray): ByteArray {
            val randomBytes = ByteArray(SIG_SIZE - SHA256DL)
            java.util.Random().nextBytes(randomBytes)
            val challengeKeyOffset = if (messageFormat == 1) getClientDigestOffset(clientsig.sliceArray(8 until 12)) else getServerDigestOffset(clientsig.sliceArray(772 until 776))
            val challengeKey = clientsig.sliceArray(challengeKeyOffset until challengeKeyOffset + SHA256DL)
            val hash = hmacSha256(challengeKey, genuineFmsConstCrud)
            val signature = hmacSha256(randomBytes, hash)
            val s2 = ByteArray(SIG_SIZE)
            System.arraycopy(randomBytes, 0, s2, 0, randomBytes.size)
            System.arraycopy(signature, 0, s2, randomBytes.size, signature.size)
            return s2
        }

        fun generateS0S1S2(clientsig: ByteArray): ByteArray {
            val messageFormat = detectClientFormat(clientsig)
            return if (messageFormat == 0) {
                // simple: S0S1S2 = C0 C1 C1
                val out = ByteArray(1 + SIG_SIZE + SIG_SIZE)
                out[0] = 3
                System.arraycopy(clientsig, 0, out, 1, SIG_SIZE)
                System.arraycopy(clientsig, 0, out, 1 + SIG_SIZE, SIG_SIZE)
                out
            } else {
                val s1 = generateS1(messageFormat)
                val s2 = generateS2(messageFormat, clientsig)
                val out = ByteArray(1 + SIG_SIZE + SIG_SIZE)
                out[0] = 3
                System.arraycopy(s1, 0, out, 1, SIG_SIZE)
                System.arraycopy(s2, 0, out, 1 + SIG_SIZE, SIG_SIZE)
                out
            }
        }
    }
}
