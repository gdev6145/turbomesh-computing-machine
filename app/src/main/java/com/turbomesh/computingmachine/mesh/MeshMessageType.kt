package com.turbomesh.computingmachine.mesh

enum class MeshMessageType(val opcode: Byte) {
    DATA(0x01),
    CONTROL(0x02),
    ACK(0x03),
    HEARTBEAT(0x04),
    NETWORK_INFO(0x05),
    BROADCAST(0x06);

    companion object {
        fun fromOpcode(opcode: Byte): MeshMessageType? =
            values().firstOrNull { it.opcode == opcode }
    }
}
