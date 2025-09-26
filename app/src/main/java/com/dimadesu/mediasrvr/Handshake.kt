package com.dimadesu.mediasrvr

object Handshake {
    private const val SIG_SIZE = 1536
    private const val SHA256DL = 32
    private val randomCrud = byteArrayOf(
        0xf0.toByte(), 0xee.toByte(), 0xc2.toByte(), 0x4a.toByte(), 0x80.toByte(), 0x68.toByte(), 0xbe.toByte(), 0xe8.toByte(),
        0x2e.toByte(), 0x00.toByte(), 0xd0.toByte(), 0xd1.toByte(), 0x02.toByte(), 0x9e.toByte(), 0x7e.toByte(), 0x57.toByte(),
        0x6e.toByte(), 0xec.toByte(), 0x5d.toByte(), 0x2d.toByte(), 0x29.toByte(), 0x80.toByte(), 0x6f.toByte(), 0xab.toByte(),
        0x93.toByte(), 0xb8.toByte(), 0xe6.toByte(), 0x36.toByte(), 0xcf.toByte(), 0xeb.toByte(), 0x31.toByte(), 0xae.toByte()
    )

    private val genuineFmsConst = ("Genuine Adobe Flash Media Server 001").toByteArray(Charsets.UTF_8)
    private val genuineFpConst = ("Genuine Adobe Flash Player 001").toByteArray(Charsets.UTF_8)
    private val genuineFmsConstCrud = genuineFmsConst + randomCrud
    private val genuineFpConstCrud = genuineFpConst + randomCrud

    private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val sk = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(sk)
        return mac.doFinal(data)
    }

    private fun getClientDigestOffset(buf: ByteArray): Int {
        val offset = (buf[0].toInt() and 0xFF) + (buf[1].toInt() and 0xFF) + (buf[2].toInt() and 0xFF) + (buf[3].toInt() and 0xFF)
        return (offset % 728) + 12
    }

    private fun getServerDigestOffset(buf: ByteArray): Int {
        val offset = (buf[0].toInt() and 0xFF) + (buf[1].toInt() and 0xFF) + (buf[2].toInt() and 0xFF) + (buf[3].toInt() and 0xFF)
        return (offset % 728) + 776
    }

    private fun detectClientFormat(clientsig: ByteArray): Int {
        // Try server digest at 772..776
        val sliceForServer = clientsig.sliceArray(772 until 776)
        val sdl = getServerDigestOffset(sliceForServer)
        val msg = ByteArray(SIG_SIZE - SHA256DL)
        System.arraycopy(clientsig, 0, msg, 0, sdl)
        System.arraycopy(clientsig, sdl + SHA256DL, msg, sdl, SIG_SIZE - SHA256DL - sdl)
        val computed = hmacSha256(msg, genuineFpConst)
        val provided = clientsig.sliceArray(sdl until sdl + SHA256DL)
        if (computed.contentEquals(provided)) return 2

        val sliceForClient = clientsig.sliceArray(8 until 12)
        val sdl2 = getClientDigestOffset(sliceForClient)
        val msg2 = ByteArray(SIG_SIZE - SHA256DL)
        System.arraycopy(clientsig, 0, msg2, 0, sdl2)
        System.arraycopy(clientsig, sdl2 + SHA256DL, msg2, sdl2, SIG_SIZE - SHA256DL - sdl2)
        val computed2 = hmacSha256(msg2, genuineFpConst)
        val provided2 = clientsig.sliceArray(sdl2 until sdl2 + SHA256DL)
        if (computed2.contentEquals(provided2)) return 1

        return 0
    }

    private fun generateS1(messageFormat: Int): ByteArray {
        val randomBytes = ByteArray(SIG_SIZE - 8)
        java.util.Random().nextBytes(randomBytes)
        val handshake = ByteArray(SIG_SIZE)
        // time(4) + zero(4)
        System.arraycopy(byteArrayOf(0, 0, 0, 0, 1, 2, 3, 4), 0, handshake, 0, 8)
        System.arraycopy(randomBytes, 0, handshake, 8, randomBytes.size)

        val serverDigestOffset = if (messageFormat == 1) getClientDigestOffset(handshake.sliceArray(8 until 12)) else getServerDigestOffset(handshake.sliceArray(772 until 776))
        val msg = ByteArray(SIG_SIZE - SHA256DL)
        System.arraycopy(handshake, 0, msg, 0, serverDigestOffset)
        System.arraycopy(handshake, serverDigestOffset + SHA256DL, msg, serverDigestOffset, SIG_SIZE - SHA256DL - serverDigestOffset)
        val hash = hmacSha256(msg, genuineFmsConst)
        System.arraycopy(hash, 0, handshake, serverDigestOffset, SHA256DL)
        return handshake
    }

    private fun generateS2(messageFormat: Int, clientsig: ByteArray): ByteArray {
        val randomBytes = ByteArray(SIG_SIZE - SHA256DL)
        java.util.Random().nextBytes(randomBytes)
        val challengeKeyOffset = if (messageFormat == 1) getClientDigestOffset(clientsig.sliceArray(8 until 12)) else getServerDigestOffset(clientsig.sliceArray(772 until 776))
        val challengeKey = clientsig.sliceArray(challengeKeyOffset until challengeKeyOffset + SHA256DL)
        val hash = hmacSha256(challengeKey, genuineFmsConstCrud)
        val signature = hmacSha256(randomBytes, hash)
        val s2 = ByteArray(SIG_SIZE)
        System.arraycopy(randomBytes, 0, s2, 0, randomBytes.size)
        System.arraycopy(signature, 0, s2, randomBytes.size, signature.size)
        return s2
    }

    fun generateS0S1S2(clientsig: ByteArray): ByteArray {
        val messageFormat = detectClientFormat(clientsig)
        return if (messageFormat == 0) {
            // simple: S0S1S2 = C0 C1 C1
            val out = ByteArray(1 + SIG_SIZE + SIG_SIZE)
            out[0] = 3
            System.arraycopy(clientsig, 0, out, 1, SIG_SIZE)
            System.arraycopy(clientsig, 0, out, 1 + SIG_SIZE, SIG_SIZE)
            out
        } else {
            val s1 = generateS1(messageFormat)
            val s2 = generateS2(messageFormat, clientsig)
            val out = ByteArray(1 + SIG_SIZE + SIG_SIZE)
            out[0] = 3
            System.arraycopy(s1, 0, out, 1, SIG_SIZE)
            System.arraycopy(s2, 0, out, 1 + SIG_SIZE, SIG_SIZE)
            out
        }
    }
}
