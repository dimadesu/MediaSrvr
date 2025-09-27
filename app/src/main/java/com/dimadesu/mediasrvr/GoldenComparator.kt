package com.dimadesu.mediasrvr

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths

object GoldenComparator {
    private const val TAG = "GoldenComparator"
    private val enabled: Boolean by lazy {
        val v = System.getenv("RTMP_GOLDEN_COMPARE")
        v != null && (v == "1" || v.equals("true", true))
    }

    private val goldenDir = Paths.get(System.getProperty("user.dir"), "inspiration", "golden").toFile()
    private val diffDir = Paths.get(System.getProperty("user.dir"), "captures", "diffs").toFile()

    init {
        try {
            if (!diffDir.exists()) diffDir.mkdirs()
        } catch (e: Exception) {
            Log.i(TAG, "Could not create diff dir: ${e.message}")
        }
    }

    fun isEnabled(): Boolean = enabled && goldenDir.exists()

    /**
     * Compare an inbound invoke payload (raw RTMP message payload) against a golden file.
     * goldenName should be the filename under inspiration/golden (e.g. connect_result_amf0.hex)
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
            // mismatch: write a small diff file with headers and hex dumps
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
            Log.i(TAG, "Comparator error: ${e.message}")
        }
    }
}
