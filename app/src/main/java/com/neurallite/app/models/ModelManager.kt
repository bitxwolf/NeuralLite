package com.neurallite.app.models

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.neurallite.app.engine.LlamaEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Describes the state of a single model in the lifecycle.
 */
sealed class ModelState {
    object NotDownloaded : ModelState()
    data class Downloading(val progress: Float, val downloadId: Long) : ModelState()
    data class Downloaded(val filePath: String) : ModelState()
    object Loading : ModelState()
    data class Loaded(val filePath: String) : ModelState()
    data class Error(val message: String) : ModelState()

    /** Compact label for logs / UI. */
    val label: String
        get() = when (this) {
            is NotDownloaded -> "Not Downloaded"
            is Downloading -> "Downloading (${(progress * 100).toInt()}%)"
            is Downloaded -> "Downloaded"
            is Loading -> "Loading…"
            is Loaded -> "Loaded"
            is Error -> "Error: $message"
        }
}

/**
 * Manages model downloads (via [DownloadManager]), file lifecycle, and
 * native model loading through [LlamaEngine].
 *
 * State is exposed as reactive [StateFlow]s for Compose observation.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val PROGRESS_POLL_MS = 500L
    }

    // ── State flows ─────────────────────────────────────────────────────────

    private val _modelStates = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelState>> = _modelStates.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    // ── Internal bookkeeping ────────────────────────────────────────────────

    /** Maps DownloadManager IDs → model catalogue IDs. */
    private val downloadIdToModelId = mutableMapOf<Long, String>()

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressPollingJob: Job? = null

    // ── Broadcast receiver ──────────────────────────────────────────────────

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val modelId = downloadIdToModelId.remove(id) ?: return

            val query = DownloadManager.Query().setFilterById(id)
            val cursor = downloadManager.query(query)

            if (cursor != null && cursor.moveToFirst()) {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val localUriIdx =
                        cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUri = if (localUriIdx >= 0) cursor.getString(localUriIdx) else null
                    val filePath = localUri?.let { Uri.parse(it).path } ?: getModelFile(modelId).absolutePath

                    updateState(modelId, ModelState.Downloaded(filePath))
                    Log.i(TAG, "Download complete: $modelId → $filePath")
                } else {
                    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                    updateState(modelId, ModelState.Error("Download failed (status=$status, reason=$reason)"))
                    Log.e(TAG, "Download failed for $modelId: status=$status reason=$reason")
                }
                cursor.close()
            } else {
                updateState(modelId, ModelState.Error("Download query returned no results"))
            }

            // Stop polling when no more active downloads
            if (downloadIdToModelId.isEmpty()) {
                progressPollingJob?.cancel()
                progressPollingJob = null
            }
        }
    }

    init {
        // Register download-complete listener
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED)

        // Seed known states from disk
        checkDownloadedModels()
    }

    // ── Download ────────────────────────────────────────────────────────────

    /**
     * Enqueue a GGUF download via [DownloadManager].
     * Files are stored in `getExternalFilesDir("models")/<filename>`.
     */
    fun downloadModel(model: ModelInfo) {
        val currentState = _modelStates.value[model.id]
        if (currentState is ModelState.Downloading) {
            Log.w(TAG, "Already downloading ${model.id}")
            return
        }
        if (currentState is ModelState.Downloaded || currentState is ModelState.Loaded) {
            Log.w(TAG, "${model.id} is already downloaded / loaded")
            return
        }

        val fileName = ModelCatalog.getFileName(model)
        val request = DownloadManager.Request(Uri.parse(model.huggingFaceDirectUrl))
            .setTitle("Downloading ${model.displayName}")
            .setDescription("${model.parameterSize} · ${model.quantization}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, MODELS_DIR, fileName)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)

        val downloadId = downloadManager.enqueue(request)
        downloadIdToModelId[downloadId] = model.id
        updateState(model.id, ModelState.Downloading(0f, downloadId))
        Log.i(TAG, "Enqueued download for ${model.id}, downloadId=$downloadId")

        startProgressPolling()
    }

    /**
     * Cancel an in-flight download.
     */
    fun cancelDownload(modelId: String) {
        val state = _modelStates.value[modelId]
        if (state is ModelState.Downloading) {
            downloadManager.remove(state.downloadId)
            downloadIdToModelId.remove(state.downloadId)
            updateState(modelId, ModelState.NotDownloaded)
            Log.i(TAG, "Cancelled download for $modelId")
        }
    }

    /**
     * Delete a downloaded model file and reset its state.
     */
    fun deleteModel(modelId: String) {
        val model = ModelCatalog.getModelById(modelId) ?: return

        // Unload first if this is the active model
        if (_activeModelId.value == modelId) {
            scope.launch {
                unloadCurrentModel()
                performDelete(model)
            }
        } else {
            performDelete(model)
        }
    }

    private fun performDelete(model: ModelInfo) {
        val file = getModelFile(model.id)
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted ${model.id}: $deleted")
        }
        updateState(model.id, ModelState.NotDownloaded)
    }

    // ── Load / Unload (native) ──────────────────────────────────────────────

    /**
     * Load a downloaded model into [LlamaEngine].
     *
     * @param nThreads  Number of inference threads (default: half the cores, min 2).
     * @param nCtx      Context length in tokens.
     */
    suspend fun loadModel(model: ModelInfo, nThreads: Int? = null, nCtx: Int? = null) {
        val state = _modelStates.value[model.id]
        if (state !is ModelState.Downloaded && state !is ModelState.Loaded) {
            updateState(model.id, ModelState.Error("Model is not downloaded"))
            return
        }

        val filePath = when (state) {
            is ModelState.Downloaded -> state.filePath
            is ModelState.Loaded -> state.filePath
            else -> return
        }

        // Unload any currently loaded model
        _activeModelId.value?.let { currentId ->
            if (currentId != model.id) {
                updateState(currentId, ModelState.Downloaded(filePath))
            }
        }

        updateState(model.id, ModelState.Loading)

        val threads = nThreads
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        val ctx = nCtx ?: model.contextLength

        val success = LlamaEngine.loadModelSafe(filePath, threads, ctx)

        if (success) {
            updateState(model.id, ModelState.Loaded(filePath))
            _activeModelId.value = model.id
            Log.i(TAG, "Model loaded: ${model.id}")
        } else {
            updateState(model.id, ModelState.Error("Failed to load model in native engine"))
            _activeModelId.value = null
            Log.e(TAG, "Failed to load ${model.id}")
        }
    }

    /**
     * Unload whichever model is currently active.
     */
    suspend fun unloadCurrentModel() {
        val currentId = _activeModelId.value ?: return
        LlamaEngine.unloadCurrentModel()

        // Transition back to Downloaded (the file is still on disk)
        val file = getModelFile(currentId)
        if (file.exists()) {
            updateState(currentId, ModelState.Downloaded(file.absolutePath))
        } else {
            updateState(currentId, ModelState.NotDownloaded)
        }
        _activeModelId.value = null
        Log.i(TAG, "Model unloaded: $currentId")
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    /**
     * Returns `(usedBytes, freeBytes)` for the models directory.
     */
    fun getStorageInfo(): Pair<Long, Long> {
        val modelsDir = getModelsDirectory()
        val usedBytes = modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.sumOf { it.length() }
            ?: 0L

        val stat = android.os.StatFs(modelsDir.absolutePath)
        val freeBytes = stat.availableBytes

        return usedBytes to freeBytes
    }

    // ── Scan disk ───────────────────────────────────────────────────────────

    /**
     * Walk `getExternalFilesDir("models")` and update state for every
     * catalogued model whose GGUF file is present on disk.
     */
    fun checkDownloadedModels() {
        val modelsDir = getModelsDirectory()
        val existingFiles = modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val newStates = _modelStates.value.toMutableMap()

        for (model in ModelCatalog.models) {
            val fileName = ModelCatalog.getFileName(model)
            val currentState = newStates[model.id]

            if (fileName in existingFiles) {
                // Don't overwrite Downloading/Loading/Loaded states
                if (currentState == null ||
                    currentState is ModelState.NotDownloaded ||
                    currentState is ModelState.Error
                ) {
                    val filePath = File(modelsDir, fileName).absolutePath
                    newStates[model.id] = ModelState.Downloaded(filePath)
                }
            } else {
                // File doesn't exist — unless actively downloading, reset
                if (currentState !is ModelState.Downloading) {
                    newStates[model.id] = ModelState.NotDownloaded
                }
            }
        }

        _modelStates.value = newStates
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    /**
     * Call when the hosting Activity/ViewModel is destroyed to prevent leaks.
     */
    fun destroy() {
        try {
            context.unregisterReceiver(downloadCompleteReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered
        }
        progressPollingJob?.cancel()
        scope.cancel()
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun getModelsDirectory(): File {
        val dir = context.getExternalFilesDir(MODELS_DIR)
            ?: File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getModelFile(modelId: String): File {
        val model = ModelCatalog.getModelById(modelId) ?: return File(getModelsDirectory(), modelId)
        return File(getModelsDirectory(), ModelCatalog.getFileName(model))
    }

    private fun updateState(modelId: String, state: ModelState) {
        _modelStates.value = _modelStates.value.toMutableMap().apply {
            this[modelId] = state
        }
    }

    /**
     * Periodically polls [DownloadManager] for active download progress.
     * Runs every [PROGRESS_POLL_MS] until all tracked downloads complete.
     */
    private fun startProgressPolling() {
        if (progressPollingJob?.isActive == true) return

        progressPollingJob = scope.launch {
            while (isActive && downloadIdToModelId.isNotEmpty()) {
                for ((downloadId, modelId) in downloadIdToModelId.toMap()) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor != null && cursor.moveToFirst()) {
                        val bytesIdx =
                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx =
                            cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        val bytesDownloaded = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
                        val totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L

                        if (totalBytes > 0) {
                            val progress = bytesDownloaded.toFloat() / totalBytes.toFloat()
                            updateState(modelId, ModelState.Downloading(progress, downloadId))
                        }
                        cursor.close()
                    }
                }
                delay(PROGRESS_POLL_MS)
            }
        }
    }
}
