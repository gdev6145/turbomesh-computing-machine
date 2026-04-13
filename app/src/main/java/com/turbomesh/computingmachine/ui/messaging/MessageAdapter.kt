package com.turbomesh.computingmachine.ui.messaging

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbomesh.computingmachine.R
import com.turbomesh.computingmachine.databinding.ItemMessageBinding
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MessageAdapter(
    /** Feature 3: Called when user long-presses a message to react. */
    private val onReactionClick: ((MeshMessage) -> Unit)? = null,
    private val onEditClick: ((MeshMessage) -> Unit)? = null,
    private val onDeleteClick: ((MeshMessage) -> Unit)? = null,
    private val onPinClick: ((MeshMessage) -> Unit)? = null,
) : ListAdapter<MessageListItem, RecyclerView.ViewHolder>(MessageListItemDiffCallback()) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Feature 3: reactions map provided by ViewModel. Key = originalMessageId. */
    var reactions: Map<String, List<Pair<String, String>>> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

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
                    .inflate(R.layout.item_date_header, parent, false) as TextView
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

    fun isMessageAt(position: Int): Boolean =
        position in 0 until currentList.size && getItem(position) is MessageListItem.MessageItem

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

            // Content
            val rawText = try {
                String(message.payload, Charsets.UTF_8).ifBlank { "[${message.type.name}]" }
            } catch (e: java.nio.charset.CharacterCodingException) {
                "[binary data ${message.payload.size} bytes]"
            }
            val contentPrefix = when (message.type) {
                MeshMessageType.FILE_COMPLETE -> "📎 "
                MeshMessageType.VOICE_COMPLETE -> "🎙️ "
                else -> ""
            }
            binding.textMessageContent.text = contentPrefix + rawText

            binding.textMessageTime.text = timeFormat.format(Date(message.timestamp))
            binding.textMessageType.text = if (message.type == MeshMessageType.DATA) "" else "[${message.type.name}]"
            binding.textHopCount.text = "Hops: ${message.hopCount}"
            binding.root.alpha = if (isSelf) 1.0f else 0.85f

            // Feature 21: Deleted message
            if (message.deletedAtMs != null) {
                binding.textMessageContent.text = "🗑 Message deleted"
                binding.textMessageContent.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.status_discovered)
                )
                binding.textReplyQuote.visibility = View.GONE
                binding.textEditedLabel.visibility = View.GONE
                binding.textExpiryCountdown.visibility = View.GONE
                binding.root.setOnLongClickListener(null)
                return
            }

            // Feature 19: Reply quote
            val replyId = message.replyToMsgId
            if (replyId != null) {
                binding.textReplyQuote.visibility = View.VISIBLE
                binding.textReplyQuote.text = "↩ Re: ${replyId.take(8)}…"
            } else {
                binding.textReplyQuote.visibility = View.GONE
            }

            // Feature 20: Edited label
            binding.textEditedLabel.visibility = if (message.isEdited) View.VISIBLE else View.GONE

            // Feature 24: Expiry countdown
            val expiresAt = message.expiresAtMs
            if (expiresAt != null && expiresAt > System.currentTimeMillis()) {
                val remaining = expiresAt - System.currentTimeMillis()
                val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
                binding.textExpiryCountdown.text = "⏱ expires in ${minutes}m"
                binding.textExpiryCountdown.visibility = View.VISIBLE
            } else {
                binding.textExpiryCountdown.visibility = View.GONE
            }

            // Feature 2: Read receipt — show lock icon for encryption or ✓✓ read confirmation
            if (isSelf) {
                binding.textMessageAck.visibility = View.VISIBLE
                binding.textMessageAck.text = when {
                    message.readAtMs != null -> "✓✓"    // read
                    message.isAcknowledged -> "✓✓"      // delivered
                    else -> "✓"                          // sent
                }
                val ackColor = when {
                    message.readAtMs != null -> ContextCompat.getColor(binding.root.context, R.color.teal_200)
                    else -> ContextCompat.getColor(binding.root.context, R.color.status_discovered)
                }
                binding.textMessageAck.setTextColor(ackColor)
            } else {
                binding.textMessageAck.visibility = View.GONE
            }

            // Feature 3: Reactions
            val msgReactions = reactions[message.id]
            if (!msgReactions.isNullOrEmpty()) {
                val grouped = msgReactions.groupBy { it.first }
                    .map { (emoji, pairs) -> if (pairs.size > 1) "$emoji×${pairs.size}" else emoji }
                binding.textReactions.visibility = View.VISIBLE
                binding.textReactions.text = grouped.joinToString("  ")
            } else {
                binding.textReactions.visibility = View.GONE
            }

            // Long-press: for own messages show edit/delete/pin menu; for others react
            binding.root.setOnLongClickListener {
                if (isSelf) {
                    val options = arrayOf(
                        "✏ Edit",
                        "🗑 Delete for everyone",
                        if (message.isPinned) "📌 Unpin" else "📌 Pin"
                    )
                    AlertDialog.Builder(binding.root.context)
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> onEditClick?.invoke(message)
                                1 -> onDeleteClick?.invoke(message)
                                2 -> onPinClick?.invoke(message)
                            }
                        }
                        .show()
                } else {
                    onReactionClick?.invoke(message)
                }
                true
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
                            oldItem.message.isAcknowledged == newItem.message.isAcknowledged &&
                            oldItem.message.readAtMs == newItem.message.readAtMs &&
                            oldItem.message.isEdited == newItem.message.isEdited &&
                            oldItem.message.deletedAtMs == newItem.message.deletedAtMs &&
                            oldItem.message.isPinned == newItem.message.isPinned
                else -> false
            }
        }
    }
}
