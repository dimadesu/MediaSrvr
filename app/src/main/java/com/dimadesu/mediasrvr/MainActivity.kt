package com.dimadesu.mediasrvr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import com.dimadesu.mediasrvr.ui.theme.MediaSrvrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaSrvrTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Hello $name!")
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Button(onClick = {
                val intent = Intent(ctx, RtmpServerService::class.java)
                ctx.startForegroundService(intent)
            }) {
                Text("Start RTMP Server")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = {
                val intent = Intent(ctx, RtmpServerService::class.java)
                ctx.stopService(intent)
            }) {
                Text("Stop RTMP Server")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        StatusPanel()
    }
}

@Composable
fun StatusPanel() {
    val sessions by RtmpServerState.sessionsFlow.collectAsState()
    val streams by RtmpServerState.streamsFlow.collectAsState()

    Column {
        SummaryPanel(sessions, streams)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Active sessions: ${sessions.size}")
        for (s in sessions) {
            val uptimeSec = ((System.currentTimeMillis() - s.connectedAt) / 1000)
            Text("#${s.id} ${s.remote} ${if (s.isPublishing) "(publishing ${s.publishName})" else ""} — ${s.bytesTransferred} bytes — ${uptimeSec}s")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Active streams: ${streams.size}")
        for ((name, pid) in streams) {
            Text("$name -> #$pid")
        }
    }
}

@Composable
fun SummaryPanel(sessions: List<com.dimadesu.mediasrvr.RtmpSessionInfo>, streams: Map<String, Int>) {
    val totalBytes = sessions.sumOf { it.bytesTransferred }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Connections: ${sessions.size}")
        Spacer(modifier = Modifier.width(12.dp))
        Text("Streams: ${streams.size}")
        Spacer(modifier = Modifier.width(12.dp))
        Text("Bytes: $totalBytes")
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MediaSrvrTheme {
        Greeting("Android")
    }
}