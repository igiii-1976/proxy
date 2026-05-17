package com.example.proxy.mdnsDiscovery

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class EdgeDevice(
    val ip: String,
    var battery: String,
    var status: String,
    var androidVersion: Int = 0,
    val lastSeen: Long = System.currentTimeMillis(),
    val longRttHistory: MutableList<Long> = mutableListOf(),
    val shortRttHistory: MutableList<Long> = mutableListOf(),
    var avgLongRtt: Double = 0.0,
    var avgShortRtt: Double = 0.0,
    var currentQueue: Int = 0,
    var totalFreeMemory: Long = 0,
)

enum class TaskType { LONG, SHORT }
enum class RoutingAlgorithm { HIGHEST_BATTERY, RANDOM, RTT_MS, BATTERY_CONSERVATION }

class EdgeRegistry {
    private val edges = ConcurrentHashMap<String, EdgeDevice>()
    private val availabilityLock = ReentrantLock()
    private val availabilityCondition = availabilityLock.newCondition()

    var selectedAlgorithm = RoutingAlgorithm.RTT_MS

    private var lastLongProbeIndex = 0
    private var lastShortProbeIndex = 0

    /**
     * Blocks if no edges are AVAILABLE (First-Come, First-Served)
     */
    fun getBestEdge(taskType: TaskType): EdgeDevice? {
        if (selectedAlgorithm == RoutingAlgorithm.RANDOM) {
            return getRandomEdge()
        }

        // Blocking logic for all other algorithms
        availabilityLock.withLock {
            var best: EdgeDevice? = findBestByAlgorithm(taskType)

            // If no available edge found, wait until one becomes AVAILABLE
            while (best == null && edges.isNotEmpty()) {
                availabilityCondition.await()
                best = findBestByAlgorithm(taskType)
            }
            return best
        }
    }

    private fun findBestByAlgorithm(taskType: TaskType): EdgeDevice? {
        val availableOnes = edges.values.filter { it.status == "AVAILABLE" }
        if (availableOnes.isEmpty()) return null

        return when (selectedAlgorithm) {
            RoutingAlgorithm.RTT_MS -> chooseLowestRtt(taskType, availableOnes)
            else -> availableOnes.random()
        }
    }

    private fun getRandomEdge(): EdgeDevice? {
        val all = edges.values.toList()
        return if (all.isEmpty()) null else all.random()
    }

    private fun chooseLowestRtt(taskType: TaskType, availablePool: List<EdgeDevice>): EdgeDevice? {
        // 1. Initial Round Robin for Edges with No History
        val noHistory = availablePool.filter {
            if (taskType == TaskType.LONG) it.longRttHistory.isEmpty()
            else it.shortRttHistory.isEmpty()
        }.sortedBy { it.ip }

        if (noHistory.isNotEmpty()) {
            val edge = if (taskType == TaskType.LONG) {
                noHistory[lastLongProbeIndex++ % noHistory.size]
            } else {
                noHistory[lastShortProbeIndex++ % noHistory.size]
            }
            return edge
        }

        // 2. Inactivity Check (Priority: Send to edges with 0 queue)
        // Check for edges doing nothing (currentQueue == 0)
        val inactiveEdges = availablePool.filter { it.currentQueue == 0 }

        if (inactiveEdges.isNotEmpty()) {
            // Pick the first inactive edge found
            val target = inactiveEdges.first()

            // Step 3 & 5 logic: Define burst limit based on Android version
            // Build.VERSION_CODES.N_MR1 is API 25 (Android 7.1)
            val burstLimit = if (target.androidVersion <= 25) 8 else 10

            // Only prioritize if it hasn't reached its burst limit yet
            // This allows the proxy to "fill" inactive edges before going back to lowest RTT
            if (target.currentQueue < burstLimit) {
                return target
            }
        }

        // 4. Default: Send to lowest Average RTT among available edges
        return availablePool.minByOrNull { edge ->
            if (taskType == TaskType.LONG) edge.avgLongRtt else edge.avgShortRtt
        }
    }

    // Call this when edge notifies "/status" or via mDNS refresh
    fun updateStatus(ip: String, newStatus: String) {
        val edge = edges[ip] ?: return
        availabilityLock.withLock {
            val wasBusy = edge.status == "BUSY"
            edge.status = newStatus
            // If it became AVAILABLE, wake up the next waiting request thread
            if (wasBusy && newStatus == "AVAILABLE") {
                availabilityCondition.signal()
            }
        }
    }


    @Synchronized
    fun updateRtt(ip: String, newRtt: Long, taskType: TaskType) {
        val edge = edges[ip] ?: return
        val history = if (taskType == TaskType.LONG) edge.longRttHistory else edge.shortRttHistory

        history.add(newRtt)

        if (taskType == TaskType.LONG) {
            edge.avgLongRtt = history.average()
        } else {
            edge.avgShortRtt = history.average()
        }
    }
    /**
     * End of RTTms strategy
     */

    fun incrementQueue(ip: String) {
        edges[ip]?.let {
            synchronized(it) { it.currentQueue++ }
        }
    }

    fun decrementQueue(ip: String) {
        edges[ip]?.let {
            synchronized(it) {
                if (it.currentQueue > 0) it.currentQueue--
            }
        }
    }

    @Synchronized
    fun upsert(ip: String, battery: String, status: String, androidVer: Int = 0, freeMem: Long = 0) {
        val existing = edges[ip]
        if (existing != null) {
            existing.battery = battery
            existing.status = status
            existing.androidVersion = androidVer
            existing.totalFreeMemory = freeMem
        } else {
            val newDevice = EdgeDevice(ip, battery, status)
            newDevice.androidVersion = androidVer
            newDevice.totalFreeMemory = freeMem
            edges[ip] = newDevice
        }
    }

    @Synchronized
    fun getAll(): List<EdgeDevice> = edges.values.toList()

    @Synchronized
    fun remove(ip: String) {
        edges.remove(ip)
    }

    // Not used
    @Synchronized
    fun clear() {
        edges.clear()
    }

    // Not used. For parsing battery percentage
    private fun parseBatteryPercent(percentStr: String?): Float {
        if (percentStr.isNullOrBlank()) return -1f
        // remove % and non-numeric characters, keep digits and dot and minus
        val cleaned = percentStr.trim().replace("%", "").replace(Regex("[^0-9.\\-]"), "")
        return try {
            cleaned.toFloat()
        } catch (e: Exception) {
            -1f
        }
    }
}

