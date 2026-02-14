import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.example.android_helloworld.db.UserDao
import com.google.gson.Gson
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.IOException
import java.io.Closeable

/**
 * Encapsulates the logic for image recognition. It loads the model and provides
 * a method to perform detection on an image file. This class is NOT thread-safe
 * on its own and should be used by a queuing mechanism like RecognitionTaskQueue.
 */
class ImageRecognizer(context: android.content.Context, private val userDao: UserDao):
    java.io.Closeable {

    private val gson = Gson()
    private val objectDetector: ObjectDetector

    init {
        android.util.Log.i("ImageRecognizer", "Initializing ObjectDetector...")
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()
        objectDetector = ObjectDetector.createFromFileAndOptions(
            context,
            "model_detection.tflite", // Ensure this model is in app/src/main/assets
            options
        )
        android.util.Log.i("ImageRecognizer", "ObjectDetector initialized successfully.")

        }
    override fun close() {
        objectDetector?.close()
        android.util.Log.i("ImageRecognizer", "ObjectDetector has been closed.")
    }

    /**
     * Processes a single image file, runs detection, saves the result, and returns a JSON string.
     * This method is NOT thread-safe and should only be called from a single thread at a time.
     */
    suspend fun processImage(
        permanentImageFile: java.io.File,
    ): String {
        val bitmap = android.graphics.BitmapFactory.decodeFile(permanentImageFile.absolutePath)
        if (bitmap == null) {
            throw java.io.IOException("Failed to decode the image file.")
        }

        val tensorImage = TensorImage.fromBitmap(bitmap)
        val results: List<Detection> = objectDetector.detect(tensorImage)

        val predictions = results.flatMap { detection ->
            detection.categories.map { category ->
                Prediction(category.label, category.score)
            }
        }
        val jsonResponse = gson.toJson(predictions)
        android.util.Log.i("ImageRecognizer", "Detection complete. Found: ${predictions.joinToString { it.label }}")

        val recognizedObjectsStr = predictions.joinToString(", ") { it.label }
        if (recognizedObjectsStr.isNotEmpty()) {
            val recognitionResult =
                com.example.android_helloworld.db.RecognitionResult(
                    timestamp = java.lang.System.currentTimeMillis(),
                    imagePath = permanentImageFile.absolutePath,
                    recognizedObjects = recognizedObjectsStr
                )
            userDao.insertRecognitionResult(recognitionResult)
            android.util.Log.i("ImageRecognizer", "Recognition result saved to database.")
        }

        return jsonResponse
    }
}
