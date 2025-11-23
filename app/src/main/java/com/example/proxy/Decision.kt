package com.example.proxy

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Decision(
    val timestamp: Long = System.currentTimeMillis(),
    val requestPath: String,
    val chosenEdgeIp: String
) {
    // Helper function to format the timestamp for easy reading.
    fun getFormattedTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun toCsvLine(): String {
        return "${getFormattedTimestamp()},$requestPath,$chosenEdgeIp\n"
    }

    companion object {
        fun getCsvHeader(): String {
            return "Timestamp,RequestPath,ChosenEdgeIp\n"
        }
    }
}
