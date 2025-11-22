package com.example.proxy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EdgeDiscovery(
    private val context: Context,
    private val edgeRegistry: EdgeRegistry,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    companion object {
        // Define a unique service type for the application
        const val SERVICE_TYPE = "_proxy-edge._tcp."
    }

    fun startDiscovery() {
        if (discoveryListener != null) {
            logger("Discovery is already active.")
            Log.w("EdgeDiscovery", "Discovery is already active.")
            return
        }
        logger("Starting network service discovery for '$SERVICE_TYPE'")
        Log.i("EdgeDiscovery", "Starting network service discovery for '$SERVICE_TYPE'")

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                logger("Discovery started successfully.")
                Log.d("EdgeDiscovery", "Discovery started successfully.")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Get edge IP address
                logger("Service found: ${service.serviceName}")
                Log.d("EdgeDiscovery", "Service found: ${service.serviceName}")
                nsdManager.resolveService(service, createResolveListener())
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // When an edge server unregisters itself.
                logger("Service lost: ${service.serviceName}. Removing from registry.")
                Log.i("EdgeDiscovery", "Service lost: ${service.serviceName}")

                // Parse the IP from the service name and remove the edge from EdgeRegistry
                try {
                    if (service.serviceName.startsWith("EdgeServer_")) {
                        val parsableIp = service.serviceName.substringAfter("EdgeServer_")
                        val ipAddress = parsableIp.replace('-', '.') // Revert to "192.168.1.10"

                        edgeRegistry.remove(ipAddress)
                        logger("Removed $ipAddress from EdgeRegistry")
                        Log.i("EdgeDiscovery", "Successfully removed lost edge server: $ipAddress")
                    } else {
                        logger("Could not parse IP from lost service name: ${service.serviceName}")
                        Log.w("EdgeDiscovery", "Could not parse IP from lost service name: ${service.serviceName}")
                    }
                } catch (e: Exception) {
                    logger("Error while processing lost service: ${e.message}")
                    Log.e("EdgeDiscovery", "Error processing lost service", e)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                logger("Discovery stopped.")
                Log.i("EdgeDiscovery", "Discovery stopped.")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger("Start discovery failed: Error code $errorCode")
                Log.e("EdgeDiscovery", "Start discovery failed: Error code $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger("Stop discovery failed: Error code $errorCode")
                Log.e("EdgeDiscovery", "Stop discovery failed: Error code $errorCode")
            }
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logger("Resolve failed for ${serviceInfo.serviceName}: Error code $errorCode")
                Log.e(
                    "EdgeDiscovery",
                    "Resolve failed for ${serviceInfo.serviceName}: Error code $errorCode"
                )
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Resolution successful! We now have the IP and port.
                val ip = serviceInfo.host.hostAddress
                val port = serviceInfo.port
                logger("Service resolved: ${serviceInfo.serviceName} at $ip:$port")
                Log.i("EdgeDiscovery", "Service resolved: ${serviceInfo.serviceName} at $ip:$port")

                // Query /status endpoint to get battery and status
                scope.launch(Dispatchers.IO) {
                    queryEdgeStatus(ip, port)
                }
            }
        }
    }

    private fun queryEdgeStatus(ip: String, port: Int) {
        val url = "http://$ip:$port/status"
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val level = json.optString("level", "Unknown")
                    val status = json.optString("status", "Unknown")
                    edgeRegistry.upsert(ip, level, status)
                    logger("Successfully queried edge at $ip. Added/updated to registry.")
                    Log.d("EdgeDiscovery", "Successfully queried edge at $ip. Added to registry.")
                } else {
                    logger("Failed to query status for $ip. HTTP ${response.code}")
                    Log.w("EdgeDiscovery", "Failed to query status for $ip. HTTP ${response.code}")
                    edgeRegistry.remove(ip)
                }
            }
        } catch (e: Exception) {
            logger("Error querying status for $ip: ${e.message}")
            Log.e("EdgeDiscovery", "Error querying status for $ip: ${e.message}")
            edgeRegistry.remove(ip)
        }
    }

    fun refreshKnownEdges() {
        logger("Starting periodic refresh of known edge servers...")
        val knownEdges = edgeRegistry.getAll()
        if (knownEdges.isEmpty()) {
            logger("No known edges to refresh.")
            return
        }

        logger("Refreshing ${knownEdges.size} edge(s).")
        for (edge in knownEdges) {
            scope.launch(Dispatchers.IO) {
                queryEdgeStatus(edge.ip, 8080)
            }
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            logger("Stopping network service discovery.")
            Log.i("EdgeDiscovery", "Stopping network service discovery.")
            nsdManager.stopServiceDiscovery(it)
            discoveryListener = null
        }
    }
}