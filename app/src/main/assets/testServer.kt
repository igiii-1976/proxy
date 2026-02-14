import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.sql.Types.NULL
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentValues
import android.provider.MediaStore
import android.net.Uri


// Data class for sending predictions back as JSON
data class Prediction(val label: String, val score: Float)

class testServer(
    private val context: android.content.Context,
    private val userDao: UserDao,
    port: Int
) : fi.iki.elonen.NanoHTTPD(port) {
    // --- NEW: Concurrency Control Variables ---
    @Volatile // Ensures writes are visible across threads
    private var maxConcurrentTasks = 2 // Default to 2 concurrent tasks

    // A semaphore to limit the number of active recognition tasks.
    @Volatile
    private var recognitionSemaphore =
        java.util.concurrent.Semaphore(maxConcurrentTasks, true) // `true` for fairness
    private val serverJob = SupervisorJob()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    private val activeProcessingTasks = java.util.concurrent.atomic.AtomicInteger(0)
    private val queuedWaitingTasks = java.util.concurrent.atomic.AtomicInteger(0)
    private val taskResults = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var cachedBatteryJson: String? = null
    @Volatile private var lastBatteryFetchTime: Long = 0
    private val batteryCacheDurationMs = 1000 // Cache for 1 second

    private val activeTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()

    /**
     * Start of timestamp logs to CSV
     */
    private val csvOutputFile = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
        "recognition_metrics.csv"
    )
    private val csvLock = Any()

    private fun getDetailedTimestamp(): String {
        return java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    private fun logToCsv(
        clientId: String,
        received: String,
        start: String,
        end: String,
        sent: String
    ) {
        synchronized(csvLock) {
            try {
                val resolver = context.contentResolver
                val fileName = "recognition_metrics.csv"

                // 1. Check if the file already exists in MediaStore
                val queryUri = android.provider.MediaStore.Files.getContentUri("external")
                val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(fileName)

                var uri: android.net.Uri? = null
                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                        uri = android.net.Uri.withAppendedPath(queryUri, id.toString())
                    }
                }

                // 2. If it doesn't exist, create it
                if (uri == null) {
                    val contentValues = android.content.ContentValues().apply {
                        android.content.ContentValues.put(
                            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                            fileName
                        )
                        android.content.ContentValues.put(
                            android.provider.MediaStore.MediaColumns.MIME_TYPE,
                            "text/csv"
                        )
                        android.content.ContentValues.put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOCUMENTS
                        )
                    }
                    uri = resolver.insert(queryUri, contentValues)
                }

                // 3. Append the data using "wa" (Write-Append) mode
                uri?.let { fileUri ->
                    resolver.openOutputStream(fileUri, "wa")?.use { outputStream ->
                        val row = "$clientId,$received,$start,$end,$sent\n"
                        outputStream.write(row.toByteArray())
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TestServer", "Failed to write to MediaStore CSV", e)
            }
        }
    }
    /**
     * End of timestamp logs to CSV
     */

    override fun stop() {
        super.stop()
        // Ensure the queue is stopped when the server stops.
//        RecognitionTaskQueue.stop()
    }

    /**
     * The main entry point for all HTTP requests.
     */
    override fun serve(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        // Use runBlocking on a background thread to safely wait for our suspend functions.
        return runBlocking(Dispatchers.IO) {
            try {
                // Handle CORS preflight OPTIONS requests first.
                if (session.method == fi.iki.elonen.NanoHTTPD.Method.OPTIONS) {
                    addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                            fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT,
                            null
                        )
                    )
                } else {
                    handleRequest(session)
                }
            } catch (e: Exception) {
                android.util.Log.e("TestServer", "Unhandled error in serve: ${session.uri}", e)
                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "Server Error: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Routes incoming requests to the correct handler function.
     */
    private suspend fun handleRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        android.util.Log.i("TestServer", "Handling: $method $uri")

        return when {
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/" -> serveHtmlPage()
            method == fi.iki.elonen.NanoHTTPD.Method.POST && uri == "/login" -> handleSecureLogin(session)
            method == fi.iki.elonen.NanoHTTPD.Method.POST && uri == "/recognize" -> handleSecureRecognition(session)
            // --- NEW ROUTES ---
            method == fi.iki.elonen.NanoHTTPD.Method.POST && uri == "/set-concurrency" -> handleConcurrencyChange(session)
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/queue-status" -> handleQueueStatus()
            // --- END NEW ---
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/battery" -> handleSecureBatteryRequest(session)
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/status" -> handleBatteryRequest()
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri.startsWith("/result/") -> handleResultRequest(uri)
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/download" -> handleFileDownload(session)
            else -> {
                android.util.Log.w("TestServer", "Unhandled request for URI: $uri")
                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND,
                        "text/plain",
                        "Error: The requested resource was not found."
                    )
                )
            }
        }
    }

    private fun serveHtmlPage(): fi.iki.elonen.NanoHTTPD.Response {
        return try {
            val html = context.assets.open("login.html").use { it.bufferedReader().readText() }
            addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    "text/html",
                    html
                )
            )
        } catch (e: java.io.IOException) {
            android.util.Log.e("TestServer", "Could not serve login.html", e)
            addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Could not load main page."
                )
            )
        }
    }

    private suspend fun handleSecureLogin(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val params = session.parameters
            val username = params["username"]?.firstOrNull()
            val password = params["password"]?.firstOrNull()

            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                        "text/plain",
                        "Username and password are required."
                    )
                )
            } else {
                val user = userDao.findByUsername(username)
                if (user != null && password == user.passwordHash) {
                    val token = generateNewToken()
                    activeTokens.add(token)
                    android.util.Log.i("TestServer", "Login successful for '$username'. Issued token.")
                    val jsonResponse = "{\"token\": \"$token\"}"
                    addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                            "application/json",
                            jsonResponse
                        )
                    )
                } else {
                    android.util.Log.w("TestServer", "Login failed for user '$username'.")
                    addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.UNAUTHORIZED,
                            "text/plain",
                            "Invalid username or password."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TestServer", "Error during login", e)
            addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "An internal error occurred during login."
                )
            )
        }
    }

    /**
     * Endpoint to dynamically set the maximum number of concurrent recognition threads.
     * This is synchronized to prevent race conditions.
     */
    private fun handleConcurrencyChange(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        // Synchronize to prevent race conditions when changing the semaphore.
        synchronized(this) {
            return try {
                val body = hashMapOf<String, String>()
                session.parseBody(body)
                val json = org.json.JSONObject(body["postData"] ?: "{}")
                val newLimit = json.optInt("maxThreads", -1)

                if (newLimit <= 0) {
                    return addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                            "text/plain",
                            "'maxThreads' must be a positive integer."
                        )
                    )
                }

                if (newLimit == maxConcurrentTasks) {
                    return addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                            "text/plain",
                            "Concurrency is already set to $newLimit."
                        )
                    )
                }

                android.util.Log.w("TestServer", "ADMIN: Changing max concurrency from $maxConcurrentTasks to $newLimit")
                maxConcurrentTasks = newLimit

                // The new semaphore will immediately apply to the next task waiting to acquire a permit.
                recognitionSemaphore = java.util.concurrent.Semaphore(newLimit, true)

                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                        "text/plain",
                        "Max concurrency successfully set to $newLimit."
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("TestServer", "Error setting concurrency", e)
                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "Failed to parse request."
                    )
                )
            }
        }
    }


    /**
     * Endpoint to report the current status of the recognition queue.
     */
    private fun handleQueueStatus(): fi.iki.elonen.NanoHTTPD.Response {
        // This is now the source of truth, no calculation needed.
        val statusMap = mapOf(
            "maxConcurrency" to maxConcurrentTasks,
            "activeProcessingTasks" to activeProcessingTasks.get(),
            "queuedWaitingTasks" to queuedWaitingTasks.get(),
        )

        val jsonResponse = gson.toJson(statusMap)
        return addCorsHeaders(
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "application/json",
                jsonResponse
            )
        )
    }



    private suspend fun handleSecureRecognition(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
//        if (!isTokenValid(session)) {
//            return addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized: Missing or invalid token."))
//        }
//        Log.i("TestServer", "Token valid, proceeding to queue recognition task.")
        return handleRecognition(session)
    }

    private suspend fun handleRecognition(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val requestReceivedTime = getDetailedTimestamp()
        val clientId = session.headers["x-client-request-id"] ?: "unknown"
        val taskId = java.util.UUID.randomUUID().toString()

        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val tempImageFilePath = files["imageFile"]
            if (tempImageFilePath.isNullOrEmpty()) {
                return addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                        "text/plain",
                        "No image"
                    )
                )
            }

            val tempFile = java.io.File(tempImageFilePath)
            val permanentImageDir = java.io.File(context.filesDir, "images")
                .apply { java.io.File.mkdirs() }
            val permanentFile = java.io.File(permanentImageDir, "img_${taskId}.jpg")
            tempFile.copyTo(permanentFile, overwrite = true)

            taskResults[taskId] = gson.toJson(mapOf("status" to "queued"))

            // Launch processing
            serverScope.launch(Dispatchers.IO) {
                processRecognitionTask(taskId, clientId, permanentFile, requestReceivedTime)
            }

            val responseSentTime = getDetailedTimestamp()
            val responseJson = gson.toJson(mapOf("taskId" to taskId, "status" to "queued"))

            // Recognition start/end will be updated inside processRecognitionTask
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.ACCEPTED,
                    "application/json",
                    responseJson
                )
            )

        } catch (e: Exception) {
            android.util.Log.e("TestServer", "Error", e)
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    e.message
                )
            )
        }
    }

    private suspend fun processRecognitionTask(
        taskId: String,
        clientId: String,
        imageFile: java.io.File,
        receivedTime: String
    ) {
        queuedWaitingTasks.incrementAndGet()
        recognitionSemaphore.acquire()

        val recognitionStartTime = getDetailedTimestamp()
        try {
            queuedWaitingTasks.decrementAndGet()
            activeProcessingTasks.incrementAndGet()

            // Recognition logic
            val recognizer = ImageRecognizer(context, userDao)
            val result = recognizer.processImage(imageFile)
            val recognitionEndTime = getDetailedTimestamp()

            // Prepare data for polling
            val resultData = mapOf(
                "status" to "complete",
                "result" to result,
                "clientId" to clientId,
                "receivedTime" to receivedTime,
                "startTime" to recognitionStartTime,
                "endTime" to recognitionEndTime
            )
            taskResults[taskId] = gson.toJson(resultData)

            logToCsv(clientId, receivedTime, recognitionStartTime, recognitionEndTime, getDetailedTimestamp())

        } catch (e: Exception) {
            android.util.Log.e("TestServer", "Processing error", e)
            taskResults[taskId] = gson.toJson(mapOf("status" to "error", "message" to e.message))
        } finally {
            activeProcessingTasks.decrementAndGet()
            recognitionSemaphore.release()

            // --- DELETE THE IMAGE TO AVOID STORAGE OVERLOAD ---
            if (imageFile.exists()) {
                imageFile.delete()
            }
        }
    }


    private fun handleFileDownload(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        val params = session.parameters
        val filename = params["file"]?.firstOrNull()

        if (filename.isNullOrEmpty()) {
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain",
                    "Error: 'file' parameter is missing."
                )
            )
        }

        try {
            // Prevent directory traversal
            if (filename.contains("/") || filename.contains("\\")) {
                android.util.Log.e("TestServer", "Security Alert: Path characters detected in filename: $filename")
                return addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                        "text/plain",
                        "Error: Invalid filename."
                    )
                )
            }

            // Open an input stream from the assets folder.
            // This will throw an IOException if the file does not exist.
            val fileInputStream = context.assets.open(filename)
            // Use .available() to get the size of the stream, which works for compressed assets.
            val fileSize = fileInputStream.available().toLong()

            android.util.Log.i("TestServer", "Serving asset file for download: $filename")

            // Serve the file. NanoHTTPD will handle closing the stream.
            // "application/octet-stream" forces a download prompt.
            val response = fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "application/octet-stream",
                fileInputStream,
                fileSize
            )

            // This header suggests a filename to the browser for the "Save As" dialog.
            response.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")

            return addCorsHeaders(response)

        } catch (e: java.io.IOException) {
            // This catch block will handle the case where the asset does not exist.
            android.util.Log.w("TestServer", "Asset file not found for download: $filename", e)
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain",
                    "Error: Asset file not found."
                )
            )
        }
    }

    private fun handleSecureBatteryRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
//        if (!isTokenValid(session)) {
//            return addCorsHeaders(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized: Missing or invalid token."))
//        }
        return handleBatteryRequest()
    }

    private fun handleBatteryRequest(): fi.iki.elonen.NanoHTTPD.Response {
        val currentTime = java.lang.System.currentTimeMillis()

        // Check if we have a valid, non-stale cache
        if (cachedBatteryJson != null && (currentTime - lastBatteryFetchTime) < batteryCacheDurationMs) {
            android.util.Log.i("TestServer", "Serving cached battery status.")
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                    "application/json",
                    cachedBatteryJson
                )
            )
        }

        // --- If cache is stale or empty, fetch new data ---
        android.util.Log.i("TestServer", "Fetching new battery status (cache miss or stale).")
        val intentFilter =
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, intentFilter)

        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level / scale.toFloat()) * 100 else -1.0f

        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL

        val chargingStatus = when {
            isCharging -> "Charging"
            status == android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            else -> "Not Charging"
        }

        val batteryData = mapOf("level" to "%.1f%%".format(batteryPct), "status" to chargingStatus)
        val jsonResponse = gson.toJson(batteryData)

        // --- Update the cache ---
        cachedBatteryJson = jsonResponse
        lastBatteryFetchTime = currentTime

        return addCorsHeaders(
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "application/json",
                jsonResponse
            )
        )
    }

    /**
     * Handles polling requests for the result of a specific task.
     * It extracts the taskId from the URL path.
     */
    private fun handleResultRequest(uri: String): fi.iki.elonen.NanoHTTPD.Response {
        val taskId = uri.substringAfterLast('/')
        if (taskId.isBlank()) {
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain",
                    "Task ID missing"
                )
            )
        }

        val resultJson = taskResults[taskId]
        if (resultJson == null) {
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND,
                    "text/plain",
                    "Task not found"
                )
            )
        }

        // Capture the EXACT moment we are about to send the result back to the client
        val actualResponseSentTime = getDetailedTimestamp()

        val type = object : TypeToken<Map<String, Any>>() {}.type
        val data: Map<String, Any> = gson.fromJson(resultJson, type)

        // Only log to CSV when the status is "complete" (the final response)
        if (data["status"] == "complete") {
            val clientId = data["clientId"]?.toString() ?: "unknown"
            val received = data["receivedTime"]?.toString() ?: "N/A"
            val start = data["startTime"]?.toString() ?: "N/A"
            val end = data["endTime"]?.toString() ?: "N/A"

            // Write to CSV using the polling time as the final Response_Sent
            logToCsv(clientId, received, start, end, actualResponseSentTime)

            // Optional: Remove from map after successful delivery to save memory
            taskResults.remove(taskId)
        }

        return addCorsHeaders(
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "application/json",
                resultJson
            )
        )
    }



    private fun generateNewToken(): String {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun isTokenValid(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): Boolean {
        val authHeader = session.headers["authorization"] ?: return false
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) return false
        val token = authHeader.substringAfter("Bearer ")
        return activeTokens.contains(token)
    }

    private fun addCorsHeaders(response: fi.iki.elonen.NanoHTTPD.Response): fi.iki.elonen.NanoHTTPD.Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "origin, x-requested-with, content-type, accept, Authorization")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return response
    }
}
