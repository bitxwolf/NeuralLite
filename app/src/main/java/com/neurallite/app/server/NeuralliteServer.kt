package com.neurallite.app.server

import android.util.Log
import com.neurallite.app.engine.LlamaEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP server exposing an OpenAI-compatible REST API backed by
 * the on-device [LlamaEngine].
 *
 * Supported routes:
 * - `GET  /health`                → liveness probe
 * - `GET  /v1/models`             → model list (auth required)
 * - `POST /v1/chat/completions`   → chat completions (auth required)
 * - `POST /v1/completions`        → legacy completions (auth required)
 * - `OPTIONS *`                   → CORS preflight
 *
 * @param port     TCP port to listen on (default 8080).
 * @param apiToken Required Bearer token for `/v1/...` routes.
 */
class NeuralliteServer(
    port: Int = 8080,
    private val apiToken: String
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "NeuralliteServer"
        private const val MODEL_ID = "neurallite-local"
    }

    private val _events = MutableSharedFlow<ServerEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Metrics ─────────────────────────────────────────────────────────────

    private val startTime = AtomicLong(System.currentTimeMillis())
    private val requestCount = AtomicInteger(0)

    val uptimeSeconds: Int
        get() = ((System.currentTimeMillis() - startTime.get()) / 1000).toInt()

    val totalRequests: Int
        get() = requestCount.get()

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun start() {
        super.start()
        startTime.set(System.currentTimeMillis())
        emitEvent(ServerEvent.ServerStarted)
        Log.i(TAG, "Server started on port $listeningPort")
    }

    override fun stop() {
        super.stop()
        emitEvent(ServerEvent.ServerStopped)
        Log.i(TAG, "Server stopped")
    }

    // ── Router ──────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        requestCount.incrementAndGet()

        val uri = session.uri ?: "/"
        val method = session.method

        emitEvent(ServerEvent.RequestReceived(method.name, uri))

        return try {
            val startMs = System.currentTimeMillis()
            val response = when {
                method == Method.OPTIONS ->
                    corsWrap(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))

                method == Method.GET && uri == "/health" ->
                    handleHealth()

                method == Method.GET && uri == "/v1/models" ->
                    withAuth(session) { handleModels() }

                method == Method.POST && uri == "/v1/chat/completions" ->
                    withAuth(session) { handleChatCompletions(session) }

                method == Method.POST && uri == "/v1/completions" ->
                    withAuth(session) { handleCompletions(session) }

                else ->
                    corsWrap(jsonError(Response.Status.NOT_FOUND, "Not found: $uri"))
            }
            val durationMs = System.currentTimeMillis() - startMs
            emitEvent(ServerEvent.RequestCompleted(uri, response.status.requestStatus, durationMs))
            response
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error: ${e.message}", e)
            emitEvent(ServerEvent.ErrorOccurred(e.message ?: "Unknown error"))
            corsWrap(jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "Internal server error"))
        }
    }

    // ── GET /health ─────────────────────────────────────────────────────────

    private fun handleHealth(): Response {
        val modelName = if (LlamaEngine.isLoaded) {
            LlamaEngine.modelInfo.value ?: "loaded"
        } else null

        val json = JSONObject().apply {
            put("status", "ok")
            put("model", modelName ?: JSONObject.NULL)
            put("uptime_seconds", uptimeSeconds)
        }
        return corsWrap(jsonResponse(Response.Status.OK, json))
    }

    // ── GET /v1/models ──────────────────────────────────────────────────────

    private fun handleModels(): Response {
        val modelObj = JSONObject().apply {
            put("id", MODEL_ID)
            put("object", "model")
            put("created", startTime.get() / 1000)
            put("owned_by", "neurallite")
        }
        val json = JSONObject().apply {
            put("object", "list")
            put("data", JSONArray().put(modelObj))
        }
        return corsWrap(jsonResponse(Response.Status.OK, json))
    }

    // ── POST /v1/chat/completions ───────────────────────────────────────────

    private fun handleChatCompletions(session: IHTTPSession): Response {
        if (!LlamaEngine.isLoaded) {
            return corsWrap(jsonError(
                Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR,
                "No model loaded"
            ))
        }

        val body = parseBody(session)
        val reqJson = JSONObject(body)
        val messages = reqJson.getJSONArray("messages")
        val maxTokens = reqJson.optInt("max_tokens", 512)
        val temperature = reqJson.optDouble("temperature", 0.7).toFloat()
        val stream = reqJson.optBoolean("stream", false)

        // Build a simple text prompt from the messages array
        val promptBuilder = StringBuilder()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.getString("role")
            val content = msg.getString("content")
            promptBuilder.append("$role: $content\n")
        }
        promptBuilder.append("assistant: ")
        val prompt = promptBuilder.toString()

        return if (stream) {
            handleStreamingChat(prompt, maxTokens, temperature)
        } else {
            handleNonStreamingChat(prompt, maxTokens, temperature)
        }
    }

    /**
     * Non-streaming: run full inference, return a single JSON response.
     */
    private fun handleNonStreamingChat(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): Response {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(8)}"

        val result = runBlocking(Dispatchers.IO) {
            LlamaEngine.runInferenceSafe(prompt, maxTokens, temperature)
        }

        val json = JSONObject().apply {
            put("id", completionId)
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", MODEL_ID)
            put("choices", JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", result)
                    })
                    put("finish_reason", "stop")
                }
            ))
            put("usage", JSONObject().apply {
                put("prompt_tokens", -1)
                put("completion_tokens", -1)
                put("total_tokens", -1)
            })
        }

        return corsWrap(jsonResponse(Response.Status.OK, json))
    }

    /**
     * Streaming: SSE with `data: {...}` delta chunks, terminated by `data: [DONE]`.
     */
    private fun handleStreamingChat(
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): Response {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(8)}"

        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 16 * 1024)

        // Launch inference on a background thread — writes SSE chunks into the pipe
        scope.launch {
            try {
                runBlocking(Dispatchers.IO) {
                    LlamaEngine.runInferenceSafe(
                        prompt = prompt,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        onToken = { token ->
                            val chunk = JSONObject().apply {
                                put("id", completionId)
                                put("object", "chat.completion.chunk")
                                put("created", System.currentTimeMillis() / 1000)
                                put("model", MODEL_ID)
                                put("choices", JSONArray().put(
                                    JSONObject().apply {
                                        put("index", 0)
                                        put("delta", JSONObject().apply {
                                            put("content", token)
                                        })
                                        put("finish_reason", JSONObject.NULL)
                                    }
                                ))
                            }
                            val sseData = "data: ${chunk}\n\n"
                            pipedOut.write(sseData.toByteArray(Charsets.UTF_8))
                            pipedOut.flush()
                        }
                    )
                }

                // Send final [DONE] sentinel
                pipedOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error: ${e.message}", e)
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return corsWrap(response)
    }

    // ── POST /v1/completions ────────────────────────────────────────────────

    private fun handleCompletions(session: IHTTPSession): Response {
        if (!LlamaEngine.isLoaded) {
            return corsWrap(jsonError(
                Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR,
                "No model loaded"
            ))
        }

        val body = parseBody(session)
        val reqJson = JSONObject(body)
        val prompt = reqJson.getString("prompt")
        val maxTokens = reqJson.optInt("max_tokens", 512)
        val temperature = reqJson.optDouble("temperature", 0.7).toFloat()

        val completionId = "cmpl-${UUID.randomUUID().toString().take(8)}"

        val result = runBlocking(Dispatchers.IO) {
            LlamaEngine.runInferenceSafe(prompt, maxTokens, temperature)
        }

        val json = JSONObject().apply {
            put("id", completionId)
            put("object", "text_completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", MODEL_ID)
            put("choices", JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("text", result)
                    put("finish_reason", "stop")
                }
            ))
            put("usage", JSONObject().apply {
                put("prompt_tokens", -1)
                put("completion_tokens", -1)
                put("total_tokens", -1)
            })
        }

        return corsWrap(jsonResponse(Response.Status.OK, json))
    }

    // ── Auth helper ─────────────────────────────────────────────────────────

    private fun withAuth(session: IHTTPSession, handler: () -> Response): Response {
        val authHeader = session.headers["authorization"] ?: ""
        val token = authHeader.removePrefix("Bearer ").trim()

        return if (token == apiToken) {
            handler()
        } else {
            corsWrap(jsonError(Response.Status.UNAUTHORIZED, "unauthorized"))
        }
    }

    // ── CORS ────────────────────────────────────────────────────────────────

    private fun corsWrap(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Authorization,Content-Type")
    }

    // ── JSON utilities ──────────────────────────────────────────────────────

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", json.toString())

    private fun jsonError(status: Response.Status, message: String): Response {
        val json = JSONObject().apply { put("error", message) }
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    /**
     * NanoHTTPD requires you to parse the body into a map before reading it.
     * For POST requests with a JSON body this extracts the raw content.
     */
    private fun parseBody(session: IHTTPSession): String {
        val bodyMap = mutableMapOf<String, String>()
        session.parseBody(bodyMap)
        // NanoHTTPD puts the raw POST body under key "postData"
        return bodyMap["postData"] ?: ""
    }

    // ── Event emission ──────────────────────────────────────────────────────

    private fun emitEvent(event: ServerEvent) {
        scope.launch { _events.emit(event) }
    }
}
