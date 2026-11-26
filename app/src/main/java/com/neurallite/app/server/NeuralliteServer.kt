package com.neurallite.app.server

import android.util.Log
import com.neurallite.app.engine.InferenceResult
import com.neurallite.app.engine.LlamaEngine
import com.neurallite.app.models.ChatMessage
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.models.ModelInfo
import com.neurallite.app.models.PromptTemplate
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Abstraction over the inference engine so [NeuralliteServer] can run both against
 * [LlamaEngine] on Android and deterministic fakes in JVM unit tests.
 */
interface InferenceBackend {
    val isLoaded: Boolean
    val modelInfoJson: String?
    val activeModel: ModelInfo?

    suspend fun runInference(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        cancelToken: AtomicBoolean,
        onToken: (String) -> Unit
    ): InferenceResult

    fun stopCurrentInference()

    fun stopInferenceIfMatching(cancelToken: AtomicBoolean)
}

/**
 * Production [InferenceBackend] delegating to [LlamaEngine].
 */
object LlamaInferenceBackend : InferenceBackend {
    override val isLoaded: Boolean
        get() = LlamaEngine.isLoaded

    override val modelInfoJson: String?
        get() = LlamaEngine.modelInfo.value

    override val activeModel: ModelInfo?
        get() = LlamaEngine.loadedModelId?.let { ModelCatalog.getModelById(it) }

    override suspend fun runInference(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        cancelToken: AtomicBoolean,
        onToken: (String) -> Unit
    ): InferenceResult = LlamaEngine.runInferenceSafe(
        prompt = prompt,
        maxTokens = maxTokens,
        temperature = temperature,
        parseSpecial = parseSpecial,
        cancelToken = cancelToken,
        onToken = onToken
    )

    override fun stopCurrentInference() {
        LlamaEngine.stopCurrentInference()
    }

    override fun stopInferenceIfMatching(cancelToken: AtomicBoolean) {
        LlamaEngine.stopInferenceIfMatching(cancelToken)
    }
}

/**
 * Incremental stop-sequence filter that holds back only a suffix matching a prefix
 * of any configured stop string, ensuring zero buffering latency for normal tokens.
 */
internal class StopSequenceFilter(stopStrings: List<String>) {
    private val stops = stopStrings.filter { it.isNotEmpty() }.distinct()
    private val pending = StringBuilder()

    data class FilterOutput(
        val textToEmit: String,
        val matchedStop: Boolean
    )

    fun push(chunk: String): FilterOutput {
        if (chunk.isEmpty()) return FilterOutput("", false)
        if (stops.isEmpty()) return FilterOutput(chunk, false)

        pending.append(chunk)
        val buffer = pending.toString()

        var earliestIdx = -1
        for (stop in stops) {
            val idx = buffer.indexOf(stop)
            if (idx >= 0 && (earliestIdx < 0 || idx < earliestIdx)) {
                earliestIdx = idx
            }
        }

        if (earliestIdx >= 0) {
            val emit = buffer.substring(0, earliestIdx)
            pending.clear()
            return FilterOutput(emit, matchedStop = true)
        }

        val holdLen = longestSuffixMatchingPrefix(buffer)
        val emitLen = buffer.length - holdLen
        return if (emitLen > 0) {
            val emit = buffer.substring(0, emitLen)
            pending.delete(0, emitLen)
            FilterOutput(emit, matchedStop = false)
        } else {
            FilterOutput("", matchedStop = false)
        }
    }

    fun flush(): String {
        if (pending.isEmpty()) return ""
        val remaining = pending.toString()
        pending.clear()
        return remaining
    }

    private fun longestSuffixMatchingPrefix(buffer: String): Int {
        var maxHold = 0
        for (stop in stops) {
            val maxPossible = minOf(buffer.length, stop.length - 1)
            for (len in maxPossible downTo (maxHold + 1)) {
                if (buffer.endsWith(stop.substring(0, len))) {
                    maxHold = len
                    break
                }
            }
        }
        return maxHold
    }
}

/**
 * Local HTTP server exposing an OpenAI-compatible REST API backed by [InferenceBackend].
 */
class NeuralliteServer(
    port: Int = 8080,
    @Volatile var apiToken: String,
    @Volatile var corsEnabled: Boolean = true,
    @Volatile var defaultTemperature: Float = 0.7f,
    @Volatile var defaultMaxTokens: Int = 512,
    private val backend: InferenceBackend = LlamaInferenceBackend,
    private val onEvent: ((ServerEvent) -> Unit)? = null
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "NeuralliteServer"
        private const val MODEL_ID = "neurallite-local"
        private const val MIME_EVENT_STREAM = "text/event-stream"
        private const val MAX_REQUEST_BODY_BYTES = 4 * 1024 * 1024L // 4 MiB
        private const val MIN_POSITIVE_TEMPERATURE = 1e-4f
        private const val MAX_TEMPERATURE = 2.0f
    }

    private class BadRequestException(message: String) : Exception(message)
    private class PayloadTooLargeException(message: String) : Exception(message)

    private val _events = MutableSharedFlow<ServerEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Metrics ─────────────────────────────────────────────────────────────

    private val startTime = AtomicLong(System.currentTimeMillis())
    private val requestCount = AtomicInteger(0)

    val uptimeSeconds: Int
        get() = ((System.currentTimeMillis() - startTime.get()) / 1000).toInt()

    val totalRequests: Int
        get() = requestCount.get()

    init {
        setServerSocketFactory {
            ServerSocket().apply {
                reuseAddress = true
            }
        }
    }

    // ── NanoHTTPD overrides ─────────────────────────────────────────────────

    /**
     * Disable GZIP compression for SSE (`text/event-stream`) so NanoHTTPD streams
     * chunks immediately instead of buffering inside a GZIPOutputStream.
     */
    override fun useGzipWhenAccepted(r: Response): Boolean {
        val mime = r.mimeType
        if (mime != null && mime.startsWith(MIME_EVENT_STREAM, ignoreCase = true)) {
            return false
        }
        return super.useGzipWhenAccepted(r)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun start() {
        super.start()
        startTime.set(System.currentTimeMillis())
        emitEvent(ServerEvent.ServerStarted)
        logInfo("Server started on port $listeningPort")
    }

    override fun stop() {
        try {
            backend.stopCurrentInference()
        } catch (_: Exception) {
        }
        scope.cancel()
        super.stop()
        emitEvent(ServerEvent.ServerStopped)
        logInfo("Server stopped")
    }

    // ── Router ──────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        requestCount.incrementAndGet()

        val uri = session.uri ?: "/"
        val method = session.method
        val startMs = System.currentTimeMillis()

        emitEvent(ServerEvent.RequestReceived(method.name, uri, startMs))

        var generatedTokens = 0
        var isStreamingSuccess = false
        var requestBodyConsumed = false
        var bodyReadStarted = false

        return try {
            // Validate Content-Length up front for all routes (including GET/OPTIONS/401/503/404)
            // so malformed/negative values never leak into keep-alive draining or HTTPSession.bodySize.
            parseContentLengthHeader(session)

            val response = when {
                method == Method.OPTIONS ->
                    corsWrap(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))

                method == Method.GET && uri == "/health" ->
                    handleHealth()

                method == Method.GET && uri == "/v1/models" ->
                    withAuth(session) { handleModels() }

                method == Method.GET && uri == "/v1/models/$MODEL_ID" ->
                    withAuth(session) { handleSingleModel() }

                method == Method.POST && uri == "/v1/chat/completions" ->
                    withAuth(session) {
                        val (resp, tokens, streaming) = handleChatCompletions(
                            session = session,
                            requestStartMs = startMs,
                            onBodyReadStarted = { bodyReadStarted = true },
                            onBodyConsumed = { requestBodyConsumed = true }
                        )
                        generatedTokens = tokens
                        isStreamingSuccess = streaming
                        resp
                    }

                method == Method.POST && uri == "/v1/completions" ->
                    withAuth(session) {
                        val (resp, tokens, streaming) = handleCompletions(
                            session = session,
                            requestStartMs = startMs,
                            onBodyReadStarted = { bodyReadStarted = true },
                            onBodyConsumed = { requestBodyConsumed = true }
                        )
                        generatedTokens = tokens
                        isStreamingSuccess = streaming
                        resp
                    }

                else ->
                    corsWrap(jsonError(Response.Status.NOT_FOUND, "Not found: $uri"))
            }

            if (!requestBodyConsumed) {
                finalizeKeepAlive(session, response)
            }

            if (!isStreamingSuccess) {
                val durationMs = System.currentTimeMillis() - startMs
                emitEvent(
                    ServerEvent.RequestCompleted(
                        method = method.name,
                        path = uri,
                        statusCode = response.status.requestStatus,
                        durationMs = durationMs,
                        tokensGenerated = generatedTokens
                    )
                )
            }
            response
        } catch (e: BadRequestException) {
            val resp = corsWrap(jsonError(Response.Status.BAD_REQUEST, e.message ?: "Bad request"))
            if (!requestBodyConsumed) {
                if (bodyReadStarted) {
                    resp.addHeader("Connection", "close")
                } else {
                    finalizeKeepAlive(session, resp)
                }
            }
            emitEvent(
                ServerEvent.RequestCompleted(
                    method = method.name,
                    path = uri,
                    statusCode = 400,
                    durationMs = System.currentTimeMillis() - startMs
                )
            )
            resp
        } catch (e: PayloadTooLargeException) {
            val status = Response.Status.lookup(413) ?: Response.Status.BAD_REQUEST
            val resp = corsWrap(jsonError(status, e.message ?: "Payload too large"))
            resp.addHeader("Connection", "close")
            emitEvent(
                ServerEvent.RequestCompleted(
                    method = method.name,
                    path = uri,
                    statusCode = status.requestStatus,
                    durationMs = System.currentTimeMillis() - startMs
                )
            )
            resp
        } catch (e: JSONException) {
            val resp = corsWrap(jsonError(Response.Status.BAD_REQUEST, "Invalid JSON: ${e.message}"))
            if (!requestBodyConsumed) {
                if (bodyReadStarted) {
                    resp.addHeader("Connection", "close")
                } else {
                    finalizeKeepAlive(session, resp)
                }
            }
            emitEvent(
                ServerEvent.RequestCompleted(
                    method = method.name,
                    path = uri,
                    statusCode = 400,
                    durationMs = System.currentTimeMillis() - startMs
                )
            )
            resp
        } catch (e: Exception) {
            logError("Unhandled error: ${e.message}", e)
            emitEvent(ServerEvent.ErrorOccurred(e.message ?: "Unknown error"))
            val resp = corsWrap(
                jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "Internal server error")
            )
            if (e is IOException || (bodyReadStarted && !requestBodyConsumed)) {
                resp.addHeader("Connection", "close")
            } else if (!requestBodyConsumed) {
                finalizeKeepAlive(session, resp)
            }
            emitEvent(
                ServerEvent.RequestCompleted(
                    method = method.name,
                    path = uri,
                    statusCode = 500,
                    durationMs = System.currentTimeMillis() - startMs
                )
            )
            resp
        }
    }

    // ── GET /health ─────────────────────────────────────────────────────────

    private fun handleHealth(): Response {
        val modelName = if (backend.isLoaded) {
            backend.modelInfoJson ?: backend.activeModel?.displayName ?: "loaded"
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

    private fun handleSingleModel(): Response {
        val modelObj = JSONObject().apply {
            put("id", MODEL_ID)
            put("object", "model")
            put("created", startTime.get() / 1000)
            put("owned_by", "neurallite")
        }
        return corsWrap(jsonResponse(Response.Status.OK, modelObj))
    }

    // ── POST /v1/chat/completions ───────────────────────────────────────────

    private data class HandlerResult(
        val response: Response,
        val tokensGenerated: Int = 0,
        val isStreaming: Boolean = false
    )

    private fun handleChatCompletions(
        session: IHTTPSession,
        requestStartMs: Long,
        onBodyReadStarted: () -> Unit,
        onBodyConsumed: () -> Unit
    ): HandlerResult {
        if (!backend.isLoaded) {
            val status = Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR
            return HandlerResult(corsWrap(jsonError(status, "No model loaded")))
        }

        val reqJson = parseJsonBody(session, onBodyReadStarted, onBodyConsumed)
        val messagesJson = reqJson.optJSONArray("messages")
            ?: throw BadRequestException("Missing 'messages' array")
        if (messagesJson.length() == 0) {
            throw BadRequestException("'messages' array must not be empty")
        }

        val chatMessages = parseChatMessages(messagesJson)
        val maxTokens = parseMaxTokens(reqJson)
        val temperature = parseTemperature(reqJson)
        val stream = reqJson.optBoolean("stream", false)
        val includeUsage = reqJson.optJSONObject("stream_options")?.optBoolean("include_usage", false) == true
        val clientStops = parseStopStrings(reqJson)

        val template = backend.activeModel?.promptTemplate ?: PromptTemplate.ZEPHYR
        val prompt = template.formatChat(chatMessages)
        val stopStrings = (template.stopStrings + clientStops).distinct()

        return if (stream) {
            val resp = handleStreamingResponse(
                session = session,
                requestPath = "/v1/chat/completions",
                methodName = session.method.name,
                requestStartMs = requestStartMs,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                parseSpecial = template.parseSpecial,
                stopStrings = stopStrings,
                isChat = true,
                includeUsage = includeUsage
            )
            HandlerResult(resp, tokensGenerated = 0, isStreaming = resp.status == Response.Status.OK)
        } else {
            val (resp, tokens) = handleNonStreamingChat(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                parseSpecial = template.parseSpecial,
                stopStrings = stopStrings
            )
            HandlerResult(resp, tokensGenerated = tokens, isStreaming = false)
        }
    }

    private fun handleNonStreamingChat(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        stopStrings: List<String>
    ): Pair<Response, Int> {
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        val execution = executeFilteredInference(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            parseSpecial = parseSpecial,
            stopStrings = stopStrings
        )

        if (execution.finishReason == "context_overflow") {
            throw BadRequestException("Prompt (${execution.promptTokens} tokens) exceeds context window")
        }
        if (execution.finishReason == "error") {
            return corsWrap(jsonError(Response.Status.INTERNAL_ERROR, "Inference failed")) to 0
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
                        put("content", execution.text)
                    })
                    put("finish_reason", execution.finishReason)
                }
            ))
            put("usage", JSONObject().apply {
                put("prompt_tokens", execution.promptTokens)
                put("completion_tokens", execution.completionTokens)
                put("total_tokens", execution.promptTokens + execution.completionTokens)
            })
        }

        return corsWrap(jsonResponse(Response.Status.OK, json)) to execution.completionTokens
    }

    // ── POST /v1/completions ────────────────────────────────────────────────

    private fun handleCompletions(
        session: IHTTPSession,
        requestStartMs: Long,
        onBodyReadStarted: () -> Unit,
        onBodyConsumed: () -> Unit
    ): HandlerResult {
        if (!backend.isLoaded) {
            val status = Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR
            return HandlerResult(corsWrap(jsonError(status, "No model loaded")))
        }

        val reqJson = parseJsonBody(session, onBodyReadStarted, onBodyConsumed)
        val rawPrompt = reqJson.opt("prompt")
        val prompt = when (rawPrompt) {
            is String -> rawPrompt
            is JSONArray -> {
                if (rawPrompt.length() == 0) throw BadRequestException("'prompt' array must not be empty")
                rawPrompt.optNonNullStringOrNull(0)
                    ?: throw BadRequestException("Missing or invalid 'prompt' field")
            }
            else -> throw BadRequestException("Missing or invalid 'prompt' field")
        }

        val maxTokens = parseMaxTokens(reqJson)
        val temperature = parseTemperature(reqJson)
        val stream = reqJson.optBoolean("stream", false)
        val includeUsage = reqJson.optJSONObject("stream_options")?.optBoolean("include_usage", false) == true
        val clientStops = parseStopStrings(reqJson)

        return if (stream) {
            val resp = handleStreamingResponse(
                session = session,
                requestPath = "/v1/completions",
                methodName = session.method.name,
                requestStartMs = requestStartMs,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                parseSpecial = false,
                stopStrings = clientStops,
                isChat = false,
                includeUsage = includeUsage
            )
            HandlerResult(resp, tokensGenerated = 0, isStreaming = resp.status == Response.Status.OK)
        } else {
            val (resp, tokens) = handleNonStreamingCompletion(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                stopStrings = clientStops
            )
            HandlerResult(resp, tokensGenerated = tokens, isStreaming = false)
        }
    }

    private fun handleNonStreamingCompletion(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stopStrings: List<String>
    ): Pair<Response, Int> {
        val completionId = "cmpl-${UUID.randomUUID().toString().take(8)}"
        val execution = executeFilteredInference(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            parseSpecial = false,
            stopStrings = stopStrings
        )

        if (execution.finishReason == "context_overflow") {
            throw BadRequestException("Prompt (${execution.promptTokens} tokens) exceeds context window")
        }
        if (execution.finishReason == "error") {
            return corsWrap(jsonError(Response.Status.INTERNAL_ERROR, "Inference failed")) to 0
        }

        val json = JSONObject().apply {
            put("id", completionId)
            put("object", "text_completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", MODEL_ID)
            put("choices", JSONArray().put(
                JSONObject().apply {
                    put("index", 0)
                    put("text", execution.text)
                    put("finish_reason", execution.finishReason)
                }
            ))
            put("usage", JSONObject().apply {
                put("prompt_tokens", execution.promptTokens)
                put("completion_tokens", execution.completionTokens)
                put("total_tokens", execution.promptTokens + execution.completionTokens)
            })
        }

        return corsWrap(jsonResponse(Response.Status.OK, json)) to execution.completionTokens
    }

    // ── Shared filtered inference & streaming ───────────────────────────────

    private fun executeFilteredInference(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        stopStrings: List<String>
    ): InferenceResult {
        val filter = StopSequenceFilter(stopStrings)
        val cancelToken = AtomicBoolean(false)
        val output = StringBuilder()
        var stoppedBySequence = false

        val rawResult = runBlocking(Dispatchers.IO) {
            backend.runInference(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                parseSpecial = parseSpecial,
                cancelToken = cancelToken
            ) { token ->
                val filtered = filter.push(token)
                if (filtered.textToEmit.isNotEmpty()) {
                    output.append(filtered.textToEmit)
                }
                if (filtered.matchedStop) {
                    stoppedBySequence = true
                    backend.stopInferenceIfMatching(cancelToken)
                }
            }
        }

        if (!stoppedBySequence) {
            val tail = filter.flush()
            if (tail.isNotEmpty()) {
                output.append(tail)
            }
        }

        val effectiveReason = when {
            stoppedBySequence -> "stop"
            else -> rawResult.finishReason
        }
        val finalText = if (output.isNotEmpty() || stopStrings.isNotEmpty() || rawResult.text.isEmpty()) {
            output.toString()
        } else {
            rawResult.text
        }

        return InferenceResult(
            text = finalText,
            promptTokens = rawResult.promptTokens,
            completionTokens = rawResult.completionTokens,
            finishReason = effectiveReason
        )
    }

    private fun handleStreamingResponse(
        session: IHTTPSession,
        requestPath: String,
        methodName: String,
        requestStartMs: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        stopStrings: List<String>,
        isChat: Boolean,
        includeUsage: Boolean = false
    ): Response {
        if (!scope.isActive) {
            val status = Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR
            val errResp = corsWrap(jsonError(status, "Server is stopping"))
            errResp.addHeader("Connection", "close")
            return errResp
        }

        val completionId = if (isChat) {
            "chatcmpl-${UUID.randomUUID().toString().take(8)}"
        } else {
            "cmpl-${UUID.randomUUID().toString().take(8)}"
        }

        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 16 * 1024)

        val job = scope.launch {
            val cancelToken = AtomicBoolean(false)
            val filter = StopSequenceFilter(stopStrings)
            var stoppedBySequence = false
            var pipeBroken = false
            var completionTokens = 0
            var finalStatusCode = 200

            fun writeSsePayload(payload: String): Boolean {
                if (pipeBroken) return false
                return try {
                    pipedOut.write(payload.toByteArray(Charsets.UTF_8))
                    pipedOut.flush()
                    true
                } catch (e: IOException) {
                    pipeBroken = true
                    logInfo("SSE client disconnected: ${e.message}")
                    backend.stopInferenceIfMatching(cancelToken)
                    false
                }
            }

            fun writeChunk(text: String?, finishReason: String?) {
                val chunk = JSONObject().apply {
                    put("id", completionId)
                    put("object", if (isChat) "chat.completion.chunk" else "text_completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", MODEL_ID)
                    put("choices", JSONArray().put(
                        JSONObject().apply {
                            put("index", 0)
                            if (isChat) {
                                put("delta", JSONObject().apply {
                                    if (text != null) put("content", text)
                                })
                            } else {
                                put("text", text ?: "")
                            }
                            put("finish_reason", finishReason ?: JSONObject.NULL)
                        }
                    ))
                }
                writeSsePayload("data: $chunk\n\n")
            }

            fun writeUsageChunk(promptTokens: Int, genTokens: Int) {
                val chunk = JSONObject().apply {
                    put("id", completionId)
                    put("object", if (isChat) "chat.completion.chunk" else "text_completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", MODEL_ID)
                    put("choices", JSONArray())
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", promptTokens)
                        put("completion_tokens", genTokens)
                        put("total_tokens", promptTokens + genTokens)
                    })
                }
                writeSsePayload("data: $chunk\n\n")
            }

            fun writeSseError(message: String, type: String) {
                val errObj = JSONObject().apply {
                    put("error", JSONObject().apply {
                        put("message", message)
                        put("type", type)
                    })
                }
                writeSsePayload("data: $errObj\n\n")
                writeSsePayload("data: [DONE]\n\n")
            }

            try {
                val result = backend.runInference(
                    prompt = prompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    parseSpecial = parseSpecial,
                    cancelToken = cancelToken
                ) { token ->
                    if (pipeBroken) {
                        backend.stopInferenceIfMatching(cancelToken)
                        return@runInference
                    }
                    val filtered = filter.push(token)
                    if (filtered.textToEmit.isNotEmpty()) {
                        writeChunk(filtered.textToEmit, null)
                    }
                    if (filtered.matchedStop) {
                        stoppedBySequence = true
                        backend.stopInferenceIfMatching(cancelToken)
                    }
                }

                completionTokens = result.completionTokens

                if (!pipeBroken) {
                    if (result.finishReason == "context_overflow") {
                        finalStatusCode = 400
                        val msg = "Prompt (${result.promptTokens} tokens) exceeds context window"
                        emitEvent(ServerEvent.ErrorOccurred(msg))
                        writeSseError(msg, "invalid_request_error")
                    } else if (result.finishReason == "error") {
                        finalStatusCode = 500
                        val msg = "Inference failed"
                        emitEvent(ServerEvent.ErrorOccurred(msg))
                        writeSseError(msg, "server_error")
                    } else {
                        if (!stoppedBySequence && result.finishReason != "cancelled") {
                            val tail = filter.flush()
                            if (tail.isNotEmpty()) {
                                writeChunk(tail, null)
                            }
                        }

                        val finalReason = when {
                            stoppedBySequence -> "stop"
                            result.finishReason == "length" -> "length"
                            result.finishReason == "cancelled" -> "cancelled"
                            else -> "stop"
                        }
                        writeChunk(null, finalReason)
                        if (includeUsage) {
                            writeUsageChunk(result.promptTokens, result.completionTokens)
                        }
                        writeSsePayload("data: [DONE]\n\n")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finalStatusCode = 500
                logError("Streaming error: ${e.message}", e)
                emitEvent(ServerEvent.ErrorOccurred(e.message ?: "Streaming error"))
                if (!pipeBroken) {
                    writeSseError(e.message ?: "Streaming error", "server_error")
                }
            } finally {
                try {
                    pipedOut.close()
                } catch (_: Exception) {
                }
                val durationMs = System.currentTimeMillis() - requestStartMs
                emitEvent(
                    ServerEvent.RequestCompleted(
                        method = methodName,
                        path = requestPath,
                        statusCode = finalStatusCode,
                        durationMs = durationMs,
                        tokensGenerated = completionTokens
                    )
                )
            }
        }
        job.invokeOnCompletion {
            runCatching { pipedOut.close() }
        }

        val response = newChunkedResponse(
            Response.Status.OK,
            MIME_EVENT_STREAM,
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        val clientConnection = session.headers["connection"]
        if (clientConnection != null && clientConnection.contains("close", ignoreCase = true)) {
            response.addHeader("Connection", "close")
        } else {
            response.addHeader("Connection", "keep-alive")
        }
        return corsWrap(response)
    }

    // ── Input parsing & validation helpers ──────────────────────────────────

    private fun parseJsonBody(
        session: IHTTPSession,
        onBodyReadStarted: () -> Unit,
        onBodyConsumed: () -> Unit
    ): JSONObject {
        val raw = readRequestBodyUtf8(session, onBodyReadStarted)
        onBodyConsumed()
        if (raw.isBlank()) {
            throw BadRequestException("Empty request body")
        }
        return JSONObject(raw)
    }

    private fun parseContentLengthHeader(session: IHTTPSession): Long? {
        val raw = session.headers["content-length"] ?: return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw BadRequestException("Invalid Content-Length header")
        }
        if (trimmed.startsWith("-")) {
            val neg = trimmed.toLongOrNull()
            if (neg != null && neg < 0L) {
                throw BadRequestException("Negative Content-Length header")
            }
            throw BadRequestException("Invalid Content-Length header")
        }
        if (!trimmed.all { it in '0'..'9' }) {
            throw BadRequestException("Invalid Content-Length header")
        }
        return trimmed.toLongOrNull()
            ?: throw BadRequestException("Invalid Content-Length header")
    }

    private fun readRequestBodyUtf8(
        session: IHTTPSession,
        onBodyReadStarted: () -> Unit = {}
    ): String {
        val transferEncoding = session.headers["transfer-encoding"]
        if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
            onBodyReadStarted()
            return readChunkedBodyUtf8(session.inputStream)
        }

        val declaredLen = parseContentLengthHeader(session) ?: 0L
        if (declaredLen > MAX_REQUEST_BODY_BYTES) {
            throw PayloadTooLargeException("Request body exceeds ${MAX_REQUEST_BODY_BYTES / (1024 * 1024)} MiB limit")
        }
        if (declaredLen == 0L) return ""

        onBodyReadStarted()
        val size = declaredLen.toInt()
        val buffer = ByteArray(size)
        val input = session.inputStream
        var totalRead = 0
        while (totalRead < size) {
            val read = input.read(buffer, totalRead, size - totalRead)
            if (read == -1) {
                throw BadRequestException("Incomplete request body")
            }
            totalRead += read
        }
        return String(buffer, Charsets.UTF_8)
    }

    private fun readChunkedBodyUtf8(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input)
                ?: throw BadRequestException("Incomplete chunked request body")
            val hexPart = sizeLine.substringBefore(';').trim()
            val chunkSize = hexPart.toIntOrNull(16)
                ?: throw BadRequestException("Invalid chunk size: '$hexPart'")
            if (chunkSize < 0) {
                throw BadRequestException("Negative chunk size")
            }
            if (chunkSize == 0) {
                // Consume optional trailers until blank line
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (out.size().toLong() + chunkSize.toLong() > MAX_REQUEST_BODY_BYTES) {
                throw PayloadTooLargeException("Request body exceeds ${MAX_REQUEST_BODY_BYTES / (1024 * 1024)} MiB limit")
            }
            val chunkBuf = ByteArray(chunkSize)
            var readSoFar = 0
            while (readSoFar < chunkSize) {
                val read = input.read(chunkBuf, readSoFar, chunkSize - readSoFar)
                if (read == -1) {
                    throw BadRequestException("Truncated chunked request body")
                }
                readSoFar += read
            }
            out.write(chunkBuf)
            // Consume CRLF after chunk payload
            val after = readAsciiLine(input)
                ?: throw BadRequestException("Missing chunk terminator")
            if (after.isNotEmpty()) {
                throw BadRequestException("Malformed chunk framing")
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun readAsciiLine(input: InputStream, maxLineBytes: Int = 4096): String? {
        val line = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (!sawAny) null else String(line.toByteArray(), Charsets.US_ASCII).trimEnd('\r')
            }
            sawAny = true
            if (b == '\n'.code) {
                return String(line.toByteArray(), Charsets.US_ASCII).trimEnd('\r')
            }
            if (line.size() >= maxLineBytes) {
                throw BadRequestException("Chunked line exceeds maximum length")
            }
            line.write(b)
        }
    }

    private fun finalizeKeepAlive(session: IHTTPSession, response: Response) {
        val transferEncoding = session.headers["transfer-encoding"]
        if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
            response.addHeader("Connection", "close")
            return
        }
        val rawContentLength = session.headers["content-length"] ?: return
        val trimmed = rawContentLength.trim()
        val contentLength = if (trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' }) {
            trimmed.toLongOrNull()
        } else {
            null
        }
        if (contentLength == null) {
            response.addHeader("Connection", "close")
            return
        }
        if (contentLength == 0L) return
        if (contentLength > 64 * 1024L) {
            response.addHeader("Connection", "close")
            return
        }
        try {
            val input = session.inputStream
            val scratch = ByteArray(4096)
            var remaining = contentLength
            while (remaining > 0L) {
                val toRead = minOf(remaining, scratch.size.toLong()).toInt()
                val read = input.read(scratch, 0, toRead)
                if (read <= 0) {
                    response.addHeader("Connection", "close")
                    return
                }
                remaining -= read.toLong()
            }
        } catch (_: Exception) {
            response.addHeader("Connection", "close")
        }
    }

    private fun JSONObject.optNonNullString(key: String, fallback: String = ""): String {
        if (!has(key) || isNull(key)) return fallback
        return (opt(key) as? String) ?: fallback
    }

    private fun JSONArray.optNonNullStringOrNull(index: Int): String? {
        if (index < 0 || index >= length() || isNull(index)) return null
        return opt(index) as? String
    }

    private fun JSONArray.optNonNullString(index: Int, fallback: String = ""): String =
        optNonNullStringOrNull(index) ?: fallback

    private fun parseChatMessages(messagesJson: JSONArray): List<ChatMessage> {
        val list = ArrayList<ChatMessage>(messagesJson.length())
        for (i in 0 until messagesJson.length()) {
            val msg = messagesJson.optJSONObject(i)
                ?: throw BadRequestException("Message at index $i must be an object")
            val role = msg.optNonNullString("role", "").trim()
            if (role.isEmpty()) {
                throw BadRequestException("Message at index $i missing non-empty 'role'")
            }

            val rawContent = msg.opt("content")
            val content = when (rawContent) {
                null, JSONObject.NULL -> ""
                is String -> rawContent
                is JSONArray -> extractTextFromContentParts(rawContent, i)
                else -> throw BadRequestException("Invalid 'content' at message index $i")
            }
            list.add(ChatMessage(role, content))
        }
        return list
    }

    private fun extractTextFromContentParts(parts: JSONArray, messageIndex: Int): String {
        val sb = StringBuilder()
        for (j in 0 until parts.length()) {
            val part = parts.optJSONObject(j)
                ?: throw BadRequestException("Content part $j in message $messageIndex must be an object")
            val type = part.optNonNullString("type", "")
            if (type == "text" || (part.has("text") && !part.isNull("text"))) {
                sb.append(part.optNonNullString("text", ""))
            }
        }
        return sb.toString()
    }

    private fun parseMaxTokens(reqJson: JSONObject): Int {
        val key = when {
            reqJson.has("max_tokens") && !reqJson.isNull("max_tokens") -> "max_tokens"
            reqJson.has("max_completion_tokens") && !reqJson.isNull("max_completion_tokens") -> "max_completion_tokens"
            else -> return defaultMaxTokens
        }
        val value = reqJson.optInt(key, -1)
        if (value <= 0) {
            throw BadRequestException("'$key' must be a positive integer")
        }
        return value
    }

    private fun parseTemperature(reqJson: JSONObject): Float {
        if (!reqJson.has("temperature") || reqJson.isNull("temperature")) {
            return defaultTemperature
        }
        val value = reqJson.optDouble("temperature", Double.NaN)
        if (value.isNaN() || value.isInfinite() || value < 0.0) {
            throw BadRequestException("'temperature' must be a non-negative finite number")
        }
        val narrowed = value.toFloat()
        if (!narrowed.isFinite() || value > MAX_TEMPERATURE.toDouble() || narrowed > MAX_TEMPERATURE) {
            throw BadRequestException("'temperature' must be between 0.0 and $MAX_TEMPERATURE")
        }
        return if (narrowed < MIN_POSITIVE_TEMPERATURE) 0.0f else narrowed
    }

    private fun parseStopStrings(reqJson: JSONObject): List<String> {
        if (!reqJson.has("stop") || reqJson.isNull("stop")) return emptyList()
        return when (val raw = reqJson.opt("stop")) {
            is String -> if (raw.isNotEmpty()) listOf(raw) else emptyList()
            is JSONArray -> buildList {
                for (i in 0 until raw.length()) {
                    val s = raw.optNonNullString(i, "")
                    if (s.isNotEmpty()) add(s)
                }
            }
            else -> throw BadRequestException("'stop' must be a string or array of strings")
        }
    }

    // ── Auth & CORS ─────────────────────────────────────────────────────────

    private fun withAuth(session: IHTTPSession, handler: () -> Response): Response {
        val expectedToken = apiToken
        if (expectedToken.isBlank()) {
            return corsWrap(jsonError(Response.Status.UNAUTHORIZED, "unauthorized"))
        }

        val authHeader = session.headers["authorization"]?.trim() ?: ""
        if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return corsWrap(jsonError(Response.Status.UNAUTHORIZED, "unauthorized"))
        }

        val providedToken = authHeader.substring(7).trim()
        return if (providedToken == expectedToken) {
            handler()
        } else {
            corsWrap(jsonError(Response.Status.UNAUTHORIZED, "unauthorized"))
        }
    }

    private fun corsWrap(response: Response): Response = response.apply {
        if (corsEnabled) {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Authorization,Content-Type")
        }
    }

    // ── JSON utilities ──────────────────────────────────────────────────────

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", json.toString())

    private fun jsonError(status: Response.Status, message: String): Response {
        val json = JSONObject().apply { put("error", message) }
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    // ── Event & Logging helpers ─────────────────────────────────────────────

    private fun emitEvent(event: ServerEvent) {
        _events.tryEmit(event)
        onEvent?.invoke(event)
    }

    private fun logInfo(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: RuntimeException) {
        }
    }

    private fun logError(msg: String, t: Throwable? = null) {
        try {
            Log.e(TAG, msg, t)
        } catch (_: RuntimeException) {
        }
    }
}
