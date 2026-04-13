package com.turbomesh.computingmachine.mesh

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Handles chunked file and voice-note transfers over BLE mesh (features 4 & 5).
 *
 * Chunk binary layout (FILE_CHUNK / VOICE_CHUNK payload):
 *   Bytes  0-15  : transfer UUID (16 raw bytes)
 *   Bytes 16-17  : chunk index (uint16 big-endian, 0-based)
 *   Bytes 18-19  : total chunk count (uint16 big-endian)
 *   Bytes 20+    : chunk data
 *
 * Complete binary layout (FILE_COMPLETE / VOICE_COMPLETE payload):
 *   Bytes  0-15  : transfer UUID (16 raw bytes)
 *   Bytes 16+    : MIME type as UTF-8 (e.g. "image/jpeg", "audio/aac")
 */
class FileTransferManager {

    companion object {
        private const val TAG = "FileTransferManager"
        /** Maximum bytes of raw data per BLE chunk. */
        const val CHUNK_DATA_SIZE = 492
        private const val HEADER_SIZE = 20
    }

    data class CompletedTransfer(
        val transferId: String,
        val mimeType: String,
        val data: ByteArray,
        val isVoice: Boolean
    )

    private data class InboundTransfer(
        val id: String,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val isVoice: Boolean
    ) {
        val isComplete get() = chunks.size == totalChunks
        fun assemble(): ByteArray {
            val result = mutableListOf<Byte>()
            for (i in 0 until totalChunks) {
                result.addAll((chunks[i] ?: byteArrayOf()).toList())
            }
            return result.toByteArray()
        }
    }

    private val inboundTransfers = mutableMapOf<String, InboundTransfer>()

    /** Emits a completed inbound transfer when all chunks arrive. */
    private val _completedTransfers = MutableSharedFlow<CompletedTransfer>(extraBufferCapacity = 16)
    val completedTransfers: SharedFlow<CompletedTransfer> = _completedTransfers.asSharedFlow()

    /** Map of outbound transferId → progress (0.0–1.0). */
    private val _outboundProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val outboundProgress: StateFlow<Map<String, Float>> = _outboundProgress.asStateFlow()

    // -------------------------------------------------------------------------
    // Outbound
    // -------------------------------------------------------------------------

    /**
     * Splits [data] into BLE-safe chunks.
     * Returns a list of (opcode, payload) pairs ready to be wrapped in MeshMessages.
     * [isVoice] selects VOICE_CHUNK/VOICE_COMPLETE vs FILE_CHUNK/FILE_COMPLETE.
     */
    fun buildChunks(
        data: ByteArray,
        mimeType: String,
        isVoice: Boolean = false
    ): Pair<String, List<Pair<MeshMessageType, ByteArray>>> {
        val transferId = UUID.randomUUID()
        val idBytes = uuidToBytes(transferId)
        val chunks = data.toList().chunked(CHUNK_DATA_SIZE) { it.toByteArray() }
        val totalChunks = chunks.size
        val chunkType = if (isVoice) MeshMessageType.VOICE_CHUNK else MeshMessageType.FILE_CHUNK
        val completeType = if (isVoice) MeshMessageType.VOICE_COMPLETE else MeshMessageType.FILE_COMPLETE

        val messages = mutableListOf<Pair<MeshMessageType, ByteArray>>()
        chunks.forEachIndexed { index, chunkData ->
            val buf = ByteBuffer.allocate(HEADER_SIZE + chunkData.size)
            buf.put(idBytes)
            buf.putShort(index.toShort())
            buf.putShort(totalChunks.toShort())
            buf.put(chunkData)
            messages.add(chunkType to buf.array())
        }
        // Append COMPLETE message
        val completeBuf = ByteBuffer.allocate(16 + mimeType.length)
        completeBuf.put(idBytes)
        completeBuf.put(mimeType.toByteArray(Charsets.UTF_8))
        messages.add(completeType to completeBuf.array())

        Log.d(TAG, "Built ${chunks.size} chunks for ${mimeType} transfer ${transferId}")
        return transferId.toString() to messages
    }

    // -------------------------------------------------------------------------
    // Inbound
    // -------------------------------------------------------------------------

    /** Process an incoming FILE_CHUNK or VOICE_CHUNK payload. */
    fun onChunkReceived(payload: ByteArray, isVoice: Boolean) {
        if (payload.size < HEADER_SIZE) return
        val buf = ByteBuffer.wrap(payload)
        val idBytes = ByteArray(16).also { buf.get(it) }
        val chunkIndex = buf.short.toInt() and 0xFFFF
        val totalChunks = buf.short.toInt() and 0xFFFF
        val chunkData = ByteArray(buf.remaining()).also { buf.get(it) }

        val transferId = bytesToUuidString(idBytes)
        val transfer = inboundTransfers.getOrPut(transferId) {
            InboundTransfer(transferId, totalChunks, isVoice = isVoice)
        }
        transfer.chunks[chunkIndex] = chunkData
        Log.d(TAG, "Chunk $chunkIndex/$totalChunks for transfer $transferId")
    }

    /** Process an incoming FILE_COMPLETE or VOICE_COMPLETE payload. Returns true if transfer was finished. */
    suspend fun onCompleteReceived(payload: ByteArray, isVoice: Boolean): Boolean {
        if (payload.size < 16) return false
        val idBytes = payload.copyOfRange(0, 16)
        val mimeType = if (payload.size > 16) payload.copyOfRange(16, payload.size).decodeToString() else ""
        val transferId = bytesToUuidString(idBytes)
        val transfer = inboundTransfers[transferId] ?: return false
        if (!transfer.isComplete) {
            Log.w(TAG, "COMPLETE received but only ${transfer.chunks.size}/${transfer.totalChunks} chunks for $transferId")
            return false
        }
        val assembled = transfer.assemble()
        inboundTransfers.remove(transferId)
        _completedTransfers.emit(CompletedTransfer(transferId, mimeType, assembled, isVoice))
        Log.d(TAG, "Transfer $transferId complete: ${assembled.size} bytes, mime=$mimeType")
        return true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    private fun bytesToUuidString(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes)
        val msb = buf.long
        val lsb = buf.long
        return UUID(msb, lsb).toString()
    }
}
