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
        // initialize comparator with app context so it can read bundled asset goldens
        try {
            GoldenComparator.init(this)
        } catch (e: Exception) {
            Log.i(TAG, "GoldenComparator init failed: ${e.message}")
        }
        // No relay/transcode adapters by default (not required)
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
                    val session = RtmpSession(sessionId, sock, input, output, serverScope, streams, waitingPlayers, delegate)
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

    // Optional external delegate that can receive higher-level server events
    // (set by the hosting application to integrate with UI or other components)
    var delegate: RtmpServerDelegate? = null

    // Global registry of streams -> publisher session
    private val streams = mutableMapOf<String, RtmpSession>()
    // If a player connects before a publisher, queue players here and attach when publisher arrives
    private val waitingPlayers = mutableMapOf<String, MutableList<RtmpSession>>()

    // Handshake and AMF parsing moved to separate files: Handshake.kt and Amf0Parser.kt
}
