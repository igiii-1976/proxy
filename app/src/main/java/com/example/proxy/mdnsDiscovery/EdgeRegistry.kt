package com.example.proxy.mdnsDiscovery

data class EdgeDevice(
    val ip: String,
    val battery: String,
    val status: String,
    val lastSeen: Long = System.currentTimeMillis()
)

enum class RoutingAlgorithm {
    HIGHEST_BATTERY,
    RANDOM
}

class EdgeRegistry {
    private val edges = mutableMapOf<String, EdgeDevice>()

    var selectedAlgorithm = RoutingAlgorithm.HIGHEST_BATTERY

    @Synchronized
    fun getBestEdge(): EdgeDevice? {
        return when (selectedAlgorithm) {
            RoutingAlgorithm.RANDOM -> getRandomEdge()
            RoutingAlgorithm.HIGHEST_BATTERY -> chooseHighestBattery()
        }
    }

    private fun getRandomEdge(): EdgeDevice? {
        val allEdges = edges.values.toList()
        if (allEdges.isEmpty()) return null
        return allEdges.random()
    }

    @Synchronized
    fun chooseHighestBattery(): EdgeDevice? {
        return edges.values
            .map { edge ->
                val numeric = parseBatteryPercent(edge.battery)
                Pair(edge, numeric)
            }
            .filter { it.second >= 0f }        // ignore invalid readings
            .maxByOrNull { it.second }?.first  // return the EdgeDevice with highest numeric battery
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

