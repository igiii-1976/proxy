package com.example.proxy.logger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Decision(
    val requestID: String,
    val timestampOfReceivingRequest: Long,
    var timestampOfForwardingRequest: Long? = null,
    var timestampOfReceivingEdgeResponse: Long? = null,
    var timestampOfSendingResponse: Long? = null,
    val requestPath: String,
    val chosenEdgeIp: String,
    val edgeBatteryPercent: String,
    var imageSizeBytes: Long? = null,
    var fileSizeBytes: Long? = null,
    var status: String = "In_Progress"
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
        val forwardTimestamp = if (timestampOfForwardingRequest != null) sdf.format(Date(timestampOfForwardingRequest!!)) else ""
        val edgeReceiveTimestamp = if (timestampOfReceivingEdgeResponse != null) sdf.format(Date(timestampOfReceivingEdgeResponse!!)) else ""
        val sentTimestamp = if (timestampOfSendingResponse != null) sdf.format(Date(timestampOfSendingResponse!!)) else ""

        return "$requestID," +
                "$receivedTimestamp," +
                "$forwardTimestamp," +
                "$edgeReceiveTimestamp," +
                "$sentTimestamp," +
                "$requestPath," +
                "$chosenEdgeIp," +
                "$edgeBatteryPercent," +
                "${imageSizeBytes ?: ""}," +
                "${fileSizeBytes ?: ""}," +
                "${rttMs ?: ""}," +
                "$status\n"
    }


    companion object {
        // Returns the CSV header row.
        fun getCsvHeader(): String {
            return "RequestID," +
                    "TimestampReceive," +
                    "TimestampForward," +
                    "TimestampEdgeReceive," +
                    "TimestampSend," +
                    "RequestPath," +
                    "ChosenEdgeIp," +
                    "EdgeBatteryPercent," +
                    "ImageSizeBytes," +
                    "FileSizeBytes," +
                    "RttMS," +
                    "Status\n"
        }
    }
}