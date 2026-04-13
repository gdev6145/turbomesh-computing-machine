package com.turbomesh.computingmachine.mesh

data class MeshNode(
    val id: String,
    val name: String,
    val rssi: Int,
    val isProvisioned: Boolean = false,
    val isConnected: Boolean = false,
    val address: String
)
