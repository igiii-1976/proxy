import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.util.Base64
import android.util.Log
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import com.google.mlkit.vision.digitalink.common.RecognitionResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

// Data class for sending predictions back as JSON
data class Prediction(val label: String, val score: Float)

class testServer(
    private val context: android.content.Context,
    private val userDao: UserDao,
    port: Int
) : fi.iki.elonen.NanoHTTPD(port) {

    private val activeTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()

    companion object {
        @Volatile
        private var objectDetector: ObjectDetector? = null

        // Singleton pattern to ensure the expensive model is loaded only once.
        fun getDetector(applicationContext: android.content.Context): ObjectDetector {
            return objectDetector ?: synchronized(this) {
                objectDetector ?: run {
                    android.util.Log.i("TestServer", "Initializing ObjectDetector singleton...")
                    val options = ObjectDetector.ObjectDetectorOptions.builder()
                        .setMaxResults(5)
                        .setScoreThreshold(0.5f)
                        .build()
                    ObjectDetector.createFromFileAndOptions(
                        applicationContext,
                        "model_detection.tflite", // Ensure this model is in app/src/main/assets
                        options
                    ).also {
                        objectDetector = it
                        android.util.Log.i("TestServer", "ObjectDetector initialized successfully.")
                    }
                }
            }
        }
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
            // Serves the main HTML page
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/" -> serveHtmlPage(session)

            // Handles secure user login and token generation
            method == fi.iki.elonen.NanoHTTPD.Method.POST && uri == "/login" -> handleSecureLogin(session)

            // Handles secure image upload and recognition
            method == fi.iki.elonen.NanoHTTPD.Method.POST && uri == "/recognize" -> handleSecureRecognition(session)

            // Handles secure battery status requests
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/battery" -> handleSecureBatteryRequest(session)

            // Changes for cycle 5: Reverse proxy server
            // Handles status request by proxy (bypasses authentication - need security changes in the future)
            method == fi.iki.elonen.NanoHTTPD.Method.GET && uri == "/status" -> handleBatteryRequest()


            // Catches any other unhandled requests
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

    /**
     * Serves the login.html file statically. No IP injection is needed.
     */
    private fun serveHtmlPage(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        return try {
            val html = context.assets.open("login.html").use {
                it.bufferedReader().readText()
            }
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

    /**
     * Handles user login, validates credentials, and returns an auth token.
     */
    private suspend fun handleSecureLogin(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        return withContext(Dispatchers.IO) {
            try {
                // Add session.parseBody() to read POST data ---
                val files = mutableMapOf<String, String>()
                session.parseBody(files)

                // Now, session.parameters will be correctly populated
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
    }

    /**
     * A security gateway for the image recognition endpoint.
     */
    private suspend fun handleSecureRecognition(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        if (!isTokenValid(session)) {
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.UNAUTHORIZED,
                    "text/plain",
                    "Unauthorized: Missing or invalid token."
                )
            )
        }
        android.util.Log.i("TestServer", "Token valid, proceeding with recognition.")
        return handleRecognition(session)
    }

    /**
     * Handles the actual image processing after security checks have passed.
     */
    private suspend fun handleRecognition(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        return withContext(Dispatchers.IO) {
            try {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)

                val tempImageFilePath = files["imageFile"] // This is the temporary path
                if (tempImageFilePath.isNullOrEmpty()) {
                    return@withContext addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST,
                            "text/plain",
                            "No image file was uploaded."
                        )
                    )
                }

                // COPY THE TEMPORARY FILE TO A PERMANENT LOCATION
                val tempFile = java.io.File(tempImageFilePath)

                // Create a permanent directory in your app's internal storage
                val permanentImageDir = java.io.File(context.filesDir, "images")
                if (!permanentImageDir.exists()) {
                    permanentImageDir.mkdirs()
                }

                // Create a unique file name for the permanent copy
                val permanentFile = java.io.File(
                    permanentImageDir,
                    "img_${java.lang.System.currentTimeMillis()}.jpg"
                )

                // Copy the contents of the temp file to the permanent file
                tempFile.copyTo(permanentFile, overwrite = true)

                // From now on, use the permanent path for everything
                val permanentImagePath = permanentFile.absolutePath
                android.util.Log.i("TestServer", "Copied uploaded image to permanent path: $permanentImagePath")

                // Now, decode the bitmap from the PERMANENT path
                val bitmap = android.graphics.BitmapFactory.decodeFile(permanentImagePath)
                if (bitmap == null) {
                    return@withContext addCorsHeaders(
                        fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                            fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            "text/plain",
                            "Failed to decode the copied image."
                        )
                    )
                }

                val tensorImage = TensorImage.fromBitmap(bitmap)
                val results: List<Detection> = getDetector(context).detect(tensorImage)

                val predictions = results.flatMap { detection ->
                    detection.categories.map { category ->
                        Prediction(category.label, category.score)
                    }
                }
                val jsonResponse = gson.toJson(predictions)
                android.util.Log.i("TestServer", "Detection complete. Found: ${predictions.joinToString { it.label }}")

                val recognizedObjectsStr = predictions.joinToString(", ") { it.label }
                if (recognizedObjectsStr.isNotEmpty()) {
                    val recognitionResult =
                        com.example.android_helloworld.db.RecognitionResult( // Explicitly using your new class
                            timestamp = java.lang.System.currentTimeMillis(),
                            imagePath = permanentImagePath,
                            recognizedObjects = recognizedObjectsStr
                        )
                    userDao.insertRecognitionResult(recognitionResult)
                    android.util.Log.i("TestServer", "Recognition result saved to database.")
                }

                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                        "application/json",
                        jsonResponse
                    )
                )

            } catch (e: Exception) {
                android.util.Log.e("TestServer", "Error during image recognition", e)
                addCorsHeaders(
                    fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                        fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "Error processing image: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * A security gateway for the battery status endpoint.
     */
    private fun handleSecureBatteryRequest(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response {
        if (!isTokenValid(session)) {
            return addCorsHeaders(
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                    fi.iki.elonen.NanoHTTPD.Response.Status.UNAUTHORIZED,
                    "text/plain",
                    "Unauthorized: Missing or invalid token."
                )
            )
        }
        return handleBatteryRequest()
    }

    /**
     * Retrieves the device's battery level and charging status.
     */
    private fun handleBatteryRequest(): fi.iki.elonen.NanoHTTPD.Response {
        val intentFilter =
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, intentFilter)

        // Get battery level
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level / scale.toFloat()) * 100 else -1.0f

        // Get battery status
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL

        val chargingStatus = when {
            isCharging -> "Charging"
            status == android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            else -> "Not Charging"
        }

        // Create a data class or map for a cleaner JSON structure
        val batteryData = mapOf(
            "level" to "%.1f%%".format(batteryPct),
            "status" to chargingStatus
        )

        val jsonResponse = gson.toJson(batteryData)
        return addCorsHeaders(
            fi.iki.elonen.NanoHTTPD.newFixedLengthResponse(
                fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "application/json",
                jsonResponse
            )
        )
    }

    // --- SECURITY HELPER FUNCTIONS ---

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
