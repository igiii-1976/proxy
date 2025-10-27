package com.example.proxy

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private val edgeRegistry = EdgeRegistry()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxy: ProxyServer? = null
    private var scanner: NetworkScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connectedEdges = mutableStateListOf<EdgeDevice>()

        // Refresh UI list every 5 seconds
        mainScope.launch(Dispatchers.Main) {
            while (isActive) {
                val current = edgeRegistry.getAll()
                connectedEdges.clear()
                connectedEdges.addAll(current)
                delay(5000L)
            }
        }

        // Start proxy server
        proxy = ProxyServer(edgeRegistry, 8080)
        Thread {
            try {
                proxy?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i("MainActivity", "Proxy server started on 8080")
            } catch (e: Exception) {
                Log.e("MainActivity", "Proxy start failed", e)
            }
        }.start()

        // Start network scanner
        scanner = NetworkScanner(this, edgeRegistry, mainScope)

        // Full subnet scan (every 10 minutes for now maybe)
        scanner?.startScanPeriodically(10 * 60 * 1000L)

        // Lightweight refresh of known edges every 2 minutes
        scanner?.startRefreshPeriodically(2 * 60 * 1000L)

        // Remove old edges
        mainScope.launch {
            while (isActive) {
                edgeRegistry.removeStale(120_000L)
                delay(30_000L)
            }
        }

        // 🖥️ UI
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text("Android Proxy Server", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))

                        if (connectedEdges.isEmpty()) {
                            Text("No edge servers found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn {
                                items(connectedEdges) { edge ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        elevation = CardDefaults.cardElevation(4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("IP: ${edge.ip}")
                                            Text("Battery: ${edge.battery}")
                                            Text("Status: ${edge.status}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            proxy?.stop()
            mainScope.cancel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Cleanup error", e)
        }
    }
}
