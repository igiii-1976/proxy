package com.example.proxy

data class EdgeDevice(
    val ip: String,
    val battery: String,
    val status: String,
    val lastSeen: Long = System.currentTimeMillis()
)

class EdgeRegistry {
    private val edges = mutableMapOf<String, EdgeDevice>()

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
    fun removeStale(timeoutMs: Long) {
        val now = System.currentTimeMillis()
        edges.entries.removeIf { now - it.value.lastSeen > timeoutMs }
    }

    @Synchronized
    fun clear() {
        edges.clear()
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

