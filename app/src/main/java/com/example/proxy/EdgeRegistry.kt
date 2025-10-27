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
}

