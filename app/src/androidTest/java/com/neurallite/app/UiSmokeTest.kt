package com.neurallite.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neurallite.app.engine.LlamaEngine
import com.neurallite.app.server.NeuralliteServerService
import com.neurallite.app.viewmodel.NeuralliteViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeLibraryLoadsWithoutError() {
        assertFalse(LlamaEngine.isLoaded)
    }

    @Test
    fun navigateAcrossAllFourTabsAndVerifyCoreControls() {
        // 1. Detect tab
        composeRule.onNodeWithText("Hardware Detection").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Scan").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Total RAM").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Total RAM").assertIsDisplayed()
        composeRule.onNodeWithText("CPU Cores").assertIsDisplayed()
        composeRule.onNodeWithText("Model Capability Range").assertIsDisplayed()

        // 2. Models tab
        composeRule.onNodeWithText("Models").performClick()
        composeRule.onNodeWithText("Model Storage").assertIsDisplayed()
        composeRule.onNodeWithText("TinyLlama 1.1B").assertIsDisplayed()

        // 3. Server tab
        composeRule.onNodeWithText("Server").performClick()
        composeRule.onNodeWithText("Endpoints").assertIsDisplayed()
        composeRule.onNodeWithText("API Token").assertIsDisplayed()
        composeRule.onNodeWithText("Requests").assertIsDisplayed()
        composeRule.onNodeWithText("Avg tok/s").assertIsDisplayed()
        composeRule.onNodeWithText("Request Log").assertIsDisplayed()

        // 4. Config tab
        composeRule.onNodeWithText("Config").performClick()
        composeRule.onNodeWithText("Configuration").assertIsDisplayed()
        composeRule.onNodeWithText("Inference").assertIsDisplayed()
        composeRule.onNodeWithText("Context Window").assertIsDisplayed()

        // Verify port validation error appears for out-of-range port
        composeRule.onNodeWithText("8080").performTextReplacement("999")
        composeRule.onNodeWithText("Port must be 1024–65535").assertIsDisplayed()
        composeRule.onNodeWithText("999").performTextReplacement("8080")
    }

    private fun httpGet(port: Int, path: String, token: String? = null): Int {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5000
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                if (token != null) {
                    append("Authorization: Bearer ").append(token).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply {
                write(req.toByteArray(Charsets.UTF_8))
                flush()
            }
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: error("No HTTP response for $path")
            return statusLine.split(" ")[1].toInt()
        }
    }

    @Test
    fun serverLifecycleEndpointsTokenRegenerationAndLogClearWorkEndToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        composeRule.onNodeWithText("Server").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            NeuralliteServerService.apiToken.value.isNotBlank()
        }

        // Start server via UI button
        composeRule.onNodeWithContentDescription("Start").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            NeuralliteServerService.isRunning.value
        }
        composeRule.onNodeWithText("Server Running").assertIsDisplayed()

        try {
            val port = NeuralliteServerService.activePort.value
            val initialToken = NeuralliteServerService.apiToken.value

            // Exercise /health and /v1/models (auth failure + auth success)
            assertEquals(200, httpGet(port, "/health"))
            assertEquals(401, httpGet(port, "/v1/models"))
            assertEquals(200, httpGet(port, "/v1/models", initialToken))

            // Verify live request logs appear
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("3").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Clear").assertIsDisplayed()

            // Regenerate API token via UI dialog (before filling log list)
            composeRule.onNodeWithContentDescription("Regenerate").performClick()
            composeRule.onNodeWithText("Regenerate").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                NeuralliteServerService.apiToken.value != initialToken
            }
            val newToken = NeuralliteServerService.apiToken.value
            assertNotEquals(initialToken, newToken)

            assertEquals(401, httpGet(port, "/v1/models", initialToken))
            assertEquals(200, httpGet(port, "/v1/models", newToken))

            // Send 25 more /health calls (30 total > 25 mixed-event threshold) and verify counter hits 30
            repeat(25) {
                assertEquals(200, httpGet(port, "/health"))
            }
            composeRule.onNode(hasScrollAction()).performScrollToIndex(3)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("30").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("30").assertIsDisplayed()

            // Clear logs via UI
            composeRule.onNode(hasScrollAction()).performScrollToIndex(4)
            composeRule.onNodeWithText("Clear").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithText("No requests yet").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("No requests yet").assertIsDisplayed()
            assertTrue(NeuralliteServerService.isRunning.value)
        } finally {
            // Stop server cleanly via UI
            composeRule.onNode(hasScrollAction()).performScrollToIndex(0)
            composeRule.onNodeWithContentDescription("Stop").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                !NeuralliteServerService.isRunning.value
            }
        }
    }

    @Test
    fun configChangesPersistAcrossViewModelRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences(NeuralliteServerService.CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
        val savedSnapshot = prefs.all.toMap()

        val store1 = ViewModelStore()
        val store2 = ViewModelStore()
        try {
            prefs.edit().clear().commit()
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            val vm1 = ViewModelProvider(store1, factory)[NeuralliteViewModel::class.java]
            val custom = NeuralliteViewModel.Config(
                contextWindow = 4096,
                threadCount = 4,
                temperature = 0.3f,
                maxTokens = 1024,
                serverPort = 8091,
                corsEnabled = false,
                mdnsEnabled = false,
                wakelockEnabled = false
            )
            vm1.updateConfig(custom)
            store1.clear()

            val vm2 = ViewModelProvider(store2, factory)[NeuralliteViewModel::class.java]
            assertEquals(custom, vm2.config.value)
        } finally {
            store1.clear()
            store2.clear()
            prefs.edit().apply {
                clear()
                for ((k, v) in savedSnapshot) {
                    when (v) {
                        is Int -> putInt(k, v)
                        is Float -> putFloat(k, v)
                        is Boolean -> putBoolean(k, v)
                        is Long -> putLong(k, v)
                        is String -> putString(k, v)
                    }
                }
                commit()
            }
        }
    }
}
