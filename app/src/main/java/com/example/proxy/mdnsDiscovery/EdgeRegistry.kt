package com.example.proxy.mdnsDiscovery

import java.util.concurrent.ConcurrentHashMap

data class EdgeDevice(
    val ip: String,
    val battery: String,
    val status: String,
    val lastSeen: Long = System.currentTimeMillis(),
    // RTTms data
    val longRttHistory: MutableList<Long> = mutableListOf(),
    val shortRttHistory: MutableList<Long> = mutableListOf(),
    var avgLongRtt: Double = 0.0,
    var avgShortRtt: Double = 0.0,
    var currentQueue: Int = 0,
    var maxConcurrentTasks: Int = 3,
    // Battery conservation data
    var previousBattery: Int = -1,
    var workloadAccumulatorMs: Long = 0,
    var energyCost: Double = 0.0        // E = BatteryConsumed / WorkloadSeconds
)

enum class TaskType { LONG, SHORT }

enum class RoutingAlgorithm { HIGHEST_BATTERY, RANDOM, RTT_MS, BATTERY_CONSERVATION}

class EdgeRegistry {
    private val edges = ConcurrentHashMap<String, EdgeDevice>()

    var selectedAlgorithm = RoutingAlgorithm.HIGHEST_BATTERY

    // --- Round Robin Counters for Probing ---
    private var lastLongProbeIndex = 0
    private var lastShortProbeIndex = 0

    // Updates the max concurrency limit for a specific edge device by IP.
    @Synchronized
    fun updateDeviceMaxConcurrency(ip: String, limit: Int) {
        edges[ip]?.let { device ->
            synchronized(device) {
                device.maxConcurrentTasks = limit
                // Wake up any threads waiting for this specific device
                (device as java.lang.Object).notifyAll()
            }
        }
    }

    @Synchronized
    fun getBestEdge(taskType: TaskType): EdgeDevice? {
        return when (selectedAlgorithm) {
            RoutingAlgorithm.RANDOM -> getRandomEdge()
            RoutingAlgorithm.HIGHEST_BATTERY -> chooseHighestBattery()
            RoutingAlgorithm.RTT_MS -> chooseLowestRtt(taskType)
            RoutingAlgorithm.BATTERY_CONSERVATION -> chooseLowestBatteryConsumption(taskType)
        }
    }

    @Synchronized
    private fun getRandomEdge(): EdgeDevice? {
        val allEdges = edges.values.toList()
        if (allEdges.isEmpty()) return null
        return allEdges.random()
    }

    @Synchronized
    private fun chooseHighestBattery(): EdgeDevice? {
        return edges.values
            .map { edge ->
                val numeric = parseBatteryPercent(edge.battery)
                Pair(edge, numeric)
            }
            .filter { it.second >= 0f }        // ignore invalid readings
            .maxByOrNull { it.second }?.first  // return the EdgeDevice with highest numeric battery
    }

    /**
     * Start of RTTms strategy
     */
    @Synchronized
    private fun chooseLowestRtt(taskType: TaskType): EdgeDevice? {
        val allEdges = edges.values.toList()
        if (allEdges.isEmpty()) return null

        // 1. Filter for edges that are NOT currently at their concurrency limit
        val availableEdges = allEdges.filter { it.currentQueue < it.maxConcurrentTasks }

        // Determine candidate pool: prioritize those with room.
        // If everyone is full, use the full list (it will wait in incrementQueue).
        val candidatePool = if (availableEdges.isNotEmpty()) availableEdges else allEdges

        // Priority 1: Round Robin Probing for Edges with No History within the candidate pool
        val edgesWithNoHistory = candidatePool.filter {
            if (taskType == TaskType.LONG) it.longRttHistory.isEmpty()
            else it.shortRttHistory.isEmpty()
        }.sortedBy { it.ip }

        if (edgesWithNoHistory.isNotEmpty()) {
            return if (taskType == TaskType.LONG) {
                val index = lastLongProbeIndex % edgesWithNoHistory.size
                val edge = edgesWithNoHistory[index]
                lastLongProbeIndex++
                edge
            } else {
                val index = lastShortProbeIndex % edgesWithNoHistory.size
                val edge = edgesWithNoHistory[index]
                lastShortProbeIndex++
                edge
            }
        }

        // Priority 2: Redirect to the "next best" edge (lowest RTT in the available pool)
        return candidatePool.minByOrNull { edge ->
            if (taskType == TaskType.LONG) edge.avgLongRtt else edge.avgShortRtt
        }
    }

    @Synchronized
    fun updateRtt(ip: String, newRtt: Long, taskType: TaskType) {
        val edge = edges[ip] ?: return
        val history = if (taskType == TaskType.LONG) edge.longRttHistory else edge.shortRttHistory

        history.add(newRtt)
//        if (history.size > 20) history.removeAt(0)

        if (taskType == TaskType.LONG) {
            edge.avgLongRtt = history.average()
        } else {
            edge.avgShortRtt = history.average()
        }
    }
    /**
     * End of RTTms strategy
     */

    /**
     * Start of Battery conservation strategy
     */
    @Synchronized
    fun recordWorkload(ip: String, durationMs: Long) {
        edges[ip]?.let { it.workloadAccumulatorMs += durationMs }
    }

    private fun chooseLowestBatteryConsumption(taskType: TaskType): EdgeDevice? {
        val allEdges = edges.values.toList()
        if (allEdges.isEmpty()) return null

        // 1. Initial Routing: Round Robin if no Energy Cost data or RTT data exists yet
        val needsData = allEdges.filter {
            it.energyCost <= 0.0 ||
                    (if (taskType == TaskType.LONG) it.avgLongRtt <= 0.0 else it.avgShortRtt <= 0.0)
        }.sortedBy { it.ip }

        if (needsData.isNotEmpty()) {
            return if (taskType == TaskType.LONG) {
                val edge = needsData[lastLongProbeIndex % needsData.size]
                lastLongProbeIndex++
                edge
            } else {
                val edge = needsData[lastShortProbeIndex % needsData.size]
                lastShortProbeIndex++
                edge
            }
        }

        // 2. Predict battery consumption: C = E * (AvgRTT / 1000)
        return allEdges.minByOrNull { edge ->
            val avgRtt = if (taskType == TaskType.LONG) edge.avgLongRtt else edge.avgShortRtt
            val predictedConsumption = edge.energyCost * (avgRtt / 1000.0)
            predictedConsumption * (1 + (edge.currentQueue * 0.3))
        }
    }

    // Updates Energy Metrics (E = B / W). Called every 5 mins.
    @Synchronized
    fun updateEnergyMetrics(ip: String, currentBatteryStr: String) {
        val edge = edges[ip] ?: return
        val currentBat = parseBatteryPercent(currentBatteryStr).toInt()

        if (edge.previousBattery != -1 && edge.workloadAccumulatorMs > 0) {
            val batteryConsumed = (edge.previousBattery - currentBat).coerceAtLeast(0)
            val workloadSeconds = edge.workloadAccumulatorMs / 1000.0

            // Energy Cost E = Battery Lost per Second of active work
            edge.energyCost = batteryConsumed / workloadSeconds
        }

        // Reset for next interval
        edge.previousBattery = currentBat
        edge.workloadAccumulatorMs = 0
    }
    /**
     * End of Battery conservation strategy
     */

    // In EdgeRegistry.kt

    fun incrementQueue(ip: String, taskType: TaskType) {
        val edge = edges[ip] ?: return
        synchronized(edge) {
            // ONLY block if the task is LONG (Image Recognition)
            if (taskType == TaskType.LONG) {
                while (edge.currentQueue >= edge.maxConcurrentTasks) {
                    try {
                        (edge as java.lang.Object).wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }

            // We still increment the queue for ALL tasks so SED routing
            // knows the total load on the CPU/Network
            edge.currentQueue++
        }
    }

    fun decrementQueue(ip: String) {
        val edge = edges[ip] ?: return
        synchronized(edge) {
            if (edge.currentQueue > 0) {
                edge.currentQueue--
            }
            // Notify the next thread in the "Proxy Queue" that a slot is open
            (edge as java.lang.Object).notifyAll()
        }
    }

    @Synchronized
    fun upsert(ip: String, battery: String, status: String) {
        val existing = edges[ip]
        if (existing != null) {
            edges[ip] = existing.copy(
                battery = battery,
                status = status,
                lastSeen = System.currentTimeMillis()
            )
        } else {
            val newDevice = EdgeDevice(ip, battery, status, System.currentTimeMillis())
            newDevice.previousBattery = parseBatteryPercent(battery).toInt()
            edges[ip] = newDevice
        }
    }


    @Synchronized
    fun getAll(): List<EdgeDevice> = edges.values.toList()

    @Synchronized
    fun remove(ip: String) {
        edges.remove(ip)
    }

    @Synchronized
    fun removeStale(stalenessThresholdMs: Long): Int {
        val now = System.currentTimeMillis()
        val originalSize = edges.size

        val iterator = edges.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > stalenessThresholdMs) {
                iterator.remove()
            }
        }

        // Reset counters if all edges are lost/stale to start fresh
        if (edges.isEmpty()) {
            lastLongProbeIndex = 0
            lastShortProbeIndex = 0
        }

        val newSize = edges.size
        return originalSize - newSize
    }

    @Synchronized
    fun clear() {
        edges.clear()
    }

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

