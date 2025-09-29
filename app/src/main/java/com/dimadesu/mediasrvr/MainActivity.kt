package com.dimadesu.mediasrvr


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("node")
        }

        // We just want one instance of node running in the background.
        var _startedNodeAlready = false
    }

    // If we need to request POST_NOTIFICATIONS, store nodeDir here until the user responds.
    private var pendingNodeDir: String? = null
    private val REQ_POST_NOTIFICATIONS = 1001
    private var pendingRequestNotification = false

    external fun startNodeWithArguments(arguments: Array<String>): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!_startedNodeAlready) {
            _startedNodeAlready = true
            // Use lifecycleScope to perform IO work and then switch to UI for permission/service start
            lifecycleScope.launch(Dispatchers.IO) {
                val nodeDir = applicationContext.filesDir.absolutePath + "/nodejs-project"
                if (wasAPKUpdated()) {
                    val nodeDirReference = File(nodeDir)
                    if (nodeDirReference.exists()) {
                        deleteFolderRecursively(File(nodeDir))
                    }
                    copyAssetFolder(applicationContext.assets, "nodejs-project", nodeDir)
                    saveLastUpdateTime()
                }

                val _nodeDirForUi = nodeDir
                launch(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            // permission already granted -> start service then node
                            startServiceAndNode(_nodeDirForUi)
                        } else {
                            // mark that we need to request permission when the activity is resumed
                            pendingNodeDir = _nodeDirForUi
                            pendingRequestNotification = true
                        }
                    } else {
                        // Older Android: no runtime notification permission required
                        startServiceAndNode(_nodeDirForUi)
                    }
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        if (pendingRequestNotification && pendingNodeDir != null) {
            pendingRequestNotification = false
            // Request notifications permission now that the activity is resumed and in foreground
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    // Helper to start the foreground service and then the node process
    private fun startServiceAndNode(nodeDir: String) {
        try {
            val svcIntent = Intent(applicationContext, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(svcIntent)
            } else {
                applicationContext.startService(svcIntent)
            }
        } catch (e: Exception) {
            // Ignore failures starting the service; node can still be started.
            e.printStackTrace()
        }

        // Start Node on a background coroutine so we don't block the UI thread
        lifecycleScope.launch(Dispatchers.IO) {
            startNodeWithArguments(arrayOf("node", "$nodeDir/main.js"))
        }

        val buttonVersions = findViewById<Button>(R.id.btVersions)
        val listViewLogs = findViewById<ListView>(R.id.lvLogs)
        val logItems = ArrayList<String>()
        val logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, logItems)
        listViewLogs.adapter = logAdapter

        // Automatically poll and show nms.log without requiring button clicks
        val LOG_POLL_MS = 1000L
        var pollJob: Job? = null

        fun startLogPolling() {
            if (pollJob?.isActive == true) return
            pollJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val result = try {
                        val logDir = applicationContext.filesDir.absolutePath + "/nodejs-project"
                        val logFile = File("$logDir/nms.log")
                        if (!logFile.exists()) {
                            "(no nms.log found at ${logFile.absolutePath})"
                        } else {
                            val MAX_LINES = 10
                            val tail = LinkedList<String>()
                            val br = BufferedReader(FileReader(logFile))
                            var line: String?
                            while (br.readLine().also { line = it } != null) {
                                if (line == null) continue
                                val trimmed = line!!.trim()
                                if (trimmed.isEmpty()) continue
                                val singleLine = trimmed.replace(Regex("\\s+"), " ")
                                tail.add(singleLine)
                                if (tail.size > MAX_LINES) tail.removeFirst()
                            }
                            br.close()
                            val sb = StringBuilder()
                            for (l in tail) {
                                sb.append(l).append('\n')
                            }
                            sb.toString()
                        }
                    } catch (e: Exception) {
                        e.toString()
                    }

                    // Post UI updates on the main dispatcher
                    launch(Dispatchers.Main) {
                        logItems.clear()
                        if (result.isNotEmpty()) {
                            val lines = result.lines()
                            for (l in lines) if (l.isNotEmpty()) logItems.add(l)
                        }
                        logAdapter.notifyDataSetChanged()
                        if (logItems.isNotEmpty()) listViewLogs.post { listViewLogs.setSelection(logItems.size - 1) }
                    }

                    delay(LOG_POLL_MS)
                }
            }
        }

        // start polling immediately
        startLogPolling()

        // Keep button available for manual refresh as well
        buttonVersions.setOnClickListener { startLogPolling() }

        // Stop polling when activity is destroyed
        this.application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (activity == this@MainActivity) {
                    pollJob?.cancel()
                    application.unregisterActivityLifecycleCallbacks(this)
                    try {
                        val svcIntent = Intent(applicationContext, ForegroundService::class.java)
                        applicationContext.stopService(svcIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    // removed unused native declaration; keep main external API above

    private fun wasAPKUpdated(): Boolean {
        val prefs = applicationContext.getSharedPreferences("NODEJS_MOBILE_PREFS", Context.MODE_PRIVATE)
        val previousLastUpdateTime = prefs.getLong("NODEJS_MOBILE_APK_LastUpdateTime", 0)
        var lastUpdateTime = 1L
        try {
            val packageInfo: PackageInfo? = getPackageInfoCompat(applicationContext.packageName)
            if (packageInfo != null) lastUpdateTime = packageInfo.lastUpdateTime
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lastUpdateTime != previousLastUpdateTime
    }

    private fun saveLastUpdateTime() {
        var lastUpdateTime = 1L
        try {
            val packageInfo: PackageInfo? = getPackageInfoCompat(applicationContext.packageName)
            if (packageInfo != null) lastUpdateTime = packageInfo.lastUpdateTime
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val prefs = applicationContext.getSharedPreferences("NODEJS_MOBILE_PREFS", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putLong("NODEJS_MOBILE_APK_LastUpdateTime", lastUpdateTime)
        editor.commit()
    }

    private fun deleteFolderRecursively(file: File): Boolean {
        try {
            var res = true
            for (childFile in file.listFiles()) {
                if (childFile.isDirectory) {
                    res = res and deleteFolderRecursively(childFile)
                } else {
                    res = res and childFile.delete()
                }
            }
            res = res and file.delete()
            return res
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        try {
            val files = assetManager.list(fromAssetPath)
            var res = true
            if (files.isNullOrEmpty()) {
                res = res and copyAsset(assetManager, fromAssetPath, toPath)
            } else {
                File(toPath).mkdirs()
                for (file in files) res = res and copyAssetFolder(assetManager, "$fromAssetPath/$file", "$toPath/$file")
            }
            return res
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun copyAsset(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        try {
            assetManager.open(fromAssetPath).use { input ->
                File(toPath).apply { parentFile?.mkdirs(); createNewFile() }
                FileOutputStream(toPath).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    @Throws(IOException::class)
    private fun copyFile(`in`: InputStream, out: OutputStream) {
        `in`.use { input ->
            out.use { output ->
                input.copyTo(output)
            }
        }
    }

    // Compatibility helper for fetching PackageInfo without using deprecated overloads
    private fun getPackageInfoCompat(packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Use the modern API available on API 33+
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            var granted = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                granted = true
            }

            val nodeDir = pendingNodeDir
            pendingNodeDir = null

            if (nodeDir == null) return

            if (granted) {
                runOnUiThread { startServiceAndNode(nodeDir) }
            } else {
                Toast.makeText(this, "Notification permission denied. Node will run without foreground notification.", Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) { startNodeWithArguments(arrayOf("node", "$nodeDir/main.js")) }
            }
        }
    }
}
