package com.dimadesu.mediasrvr

import org.junit.Assert.*
import org.junit.Test

class NodeCoreAmfTest {

    @Test
    fun testEncodeDecodeAmf0ConnectResultRoundTrip() {
        // build a _result AMF0 message
        val opt = mapOf<String, Any?>(
            "cmd" to "_result",
            "transId" to 1.0,
            "cmdObj" to null,
            "info" to mapOf("level" to "status", "code" to "NetConnection.Connect.Success")
        )
        val buf = NodeCoreAmf.encodeAmf0Cmd(opt)
        // decode using Amf0Parser
        val parser = Amf0Parser(buf)
        val cmd = parser.readAmf0() as? String
        val trans = parser.readAmf0() as? Double
        val cmdObj = parser.readAmf0()
        val info = parser.readAmf0() as? Map<*, *>

        assertEquals("_result", cmd)
        assertEquals(1.0, trans)
        assertEquals(null, cmdObj)
        assertEquals("status", info?.get("level"))
    }

    @Test
    fun testEncodeDecodeAmf3ConnectResultRoundTrip() {
        val opt = mapOf<String, Any?>(
            "cmd" to "_result",
            "transId" to 1,
            "cmdObj" to null,
            "info" to mapOf("level" to "status", "code" to "NetConnection.Connect.Success")
        )
        val buf = NodeCoreAmf.encodeAmf3Cmd(opt)
        // AMF3 commands are AMF0 string (cmd) then AMF3 values — our encode writes the AMF0 cmd first.
        val p0 = Amf0Parser(buf)
        val cmd = p0.readAmf0() as? String
        // read next bytes as AMF3 sequence
        val remaining = buf.copyOfRange(p0.pos, buf.size)
        val p3 = Amf3Parser(remaining, 0)
        val trans = p3.readAmf3()
        val cmdObj = run { val v = p3.readAmf3(); v }
        val info = p3.readAmf3() as? Map<*, *>

        assertEquals("_result", cmd)
        // trans encoded as integer
        assertEquals(1, trans)
        assertEquals(null, cmdObj)
        assertEquals("status", info?.get("level"))
    }
}
