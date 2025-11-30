package com.example.proxy.logger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Decision(
    val requestID: String = UUID.randomUUID().toString(),
    val timestampOfReceivingRequest: Long,
    var timestampOfSendingResponse: Long? = null,
    val requestPath: String,
    val chosenEdgeIp: String,
    val edgeBatteryPercent: String,
    var imageSizeBytes: Long? = null,
    var fileSizeBytes: Long? = null
) {
    // Helper function to calculate Round-Trip Time (RTT) in milliseconds.
    val rttMs: Long?
        get() = if (timestampOfSendingResponse != null) {
            timestampOfSendingResponse!! - timestampOfReceivingRequest
        } else {
            null
        }

    // Returns the decision data as a CSV-formatted string line.
    fun toCsvLine(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        val receivedTimestamp = sdf.format(Date(timestampOfReceivingRequest))
        val sentTimestamp = if (timestampOfSendingResponse != null) sdf.format(
            Date(
                timestampOfSendingResponse!!
            )
        ) else ""

        // Use empty strings for null numeric values to keep CSV structure clean.
        return "$requestID," +
                "$receivedTimestamp," +
                "$sentTimestamp," +
                "$requestPath," +
                "$chosenEdgeIp," +
                "$edgeBatteryPercent," +
                "${imageSizeBytes ?: ""}," +
                "${fileSizeBytes ?: ""}," +
                "${rttMs ?: ""}\n"
    }

    companion object {
        // Returns the CSV header row.
        fun getCsvHeader(): String {
            return "RequestID," +
                    "TimestampReceive," +
                    "TimestampSend," +
                    "RequestPath," +
                    "ChosenEdgeIp," +
                    "EdgeBatteryPercent," +
                    "ImageSizeBytes," +
                    "FileSizeBytes," +
                    "RttMS\n"
        }
    }
}