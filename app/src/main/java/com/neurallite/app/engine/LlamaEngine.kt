package com.neurallite.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton wrapper around the native llama.cpp inference engine.
 *
 * All inference runs on [Dispatchers.IO]. The active model handle is stored
 * in an [AtomicLong] — never in a local variable — and every [loadModelSafe]
 * call unloads any existing model first.
 */
object LlamaEngine {

    private const val TAG = "LlamaEngine"

    // ── Native handle ────────────────────────────────────────────────────────
    private val activeHandle = AtomicLong(-1L)
    val isLoaded: Boolean get() = activeHandle.get() != -1L

    // ── Error state ──────────────────────────────────────────────────────────
    sealed class LlamaError {
        object OutOfMemory : LlamaError()
        data class LoadFailed(val path: String) : LlamaError()
        data class InferenceFailed(val message: String) : LlamaError()
    }

    private val _error = MutableStateFlow<LlamaError?>(null)
    val error: StateFlow<LlamaError?> = _error.asStateFlow()

    private val _modelInfo = MutableStateFlow<String?>(null)
    val modelInfo: StateFlow<String?> = _modelInfo.asStateFlow()

    // ── JNI declarations ─────────────────────────────────────────────────────
    private external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun runInference(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: (String) -> Unit
    ): String
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
     * Must be called from a coroutine context.
     *
     * @return true if the model was loaded successfully.
     */
    suspend fun loadModelSafe(
        modelPath: String,
        nThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        nCtx: Int = 2048
    ): Boolean = withContext(Dispatchers.IO) {
        _error.value = null

        // Unload existing model
        val existing = activeHandle.getAndSet(-1L)
        if (existing != -1L) {
            Log.i(TAG, "Unloading existing model (handle=$existing)")
            try {
                unloadModel(existing)
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model: ${e.message}")
            }
        }

        Log.i(TAG, "Loading model: $modelPath (threads=$nThreads, ctx=$nCtx)")
        val handle = loadModel(modelPath, nThreads, nCtx)

        if (handle == -1L) {
            Log.e(TAG, "loadModel returned -1 for $modelPath")
            _error.value = LlamaError.OutOfMemory
            return@withContext false
        }

        activeHandle.set(handle)

        // Fetch model info
        try {
            _modelInfo.value = getModelInfo(handle)
        } catch (e: Exception) {
            Log.w(TAG, "Could not get model info: ${e.message}")
        }

        Log.i(TAG, "Model loaded successfully (handle=$handle)")
        true
    }

    /**
     * Run inference with streaming token callback.
     * Must be called from a coroutine on [Dispatchers.IO].
     */
    suspend fun runInferenceSafe(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val handle = activeHandle.get()
        if (handle == -1L) {
            _error.value = LlamaError.InferenceFailed("No model loaded")
            return@withContext ""
        }

        try {
            runInference(handle, prompt, maxTokens, temperature, onToken)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            _error.value = LlamaError.InferenceFailed(e.message ?: "Unknown error")
            ""
        }
    }

    /**
     * Stop the current inference run.
     */
    fun stopCurrentInference() {
        val handle = activeHandle.get()
        if (handle != -1L) {
            stopInference(handle)
        }
    }

    /**
     * Unload the active model and free native memory.
     */
    suspend fun unloadCurrentModel() = withContext(Dispatchers.IO) {
        val handle = activeHandle.getAndSet(-1L)
        if (handle != -1L) {
            try {
                unloadModel(handle)
                _modelInfo.value = null
                Log.i(TAG, "Model unloaded")
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model: ${e.message}")
            }
        }
    }

    /**
     * Get info about the currently loaded model as a JSON string.
     */
    fun getCurrentModelInfo(): String? {
        val handle = activeHandle.get()
        if (handle == -1L) return null
        return try {
            getModelInfo(handle)
        } catch (e: Exception) {
            Log.e(TAG, "getModelInfo error: ${e.message}")
            null
        }
    }

    fun clearError() {
        _error.value = null
    }
}
