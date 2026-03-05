package com.example.llama

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.flow.collect
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ChatListActivity : AppCompatActivity() {

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var addChatFab: FloatingActionButton
    private lateinit var chatStorageManager: ChatStorageManager
    private lateinit var chatAdapter: ChatListAdapter
    private lateinit var engine: InferenceEngine

    private var chatList = mutableListOf<ChatSession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_chat_list)

        // Setup toolbar as action bar to host menu
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Handle system insets for the root layout to keep content within safe zone
        val rootLayout = findViewById<ConstraintLayout>(R.id.chat_list_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                0
            )
            insets
        }

        // Initialize storage manager
        chatStorageManager = ChatStorageManager(this)

        // Initialize storage manager
        chatStorageManager = ChatStorageManager(this)

        // If no chats exist (first run), create one and open it directly so model loads
        val existing = chatStorageManager.getChatList()
        if (existing.isEmpty()) {
            val newChat = chatStorageManager.createNewChat("Topic 1")
            chatStorageManager.saveChatSessions(listOf(newChat))
            openChat(newChat)
            finish()
            return
        }

        // Find views
        chatRecyclerView = findViewById(R.id.chat_recycler_view)
        addChatFab = findViewById(R.id.add_chat_fab)
        
        // Setup RecyclerView
        chatAdapter = ChatListAdapter(
            chatList, 
            { chatSession ->
                openChat(chatSession)
            }, 
            { chatSession ->
                // immediate long-press action: prompt to delete single chat
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete chat")
                    .setMessage("Delete chat \"${chatSession.title}\"?")
                    .setPositiveButton("Yes") { _, _ ->
                        chatStorageManager.deleteChatSession(chatSession.id)
                        val idx = chatList.indexOfFirst { it.id == chatSession.id }
                        if (idx != -1) {
                            chatList.removeAt(idx)
                            chatAdapter.notifyItemRemoved(idx)
                        }
                        Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, 
            { chatSession ->
                // rename callback: show dialog with input field and Auto Rename option
                val editText = EditText(this)
                editText.setText(chatSession.title)
                editText.background = ContextCompat.getDrawable(this, R.drawable.bg_chat_input)
                editText.backgroundTintList = null
                val pad = (14 * resources.displayMetrics.density).toInt()
                editText.setPadding(pad, pad, pad, pad)

                val inputContainer = FrameLayout(this).apply {
                    val outerPad = (8 * resources.displayMetrics.density).toInt()
                    setPadding(outerPad, outerPad, outerPad, 0)
                    addView(
                        editText,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }

                val builder = MaterialAlertDialogBuilder(this)
                    .setTitle("Rename chat")
                    .setView(inputContainer)
                    .setPositiveButton("Save", null)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Auto Rename", null)

                val dialog = builder.create()
                dialog.show()

                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                val targetChatId = chatSession.id

                positive.setOnClickListener {
                    val newTitle = editText.text.toString().trim()
                    if (newTitle.isNotEmpty()) {
                        val updatedChat = chatSession.copy(title = newTitle)
                        chatStorageManager.updateChatSession(updatedChat)
                        val idx = chatList.indexOfFirst { it.id == chatSession.id }
                        if (idx != -1) {
                            chatList[idx] = updatedChat
                            chatAdapter.notifyItemChanged(idx)
                        }
                        dialog.dismiss()
                        Toast.makeText(this, "Chat renamed", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }

                neutral.setOnClickListener {
                    neutral.isEnabled = false
                    positive.isEnabled = false
                    neutral.text = "Renaming..."

                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            ensureModelReadyForAutoRename()

                            val targetSession = chatStorageManager.getChatSession(targetChatId)
                            if (targetSession == null) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@ChatListActivity, "Chat not found", Toast.LENGTH_SHORT).show()
                                    neutral.isEnabled = true
                                    positive.isEnabled = true
                                    neutral.text = "Auto Rename"
                                }
                                return@launch
                            }

                            val recentMessages = targetSession.messages.takeLast(10)
                            val userMessages = recentMessages.filter { it.isUser }
                                .joinToString("\n") { it.content.take(200) }
                            val assistantMessages = recentMessages.filter { !it.isUser }
                                .joinToString("\n") { it.content.take(200) }
                            val heuristicTopicTitle = deriveTopicTitleFromMessages(targetSession.messages)

                            val prompt = """Create ONE short title for this chat based on the dominant topic across the conversation.
Return only the final title text.

Critical instructions:
- Use the actual conversation content below. Do NOT invent a topic.
- If the latest user line is generic (like "tell me more"), infer topic from earlier user lines.
- Prefer USER messages for topic extraction; use ASSISTANT only as backup.
- The title must reflect the chat topic, not assistant phrasing.

Rules:
- MUST be 2-6 words
- MUST read like a title/headline (noun phrase), not a sentence
- MUST be clearly related to the main conversation theme
- Avoid starting with filler verbs like "Tell", "Explain", "Help", "Discuss"
- NO markdown formatting (no **bold**, no _italic_, no backticks)
- NO numbering, lists, or repetition
- NO quotes or punctuation
- NO generic titles like "Conversation", "Chat", "Question", or "Help"

USER MESSAGES:
$userMessages

ASSISTANT MESSAGES (backup):
$assistantMessages

Respond with ONLY the title, nothing else.
"""

                            var generatedName = ""
                            engine.sendUserPrompt(prompt).collect { token ->
                                generatedName += token
                            }

                            // Post-process: remove numbering and digits, collapse whitespace, enforce title shape
                            var cleaned = generatedName.trim().replace("\"", "").take(60).trim()
                            // Remove markdown emphasis/code markers
                            cleaned = cleaned.replace(Regex("""[*_`]+"""), "")
                            // Remove common numbering patterns (e.g., "1.", "1)", "1:") and any standalone digits
                            cleaned = cleaned.replace(Regex("""(?m)\b\d+[\.\)\:\-]*\s*"""), "")
                            // Remove any remaining digits
                            cleaned = cleaned.replace(Regex("""\d+"""), "")
                            // Collapse multiple spaces
                            cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
                            // Remove sentence-like leading filler
                            cleaned = cleaned.replace(Regex("""^(let'?s|lets|we|this is)\s+""", RegexOption.IGNORE_CASE), "").trim()

                            val words = cleaned.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                            val hasCopula = Regex("""\b(is|are|was|were)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)

                            withContext(Dispatchers.Main) {
                                if (words.isNotEmpty()) {
                                    val finalTitle = if (words.size <= 5) {
                                        words.joinToString(" ")
                                    } else {
                                        // take first 5 words as a safe fallback
                                        words.take(5).joinToString(" ")
                                    }
                                    // Ensure no leftover numbering characters
                                    var safe = finalTitle.replace(Regex("""\d+|[\.:)\-_,]"""), "").trim()

                                    // If model still produced sentence-style output, prefer topic-focused fallback.
                                    if (hasCopula && safe.split(Regex("""\s+""")).size >= 4) {
                                        safe = heuristicTopicTitle
                                    }

                                    if (isGenericOrUnrelatedAutoTitle(safe)) {
                                        safe = heuristicTopicTitle
                                    }

                                    if (safe.isNotEmpty()) {
                                        editText.setText(safe)
                                    } else {
                                        Toast.makeText(this@ChatListActivity, "Failed to generate name", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(this@ChatListActivity, "Failed to generate name", Toast.LENGTH_SHORT).show()
                                }
                                neutral.isEnabled = true
                                positive.isEnabled = true
                                neutral.text = "Auto Rename"
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatListActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                neutral.isEnabled = true
                                positive.isEnabled = true
                                neutral.text = "Auto Rename"
                            }
                        }
                    }
                }
            },
            chatStorageManager
        )
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = chatAdapter

        // Setup FAB with proper bottom inset handling
        addChatFab.setOnClickListener {
            createNewChat()
        }

        // Initialize AI engine (for model management)
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            Log.d(TAG, "AI Engine initialized for chat management")
            
            // engine initialized; Auto Rename will be handled from the rename dialog
        }

        // Load existing chats
        loadChatList()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.chat_list_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val inSelection = chatAdapter.isSelectionMode()
        menu.findItem(R.id.action_cancel_selection)?.isVisible = inSelection
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
                if (!chatAdapter.isSelectionMode()) {
                    // enter selection mode and instruct user
                    chatAdapter.enterSelectionMode()
                    Toast.makeText(this, "Long-press or tap to select chats to delete", Toast.LENGTH_SHORT).show()
                    invalidateOptionsMenu()
                } else {
                    val selected = chatAdapter.getSelectedChatIds()
                    if (selected.isEmpty()) {
                        Toast.makeText(this, "No chats selected", Toast.LENGTH_SHORT).show()
                    } else {
                        // confirm deletion
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Delete chats")
                            .setMessage("Delete ${selected.size} selected chat(s)? This action cannot be undone.")
                            .setPositiveButton("Yes") { _, _ -> performDelete(selected) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                true
            }
            R.id.action_cancel_selection -> {
                chatAdapter.exitSelectionMode()
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performDelete(selectedIds: List<String>) {
        for (id in selectedIds) {
            chatStorageManager.deleteChatSession(id)
            val idx = chatList.indexOfFirst { it.id == id }
            if (idx != -1) {
                chatList.removeAt(idx)
            }
        }
        chatAdapter.exitSelectionMode()
        chatAdapter.notifyDataSetChanged()
        invalidateOptionsMenu()
        Toast.makeText(this, "Deleted ${selectedIds.size} chat(s)", Toast.LENGTH_SHORT).show()
    }

    private fun loadChatList() {
        chatList.clear()
        chatList.addAll(chatStorageManager.getChatList())
        chatAdapter.notifyDataSetChanged()
    }

    private fun createNewChat() {
        // Pick the smallest unused Topic number (Topic 1, Topic 2, ...)
        var n = 1
        val existingTitles = chatList.map { it.title }.toSet()
        while (existingTitles.contains("Topic $n")) {
            n++
        }
        val newChat = chatStorageManager.createNewChat("Topic $n")
        chatList.add(0, newChat) // Add to beginning
        chatStorageManager.saveChatSessions(chatList)
        chatAdapter.notifyItemInserted(0)
        chatRecyclerView.scrollToPosition(0)

        // Open the new chat
        openChat(newChat)
    }

    private fun openChat(chatSession: ChatSession) {
        // Save as active chat
        chatStorageManager.saveActiveChatId(chatSession.id)

        // Start ChatActivity
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chatSession.id)
        }
        startActivity(intent)
    }


    override fun onResume() {
        super.onResume()
        // Refresh chat list when returning from chat
        loadChatList()
    }

    private suspend fun ensureModelReadyForAutoRename() {
        if (!::engine.isInitialized) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        var waitedMs = 0L
        while (true) {
            when (engine.state.value) {
                is InferenceEngine.State.ModelReady -> {
                    // Always reset before rename so previous chat context cannot leak.
                    engine.cleanUp()
                    continue
                }
                is InferenceEngine.State.Initialized -> {
                    val modelFile = ensureBundledModelFile()
                    engine.loadModel(modelFile.absolutePath)
                    engine.setSystemPrompt(DEFAULT_SYSTEM_PROMPT_FOR_LIST_SCREEN)
                    return
                }
                is InferenceEngine.State.Error -> engine.cleanUp()
                else -> {
                    delay(120)
                    waitedMs += 120
                    if (waitedMs >= MODEL_READY_WAIT_TIMEOUT_MS) {
                        throw IllegalStateException("Model is still initializing. Please try again.")
                    }
                }
            }
        }
    }

    private suspend fun ensureBundledModelFile() =
        withContext(Dispatchers.IO) {
            val targetFile = File(ensureModelsDirectory(), MODEL_FILE_NAME)
            if (targetFile.exists() && targetFile.length() > 1_000_000) {
                return@withContext targetFile
            }

            if (targetFile.exists()) targetFile.delete()
            targetFile.parentFile?.mkdirs()
            assets.open(MODEL_FILE_NAME).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!targetFile.exists() || targetFile.length() <= 1_000_000) {
                throw IllegalStateException("Model file missing or invalid.")
            }
            targetFile
        }

    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) it.delete()
            if (!it.exists()) it.mkdir()
        }

    private fun deriveTopicTitleFromMessages(messages: List<Message>): String {
        val firstSubstantiveUser = messages
            .filter { it.isUser }
            .map { normalizeInline(it.content) }
            .firstOrNull { text -> text.length >= 8 && !isGenericFollowUp(text) }
            ?: messages.filter { it.isUser }.firstOrNull()?.content?.let(::normalizeInline)
            ?: return "Conversation Topic"

        return deriveTopicTitleFromText(firstSubstantiveUser)
    }

    private fun deriveTopicTitleFromText(raw: String): String {
        var text = raw.trim()
        text = text.replace(Regex("""^(hi|hello|hey)\b[!,. ]*""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""^(can you|could you|would you|please)\s+""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""^(tell me|explain|describe|discuss|help me understand)\s+""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""^(about|on)\s+""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""[\r\n]+"""), " ").trim()
        text = text.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

        val working = Regex("""\bhow\s+(.+?)\s+work(s)?\b""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        val baseTitle = if (working != null) {
            "How $working Works"
        } else {
            text.split(Regex("""\s+"""))
                .filter { token ->
                    token.length > 2 && token.lowercase(Locale.US) !in STOP_WORDS
                }
                .take(5)
                .joinToString(" ")
        }

        if (baseTitle.isBlank()) return "Conversation Topic"
        return baseTitle.split(Regex("""\s+"""))
            .take(6)
            .joinToString(" ") { word ->
                word.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                }
            }
            .trim()
    }

    private fun isGenericOrUnrelatedAutoTitle(title: String): Boolean {
        if (title.isBlank()) return true
        val normalized = title.lowercase(Locale.US).trim()
        if (normalized in GENERIC_AUTO_TITLES) return true
        if (normalized.split(Regex("""\s+""")).size < 2) return true
        return false
    }

    private fun normalizeInline(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

    private fun isGenericFollowUp(input: String): Boolean {
        val normalized = normalizeInline(input).lowercase(Locale.US)
        return normalized in GENERIC_FOLLOW_UPS
    }

    companion object {
        private const val TAG = "ChatListActivity"
        private const val DIRECTORY_MODELS = "models"
        private const val MODEL_FILE_NAME = "llama.gguf"
        private const val MODEL_READY_WAIT_TIMEOUT_MS = 20_000L
        private const val DEFAULT_SYSTEM_PROMPT_FOR_LIST_SCREEN =
            "You are a helpful assistant. Follow user instructions accurately and respond clearly."
        private val GENERIC_FOLLOW_UPS = setOf(
            "tell me more",
            "more",
            "continue",
            "what else",
            "anything else",
            "something else"
        )
        private val GENERIC_AUTO_TITLES = setOf(
            "conversation",
            "chat",
            "help",
            "question",
            "topic",
            "timekeeping",
            "timekeeping systems",
            "conversation topic"
        )
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "about", "into", "your",
            "you", "are", "was", "were", "have", "has", "had", "would", "could", "should",
            "tell", "more", "please", "explain", "what", "when", "where", "why", "how",
            "does", "do", "did", "can", "i", "me", "my", "to", "of", "in", "on", "a", "an"
        )
    }
}
