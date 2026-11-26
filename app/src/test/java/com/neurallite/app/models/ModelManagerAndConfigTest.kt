package com.neurallite.app.models

import android.app.Application
import android.app.DownloadManager
import com.neurallite.app.engine.LlamaEngine
import com.neurallite.app.server.NeuralliteServerService
import com.neurallite.app.server.ServerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ModelManagerAndConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeDownloadGateway : ModelManager.DownloadGateway {
        private val nextId = AtomicLong(100L)
        val rows = ConcurrentHashMap<Long, ModelManager.DownloadRow>()
        val removedIds = mutableListOf<Long>()
        var enqueueException: Exception? = null
        var queryDelayMs: Long = 0L

        override fun enqueue(model: ModelInfo): Long {
            enqueueException?.let { throw it }
            val id = nextId.incrementAndGet()
            rows[id] = ModelManager.DownloadRow(
                id = id,
                uri = model.huggingFaceDirectUrl,
                status = DownloadManager.STATUS_RUNNING,
                bytesDownloaded = 0L,
                totalBytes = 100L,
                reason = 0,
                localUri = null,
                lastModifiedMs = System.currentTimeMillis()
            )
            return id
        }

        override fun remove(downloadId: Long) {
            removedIds.add(downloadId)
            rows.remove(downloadId)
        }

        override fun queryById(downloadId: Long): ModelManager.DownloadQueryResult {
            if (queryDelayMs > 0L) {
                Thread.sleep(queryDelayMs)
            }
            val row = rows[downloadId] ?: return ModelManager.DownloadQueryResult.NotFound
            return ModelManager.DownloadQueryResult.Found(row)
        }

        override fun queryCatalogDownloads(): List<ModelManager.DownloadRow> =
            rows.values.toList()
    }

    private class FakeEngineGateway : ModelManager.EngineGateway {
        override var isLoaded: Boolean = false
        override var loadedModelId: String? = null
        override var loadedConfig: Pair<Int, Int>? = null
        private val _error = MutableStateFlow<LlamaEngine.LlamaError?>(null)
        override val error: StateFlow<LlamaEngine.LlamaError?> = _error

        val loadCallCount = AtomicInteger(0)
        val unloadCallCount = AtomicInteger(0)
        var loadResult = true

        override suspend fun loadModelSafe(
            modelPath: String,
            nThreads: Int,
            nCtx: Int,
            modelId: String
        ): Boolean {
            loadCallCount.incrementAndGet()
            if (!loadResult) return false
            isLoaded = true
            loadedModelId = modelId
            loadedConfig = nCtx to nThreads
            return true
        }

        override suspend fun unloadCurrentModel() {
            unloadCallCount.incrementAndGet()
            isLoaded = false
            loadedModelId = null
            loadedConfig = null
        }
    }

    private lateinit var modelsDir: File
    private lateinit var gateway: FakeDownloadGateway
    private lateinit var engine: FakeEngineGateway
    private lateinit var manager: ModelManager
    private val testModel = ModelCatalog.models.first()

    @Before
    fun setUp() {
        modelsDir = tempFolder.newFolder("models")
        gateway = FakeDownloadGateway()
        engine = FakeEngineGateway()
        manager = ModelManager(
            context = Application(),
            downloadGateway = gateway,
            engine = engine,
            modelsDirProvider = { modelsDir },
            registerReceiver = false,
            runInitialCheck = false
        )
        NeuralliteServerService.clearServerEvents()
    }

    @After
    fun tearDown() {
        manager.destroy()
        NeuralliteServerService.clearServerEvents()
        NeuralliteServerService.setLastConfigForTest(null)
    }

    private fun writeValidGgufFile(model: ModelInfo, sizeBytes: Int = 64): File {
        val file = File(modelsDir, ModelCatalog.getFileName(model))
        val bytes = ByteArray(sizeBytes)
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `checkDownloadedModels deletes partial gguf on STATUS_FAILED and marks Error`() = runBlocking {
        val partialFile = writeValidGgufFile(testModel, sizeBytes = 128)
        assertTrue(partialFile.exists())

        gateway.rows[42L] = ModelManager.DownloadRow(
            id = 42L,
            uri = testModel.huggingFaceDirectUrl,
            status = DownloadManager.STATUS_FAILED,
            bytesDownloaded = 128L,
            totalBytes = 1024L,
            reason = DownloadManager.ERROR_HTTP_DATA_ERROR,
            localUri = null,
            lastModifiedMs = 1000L
        )

        manager.checkDownloadedModels()

        assertFalse("Partial file from failed download must be deleted", partialFile.exists())
        assertTrue(42L in gateway.removedIds)
        val state = manager.modelStates.value[testModel.id]
        assertTrue("Expected Error state, got $state", state is ModelState.Error)
    }

    @Test
    fun `checkDownloadedModels rejects truncated or non-GGUF file when STATUS_SUCCESSFUL`() = runBlocking {
        val file = File(modelsDir, ModelCatalog.getFileName(testModel))
        file.writeText("<html>error page</html>" + "x".repeat(40))

        gateway.rows[55L] = ModelManager.DownloadRow(
            id = 55L,
            uri = testModel.huggingFaceDirectUrl,
            status = DownloadManager.STATUS_SUCCESSFUL,
            bytesDownloaded = file.length(),
            totalBytes = file.length(),
            reason = 0,
            localUri = null,
            lastModifiedMs = 2000L
        )

        manager.checkDownloadedModels()

        assertFalse("Invalid non-GGUF file should be deleted", file.exists())
        assertTrue(manager.modelStates.value[testModel.id] is ModelState.Error)
    }

    @Test
    fun `checkDownloadedModels accepts legitimate pre-pushed GGUF file without DownloadManager row`() = runBlocking {
        val validFile = writeValidGgufFile(testModel, sizeBytes = 128)

        manager.checkDownloadedModels()

        assertTrue(validFile.exists())
        val state = manager.modelStates.value[testModel.id]
        assertEquals(ModelState.Downloaded(validFile.absolutePath), state)
    }

    @Test
    fun `checkDownloadedModels reconciles activeModelId and loadedConfig from engine`() = runBlocking {
        val validFile = writeValidGgufFile(testModel, sizeBytes = 128)
        engine.isLoaded = true
        engine.loadedModelId = testModel.id
        engine.loadedConfig = 2048 to 4

        manager.checkDownloadedModels()

        assertEquals(testModel.id, manager.activeModelId.value)
        assertEquals(2048 to 4, manager.loadedConfig.value)
        assertEquals(ModelState.Loaded(validFile.absolutePath), manager.modelStates.value[testModel.id])
    }

    @Test
    fun `deleteModel unloads native engine even before activeModelId is populated`() = runBlocking {
        val validFile = writeValidGgufFile(testModel, sizeBytes = 128)
        engine.isLoaded = true
        engine.loadedModelId = testModel.id
        engine.loadedConfig = 2048 to 4

        manager.deleteModel(testModel.id)

        assertEquals(1, engine.unloadCallCount.get())
        assertFalse(engine.isLoaded)
        assertFalse(validFile.exists())
        assertEquals(ModelState.NotDownloaded, manager.modelStates.value[testModel.id])
    }

    @Test
    fun `loadModel short-circuits when model and config match`() = runBlocking {
        writeValidGgufFile(testModel, sizeBytes = 128)
        manager.checkDownloadedModels()

        manager.loadModel(testModel, nThreads = 4, nCtx = 2048)
        assertEquals(1, engine.loadCallCount.get())

        manager.loadModel(testModel, nThreads = 4, nCtx = 2048)
        assertEquals("Should skip redundant reload for identical config", 1, engine.loadCallCount.get())

        manager.loadModel(testModel, nThreads = 2, nCtx = 4096)
        assertEquals("Should reload when config changes", 2, engine.loadCallCount.get())
        assertEquals(4096 to 2, manager.loadedConfig.value)
    }

    @Test
    fun `downloadModel transitions to Error when external storage throws`() = runBlocking {
        gateway.enqueueException = IllegalStateException("External storage unavailable")

        manager.downloadModel(testModel)

        var state: ModelState? = null
        repeat(40) {
            state = manager.modelStates.value[testModel.id]
            if (state is ModelState.Error) return@repeat
            delay(25)
        }
        assertTrue("Expected Error state, got $state", state is ModelState.Error)
    }

    @Test
    fun `broadcast query ignores non-terminal statuses without deleting or erroring`() = runBlocking {
        val partialFile = writeValidGgufFile(testModel, sizeBytes = 64)
        manager.downloadModel(testModel)

        var downloadId = -1L
        repeat(40) {
            val s = manager.modelStates.value[testModel.id]
            if (s is ModelState.Downloading) {
                downloadId = s.downloadId
                return@repeat
            }
            delay(25)
        }
        assertTrue(downloadId > 0L)

        gateway.rows[downloadId] = gateway.rows[downloadId]!!.copy(
            status = DownloadManager.STATUS_PAUSED,
            reason = DownloadManager.PAUSED_WAITING_FOR_NETWORK
        )
        // Re-create partial file as DownloadManager writes to destination
        partialFile.writeBytes(ByteArray(64))

        manager.onDownloadBroadcastForTest(downloadId)

        assertTrue("Partial file must not be deleted on PAUSED", partialFile.exists())
        assertEquals(1, manager.trackedDownloadCountForTest())
        assertTrue(manager.modelStates.value[testModel.id] is ModelState.Downloading)

        manager.cancelDownload(testModel.id)
        repeat(40) {
            if (manager.modelStates.value[testModel.id] is ModelState.NotDownloaded) return@repeat
            delay(25)
        }
        assertEquals(ModelState.NotDownloaded, manager.modelStates.value[testModel.id])
    }

    @Test
    fun `cancelDownload does not race with slow polling query`() = runBlocking {
        gateway.queryDelayMs = 150L
        manager.downloadModel(testModel)

        repeat(40) {
            if (manager.modelStates.value[testModel.id] is ModelState.Downloading) return@repeat
            delay(20)
        }
        assertTrue(manager.modelStates.value[testModel.id] is ModelState.Downloading)

        // Cancel while polling queryById is sleeping inside Thread.sleep(150)
        delay(30)
        manager.cancelDownload(testModel.id)

        // Wait longer than queryDelayMs + poll interval
        delay(350)
        assertEquals(
            "Polling must not resurrect Downloading state after cancel",
            ModelState.NotDownloaded,
            manager.modelStates.value[testModel.id]
        )
        assertEquals(0, manager.trackedDownloadCountForTest())
    }

    @Test
    fun `server telemetry is hosted in service and cleared only explicitly`() {
        repeat(55) { idx ->
            NeuralliteServerService.recordServerEvent(
                ServerEvent.RequestCompleted(
                    method = "POST",
                    path = "/v1/chat/completions",
                    statusCode = 200,
                    durationMs = 500L,
                    tokensGenerated = 10,
                    timestampMs = 1000L + idx
                )
            )
        }

        assertEquals(55, NeuralliteServerService.totalRequests.value)
        assertEquals(50, NeuralliteServerService.serverEvents.value.size)
        assertEquals(20.0f, NeuralliteServerService.avgTokPerSec.value, 0.01f)

        NeuralliteServerService.clearServerEvents()
        assertEquals(0, NeuralliteServerService.totalRequests.value)
        assertTrue(NeuralliteServerService.serverEvents.value.isEmpty())
        assertEquals(0f, NeuralliteServerService.avgTokPerSec.value, 0.001f)
    }

    @Test
    fun `updateLiveServerConfig synchronizes lastConfig when present`() {
        NeuralliteServerService.updateLiveServerConfig(
            corsEnabled = false,
            defaultTemperature = 0.3f,
            defaultMaxTokens = 256
        )
        assertNull(NeuralliteServerService.getLastConfigForTest())

        NeuralliteServerService.setLastConfigForTest(
            NeuralliteServerService.ServerRuntimeConfig(
                port = 8080,
                corsEnabled = true,
                mdnsEnabled = true,
                wakelockEnabled = true,
                defaultTemperature = 0.7f,
                defaultMaxTokens = 512
            )
        )
        NeuralliteServerService.updateLiveServerConfig(
            corsEnabled = false,
            defaultTemperature = 0.3f,
            defaultMaxTokens = 256
        )
        val updated = NeuralliteServerService.getLastConfigForTest()
        assertEquals(8080, updated?.port)
        assertEquals(false, updated?.corsEnabled)
        assertEquals(0.3f, updated?.defaultTemperature ?: -1f, 0.001f)
        assertEquals(256, updated?.defaultMaxTokens)
    }

    @Test
    fun `deleteModel cleans completed DownloadManager rows so next scan stays NotDownloaded`() = runBlocking {
        val validFile = writeValidGgufFile(testModel, sizeBytes = 128)
        gateway.rows[77L] = ModelManager.DownloadRow(
            id = 77L,
            uri = testModel.huggingFaceDirectUrl,
            status = DownloadManager.STATUS_SUCCESSFUL,
            bytesDownloaded = validFile.length(),
            totalBytes = validFile.length(),
            reason = 0,
            localUri = null,
            lastModifiedMs = 3000L
        )
        manager.checkDownloadedModels()
        assertEquals(ModelState.Downloaded(validFile.absolutePath), manager.modelStates.value[testModel.id])

        manager.deleteModel(testModel.id)
        assertFalse(validFile.exists())
        assertTrue(77L in gateway.removedIds)

        manager.checkDownloadedModels()
        assertEquals(ModelState.NotDownloaded, manager.modelStates.value[testModel.id])
    }

    @Test
    fun `checkDownloadedModels treats externally deleted STATUS_SUCCESSFUL file as NotDownloaded`() = runBlocking {
        gateway.rows[88L] = ModelManager.DownloadRow(
            id = 88L,
            uri = testModel.huggingFaceDirectUrl,
            status = DownloadManager.STATUS_SUCCESSFUL,
            bytesDownloaded = 128L,
            totalBytes = 128L,
            reason = 0,
            localUri = null,
            lastModifiedMs = 3000L
        )

        manager.checkDownloadedModels()

        assertTrue(88L in gateway.removedIds)
        assertEquals(ModelState.NotDownloaded, manager.modelStates.value[testModel.id])
    }

    @Test
    fun `loadModel refuses to load while model is downloading`() = runBlocking {
        val partialFile = writeValidGgufFile(testModel, sizeBytes = 64)
        manager.downloadModel(testModel)
        repeat(40) {
            if (manager.modelStates.value[testModel.id] is ModelState.Downloading) return@repeat
            delay(20)
        }
        partialFile.writeBytes(ByteArray(64))

        manager.loadModel(testModel, nThreads = 4, nCtx = 2048)

        assertEquals(0, engine.loadCallCount.get())
        assertTrue(manager.modelStates.value[testModel.id] is ModelState.Downloading)
        assertTrue(manager.modelError.value?.contains("still downloading") == true)

        manager.cancelDownload(testModel.id)
        repeat(40) {
            if (manager.modelStates.value[testModel.id] is ModelState.NotDownloaded) return@repeat
            delay(20)
        }
    }
}
