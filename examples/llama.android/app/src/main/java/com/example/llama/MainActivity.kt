package com.example.llama

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // View model boilerplate and state management is out of this basic sample's scope
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)

        // Initialize UI state
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        userInputEt.hint = "Initializing Habibi..."

        // Arm AI Chat initialization and model loading
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)

            // Load the bundled model first
            loadBundledModel()
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            }
        }
    }

    /**
     * Load the bundled Llama model from assets
     */
    private suspend fun loadBundledModel() {
        withContext(Dispatchers.Main) {
            userInputEt.hint = "Loading Habibi model..."
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Copy model from assets to internal storage
                val modelFile = ensureBundledModelFile()

                // Load the model
                loadModel("llama.gguf", modelFile)

                withContext(Dispatchers.Main) {
                    isModelReady = true
                    userInputEt.hint = "Type and send a message!"
                    userInputEt.isEnabled = true
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bundled model", e)
                withContext(Dispatchers.Main) {
                    userInputEt.hint = "Failed to load model"
                }
            }
        }
    }

    /**
     * Copy the bundled model from assets to internal storage
     */
//    private suspend fun ensureBundledModelFile() =
//        withContext(Dispatchers.IO) {
//            val modelName = "llama.gguf"
//            File(ensureModelsDirectory(), modelName).also { file ->
//                // Copy the file from assets if not yet done
//                if (!file.exists()) {
//                    Log.i(TAG, "Copying bundled model to internal storage")
//                    withContext(Dispatchers.Main) {
//                        ggufTv.text = "Copying model to device storage..."
//                    }
//
//                    assets.open(modelName).use { input ->
//                        FileOutputStream(file).use { output ->
//                            input.copyTo(output)
//                        }
//                    }
//                    Log.i(TAG, "Finished copying bundled model")
//                } else {
//                    Log.i(TAG, "Bundled model already exists in storage")
//                }
//            }
//        }

    private suspend fun ensureBundledModelFile() =
        withContext(Dispatchers.IO) {

            val modelName = "llama.gguf"
            val targetFile = File(ensureModelsDirectory(), modelName)

            if (targetFile.exists() && targetFile.length() > 1000000) { // Check if file exists and is reasonably sized (>1MB)
                Log.i(TAG, "Model already exists at ${targetFile.absolutePath}, size: ${targetFile.length()}")
                return@withContext targetFile
            }

            Log.i(TAG, "Model file missing or too small, copying from assets...")

            // Get available internal storage
            val freeBytes = filesDir.usableSpace
            val safetyBuffer = 50L * 1024 * 1024

            if (freeBytes < 900000000 + safetyBuffer) { // ~900MB for model + buffer
                throw Exception("Not enough storage to copy model. Need ~950MB free space")
            }

            Log.i(TAG, "Storage OK (${freeBytes / 1024 / 1024}MB free), copying model...")

            withContext(Dispatchers.Main) {
                userInputEt.hint = "Copying model to device storage..."
            }

            try {
                // Delete any existing incomplete file
                if (targetFile.exists()) {
                    targetFile.delete()
                    Log.i(TAG, "Deleted incomplete model file")
                }

                // Ensure parent directory exists
                targetFile.parentFile?.mkdirs()

                // Copy model from assets
                assets.open(modelName).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            // Log progress every 50MB
                            if (totalBytes % (50 * 1024 * 1024) == 0L) {
                                Log.i(TAG, "Copied ${totalBytes / 1024 / 1024}MB so far...")
                            }
                        }

                        Log.i(TAG, "Model copy complete, total size: ${totalBytes / 1024 / 1024}MB")
                    }
                }

                // Verify the copied file
                if (!targetFile.exists()) {
                    throw Exception("Model file was not created")
                }

                if (targetFile.length() < 1000000) {
                    throw Exception("Model file is too small: ${targetFile.length()} bytes")
                }

                Log.i(TAG, "Model successfully copied to ${targetFile.absolutePath}")
                targetFile

            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model", e)
                // Clean up on failure
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                throw Exception("Model copy failed: ${e.message}")
            }
        }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copying file..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName from path: ${modelFile.absolutePath}")
            Log.i(TAG, "Model file exists: ${modelFile.exists()}")
            Log.i(TAG, "Model file size: ${modelFile.length()} bytes")

            withContext(Dispatchers.Main) {
                userInputEt.hint = "Loading model..."
            }

            try {
                engine.loadModel(modelFile.absolutePath)
                Log.i(TAG, "Model loaded successfully")

                // Set system prompt immediately after model loading
                val systemPrompt = "You are Habibi, a helpful and honest AI assistant running on a mobile device. Provide clear, concise, and accurate responses."
                Log.i(TAG, "Setting system prompt after model load")
                engine.setSystemPrompt(systemPrompt)
                Log.i(TAG, "System prompt set successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model or set system prompt", e)
                throw e
            }
        }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true, System.currentTimeMillis()))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false, System.currentTimeMillis()))

                generationJob = lifecycleScope.launch(Dispatchers.Default) {

                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }.collect { token ->
                            withContext(Dispatchers.Main) {
                                val messageCount = messages.size
                                check(messageCount > 0 && !messages[messageCount - 1].isUser)

                                messages.removeAt(messageCount - 1).copy(
                                    content = lastAssistantMsg.append(token).toString()
                                ).let { messages.add(it) }

                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                        }
                }
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false, System.currentTimeMillis()))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}



