package com.neurallite.app.server

import java.util.UUID

/**
 * Events emitted by [NeuralliteServer] and [NeuralliteServerService] for UI observation.
 */
sealed class ServerEvent {
    data class RequestReceived(
        val method: String,
        val path: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) : ServerEvent()

    data class RequestCompleted(
        val id: String = UUID.randomUUID().toString(),
        val method: String,
        val path: String,
        val statusCode: Int,
        val durationMs: Long,
        val tokensGenerated: Int = 0,
        val timestampMs: Long = System.currentTimeMillis()
    ) : ServerEvent()

    object ServerStarted : ServerEvent()
    object ServerStopped : ServerEvent()
    data class ErrorOccurred(val message: String) : ServerEvent()
}
