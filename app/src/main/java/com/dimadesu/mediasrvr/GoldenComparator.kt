package com.dimadesu.mediasrvr

import android.util.Log
import java.io.File
import java.io.FileOutputStream
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

    fun isEnabled(): Boolean = enabled && goldenDir.exists()

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
            if (!goldenFile.exists()) {
                Log.i(TAG, "Golden missing for $goldenName; skipping comparator")
                return
            }
            val expected = Files.readAllBytes(goldenFile.toPath())
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
}
