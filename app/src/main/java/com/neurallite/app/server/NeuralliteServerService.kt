package com.neurallite.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.neurallite.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that keeps the [NeuralliteServer] alive in the
 * background, with configurable Wi-Fi lock, wake lock, mDNS advertisement,
 * persistent encrypted Bearer token, and service-scoped event/IP telemetry.
 */
class NeuralliteServerService : Service() {

    data class ServerRuntimeConfig(
        val port: Int = DEFAULT_PORT,
        val corsEnabled: Boolean = true,
        val mdnsEnabled: Boolean = true,
        val wakelockEnabled: Boolean = true,
        val defaultTemperature: Float = 0.7f,
        val defaultMaxTokens: Int = 512
    )

    companion object {
        private const val TAG = "NeuralliteServerService"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "neurallite_server"
        private const val CHANNEL_NAME = "Server Status"

        private const val ACTION_STOP = "com.neurallite.app.STOP_SERVER"

        private const val PREFS_NAME = "neurallite_secure_prefs"
        private const val KEY_API_TOKEN = "api_token"

        const val CONFIG_PREFS_NAME = "neurallite_config"

        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_CORS_ENABLED = "extra_cors_enabled"
        private const val EXTRA_MDNS_ENABLED = "extra_mdns_enabled"
        private const val EXTRA_WAKELOCK_ENABLED = "extra_wakelock_enabled"
        private const val EXTRA_DEFAULT_TEMP = "extra_default_temp"
        private const val EXTRA_DEFAULT_MAX_TOKENS = "extra_default_max_tokens"
        private const val EXTRA_FORCE_RESTART = "extra_force_restart"

        const val DEFAULT_PORT = 8080

        private const val NSD_SERVICE_TYPE = "_neurallite._tcp."
        private const val NSD_SERVICE_NAME = "Neurallite"
        private const val MAX_SERVER_EVENTS = 50

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        @Volatile
        var server: NeuralliteServer? = null
            private set

        private val _apiToken = MutableStateFlow("")
        val apiToken: StateFlow<String> = _apiToken.asStateFlow()

        private val _lanIp = MutableStateFlow("127.0.0.1")
        val lanIp: StateFlow<String> = _lanIp.asStateFlow()

        private val _activePort = MutableStateFlow(DEFAULT_PORT)
        val activePort: StateFlow<Int> = _activePort.asStateFlow()

        private val _serverError = MutableStateFlow<String?>(null)
        val serverError: StateFlow<String?> = _serverError.asStateFlow()

        private val _events = MutableSharedFlow<ServerEvent>(replay = 0, extraBufferCapacity = 128)
        val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

        private val telemetryLock = Any()

        private val _serverEvents = MutableStateFlow<List<ServerEvent.RequestCompleted>>(emptyList())
        val serverEvents: StateFlow<List<ServerEvent.RequestCompleted>> = _serverEvents.asStateFlow()

        private val _totalRequests = MutableStateFlow(0)
        val totalRequests: StateFlow<Int> = _totalRequests.asStateFlow()

        private val _avgTokPerSec = MutableStateFlow(0f)
        val avgTokPerSec: StateFlow<Float> = _avgTokPerSec.asStateFlow()

        private var totalInferenceTokens = 0L
        private var totalInferenceDurationMs = 0L

        private val tokenLock = Any()
        private val configLock = Any()

        @Volatile
        private var lastConfig: ServerRuntimeConfig? = null

        fun start(context: Context, config: ServerRuntimeConfig? = null, forceRestart: Boolean = false) {
            val resolved = synchronized(configLock) {
                val next = config ?: lastConfig
                if (next != null) {
                    lastConfig = next
                }
                next
            }
            val intent = Intent(context, NeuralliteServerService::class.java).apply {
                if (resolved != null) {
                    putExtra(EXTRA_PORT, resolved.port)
                    putExtra(EXTRA_CORS_ENABLED, resolved.corsEnabled)
                    putExtra(EXTRA_MDNS_ENABLED, resolved.mdnsEnabled)
                    putExtra(EXTRA_WAKELOCK_ENABLED, resolved.wakelockEnabled)
                    putExtra(EXTRA_DEFAULT_TEMP, resolved.defaultTemperature)
                    putExtra(EXTRA_DEFAULT_MAX_TOKENS, resolved.defaultMaxTokens)
                }
                putExtra(EXTRA_FORCE_RESTART, forceRestart)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NeuralliteServerService::class.java)
            context.stopService(intent)
        }

        fun clearError() {
            _serverError.value = null
        }

        fun clearServerEvents() {
            synchronized(telemetryLock) {
                _serverEvents.value = emptyList()
                _totalRequests.value = 0
                totalInferenceTokens = 0L
                totalInferenceDurationMs = 0L
                _avgTokPerSec.value = 0f
            }
        }

        fun updateLiveServerConfig(
            corsEnabled: Boolean,
            defaultTemperature: Float,
            defaultMaxTokens: Int
        ) {
            synchronized(configLock) {
                lastConfig = lastConfig?.copy(
                    corsEnabled = corsEnabled,
                    defaultTemperature = defaultTemperature,
                    defaultMaxTokens = defaultMaxTokens
                )
                server?.let { activeServer ->
                    activeServer.corsEnabled = corsEnabled
                    activeServer.defaultTemperature = defaultTemperature
                    activeServer.defaultMaxTokens = defaultMaxTokens
                }
            }
        }

        internal fun getLastConfigForTest(): ServerRuntimeConfig? =
            synchronized(configLock) { lastConfig }

        internal fun setLastConfigForTest(config: ServerRuntimeConfig?) {
            synchronized(configLock) {
                lastConfig = config
            }
        }

        internal fun recordServerEvent(event: ServerEvent) {
            _events.tryEmit(event)
            if (event is ServerEvent.RequestCompleted) {
                synchronized(telemetryLock) {
                    _totalRequests.value += 1
                    if (event.tokensGenerated > 0) {
                        totalInferenceTokens += event.tokensGenerated.toLong()
                        totalInferenceDurationMs += event.durationMs.coerceAtLeast(1L)
                        _avgTokPerSec.value =
                            totalInferenceTokens.toFloat() / totalInferenceDurationMs.toFloat() * 1000f
                    }
                    _serverEvents.value = (_serverEvents.value + event).takeLast(MAX_SERVER_EVENTS)
                }
            }
        }

        fun loadOrCreateApiToken(context: Context): String = synchronized(tokenLock) {
            val prefs = getEncryptedPrefsSafe(context.applicationContext)
            var token = prefs.getString(KEY_API_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_API_TOKEN, token).apply()
                Log.i(TAG, "Generated new API token")
            }
            _apiToken.value = token
            token
        }

        fun regenerateApiToken(context: Context): String = synchronized(tokenLock) {
            val appCtx = context.applicationContext
            val prefs = getEncryptedPrefsSafe(appCtx)
            val newToken = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_API_TOKEN, newToken).commit()
            _apiToken.value = newToken
            // Rotate token seamlessly on live server without dropping socket
            server?.apiToken = newToken
            Log.i(TAG, "Regenerated API token")
            newToken
        }

        fun refreshLanIp(): String {
            val ip = discoverLanIpv4()
            _lanIp.value = ip
            return ip
        }

        private fun getEncryptedPrefsSafe(context: Context): SharedPreferences {
            return try {
                createEncryptedPrefs(context)
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences read failed, resetting: ${e.message}", e)
                try {
                    context.deleteSharedPreferences(PREFS_NAME)
                } catch (_: Exception) {
                }
                try {
                    createEncryptedPrefs(context)
                } catch (e2: Exception) {
                    Log.e(TAG, "Recreating master key after keystore mismatch: ${e2.message}", e2)
                    try {
                        val ks = KeyStore.getInstance("AndroidKeyStore")
                        ks.load(null)
                        ks.deleteEntry("_androidx_security_master_key_")
                    } catch (_: Exception) {
                    }
                    try {
                        context.deleteSharedPreferences(PREFS_NAME)
                    } catch (_: Exception) {
                    }
                    createEncryptedPrefs(context)
                }
            }
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun discoverLanIpv4(): String {
            return try {
                var fallbackSiteLocal: String? = null
                var fallbackAny: String? = null
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val name = iface.name?.lowercase() ?: ""
                    val isPreferred = name.startsWith("wlan") ||
                        name.startsWith("eth") ||
                        name.startsWith("ap") ||
                        name.startsWith("swlan")

                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            if (isPreferred) return host
                            if (addr.isSiteLocalAddress) {
                                if (fallbackSiteLocal == null) fallbackSiteLocal = host
                            } else if (fallbackAny == null) {
                                fallbackAny = host
                            }
                        }
                    }
                }
                fallbackSiteLocal ?: fallbackAny ?: "127.0.0.1"
            } catch (e: Exception) {
                Log.w(TAG, "Failed to discover LAN IP: ${e.message}")
                "127.0.0.1"
            }
        }

        private fun loadPersistedRuntimeConfig(context: Context): ServerRuntimeConfig {
            val prefs = context.getSharedPreferences(CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
            return ServerRuntimeConfig(
                port = prefs.getInt("serverPort", DEFAULT_PORT),
                corsEnabled = prefs.getBoolean("corsEnabled", true),
                mdnsEnabled = prefs.getBoolean("mdnsEnabled", true),
                wakelockEnabled = prefs.getBoolean("wakelockEnabled", true),
                defaultTemperature = prefs.getFloat("temperature", 0.7f),
                defaultMaxTokens = prefs.getInt("maxTokens", 512)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverMutex = Mutex()
    private val stateLock = Any()
    private val nsdLock = Any()
    private val startSeq = AtomicLong(0L)

    @Volatile
    private var isDestroyed = false

    private var startJob: Job? = null
    private var _serverInstance: NeuralliteServer? = null
    @Volatile
    private var currentPort: Int = DEFAULT_PORT
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdManager: NsdManager? = null

    @Volatile
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile
    private var nsdRegistered = false

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isDestroyed = false
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startSeq.incrementAndGet()
            startJob?.cancel()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hasConfigExtras = intent?.hasExtra(EXTRA_PORT) == true
        val initialPort = if (hasConfigExtras) {
            intent!!.getIntExtra(EXTRA_PORT, DEFAULT_PORT).coerceIn(1024, 65535)
        } else {
            synchronized(configLock) { lastConfig?.port } ?: currentPort
        }

        val initialNotification = buildNotification(initialPort, _lanIp.value)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            fgsType
        )

        val extraPort = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val extraCors = intent?.getBooleanExtra(EXTRA_CORS_ENABLED, true) ?: true
        val extraMdns = intent?.getBooleanExtra(EXTRA_MDNS_ENABLED, true) ?: true
        val extraWakelock = intent?.getBooleanExtra(EXTRA_WAKELOCK_ENABLED, true) ?: true
        val extraTemp = intent?.getFloatExtra(EXTRA_DEFAULT_TEMP, 0.7f) ?: 0.7f
        val extraMaxTokens = intent?.getIntExtra(EXTRA_DEFAULT_MAX_TOKENS, 512) ?: 512
        val forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART, false) ?: false

        val seq = startSeq.incrementAndGet()
        startJob?.cancel()
        startJob = serviceScope.launch {
            serverMutex.withLock {
                if (!isActive || isDestroyed || startSeq.get() != seq) return@withLock

                val fallback = synchronized(configLock) { lastConfig }
                    ?: loadPersistedRuntimeConfig(this@NeuralliteServerService)
                val resolvedConfig = if (hasConfigExtras) {
                    ServerRuntimeConfig(
                        port = extraPort.coerceIn(1024, 65535),
                        corsEnabled = extraCors,
                        mdnsEnabled = extraMdns,
                        wakelockEnabled = extraWakelock,
                        defaultTemperature = extraTemp,
                        defaultMaxTokens = extraMaxTokens
                    )
                } else {
                    fallback
                }
                synchronized(configLock) {
                    lastConfig = resolvedConfig
                }

                val token = loadOrCreateApiToken(this@NeuralliteServerService)
                val lanIpAddress = refreshLanIp()

                if (!isActive || isDestroyed || startSeq.get() != seq) return@withLock

                val liveConfig = synchronized(configLock) { lastConfig } ?: resolvedConfig
                updateNotification(liveConfig.port, lanIpAddress)

                // Idempotent update if already running on the same port and not forcing restart
                val (existing, runningPort) = synchronized(stateLock) {
                    _serverInstance to currentPort
                }
                if (existing != null && runningPort == liveConfig.port && !forceRestart) {
                    synchronized(configLock) {
                        val latest = lastConfig ?: liveConfig
                        existing.apiToken = token
                        existing.corsEnabled = latest.corsEnabled
                        existing.defaultTemperature = latest.defaultTemperature
                        existing.defaultMaxTokens = latest.defaultMaxTokens
                    }

                    applyLocks(liveConfig.wakelockEnabled)
                    applyMdns(liveConfig.mdnsEnabled, liveConfig.port)
                    _serverError.value = null
                    _activePort.value = liveConfig.port
                    _isRunning.value = true
                    return@withLock
                }

                // Tear down existing server if restarting or port changed
                if (existing != null) {
                    synchronized(stateLock) {
                        _serverInstance = null
                        server = null
                    }
                    existing.stop()
                    unregisterNsd()
                }

                if (!isActive || isDestroyed || startSeq.get() != seq) return@withLock

                applyLocks(liveConfig.wakelockEnabled)

                try {
                    val newServer = NeuralliteServer(
                        port = liveConfig.port,
                        apiToken = token,
                        corsEnabled = liveConfig.corsEnabled,
                        defaultTemperature = liveConfig.defaultTemperature,
                        defaultMaxTokens = liveConfig.defaultMaxTokens,
                        onEvent = { event -> recordServerEvent(event) }
                    ).also { it.start() }

                    val published = synchronized(stateLock) {
                        if (isDestroyed || !isActive || startSeq.get() != seq) {
                            false
                        } else {
                            _serverInstance = newServer
                            server = newServer
                            currentPort = liveConfig.port
                            true
                        }
                    }

                    if (!published) {
                        newServer.stop()
                        return@withLock
                    }

                    synchronized(configLock) {
                        lastConfig?.let { latest ->
                            newServer.corsEnabled = latest.corsEnabled
                            newServer.defaultTemperature = latest.defaultTemperature
                            newServer.defaultMaxTokens = latest.defaultMaxTokens
                        }
                    }
                    synchronized(tokenLock) {
                        val currentToken = _apiToken.value
                        if (currentToken.isNotBlank()) {
                            newServer.apiToken = currentToken
                        }
                    }

                    _activePort.value = liveConfig.port
                    _serverError.value = null
                    _isRunning.value = true

                    applyMdns(liveConfig.mdnsEnabled, liveConfig.port)
                    Log.i(TAG, "Server started on port ${liveConfig.port}, IP=$lanIpAddress")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isDestroyed && startSeq.get() == seq) {
                        val errMsg =
                            "Failed to start server on port ${liveConfig.port}: ${e.message ?: "unknown error"}"
                        Log.e(TAG, errMsg, e)
                        _serverError.value = errMsg
                        recordServerEvent(ServerEvent.ErrorOccurred(errMsg))
                        _isRunning.value = false
                        synchronized(stateLock) {
                            releaseWifiLock()
                            releaseWakeLock()
                        }
                        ServiceCompat.stopForeground(
                            this@NeuralliteServerService,
                            ServiceCompat.STOP_FOREGROUND_REMOVE
                        )
                        stopSelf()
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        val existing: NeuralliteServer?
        synchronized(stateLock) {
            isDestroyed = true
            _isRunning.value = false
            existing = _serverInstance
            _serverInstance = null
            server = null
            releaseWifiLock()
            releaseWakeLock()
        }
        startSeq.incrementAndGet()
        startJob?.cancel()
        serviceScope.cancel()

        existing?.stop()
        unregisterNsd()
        Log.i(TAG, "Server stopped")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the Neurallite inference server is running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(port: Int, lanIp: String) {
        if (isDestroyed) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(port, lanIp))
    }

    private fun buildNotification(port: Int, lanIp: String): Notification {
        val stopIntent = Intent(this, NeuralliteServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neurallite Server Running")
            .setContentText("Listening on port $port · $lanIp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Server",
                stopPendingIntent
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── Locks ───────────────────────────────────────────────────────────────

    private fun applyLocks(enabled: Boolean) {
        synchronized(stateLock) {
            if (enabled) {
                if (isDestroyed) return
                if (wifiLock == null) acquireWifiLock()
                if (wakeLock == null) acquireWakeLock()
            } else {
                releaseWifiLock()
                releaseWakeLock()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "neurallite:server"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WifiLock acquired")
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WifiLock released")
            }
        }
        wifiLock = null
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "neurallite:server_wake"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    // ── NSD / mDNS ──────────────────────────────────────────────────────────

    private fun applyMdns(enabled: Boolean, port: Int) {
        if (enabled) {
            val shouldRegister = synchronized(nsdLock) {
                !isDestroyed && registrationListener == null
            }
            if (shouldRegister) {
                registerNsd(port)
            }
        } else {
            unregisterNsd()
        }
    }

    private fun registerNsd(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = NSD_SERVICE_NAME
            serviceType = NSD_SERVICE_TYPE
            setPort(port)
        }

        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                val isCurrent = synchronized(nsdLock) {
                    if (registrationListener === this && !isDestroyed) {
                        nsdRegistered = true
                        true
                    } else {
                        false
                    }
                }
                if (!isCurrent) {
                    // Service stopped or mDNS disabled before NSD callback arrived; unregister immediately
                    try {
                        manager.unregisterService(this)
                    } catch (_: Exception) {
                    }
                    return
                }
                Log.i(TAG, "mDNS registered: ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(nsdLock) {
                    if (registrationListener === this) {
                        nsdRegistered = false
                        registrationListener = null
                    }
                }
                Log.e(TAG, "mDNS registration failed: error=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                synchronized(nsdLock) {
                    if (registrationListener === this) {
                        nsdRegistered = false
                        registrationListener = null
                    }
                }
                Log.i(TAG, "mDNS unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(nsdLock) {
                    if (registrationListener === this) {
                        nsdRegistered = false
                        registrationListener = null
                    }
                }
                Log.e(TAG, "mDNS unregistration failed: error=$errorCode")
            }
        }

        val registered = synchronized(nsdLock) {
            if (isDestroyed || registrationListener != null) {
                false
            } else {
                nsdManager = manager
                registrationListener = listener
                nsdRegistered = false
                true
            }
        }
        if (!registered) return

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            synchronized(nsdLock) {
                if (registrationListener === listener) {
                    registrationListener = null
                    nsdRegistered = false
                }
            }
            Log.w(TAG, "Error registering NSD: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        val listenerToUnregister: NsdManager.RegistrationListener?
        val manager: NsdManager?
        synchronized(nsdLock) {
            val listener = registrationListener ?: return
            val wasRegistered = nsdRegistered
            registrationListener = null
            nsdRegistered = false
            listenerToUnregister = if (wasRegistered) listener else null
            manager = nsdManager
        }
        if (listenerToUnregister != null) {
            try {
                manager?.unregisterService(listenerToUnregister)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NSD: ${e.message}")
            }
        }
    }
}
