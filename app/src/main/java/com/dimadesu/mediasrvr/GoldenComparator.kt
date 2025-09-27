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
