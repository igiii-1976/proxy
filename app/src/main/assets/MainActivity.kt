import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.room.Room
import com.example.android_helloworld.db.AppDatabase
import com.example.android_helloworld.db.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var server: testServer? = null

    // Lazily initialize the database instance.
    private val db by lazy {
        Room.databaseBuilder(
            android.content.ContextWrapper.getApplicationContext,
            AppDatabase::class.java,
            "hello-server-db"
        )
            // This will delete and recreate the database on schema changes.
            // It's simple for development but not for production apps with real user data.
            .fallbackToDestructiveMigration()
            .build()
    }

    // Initialize the ViewModel using the factory that provides the Dao.
    private val viewModel: RecognitionViewModel by viewModels {
        RecognitionViewModelFactory(db.userDao())
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the server in a background coroutine.
        startServer()

        // Set the content of the activity to be our new history screen.
        setContent {
            // The RecognitionHistoryScreen composable will now be the main UI.
            // It observes the ViewModel for data changes.
            RecognitionHistoryScreen(viewModel = viewModel)
        }
    }

    private fun startServer() {
        // Use a dedicated CoroutineScope for the server's lifecycle.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ensure the directory for storing uploaded images exists.
                val imageDir = java.io.File(android.content.ContextWrapper.getFilesDir, "images")
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }

                // Get a reference to the DAO.
                val userDao = db.userDao()

                // Insert the sample user for testing if it doesn't already exist.
                if (userDao.findByUsername("testuser") == null) {
                    userDao.insert(User(username = "testuser", passwordHash = "password123"))
                    android.util.Log.i("MainActivity", "Sample user 'testuser' inserted into database.")
                }

                // Initialize and start the NanoHTTPD server.
                server = testServer(android.content.ContextWrapper.getApplicationContext, userDao, 8080)
                server?.start()
                android.util.Log.i("MainActivity", "Server started successfully on port 8080.")

            } catch (e: java.io.IOException) {
                android.util.Log.e("MainActivity", "Server failed to start.", e)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "An unexpected error occurred during server startup.", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the server when the activity is destroyed to free up the port.
        CoroutineScope(Dispatchers.IO).launch {
            server?.stop()
            android.util.Log.i("MainActivity", "Server stopped.")
        }
    }
}
