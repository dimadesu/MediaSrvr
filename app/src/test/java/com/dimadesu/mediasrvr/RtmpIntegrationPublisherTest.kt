package com.dimadesu.mediasrvr

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class RtmpIntegrationPublisherTest {

    @Test
    fun testSimpleTwoChunkAudioReassembly() {
        // Build a message: audio type=8, streamId=1, timestamp=0, payload="AUDIO" (5 bytes)
        val payload = "AUDIO".toByteArray(Charsets.US_ASCII)
        val payloadLen = payload.size
        val timestamp3 = byteArrayOf(0x00, 0x00, 0x00)
        val len3 = byteArrayOf(((payloadLen shr 16) and 0xff).toByte(), ((payloadLen shr 8) and 0xff).toByte(), (payloadLen and 0xff).toByte())
        val msgType = 8.toByte()
        val streamId = byteArrayOf(0x01, 0x00, 0x00, 0x00) // little-endian

        // Choose cid=3, fmt=0 for first chunk
        val basicHdr1 = byteArrayOf(((0 and 0x03) shl 6 or (3 and 0x3f)).toByte())
        val header = ByteArray(11)
        System.arraycopy(timestamp3, 0, header, 0, 3)
        System.arraycopy(len3, 0, header, 3, 3)
        header[6] = msgType
        System.arraycopy(streamId, 0, header, 7, 4)

        // inChunkSize smaller than payload to force chunking
        val inChunkSize = 3

        // first payload chunk = first 3 bytes
        val p1 = payload.copyOfRange(0, 3)
        // continuation basic header fmt=3 cid=3
        val basicHdr2 = byteArrayOf(((3 and 0x03) shl 6 or (3 and 0x3f)).toByte())
        val p2 = payload.copyOfRange(3, payloadLen)

        // assemble wire bytes: basicHdr1 + header + p1 + basicHdr2 + p2
        val out = ByteArray(basicHdr1.size + header.size + p1.size + basicHdr2.size + p2.size)
        var pos = 0
        System.arraycopy(basicHdr1, 0, out, pos, basicHdr1.size); pos += basicHdr1.size
        System.arraycopy(header, 0, out, pos, header.size); pos += header.size
        System.arraycopy(p1, 0, out, pos, p1.size); pos += p1.size
        System.arraycopy(basicHdr2, 0, out, pos, basicHdr2.size); pos += basicHdr2.size
        System.arraycopy(p2, 0, out, pos, p2.size); pos += p2.size

        val dis = DataInputStream(ByteArrayInputStream(out))

        // simulate the session read loop for this single cid
        val cid = 3
        val cs = RtmpChunkStream(cid, null)
        var prevHeader: HeaderState? = null

        // read first basic header
        val header0 = dis.readUnsignedByte()
        val fmt = header0 shr 6
        var readCid = header0 and 0x3f
        if (readCid == 0) {
            readCid = 64 + dis.readUnsignedByte()
        } else if (readCid == 1) {
            val b1 = dis.readUnsignedByte()
            val b2 = dis.readUnsignedByte()
            readCid = 64 + b1 + (b2 shl 8)
        }
        assertEquals(cid, readCid)

        val ts = cs.readAndUpdateHeader(fmt, dis, prevHeader)
        // read first chunk payload
        val toRead1 = cs.getChunkDataSize(inChunkSize)
        val tmp1 = ByteArray(toRead1)
        dis.readFully(tmp1)
        cs.appendBytes(tmp1, 0, tmp1.size)

        prevHeader = cs.header.copy()

        // read continuation basic header
        val header1 = dis.readUnsignedByte()
        val fmt1 = header1 shr 6
        var cid1 = header1 and 0x3f
        if (cid1 == 0) cid1 = 64 + dis.readUnsignedByte() else if (cid1 == 1) {
            val b1 = dis.readUnsignedByte(); val b2 = dis.readUnsignedByte(); cid1 = 64 + b1 + (b2 shl 8)
        }
        assertEquals(cid, cid1)

        val ts2 = cs.readAndUpdateHeader(fmt1, dis, prevHeader)
        val toRead2 = cs.getChunkDataSize(inChunkSize)
        val tmp2 = ByteArray(toRead2)
        dis.readFully(tmp2)
        cs.appendBytes(tmp2, 0, tmp2.size)

        val cp = cs.getCompletedPacketIfComplete(ts2)
        assertNotNull("CompletedPacket should be produced", cp)
        cp?.let {
            assertEquals(8, it.type)
            assertEquals(1, it.streamId)
            assertEquals(payloadLen, it.payload.size)
            assertArrayEquals(payload, it.payload)
        }
    }
}
