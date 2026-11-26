package com.neurallite.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurallite.app.engine.LlamaEngine
import com.neurallite.app.hardware.HardwareScanner
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.models.ModelInfo
import com.neurallite.app.models.ModelManager
import com.neurallite.app.models.ModelState
import com.neurallite.app.server.NeuralliteServerService
import com.neurallite.app.server.ServerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Primary ViewModel for the Neurallite app.
 *
 * Orchestrates hardware scanning, model management, the local HTTP server
 * lifecycle, persistent configuration, and telemetry flows.
 */
class NeuralliteViewModel(application: Application) : AndroidViewModel(application) {

    data class Config(
        val contextWindow: Int = 2048,
        val threadCount: Int = -1,
        val temperature: Float = 0.7f,
        val maxTokens: Int = 512,
        val serverPort: Int = 8080,
        val corsEnabled: Boolean = true,
        val mdnsEnabled: Boolean = true,
        val wakelockEnabled: Boolean = true,
    )

    // ── Dependencies ────────────────────────────────────────────────────────

    private val configPrefs = application.getSharedPreferences(
        NeuralliteServerService.CONFIG_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val hardwareScanner = HardwareScanner(application)
    private val modelManager = ModelManager(application)

    // ── Exposed state ───────────────────────────────────────────────────────

    private val _hardwareProfile = MutableStateFlow<HardwareScanner.HardwareProfile?>(null)
    val hardwareProfile: StateFlow<HardwareScanner.HardwareProfile?> = _hardwareProfile.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _modelStates = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelState>> = _modelStates.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _storageInfo = MutableStateFlow(0L to 0L)
    val storageInfo: StateFlow<Pair<Long, Long>> = _storageInfo.asStateFlow()

    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    val serverEvents: StateFlow<List<ServerEvent.RequestCompleted>> = NeuralliteServerService.serverEvents
    val totalRequests: StateFlow<Int> = NeuralliteServerService.totalRequests
    val avgTokPerSec: StateFlow<Float> = NeuralliteServerService.avgTokPerSec

    val apiToken: StateFlow<String> = NeuralliteServerService.apiToken
    val lanIp: StateFlow<String> = NeuralliteServerService.lanIp
    val activePort: StateFlow<Int> = NeuralliteServerService.activePort
    val serverError: StateFlow<String?> = NeuralliteServerService.serverError
    val isInferring: StateFlow<Boolean> = LlamaEngine.isInferring
    val loadedModelConfig: StateFlow<Pair<Int, Int>?> = modelManager.loadedConfig

    private val _config = MutableStateFlow(loadPersistedConfig())
    val config: StateFlow<Config> = _config.asStateFlow()

    val recommendedModels: List<ModelInfo>
        get() {
            val tier = _hardwareProfile.value?.tier ?: return ModelCatalog.models
            return ModelCatalog.getRecommendedModels(tier)
        }

    // ── Initialisation ──────────────────────────────────────────────────────

    init {
        _hardwareProfile.value = hardwareScanner.getCachedProfile()

        viewModelScope.launch {
            modelManager.modelStates.collect { states ->
                _modelStates.value = states
            }
        }

        viewModelScope.launch {
            modelManager.activeModelId.collect { id ->
                _activeModelId.value = id
            }
        }

        viewModelScope.launch {
            modelManager.storageInfo.collect { info ->
                _storageInfo.value = info
            }
        }

        viewModelScope.launch {
            modelManager.modelError.collect { err ->
                _modelError.value = err
            }
        }

        viewModelScope.launch {
            NeuralliteServerService.isRunning.collect { running ->
                _serverRunning.value = running
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            NeuralliteServerService.loadOrCreateApiToken(application)
            NeuralliteServerService.refreshLanIp()
        }
    }

    // ── Hardware actions ────────────────────────────────────────────────────

    fun scanHardware() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                _hardwareProfile.value = hardwareScanner.scan()
            } finally {
                _isScanning.value = false
            }
        }
    }

    // ── Model actions ───────────────────────────────────────────────────────

    fun downloadModel(modelInfo: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadModel(modelInfo)
        }
    }

    fun cancelDownload(modelId: String) {
        modelManager.cancelDownload(modelId)
    }

    fun loadModel(modelId: String) {
        viewModelScope.launch {
            val model = ModelCatalog.getModelById(modelId) ?: return@launch
            val cfg = _config.value
            val threads = resolveThreads(cfg.threadCount)
            modelManager.loadModel(model, nThreads = threads, nCtx = cfg.contextWindow)
        }
    }

    fun switchModel(newModelId: String) {
        viewModelScope.launch {
            val model = ModelCatalog.getModelById(newModelId) ?: return@launch
            val cfg = _config.value
            val threads = resolveThreads(cfg.threadCount)
            modelManager.switchModel(model, nThreads = threads, nCtx = cfg.contextWindow)
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            modelManager.unloadCurrentModel()
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            modelManager.deleteModel(modelId)
        }
    }

    fun clearAllModels() {
        viewModelScope.launch {
            modelManager.clearAllModels()
        }
    }

    fun clearModelError() {
        modelManager.clearModelError()
    }

    fun getStorageInfo(): Pair<Long, Long> = modelManager.getStorageInfo()

    // ── Server actions ──────────────────────────────────────────────────────

    fun startServer(context: Context) {
        NeuralliteServerService.clearError()
        NeuralliteServerService.start(context, _config.value.toRuntimeConfig())
    }

    fun stopServer(context: Context) {
        NeuralliteServerService.stop(context)
    }

    fun regenerateApiToken(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            NeuralliteServerService.regenerateApiToken(context)
        }
    }

    fun refreshLanIp() {
        viewModelScope.launch(Dispatchers.IO) {
            NeuralliteServerService.refreshLanIp()
        }
    }

    fun stopInference() {
        LlamaEngine.stopCurrentInference()
    }

    fun clearServerError() {
        NeuralliteServerService.clearError()
    }

    fun clearServerEvents() {
        NeuralliteServerService.clearServerEvents()
    }

    // ── Config persistence & live propagation ───────────────────────────────

    fun updateConfig(newConfig: Config) {
        val previous = _config.value
        _config.value = newConfig
        persistConfig(newConfig)

        NeuralliteServerService.updateLiveServerConfig(
            corsEnabled = newConfig.corsEnabled,
            defaultTemperature = newConfig.temperature,
            defaultMaxTokens = newConfig.maxTokens
        )

        if (_serverRunning.value &&
            (previous.mdnsEnabled != newConfig.mdnsEnabled ||
                previous.wakelockEnabled != newConfig.wakelockEnabled)
        ) {
            val liveConfig = newConfig.toRuntimeConfig().copy(port = activePort.value)
            NeuralliteServerService.start(getApplication(), liveConfig)
        }
    }

    private fun loadPersistedConfig(): Config = Config(
        contextWindow = configPrefs.getInt("contextWindow", 2048).coerceAtLeast(64),
        threadCount = configPrefs.getInt("threadCount", -1),
        temperature = configPrefs.getFloat("temperature", 0.7f).coerceIn(0f, 2f),
        maxTokens = configPrefs.getInt("maxTokens", 512).coerceAtLeast(1),
        serverPort = configPrefs.getInt("serverPort", 8080).coerceIn(1024, 65535),
        corsEnabled = configPrefs.getBoolean("corsEnabled", true),
        mdnsEnabled = configPrefs.getBoolean("mdnsEnabled", true),
        wakelockEnabled = configPrefs.getBoolean("wakelockEnabled", true)
    )

    private fun persistConfig(cfg: Config) {
        configPrefs.edit()
            .putInt("contextWindow", cfg.contextWindow)
            .putInt("threadCount", cfg.threadCount)
            .putFloat("temperature", cfg.temperature)
            .putInt("maxTokens", cfg.maxTokens)
            .putInt("serverPort", cfg.serverPort)
            .putBoolean("corsEnabled", cfg.corsEnabled)
            .putBoolean("mdnsEnabled", cfg.mdnsEnabled)
            .putBoolean("wakelockEnabled", cfg.wakelockEnabled)
            .apply()
    }

    fun resolvedThreadCount(configured: Int = _config.value.threadCount): Int =
        resolveThreads(configured)

    private fun resolveThreads(configured: Int): Int =
        if (configured <= 0) {
            (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        } else {
            configured.coerceAtLeast(1)
        }

    private fun Config.toRuntimeConfig(): NeuralliteServerService.ServerRuntimeConfig =
        NeuralliteServerService.ServerRuntimeConfig(
            port = serverPort,
            corsEnabled = corsEnabled,
            mdnsEnabled = mdnsEnabled,
            wakelockEnabled = wakelockEnabled,
            defaultTemperature = temperature,
            defaultMaxTokens = maxTokens
        )

    override fun onCleared() {
        modelManager.destroy()
        super.onCleared()
    }
}
