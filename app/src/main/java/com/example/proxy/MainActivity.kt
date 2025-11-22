package com.example.proxy

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

// A simple, thread-safe logger to hold messages for the UI
object UiLogger {
    private val logs = ConcurrentLinkedQueue<String>()
    private const val MAX_LOGS = 100

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$timestamp - $message"
        logs.add(logEntry)
        // Keep the log list from growing indefinitely
        if (logs.size > MAX_LOGS) {
            logs.poll()
        }
    }

    fun getLogs(): List<String> {
        return logs.toList().reversed() // Show newest logs first
    }
}


class MainActivity : ComponentActivity() {

    private val edgeRegistry = EdgeRegistry()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxy: ProxyServer? = null
    private var discovery: EdgeDiscovery? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connectedEdges = mutableStateListOf<EdgeDevice>()
        val logMessages = mutableStateListOf<String>()

        // Refresh UI list for Edges and Logs every second
        mainScope.launch(Dispatchers.Main) {
            while (isActive) {
                // Update Edges
                val currentEdges = edgeRegistry.getAll()
                connectedEdges.clear()
                connectedEdges.addAll(currentEdges)

                // Update Logs
                val currentLogs = UiLogger.getLogs()
                logMessages.clear()
                logMessages.addAll(currentLogs)

                delay(1000L) // Refresh UI every second
            }
        }

        // Start proxy server and pass it the logger
        UiLogger.log("Starting Proxy Server...")
        proxy = ProxyServer(edgeRegistry, 8080, UiLogger::log) // Pass the log function
        proxy?.start()
        UiLogger.log("Proxy Server started on port 8080.")


        // Start edge discovery
        UiLogger.log("Starting Edge Discovery...")
        discovery = EdgeDiscovery(this, edgeRegistry, mainScope, UiLogger::log) // Pass the log function
        discovery?.startDiscovery()

        // Periodic battery updates
        startPeriodicMaintenance()

        // UI
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text("Android Proxy Server", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))

                        // Edge Server List (occupies the top part)
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            if (connectedEdges.isEmpty()) {
                                item {
                                    Text("No edge servers found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
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

                        Spacer(Modifier.height(16.dp))

                        // --- NEW LOG VIEWER UI ---
                        Column(modifier = Modifier
                            .weight(1f) // Makes it take up the bottom half
                            .fillMaxWidth()
                        ) {
                            Text("Live Logs", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.9f), shape = MaterialTheme.shapes.medium)
                                    .padding(8.dp)
                            ) {
                                val listState = rememberLazyListState()

                                // Auto-scroll to the top (newest message) when logs change
                                LaunchedEffect(logMessages.size) {
                                    listState.animateScrollToItem(0)
                                }

                                LazyColumn(state = listState) {
                                    items(logMessages) { log ->
                                        Text(
                                            text = log,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startPeriodicMaintenance() {
        mainScope.launch {
            while (isActive) {
                delay( 60 * 1000L) // 1 minutes

                // Refresh the status of all currently known edges.
                discovery?.refreshKnownEdges()

//                // Remove any edges that haven't responded recently.
//                val removedCount = edgeRegistry.removeStale(6 * 60 * 1000L)
//                if (removedCount > 0) {
//                    UiLogger.log("Removed $removedCount stale edge server(s).")
//                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            UiLogger.log("Shutting down services...")
            proxy?.stop()
            discovery?.stopDiscovery()
            mainScope.cancel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Cleanup error", e)
        }
    }
}
