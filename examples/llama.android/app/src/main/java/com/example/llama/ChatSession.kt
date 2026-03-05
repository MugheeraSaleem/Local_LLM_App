package com.example.llama

import com.google.gson.annotations.SerializedName

data class ChatSession(
    @SerializedName("id")
    val id: String,

    @SerializedName("title")
    val title: String,

    @SerializedName("createdAt")
    val createdAt: Long,

    @SerializedName("lastMessageAt")
    val lastMessageAt: Long = createdAt,

    @SerializedName("messages")
    val messages: List<Message> = emptyList()
)

data class Message(
    @SerializedName("id")
    val id: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("isUser")
    val isUser: Boolean,

    @SerializedName("timestamp")
    val timestamp: Long
)