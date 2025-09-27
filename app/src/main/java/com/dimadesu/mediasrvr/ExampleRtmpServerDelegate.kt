package com.dimadesu.mediasrvr

import android.util.Log

/**
 * Simple example implementation of [RtmpServerDelegate].
 * - Logs lifecycle events and previews of audio/video payloads.
 * - Keeps a tiny in-memory recent buffer of the last N audio/video payloads for inspection.
 *
 * Use by assigning an instance to `RtmpServerService.delegate` from your application code.
 */
class ExampleRtmpServerDelegate(private val previewBytes: Int = 32, private val maxCache: Int = 64) : RtmpServerDelegate {
    private val TAG = "ExampleRtmpDelegate"

    private val recentAudio = ArrayDeque<ByteArray>()
    private val recentVideo = ArrayDeque<ByteArray>()

    override fun onPublishStart(streamKey: String, sessionId: Int) {
        Log.i(TAG, "onPublishStart session=$sessionId stream=$streamKey")
    }

    override fun onPublishStop(streamKey: String, sessionId: Int, reason: String?) {
        Log.i(TAG, "onPublishStop session=$sessionId stream=$streamKey reason=$reason")
    }

    override fun onVideoBuffer(sessionId: Int, sampleBytes: ByteArray) {
        try {
            val preview = sampleBytes.take(previewBytes).joinToString(" ") { String.format("%02x", it) }
            Log.i(TAG, "onVideoBuffer session=$sessionId len=${sampleBytes.size} preview=$preview")
        } catch (e: Exception) {
            Log.i(TAG, "onVideoBuffer (preview failed): ${e.message}")
        }
        synchronized(recentVideo) {
            if (recentVideo.size >= maxCache) recentVideo.removeFirst()
            recentVideo.addLast(sampleBytes.copyOf())
        }
    }

    override fun onAudioBuffer(sessionId: Int, sampleBytes: ByteArray) {
        try {
            val preview = sampleBytes.take(previewBytes).joinToString(" ") { String.format("%02x", it) }
            Log.i(TAG, "onAudioBuffer session=$sessionId len=${sampleBytes.size} preview=$preview")
        } catch (e: Exception) {
            Log.i(TAG, "onAudioBuffer (preview failed): ${e.message}")
        }
        synchronized(recentAudio) {
            if (recentAudio.size >= maxCache) recentAudio.removeFirst()
            recentAudio.addLast(sampleBytes.copyOf())
        }
    }

    override fun setTargetLatencies(sessionId: Int, videoMs: Double, audioMs: Double) {
        Log.i(TAG, "setTargetLatencies session=$sessionId videoMs=$videoMs audioMs=$audioMs")
    }

    /** Convenience methods for other components to inspect cached frames. */
    fun getRecentAudio(): List<ByteArray> = synchronized(recentAudio) { recentAudio.toList() }
    fun getRecentVideo(): List<ByteArray> = synchronized(recentVideo) { recentVideo.toList() }
}
