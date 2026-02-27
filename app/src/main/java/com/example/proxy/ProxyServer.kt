package com.example.proxy

import android.util.Log
import com.example.proxy.logger.DecisionLogger
import com.example.proxy.mdnsDiscovery.EdgeRegistry
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ProxyServer(
    private val edgeRegistry: EdgeRegistry,
    port: Int,
    private val logger: (String) -> Unit
) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        logger("Incoming request: $method $uri") // <-- REPLACE Log.i with logger()
        Log.i("ProxyServer", "Incoming request: $method $uri")

        return when {
            uri == "/" && method == Method.GET -> handleHtmlRequest()
            uri == "/login" && method == Method.POST -> handleLoginRequest(session)

            uri == "/recognize" && method == Method.POST -> handleRecognitionRequest(session)
            uri.startsWith("/result/") && method == Method.GET -> handleForwardingRequest(session)
            uri == "/queue-status" && method == Method.GET -> handleForwardingRequest(session)
            uri == "/set-concurrency" && method == Method.POST -> handleForwardingRequest(session)

            uri == "/battery" && method == Method.GET -> handleBatteryRequest(session)
            uri == "/download" && method == Method.GET -> handleFileDownloadRequest(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    private fun handleHtmlRequest(): Response {
        val edge = edgeRegistry.getBestEdge()
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge servers available")

        val edgeUrl = "http://${edge.ip}:8080/"
        logger("Fetching HTML from edge: $edgeUrl")
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
            logger("Error fetching HTML")
            Log.e("ProxyServer", "Error fetching HTML", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        }
    }

    private fun handleLoginRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge()
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge servers available")

        val edgeUrl = "http://${edge.ip}:8080${session.uri}"
        logger("Forwarding login to $edgeUrl")
        Log.i("ProxyServer", "Forwarding login to $edgeUrl")

        return try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            val formData = session.parameters.map { (key, values) ->
                val encodedKey = URLEncoder.encode(key, "UTF-8")
                val encodedValue = URLEncoder.encode(values.firstOrNull() ?: "", "UTF-8")
                "$encodedKey=$encodedValue"
            }.joinToString("&")

            val body = formData.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder().url(edgeUrl)
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            client.newCall(request).execute().use { edgeResponse ->
                val bytes = edgeResponse.body?.bytes() ?: ByteArray(0)
                val mime = edgeResponse.header("Content-Type") ?: "application/json"

                newFixedLengthResponse(
                    Response.Status.lookup(edgeResponse.code) ?: Response.Status.OK,
                    mime,
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            logger("Error forwarding /login")
            Log.e("ProxyServer", "Error forwarding /login", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        }
    }

    private fun handleRecognitionRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for recognition"
            )

        // Capture the client's Request ID
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // First decision call (request endpoint, chosen edge IP, edge IP battery)
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        val edgeUrl = "http://${edge.ip}:8080/recognize"
        logger("Forwarding image request to edge: $edgeUrl")
        Log.i("ProxyServer", "Forwarding image request to edge: $edgeUrl")

        val contentType = session.headers["content-type"]
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain",
                "Content-Type header is missing for recognition request"
            )

        if (!contentType.startsWith("multipart/form-data", ignoreCase = true)) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain",
                "Unsupported content type for /recognize: was '$contentType', expected 'multipart/form-data'."
            )
        }

        val authorization = session.headers["authorization"]

        var tempImageFile: java.io.File? = null

        try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            val tempImageFilePath = tempFiles["imageFile"]
                ?: throw IOException("File 'imageFile' not found in multipart request.")

            tempImageFile = java.io.File(tempImageFilePath)

            // Second decision call (for image file size)
            decision.imageSizeBytes = tempImageFile.length()
            logger("Successfully received file from client at: $tempImageFilePath")
            Log.i("ProxyServer", "Successfully received file from client at: $tempImageFilePath")

            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "imageFile",
                    tempImageFile.name,
                    tempImageFile.asRequestBody(contentType.toMediaTypeOrNull())
                )
                .build()

            val requestBuilder = Request.Builder()
                .url(edgeUrl)
                .post(requestBody)
                .header("X-Client-Request-ID", clientRequestId)

            // RECORD FORWARDING TIMESTAMP
            decision.timestampOfForwardingRequest = System.currentTimeMillis()

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization)
            }

            return client.newCall(requestBuilder.build()).execute().use { edgeResponse ->
//                val bytes = edgeResponse.body?.bytes() ?: ByteArray(0)
//                val mime = edgeResponse.header("Content-Type") ?: "application/json"

                val status = if (edgeResponse.code == 202)
                    Response.Status.ACCEPTED
                else
                    Response.Status.lookup(edgeResponse.code) ?: Response.Status.OK

                val responseBody = edgeResponse.body?.bytes() ?: ByteArray(0)

                // Finalize log as "Accepted"
                decision.status = "Accepted"

                newFixedLengthResponse(
                    status,
                    edgeResponse.header("Content-Type") ?: "application/json",
                    ByteArrayInputStream(responseBody),
                    responseBody.size.toLong()
                )
            }

        } catch (e: Exception) {
            // Decision logger
            decision.status = "Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)

            logger("Error forwarding /recognize")
            Log.e("ProxyServer", "Error forwarding /recognize", e)

            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        } finally {
            // clean up temp file
            if (tempImageFile?.exists() == true) {
                if (tempImageFile.delete()) {
                    logger("Successfully deleted temp file: ${tempImageFile.path}")
                    Log.i("ProxyServer", "Successfully deleted temp file: ${tempImageFile.path}")
                } else {
                    logger("Failed to delete temp file: ${tempImageFile.path}")
                    Log.w("ProxyServer", "Failed to delete temp file: ${tempImageFile.path}")
                }
            }
        }
    }



    private fun handleBatteryRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge()
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge")

        // 1. Capture Client Request ID
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // 2. Create the Decision Record
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        val edgeUrl = "http://${edge.ip}:8080/battery"
        val requestBuilder = Request.Builder()
            .url(edgeUrl)
            .header("X-Client-Request-ID", clientRequestId)

        session.headers["authorization"]?.let {
            requestBuilder.header("Authorization", it)
        }

        // 3. Record Forwarding Timestamp
        decision.timestampOfForwardingRequest = System.currentTimeMillis()

        return try {
            client.newCall(requestBuilder.build()).execute().use { edgeResponse ->
                // 4. Record Edge Response Timestamp
                decision.timestampOfReceivingEdgeResponse = System.currentTimeMillis()

                val bytes = edgeResponse.body?.bytes() ?: ByteArray(0)

                if (edgeResponse.isSuccessful) {
                    decision.status = "Battery_Success"
                } else {
                    decision.status = "Battery_Edge_Error_${edgeResponse.code}"
                }

                // 5. Finalize the Log (sets timestampOfSendingResponse)
                DecisionLogger.finalizeAndWrite(decision)

                newFixedLengthResponse(
                    Response.Status.lookup(edgeResponse.code) ?: Response.Status.OK,
                    edgeResponse.header("Content-Type") ?: "application/json",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            decision.status = "Battery_Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)
            Log.e("ProxyServer", "Battery request failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handleFileDownloadRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for file download"
            )

        // First decision call (request endpoint, chosen edge IP, edge IP battery)
        val decision = DecisionLogger.createRecord(session.uri, edge, "unknown")

        // Construct the full URL, including the query string
        val queryString = if (session.queryParameterString != null) "?${session.queryParameterString}" else ""
        val edgeUrl = "http://${edge.ip}:8080${session.uri}$queryString"
        logger("Forwarding download request to edge: $edgeUrl")
        Log.i("ProxyServer", "Forwarding download request to edge: $edgeUrl")

        val authorization = session.headers["authorization"]

        return try {
            val requestBuilder = Request.Builder().url(edgeUrl)

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization)
            }

            client.newCall(requestBuilder.build()).execute().use { edgeResponse ->
                if (!edgeResponse.isSuccessful) {
                    val errorBody = edgeResponse.body?.string() ?: "Edge server returned error ${edgeResponse.code}"
                    return newFixedLengthResponse(Response.Status.lookup(edgeResponse.code), "text/plain", errorBody)
                }

                // Decision call for status (if response is success or not)
                if (edgeResponse.isSuccessful) {
                    decision.status = "Success"
                } else {
                    decision.status = "Edge_Error_${edgeResponse.code}"
                }

                // Read the entire file from the edge server
                val fileBytes = edgeResponse.body?.bytes() ?: ByteArray(0)

                // Second decision call (for file size)
                decision.fileSizeBytes = fileBytes.size.toLong()

                logger("Successfully downloaded ${fileBytes.size} bytes from edge.")
                Log.i("ProxyServer", "Successfully downloaded ${fileBytes.size} bytes from edge.")

                // Get data from the edge's response to forward to the client.
                val mime = edgeResponse.header("Content-Type") ?: "application/octet-stream"
                val length = fileBytes.size.toLong()

                // Third decision call (for timestamp of sending response back to client)
                DecisionLogger.finalizeAndWrite(decision)

                // FORWARD: Send the byte array to the client.
                newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    ByteArrayInputStream(fileBytes),
                    length
                )
            }
        } catch (e: Exception) {
            // Decision logger for proxy error
            decision.status = "Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)

            logger("Error forwarding /download request")
            Log.e("ProxyServer", "Error forwarding /download request", e)

            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        }
    }

    private fun handleForwardingRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge()
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge")

        val edgeUrl = "http://${edge.ip}:8080${session.uri}"

        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"
        val decision = DecisionLogger.getRecord(clientRequestId)

        val request = Request.Builder()
            .url(edgeUrl)
            .get()
            .build()

        return client.newCall(request).execute().use { edgeResponse ->
            // RECORD RECEIVING FROM EDGE TIMESTAMP
            decision?.timestampOfReceivingEdgeResponse = System.currentTimeMillis()

            val body = edgeResponse.body?.bytes() ?: ByteArray(0)

            if (edgeResponse.code == 200 && decision != null) {
                decision.status = "Success"
                DecisionLogger.finalizeAndWrite(decision) // This sets timestampOfSendingResponse
            } else if (edgeResponse.code >= 400 && decision != null) {
                decision.status = "Final_Error_${edgeResponse.code}"
                DecisionLogger.finalizeAndWrite(decision)
            }

            newFixedLengthResponse(
                Response.Status.lookup(edgeResponse.code) ?: Response.Status.OK,
                edgeResponse.header("Content-Type") ?: "application/json",
                ByteArrayInputStream(body),
                body.size.toLong()
            )
        }
    }

}

