package com.turbomesh.computingmachine.ui.messaging

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.turbomesh.computingmachine.data.MeshRepository
import com.turbomesh.computingmachine.mesh.MeshMessage
import com.turbomesh.computingmachine.mesh.MeshMessageType
import com.turbomesh.computingmachine.mesh.MeshNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagingViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeshRepository(application)

    /** Current search query; empty string means no filter. */
    val searchQuery = MutableStateFlow("")

    /**
     * Active channel tag filter. Null = show all channels.
     * A non-null value (e.g. "emergency") shows only messages prefixed with "#emergency".
     */
    val activeChannelFilter = MutableStateFlow<String?>(null)

    /** All unique channel tags seen so far across all messages. */
    val knownChannels: StateFlow<List<String>> = repository.allMessages
        .map { messages ->
            messages.mapNotNull { extractChannel(messageText(it)) }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** DB-backed messages filtered by search query and active channel. */
    val filteredMessages: StateFlow<List<MeshMessage>> = combine(
        repository.allMessages,
        searchQuery,
        activeChannelFilter
    ) { msgs, query, channel ->
        var result = msgs
        if (channel != null) {
            result = result.filter { extractChannel(messageText(it)) == channel }
        }
        if (query.isNotBlank()) {
            result = result.filter { msg ->
                val text = messageText(msg)
                text.contains(query, ignoreCase = true) ||
                        msg.sourceNodeId.contains(query, ignoreCase = true)
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableDestinations: StateFlow<List<String>> = combine(
        repository.provisionedNodes,
        repository.connectedDevices
    ) { provisioned, connected ->
        val destinations = mutableListOf(MeshMessage.BROADCAST_DESTINATION)
        destinations.addAll(connected.map { addr ->
            provisioned.firstOrNull { it.id == addr }?.displayName?.takeIf { it.isNotBlank() } ?: addr
        })
        destinations
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(MeshMessage.BROADCAST_DESTINATION))

    val connectedNodes: StateFlow<List<MeshNode>> = combine(
        repository.provisionedNodes,
        repository.connectedDevices
    ) { provisioned, connected ->
        provisioned.filter { it.id in connected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Emits the ID of a message whose delivery permanently failed. */
    val deliveryFailures: SharedFlow<String> = repository.deliveryFailures

    /**
     * Timestamp of the last time the user viewed the messaging screen.
     * Incoming messages with a timestamp newer than this are counted as unread.
     */
    private val _lastReadTimestamp = MutableStateFlow(System.currentTimeMillis())

    /** Number of incoming messages not yet read by the user. */
    val unreadCount: StateFlow<Int> = combine(
        repository.allMessages,
        _lastReadTimestamp
    ) { msgs, lastRead ->
        msgs.count { it.sourceNodeId != "self" && it.timestamp > lastRead }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Call when the user opens the messaging screen. Clears the unread count. */
    fun markAllRead() {
        _lastReadTimestamp.value = System.currentTimeMillis()
    }

    private var selectedDestination: String = MeshMessage.BROADCAST_DESTINATION

    fun selectDestination(destination: String) {
        selectedDestination = destination
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setChannelFilter(channel: String?) {
        activeChannelFilter.value = channel
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val message = MeshMessage(
                sourceNodeId = "self",
                destinationNodeId = selectedDestination,
                type = MeshMessageType.DATA,
                payload = text.toByteArray(Charsets.UTF_8)
            )
            repository.sendMessage(message)
        }
    }

    fun deleteMessage(id: String) {
        repository.deleteMessage(id)
    }

    fun clearMessages() {
        repository.clearMessages()
    }

    /**
     * Builds a human-readable export string for the currently visible messages.
     * Format: TSV with columns [time, from, to, type, hops, text].
     */
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
        repository.destroy()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun messageText(msg: MeshMessage): String = try {
        String(msg.payload, Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}

private val CHANNEL_PATTERN = Regex("^#(\\w+)\\s*")

/**
 * Extracts a channel tag from a message body.
 * A message is on a channel if it starts with `#word` (e.g. "#emergency hello").
 * Returns the tag without the `#`, or null if not tagged.
 */
fun extractChannel(text: String): String? =
    CHANNEL_PATTERN.find(text.trimStart())?.groupValues?.get(1)?.lowercase()
