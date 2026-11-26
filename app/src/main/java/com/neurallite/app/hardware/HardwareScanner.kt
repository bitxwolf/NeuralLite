package com.neurallite.app.hardware

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES10
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLSurface

/**
 * Scans device hardware capabilities and assigns a performance tier.
 *
 * Tier definitions:
 *  - TIER 1 (Flagship):  totalRAM >= 8 GB AND cores >= 8
 *  - TIER 2 (Mid-range): totalRAM >= 4 GB AND cores >= 6
 *  - TIER 3 (Entry):     totalRAM >= 3 GB AND cores >= 4
 *  - TIER 4 (Minimal):   anything below Tier 3
 */
class HardwareScanner(private val context: Context) {

    companion object {
        private const val TAG = "HardwareScanner"
        private const val PREFS_NAME = "neurallite_hardware"
        private const val KEY_HW_PROFILE = "hw_profile"
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES2_BIT = 4

        const val GB = 1_073_741_824L // 1 GB in bytes

        fun computeTier(totalRamBytes: Long, cores: Int): Int = when {
            totalRamBytes >= 8 * GB && cores >= 8 -> 1
            totalRamBytes >= 4 * GB && cores >= 6 -> 2
            totalRamBytes >= 3 * GB && cores >= 4 -> 3
            else -> 4
        }
    }

    // ── Data class ──────────────────────────────────────────────────────────

    data class HardwareProfile(
        val totalRamBytes: Long,
        val availRamBytes: Long,
        val cpuCores: Int,
        val cpuAbi: String,
        val gpuRenderer: String,
        val freeStorageBytes: Long,
        val apiLevel: Int,
        val tier: Int
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("totalRamBytes", totalRamBytes)
            put("availRamBytes", availRamBytes)
            put("cpuCores", cpuCores)
            put("cpuAbi", cpuAbi)
            put("gpuRenderer", gpuRenderer)
            put("freeStorageBytes", freeStorageBytes)
            put("apiLevel", apiLevel)
            put("tier", tier)
        }

        companion object {
            fun fromJson(json: JSONObject): HardwareProfile = HardwareProfile(
                totalRamBytes = json.getLong("totalRamBytes"),
                availRamBytes = json.getLong("availRamBytes"),
                cpuCores = json.getInt("cpuCores"),
                cpuAbi = json.getString("cpuAbi"),
                gpuRenderer = json.getString("gpuRenderer"),
                freeStorageBytes = json.getLong("freeStorageBytes"),
                apiLevel = json.getInt("apiLevel"),
                tier = json.getInt("tier")
            )
        }

        val tierLabel: String
            get() = when (tier) {
                1 -> "Flagship"
                2 -> "Mid-range"
                3 -> "Entry"
                else -> "Minimal"
            }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    suspend fun scan(): HardwareProfile = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting hardware scan…")

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem
        val availRam = memInfo.availMem
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val gpuRenderer = detectGpuRenderer()

        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val freeStorage = statFs.availableBytes
        val apiLevel = Build.VERSION.SDK_INT
        val tier = computeTier(totalRam, cpuCores)

        val profile = HardwareProfile(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            cpuCores = cpuCores,
            cpuAbi = cpuAbi,
            gpuRenderer = gpuRenderer,
            freeStorageBytes = freeStorage,
            apiLevel = apiLevel,
            tier = tier
        )

        cacheProfile(profile)

        Log.i(
            TAG,
            "Scan complete — Tier $tier (${profile.tierLabel}): " +
                "RAM=${totalRam / GB}GB, cores=$cpuCores, GPU=$gpuRenderer, ABI=$cpuAbi, API=$apiLevel"
        )

        profile
    }

    fun getCachedProfile(): HardwareProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HW_PROFILE, null) ?: return null
        return try {
            HardwareProfile.fromJson(JSONObject(json))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse cached profile: ${e.message}")
            null
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun detectGpuRenderer(): String {
        val egl = EGLContext.getEGL() as? EGL10 ?: return "Unknown GPU"
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (display === EGL10.EGL_NO_DISPLAY) return "Unknown GPU"
        val version = IntArray(2)
        if (!egl.eglInitialize(display, version)) return "Unknown GPU"

        var context: EGLContext = EGL10.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL10.EGL_NO_SURFACE
        return try {
            val configSpec = intArrayOf(
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!egl.eglChooseConfig(display, configSpec, configs, 1, numConfigs) || numConfigs[0] <= 0) {
                return "Unknown GPU"
            }
            val config = configs[0] ?: return "Unknown GPU"
            val contextAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
            context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
            if (context === EGL10.EGL_NO_CONTEXT) return "Unknown GPU"

            val surfaceAttribs = intArrayOf(
                EGL10.EGL_WIDTH, 1,
                EGL10.EGL_HEIGHT, 1,
                EGL10.EGL_NONE
            )
            surface = egl.eglCreatePbufferSurface(display, config, surfaceAttribs)
            if (surface === EGL10.EGL_NO_SURFACE) return "Unknown GPU"

            if (!egl.eglMakeCurrent(display, surface, surface, context)) return "Unknown GPU"
            GLES10.glGetString(GLES10.GL_RENDERER)?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown GPU"
        } catch (e: Exception) {
            Log.w(TAG, "GPU detection failed: ${e.message}")
            "Unknown GPU"
        } finally {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            if (surface !== EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface)
            if (context !== EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)
        }
    }

    private fun cacheProfile(profile: HardwareProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HW_PROFILE, profile.toJson().toString())
            .apply()
    }
}
