package com.example.edgeai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layoutId = if (viewType == TYPE_USER) R.layout.item_chat_user else R.layout.item_chat_ai
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val ivImage: ImageView? = itemView.findViewById(R.id.ivChatImage)

        fun bind(message: ChatMessage) {
            if (message.isLoading) {
                tvMessage.text = "思考中..."
            } else {
                tvMessage.text = message.text
            }

            // 用户消息可能带图片
            ivImage?.let {
                if (message.image != null) {
                    it.setImageBitmap(message.image)
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.GONE
                }
            }
        }
    }
}
