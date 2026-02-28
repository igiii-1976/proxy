package com.example.proxy.mdnsDiscovery

data class EdgeDevice(
    val ip: String,
    val battery: String,
    val status: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val longRttHistory: MutableList<Long> = mutableListOf(),
    val shortRttHistory: MutableList<Long> = mutableListOf(),
    var avgLongRtt: Double = 0.0,
    var avgShortRtt: Double = 0.0,
    var currentQueue: Int = 0
)

enum class TaskType { LONG, SHORT }

enum class RoutingAlgorithm { HIGHEST_BATTERY, RANDOM, RTT_MS }

class EdgeRegistry {
    private val edges = mutableMapOf<String, EdgeDevice>()

    var selectedAlgorithm = RoutingAlgorithm.HIGHEST_BATTERY

    @Synchronized
    fun getBestEdge(taskType: TaskType): EdgeDevice? {
        return when (selectedAlgorithm) {
            RoutingAlgorithm.RANDOM -> getRandomEdge()
            RoutingAlgorithm.HIGHEST_BATTERY -> chooseHighestBattery()
            RoutingAlgorithm.RTT_MS -> chooseLowestRtt(taskType)
        }
    }

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

    private fun chooseLowestRtt(taskType: TaskType): EdgeDevice? {
        val allEdges = edges.values.toList()
        if (allEdges.isEmpty()) return null

        // Priority 1: Initial Config (Check specific history based on task type)
        val edgesWithNoHistory = allEdges.filter {
            if (taskType == TaskType.LONG) it.longRttHistory.isEmpty()
            else it.shortRttHistory.isEmpty()
        }

        if (edgesWithNoHistory.isNotEmpty()) return edgesWithNoHistory.random()

        // Priority 2: Effective RTT using specific average
        return allEdges.minByOrNull { edge ->
            val baseRtt = if (taskType == TaskType.LONG) edge.avgLongRtt else edge.avgShortRtt
            baseRtt * (1 + edge.currentQueue)
        }
    }

    @Synchronized
    fun updateRtt(ip: String, newRtt: Long, taskType: TaskType) {
        val edge = edges[ip] ?: return
        val history = if (taskType == TaskType.LONG) edge.longRttHistory else edge.shortRttHistory

        history.add(newRtt)
        if (history.size > 20) history.removeAt(0)

        if (taskType == TaskType.LONG) {
            edge.avgLongRtt = history.average()
        } else {
            edge.avgShortRtt = history.average()
        }
    }

    @Synchronized
    fun incrementQueue(ip: String) {
        edges[ip]?.let { it.currentQueue++ }
    }

    @Synchronized
    fun decrementQueue(ip: String) {
        edges[ip]?.let {
            if (it.currentQueue > 0) it.currentQueue--
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
            // Add new entry
            edges[ip] = EdgeDevice(ip, battery, status, System.currentTimeMillis())
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

