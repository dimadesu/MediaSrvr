package com.dimadesu.mediasrvr

import org.junit.Assert.*
import org.junit.Test

class RtmpChunkStreamTest {
    @Test
    fun testMultiChunkAssemblyCallsCallback() {
        var called = false
        var receivedType = -1
        var receivedStreamId = -1
        var receivedTs = -1
        var receivedPayload: ByteArray? = null

        val cs = RtmpChunkStream(5, null) { type, streamId, ts, payload ->
            called = true
            receivedType = type
            receivedStreamId = streamId
            receivedTs = ts
            receivedPayload = payload
        }

        // set header for a message of length 10
        cs.updateHeader(123, 10, 9, 7)

        // provide two chunks of 6 and 4 bytes
        val a = byteArrayOf(1,2,3,4,5,6)
        val b = byteArrayOf(7,8,9,10)
        cs.appendBytes(a, 0, a.size)
        assertFalse(cs.isComplete())
        cs.appendBytes(b, 0, b.size)
        assertTrue(cs.isComplete())

        cs.deliverIfComplete(123)

        assertTrue(called)
        assertEquals(9, receivedType)
        assertEquals(7, receivedStreamId)
        assertEquals(123, receivedTs)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,9,10), receivedPayload)
    }
}
