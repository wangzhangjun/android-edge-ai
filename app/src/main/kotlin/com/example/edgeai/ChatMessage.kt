package com.example.edgeai

import android.graphics.Bitmap

/**
 * 聊天消息数据类
 * @param text 消息文本
 * @param image 附带的图片（仅用户消息可能有）
 * @param isUser true=用户消息，false=AI回复
 * @param isLoading AI 正在生成中（用于显示加载状态）
 */
data class ChatMessage(
    var text: String,
    val image: Bitmap? = null,
    val isUser: Boolean,
    var isLoading: Boolean = false
)
