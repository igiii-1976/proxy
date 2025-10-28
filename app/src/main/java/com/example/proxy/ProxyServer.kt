package com.example.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Headers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProxyServer(
    private val edgeRegistry: EdgeRegistry,
    port: Int
) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.i("ProxyServer", "Incoming request: $method $uri")

        return when {
            // Serve HTML from edge
            uri == "/" && method == Method.GET -> handleHtmlRequest()

            // Handle login forwarding
            uri == "/login" && method == Method.POST -> handleLoginRequest(session)

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
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

    private fun handleLoginRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.chooseHighestBattery()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain",
                "No edge servers available"
            )

        val edgeUrl = "http://${edge.ip}:8080${session.uri}"
        Log.i("ProxyServer", "Forwarding login request to: $edgeUrl")

        val contentType = session.headers["content-type"]
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Content-Type header is missing"
            )

        return try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            // Everything below just forwards the entire data from the client to the edge
            val formData = session.parameters.map { (key, values) ->
                val encodedKey = URLEncoder.encode(key, "UTF-8")
                val encodedValue = URLEncoder.encode(values.firstOrNull() ?: "", "UTF-8")
                "$encodedKey=$encodedValue"
            }.joinToString("&")

            val requestBody = formData.toRequestBody("application/x-www-form-urlencoded".toMediaType())

            // Build the forwarding request to the edge
            val request = Request.Builder()
                .url(edgeUrl)
                .post(requestBody)
                .header("Content-Type", "application/x-www-form-urlencoded") // Explicitly set the correct header.
                .build()

            // Execute call to edge and return response back to the original client.
            client.newCall(request).execute().use { edgeResponse ->
                val responseBody = edgeResponse.body?.bytes() ?: ByteArray(0)
                val mime = edgeResponse.header("Content-Type") ?: "application/json"

                newFixedLengthResponse(
                    Response.Status.lookup(edgeResponse.code) ?: Response.Status.OK,
                    mime,
                    ByteArrayInputStream(responseBody),
                    responseBody.size.toLong()
                )
            }
        } catch (e: Exception) {
            Log.e("ProxyServer", "Error forwarding login request", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Proxy forwarding error: ${e.message}"
            )
        }
    }
}
