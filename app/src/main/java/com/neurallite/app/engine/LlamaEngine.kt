package com.neurallite.app.engine

import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured output returned by [LlamaEngine.runInferenceSafe].
 */
data class InferenceResult(
    val text: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val finishReason: String
)

internal interface NativeLlamaBridge {
    fun loadModel(modelPathBytes: ByteArray, nThreads: Int, nCtx: Int): Long
    fun runInference(
        handle: Long,
        promptBytes: ByteArray,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        onTokenBytes: (ByteArray) -> Unit,
        outStats: IntArray
    ): ByteArray
    fun resetStopFlag(handle: Long)
    fun stopInference(handle: Long)
    fun unloadModel(handle: Long)
    fun getModelInfo(handle: Long): String
}

/**
 * Pure-Kotlin lifecycle & concurrency coordinator for [LlamaEngine] so the
 * mutex handoff, generation barrier, and cancel-token locking contracts are
 * directly unit-testable on the JVM without loading native `.so` binaries.
 */
internal class EngineCoordinator(
    private val bridge: NativeLlamaBridge,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logInfo: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
    private val logError: (String, Throwable?) -> Unit = { _, _ -> }
) {
    private val activeHandle = AtomicLong(-1L)
    private val handleLock = Any()
    private val inferenceMutex = Mutex()
    private val pendingLifecycleRequests = AtomicInteger(0)
    private val modelGeneration = AtomicLong(0L)

    @Volatile
    private var activeCancelToken: AtomicBoolean? = null

    @Volatile
    var loadedModelId: String? = null
        private set

    @Volatile
    var loadedConfig: Pair<Int, Int>? = null
        private set

    val isLoaded: Boolean get() = activeHandle.get() != -1L

    private val _error = MutableStateFlow<LlamaEngine.LlamaError?>(null)
    val error: StateFlow<LlamaEngine.LlamaError?> = _error.asStateFlow()

    private val _modelInfo = MutableStateFlow<String?>(null)
    val modelInfo: StateFlow<String?> = _modelInfo.asStateFlow()

    private val _isInferring = MutableStateFlow(false)
    val isInferring: StateFlow<Boolean> = _isInferring.asStateFlow()

    suspend fun loadModelSafe(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        modelId: String?
    ): Boolean {
        pendingLifecycleRequests.incrementAndGet()
        modelGeneration.incrementAndGet()
        val decremented = AtomicBoolean(false)
        return try {
            stopCurrentInference()
            withContext(ioDispatcher) {
                inferenceMutex.withLock {
                    try {
                        _error.value = null
                        val existing = synchronized(handleLock) {
                            val prev = activeHandle.getAndSet(-1L)
                            loadedModelId = null
                            loadedConfig = null
                            _modelInfo.value = null
                            prev
                        }
                        if (existing != -1L) {
                            logInfo("Unloading existing model (handle=$existing)")
                            try {
                                bridge.unloadModel(existing)
                            } catch (e: Exception) {
                                logError("Error unloading model: ${e.message}", e)
                            }
                        }

                        val modelFile = File(modelPath)
                        if (!modelFile.exists() || !modelFile.isFile || !modelFile.canRead() || modelFile.length() <= 0L) {
                            logError("Model file missing or unreadable: $modelPath", null)
                            _error.value = LlamaEngine.LlamaError.LoadFailed(modelPath)
                            return@withLock false
                        }

                        logInfo("Loading model: $modelPath (threads=$nThreads, ctx=$nCtx)")
                        val handle = bridge.loadModel(modelPath.toByteArray(Charsets.UTF_8), nThreads, nCtx)

                        if (handle == -1L) {
                            logError("loadModel returned -1 for $modelPath", null)
                            _error.value = LlamaEngine.LlamaError.OutOfMemory
                            return@withLock false
                        }

                        val infoJson = try {
                            bridge.getModelInfo(handle)
                        } catch (e: Exception) {
                            logWarn("Could not get model info: ${e.message}")
                            null
                        }

                        synchronized(handleLock) {
                            loadedModelId = modelId
                            loadedConfig = Pair(nCtx, nThreads)
                            _modelInfo.value = infoJson
                            activeHandle.set(handle)
                        }

                        logInfo("Model loaded successfully (handle=$handle)")
                        true
                    } finally {
                        if (decremented.compareAndSet(false, true)) {
                            pendingLifecycleRequests.decrementAndGet()
                        }
                    }
                }
            }
        } finally {
            if (decremented.compareAndSet(false, true)) {
                pendingLifecycleRequests.decrementAndGet()
            }
        }
    }

    suspend fun runInferenceSafe(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        cancelToken: AtomicBoolean?,
        onToken: (String) -> Unit
    ): InferenceResult {
        val effectiveCancelToken = cancelToken ?: AtomicBoolean(false)
        val entryGeneration = modelGeneration.get()
        if (effectiveCancelToken.get() || pendingLifecycleRequests.get() > 0) {
            return InferenceResult(
                text = "",
                promptTokens = 0,
                completionTokens = 0,
                finishReason = "cancelled"
            )
        }
        return withContext(ioDispatcher) {
            inferenceMutex.withLock {
                var preflightResult: InferenceResult? = null
                val handle = synchronized(handleLock) {
                    if (effectiveCancelToken.get() ||
                        pendingLifecycleRequests.get() > 0 ||
                        modelGeneration.get() != entryGeneration
                    ) {
                        preflightResult = InferenceResult(
                            text = "",
                            promptTokens = 0,
                            completionTokens = 0,
                            finishReason = "cancelled"
                        )
                        -1L
                    } else {
                        val h = activeHandle.get()
                        if (h == -1L) {
                            _error.value = LlamaEngine.LlamaError.InferenceFailed("No model loaded")
                            preflightResult = InferenceResult(
                                text = "",
                                promptTokens = 0,
                                completionTokens = 0,
                                finishReason = "error"
                            )
                            -1L
                        } else {
                            bridge.resetStopFlag(h)
                            activeCancelToken = effectiveCancelToken
                            _error.value = null
                            _isInferring.value = true
                            h
                        }
                    }
                }
                preflightResult?.let { return@withLock it }

                try {
                    val stats = IntArray(3)
                    val outBytes = bridge.runInference(
                        handle = handle,
                        promptBytes = prompt.toByteArray(Charsets.UTF_8),
                        maxTokens = maxTokens,
                        temperature = temperature,
                        parseSpecial = parseSpecial,
                        onTokenBytes = { utf8Bytes ->
                            if (effectiveCancelToken.get()) {
                                stopInferenceIfMatching(effectiveCancelToken)
                            } else if (utf8Bytes.isNotEmpty()) {
                                onToken(String(utf8Bytes, Charsets.UTF_8))
                            }
                        },
                        outStats = stats
                    )

                    val finishReason = when (stats[2]) {
                        0 -> "stop"
                        1 -> "length"
                        2 -> "cancelled"
                        4 -> {
                            _error.value = LlamaEngine.LlamaError.InferenceFailed(
                                "Prompt (${stats[0]} tokens) exceeds context window"
                            )
                            "context_overflow"
                        }
                        else -> {
                            _error.value = LlamaEngine.LlamaError.InferenceFailed("Native inference error")
                            "error"
                        }
                    }

                    InferenceResult(
                        text = String(outBytes, Charsets.UTF_8),
                        promptTokens = stats[0],
                        completionTokens = stats[1],
                        finishReason = finishReason
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logError("Inference error: ${e.message}", e)
                    _error.value = LlamaEngine.LlamaError.InferenceFailed(e.message ?: "Unknown error")
                    InferenceResult(
                        text = "",
                        promptTokens = 0,
                        completionTokens = 0,
                        finishReason = "error"
                    )
                } finally {
                    synchronized(handleLock) {
                        _isInferring.value = false
                        activeCancelToken = null
                    }
                }
            }
        }
    }

    fun stopCurrentInference() {
        synchronized(handleLock) {
            activeCancelToken?.set(true)
            val handle = activeHandle.get()
            if (handle != -1L) {
                bridge.stopInference(handle)
            }
        }
    }

    fun stopInferenceIfMatching(token: AtomicBoolean) {
        token.set(true)
        synchronized(handleLock) {
            if (activeCancelToken === token) {
                val handle = activeHandle.get()
                if (handle != -1L) {
                    bridge.stopInference(handle)
                }
            }
        }
    }

    suspend fun unloadCurrentModel() {
        pendingLifecycleRequests.incrementAndGet()
        modelGeneration.incrementAndGet()
        val decremented = AtomicBoolean(false)
        try {
            stopCurrentInference()
            withContext(ioDispatcher + NonCancellable) {
                inferenceMutex.withLock {
                    try {
                        val handle = synchronized(handleLock) {
                            val prev = activeHandle.getAndSet(-1L)
                            loadedModelId = null
                            loadedConfig = null
                            _modelInfo.value = null
                            prev
                        }
                        if (handle != -1L) {
                            try {
                                bridge.unloadModel(handle)
                                logInfo("Model unloaded")
                            } catch (e: Exception) {
                                logError("Error unloading model: ${e.message}", e)
                            }
                        }
                    } finally {
                        if (decremented.compareAndSet(false, true)) {
                            pendingLifecycleRequests.decrementAndGet()
                        }
                    }
                }
            }
        } finally {
            if (decremented.compareAndSet(false, true)) {
                pendingLifecycleRequests.decrementAndGet()
            }
        }
    }

    fun getCurrentModelInfo(): String? = synchronized(handleLock) {
        val handle = activeHandle.get()
        if (handle == -1L) return@synchronized null
        return@synchronized try {
            bridge.getModelInfo(handle)
        } catch (e: Exception) {
            logError("getModelInfo error: ${e.message}", e)
            null
        }
    }

    fun clearError() {
        _error.value = null
    }
}

/**
 * Singleton wrapper around the native llama.cpp inference engine.
 *
 * All model loads, unloads, and inference runs are serialized with [inferenceMutex]
 * on [Dispatchers.IO], while [handleLock] protects the native handle pointer and
 * [activeCancelToken] from concurrent races between [stopCurrentInference],
 * [stopInferenceIfMatching], and [unloadModel].
 */
object LlamaEngine {

    private const val TAG = "LlamaEngine"

    @Keep
    fun interface TokenCallback {
        fun onToken(utf8Bytes: ByteArray)
    }

    // ── Error & model metadata state ─────────────────────────────────────────
    sealed class LlamaError {
        object OutOfMemory : LlamaError()
        data class LoadFailed(val path: String) : LlamaError()
        data class InferenceFailed(val message: String) : LlamaError()
    }

    private val nativeBridge = object : NativeLlamaBridge {
        override fun loadModel(modelPathBytes: ByteArray, nThreads: Int, nCtx: Int): Long =
            this@LlamaEngine.loadModel(modelPathBytes, nThreads, nCtx)

        override fun runInference(
            handle: Long,
            promptBytes: ByteArray,
            maxTokens: Int,
            temperature: Float,
            parseSpecial: Boolean,
            onTokenBytes: (ByteArray) -> Unit,
            outStats: IntArray
        ): ByteArray = this@LlamaEngine.runInference(
            handle = handle,
            promptBytes = promptBytes,
            maxTokens = maxTokens,
            temperature = temperature,
            parseSpecial = parseSpecial,
            tokenCallback = TokenCallback { bytes -> onTokenBytes(bytes) },
            outStats = outStats
        )

        override fun resetStopFlag(handle: Long) = this@LlamaEngine.resetStopFlag(handle)
        override fun stopInference(handle: Long) = this@LlamaEngine.stopInference(handle)
        override fun unloadModel(handle: Long) = this@LlamaEngine.unloadModel(handle)
        override fun getModelInfo(handle: Long): String = this@LlamaEngine.getModelInfo(handle)
    }

    private val coordinator = EngineCoordinator(
        bridge = nativeBridge,
        ioDispatcher = Dispatchers.IO,
        logInfo = { msg -> Log.i(TAG, msg) },
        logWarn = { msg -> Log.w(TAG, msg) },
        logError = { msg, err ->
            if (err != null) Log.e(TAG, msg, err) else Log.e(TAG, msg)
        }
    )

    val loadedModelId: String? get() = coordinator.loadedModelId
    val loadedConfig: Pair<Int, Int>? get() = coordinator.loadedConfig
    val isLoaded: Boolean get() = coordinator.isLoaded

    val error: StateFlow<LlamaError?> = coordinator.error
    val modelInfo: StateFlow<String?> = coordinator.modelInfo
    val isInferring: StateFlow<Boolean> = coordinator.isInferring

    // ── JNI declarations ─────────────────────────────────────────────────────
    private external fun loadModel(modelPathBytes: ByteArray, nThreads: Int, nCtx: Int): Long
    private external fun runInference(
        handle: Long,
        promptBytes: ByteArray,
        maxTokens: Int,
        temperature: Float,
        parseSpecial: Boolean,
        tokenCallback: TokenCallback,
        outStats: IntArray
    ): ByteArray
    private external fun resetStopFlag(handle: Long)
    private external fun stopInference(handle: Long)
    private external fun unloadModel(handle: Long)
    private external fun getModelInfo(handle: Long): String

    init {
        System.loadLibrary("llama_android")
        Log.i(TAG, "libllama_android.so loaded")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Safely load a model. Unloads any existing model first.
     */
    suspend fun loadModelSafe(
        modelPath: String,
        nThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        nCtx: Int = 2048,
        modelId: String? = null
    ): Boolean = coordinator.loadModelSafe(
        modelPath = modelPath,
        nThreads = nThreads,
        nCtx = nCtx,
        modelId = modelId
    )

    /**
     * Run inference with serialized context access and UTF-8 byte-safe token streaming.
     */
    suspend fun runInferenceSafe(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        parseSpecial: Boolean = false,
        cancelToken: AtomicBoolean? = null,
        onToken: (String) -> Unit = {}
    ): InferenceResult = coordinator.runInferenceSafe(
        prompt = prompt,
        maxTokens = maxTokens,
        temperature = temperature,
        parseSpecial = parseSpecial,
        cancelToken = cancelToken,
        onToken = onToken
    )

    /**
     * Stop the currently running inference if any. Safe to call from any thread.
     */
    fun stopCurrentInference() = coordinator.stopCurrentInference()

    /**
     * Stop only if the active run matches [token]. Prevents a disconnected
     * queued client from cancelling another client's active inference.
     */
    fun stopInferenceIfMatching(token: AtomicBoolean) = coordinator.stopInferenceIfMatching(token)

    /**
     * Unload the active model and free native memory.
     */
    suspend fun unloadCurrentModel() = coordinator.unloadCurrentModel()

    /**
     * Get info about the currently loaded model as a JSON string.
     */
    fun getCurrentModelInfo(): String? = coordinator.getCurrentModelInfo()

    fun clearError() = coordinator.clearError()
}
