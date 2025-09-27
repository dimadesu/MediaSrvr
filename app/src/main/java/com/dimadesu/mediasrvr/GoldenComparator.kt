package com.dimadesu.mediasrvr

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

object GoldenComparator {
    private const val TAG = "GoldenComparator"
    // Comparator enabled by default. Set RTMP_GOLDEN_COMPARE=0 or "false" to explicitly disable.
    private val enabled: Boolean by lazy {
        val v = System.getenv("RTMP_GOLDEN_COMPARE")
        if (v == null) return@lazy true
        !(v == "0" || v.equals("false", true))
    }

    // Optional: explicit opt-in to write diff files to disk. Default = false (no writes).
    private val dumpToDisk: Boolean by lazy {
        val v = System.getenv("RTMP_GOLDEN_DUMP")
        v != null && (v == "1" || v.equals("true", true))
    }

    // Allow explicit override of golden directory (useful when running on device/emulator)
    private val goldenDir: File by lazy {
        val explicit = System.getenv("RTMP_GOLDEN_PATH")
        if (!explicit.isNullOrBlank()) {
            val f = File(explicit)
            Log.i(TAG, "GoldenComparator using RTMP_GOLDEN_PATH=$explicit")
            return@lazy f
        }
        val f = Paths.get(System.getProperty("user.dir"), "inspiration", "golden").toFile()
        Log.i(TAG, "GoldenComparator resolved goldenDir=${f.absolutePath}")
        f
    }

    private val diffDir = Paths.get(System.getProperty("user.dir"), "captures", "diffs").toFile()

    // If the app bundles goldens inside assets/golden/, we can read them via this context.
    // Call GoldenComparator.init(appContext) from your Application or service startup.
    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        Log.i(TAG, "GoldenComparator initialized with appContext; assets available=${appContext != null}")
    }

    fun isEnabled(): Boolean = enabled && goldenDir.exists()
        || (appContext != null)

    /**
     * Resolve the first candidate golden filename that exists either on disk under goldenDir
     * or in bundled assets/golden/. Returns the filename if found, otherwise null.
     */
    fun resolveExistingGoldenName(candidates: List<String>): String? {
        for (name in candidates) {
            // prefer .bin variant when a .hex candidate is provided
            val binName = if (name.endsWith(".hex")) name.substring(0, name.length - 4) + ".bin" else null
            if (binName != null) {
                try {
                    val fbin = File(goldenDir, binName)
                    if (fbin.exists()) return binName
                } catch (_: Exception) { }
                val ctxb = appContext
                if (ctxb != null) {
                    try {
                        ctxb.assets.open("golden/$binName").use { }
                        return binName
                    } catch (_: Exception) { }
                }
            }
            try {
                val f = File(goldenDir, name)
                if (f.exists()) return name
            } catch (_: Exception) { }
            val ctx = appContext
            if (ctx != null) {
                try {
                    ctx.assets.open("golden/$name").use { /* just checking existence */ }
                    return name
                } catch (_: Exception) { }
            }
        }
        return null
    }

    /**
     * Compare an inbound invoke payload (raw RTMP message payload) against a golden file.
     * goldenName should be the filename under inspiration/golden (e.g. connect_result_amf0.hex)
     * By default this will not write to disk — it only logs a concise summary. Set RTMP_GOLDEN_DUMP=1
     * to allow writing full diffs to disk.
     */
    fun compare(sessionId: Int, cmd: String, goldenName: String, actual: ByteArray) {
        if (!isEnabled()) return
        try {
            val goldenFile = File(goldenDir, goldenName)
            var expectedRaw: ByteArray = if (goldenFile.exists()) {
                Files.readAllBytes(goldenFile.toPath())
            } else {
                // attempt to load from bundled assets/golden/<goldenName>
                val ctx = appContext
                if (ctx != null) {
                    try {
                        val assetPath = "golden/$goldenName"
                        Log.i(TAG, "Golden not found on disk, trying assets: $assetPath")
                        ctx.assets.open(assetPath).use { ins -> readAllBytes(ins) }
                    } catch (e: Exception) {
                        Log.i(TAG, "Golden missing from assets for $goldenName: ${e.message}")
                        return
                    }
                } else {
                    Log.i(TAG, "Golden missing for $goldenName and no appContext to read assets; skipping comparator")
                    return
                }
            }
            // If the golden file is stored as ASCII hex (common for generator outputs), decode it.
            val expected = try {
                decodeHexIfAscii(expectedRaw = expectedRaw)
            } catch (e: Exception) {
                Log.i(TAG, "Error decoding golden hex for $goldenName: ${e.message}")
                expectedRaw
            }

            if (expected.contentEquals(actual)) {
                Log.i(TAG, "Golden match: session#$sessionId cmd=$cmd golden=$goldenName len=${actual.size}")
                return
            }

            // If both appear to be AMF0 payloads (not AMF3 wrapper), try a structural AMF0 compare first.
            try {
                val expFirst = if (expected.isNotEmpty()) expected[0].toInt() and 0xff else -1
                val actFirst = if (actual.isNotEmpty()) actual[0].toInt() and 0xff else -1
                // AMF3 wrapper marker is 0x11; if neither starts with 0x11, attempt AMF0 structural compare
                if (expFirst != 0x11 && actFirst != 0x11) {
                    val expStruct = parseAmf0Sequence(expected)
                    val actStruct = parseAmf0Sequence(actual)
                    if (deepEqualAmfValues(expStruct, actStruct)) {
                        Log.i(TAG, "Golden structural AMF0 match: session#$sessionId cmd=$cmd golden=$goldenName values_match=true")
                        return
                    } else {
                        // Try a normalized compare where known transId numeric slots are masked
                        val nExp = normalizeAmfTopLevel(expStruct)
                        val nAct = normalizeAmfTopLevel(actStruct)
                        if (deepEqualAmfValues(nExp, nAct)) {
                            Log.i(TAG, "Golden structural AMF0 match after normalization: session#$sessionId cmd=$cmd golden=$goldenName")
                            return
                        }
                        Log.i(TAG, "Golden structural AMF0 mismatch: session#$sessionId cmd=$cmd golden=$goldenName expected_values=${expStruct.take(8)} actual_values=${actStruct.take(8)}")
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "AMF0 structural compare error: ${e.message}")
            }

            // Build a concise in-memory diff summary (no file IO by default)
            val maxPreview = 128
            val expPreview = expected.take(maxPreview).joinToString(" ") { String.format("%02x", it) }
            val actPreview = actual.take(maxPreview).joinToString(" ") { String.format("%02x", it) }
            // find first few differing positions
            val diffs = mutableListOf<Int>()
            val scanLen = minOf(expected.size, actual.size, 256)
            for (i in 0 until scanLen) {
                if (expected[i] != actual[i]) {
                    diffs.add(i)
                    if (diffs.size >= 8) break
                }
            }
            Log.i(TAG, "Golden mismatch: session#$sessionId cmd=$cmd golden=$goldenName expected_len=${expected.size} actual_len=${actual.size} diffs=${diffs}")
            Log.i(TAG, "expected_preview(len=${minOf(expected.size, maxPreview)})=$expPreview")
            Log.i(TAG, "actual_preview  (len=${minOf(actual.size, maxPreview)})=$actPreview")

            // If the user explicitly opted in, write the full diff to disk (opt-in to avoid phone writes)
            if (dumpToDisk) {
                try {
                    if (!diffDir.exists()) diffDir.mkdirs()
                    val out = StringBuilder()
                    out.append("session=$sessionId cmd=$cmd golden=$goldenName\n")
                    out.append("expected_len=${expected.size} actual_len=${actual.size}\n")
                    out.append("--- expected (hex) ---\n")
                    out.append(expected.joinToString(" ") { String.format("%02x", it) })
                    out.append("\n--- actual (hex) ---\n")
                    out.append(actual.joinToString(" ") { String.format("%02x", it) })
                    out.append("\n")
                    val fname = "session-${sessionId}-${cmd.replace(Regex("[^A-Za-z0-9._-]"), "_")}.diff"
                    val f = File(diffDir, fname)
                    FileOutputStream(f).use { fos -> fos.write(out.toString().toByteArray()) }
                    Log.i(TAG, "Golden mismatch written to ${f.absolutePath}")
                } catch (e: Exception) {
                    Log.i(TAG, "Comparator disk write error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Comparator error: ${e.message}")
        }
    }

    private fun readAllBytes(ins: InputStream): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        var r: Int
        while (true) {
            r = ins.read(tmp)
            if (r <= 0) break
            buf.write(tmp, 0, r)
        }
        return buf.toByteArray()
    }
}

/**
 * If the provided byte array represents ASCII hex (optionally with whitespace), decode it to raw bytes.
 * If it's not hex-like, return the original byte array.
 */
private fun decodeHexIfAscii(expectedRaw: ByteArray): ByteArray {
    if (expectedRaw.isEmpty()) return expectedRaw
    val s = try {
        String(expectedRaw, Charsets.UTF_8).trim()
    } catch (e: Exception) {
        return expectedRaw
    }
    // If the string contains only hex chars and whitespace, treat it as hex output
    if (!s.matches(Regex("^[0-9a-fA-F\\s]+$"))) {
        return expectedRaw
    }
    val hex = s.replace(Regex("\\s+"), "")
    if (hex.length < 2) return expectedRaw
    // if odd length, pad with leading 0
    val evenHex = if (hex.length % 2 != 0) "0$hex" else hex
    val out = ByteArray(evenHex.length / 2)
    var j = 0
    try {
        for (i in evenHex.indices step 2) {
            val pair = evenHex.substring(i, i + 2)
            out[j++] = Integer.parseInt(pair, 16).toByte()
        }
    } catch (e: Exception) {
        // If anything goes wrong, fall back to original raw bytes
        return expectedRaw
    }
    return out
}

// Parse a sequence of AMF0 values from a byte array using the existing Amf0Parser
private fun parseAmf0Sequence(bytes: ByteArray): List<Any?> {
    try {
        val parser = com.dimadesu.mediasrvr.Amf0Parser(bytes)
        val out = mutableListOf<Any?>()
        while (true) {
            val v = parser.readAmf0() ?: break
            out.add(v)
        }
        return out
    } catch (e: Exception) {
        return emptyList()
    }
}

// Deep-equal comparison for AMF0-decoded values (strings, numbers, booleans, maps, lists, nulls)
private fun deepEqualAmfValues(a: Any?, b: Any?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return a == b
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    if (a is String && b is String) return amfStringEquals(a, b)
    if (a is Boolean && b is Boolean) return a == b
    if (a is Map<*, *> && b is Map<*, *>) {
        // Treat the expected map (a) as a subset of the actual map (b).
        // This tolerates goldens that omit optional keys while still asserting
        // required keys/values are present and equal.
        for ((k, v) in a) {
            if (!b.containsKey(k)) return false
            if (!deepEqualAmfValues(v, b[k])) return false
        }
        return true
    }
    if (a is List<*> && b is List<*>) {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!deepEqualAmfValues(a[i], b[i])) return false
        }
        return true
    }
    return false
}

// Compare AMF strings with special-case for RTMP tcUrl-like values. When both
// values look like RTMP URIs, compare scheme, port and path but ignore the host
// so goldens may use 127.0.0.1 while tests run against other local IPs.
private fun amfStringEquals(a: String, b: String): Boolean {
    try {
        val la = a.trim()
        val lb = b.trim()
        if (la.startsWith("rtmp://", true) && lb.startsWith("rtmp://", true)) {
            try {
                val ua = java.net.URI(la)
                val ub = java.net.URI(lb)
                val schemeEq = ua.scheme.equals(ub.scheme, ignoreCase = true)
                // If port is -1, assume default 1935 for RTMP
                val pa = if (ua.port == -1) 1935 else ua.port
                val pb = if (ub.port == -1) 1935 else ub.port
                val portEq = pa == pb
                // Compare path (which includes /app/stream) and query/fragment as string
                val pathEq = (ua.rawPath ?: "") == (ub.rawPath ?: "")
                return schemeEq && portEq && pathEq
            } catch (_: Exception) {
                // fallthrough to plain string compare
            }
        }
    } catch (_: Exception) { }
    return a == b
}

// Normalize top-level AMF sequences by masking known transId numeric positions so
// semantic comparisons ignore transaction-id differences between client and server.
private fun normalizeAmfTopLevel(list: List<Any?>): List<Any?> {
    if (list.isEmpty()) return list
    val out = list.toMutableList()
    try {
        val first = out[0]
        if (first is String) {
            when (first) {
                "_result", "_error" -> {
                    if (out.size > 1 && out[1] is Number) out[1] = 0.0
                }
                "createStream" -> {
                    // client createStream transId often 3.0 vs server _result uses 2.0 — mask second slot
                    if (out.size > 1 && out[1] is Number) out[1] = 0.0
                }
                "publish" -> {
                    if (out.size > 1 && out[1] is Number) out[1] = 0.0
                }
                else -> { /* no-op */ }
            }
        }
    } catch (_: Exception) { }
    return out
}
