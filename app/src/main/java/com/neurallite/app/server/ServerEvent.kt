package com.neurallite.app.server

/**
 * Events emitted by [NeuralliteServer] for UI observation.
 *
 * Collected via [NeuralliteServer.events] SharedFlow and forwarded
 * to the ViewModel layer for display in the Server tab.
 */
sealed class ServerEvent {
    data class RequestReceived(
        val method: String,
        val path: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) : ServerEvent()

    data class RequestCompleted(
        val path: String,
        val statusCode: Int,
        val durationMs: Long,
        val tokensGenerated: Int = 0
    ) : ServerEvent()

    object ServerStarted : ServerEvent()
    object ServerStopped : ServerEvent()
    data class ErrorOccurred(val message: String) : ServerEvent()
}
