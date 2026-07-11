package com.neurallite.app

import android.app.Application
import android.util.Log

/**
 * Neurallite [Application] entry-point.
 *
 * Performs lightweight bootstrap work (logging, crash-safe init).
 * Heavy subsystem initialisation (model loading, server startup) is
 * deferred to the relevant components so the cold-start path stays fast.
 *
 * Declare in AndroidManifest.xml:
 * ```xml
 * <application
 *     android:name=".NeuralliteApp"
 *     … />
 * ```
 */
class NeuralliteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Neurallite application created")
        Log.d(TAG, "Process: ${android.os.Process.myPid()}, SDK: ${android.os.Build.VERSION.SDK_INT}")
    }

    companion object {
        private const val TAG = "NeuralliteApp"

        @Volatile
        private lateinit var instance: NeuralliteApp

        /**
         * Returns the global [NeuralliteApp] singleton.
         *
         * Safe to call from any thread after [onCreate] has completed.
         */
        fun getInstance(): NeuralliteApp = instance
    }
}
