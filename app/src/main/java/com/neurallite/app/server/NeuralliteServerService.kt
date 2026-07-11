package com.neurallite.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Foreground service that keeps the [NeuralliteServer] alive in the
 * background, with Wi-Fi lock, wake lock, and mDNS advertisement.
 *
 * Start with `Context.startForegroundService(intent)`.
 * Stop with `Context.stopService(intent)` or the notification "Stop Server" action.
 */
class NeuralliteServerService : Service() {

    companion object {
        private const val TAG = "NeuralliteServerService"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "neurallite_server"
        private const val CHANNEL_NAME = "Server Status"

        private const val ACTION_STOP = "com.neurallite.app.STOP_SERVER"

        private const val PREFS_NAME = "neurallite_secure_prefs"
        private const val KEY_API_TOKEN = "api_token"

        private const val SERVER_PORT = 8080

        private const val NSD_SERVICE_TYPE = "_neurallite._tcp."
        private const val NSD_SERVICE_NAME = "Neurallite"

        // Observable running state for UI
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // Reference to the active server instance for event collection
        var server: NeuralliteServer? = null
            private set

        // Active API token
        private val _apiToken = MutableStateFlow("")
        val apiToken: StateFlow<String> = _apiToken.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, NeuralliteServerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NeuralliteServerService::class.java)
            context.stopService(intent)
        }
    }


    private var _serverInstance: NeuralliteServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistered = false

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle the "Stop Server" notification action
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val apiToken = getOrCreateApiToken()
        val wifiIp = getWifiIpAddress()

        // Build and post foreground notification
        val notification = buildNotification(wifiIp)
        startForeground(NOTIFICATION_ID, notification)

        // Acquire locks
        acquireWifiLock()
        acquireWakeLock()

        // Start HTTP server
        try {
            _serverInstance = NeuralliteServer(SERVER_PORT, apiToken).also { it.start() }
            server = _serverInstance
            Log.i(TAG, "Server started on port $SERVER_PORT, IP=$wifiIp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Register mDNS / NSD
        registerNsd()

        _isRunning.value = true

        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false

        // Stop HTTP server
        _serverInstance?.stop()
        _serverInstance = null
        server = null
        Log.i(TAG, "Server stopped")

        // Unregister mDNS
        unregisterNsd()

        // Release locks
        releaseWifiLock()
        releaseWakeLock()

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

    private fun buildNotification(wifiIp: String): Notification {
        // "Stop Server" action
        val stopIntent = Intent(this, NeuralliteServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neurallite Server Running")
            .setContentText("Listening on port $SERVER_PORT · $wifiIp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Server",
                stopPendingIntent
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── API Token (EncryptedSharedPreferences) ──────────────────────────────

    private fun getOrCreateApiToken(): String {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

        val prefs = EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        var token = prefs.getString(KEY_API_TOKEN, null)
        if (token.isNullOrBlank()) {
            token = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_API_TOKEN, token).apply()
            Log.i(TAG, "Generated new API token")
        }
        _apiToken.value = token
        return token
    }

    // ── Wi-Fi IP ────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String {
        val wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress

        return if (ipInt == 0) {
            "127.0.0.1"
        } else {
            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff
            )
        }
    }

    // ── Wi-Fi Lock ──────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "neurallite:server"
        ).apply { acquire() }
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

    // ── Wake Lock ───────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "neurallite:server_wake"
        ).apply { acquire() }
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

    private fun registerNsd() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = NSD_SERVICE_NAME
            serviceType = NSD_SERVICE_TYPE
            port = SERVER_PORT
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        nsdManager?.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    nsdRegistered = true
                    Log.i(TAG, "mDNS registered: ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS registration failed: error=$errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    nsdRegistered = false
                    Log.i(TAG, "mDNS unregistered")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS unregistration failed: error=$errorCode")
                }
            }
        )
    }

    private fun unregisterNsd() {
        if (nsdRegistered) {
            try {
                nsdManager?.unregisterService(object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {}
                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceUnregistered(info: NsdServiceInfo) {
                        nsdRegistered = false
                    }
                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                })
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NSD: ${e.message}")
            }
        }
    }
}
