package com.turbomesh.computingmachine.data.models

data class NetworkStats(
    val totalNodes: Int = 0,
    val connectedNodes: Int = 0,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val networkHealth: Float = 0f
)
