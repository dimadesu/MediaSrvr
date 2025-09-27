package com.dimadesu.mediasrvr

/** Parsed audio metadata (best-effort). */
data class AudioMetadata(
    val isSequenceHeader: Boolean,
    val soundFormat: Int,
    val aacProfile: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null
)

/** Parsed video metadata (best-effort). */
data class VideoMetadata(
    val isSequenceHeader: Boolean,
    val codecId: Int,
    val avcPacketType: Int? = null,
    val profile: Int? = null,
    val level: Int? = null,
    val width: Int? = null,
    val height: Int? = null
)

/**
 * Typed delegate callbacks for the RTMP server.
 * Provides parsed metadata along with raw payloads for convenience.
 */
interface RtmpServerDelegate {
    /** Called when a publisher starts publishing a stream. */
    fun onPublishStart(streamKey: String, sessionId: Int)

    /** Called when a publisher stops publishing a stream with an optional reason. */
    fun onPublishStop(streamKey: String, sessionId: Int, reason: String?)

    /** Delivery of a raw video payload plus best-effort parsed metadata. */
    fun onVideoBuffer(sessionId: Int, sampleBytes: ByteArray, meta: VideoMetadata?)

    /** Delivery of a raw audio payload plus best-effort parsed metadata. */
    fun onAudioBuffer(sessionId: Int, sampleBytes: ByteArray, meta: AudioMetadata?)

    /** Optional: notify server to adjust target latencies for a publisher */
    fun setTargetLatencies(sessionId: Int, videoMs: Double, audioMs: Double)
}
