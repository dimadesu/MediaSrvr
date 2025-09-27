package com.dimadesu.mediasrvr

import android.media.MediaCodec
import androidx.annotation.UiThread

/**
 * Typed delegate callbacks for the RTMP server.
 * Matches patterns from the Moblin project (publish start/stop, audio/video frames, latency hints).
 */
interface RtmpServerDelegate {
    /** Called when a publisher starts publishing a stream. */
    fun onPublishStart(streamKey: String, sessionId: Int)

    /** Called when a publisher stops publishing a stream with an optional reason. */
    fun onPublishStop(streamKey: String, sessionId: Int, reason: String?)

    /** Delivery of a decoded video buffer (CMSampleBuffer equivalent on Android may be a wrapper) */
    fun onVideoBuffer(sessionId: Int, sampleBytes: ByteArray)

    /** Delivery of a decoded audio buffer */
    fun onAudioBuffer(sessionId: Int, sampleBytes: ByteArray)

    /** Optional: notify server to adjust target latencies for a publisher */
    fun setTargetLatencies(sessionId: Int, videoMs: Double, audioMs: Double)
}
