package com.dimadesu.mediasrvr

import org.junit.Assert.*
import org.junit.Test

class NodeCoreAmfRoundTripTest {

    @Test
    fun testCreateStreamResultAmf0RoundTrip() {
        val transId = 2.0
        val streamId = 42
        val buf = NodeCoreAmf.amf0Encode(listOf<Any?>("_result", transId, null, streamId.toDouble()))

        val p = Amf0Parser(buf)
        val cmd = p.readAmf0() as? String
        val t = p.readAmf0() as? Double
        val cmdObj = p.readAmf0()
        val sid = p.readAmf0() as? Double

        assertEquals("_result", cmd)
        assertEquals(transId, t)
        assertNull(cmdObj)
        assertEquals(streamId.toDouble(), sid)
    }

    @Test
    fun testCreateStreamResultAmf3RoundTrip() {
        val transId = 3
        val streamId = 100
        val cmdPart = NodeCoreAmf.amf0encString("_result")
        val enc = Amf3Encoder()
        enc.writeValue(transId)
        enc.writeValue(null)
        enc.writeValue(streamId)
        val buf = cmdPart + enc.toByteArray()

        val p0 = Amf0Parser(buf)
        val cmd = p0.readAmf0() as? String
        val remaining = buf.copyOfRange(p0.pos, buf.size)
        val p3 = Amf3Parser(remaining, 0)
        val t = p3.readAmf3()
        val cmdObj = p3.readAmf3()
        val sid = p3.readAmf3()

        assertEquals("_result", cmd)
        assertEquals(transId, t)
        assertNull(cmdObj)
        assertEquals(streamId, sid)
    }

    @Test
    fun testOnStatusAmf0RoundTrip() {
        val opt = mapOf<String, Any?>(
            "cmd" to "onStatus",
            "transId" to 0.0,
            "cmdObj" to null,
            "info" to mapOf("level" to "status", "code" to "NetStream.Play.Start")
        )
        val buf = NodeCoreAmf.encodeAmf0Cmd(opt)
        val p = Amf0Parser(buf)
        val cmd = p.readAmf0() as? String
        val trans = p.readAmf0() as? Double
        val cmdObj = p.readAmf0()
        val info = p.readAmf0() as? Map<*, *>

        assertEquals("onStatus", cmd)
        assertEquals(0.0, trans)
        assertNull(cmdObj)
        assertEquals("status", info?.get("level"))
        assertEquals("NetStream.Play.Start", info?.get("code"))
    }

    @Test
    fun testPublishAmf0RoundTrip() {
        val opt = mapOf<String, Any?>(
            "cmd" to "publish",
            "transId" to 0.0,
            "cmdObj" to null,
            "streamName" to "mystream",
            "type" to "live"
        )
        val buf = NodeCoreAmf.encodeAmf0Cmd(opt)
        val p = Amf0Parser(buf)
        val cmd = p.readAmf0() as? String
        val trans = p.readAmf0() as? Double
        val cmdObj = p.readAmf0()
        val name = p.readAmf0() as? String
        val type = p.readAmf0() as? String

        assertEquals("publish", cmd)
        assertEquals(0.0, trans)
        assertNull(cmdObj)
        assertEquals("mystream", name)
        assertEquals("live", type)
    }
}
