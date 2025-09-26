package com.dimadesu.mediasrvr

object AvUtils {
    data class AacInfo(val profile: Int, val sampleRate: Int, val channels: Int)
    data class AvcInfo(val profile: Int, val level: Int, val width: Int, val height: Int)

    // Very small AAC AudioSpecificConfig parser for common cases (expects AAC sequence header payload starting at index 2 per FLV AAC)
    fun parseAacSequenceHeader(payload: ByteArray): AacInfo? {
        try {
            // FLV AAC packet: first byte = soundformat/..., second byte = AACPacketType (0 = sequence header)
            // The AudioSpecificConfig begins at offset 2
            if (payload.size < 4) return null
            val ascOffset = 2
            val b0 = payload[ascOffset].toInt() and 0xff
            val b1 = payload[ascOffset + 1].toInt() and 0xff
            val audioObjectType = (b0 shr 3) and 0x1f
            val samplingIndex = ((b0 and 0x07) shl 1) or ((b1 shr 7) and 0x01)
            val channelConfig = (b1 shr 3) and 0x0f
            val sampleRate = when (samplingIndex) {
                0 -> 96000
                1 -> 88200
                2 -> 64000
                3 -> 48000
                4 -> 44100
                5 -> 32000
                6 -> 24000
                7 -> 22050
                8 -> 16000
                9 -> 12000
                10 -> 11025
                11 -> 8000
                else -> 0
            }
            return AacInfo(audioObjectType, sampleRate, channelConfig)
        } catch (e: Exception) {
            return null
        }
    }

    // Very small AVCDecoderConfigurationRecord reader: expects payload starting at index 1 (FLV VideoData with codec id set)
    fun parseAvcSequenceHeader(payload: ByteArray): AvcInfo? {
        try {
            // payload[0] = frameType<<4 | codecId
            // payload[1] = AVCPacketType
            // AVCDecoderConfigurationRecord starts at offset 5 (1+1+3 bytes for compositionTime)
            if (payload.size < 11) return null
            // find AVCDecoderConfigurationRecord start: typically offset 5
            val offset = 5
            val configurationVersion = payload[offset].toInt() and 0xff
            if (configurationVersion != 1) return null
            val avcProfile = payload[offset + 1].toInt() and 0xff
            val profileCompatibility = payload[offset + 2].toInt() and 0xff
            val avcLevel = payload[offset + 3].toInt() and 0xff
            // parse SPS to extract width/height (very small parse, may fail for complex streams)
            val spsCount = payload[offset + 5].toInt() and 0xff
            var p = offset + 6
            if (spsCount == 0) return AvcInfo(avcProfile, avcLevel, 0, 0)
            val spsLen = ((payload[p].toInt() and 0xff) shl 8) or (payload[p + 1].toInt() and 0xff)
            p += 2
            if (p + spsLen > payload.size) return AvcInfo(avcProfile, avcLevel, 0, 0)
            val sps = payload.copyOfRange(p, p + spsLen)
            // very naive SPS parse to extract width/height from bits (not full parser)
            // Search for NAL rbsp and find width/height may be involved; skip complex parse and return zeros if unknown
            return AvcInfo(avcProfile, avcLevel, 0, 0)
        } catch (e: Exception) {
            return null
        }
    }
}
