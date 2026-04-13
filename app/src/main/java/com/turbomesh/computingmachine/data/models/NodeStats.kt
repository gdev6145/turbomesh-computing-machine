package com.turbomesh.computingmachine.data.models

/**
 * Per-node message traffic counters.
 */
data class NodeStats(
    val sent: Int = 0,
    val received: Int = 0
)
