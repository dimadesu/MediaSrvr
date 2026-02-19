package com.dimadesu.mediasrvr


import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import android.widget.TextView
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.LinkProperties
import android.util.Log
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

        private const val TAG = "MediaSrvr"

        /**
         * Two parallel prerequisites must be satisfied before Node.js can start:
         *   1. Assets ready  – log deleted, assets copied (IO thread)
         *   2. Permission resolved – notification permission granted/denied/not-needed (Main thread)
         * Both tracks run concurrently; [tryStart] launches Node once both are done.
         */
        private var startupInitiated = false          // Has the startup sequence been kicked off?
        @Volatile private var assetsReady = false     // IO work completed? (@Volatile: written on IO, read on Main)
        private var permissionResult: Boolean? = null  // null = pending, true = granted, false = denied
        private var nodeStarted = false                // Double-launch guard

        /** Permission-request lifecycle sub-state. */
        private enum class PermissionRequest { NONE, AWAITING_RESUME, IN_FLIGHT }
        private var permissionRequest = PermissionRequest.NONE
    }

    private val REQ_POST_NOTIFICATIONS = 1001

    // Flag to track when old logs have been cleared (prevents flash of stale logs)
    private var logCleared = false

    // ViewModel that holds log lines across configuration changes
    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var urlAdapter: UrlAdapter
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hotspotReceiver: android.content.BroadcastReceiver? = null

    /** Deterministic path to the Node.js project directory. */
    private val nodeDir: String
        get() = applicationContext.filesDir.absolutePath + "/nodejs-project"

    external fun startNodeWithArguments(arguments: Array<String>): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // How to use button
        val btHowToUse = findViewById<Button>(R.id.btHowToUse)
        btHowToUse.setOnClickListener {
            startActivity(Intent(this@MainActivity, HowToUseActivity::class.java))
        }

        // Initialize log UI and ViewModel once in onCreate so state survives config changes
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
        val rvLogs = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvLogs)
        val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvLogs.layoutManager = layoutManager
        val logAdapterRv = LogAdapter()
        rvLogs.adapter = logAdapterRv
        logViewModel = androidx.lifecycle.ViewModelProvider(this, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)).get(LogViewModel::class.java)
        logViewModel.lines.observe(this) { newLines ->
            // Check if ScrollView is at bottom (user is viewing latest logs)
            val atBottom = if (scrollView.childCount > 0) {
                val child = scrollView.getChildAt(0)
                scrollView.scrollY + scrollView.height >= child.height
            } else {
                true
            }

            // Submit the new list via ListAdapter (diff applied on background thread)
            logAdapterRv.submitList(ArrayList(newLines)) {
                // Auto-scroll only if user was already at the bottom
                if (newLines.isNotEmpty() && atBottom) {
                    scrollView.post {
                        scrollView.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }
        }

        if (!startupInitiated) {
            startupInitiated = true
            Log.d(TAG, "First run: starting parallel asset-copy + permission-request")

            // Track 1: IO work (delete stale log, copy assets if APK updated)
            lifecycleScope.launch(Dispatchers.IO) {
                val nodeDir = this@MainActivity.nodeDir

                // Clear old log file to prevent showing stale logs on startup
                try {
                    val logFile = File("$nodeDir/nms.log")
                    if (logFile.exists()) logFile.delete()
                } catch (_: Exception) { /* ignore */ }

                // Mark log as cleared and start polling now that it's safe
                logCleared = true
                launch(Dispatchers.Main) { logViewModel.startPolling() }

                if (wasAPKUpdated()) {
                    Log.d(TAG, "APK updated, copying assets...")
                    val nodeDirReference = File(nodeDir)
                    if (nodeDirReference.exists()) deleteFolderRecursively(File(nodeDir))
                    copyAssetFolder(applicationContext.assets, "nodejs-project", nodeDir)
                    saveLastUpdateTime()
                    Log.d(TAG, "Assets copied")
                }

                assetsReady = true
                Log.d(TAG, "Assets ready")
                launch(Dispatchers.Main) { tryStart() }
            }

            // Track 2: Notification permission (runs on Main thread)
            requestPermissionIfNeeded()
        } else {
            // Activity recreated (config change, permission dialog, etc.)
            Log.d(TAG, "Activity recreated: permissionRequest=$permissionRequest")
            logCleared = true
            logViewModel.startPolling()

            // If we were waiting for the permission dialog when the Activity was recreated, retry
            if (permissionRequest == PermissionRequest.AWAITING_RESUME) {
                requestPermissionIfNeeded()
            }
        }

        // Initialize URL RecyclerView
        val rvUrls = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvUrls)
        val urlLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvUrls.layoutManager = urlLayoutManager
        urlAdapter = UrlAdapter { url ->
            // Copy URL to clipboard when tapped
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RTMP URL", url)
            clipboard.setPrimaryClip(clip)
        }
        rvUrls.adapter = urlAdapter

        // Load URLs initially (include interface/display names next to each URL)
        lifecycleScope.launch(Dispatchers.IO) {
            val urls = buildRtmpUrls(getDeviceIpPairs())
            launch(Dispatchers.Main) {
                urlAdapter.submitList(urls)
            }
        }
    }

    /**
     * Resolve notification permission (Track 2).
     * Sets [permissionResult] and calls [tryStart] when resolved immediately,
     * or defers to [onResume] / [onRequestPermissionsResult].
     */
    private fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission already granted")
                permissionResult = true
                tryStart()
            // Guard: requestPermissions() can recreate the Activity; without this check
            // the new Activity would re-call requestPermissions() in an infinite loop.
            } else if (permissionRequest != PermissionRequest.IN_FLIGHT) {
                Log.d(TAG, "Requesting notification permission")
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    permissionRequest = PermissionRequest.IN_FLIGHT
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
                } else {
                    permissionRequest = PermissionRequest.AWAITING_RESUME
                }
            }
        } else {
            // Pre-Tiramisu: no runtime permission needed
            permissionResult = true
            tryStart()
        }
    }

    /**
     * Convergence point: start service + Node when both prerequisites are met.
     */
    private fun tryStart() {
        if (!assetsReady || permissionResult == null || nodeStarted) return
        if (permissionResult == true) {
            startServiceAndNode()
        } else {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_LONG).show()
            startNode()
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionRequest == PermissionRequest.AWAITING_RESUME) {
            requestPermissionIfNeeded()
        }
    }

    // Helper to start the foreground service and then the node process
    private fun startServiceAndNode() {
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

        startNode()
    }

    /** Launch the Node.js process exactly once. */
    private fun startNode() {
        if (nodeStarted) return
        nodeStarted = true
        Log.d(TAG, "Starting Node.js process")
        Thread {
            startNodeWithArguments(arrayOf("node", "$nodeDir/main.js"))
        }.start()
    }

    override fun onStart() {
        super.onStart()
        // Only start polling if log has been cleared (prevents flash of old logs)
        if (logCleared) {
            logViewModel.startPolling()
        }
        // Register network callback to auto-refresh IPs on changes
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshIps()
                }

                override fun onLost(network: Network) {
                    refreshIps()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    refreshIps()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) {
                    // IP addressing or capability changes
                    refreshIps()
                }
            }

            // Prefer registerDefaultNetworkCallback when available to get broad updates
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val req = NetworkRequest.Builder().build()
                connectivityManager?.registerNetworkCallback(req, networkCallback!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register hotspot/tethering broadcasts for broad device support
        try {
            hotspotReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    // Any hotspot/tethering related intent -> refresh IPs
                    refreshIps()
                }
            }
            val filter = android.content.IntentFilter()
            // Android Wi-Fi AP state change (may be vendor specific)
            filter.addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            // Tethering state change (vendor specific action exists on some devices)
            filter.addAction("android.net.conn.TETHER_STATE_CHANGED")
            registerReceiver(hotspotReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        // Unregister network callback to avoid leaks
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Unregister hotspot receiver
        try {
            if (hotspotReceiver != null) {
                unregisterReceiver(hotspotReceiver)
                hotspotReceiver = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onStop()
        logViewModel.stopPolling()
    }

    private fun refreshIps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val urls = buildRtmpUrls(getDeviceIpPairs())
            launch(Dispatchers.Main) {
                urlAdapter.submitList(urls)
            }
        }
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
            val children = file.listFiles() ?: emptyArray()
            for (childFile in children) {
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

    // Returns list of device IP addresses (IPv4 only, excluding loopback and mobile data)
    private fun getDeviceIpList(): List<String> {
        val ipList = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val mobileIfRegex = Regex("(?i).*(rmnet|ccmni|pdp|wwan|rmnet_data|rmnet_qti).*")
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val ifName = nif.name ?: nif.displayName ?: ""
                if (mobileIfRegex.matches(ifName)) continue
                
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        ipList.add(host)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ipList
    }

    // Build full RTMP URLs from (interface display name, ip) pairs
    // Returns a list of pairs: first = interface/display name, second = full RTMP URL
    private fun buildRtmpUrls(ipPairs: List<Pair<String, String>>): List<Pair<String, String>> {
        val urls = mutableListOf<Pair<String, String>>()
        // include localhost entry
        urls.add(Pair("localhost", "rtmp://localhost:1935/publish/live"))
        for ((ifName, ip) in ipPairs) {
            urls.add(Pair(ifName, "rtmp://$ip:1935/publish/live"))
        }
        return urls
    }

    // Returns a list of (interface display name, IPv4 address) pairs
    private fun getDeviceIpPairs(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val mobileIfRegex = Regex("(?i).*(rmnet|ccmni|pdp|wwan|rmnet_data|rmnet_qti).*")
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val ifName = nif.displayName ?: nif.name ?: ""
                if (mobileIfRegex.matches(ifName)) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        results.add(Pair(ifName, host))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    // Returns a formatted string containing device IPs on available network interfaces
    private fun getDeviceIps(): String {
        val sb = StringBuilder()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            // common mobile interface name patterns to exclude (rmnet, ccmni, pdp, wwan, etc.)
            val mobileIfRegex = Regex("(?i).*(rmnet|ccmni|pdp|wwan|rmnet_data|rmnet_qti).*")
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val ifName = nif.name ?: nif.displayName ?: ""
                if (mobileIfRegex.matches(ifName)) {
                    // skip mobile data interfaces
                    continue
                }
                val addrs = nif.inetAddresses
                val linePrefix = "${nif.displayName}: "
                val entries = mutableListOf<String>()
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    // Only include IPv4 addresses (filter out IPv6)
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        entries.add(host)
                    }
                }
                if (entries.isNotEmpty()) {
                    sb.append(linePrefix)
                    sb.append(entries.joinToString(", "))
                    sb.append("\n")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return "Error enumerating IPs\n"
        }
        val result = sb.toString().ifEmpty { "No network interfaces found\n" }
        return result
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            permissionResult = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission result: granted=$permissionResult")
            tryStart()
        }
    }

}


