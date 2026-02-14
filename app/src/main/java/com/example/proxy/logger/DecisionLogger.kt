package com.example.proxy.logger

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.proxy.mdnsDiscovery.EdgeDevice
import com.example.proxy.UiLogger
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap

object DecisionLogger {

    private var fileWriter: FileWriter? = null
    private var queueFileWriter: FileWriter? = null
    private var isInitialized = false

    val liveQueue = ConcurrentHashMap<String, Decision>()

    // Initializes the logger with a file path. Must be called once on app startup.
    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            val baseDir = context.getExternalFilesDir(null)

            val customDocsDir = File(baseDir, "Documents")

            if (!customDocsDir.exists()) {
                val created = customDocsDir.mkdirs()
                Log.i("DecisionLogger", "Creating custom Documents folder: $created")
            }

            val logFile = File(customDocsDir, "proxy_decisions_detailed.csv")
            val queueFile = File(customDocsDir, "proxy_queue_log.txt")

            val writeHeader = !logFile.exists() || logFile.length() == 0L

            fileWriter = FileWriter(logFile, true)
            if (writeHeader) {
                fileWriter?.append(Decision.getCsvHeader())
                fileWriter?.flush()
            }

            queueFileWriter = FileWriter(queueFile, true)
            queueFileWriter?.append("\n--- New Session Started: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())} ---\n")
            queueFileWriter?.flush()

            isInitialized = true
            Log.i("DecisionLogger", "✅ Logging initialized in custom folder: ${logFile.absolutePath}")

        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to initialize file logger", e)
        }
    }

    // Queue helper function
    private fun logQueueSnapshot(action: String, requestId: String) {
        try {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val currentQueueIds = liveQueue.keys().toList()

            val logEntry = "[$timestamp] ACTION: $action | ID: $requestId | ACTIVE_COUNT: ${currentQueueIds.size} | QUEUE: $currentQueueIds\n"

            queueFileWriter?.append(logEntry)
            queueFileWriter?.flush()
        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to write queue snapshot", e)
        }
    }


    // This should be called immediately after choosing an edge.
    fun createRecord(requestPath: String, chosenEdge: EdgeDevice, requestID: String): Decision {
        val decision = Decision(
            timestampOfReceivingRequest = System.currentTimeMillis(),
            requestPath = requestPath,
            chosenEdgeIp = chosenEdge.ip,
            edgeBatteryPercent = chosenEdge.battery,
            requestID = requestID
        )
        // Add to queue
        liveQueue[decision.requestID] = decision
        logQueueSnapshot("ADDED", decision.requestID)

        return decision
    }

    // This should be called just before returning the final response to the client.
    @Synchronized
    fun finalizeAndWrite(decision: Decision) {
        if (!isInitialized) {
            Log.w("DecisionLogger", "Logger not initialized. Cannot record decision.")
            return
        }

        // Set the final timestamp before writing.
        decision.timestampOfSendingResponse = System.currentTimeMillis()
        // Remove from live queue as the request is finished
        liveQueue.remove(decision.requestID)
        logQueueSnapshot("REMOVED", decision.requestID)

        try {
            fileWriter?.append(decision.toCsvLine())
            fileWriter?.flush()

            UiLogger.log("Decision '${decision.requestPath}': " + "Edge ${decision.chosenEdgeIp} | " + "RTT ${decision.rttMs}ms")
        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to write to log file", e)
        }
    }


    @Synchronized
    fun close() {
        try {
            fileWriter?.close()
            queueFileWriter?.close()
            isInitialized = false
            Log.i("DecisionLogger", "Logger file closed.")
        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to close logger file", e)
        }
    }

    fun getRecord(requestId: String): Decision? = liveQueue[requestId]
}
