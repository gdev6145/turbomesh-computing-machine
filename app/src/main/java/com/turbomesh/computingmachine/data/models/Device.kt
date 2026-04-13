package com.turbomesh.computingmachine.data.models

data class Device(
    val id: String,
    val name: String,
    val address: String,
    val rssi: Int,
    val isProvisioned: Boolean = false,
    val isConnected: Boolean = false
)
