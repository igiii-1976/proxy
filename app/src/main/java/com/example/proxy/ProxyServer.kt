package com.example.proxy

import android.util.Log
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
            uri == "/battery" && method == Method.GET -> handleBatteryRequest(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    private fun handleHtmlRequest(): Response {
        val edge = edgeRegistry.chooseHighestBattery()
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
        val edge = edgeRegistry.chooseHighestBattery()
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
        val edge = edgeRegistry.chooseHighestBattery()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for recognition"
            )

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

            val requestBuilder = Request.Builder().url(edgeUrl).post(requestBody)

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization)
            }

            return client.newCall(requestBuilder.build()).execute().use { edgeResponse ->
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
        val edge = edgeRegistry.chooseHighestBattery()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "text/plain", "No edge servers available for battery status"
            )

        val edgeUrl = "http://${edge.ip}:8080/battery"
        Log.i("ProxyServer", "Forwarding battery request to edge: $edgeUrl")

        val authorization = session.headers["authorization"]

        return try {
            val requestBuilder = Request.Builder().url(edgeUrl)

            if (authorization != null) {
                requestBuilder.header("Authorization", authorization)
            }

            client.newCall(requestBuilder.build()).execute().use { edgeResponse ->
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
            Log.e("ProxyServer", "Error forwarding /battery", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        }
    }

}
