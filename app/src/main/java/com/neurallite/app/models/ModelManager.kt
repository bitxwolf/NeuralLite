package com.neurallite.app.models

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import com.neurallite.app.engine.LlamaEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Describes the state of a single model in the lifecycle.
 */
sealed class ModelState {
    object NotDownloaded : ModelState()
    data class Downloading(
        val progress: Float,
        val downloadId: Long,
        val statusNote: String? = null
    ) : ModelState()
    data class Downloaded(val filePath: String) : ModelState()
    object Loading : ModelState()
    data class Loaded(val filePath: String) : ModelState()
    data class Error(val message: String) : ModelState()

    val label: String
        get() = when (this) {
            is NotDownloaded -> "Not Downloaded"
            is Downloading -> statusNote ?: "Downloading (${(progress * 100).toInt()}%)"
            is Downloaded -> "Downloaded"
            is Loading -> "Loading…"
            is Loaded -> "Loaded"
            is Error -> "Error: $message"
        }
}

/**
 * Manages model downloads (via [DownloadManager]), file lifecycle, reactive
 * storage metrics, and native model loading through [LlamaEngine].
 */
class ModelManager internal constructor(
    private val context: Context,
    private val downloadGateway: DownloadGateway,
    private val engine: EngineGateway,
    private val modelsDirProvider: () -> File,
    private val registerReceiver: Boolean,
    runInitialCheck: Boolean
) {

    constructor(context: Context) : this(
        context = context,
        downloadGateway = AndroidDownloadGateway(context),
        engine = LlamaEngineGateway,
        modelsDirProvider = {
            val dir = context.getExternalFilesDir(MODELS_DIR)
                ?: File(context.filesDir, MODELS_DIR)
            if (!dir.exists()) dir.mkdirs()
            dir
        },
        registerReceiver = true,
        runInitialCheck = true
    )

    internal data class DownloadRow(
        val id: Long,
        val uri: String,
        val status: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val reason: Int,
        val localUri: String?,
        val lastModifiedMs: Long
    )

    internal sealed class DownloadQueryResult {
        object Unavailable : DownloadQueryResult()
        object NotFound : DownloadQueryResult()
        data class Found(val row: DownloadRow) : DownloadQueryResult()
    }

    internal interface DownloadGateway {
        fun enqueue(model: ModelInfo): Long
        fun remove(downloadId: Long)
        fun queryById(downloadId: Long): DownloadQueryResult
        fun queryCatalogDownloads(): List<DownloadRow>
    }

    internal interface EngineGateway {
        val isLoaded: Boolean
        val loadedModelId: String?
        val loadedConfig: Pair<Int, Int>?
        val error: StateFlow<LlamaEngine.LlamaError?>
        suspend fun loadModelSafe(
            modelPath: String,
            nThreads: Int,
            nCtx: Int,
            modelId: String
        ): Boolean

        suspend fun unloadCurrentModel()
    }

    private object LlamaEngineGateway : EngineGateway {
        override val isLoaded: Boolean get() = LlamaEngine.isLoaded
        override val loadedModelId: String? get() = LlamaEngine.loadedModelId
        override val loadedConfig: Pair<Int, Int>? get() = LlamaEngine.loadedConfig
        override val error: StateFlow<LlamaEngine.LlamaError?> get() = LlamaEngine.error

        override suspend fun loadModelSafe(
            modelPath: String,
            nThreads: Int,
            nCtx: Int,
            modelId: String
        ): Boolean = LlamaEngine.loadModelSafe(
            modelPath = modelPath,
            nThreads = nThreads,
            nCtx = nCtx,
            modelId = modelId
        )

        override suspend fun unloadCurrentModel() {
            LlamaEngine.unloadCurrentModel()
        }
    }

    private class AndroidDownloadGateway(private val context: Context) : DownloadGateway {
        private val downloadManager: DownloadManager? by lazy {
            context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        }

        override fun enqueue(model: ModelInfo): Long {
            val dm = downloadManager
                ?: throw IllegalStateException("DownloadManager service unavailable")
            val externalDir = context.getExternalFilesDir(MODELS_DIR)
                ?: throw IllegalStateException("External storage is unavailable")
            if (!externalDir.exists()) externalDir.mkdirs()

            val fileName = ModelCatalog.getFileName(model)
            val request = DownloadManager.Request(Uri.parse(model.huggingFaceDirectUrl))
                .setTitle("Downloading ${model.displayName}")
                .setDescription("${model.parameterSize} · ${model.quantization}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, MODELS_DIR, fileName)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)

            return dm.enqueue(request)
        }

        override fun remove(downloadId: Long) {
            try {
                downloadManager?.remove(downloadId)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing download $downloadId: ${e.message}")
            }
        }

        override fun queryById(downloadId: Long): DownloadQueryResult {
            val dm = downloadManager ?: return DownloadQueryResult.Unavailable
            return try {
                val query = DownloadManager.Query().setFilterById(downloadId)
                dm.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        DownloadQueryResult.Found(readRow(cursor))
                    } else {
                        DownloadQueryResult.NotFound
                    }
                } ?: DownloadQueryResult.Unavailable
            } catch (e: Exception) {
                Log.w(TAG, "Could not query download $downloadId: ${e.message}")
                DownloadQueryResult.Unavailable
            }
        }

        override fun queryCatalogDownloads(): List<DownloadRow> {
            val dm = downloadManager ?: return emptyList()
            val result = mutableListOf<DownloadRow>()
            try {
                val query = DownloadManager.Query().setFilterByStatus(
                    DownloadManager.STATUS_PENDING or
                        DownloadManager.STATUS_RUNNING or
                        DownloadManager.STATUS_PAUSED or
                        DownloadManager.STATUS_FAILED or
                        DownloadManager.STATUS_SUCCESSFUL
                )
                dm.query(query)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = readRow(cursor)
                        if (ModelCatalog.models.any { it.huggingFaceDirectUrl == row.uri }) {
                            result.add(row)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query catalog downloads: ${e.message}")
            }
            return result
        }

        private fun readRow(cursor: android.database.Cursor): DownloadRow {
            val idIdx = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val modifiedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

            return DownloadRow(
                id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L,
                uri = if (uriIdx >= 0) cursor.getString(uriIdx) ?: "" else "",
                status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1,
                bytesDownloaded = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L,
                totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L,
                reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1,
                localUri = if (localUriIdx >= 0) cursor.getString(localUriIdx) else null,
                lastModifiedMs = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) else 0L
            )
        }
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val PROGRESS_POLL_MS = 500L
        internal const val MIN_GGUF_BYTES = 24L

        private val ACTIVE_STATUSES = setOf(
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED
        )

        private val ROW_ORDER = compareBy<DownloadRow>(
            { it.lastModifiedMs },
            { it.id }
        )
    }

    // ── State flows ─────────────────────────────────────────────────────────

    private val _modelStates = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelState>> = _modelStates.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _storageInfo = MutableStateFlow(0L to 0L)
    val storageInfo: StateFlow<Pair<Long, Long>> = _storageInfo.asStateFlow()

    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    private val _loadedConfig = MutableStateFlow<Pair<Int, Int>?>(null)
    val loadedConfig: StateFlow<Pair<Int, Int>?> = _loadedConfig.asStateFlow()

    // ── Internal bookkeeping ────────────────────────────────────────────────
    // Strict lock ordering: lifecycleMutex -> downloadMutex -> pollingLock

    private val downloadIdToModelId = ConcurrentHashMap<Long, String>()
    private val lifecycleMutex = Mutex()
    private val downloadMutex = Mutex()
    private val pollingLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressPollingJob: Job? = null

    // ── Broadcast receiver ──────────────────────────────────────────────────

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            val modelId = downloadIdToModelId[id] ?: return
            scope.launch {
                handleDownloadTerminalQuery(id, modelId)
            }
        }
    }

    init {
        if (registerReceiver) {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(
                context,
                downloadCompleteReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        if (runInitialCheck) {
            scope.launch {
                checkDownloadedModels()
            }
        }
    }

    // ── Download ────────────────────────────────────────────────────────────

    fun downloadModel(model: ModelInfo) {
        scope.launch {
            downloadMutex.withLock {
                val currentState = _modelStates.value[model.id]
                if (currentState is ModelState.Downloading || downloadIdToModelId.containsValue(model.id)) {
                    Log.w(TAG, "Already downloading ${model.id}")
                    return@withLock
                }
                if (currentState is ModelState.Downloaded ||
                    currentState is ModelState.Loaded ||
                    currentState is ModelState.Loading
                ) {
                    Log.w(TAG, "${model.id} is already downloaded / active")
                    return@withLock
                }

                _modelError.value = null

                val downloadId = try {
                    val destFile = getModelFile(model.id)
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    downloadGateway.enqueue(model)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enqueue download for ${model.id}: ${e.message}", e)
                    updateState(
                        model.id,
                        ModelState.Error("Unable to start download: ${e.message ?: "unknown error"}")
                    )
                    return@withLock
                }

                downloadIdToModelId[downloadId] = model.id
                updateState(model.id, ModelState.Downloading(0f, downloadId))
                Log.i(TAG, "Enqueued download for ${model.id}, downloadId=$downloadId")

                startProgressPolling()
            }
        }
    }

    fun cancelDownload(modelId: String) {
        scope.launch {
            downloadMutex.withLock {
                cancelDownloadLocked(modelId)
            }
        }
    }

    private fun cancelDownloadLocked(modelId: String) {
        val state = _modelStates.value[modelId]
        val matchingIds = downloadIdToModelId.entries
            .filter { it.value == modelId }
            .map { it.key }
            .toMutableSet()
        if (state is ModelState.Downloading) {
            matchingIds.add(state.downloadId)
        }

        if (matchingIds.isEmpty() && state !is ModelState.Downloading) {
            return
        }

        for (id in matchingIds) {
            downloadIdToModelId.remove(id)
            downloadGateway.remove(id)
        }

        val destFile = getModelFile(modelId)
        if (destFile.exists()) {
            destFile.delete()
        }
        updateState(modelId, ModelState.NotDownloaded)
        stopPollingIfIdleLocked()
        refreshStorageInfo()
        Log.i(TAG, "Cancelled download for $modelId")
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            deleteModelLocked(modelId)
        }
    }

    suspend fun clearAllModels() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            for (model in ModelCatalog.models) {
                deleteModelLocked(model.id)
            }
            downloadMutex.withLock {
                val remainingIds = downloadIdToModelId.keys.toList()
                for (id in remainingIds) {
                    downloadIdToModelId.remove(id)
                    downloadGateway.remove(id)
                }
                stopPollingIfIdleLocked()
            }
            if (engine.isLoaded) {
                unloadCurrentModelLocked()
            }
            // Also clean up any leftover .gguf files in models dir
            getModelsDirectory().listFiles()
                ?.filter { it.isFile && it.extension == "gguf" }
                ?.forEach { it.delete() }
            refreshStorageInfo()
        }
    }

    private suspend fun deleteModelLocked(modelId: String) {
        val model = ModelCatalog.getModelById(modelId) ?: return

        downloadMutex.withLock {
            val state = _modelStates.value[modelId]
            val matchingIds = downloadIdToModelId.entries
                .filter { it.value == modelId }
                .map { it.key }
                .toMutableSet()
            if (state is ModelState.Downloading) {
                matchingIds.add(state.downloadId)
            }
            downloadGateway.queryCatalogDownloads()
                .filter { it.uri == model.huggingFaceDirectUrl }
                .forEach { matchingIds.add(it.id) }
            for (id in matchingIds) {
                downloadIdToModelId.remove(id)
                downloadGateway.remove(id)
            }
            stopPollingIfIdleLocked()
        }

        val state = _modelStates.value[modelId]
        if (_activeModelId.value == modelId ||
            state is ModelState.Loaded ||
            state is ModelState.Loading ||
            (engine.isLoaded && engine.loadedModelId == modelId)
        ) {
            unloadCurrentModelLocked()
        }

        val file = getModelFile(model.id)
        if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted ${model.id}: $deleted")
        }
        updateState(model.id, ModelState.NotDownloaded)
        refreshStorageInfo()
    }

    // ── Load / Unload / Switch (native) ─────────────────────────────────────

    suspend fun loadModel(model: ModelInfo, nThreads: Int? = null, nCtx: Int? = null) =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                loadModelLocked(model, nThreads, nCtx)
            }
        }

    suspend fun switchModel(model: ModelInfo, nThreads: Int? = null, nCtx: Int? = null) =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val activeId = _activeModelId.value ?: engine.loadedModelId
                if (activeId != null && activeId != model.id) {
                    unloadCurrentModelLocked()
                }
                loadModelLocked(model, nThreads, nCtx)
            }
        }

    suspend fun unloadCurrentModel() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            unloadCurrentModelLocked()
        }
    }

    private suspend fun loadModelLocked(model: ModelInfo, nThreads: Int?, nCtx: Int?) {
        _modelError.value = null
        val state = _modelStates.value[model.id]
        if (state is ModelState.Downloading) {
            _modelError.value = "${model.displayName} is still downloading"
            return
        }
        val expectedFile = getModelFile(model.id)
        val filePath = when (state) {
            is ModelState.Downloaded -> state.filePath
            is ModelState.Loaded -> state.filePath
            else -> if (isValidGgufFile(expectedFile)) expectedFile.absolutePath else {
                val msg = "${model.displayName} is not downloaded"
                _modelError.value = msg
                updateState(model.id, ModelState.NotDownloaded)
                return
            }
        }

        val threads = (nThreads ?: (Runtime.getRuntime().availableProcessors() / 2))
            .coerceAtLeast(1)
        val ctx = nCtx ?: model.contextLength

        val currentId = _activeModelId.value ?: engine.loadedModelId
        val currentConfig = _loadedConfig.value ?: engine.loadedConfig
        if (_activeModelId.value == model.id &&
            engine.isLoaded &&
            _loadedConfig.value == Pair(ctx, threads)
        ) {
            if (state !is ModelState.Loaded) {
                updateState(model.id, ModelState.Loaded(filePath))
            }
            Log.i(TAG, "Model ${model.id} already loaded with matching config; skipping reload")
            return
        }
        if (currentId == model.id &&
            engine.isLoaded &&
            currentConfig == Pair(ctx, threads)
        ) {
            _activeModelId.value = model.id
            _loadedConfig.value = currentConfig
            updateState(model.id, ModelState.Loaded(filePath))
            Log.i(TAG, "Reconciled already-loaded model ${model.id}; skipping reload")
            return
        }

        if (currentId != null && currentId != model.id) {
            unloadCurrentModelLocked()
        }

        updateState(model.id, ModelState.Loading)

        val success = engine.loadModelSafe(
            modelPath = filePath,
            nThreads = threads,
            nCtx = ctx,
            modelId = model.id
        )

        if (success) {
            updateState(model.id, ModelState.Loaded(filePath))
            _activeModelId.value = model.id
            _loadedConfig.value = ctx to threads
            Log.i(TAG, "Model loaded: ${model.id}")
        } else {
            val errDetail = when (val err = engine.error.value) {
                is LlamaEngine.LlamaError.OutOfMemory ->
                    "Failed to load ${model.displayName}: insufficient memory or incompatible GGUF."
                is LlamaEngine.LlamaError.LoadFailed ->
                    "Model file missing or unreadable: ${err.path}"
                is LlamaEngine.LlamaError.InferenceFailed -> err.message
                null -> "Failed to load ${model.displayName} in native engine."
            }
            _modelError.value = errDetail
            // Keep file recoverable as Downloaded if it still exists on disk
            if (File(filePath).exists()) {
                updateState(model.id, ModelState.Downloaded(filePath))
            } else {
                updateState(model.id, ModelState.NotDownloaded)
            }
            _activeModelId.value = null
            _loadedConfig.value = null
            Log.e(TAG, "Failed to load ${model.id}: $errDetail")
        }
    }

    private suspend fun unloadCurrentModelLocked() {
        val currentId = _activeModelId.value ?: engine.loadedModelId
        if (currentId == null && !engine.isLoaded) return

        engine.unloadCurrentModel()

        if (currentId != null) {
            val file = getModelFile(currentId)
            if (file.exists()) {
                updateState(currentId, ModelState.Downloaded(file.absolutePath))
            } else {
                updateState(currentId, ModelState.NotDownloaded)
            }
        }
        _activeModelId.value = null
        _loadedConfig.value = null
        Log.i(TAG, "Model unloaded: $currentId")
    }

    fun clearModelError() {
        _modelError.value = null
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    fun getStorageInfo(): Pair<Long, Long> {
        val modelsDir = getModelsDirectory()
        val usedBytes = modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.sumOf { it.length() }
            ?: 0L

        val freeBytes = try {
            val stat = StatFs(modelsDir.absolutePath)
            stat.availableBytes
        } catch (_: Throwable) {
            modelsDir.usableSpace
        }

        return usedBytes to freeBytes
    }

    fun refreshStorageInfo() {
        _storageInfo.value = getStorageInfo()
    }

    // ── Scan disk ───────────────────────────────────────────────────────────

    suspend fun checkDownloadedModels() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            downloadMutex.withLock {
                checkDownloadedModelsLocked()
            }
        }
    }

    private fun checkDownloadedModelsLocked() {
        val allRows = downloadGateway.queryCatalogDownloads()
        val rowsByModelId = allRows.groupBy { row ->
            ModelCatalog.models.firstOrNull { it.huggingFaceDirectUrl == row.uri }?.id
        }

        val engineLoaded = engine.isLoaded
        val engineModelId = if (engineLoaded) engine.loadedModelId else null
        val engineConfig = if (engineLoaded) engine.loadedConfig else null

        if (engineLoaded && engineModelId != null) {
            _activeModelId.value = engineModelId
            _loadedConfig.value = engineConfig
        } else if (!engineLoaded) {
            _activeModelId.value = null
            _loadedConfig.value = null
        }

        val modelsDir = getModelsDirectory()
        val reconciledActiveId = _activeModelId.value

        _modelStates.update { currentMap ->
            val newStates = currentMap.toMutableMap()
            for (model in ModelCatalog.models) {
                val rowsForModel = rowsByModelId[model.id].orEmpty()
                val activeRow = rowsForModel
                    .filter { it.status in ACTIVE_STATUSES }
                    .maxWithOrNull(ROW_ORDER)

                val currentState = newStates[model.id]
                if (activeRow != null) {
                    // Clean up stale tracking IDs for this model
                    downloadIdToModelId.entries.removeAll { it.value == model.id && it.key != activeRow.id }
                    downloadIdToModelId[activeRow.id] = model.id

                    val prev = currentState as? ModelState.Downloading
                    val progress = if (activeRow.totalBytes > 0) {
                        (activeRow.bytesDownloaded.toFloat() / activeRow.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        prev?.takeIf { it.downloadId == activeRow.id }?.progress ?: 0f
                    }
                    val statusNote = pausedNote(activeRow.status, activeRow.reason)
                    newStates[model.id] = ModelState.Downloading(progress, activeRow.id, statusNote)
                    continue
                }

                // No active download for this model
                downloadIdToModelId.entries.removeAll { it.value == model.id }

                val latestTerminal = rowsForModel
                    .filter { it.status !in ACTIVE_STATUSES }
                    .maxWithOrNull(ROW_ORDER)
                val destFile = File(modelsDir, ModelCatalog.getFileName(model))

                if (latestTerminal?.status == DownloadManager.STATUS_FAILED) {
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    latestTerminal.localUri
                        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        ?.takeIf { it.scheme == "file" }
                        ?.path
                        ?.let { File(it) }
                        ?.takeIf { it.exists() && it.parentFile?.absolutePath == modelsDir.absolutePath }
                        ?.delete()
                    rowsForModel
                        .filter { it.status == DownloadManager.STATUS_FAILED }
                        .forEach { downloadGateway.remove(it.id) }
                    if (currentState !is ModelState.Loading) {
                        newStates[model.id] = ModelState.Error(
                            "Download failed (reason=${latestTerminal.reason})"
                        )
                    }
                    continue
                }

                // Clean up older failed rows if a newer terminal/completed state supersedes them
                rowsForModel
                    .filter { it.status == DownloadManager.STATUS_FAILED }
                    .forEach { downloadGateway.remove(it.id) }

                if (latestTerminal?.status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (!destFile.exists()) {
                        downloadGateway.remove(latestTerminal.id)
                        if (currentState !is ModelState.Loading) {
                            newStates[model.id] = if (latestTerminal.bytesDownloaded > 0L) {
                                ModelState.NotDownloaded
                            } else {
                                ModelState.Error("Downloaded model file is incomplete or corrupt")
                            }
                        }
                        continue
                    }

                    val incompleteBytes = latestTerminal.totalBytes > 0L &&
                        latestTerminal.bytesDownloaded >= 0L &&
                        latestTerminal.bytesDownloaded < latestTerminal.totalBytes
                    if (incompleteBytes || !isValidGgufFile(destFile, latestTerminal.totalBytes)) {
                        if (destFile.exists()) {
                            destFile.delete()
                        }
                        downloadGateway.remove(latestTerminal.id)
                        if (currentState !is ModelState.Loading) {
                            newStates[model.id] = ModelState.Error("Downloaded model file is incomplete or corrupt")
                        }
                        continue
                    }

                    if (currentState !is ModelState.Loading) {
                        val filePath = destFile.absolutePath
                        newStates[model.id] = if (model.id == reconciledActiveId && engineLoaded) {
                            ModelState.Loaded(filePath)
                        } else {
                            ModelState.Downloaded(filePath)
                        }
                    }
                    continue
                }

                // No DownloadManager entry (e.g., pre-pushed or user-copied .gguf file)
                if (destFile.exists()) {
                    if (isValidGgufFile(destFile)) {
                        if (currentState !is ModelState.Loading) {
                            val filePath = destFile.absolutePath
                            newStates[model.id] = if (model.id == reconciledActiveId && engineLoaded) {
                                ModelState.Loaded(filePath)
                            } else {
                                ModelState.Downloaded(filePath)
                            }
                        }
                    } else if (currentState !is ModelState.Loading) {
                        newStates[model.id] = if (destFile.length() == 0L) {
                            ModelState.NotDownloaded
                        } else {
                            ModelState.Error("Model file is incomplete or not a valid GGUF")
                        }
                    }
                } else {
                    if (currentState !is ModelState.Downloading && currentState !is ModelState.Loading) {
                        newStates[model.id] = ModelState.NotDownloaded
                    }
                }
            }
            newStates
        }

        if (downloadIdToModelId.isNotEmpty()) {
            startProgressPolling()
        }
        refreshStorageInfo()
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    fun destroy() {
        if (registerReceiver) {
            try {
                context.unregisterReceiver(downloadCompleteReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was already unregistered
            }
        }
        synchronized(pollingLock) {
            progressPollingJob?.cancel()
            progressPollingJob = null
        }
        scope.cancel()
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun getModelsDirectory(): File = modelsDirProvider().also {
        if (!it.exists()) it.mkdirs()
    }

    private fun getModelFile(modelId: String): File {
        val model = ModelCatalog.getModelById(modelId) ?: return File(getModelsDirectory(), modelId)
        return File(getModelsDirectory(), ModelCatalog.getFileName(model))
    }

    private fun isValidGgufFile(file: File, expectedTotalBytes: Long = -1L): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) return false
        val length = file.length()
        if (length < MIN_GGUF_BYTES) return false
        if (expectedTotalBytes > 0L && length != expectedTotalBytes) return false
        return hasGgufMagic(file)
    }

    private fun hasGgufMagic(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 &&
                    header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun pausedNote(status: Int, reason: Int): String? {
        if (status != DownloadManager.STATUS_PAUSED) return null
        return when (reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI,
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for Wi-Fi…"
            else -> "Paused"
        }
    }

    private fun updateState(modelId: String, state: ModelState) {
        _modelStates.update { current ->
            current.toMutableMap().apply {
                this[modelId] = state
            }
        }
    }

    private fun stopPollingIfIdleLocked() {
        synchronized(pollingLock) {
            if (downloadIdToModelId.isEmpty()) {
                progressPollingJob?.cancel()
                progressPollingJob = null
            }
        }
    }

    private suspend fun handleDownloadTerminalQuery(downloadId: Long, modelId: String) {
        downloadMutex.withLock {
            if (downloadIdToModelId[downloadId] != modelId) return@withLock
            handleDownloadTerminalQueryLocked(downloadId, modelId)
        }
    }

    private fun handleDownloadTerminalQueryLocked(downloadId: Long, modelId: String) {
        when (val result = downloadGateway.queryById(downloadId)) {
            is DownloadQueryResult.Unavailable -> {
                Log.w(TAG, "Download query temporarily unavailable for $downloadId ($modelId)")
                return
            }
            is DownloadQueryResult.NotFound -> {
                downloadIdToModelId.remove(downloadId)
                val destFile = getModelFile(modelId)
                if (destFile.exists()) destFile.delete()
                updateState(modelId, ModelState.NotDownloaded)
            }
            is DownloadQueryResult.Found -> {
                val row = result.row
                when (row.status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED -> {
                        // Non-terminal status: ignore without deleting files or marking Error
                        return
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        downloadIdToModelId.remove(downloadId)
                        val parsedPath = row.localUri
                            ?.let { Uri.parse(it) }
                            ?.takeIf { it.scheme == "file" }
                            ?.path
                        val fallbackFile = getModelFile(modelId)
                        val filePath = parsedPath?.takeIf { File(it).exists() }
                            ?: fallbackFile.absolutePath
                        val targetFile = File(filePath)

                        val incompleteBytes = row.totalBytes > 0L &&
                            row.bytesDownloaded >= 0L &&
                            row.bytesDownloaded < row.totalBytes
                        if (incompleteBytes || !isValidGgufFile(targetFile, row.totalBytes)) {
                            if (targetFile.exists()) targetFile.delete()
                            if (fallbackFile.exists() && fallbackFile.absolutePath != targetFile.absolutePath) {
                                fallbackFile.delete()
                            }
                            updateState(
                                modelId,
                                ModelState.Error("Downloaded file is incomplete or corrupt")
                            )
                            Log.e(TAG, "Completed download validation failed for $modelId")
                        } else {
                            updateState(modelId, ModelState.Downloaded(filePath))
                            Log.i(TAG, "Download complete: $modelId → $filePath")
                        }
                    }
                    else -> {
                        downloadIdToModelId.remove(downloadId)
                        val destFile = getModelFile(modelId)
                        if (destFile.exists()) destFile.delete()
                        updateState(
                            modelId,
                            ModelState.Error("Download failed (status=${row.status}, reason=${row.reason})")
                        )
                        Log.e(TAG, "Download failed for $modelId: status=${row.status} reason=${row.reason}")
                    }
                }
            }
        }

        refreshStorageInfo()
        stopPollingIfIdleLocked()
    }

    private fun startProgressPolling() {
        synchronized(pollingLock) {
            if (progressPollingJob?.isActive == true) return

            progressPollingJob = scope.launch {
                while (isActive) {
                    val activeSnapshot = downloadMutex.withLock {
                        if (downloadIdToModelId.isEmpty()) {
                            stopPollingIfIdleLocked()
                            return@launch
                        }
                        downloadIdToModelId.toMap()
                    }

                    for ((downloadId, modelId) in activeSnapshot) {
                        if (!isActive) break
                        when (val result = downloadGateway.queryById(downloadId)) {
                            is DownloadQueryResult.Unavailable -> Unit
                            is DownloadQueryResult.NotFound -> {
                                downloadMutex.withLock {
                                    if (isActive && downloadIdToModelId[downloadId] == modelId) {
                                        downloadIdToModelId.remove(downloadId)
                                        val file = getModelFile(modelId)
                                        if (file.exists()) {
                                            file.delete()
                                        }
                                        updateState(modelId, ModelState.NotDownloaded)
                                        refreshStorageInfo()
                                        stopPollingIfIdleLocked()
                                    }
                                }
                            }
                            is DownloadQueryResult.Found -> {
                                val row = result.row
                                when (row.status) {
                                    DownloadManager.STATUS_SUCCESSFUL,
                                    DownloadManager.STATUS_FAILED -> {
                                        if (isActive) {
                                            handleDownloadTerminalQuery(downloadId, modelId)
                                        }
                                    }
                                    DownloadManager.STATUS_PENDING,
                                    DownloadManager.STATUS_RUNNING,
                                    DownloadManager.STATUS_PAUSED -> {
                                        downloadMutex.withLock {
                                            if (isActive && downloadIdToModelId[downloadId] == modelId) {
                                                val progress = if (row.totalBytes > 0) {
                                                    (row.bytesDownloaded.toFloat() / row.totalBytes.toFloat())
                                                        .coerceIn(0f, 1f)
                                                } else {
                                                    (_modelStates.value[modelId] as? ModelState.Downloading)
                                                        ?.progress ?: 0f
                                                }
                                                val statusNote = pausedNote(row.status, row.reason)
                                                updateState(
                                                    modelId,
                                                    ModelState.Downloading(progress, downloadId, statusNote)
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        if (isActive) {
                                            handleDownloadTerminalQuery(downloadId, modelId)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!isActive) break
                    val shouldContinue = downloadMutex.withLock {
                        stopPollingIfIdleLocked()
                        downloadIdToModelId.isNotEmpty()
                    }
                    if (!shouldContinue) break
                    delay(PROGRESS_POLL_MS)
                }
            }
        }
    }

    // ── Test helpers ────────────────────────────────────────────────────────

    internal suspend fun onDownloadBroadcastForTest(downloadId: Long) {
        val modelId = downloadIdToModelId[downloadId] ?: return
        handleDownloadTerminalQuery(downloadId, modelId)
    }

    internal fun trackedDownloadCountForTest(): Int = downloadIdToModelId.size
}
