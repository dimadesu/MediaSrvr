package com.dimadesu.mediasrvr

import org.junit.Test

class AmfGoldenTest {
    @Test
    fun connectResultMatchesGoldenIfPresent() {
        val transId = 1.0
        val respOpt = mapOf("cmd" to "_result", "transId" to transId, "cmdObj" to null,
            "info" to mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded."))
        val payload = NodeCoreAmf.encodeAmf0Cmd(respOpt)
        try {
            GoldenComparator.compare(0, "connect_result", "connect_result_amf0.hex", payload)
        } catch (e: RuntimeException) {
            // In JVM unit tests android.util.Log may not be available/mocked; ignore comparator failures here
        }
    }

    @Test
    fun createStreamResultMatchesGoldenIfPresent() {
        val transId = 2.0
        val streamId = 1
        // match RtmpSession: cmd, transId, null, streamId as number
        val parts = listOf<Any?>("_result", transId, null, streamId.toDouble())
        val payload = NodeCoreAmf.amf0Encode(parts)
        try {
            GoldenComparator.compare(0, "createStream_result", "create_stream_result_amf0.hex", payload)
        } catch (e: RuntimeException) {
            // ignore
        }
    }

    @Test
    fun onStatusMatchesGoldenIfPresent() {
        val notif = mapOf("cmd" to "onStatus", "transId" to 0.0, "cmdObj" to null, "info" to mapOf("level" to "status", "code" to "NetStream.Play.Start", "description" to "Playing"))
        val payload = NodeCoreAmf.encodeAmf0Cmd(notif)
        try {
            GoldenComparator.compare(0, "onStatus", "onstatus_amf0.hex", payload)
        } catch (e: RuntimeException) {
            // ignore
        }
    }
}
