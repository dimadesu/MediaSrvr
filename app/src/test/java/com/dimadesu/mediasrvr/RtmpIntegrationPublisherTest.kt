package com.dimadesu.mediasrvr

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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

    @Test
    fun testFmt1TimestampDelta() {
        // first message timestamp base=1000, payload "ONE"
        val p1 = "ONE".toByteArray()
        val p2 = "TWO".toByteArray()
        val baseTs = 1000
        val delta = 20

        // build first message fmt=0 cid=3
        val basicHdr1 = byteArrayOf(((0 and 0x03) shl 6 or (3 and 0x3f)).toByte())
        val ts1 = byteArrayOf(((baseTs shr 16) and 0xff).toByte(), ((baseTs shr 8) and 0xff).toByte(), (baseTs and 0xff).toByte())
        val len1 = byteArrayOf(0x00,0x00, p1.size.toByte())
        val type1 = 8.toByte()
        val streamId = byteArrayOf(0x01,0x00,0x00,0x00)
        val header1 = ts1 + len1 + byteArrayOf(type1) + streamId

        // second message fmt=1 header contains ts-delta (3 bytes)
        val basicHdr2 = byteArrayOf(((1 and 0x03) shl 6 or (3 and 0x3f)).toByte())
        val delta3 = byteArrayOf(((delta shr 16) and 0xff).toByte(), ((delta shr 8) and 0xff).toByte(), (delta and 0xff).toByte())
        val len2 = byteArrayOf(0x00,0x00, p2.size.toByte())
        val header2 = delta3 + len2 + byteArrayOf(8.toByte())

        val out = ByteArray(basicHdr1.size + header1.size + p1.size + basicHdr2.size + header2.size + p2.size)
        var pos = 0
        System.arraycopy(basicHdr1,0,out,pos,basicHdr1.size); pos+=basicHdr1.size
        System.arraycopy(header1,0,out,pos,header1.size); pos+=header1.size
        System.arraycopy(p1,0,out,pos,p1.size); pos+=p1.size
        System.arraycopy(basicHdr2,0,out,pos,basicHdr2.size); pos+=basicHdr2.size
        System.arraycopy(header2,0,out,pos,header2.size); pos+=header2.size
        System.arraycopy(p2,0,out,pos,p2.size); pos+=p2.size

        val dis = DataInputStream(ByteArrayInputStream(out))
        val cs = RtmpChunkStream(3, null)
        var prev: HeaderState? = null

        // first basic header
        val h0 = dis.readUnsignedByte(); val fmt = h0 shr 6; var cid = h0 and 0x3f
        if (cid == 0) cid = 64 + dis.readUnsignedByte() else if (cid == 1) { val b1=dis.readUnsignedByte(); val b2=dis.readUnsignedByte(); cid = 64 + b1 + (b2 shl 8) }
        val t1 = cs.readAndUpdateHeader(fmt, dis, prev)
        val toRead1 = cs.getChunkDataSize(1024)
        val tmp1 = ByteArray(toRead1); dis.readFully(tmp1); cs.appendBytes(tmp1,0,tmp1.size)
        prev = cs.header.copy()

        // second basic header (fmt=1)
        val h1 = dis.readUnsignedByte(); val fmt1 = h1 shr 6; var cid1 = h1 and 0x3f
        if (cid1 == 0) cid1 = 64 + dis.readUnsignedByte() else if (cid1 == 1) { val b1=dis.readUnsignedByte(); val b2=dis.readUnsignedByte(); cid1 = 64 + b1 + (b2 shl 8) }
        val t2 = cs.readAndUpdateHeader(fmt1, dis, prev)
        val toRead2 = cs.getChunkDataSize(1024)
        val tmp2 = ByteArray(toRead2); dis.readFully(tmp2); cs.appendBytes(tmp2,0,tmp2.size)

        val cp1 = cs.getCompletedPacketIfComplete(t2)
        // Since we appended both messages into the same cs without resetting between messages, cp1 should represent the second message only
        assertNotNull(cp1)
        cp1?.let {
            assertEquals(8, it.type)
            assertEquals(baseTs + delta, it.timestamp)
        }
    }

    @Test
    fun testExtendedTimestamp() {
        // payload small
        val payload = "EXT".toByteArray()
        val bigTs = 0x01_000000 // 16777216 > 0xFFFFFF

        val basicHdr = byteArrayOf(((0 and 0x03) shl 6 or (3 and 0x3f)).toByte())
        val ts3 = byteArrayOf(0xFF.toByte(),0xFF.toByte(),0xFF.toByte())
        val len3 = byteArrayOf(0x00,0x00,payload.size.toByte())
        val header = ts3 + len3 + byteArrayOf(9.toByte()) + byteArrayOf(0x01,0x00,0x00,0x00)
        val ext = java.nio.ByteBuffer.allocate(4).putInt(bigTs).array()

        val out = ByteArray(basicHdr.size + header.size + ext.size + payload.size)
        var pos = 0
        System.arraycopy(basicHdr,0,out,pos,basicHdr.size); pos+=basicHdr.size
        System.arraycopy(header,0,out,pos,header.size); pos+=header.size
        System.arraycopy(ext,0,out,pos,ext.size); pos+=ext.size
        System.arraycopy(payload,0,out,pos,payload.size); pos+=payload.size

        val dis = DataInputStream(ByteArrayInputStream(out))
        val cs = RtmpChunkStream(3, null)
        val h0 = dis.readUnsignedByte(); val fmt = h0 shr 6; var cid = h0 and 0x3f
        if (cid == 0) cid = 64 + dis.readUnsignedByte() else if (cid == 1) { val b1=dis.readUnsignedByte(); val b2=dis.readUnsignedByte(); cid = 64 + b1 + (b2 shl 8) }
        val resolved = cs.readAndUpdateHeader(fmt, dis, null)
        // should equal bigTs
        assertEquals(bigTs, resolved)
    }

    @Test
    fun testAggregateAndVideoForwarding() {
        // create publisher and player sessions with ByteArrayOutputStreams
        val pubOutBaos = ByteArrayOutputStream()
        val pubOut = DataOutputStream(pubOutBaos)
        val pubIn = DataInputStream(ByteArrayInputStream(ByteArray(0)))
        val playerOutBaos = ByteArrayOutputStream()
        val playerOut = DataOutputStream(playerOutBaos)
        val playerIn = DataInputStream(ByteArrayInputStream(ByteArray(0)))

        val streams = mutableMapOf<String, RtmpSession>()
        val waiting = mutableMapOf<String, MutableList<RtmpSession>>()
        val scope = CoroutineScope(Dispatchers.Default)

        val socketPub = Socket()
        val socketPlayer = Socket()
        val player = RtmpSession(2, socketPlayer, playerIn, playerOut, scope, streams, waiting, null)
        // create a small test subclass for publisher that avoids Android Log calls in handleMessage
        val pub = object : RtmpSession(1, socketPub, pubIn, pubOut, scope, streams, waiting, null) {
            override fun handleMessage(type: Int, streamId: Int, timestamp: Int, payload: ByteArray) {
                // Minimal forwarding logic used in test: parse aggregate messages and forward video/audio/data to players
                when (type) {
                    22 -> {
                        // parse simple FLV aggregate: loop tags
                        var idx = 0
                        while (idx + 11 <= payload.size) {
                            val tagType = payload[idx].toInt() and 0xff
                            val dataSize = ((payload[idx + 1].toInt() and 0xff) shl 16) or
                                    ((payload[idx + 2].toInt() and 0xff) shl 8) or
                                    (payload[idx + 3].toInt() and 0xff)
                            val ts = ((payload[idx + 4].toInt() and 0xff) shl 16) or
                                    ((payload[idx + 5].toInt() and 0xff) shl 8) or
                                    (payload[idx + 6].toInt() and 0xff)
                            val tsExt = payload[idx + 7].toInt() and 0xff
                            val fullTs = (tsExt shl 24) or ts
                            val tagHeaderTotal = 11
                            val prevTagSizeTotal = 4
                            val tagTotal = tagHeaderTotal + dataSize + prevTagSizeTotal
                            if (idx + tagTotal > payload.size) break
                            val tagPayloadStart = idx + tagHeaderTotal
                            val tagPayloadEnd = tagPayloadStart + dataSize
                            val tagPayload = payload.copyOfRange(tagPayloadStart, tagPayloadEnd)
                            // forward to players
                            for (p in players) {
                                try {
                                    val outStreamId = if (p.playStreamId != 0) p.playStreamId else 1
                                    p.sendRtmpMessage(tagType, outStreamId, tagPayload, fullTs)
                                } catch (_: Exception) {}
                            }
                            idx += tagTotal
                        }
                    }
                    8,9,18 -> {
                        for (p in players) {
                            try {
                                val outStreamId = if (p.playStreamId != 0) p.playStreamId else 1
                                p.sendRtmpMessage(type, outStreamId, payload, timestamp)
                            } catch (_: Exception) {}
                        }
                    }
                    else -> {
                        // ignore for test
                    }
                }
            }
        }

        // attach player to publisher
        pub.isPublishing = true
        pub.publishStreamName = "/app/stream"
        pub.players.add(player)

        // build an aggregate payload with one video tag (tagType=9) dataSize=1, payload 0x01
        val tagType = 9.toByte()
        val dataSize = 1
        val ts = 0
        val tagHeader = ByteArray(11)
        tagHeader[0] = tagType
        tagHeader[1] = ((dataSize shr 16) and 0xff).toByte()
        tagHeader[2] = ((dataSize shr 8) and 0xff).toByte()
        tagHeader[3] = (dataSize and 0xff).toByte()
        tagHeader[4] = ((ts shr 16) and 0xff).toByte()
        tagHeader[5] = ((ts shr 8) and 0xff).toByte()
        tagHeader[6] = (ts and 0xff).toByte()
        tagHeader[7] = 0x00
        tagHeader[8] = 0x00
        tagHeader[9] = 0x00
        tagHeader[10] = 0x00
        val tagPayload = byteArrayOf(0x01)
        val prevTagSize = 11 + dataSize
        val prevTagBytes = byteArrayOf(((prevTagSize shr 24) and 0xff).toByte(), ((prevTagSize shr 16) and 0xff).toByte(), ((prevTagSize shr 8) and 0xff).toByte(), (prevTagSize and 0xff).toByte())

        val aggregate = tagHeader + tagPayload + prevTagBytes

        // call handleMessage with type 22 (aggregate)
        pub.handleMessage(22, 1, 0, aggregate)

        // playerOut should have bytes written by sendRtmpMessage
        val written = playerOutBaos.toByteArray()
        assertTrue(written.isNotEmpty())

        // Now test simple video frame forwarding (type 9)
        val videoPayload = byteArrayOf(0x17, 0x01, 0x00) // small h264-like fragment
        playerOutBaos.reset()
        pub.handleMessage(9, 1, 0, videoPayload)
        val written2 = playerOutBaos.toByteArray()
        assertTrue(written2.isNotEmpty())
    }
}
