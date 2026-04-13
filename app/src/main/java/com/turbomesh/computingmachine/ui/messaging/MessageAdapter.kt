package com.turbomesh.computingmachine.ui.messaging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.computingmachine.databinding.ItemMessageBinding
import com.turbomesh.computingmachine.mesh.MeshMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<MeshMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: MeshMessage) {
            val isSelf = message.sourceNodeId == "self"
            binding.textMessageSource.text = if (isSelf) "Me" else message.sourceNodeId.take(8)
            binding.textMessageContent.text = try {
                String(message.payload, Charsets.UTF_8).ifBlank { "[${message.type.name}]" }
            } catch (e: Exception) {
                "[binary data ${message.payload.size} bytes]"
            }
            binding.textMessageTime.text = dateFormat.format(Date(message.timestamp))
            binding.textMessageType.text = message.type.name
            binding.textHopCount.text = "Hops: ${message.hopCount}"

            binding.root.alpha = if (isSelf) 1.0f else 0.85f
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MeshMessage>() {
        override fun areItemsTheSame(oldItem: MeshMessage, newItem: MeshMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MeshMessage, newItem: MeshMessage) = oldItem == newItem
    }
}
