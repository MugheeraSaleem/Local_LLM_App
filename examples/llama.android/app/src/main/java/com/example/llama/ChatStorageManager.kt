package com.example.llama

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class ChatStorageManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "llama_chat_prefs"
        private const val KEY_CHAT_SESSIONS = "chat_sessions"
        private const val KEY_ACTIVE_CHAT_ID = "active_chat_id"
    }

    // Save all chat sessions
    fun saveChatSessions(sessions: List<ChatSession>) {
        val json = gson.toJson(sessions)
        prefs.edit().putString(KEY_CHAT_SESSIONS, json).apply()
    }

    // Load all chat sessions
    fun loadChatSessions(): MutableList<ChatSession> {
        val json = prefs.getString(KEY_CHAT_SESSIONS, null)
        return if (json != null) {
            val type = object : TypeToken<List<ChatSession>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } else {
            mutableListOf()
        }
    }

    // Save active chat ID
    fun saveActiveChatId(chatId: String?) {
        prefs.edit().putString(KEY_ACTIVE_CHAT_ID, chatId).apply()
    }

    // Get active chat ID
    fun getActiveChatId(): String? {
        return prefs.getString(KEY_ACTIVE_CHAT_ID, null)
    }

    // Create a new chat session
    fun createNewChat(title: String = "New Chat"): ChatSession {
        val chatId = UUID.randomUUID().toString()
        val newChat = ChatSession(
            id = chatId,
            title = title,
            createdAt = System.currentTimeMillis()
        )
        return newChat
    }

    // Update a chat session
    fun updateChatSession(chatSession: ChatSession) {
        val sessions = loadChatSessions()
        val index = sessions.indexOfFirst { it.id == chatSession.id }
        if (index != -1) {
            sessions[index] = chatSession
            saveChatSessions(sessions)
        }
    }

    // Delete a chat session
    fun deleteChatSession(chatId: String) {
        val sessions = loadChatSessions()
        sessions.removeAll { it.id == chatId }
        saveChatSessions(sessions)

        // If this was the active chat, clear it
        if (getActiveChatId() == chatId) {
            saveActiveChatId(null)
        }
    }

    // Get a specific chat session
    fun getChatSession(chatId: String): ChatSession? {
        val sessions = loadChatSessions()
        return sessions.find { it.id == chatId }
    }

    // Add message to chat session
    fun addMessageToChat(chatId: String, message: Message) {
        val sessions = loadChatSessions()
        val index = sessions.indexOfFirst { it.id == chatId }
        if (index != -1) {
            val chat = sessions[index]
            val updatedMessages = chat.messages + message
            val updatedChat = chat.copy(
                messages = updatedMessages,
                lastMessageAt = message.timestamp
            )
            sessions[index] = updatedChat
            saveChatSessions(sessions)
        }
    }

    // Get chat list for display (sorted by last message time)
    fun getChatList(): List<ChatSession> {
        return loadChatSessions().sortedByDescending { it.lastMessageAt }
    }
}