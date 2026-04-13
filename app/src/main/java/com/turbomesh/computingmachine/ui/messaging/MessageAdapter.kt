package com.turbomesh.computingmachine.ui.messaging

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.computingmachine.databinding.ItemMessageBinding
import com.turbomesh.computingmachine.mesh.MeshMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : ListAdapter<MessageListItem, RecyclerView.ViewHolder>(MessageListItemDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val VIEW_TYPE_DATE_HEADER = 0
        private const val VIEW_TYPE_MESSAGE = 1
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MessageListItem.DateHeader -> VIEW_TYPE_DATE_HEADER
        is MessageListItem.MessageItem -> VIEW_TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(com.turbomesh.computingmachine.R.layout.item_date_header, parent, false) as TextView
                DateHeaderViewHolder(view)
            }
            else -> {
                val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                MessageViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is MessageListItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item.label)
            is MessageListItem.MessageItem -> (holder as MessageViewHolder).bind(item.message)
        }
    }

    /** Returns true if the item at [position] is a swipeable message (not a date header). */
    fun isMessageAt(position: Int): Boolean =
        position in 0 until currentList.size && getItem(position) is MessageListItem.MessageItem

    /** Returns the [MeshMessage] at [position], or null if it is a date-header row. */
    fun messageAt(position: Int): MeshMessage? =
        (currentList.getOrNull(position) as? MessageListItem.MessageItem)?.message

    class DateHeaderViewHolder(private val view: TextView) : RecyclerView.ViewHolder(view) {
        fun bind(label: String) { view.text = label }
    }

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: MeshMessage) {
            val isSelf = message.sourceNodeId == "self"
            binding.textMessageSource.text = if (isSelf) "Me" else message.sourceNodeId.take(8)
            binding.textMessageContent.text = try {
                String(message.payload, Charsets.UTF_8).ifBlank { "[${message.type.name}]" }
            } catch (e: java.nio.charset.CharacterCodingException) {
                android.util.Log.d("MessageAdapter", "Non-UTF8 payload, showing byte count", e)
                "[binary data ${message.payload.size} bytes]"
            }
            binding.textMessageTime.text = timeFormat.format(Date(message.timestamp))
            binding.textMessageType.text = message.type.name
            binding.textHopCount.text = "Hops: ${message.hopCount}"

            binding.root.alpha = if (isSelf) 1.0f else 0.85f

            if (isSelf) {
                binding.textMessageAck.visibility = View.VISIBLE
                binding.textMessageAck.text = if (message.isAcknowledged) "✓✓" else "✓"
            } else {
                binding.textMessageAck.visibility = View.GONE
            }
        }
    }

    class MessageListItemDiffCallback : DiffUtil.ItemCallback<MessageListItem>() {
        override fun areItemsTheSame(oldItem: MessageListItem, newItem: MessageListItem): Boolean {
            return when {
                oldItem is MessageListItem.DateHeader && newItem is MessageListItem.DateHeader ->
                    oldItem.label == newItem.label
                oldItem is MessageListItem.MessageItem && newItem is MessageListItem.MessageItem ->
                    oldItem.message.id == newItem.message.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: MessageListItem, newItem: MessageListItem): Boolean {
            return when {
                oldItem is MessageListItem.DateHeader && newItem is MessageListItem.DateHeader ->
                    oldItem == newItem
                oldItem is MessageListItem.MessageItem && newItem is MessageListItem.MessageItem ->
                    oldItem.message.id == newItem.message.id &&
                            oldItem.message.isAcknowledged == newItem.message.isAcknowledged
                else -> false
            }
        }
    }
}


import android.view.LayoutInflater
import android.view.View
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
            } catch (e: java.nio.charset.CharacterCodingException) {
                android.util.Log.d("MessageAdapter", "Non-UTF8 payload, showing byte count", e)
                "[binary data ${message.payload.size} bytes]"
            }
            binding.textMessageTime.text = dateFormat.format(Date(message.timestamp))
            binding.textMessageType.text = message.type.name
            binding.textHopCount.text = "Hops: ${message.hopCount}"

            binding.root.alpha = if (isSelf) 1.0f else 0.85f

            // Show ACK status only for messages sent by this device
            if (isSelf) {
                binding.textMessageAck.visibility = View.VISIBLE
                binding.textMessageAck.text = if (message.isAcknowledged) "✓✓" else "✓"
            } else {
                binding.textMessageAck.visibility = View.GONE
            }
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<MeshMessage>() {
        override fun areItemsTheSame(oldItem: MeshMessage, newItem: MeshMessage) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MeshMessage, newItem: MeshMessage) =
            oldItem.id == newItem.id && oldItem.isAcknowledged == newItem.isAcknowledged
    }
}

