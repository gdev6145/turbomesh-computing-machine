package com.turbomesh.computingmachine.mesh

enum class MeshMessageType(val opcode: Byte) {
    DATA(0x01),
    CONTROL(0x02),
    ACK(0x03),
    HEARTBEAT(0x04),
    NETWORK_INFO(0x05),
    BROADCAST(0x06),
    /** Typing-indicator pulse (TTL=1, not stored in DB). */
    TYPING(0x07),
    /** Read receipt sent back to message originator. */
    READ(0x08),
    /** Emoji reaction; payload = "<original_msg_id>:<emoji>". */
    REACTION(0x09),
    /** One chunk of a multi-part file or voice transfer. */
    FILE_CHUNK(0x0A),
    /** Signals the end of a file transfer. */
    FILE_COMPLETE(0x0B),
    /** Routing-table advertisement; payload = comma-separated nodeIds. */
    ROUTE_ADV(0x0C),
    /** Battery / sensor telemetry TLV payload. */
    TELEMETRY(0x0D),
    /** High-priority emergency SOS broadcast (TTL=15). */
    EMERGENCY(0x0E),
    /** Group invitation; payload = "<groupUuid>:<groupName>". */
    GROUP_INVITE(0x0F),
    /** Shared clipboard text. */
    CLIPBOARD(0x10),
    /** One chunk of a voice-note transfer. */
    VOICE_CHUNK(0x11),
    /** Signals the end of a voice-note transfer. */
    VOICE_COMPLETE(0x12);

    companion object {
        fun fromOpcode(opcode: Byte): MeshMessageType? =
            values().firstOrNull { it.opcode == opcode }
    }
}
