package com.example.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

class NetworkScanner(
    private val context: Context,
    private val edgeRegistry: EdgeRegistry,
    private val scope: CoroutineScope
) {
    // 3 second wait time for connection and reading (if no response, cancel)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // Scans the entire subnet every 10 mins
    fun startScanPeriodically(intervalMs: Long) {
        scope.launch {
            while (isActive) {
                scanNetwork()
                delay(intervalMs)
            }
        }
    }

    // Scans and updates existing Edge Servers details every 2 min
    fun startRefreshPeriodically(intervalMs: Long) {
        scope.launch {
            while (isActive) {
                refreshKnownEdges()
                delay(intervalMs)
            }
        }
    }

    // Function for scanning entire network
    private suspend fun scanNetwork() = withContext(Dispatchers.IO) {
        val proxyIP = getIPAddress() ?: return@withContext

        // Extracts subnet base (192.168.1.10 -> 192.168.1)
        val subnet = proxyIP?.substringBeforeLast('.')

        val jobs = (1..254).map { i ->
            async {
                val ip = "$subnet.$i"
                if (ip != proxyIP) {
                    val url = "http://$ip:8080/status"

                    val request = Request.Builder().url(url).build()
                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val body = response.body?.string() ?: ""
                                Log.d("NetworkScanner", "✅ $ip responded: $body")
                                val json = JSONObject(body)
                                val level = json.optString("level", "Unknown")
                                val status = json.optString("status", "Unknown")
                                edgeRegistry.upsert(ip, level, status)
                            } else {
                                Log.d("NetworkScanner", "❌ $ip HTTP ${response.code}")
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
        jobs.awaitAll()
    }

    // Function for scanning known Edge Servers
    private suspend fun refreshKnownEdges() = withContext(Dispatchers.IO) {
        val edges = edgeRegistry.getAll()
        for (edge in edges) {
            val ip = edge.ip
            val url = "http://$ip:8080/status"

            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val level = json.optString("level", "Unknown")
                        val status = json.optString("status", "Unknown")

                        edgeRegistry.upsert(ip, level, status)
                        Log.i("NetworkScanner", "Refreshed edge $ip ($level, $status)")
                    } else {
                        Log.w("NetworkScanner", "Edge $ip not responding (HTTP ${response.code})")
                    }
                }
            } catch (e: Exception) {
                Log.w("NetworkScanner", "Edge $ip refresh failed: ${e.message}")
            }
        }
    }

    // Get the IP
    private fun getIPAddress(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val props: LinkProperties = cm.getLinkProperties(network) ?: return null
            val ipv4 = props.linkAddresses.mapNotNull { it.address as? Inet4Address }.firstOrNull()
            ipv4?.hostAddress
        } catch (e: Exception) {
            Log.e("NetworkScanner", "Failed to get subnet base", e)
            null
        }
    }
}
