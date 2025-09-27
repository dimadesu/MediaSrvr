package com.dimadesu.mediasrvr

import org.junit.Assume
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class GoldenAmfComparisonTest {

    private fun loadGolden(name: String): ByteArray? {
        val f = File("inspiration/golden/$name.hex")
        if (!f.exists()) return null
        val hex = f.readText().trim()
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte()
        return out
    }

    @Test
    fun compareConnectResultAmf0() {
        val gold = loadGolden("connect_result_amf0")
        Assume.assumeTrue("Golden files missing; generate with: node inspiration/generate_golden_amf.js", gold != null)
        val mine = NodeCoreAmf.encodeAmf0Cmd(mapOf("cmd" to "_result", "transId" to 1.0, "cmdObj" to null, "info" to mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded.")))
        assertEquals(gold!!.size, mine.size)
        assertEquals(gold.toList(), mine.toList())
    }

    @Test
    fun compareOnStatusAmf0() {
        val gold = loadGolden("onstatus_amf0")
        Assume.assumeTrue("Golden files missing; generate with: node inspiration/generate_golden_amf.js", gold != null)
        val mine = NodeCoreAmf.encodeAmf0Cmd(mapOf("cmd" to "onStatus", "transId" to 0.0, "cmdObj" to null, "info" to mapOf("level" to "status", "code" to "NetStream.Play.Start")))
        assertEquals(gold!!.size, mine.size)
        assertEquals(gold.toList(), mine.toList())
    }

    @Test
    fun comparePublishAmf0() {
        val gold = loadGolden("publish_amf0")
        Assume.assumeTrue("Golden files missing; generate with: node inspiration/generate_golden_amf.js", gold != null)
        val mine = NodeCoreAmf.encodeAmf0Cmd(mapOf("cmd" to "publish", "transId" to 0.0, "cmdObj" to null, "streamName" to "mystream", "type" to "live"))
        assertEquals(gold!!.size, mine.size)
        assertEquals(gold.toList(), mine.toList())
    }
}
