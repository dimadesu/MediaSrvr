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

    // ViewModel that holds log lines across configuration changes
    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var urlAdapter: UrlAdapter
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hotspotReceiver: android.content.BroadcastReceiver? = null

    external fun startNodeWithArguments(arguments: Array<String>): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // How to use button
        val btHowToUse = findViewById<Button>(R.id.btHowToUse)
        btHowToUse.setOnClickListener {
            startActivity(Intent(this@MainActivity, HowToUseActivity::class.java))
        }

    // Initialize log UI and ViewModel once in onCreate so state survives config changes
    val rvLogs = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvLogs)
    val layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
    rvLogs.layoutManager = layoutManager
    val logAdapterRv = LogAdapter()
    rvLogs.adapter = logAdapterRv
        logViewModel = androidx.lifecycle.ViewModelProvider(this, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)).get(LogViewModel::class.java)
        logViewModel.lines.observe(this) { newLines ->
            // Determine if we should auto-scroll: use RecyclerView.canScrollVertically(1)
            // If canScrollVertically(1) == false then the view is already at the bottom.
            val countBefore = (rvLogs.adapter?.itemCount) ?: 0
            val atBottom = (countBefore == 0) || !rvLogs.canScrollVertically(1)

            // Submit the new list via ListAdapter (diff applied on background thread)
            logAdapterRv.submitList(ArrayList(newLines)) {
                // callback after diff applied: scroll only if we were at the bottom
                if (newLines.isNotEmpty() && atBottom) {
                    rvLogs.post { rvLogs.scrollToPosition(newLines.size - 1) }
                }
            }
        }

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

    // btVersions button removed from layout; no-op here.
    }

    override fun onStart() {
        super.onStart()
        logViewModel.startPolling()
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
