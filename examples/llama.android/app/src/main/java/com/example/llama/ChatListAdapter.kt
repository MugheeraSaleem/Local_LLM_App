package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatListAdapter(
    private val chatSessions: MutableList<ChatSession>,
    private val onChatClick: (ChatSession) -> Unit,
    private val onChatLongClick: ((ChatSession) -> Unit)? = null,
    private val onChatRename: ((ChatSession) -> Unit)? = null,
    private val chatStorageManager: ChatStorageManager? = null
) : RecyclerView.Adapter<ChatListAdapter.ChatViewHolder>() {

    // inference engine not required in adapter — auto-rename handled in activity
    private val dateFormat = SimpleDateFormat("dd:MM:yyyy", Locale.getDefault())
    private val selectedIds = mutableSetOf<String>()
    private var selectionMode = false

    fun isSelectionMode(): Boolean = selectionMode
    fun enterSelectionMode() {
        selectionMode = true
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        selectionMode = false
        clearSelection()
        notifyDataSetChanged()
    }

    fun getSelectedChatIds(): List<String> = selectedIds.toList()

    fun clearSelection() {
        selectedIds.clear()
    }

    private fun toggleSelection(chatId: String) {
        if (selectedIds.contains(chatId)) selectedIds.remove(chatId) else selectedIds.add(chatId)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_session, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chatSession = chatSessions[position]
        holder.bind(chatSession)
    }

    override fun getItemCount(): Int = chatSessions.size

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.chat_title)
        private val lastMessageTextView: TextView = itemView.findViewById(R.id.last_message)
        private val timestampTextView: TextView = itemView.findViewById(R.id.timestamp)
        private val messageCountTextView: TextView = itemView.findViewById(R.id.message_count)
        private val editButton: ImageButton = itemView.findViewById(R.id.edit_chat_button)

        fun bind(chatSession: ChatSession) {
            titleTextView.text = chatSession.title

            // Show last message preview or default text
            val lastMessage = chatSession.messages.lastOrNull()
            lastMessageTextView.text = lastMessage?.content?.take(50)?.plus(if (lastMessage.content.length > 50) "..." else "") ?: "No messages yet"

            // Show timestamp
            timestampTextView.text = dateFormat.format(Date(chatSession.lastMessageAt))

            // Show message count
            val messageCount = chatSession.messages.size
            messageCountTextView.text = if (messageCount > 0) "$messageCount messages" else "Empty chat"

            // Visual selection state
            val isSelected = selectedIds.contains(chatSession.id)
            itemView.alpha = if (isSelected) 0.6f else 1.0f

            // Handle click/long click for selection
            itemView.setOnLongClickListener {
                if (!selectionMode) {
                    // if activity provided an explicit long-click handler, prefer that (e.g., prompt delete)
                    if (onChatLongClick != null) {
                        onChatLongClick.invoke(chatSession)
                        return@setOnLongClickListener true
                    }
                    selectionMode = true
                }
                toggleSelection(chatSession.id)
                true
            }

            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(chatSession.id)
                } else {
                    onChatClick(chatSession)
                }
            }

            // No auto-rename button in card; rename handled via edit dialog

            // Handle edit button click
            editButton.setOnClickListener {
                if (onChatRename != null) {
                    onChatRename.invoke(chatSession)
                }
            }
        }
        
        // generateChatName moved to activity where engine is available
    }
}