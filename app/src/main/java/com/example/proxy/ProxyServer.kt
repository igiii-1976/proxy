package com.example.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ProxyServer(
    private val edgeRegistry: EdgeRegistry,
    port: Int
) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.i("ProxyServer", "Incoming request: $uri")

        // Handles serving of HTLM page
        return if ( method == Method.GET && uri == "/") {
            handleHtmlRequest()
        } else {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "404 Not Found"
            )
        }
    }

    private fun handleHtmlRequest(): Response {
        val edges = edgeRegistry.getAll()
        if (edges.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No edge servers available"
            )
        }

        val chosenEdge = edgeRegistry.chooseHighestBattery()
        val edgeUrl = "http://${chosenEdge?.ip}:8080/"
        Log.i("ProxyServer", "Fetching HTML from edge: $edgeUrl")

        return try {
            val request = Request.Builder().url(edgeUrl).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val mime = response.header("Content-Type") ?: "text/html"

                newFixedLengthResponse(
                    Response.Status.lookup(response.code) ?: Response.Status.OK,
                    mime,
                    body
                )
            }
        } catch (e: Exception) {
            Log.e("ProxyServer", "Error fetching HTML from edge", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error fetching HTML: ${e.message}"
            )
        }
    }
}
