package com.turbomesh.computingmachine.ui.messaging

import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.turbomesh.computingmachine.R
import com.turbomesh.computingmachine.databinding.FragmentMessagingBinding
import com.turbomesh.computingmachine.mesh.MeshMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessagingFragment : Fragment() {

    private var _binding: FragmentMessagingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MessagingViewModel by activityViewModels()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var destinationArrayAdapter: ArrayAdapter<String>
    private val destinations = mutableListOf(MeshMessage.BROADCAST_DESTINATION)

    private val dateLabelFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val mimeType = requireContext().contentResolver.getType(uri) ?: "application/octet-stream"
            viewModel.sendFile(uri, mimeType)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        messageAdapter = MessageAdapter(
            onReactionClick = { msg -> showReactionPicker(msg) },
            onEditClick = { msg ->
                val editText = android.widget.EditText(requireContext()).apply {
                    try { setText(String(msg.payload, Charsets.UTF_8)) } catch (e: Exception) {}
                    selectAll()
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Edit message")
                    .setView(editText)
                    .setPositiveButton("Save") { _, _ ->
                        viewModel.editMessage(msg.id, editText.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onDeleteClick = { msg ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete message")
                    .setMessage("Delete this message for everyone?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.recallMessage(msg.id) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
            onPinClick = { msg -> viewModel.togglePin(msg) }
        )
        binding.recyclerMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.recyclerMessages.adapter = messageAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (messageAdapter.isMessageAt(vh.adapterPosition)) super.getSwipeDirs(rv, vh) else 0
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val msg = messageAdapter.messageAt(vh.adapterPosition) ?: return
                when (direction) {
                    ItemTouchHelper.LEFT -> viewModel.deleteMessage(msg.id)
                    ItemTouchHelper.RIGHT -> viewModel.replyTo(msg)
                }
            }
        }).attachToRecyclerView(binding.recyclerMessages)

        destinationArrayAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, destinations
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerDestination.adapter = destinationArrayAdapter

        binding.toolbarMessaging.inflateMenu(R.menu.menu_messaging)
        binding.toolbarMessaging.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_messages -> { shareMessages(); true }
                R.id.action_clear_messages -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.clear_messages)
                        .setMessage(R.string.clear_messages_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.clearMessages() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }

        binding.editSearch.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }

        // Feature 1: Send typing indicator as user types
        binding.editMessageInput.addTextChangedListener { text ->
            if (!text.isNullOrBlank()) viewModel.sendTypingIndicator()
        }

        binding.buttonSend.setOnClickListener {
            val text = binding.editMessageInput.text?.toString() ?: return@setOnClickListener
            viewModel.sendMessage(text)
            binding.editMessageInput.text?.clear()
        }

        // Feature 14: Long-press send button → SOS emergency
        binding.buttonSend.setOnLongClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("🆘 Emergency SOS")
                .setMessage(getString(R.string.sos_confirm))
                .setPositiveButton(R.string.sos_send) { _, _ ->
                    val text = binding.editMessageInput.text?.toString()?.ifBlank { "⚠️ EMERGENCY SOS" } ?: "⚠️ EMERGENCY SOS"
                    viewModel.sendEmergency(text)
                    binding.editMessageInput.text?.clear()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // Feature 4: Attach button for file sharing
        binding.buttonAttach.setOnClickListener {
            filePicker.launch("*/*")
        }

        // Feature 5: Voice note PTT — hold to record
        binding.buttonVoice.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { viewModel.startVoiceRecording(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    viewModel.stopVoiceRecordingAndSend()
                    true
                }
                else -> false
            }
        }

        // Feature 17: Clipboard share button
        binding.buttonClipboard.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                viewModel.sendClipboard(text)
                Snackbar.make(binding.root, getString(R.string.clipboard_sent), Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, getString(R.string.clipboard_empty), Snackbar.LENGTH_SHORT).show()
            }
        }

        // Reply banner dismiss button
        binding.buttonDismissReply.setOnClickListener {
            viewModel.clearReply()
        }

        // Feature 23: Schedule message button
        binding.buttonSchedule.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, year, month, day ->
                android.app.TimePickerDialog(requireContext(), { _, hour, minute ->
                    cal.set(year, month, day, hour, minute, 0)
                    viewModel.scheduledAt.value = cal.timeInMillis
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        // Feature 24: Expiry button
        binding.buttonExpiry.setOnClickListener {
            val options = arrayOf("5 min", "1 hour", "24 hours", "Never")
            val durations = arrayOf(5 * 60 * 1000L, 60 * 60 * 1000L, 24 * 60 * 60 * 1000L, null)
            AlertDialog.Builder(requireContext())
                .setTitle("Message expiry")
                .setItems(options) { _, which ->
                    viewModel.expiresInMs.value = durations[which]
                }
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.filteredMessages.collect { messages ->
                        val listItems = insertDateSeparators(messages)
                        messageAdapter.submitList(listItems)
                        if (listItems.isNotEmpty()) {
                            binding.recyclerMessages.scrollToPosition(listItems.size - 1)
                        }
                    }
                }
                launch {
                    viewModel.reactionsByMessageId.collect { reactionMap ->
                        messageAdapter.reactions = reactionMap
                    }
                }
                launch {
                    viewModel.availableDestinations.collect { dests ->
                        destinations.clear()
                        destinations.addAll(dests.map { formatDestination(it) })
                        destinationArrayAdapter.notifyDataSetChanged()
                    }
                }
                launch { viewModel.knownChannels.collect { channels -> rebuildChannelChips(channels) } }
                launch {
                    viewModel.deliveryFailures.collect { failedId ->
                        Snackbar.make(binding.root, getString(R.string.delivery_failed_format, failedId.take(8)), Snackbar.LENGTH_LONG).show()
                    }
                }
                // Feature 1: Typing indicator
                launch {
                    viewModel.typingPeers.collect { peers ->
                        if (peers.isEmpty()) {
                            binding.textTypingIndicator.visibility = View.GONE
                        } else {
                            val names = peers.joinToString(", ") { it.take(8) }
                            binding.textTypingIndicator.text = getString(R.string.typing_indicator_format, names)
                            binding.textTypingIndicator.visibility = View.VISIBLE
                        }
                    }
                }
                // Feature 5: Recording indicator
                launch {
                    viewModel.isRecording.collect { recording ->
                        binding.buttonVoice.text = if (recording) getString(R.string.recording) else getString(R.string.voice)
                    }
                }
                // Feature 14: Emergency SOS alert
                launch {
                    viewModel.emergencyMessages.collect { (sender, text) ->
                        showEmergencyNotification(sender, text)
                        Snackbar.make(binding.root, "🆘 SOS from $sender: $text", Snackbar.LENGTH_INDEFINITE)
                            .setAction("OK") {}
                            .show()
                    }
                }
                // Feature 17: Inbound clipboard
                launch {
                    viewModel.inboundClipboard.collect { text ->
                        showClipboardNotification(text)
                    }
                }
                // Feature 16: Group invitation
                launch {
                    viewModel.groupInvites.collect { (groupId, groupName, sender) ->
                        AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.group_invite_title))
                            .setMessage(getString(R.string.group_invite_message, groupName, sender.take(8)))
                            .setPositiveButton(R.string.accept) { _, _ -> viewModel.createGroup(groupName, emptyList()) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                // Feature 19: Reply target banner
                launch {
                    viewModel.replyTarget.collect { replyMsg ->
                        if (replyMsg != null) {
                            binding.layoutReplyBanner.visibility = View.VISIBLE
                            val text = try { String(replyMsg.payload, Charsets.UTF_8).take(60) } catch (e: Exception) { "" }
                            binding.textReplyBannerContent.text = "↩ Replying to: $text"
                        } else {
                            binding.layoutReplyBanner.visibility = View.GONE
                        }
                    }
                }
                // Feature 22: Pinned messages
                launch {
                    viewModel.pinnedMessages.collect { pinned ->
                        updatePinnedRow(pinned)
                    }
                }
                // Feature 23: Scheduled indicator
                launch {
                    viewModel.scheduledAt.collect { schedAt ->
                        binding.chipScheduled.visibility = if (schedAt != null) View.VISIBLE else View.GONE
                        if (schedAt != null) {
                            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                            binding.chipScheduled.text = "(scheduled: ${fmt.format(Date(schedAt))})"
                        }
                    }
                }
                // Feature 24: Expiry indicator
                launch {
                    viewModel.expiresInMs.collect { expiresMs ->
                        binding.chipExpiry.visibility = if (expiresMs != null) View.VISIBLE else View.GONE
                        if (expiresMs != null) {
                            val mins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(expiresMs)
                            binding.chipExpiry.text = "(expires in ${mins}m)"
                        }
                    }
                }
            }
        }

        binding.spinnerDestination.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    viewModel.selectDestination(destinations.getOrNull(position) ?: MeshMessage.BROADCAST_DESTINATION)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    override fun onResume() {
        super.onResume()
        viewModel.markAllRead()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // -------------------------------------------------------------------------
    // Feature 3: Reaction picker
    // -------------------------------------------------------------------------

    private fun showReactionPicker(message: MeshMessage) {
        val emojis = arrayOf("👍", "❤️", "😂", "😮", "😢", "🔥")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.react_to_message)
            .setItems(emojis) { _, which ->
                viewModel.sendReaction(message.id, emojis[which])
            }
            .show()
    }

    // -------------------------------------------------------------------------
    // Feature 14: Emergency heads-up notification
    // -------------------------------------------------------------------------

    private fun showEmergencyNotification(sender: String, text: String) {
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_EMERGENCY, getString(R.string.channel_emergency), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notif = NotificationCompat.Builder(requireContext(), CHANNEL_EMERGENCY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🆘 Emergency SOS from $sender")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        nm.notify(NOTIF_ID_EMERGENCY, notif)
    }

    // -------------------------------------------------------------------------
    // Feature 17: Clipboard notification
    // -------------------------------------------------------------------------

    private fun showClipboardNotification(text: String) {
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CLIPBOARD, getString(R.string.channel_clipboard), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(requireContext(), CHANNEL_CLIPBOARD)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.clipboard_received_title))
            .setContentText(text.take(100))
            .addAction(
                android.R.drawable.ic_menu_edit,
                getString(R.string.paste_here),
                android.app.PendingIntent.getBroadcast(
                    requireContext(), 0, Intent(), android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        nm.notify(NOTIF_ID_CLIPBOARD, notif)
        // Also place text in system clipboard
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mesh", text))
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun insertDateSeparators(messages: List<MeshMessage>): List<MessageListItem> {
        val result = mutableListOf<MessageListItem>()
        var lastLabel: String? = null
        messages.forEach { msg ->
            val label = dateLabelFormat.format(Date(msg.timestamp))
            if (label != lastLabel) {
                result.add(MessageListItem.DateHeader(label))
                lastLabel = label
            }
            result.add(MessageListItem.MessageItem(msg))
        }
        return result
    }

    private fun shareMessages() {
        val text = viewModel.buildExportText()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_messages)))
    }

    private fun rebuildChannelChips(channels: List<String>) {
        val chipGroup = binding.chipGroupChannels
        val scrollView = binding.scrollChannels
        if (channels.isEmpty()) { scrollView.visibility = View.GONE; return }
        scrollView.visibility = View.VISIBLE
        val existingTags = (0 until chipGroup.childCount)
            .mapNotNull { i -> (chipGroup.getChildAt(i) as? Chip)?.tag as? String }
            .toSet()
        val newTags = channels.toSet()
        val toRemove = (0 until chipGroup.childCount)
            .mapNotNull { i -> chipGroup.getChildAt(i) as? Chip }
            .filter { (it.tag as? String) !in newTags }
        toRemove.forEach { chipGroup.removeView(it) }
        channels.filter { it !in existingTags }.forEach { channel ->
            val chip = Chip(requireContext()).apply {
                text = "#$channel"; tag = channel; isCheckable = true; isCheckedIconVisible = true
            }
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) viewModel.setChannelFilter(channel)
                else if (viewModel.activeChannelFilter.value == channel) viewModel.setChannelFilter(null)
            }
            chipGroup.addView(chip)
        }
    }

    private fun formatDestination(raw: String): String =
        if (raw.startsWith("group:")) {
            val parts = raw.split(":")
            "👥 ${parts.getOrElse(2) { "Group" }}"
        } else raw

    private fun updatePinnedRow(pinned: List<MeshMessage>) {
        binding.pinnedMessagesRow.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
        binding.chipGroupPinned.removeAllViews()
        pinned.forEach { msg ->
            val chip = Chip(requireContext()).apply {
                val text = try { String(msg.payload, Charsets.UTF_8).take(50) } catch (e: Exception) { "…" }
                this.text = "📌 $text"
                isClickable = true
                setOnClickListener {
                    val idx = messageAdapter.currentList.indexOfFirst {
                        it is MessageListItem.MessageItem && it.message.id == msg.id
                    }
                    if (idx >= 0) binding.recyclerMessages.scrollToPosition(idx)
                }
            }
            binding.chipGroupPinned.addView(chip)
        }
    }

    companion object {
        private const val CHANNEL_EMERGENCY = "channel_sos"
        private const val CHANNEL_CLIPBOARD = "channel_clipboard"
        private const val NOTIF_ID_EMERGENCY = 1001
        private const val NOTIF_ID_CLIPBOARD = 1002
    }
}
