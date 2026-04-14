package com.turbomesh.computingmachine.ui.messaging

import com.turbomesh.computingmachine.mesh.MeshMessage

/**
 * Items displayed in the message list. There are two types:
 * - [DateHeader]: a date-separator row (e.g. "Monday, Apr 13")
 * - [MessageItem]: a regular mesh message row
 */
sealed class MessageListItem {
    data class DateHeader(val label: String) : MessageListItem()
    data class MessageItem(val message: MeshMessage) : MessageListItem()
}
