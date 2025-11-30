package com.example.proxy.logger

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.proxy.mdnsDiscovery.EdgeDevice
import com.example.proxy.UiLogger
import java.io.File
import java.io.FileWriter

object DecisionLogger {

    private var fileWriter: FileWriter? = null
    private var isInitialized = false

    // Initializes the logger with a file path. Must be called once on app startup.
    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val logFile = File(documentsDir, "proxy_decisions_detailed.csv") // New filename

            val writeHeader = !logFile.exists()
            fileWriter = FileWriter(logFile, true)

            if (writeHeader) {
                fileWriter?.append(Decision.getCsvHeader())
            }

            isInitialized = true
            Log.i("DecisionLogger", "Logger initialized. Writing to: ${logFile.absolutePath}")

        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to initialize file logger", e)
        }
    }

    // This should be called immediately after choosing an edge.
    fun createRecord(requestPath: String, chosenEdge: EdgeDevice): Decision {
        return Decision(
            timestampOfReceivingRequest = System.currentTimeMillis(),
            requestPath = requestPath,
            chosenEdgeIp = chosenEdge.ip,
            edgeBatteryPercent = chosenEdge.battery
        )
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
            isInitialized = false
            Log.i("DecisionLogger", "Logger file closed.")
        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to close logger file", e)
        }
    }
}
