package com.example.proxy

import android.content.Context
import android.os.Environment
import android.util.Log
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
            // Get the public Documents directory
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val logFile = File(documentsDir, "proxy_decisions.csv")

            // Check if file is new to write the header
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

    // Records a new routing decision by writing it to the CSV file.
    @Synchronized
    fun record(requestPath: String, chosenEdge: EdgeDevice) {
        if (!isInitialized) {
            Log.w("DecisionLogger", "Logger not initialized. Cannot record decision.")
            return
        }

        val newDecision = Decision(requestPath = requestPath, chosenEdgeIp = chosenEdge.ip)
        try {
            // Append the new decision as a CSV line
            fileWriter?.append(newDecision.toCsvLine())
            fileWriter?.flush()

            UiLogger.log("Decision: '${newDecision.requestPath}' -> Edge ${newDecision.chosenEdgeIp}")
        } catch (e: Exception) {
            Log.e("DecisionLogger", "Failed to write to log file", e)
        }
    }


    // Closes the file writer. Should be called when the app is destroyed.
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
