package com.example.llama

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        private const val DIRECTORY_MODELS = "models"
        const val EXTRA_CHAT_ID = "chat_id"
        private val GENERIC_FOLLOW_UPS = setOf(
            "tell me more",
            "more",
            "continue",
            "what else",
            "anything else",
            "something else"
        )

        // Track which chat ID the model is currently loaded for
        // This helps us know when we need to switch context
        var currentChatIdForModel: String? = null
    }

    // Android views
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var loadingSpinner: android.widget.ProgressBar

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Chat management
    private lateinit var chatStorageManager: ChatStorageManager
    private lateinit var currentChatSession: ChatSession
    private var chatId: String? = null

    // Conversation states
    private var isModelReady = false
    private var isActivityActive = true
    private var canGoBack = false  // Don't allow back until model is ready
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Get chat ID from intent
        chatId = intent.getStringExtra(EXTRA_CHAT_ID)
        if (chatId == null) {
            Log.e(TAG, "No chat ID provided")
            finish()
            return
        }

        // Initialize storage manager
        chatStorageManager = ChatStorageManager(this)

        // Load chat session
        loadChatSession()

        // View model boilerplate and state management is out of this basic sample's scope
        // Don't allow back navigation until model is ready
        onBackPressedDispatcher.addCallback(this) {
            if (canGoBack) {
                saveCurrentChat()
                finish()
            }
            // If model not ready, ignore back press
        }

        // Find views
        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Initializing James..."

        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        loadingSpinner = findViewById(R.id.loading_spinner)

        // Handle insets for all views - system bars and keyboard
        val rootView = findViewById<View>(R.id.main)
        val inputContainer = findViewById<LinearLayout>(R.id.input_container)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply system bars padding to root container (including toolbar)
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                0
            )

            // Apply IME padding to input container to push it above keyboard
            inputContainer.setPadding(
                inputContainer.paddingLeft,
                inputContainer.paddingTop,
                inputContainer.paddingRight,
                maxOf(systemBars.bottom, ime.bottom)
            )

            insets
        }

        // Initialize UI state
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false

        // Load existing messages (only if chat session was loaded successfully)
        if (::currentChatSession.isInitialized) {
            loadChatMessages()
        }

        // Arm AI Chat initialization and model loading
        initializeAndLoadModel()

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            }
        }
    }

    /**
     * Initialize the AI engine and load the model
     * This is called every time the activity is created to ensure the model is ready
     */
    private fun initializeAndLoadModel() {
        Log.d(TAG, "Initializing AI engine and loading model...")
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Get the inference engine
                engine = AiChat.getInferenceEngine(applicationContext)
                Log.d(TAG, "Got inference engine")

                // Check current state - if model is already loaded, we might need to reload for new chat context
                val currentState = engine.state.value
                Log.d(TAG, "Current engine state: $currentState")

                // Load the bundled model (will handle unloading if needed)
                loadBundledModel()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine", e)
                withContext(Dispatchers.Main) {
                    supportActionBar?.title = "Failed to initialize: ${e.message}"
                }
            }
        }
    }

    private fun loadChatSession() {
        chatId?.let { id ->
            chatStorageManager.getChatSession(id)?.let { session ->
                currentChatSession = session
                title = session.title
            } ?: run {
                Log.e(TAG, "Chat session not found: $id")
                finish()
            }
        }
    }

    private fun loadChatMessages() {
        messages.clear()
        messages.addAll(currentChatSession.messages)
        messageAdapter.notifyDataSetChanged()

        // Scroll to bottom
        if (messages.isNotEmpty()) {
            messagesRv.post {
                messagesRv.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun saveCurrentChat() {
        currentChatSession = currentChatSession.copy(
            messages = messages.toList(),
            lastMessageAt = System.currentTimeMillis()
        )
        chatStorageManager.updateChatSession(currentChatSession)
    }

    /**
     * Load the bundled Llama model from assets
     */
    private suspend fun loadBundledModel() {
        withContext(Dispatchers.Main) {
            supportActionBar?.title = "Loading James..."
            supportActionBar?.title = "Loading James..."
            loadingSpinner.visibility = View.VISIBLE
        }

        try {
            // Copy model from assets to internal storage
            val modelFile = ensureBundledModelFile()

            // Load the model
            loadModel("llama.gguf", modelFile)

            withContext(Dispatchers.Main) {
                isModelReady = true
                canGoBack = true  // Allow back navigation now that model is ready
                userInputEt.hint = "Type and send a message!"
                userInputEt.isEnabled = true
                userActionFab.setImageResource(R.drawable.outline_send_24)
                userActionFab.isEnabled = true
                supportActionBar?.title = "James is ready for chat"
                loadingSpinner.visibility = View.GONE
                Log.d(TAG, "Model ready for chat")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled model", e)
            withContext(Dispatchers.Main) {
                canGoBack = true  // Allow back even on failure so user can leave
                val err = "Failed to load model: ${e.message}"
                supportActionBar?.title = err
                supportActionBar?.title = err
                loadingSpinner.visibility = View.GONE
            }
        }
    }

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
                supportActionBar?.title = "Copying model to device storage..."
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
     * Load the model file from the app private storage
     * Note: Each chat gets a completely fresh model context
     * The model is unloaded when the activity is destroyed to prevent context bleeding
     */
    private suspend fun loadModel(modelName: String, modelFile: File) {
        // Check current state
        val currentState = withContext(Dispatchers.Main) {
            engine.state.value
        }
        Log.i(TAG, "Current engine state: $currentState")

        // If model is loaded for a different chat, unload it first
        if (currentState is com.arm.aichat.InferenceEngine.State.ModelReady && currentChatIdForModel != chatId) {
            Log.i(TAG, "Model loaded for different chat (${currentChatIdForModel}), unloading before loading for new chat ($chatId)")
            try {
                withContext(Dispatchers.Main) {
                    engine.cleanUp()
                }
                // Wait a moment for cleanup
                kotlinx.coroutines.delay(500)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unload previous model", e)
            }
        }

        // Check if model is already loaded for THIS specific chat
        val isModelLoadedForThisChat = currentState is com.arm.aichat.InferenceEngine.State.ModelReady && currentChatIdForModel == chatId

        if (isModelLoadedForThisChat) {
            // Model already loaded for this chat - but we should NOT reuse it
            // because we want a fresh context. Unload and reload.
            Log.i(TAG, "Model already loaded for this chat, but reloading for fresh context")
            try {
                withContext(Dispatchers.Main) {
                    engine.cleanUp()
                }
                // Wait for cleanup
                kotlinx.coroutines.delay(500)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unload model for reload", e)
            }
        }

        // Now load the model fresh for this chat
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName from path: ${modelFile.absolutePath}")
            Log.i(TAG, "Model file exists: ${modelFile.exists()}")
            Log.i(TAG, "Model file size: ${modelFile.length()} bytes")
        }

        withContext(Dispatchers.Main) {
            userInputEt.hint = "Initializing James..."
        }

        try {
            // Load the model on main thread (as per API requirements)
            withContext(Dispatchers.Main) {
                engine.loadModel(modelFile.absolutePath)
            }

            Log.i(TAG, "Model loaded successfully for chat: $chatId")

            // Update the tracking variable to mark this chat as the one the model is loaded for
            currentChatIdForModel = chatId

            // Build system prompt with conversation history from THIS chat (if available)
            val systemPrompt = buildSystemPromptForChat()
            Log.i(TAG, "Setting system prompt with chat history if available")

            withContext(Dispatchers.Main) {
                engine.setSystemPrompt(systemPrompt)
            }
            Log.i(TAG, "System prompt set successfully")

            withContext(Dispatchers.Main) {
                isModelReady = true
                canGoBack = true  // Allow back navigation
                userInputEt.hint = "Type and send a message!"
                userInputEt.isEnabled = true
                userActionFab.setImageResource(R.drawable.outline_send_24)
                userActionFab.isEnabled = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model or set system prompt", e)
            withContext(Dispatchers.Main) {
                supportActionBar?.title = "Failed to load model: ${e.message}"            }
            throw e
        }
    }

    /**
     * Build system prompt for the chat with conversation history
     * When reopening a chat with existing messages, include a bounded, recent memory block
     * so history is less likely to be crowded out by long instructions.
     */
    private fun buildSystemPromptForChat(): String {
        val basePrompt = """
You are James, a helpful and honest AI assistant running locally on a mobile device.
Reply in concise, clear English.
Never invent facts; if unsure, say you are unsure.
Do not generate explicit, hateful, or harassing content.
Answer the user's request directly.
If chat memory is provided below, treat it as prior conversation context.
Do not claim this is the first conversation when memory exists.
If asked what we were talking about, summarize the main topic (not just the last short message).
        """.trimIndent()

        if (!::currentChatSession.isInitialized || currentChatSession.messages.isEmpty()) {
            Log.d(TAG, "No previous messages in chat, using base system prompt")
            return basePrompt
        }

        // Keep memory bounded so the model reliably receives recent turns.
        val maxMemoryChars = 1800
        val maxTurns = 12
        val normalized = currentChatSession.messages.asReversed().mapNotNull { msg ->
            val text = msg.content.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            val clipped = if (text.length > 220) "${text.take(217)}..." else text
            val role = if (msg.isUser) "U" else "A"
            "$role: $clipped"
        }

        val selected = mutableListOf<String>()
        var used = 0
        for (line in normalized) {
            if (selected.size >= maxTurns) break
            val cost = line.length + 1
            if (used + cost > maxMemoryChars) break
            selected.add(line)
            used += cost
        }
        selected.reverse()

        if (selected.isEmpty()) return basePrompt

        val genericFollowUps = setOf(
            "something else",
            "tell me more",
            "more",
            "what else",
            "anything else",
            "continue"
        )
        val topicHint = currentChatSession.messages
            .asReversed()
            .filter { it.isUser }
            .map { it.content.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { text ->
                val norm = text.lowercase()
                text.length >= 8 && norm !in genericFollowUps
            }
            ?.take(180)
            ?: ""

        val latestUser = currentChatSession.messages
            .lastOrNull { it.isUser }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
            ?: ""

        val promptWithMemory = buildString {
            append(basePrompt)
            append("\n\nConversation memory (chronological order):\n")
            append("<memory>\n")
            selected.forEach { append(it).append('\n') }
            append("</memory>\n")
            if (topicHint.isNotBlank()) {
                append("Topic hint from prior user messages: ").append(topicHint).append('\n')
            }
            if (latestUser.isNotBlank()) {
                append("Latest user message: ").append(latestUser).append('\n')
            }
            append("When asked what we last discussed, summarize the topic from <memory> in one sentence, then continue helpfully. DON'T repeat anything verbatim, but expand on the topic from <memory>.\n\n")
            append("Do not output role prefixes like 'U:' or 'A:'.")
        }

        Log.d(TAG, "Built system prompt with ${selected.size} memory lines and ${promptWithMemory.length} chars")
        return promptWithMemory
    }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        // Check if activity is still active
        if (!isActivityActive || isFinishing || isDestroyed) {
            Log.w(TAG, "Activity not active, ignoring input")
            return
        }

        // First check if model is ready
        if (!isModelReady) {
            Toast.makeText(this, "Model not ready, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Create user message
                val userMessage = Message(
                    id = UUID.randomUUID().toString(),
                    content = userMsg,
                    isUser = true,
                    timestamp = System.currentTimeMillis()
                )

                // Add to messages and save
                messages.add(userMessage)
                messageAdapter.notifyItemInserted(messages.size - 1)
                messagesRv.scrollToPosition(messages.size - 1)

                // Save to storage
                chatStorageManager.addMessageToChat(currentChatSession.id, userMessage)

                // Create placeholder for assistant response
                val assistantMessage = Message(
                    id = UUID.randomUUID().toString(),
                    content = "",
                    isUser = false,
                    timestamp = System.currentTimeMillis()
                )
                messages.add(assistantMessage)
                messageAdapter.notifyItemInserted(messages.size - 1)
                messagesRv.scrollToPosition(messages.size - 1)

                lastAssistantMsg.clear()

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        // Check engine state before sending
                        val state = engine.state.value
                        Log.d(TAG, "Engine state before sending: $state")

                        if (state !is com.arm.aichat.InferenceEngine.State.ModelReady) {
                            Log.w(TAG, "Engine not ready, state: $state")
                            withContext(Dispatchers.Main) {
                                if (isActivityActive && !isFinishing && !isDestroyed) {
                                    Toast.makeText(this@ChatActivity, "Model not ready yet, please try again", Toast.LENGTH_SHORT).show()
                                    userInputEt.isEnabled = true
                                    userActionFab.isEnabled = true
                                }
                            }
                            return@launch
                        }

                        val modelPrompt = buildUserPromptForTurn(userMsg)
                        engine.sendUserPrompt(modelPrompt)
                            .onCompletion {
                                withContext(Dispatchers.Main) {
                                    if (isActivityActive && !isFinishing && !isDestroyed) {
                                        userInputEt.isEnabled = true
                                        userActionFab.isEnabled = true

                                        // Save the completed assistant message
                                        val finalAssistantMessage = assistantMessage.copy(
                                            content = lastAssistantMsg.toString()
                                        )
                                        messages[messages.size - 1] = finalAssistantMessage
                                        messageAdapter.notifyItemChanged(messages.size - 1)

                                        // Save to storage
                                        chatStorageManager.updateChatSession(
                                            currentChatSession.copy(
                                                messages = messages,
                                                lastMessageAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            }.collect { token ->
                                withContext(Dispatchers.Main) {
                                    if (isActivityActive && !isFinishing && !isDestroyed) {
                                        val currentContent = lastAssistantMsg.append(token).toString()
                                        messages[messages.size - 1] = assistantMessage.copy(content = currentContent)
                                        messageAdapter.notifyItemChanged(messages.size - 1)
                                        messagesRv.scrollToPosition(messages.size - 1)
                                    }
                                }
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during chat generation", e)
                        withContext(Dispatchers.Main) {
                            if (isActivityActive && !isFinishing && !isDestroyed) {
                                Toast.makeText(this@ChatActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
                            }
                        }
                    }
                }
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

    private fun buildUserPromptForTurn(rawUserMessage: String): String {
        val normalized = rawUserMessage.replace(Regex("""\s+"""), " ").trim()
        if (normalized.isEmpty() || !isGenericFollowUp(normalized)) {
            return rawUserMessage
        }

        val priorUserTopic = messages
            .asReversed()
            .drop(1) // skip just-added follow-up message
            .filter { it.isUser }
            .map { it.content.replace(Regex("""\s+"""), " ").trim() }
            .firstOrNull { candidate -> candidate.length >= 8 && !isGenericFollowUp(candidate) }
            ?.take(220)
            .orEmpty()

        val priorAssistantSummary = messages
            .asReversed()
            .drop(1) // skip placeholder assistant response
            .firstOrNull { !it.isUser && it.content.isNotBlank() }
            ?.content
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(360)
            .orEmpty()

        return buildString {
            append(rawUserMessage)
            append("\n\n")
            append("Continue the same topic with NEW information only. ")
            append("Add deeper detail, useful context, and one concrete example. ")
            append("Do not repeat or paraphrase the same lines.")
            if (priorUserTopic.isNotBlank()) {
                append("\nTopic: ").append(priorUserTopic)
            }
            if (priorAssistantSummary.isNotBlank()) {
                append("\nAlready covered (avoid repeating): ").append(priorAssistantSummary)
            }
        }
    }

    private fun isGenericFollowUp(input: String): Boolean {
        val normalized = input.replace(Regex("""\s+"""), " ").trim().lowercase(Locale.US)
        return normalized in GENERIC_FOLLOW_UPS
    }

    override fun onResume() {
        super.onResume()
        isActivityActive = true
    }

    override fun onPause() {
        super.onPause()
        isActivityActive = false
    }

    override fun onStop() {
        // Cancel any ongoing generation
        generationJob?.cancel()
        generationJob = null
        isActivityActive = false
        saveCurrentChat()
        super.onStop()
    }

    override fun onDestroy() {
        // Cancel any ongoing generation
        generationJob?.cancel()
        generationJob = null
        isActivityActive = false
        saveCurrentChat()

        // Clean up the model when leaving this chat to prevent context bleeding
        // This ensures the model is unloaded so the next chat gets a fresh context
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "Unloading model for chat isolation")
                engine.cleanUp()
                currentChatIdForModel = null  // Reset the tracking variable
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unload model", e)
            }
        }

        super.onDestroy()
    }
}
