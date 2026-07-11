package com.neurallite.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.neurallite.app.hardware.HardwareScanner
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.models.ModelInfo
import com.neurallite.app.models.ModelManager
import com.neurallite.app.models.ModelState
import com.neurallite.app.server.NeuralliteServerService
import com.neurallite.app.server.ServerEvent
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Primary ViewModel for the Neurallite app.
 *
 * Orchestrates hardware scanning, model management (download / load / unload / delete),
 * the local inference server lifecycle, and user-facing configuration.
 *
 * All mutable state is exposed as [StateFlow]s so Jetpack Compose screens can
 * collect them in a lifecycle-aware manner.
 */
class NeuralliteViewModel(application: Application) : AndroidViewModel(application) {

    // ── Configuration data class ────────────────────────────────────────────

    /**
     * User-configurable settings that influence inference and the HTTP server.
     *
     * @property contextWindow  Maximum context length in tokens.
     * @property threadCount    CPU threads to use for inference; -1 = auto-detect.
     * @property temperature    Sampling temperature (0 = greedy, higher = more random).
     * @property maxTokens      Maximum number of tokens to generate per request.
     * @property serverPort     Port the local HTTP server binds to.
     * @property corsEnabled    Whether the server sends permissive CORS headers.
     * @property mdnsEnabled    Advertise the server via mDNS / Zeroconf.
     * @property wakelockEnabled Keep a partial wake-lock while the server is running.
     */
    data class Config(
        val contextWindow: Int = 2048,
        val threadCount: Int = -1,
        val temperature: Float = 0.7f,
        val maxTokens: Int = 512,
        val serverPort: Int = 8080,
        val corsEnabled: Boolean = true,
        val mdnsEnabled: Boolean = false,
        val wakelockEnabled: Boolean = false,
    )

    // ── Dependencies ────────────────────────────────────────────────────────

    private val hardwareScanner = HardwareScanner(application)
    private val modelManager = ModelManager(application)

    // ── Exposed state ───────────────────────────────────────────────────────

    private val _hardwareProfile = MutableStateFlow<HardwareScanner.HardwareProfile?>(null)
    /** Latest hardware profile obtained from [scanHardware]. */
    val hardwareProfile: StateFlow<HardwareScanner.HardwareProfile?> = _hardwareProfile.asStateFlow()

    private val _modelStates = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    /** Per-model download / load state, keyed by model ID. */
    val modelStates: StateFlow<Map<String, ModelState>> = _modelStates.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    /** ID of the model currently loaded into memory, or `null`. */
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _serverRunning = MutableStateFlow(false)
    /** Whether the local inference server is currently running. */
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()

    private val _serverEvents = MutableStateFlow<List<ServerEvent>>(emptyList())
    /** Rolling window of the most recent 50 server events. */
    val serverEvents: StateFlow<List<ServerEvent>> = _serverEvents.asStateFlow()

    /** The actual server API token */
    val apiToken: StateFlow<String> = NeuralliteServerService.apiToken

    private val _config = MutableStateFlow(Config())
    /** Current user configuration. */
    val config: StateFlow<Config> = _config.asStateFlow()

    // ── Derived convenience state ───────────────────────────────────────────

    /**
     * Recommended models for the current hardware tier.
     * Returns all models when no hardware profile has been scanned yet.
     */
    val recommendedModels: List<ModelInfo>
        get() {
            val tier = _hardwareProfile.value?.tier ?: return ModelCatalog.models
            return ModelCatalog.getRecommendedModels(tier)
        }

    // ── Initialisation ──────────────────────────────────────────────────────

    init {
        // Mirror ModelManager's own StateFlows into the ViewModel's state.
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

        // Track the foreground-service running flag.
        viewModelScope.launch {
            NeuralliteServerService.isRunning.collect { running ->
                _serverRunning.value = running
            }
        }

        // Collect server events, keeping only the last 50.
        viewModelScope.launch {
            NeuralliteServerService.server?.events?.collect { event ->
                _serverEvents.update { current ->
                    (current + event).takeLast(MAX_SERVER_EVENTS)
                }
            }
        }

        // Reconcile on-disk model files with the catalog on startup.
        viewModelScope.launch {
            modelManager.checkDownloadedModels()
        }
    }

    // ── Public actions ──────────────────────────────────────────────────────

    /**
     * Performs an async hardware scan and updates [hardwareProfile].
     */
    fun scanHardware() {
        viewModelScope.launch {
            val profile = hardwareScanner.scan()
            _hardwareProfile.value = profile
        }
    }

    /**
     * Starts downloading the given model in the background.
     * Progress is reflected in [modelStates] via [ModelState.Downloading].
     */
    fun downloadModel(modelInfo: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadModel(modelInfo)
        }
    }

    /**
     * Loads a previously downloaded model into memory for inference.
     * The model's state transitions through [ModelState.Loading] → [ModelState.Loaded].
     */
    fun loadModel(modelId: String) {
        viewModelScope.launch {
            val model = ModelCatalog.getModelById(modelId) ?: return@launch
            modelManager.loadModel(model)
        }
    }

    /**
     * Unloads the currently active model, freeing its memory.
     */
    fun unloadModel() {
        viewModelScope.launch {
            modelManager.unloadCurrentModel()
        }
    }

    /**
     * Deletes the on-disk weights for [modelId].
     * If the model is currently loaded it will be unloaded first.
     */
    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            if (_activeModelId.value == modelId) {
                modelManager.unloadCurrentModel()
            }
            modelManager.deleteModel(modelId)
        }
    }

    /**
     * Starts the local inference HTTP server as a foreground service.
     */
    fun startServer(context: Context) {
        NeuralliteServerService.start(context)
    }

    /**
     * Stops the local inference HTTP server.
     */
    fun stopServer(context: Context) {
        NeuralliteServerService.stop(context)
    }

    /**
     * Replaces the current [Config] atomically.
     */
    fun updateConfig(newConfig: Config) {
        _config.value = newConfig
    }

    /**
     * Returns a pair of (usedBytes, totalBytes) for the device's primary storage.
     */
    fun getStorageInfo(): Pair<Long, Long> = modelManager.getStorageInfo()

    /**
     * Clears the in-memory server event log.
     */
    fun clearServerEvents() {
        _serverEvents.value = emptyList()
    }

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        /** Maximum number of [ServerEvent] entries kept in the rolling buffer. */
        private const val MAX_SERVER_EVENTS = 50
    }
}
