package com.example.proxy

import android.util.Log
import com.example.proxy.logger.DecisionLogger
import com.example.proxy.mdnsDiscovery.EdgeRegistry
import com.example.proxy.mdnsDiscovery.TaskType
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
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
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
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
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
        val edge = edgeRegistry.getBestEdge(TaskType.LONG)
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for recognition"
            )

        // Increase chosen edge's queue
        edgeRegistry.incrementQueue(edge.ip, TaskType.LONG)

        // Capture the client's Request ID
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // 1. Create the initial decision record (sets timestampOfReceivingRequest)
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        val edgeUrl = "http://${edge.ip}:8080/recognize"
        logger("Forwarding image request to edge: $edgeUrl")

        val contentType = session.headers["content-type"]
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain",
                "Content-Type header is missing"
            )

        if (!contentType.startsWith("multipart/form-data", ignoreCase = true)) {
            decision.status = "Client_Error_400"
            DecisionLogger.finalizeAndWrite(decision)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Type")
        }

        val authorization = session.headers["authorization"]
        var tempImageFile: java.io.File? = null

        try {
            val tempFiles = mutableMapOf<String, String>()
            session.parseBody(tempFiles)

            val tempImageFilePath = tempFiles["imageFile"]
                ?: throw IOException("File 'imageFile' not found in multipart request.")

            tempImageFile = java.io.File(tempImageFilePath)
            decision.imageSizeBytes = tempImageFile.length()

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

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization)
            }

            // 2. RECORD FORWARDING TIMESTAMP
            decision.timestampOfForwardingRequest = System.currentTimeMillis()

            // Execute the blocking call to the edge
            return client.newCall(requestBuilder.build()).execute().use { edgeResponse ->

                // 3. RECORD RECEIVING FROM EDGE TIMESTAMP (Headers received)
                decision.timestampOfReceivingEdgeResponse = System.currentTimeMillis()

                val responseBody = edgeResponse.body?.bytes() ?: ByteArray(0)

                // 4. Manually set End Timestamp for total RTT calculation
                decision.timestampOfSendingResponse = System.currentTimeMillis()
                val totalRtt = decision.rttMs ?: 0L

                if (edgeResponse.isSuccessful) {
                    decision.status = "Success"
                    // Update algorithms using Total RTT (Full Proxy Round Trip)
                    edgeRegistry.updateRtt(edge.ip, totalRtt, TaskType.LONG)
                    edgeRegistry.recordWorkload(edge.ip, totalRtt)
                } else {
                    decision.status = "Edge_Error_${edgeResponse.code}"
                }

                DecisionLogger.finalizeAndWrite(decision)

                newFixedLengthResponse(
                    Response.Status.lookup(edgeResponse.code) ?: Response.Status.OK,
                    edgeResponse.header("Content-Type") ?: "application/json",
                    ByteArrayInputStream(responseBody),
                    responseBody.size.toLong()
                )
            }

        } catch (e: Exception) {
            decision.status = "Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)
            Log.e("ProxyServer", "Error forwarding /recognize", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        } finally {
            // Clean up temp file
            if (tempImageFile?.exists() == true) {
                tempImageFile.delete()
            }
            // Always decrement queue of chosen edge
            edgeRegistry.decrementQueue(edge.ip)
        }
    }



    private fun handleBatteryRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
            ?: return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "No edge")

        // Increase chosen edge's queue
        edgeRegistry.incrementQueue(edge.ip, TaskType.SHORT)

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
                val endTime = System.currentTimeMillis()
                val rtt = endTime - decision.timestampOfForwardingRequest!!

                // Update Rtt and Decrement Queue on success
                if (edgeResponse.isSuccessful) {
                    edgeRegistry.updateRtt(edge.ip, rtt, TaskType.SHORT)
                }

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
        } finally {
            // Always decrement queue of chosen edge
            edgeRegistry.decrementQueue(edge.ip)
        }
    }

    private fun handleFileDownloadRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge(TaskType.SHORT)
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for file download"
            )

        // 1. Increment chosen edge's queue (Backpressure/SED logic)
        edgeRegistry.incrementQueue(edge.ip, TaskType.SHORT)

        // 2. Capture Client Request ID for end-to-end traceability
        val clientRequestId = session.headers["x-client-request-id"] ?: "unknown"

        // 3. Create initial decision record (Captures timestampOfReceivingRequest)
        val decision = DecisionLogger.createRecord(session.uri, edge, clientRequestId)

        // Construct the full URL, including the query string (e.g., ?file=test.txt)
        val queryString = if (session.queryParameterString != null) "?${session.queryParameterString}" else ""
        val edgeUrl = "http://${edge.ip}:8080${session.uri}$queryString"

        logger("Forwarding download request to edge: $edgeUrl")

        return try {
            val requestBuilder = Request.Builder()
                .url(edgeUrl)
                .header("X-Client-Request-ID", clientRequestId)

            session.headers["authorization"]?.let {
                requestBuilder.header("Authorization", it)
            }

            // 4. RECORD FORWARDING TIMESTAMP (Proxy -> Edge)
            decision.timestampOfForwardingRequest = System.currentTimeMillis()

            client.newCall(requestBuilder.build()).execute().use { edgeResponse ->

                // 5. RECORD EDGE RESPONSE TIMESTAMP (Edge -> Proxy headers received)
                decision.timestampOfReceivingEdgeResponse = System.currentTimeMillis()

                if (!edgeResponse.isSuccessful) {
                    decision.status = "Download_Error_${edgeResponse.code}"
                    decision.timestampOfSendingResponse = System.currentTimeMillis()
                    DecisionLogger.finalizeAndWrite(decision)
                    return newFixedLengthResponse(Response.Status.lookup(edgeResponse.code), "text/plain", "Error")
                }

                // Store and Forward
                val fileBytes = edgeResponse.body?.bytes() ?: ByteArray(0)
                decision.fileSizeBytes = fileBytes.size.toLong()

                decision.timestampOfSendingResponse = System.currentTimeMillis()
                val totalRtt = decision.rttMs ?: 0L

                decision.status = "Success"
                edgeRegistry.updateRtt(edge.ip, totalRtt, TaskType.SHORT)
                edgeRegistry.recordWorkload(edge.ip, totalRtt)

                DecisionLogger.finalizeAndWrite(decision)

                val mime = edgeResponse.header("Content-Type") ?: "application/octet-stream"

                // FORWARD: Send the byte array to the client
                newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    ByteArrayInputStream(fileBytes),
                    fileBytes.size.toLong()
                )
            }
        } catch (e: Exception) {
            decision.status = "Download_Proxy_Error"
            DecisionLogger.finalizeAndWrite(decision)

            logger("Error forwarding /download request")
            Log.e("ProxyServer", "Error forwarding /download request", e)

            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        } finally {
            // 7. ALWAYS decrement queue of chosen edge to release the lock
            edgeRegistry.decrementQueue(edge.ip)
        }
    }

    private fun handleForwardingRequest(session: IHTTPSession): Response {
        val edge = edgeRegistry.getBestEdge(TaskType.LONG)
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

