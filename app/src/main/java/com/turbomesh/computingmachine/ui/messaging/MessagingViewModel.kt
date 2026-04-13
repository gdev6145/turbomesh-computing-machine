package com.turbomesh.computingmachine.ui.messaging

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.GroupStore
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.mesh.FileTransferManager
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launchimport java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    val searchQuery = MutableStateFlow("")
    val activeChannelFilter = MutableStateFlow<String?>(null)

    val knownChannels: StateFlow<List<String>> = repository.allMessages
        .map { messages ->
            messages.mapNotNull { extractChannel(messageText(it)) }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredMessages: StateFlow<List<MeshMessage>> = combine(
        repository.allMessages,
        searchQuery,
        activeChannelFilter
    ) { msgs, query, channel ->
        var result = msgs.filter { it.type == MeshMessageType.DATA || it.type == MeshMessageType.FILE_COMPLETE || it.type == MeshMessageType.VOICE_COMPLETE || it.type == MeshMessageType.REPLY }
        if (channel != null) result = result.filter { extractChannel(messageText(it)) == channel }
        if (query.isNotBlank()) {
            result = result.filter { msg ->
                val text = messageText(msg)
                text.contains(query, ignoreCase = true) || msg.sourceNodeId.contains(query, ignoreCase = true)
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Feature 3: Map of originalMessageId → list of (emoji, senderNodeId). */
    val reactionsByMessageId: StateFlow<Map<String, List<Pair<String, String>>>> = repository.allMessages
        .map { messages ->
            val map = mutableMapOf<String, MutableList<Pair<String, String>>>()
            messages.filter { it.type == MeshMessageType.REACTION }.forEach { msg ->
                val text = messageText(msg)
                val colonIdx = text.indexOf(':')
                if (colonIdx > 0) {
                    val origId = text.substring(0, colonIdx)
                    val emoji = text.substring(colonIdx + 1)
                    map.getOrPut(origId) { mutableListOf() }.add(emoji to msg.sourceNodeId)
                }
            }
            map
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val availableDestinations: StateFlow<List<String>> = combine(
        repository.provisionedNodes,
        repository.connectedDevices,
        repository.groups
    ) { provisioned, connected, groups ->
        val destinations = mutableListOf(MeshMessage.BROADCAST_DESTINATION)
        destinations.addAll(connected.map { addr ->
            provisioned.firstOrNull { it.id == addr }?.displayName?.takeIf { it.isNotBlank() } ?: addr
        })
        // Feature 16: Add groups as destinations prefixed with "group:"
        groups.forEach { group -> destinations.add("group:${group.id}:${group.name}") }
        destinations
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(MeshMessage.BROADCAST_DESTINATION))

    val connectedNodes: StateFlow<List<MeshNode>> = combine(
        repository.provisionedNodes,
        repository.connectedDevices
    ) { provisioned, connected ->
        provisioned.filter { it.id in connected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deliveryFailures: SharedFlow<String> = repository.deliveryFailures

    /** Feature 19: The message currently being replied to. */
    private val _replyTarget = MutableStateFlow<MeshMessage?>(null)
    val replyTarget: StateFlow<MeshMessage?> = _replyTarget

    /** Feature 22: Pinned messages. */
    val pinnedMessages: StateFlow<List<MeshMessage>> = repository.pinnedMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Feature 23: Scheduled send time (null = send immediately). */
    val scheduledAt = MutableStateFlow<Long?>(null)

    /** Feature 24: Duration in ms until expiry (null = no expiry). */
    val expiresInMs = MutableStateFlow<Long?>(null)

    /** Feature 14: Emergency SOS messages from remote peers. */
    val emergencyMessages: SharedFlow<Pair<String, String>> = repository.emergencyMessages

    /** Feature 17: Clipboard text from a remote peer. */
    val inboundClipboard: SharedFlow<String> = repository.inboundClipboard

    /** Feature 16: Group invitations from peers. */
    val groupInvites: SharedFlow<Triple<String, String, String>> = repository.groupInvites

    private val _lastReadTimestamp = MutableStateFlow(System.currentTimeMillis())

    val unreadCount: StateFlow<Int> = combine(
        repository.allMessages,
        _lastReadTimestamp
    ) { msgs, lastRead ->
        msgs.count { it.sourceNodeId != "self" && it.timestamp > lastRead && it.type == MeshMessageType.DATA }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun markAllRead() {
        _lastReadTimestamp.value = System.currentTimeMillis()
    }

    // -------------------------------------------------------------------------
    // Feature 1: Typing indicator
    // -------------------------------------------------------------------------

    /** Current set of node IDs that are typing (auto-cleared after 3s). */
    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers

    private val typingClearJobs = mutableMapOf<String, Job>()

    fun sendTypingIndicator() {
        repository.sendTypingIndicator()
    }

    private fun observeTypingPeers() {
        viewModelScope.launch {
            repository.typingNodeIds.collect { nodeId ->
                _typingPeers.value = _typingPeers.value + nodeId
                typingClearJobs[nodeId]?.cancel()
                typingClearJobs[nodeId] = viewModelScope.launch {
                    delay(3_000)
                    _typingPeers.value = _typingPeers.value - nodeId
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Feature 4/5: File and voice transfer
    // -------------------------------------------------------------------------

    /** Emitted with (transferId, mimeType, data) on completed inbound transfer. */
    private val _completedTransfer = MutableSharedFlow<FileTransferManager.CompletedTransfer>(extraBufferCapacity = 8)
    val completedTransfers: SharedFlow<FileTransferManager.CompletedTransfer> = _completedTransfer.asSharedFlow()

    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    val isRecording = MutableStateFlow(false)

    fun startVoiceRecording() {
        if (isRecording.value) return
        val dir = getApplication<Application>().cacheDir
        voiceFile = File(dir, "voice_${System.currentTimeMillis()}.aac")
        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(getApplication())
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setOutputFile(voiceFile!!.absolutePath)
            prepare()
            start()
        }
        isRecording.value = true
    }

    fun stopVoiceRecordingAndSend() {
        if (!isRecording.value) return
        recorder?.apply { stop(); release() }
        recorder = null
        isRecording.value = false
        val file = voiceFile ?: return
        viewModelScope.launch {
            val data = file.readBytes()
            file.delete()
            val (transferId, chunks) = repository.buildFileChunks(data, "audio/aac", isVoice = true)
            chunks.forEach { (msgType, payload) ->
                val msg = MeshMessage(
                    sourceNodeId = "self",
                    destinationNodeId = selectedDestination,
                    type = msgType,
                    payload = payload
                )
                repository.sendMessage(msg)
            }
        }
    }

    fun sendFile(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.readBytes() ?: return@launch
            val (transferId, chunks) = repository.buildFileChunks(bytes, mimeType, isVoice = false)
            chunks.forEach { (msgType, payload) ->
                val msg = MeshMessage(
                    sourceNodeId = "self",
                    destinationNodeId = selectedDestination,
                    type = msgType,
                    payload = payload
                )
                repository.sendMessage(msg)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Feature 14: Emergency SOS
    // -------------------------------------------------------------------------

    fun sendEmergency(text: String) {
        repository.sendEmergency(text)
    }

    // -------------------------------------------------------------------------
    // Feature 17: Shared clipboard
    // -------------------------------------------------------------------------

    fun sendClipboard(text: String) {
        repository.sendClipboard(text)
    }

    // -------------------------------------------------------------------------
    // Feature 16: Groups
    // -------------------------------------------------------------------------

    fun createGroup(name: String, members: List<String>) = repository.createGroup(name, members)

    // -------------------------------------------------------------------------
    // Feature 3: Reactions
    // -------------------------------------------------------------------------

    fun sendReaction(originalMessageId: String, emoji: String) {
        val dest = selectedDestinationNodeId ?: return
        repository.sendReaction(originalMessageId, emoji, dest)
    }

    // -------------------------------------------------------------------------
    // Standard messaging
    // -------------------------------------------------------------------------

    private var selectedDestination: String = MeshMessage.BROADCAST_DESTINATION
    private var selectedDestinationNodeId: String? = null

    fun selectDestination(destination: String) {
        selectedDestination = destination
        selectedDestinationNodeId = connectedNodes.value.firstOrNull { n ->
            n.displayName == destination || n.id == destination
        }?.id
    }

    fun setSearchQuery(query: String) { searchQuery.value = query }
    fun setChannelFilter(channel: String?) { activeChannelFilter.value = channel }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val replyMsg = _replyTarget.value
            val msgType = if (replyMsg != null) MeshMessageType.REPLY else MeshMessageType.DATA
            val msgPayload = if (replyMsg != null) {
                "${replyMsg.id}:$text".toByteArray(Charsets.UTF_8)
            } else {
                text.toByteArray(Charsets.UTF_8)
            }
            val replyToId = replyMsg?.id
            val schedAt = scheduledAt.value
            val expAt = expiresInMs.value?.let { System.currentTimeMillis() + it }

            if (selectedDestination.startsWith("group:")) {
                val parts = selectedDestination.split(":")
                val groupId = parts.getOrElse(1) { "" }
                val group = repository.getGroup(groupId) ?: return@launch
                group.members.forEach { memberId ->
                    val msg = MeshMessage(
                        sourceNodeId = "self",
                        destinationNodeId = memberId,
                        type = msgType,
                        payload = msgPayload,
                        replyToMsgId = replyToId,
                        scheduledAtMs = schedAt,
                        expiresAtMs = expAt,
                        pendingDelivery = schedAt != null
                    )
                    repository.sendMessage(msg)
                }
            } else {
                val msg = MeshMessage(
                    sourceNodeId = "self",
                    destinationNodeId = selectedDestination,
                    type = msgType,
                    payload = msgPayload,
                    replyToMsgId = replyToId,
                    scheduledAtMs = schedAt,
                    expiresAtMs = expAt,
                    pendingDelivery = schedAt != null
                )
                repository.sendMessage(msg)
            }
            _replyTarget.value = null
            scheduledAt.value = null
            expiresInMs.value = null
        }
    }

    fun deleteMessage(id: String) = repository.deleteMessage(id)
    fun clearMessages() = repository.clearMessages()

    /** Feature 19: Set message as reply target. */
    fun replyTo(msg: MeshMessage) { _replyTarget.value = msg }

    /** Feature 19: Clear the current reply target. */
    fun clearReply() { _replyTarget.value = null }

    /** Feature 20: Edit own message. */
    fun editMessage(id: String, newText: String) {
        viewModelScope.launch {
            val payload = newText.toByteArray(Charsets.UTF_8)
            repository.markEdited(id, payload, System.currentTimeMillis())
            val dest = selectedDestinationNodeId ?: selectedDestination
            val msg = MeshMessage(
                sourceNodeId = "self",
                destinationNodeId = dest,
                type = MeshMessageType.EDIT,
                payload = "$id:$newText".toByteArray(Charsets.UTF_8)
            )
            repository.sendMessage(msg)
        }
    }

    /** Feature 21: Delete message for everyone. */
    fun recallMessage(id: String) {
        viewModelScope.launch {
            repository.markDeleted(id, System.currentTimeMillis())
            val dest = selectedDestinationNodeId ?: selectedDestination
            val msg = MeshMessage(
                sourceNodeId = "self",
                destinationNodeId = dest,
                type = MeshMessageType.DELETE,
                payload = id.toByteArray(Charsets.UTF_8)
            )
            repository.sendMessage(msg)
        }
    }

    /** Feature 22: Toggle pinned state. */
    fun togglePin(msg: MeshMessage) {
        repository.setPinned(msg.id, !msg.isPinned)
    }

    fun buildExportText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("time\tfrom\tto\ttype\thops\ttext")
        filteredMessages.value.forEach { msg ->
            sb.appendLine(
                "${fmt.format(Date(msg.timestamp))}\t" +
                "${msg.sourceNodeId}\t" +
                "${msg.destinationNodeId}\t" +
                "${msg.type.name}\t" +
                "${msg.hopCount}\t" +
                messageText(msg).replace("\t", " ").replace("\n", " ")
            )
        }
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        recorder?.release()
        recorder = null
        repository.destroy()
    }

    private fun messageText(msg: MeshMessage): String = try {
        String(msg.payload, Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    init {
        observeTypingPeers()
        observeCompletedTransfers()
    }

    private fun observeCompletedTransfers() {
        viewModelScope.launch {
            repository.completedTransfers.collect { transfer ->
                _completedTransfer.emit(transfer)
            }
        }
    }
}

private val CHANNEL_PATTERN = Regex("^#(\\w+)\\s*")

fun extractChannel(text: String): String? =
    CHANNEL_PATTERN.find(text.trimStart())?.groupValues?.get(1)?.lowercase()

