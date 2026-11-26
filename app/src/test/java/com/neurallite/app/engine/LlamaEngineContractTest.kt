package com.neurallite.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LlamaEngineContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeNativeBridge : NativeLlamaBridge {
        private val nextHandle = AtomicLong(100L)
        val stopFlags = ConcurrentHashMap<Long, AtomicBoolean>()
        val unloadedHandles = mutableListOf<Long>()

        var failLoadWithOom = false
        var infoJsonToReturn = """{"name":"tiny","nParams":1000,"nCtx":2048,"nLayers":22}"""
        var onGetModelInfo: (() -> Unit)? = null
        var onRunInference: ((Long, AtomicBoolean, (ByteArray) -> Unit, IntArray) -> ByteArray)? = null

        val loadCount = AtomicInteger(0)
        val runCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)

        override fun loadModel(modelPathBytes: ByteArray, nThreads: Int, nCtx: Int): Long {
            loadCount.incrementAndGet()
            if (failLoadWithOom) return -1L
            val h = nextHandle.getAndIncrement()
            stopFlags[h] = AtomicBoolean(false)
            return h
        }

        override fun runInference(
            handle: Long,
            promptBytes: ByteArray,
            maxTokens: Int,
            temperature: Float,
            parseSpecial: Boolean,
            onTokenBytes: (ByteArray) -> Unit,
            outStats: IntArray
        ): ByteArray {
            runCount.incrementAndGet()
            val flag = stopFlags[handle] ?: AtomicBoolean(false)
            val custom = onRunInference
            if (custom != null) {
                return custom(handle, flag, onTokenBytes, outStats)
            }
            if (flag.get()) {
                outStats[0] = 0
                outStats[1] = 0
                outStats[2] = 2
                return ByteArray(0)
            }
            val bytes = "ok".toByteArray(Charsets.UTF_8)
            onTokenBytes(bytes)
            outStats[0] = 4
            outStats[1] = 1
            outStats[2] = 0
            return bytes
        }

        override fun resetStopFlag(handle: Long) {
            stopFlags[handle]?.set(false)
        }

        override fun stopInference(handle: Long) {
            stopCount.incrementAndGet()
            stopFlags[handle]?.set(true)
        }

        override fun unloadModel(handle: Long) {
            synchronized(unloadedHandles) {
                unloadedHandles.add(handle)
            }
            stopFlags.remove(handle)
        }

        override fun getModelInfo(handle: Long): String {
            onGetModelInfo?.invoke()
            return infoJsonToReturn
        }
    }

    private class TrackingIoDispatcher : CoroutineDispatcher() {
        private val afterNextDispatch = AtomicReference<CountDownLatch?>(null)

        fun notifyAfterNextDispatch(latch: CountDownLatch) {
            afterNextDispatch.set(latch)
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val latch = afterNextDispatch.getAndSet(null)
            Dispatchers.IO.dispatch(context, Runnable {
                try {
                    block.run()
                } finally {
                    latch?.countDown()
                }
            })
        }
    }

    private fun createDummyModelFile(name: String = "model.gguf"): File {
        val file = tempFolder.newFile(name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        return file
    }

    @Test
    fun `loadModelSafe publishes metadata and loadedConfig before isLoaded becomes true`() = runBlocking {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val modelFile = createDummyModelFile()

        var wasLoadedDuringGetModelInfo = true
        bridge.onGetModelInfo = {
            wasLoadedDuringGetModelInfo = coordinator.isLoaded
        }

        val ok = coordinator.loadModelSafe(
            modelPath = modelFile.absolutePath,
            nThreads = 4,
            nCtx = 2048,
            modelId = "tinyllama_1b_q8"
        )

        assertTrue(ok)
        assertFalse("activeHandle must not be published before getModelInfo finishes", wasLoadedDuringGetModelInfo)
        assertTrue(coordinator.isLoaded)
        assertEquals("tinyllama_1b_q8", coordinator.loadedModelId)
        assertEquals(2048 to 4, coordinator.loadedConfig)
        assertEquals(bridge.infoJsonToReturn, coordinator.modelInfo.value)
    }

    @Test
    fun `loadModelSafe failure and unloadCurrentModel clear loadedConfig and metadata`() = runBlocking {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val modelFile = createDummyModelFile()

        assertTrue(coordinator.loadModelSafe(modelFile.absolutePath, nThreads = 6, nCtx = 4096, modelId = "m1"))
        assertEquals(4096 to 6, coordinator.loadedConfig)

        coordinator.unloadCurrentModel()
        assertFalse(coordinator.isLoaded)
        assertNull(coordinator.loadedModelId)
        assertNull(coordinator.loadedConfig)
        assertNull(coordinator.modelInfo.value)

        // Missing file -> LoadFailed, loadedConfig stays null
        assertFalse(coordinator.loadModelSafe(File(tempFolder.root, "missing.gguf").absolutePath, 2, 1024, "m2"))
        assertFalse(coordinator.isLoaded)
        assertNull(coordinator.loadedConfig)
        assertTrue(coordinator.error.value is LlamaEngine.LlamaError.LoadFailed)

        // Native OOM -> OutOfMemory, loadedConfig stays null
        bridge.failLoadWithOom = true
        assertFalse(coordinator.loadModelSafe(modelFile.absolutePath, 2, 1024, "m3"))
        assertFalse(coordinator.isLoaded)
        assertNull(coordinator.loadedConfig)
        assertEquals(LlamaEngine.LlamaError.OutOfMemory, coordinator.error.value)
    }

    @Test
    fun `stopCurrentInference after isInferring armed is not wiped out before native loop`() = runBlocking(Dispatchers.Default) {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val modelFile = createDummyModelFile()
        assertTrue(coordinator.loadModelSafe(modelFile.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m1"))

        val enteredRunInference = CountDownLatch(1)
        val stopDispatched = CountDownLatch(1)

        bridge.onRunInference = { _, stopFlag, _, outStats ->
            enteredRunInference.countDown()
            assertTrue("Expected stop signal in test", stopDispatched.await(3, TimeUnit.SECONDS))
            if (stopFlag.get()) {
                outStats[0] = 0
                outStats[1] = 0
                outStats[2] = 2
                ByteArray(0)
            } else {
                outStats[0] = 3
                outStats[1] = 1
                outStats[2] = 0
                "should_not_run".toByteArray(Charsets.UTF_8)
            }
        }

        val inferJob = async {
            coordinator.runInferenceSafe(
                prompt = "hello",
                maxTokens = 32,
                temperature = 0.7f,
                parseSpecial = false,
                cancelToken = null,
                onToken = {}
            )
        }

        assertTrue(enteredRunInference.await(3, TimeUnit.SECONDS))
        assertTrue(coordinator.isInferring.value)
        coordinator.stopCurrentInference()
        stopDispatched.countDown()

        val result = withTimeout(3000) { inferJob.await() }
        assertEquals("cancelled", result.finishReason)
        assertFalse(coordinator.isInferring.value)
    }

    @Test
    fun `unloadCurrentModel barrier cancels queued runInferenceSafe across mutex handoff`() = runBlocking(Dispatchers.Default) {
        val bridge = FakeNativeBridge()
        val ioDispatcher = TrackingIoDispatcher()
        val coordinator = EngineCoordinator(bridge, ioDispatcher)
        val modelFile = createDummyModelFile()
        assertTrue(coordinator.loadModelSafe(modelFile.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m1"))

        val r1Started = CountDownLatch(1)
        val r1Release = CountDownLatch(1)
        bridge.onRunInference = { _, _, _, outStats ->
            r1Started.countDown()
            r1Release.await(3, TimeUnit.SECONDS)
            outStats[0] = 5
            outStats[1] = 1
            outStats[2] = 2
            ByteArray(0)
        }

        val r1 = async {
            coordinator.runInferenceSafe("p1", 16, 0.7f, false, AtomicBoolean(false)) {}
        }
        assertTrue(r1Started.await(3, TimeUnit.SECONDS))

        // Queue R2 while R1 holds inferenceMutex and wait deterministically until R2 suspends on it
        val r2QueuedOnMutex = CountDownLatch(1)
        ioDispatcher.notifyAfterNextDispatch(r2QueuedOnMutex)
        val r2 = async {
            coordinator.runInferenceSafe("p2", 16, 0.7f, false, AtomicBoolean(false)) {}
        }
        assertTrue(r2QueuedOnMutex.await(3, TimeUnit.SECONDS))

        val unloadJob = launch {
            coordinator.unloadCurrentModel()
        }
        // Wait until stopInference was triggered by unloadCurrentModel
        val deadline = System.currentTimeMillis() + 3000
        while (bridge.stopCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(bridge.stopCount.get() >= 1)

        r1Release.countDown()

        withTimeout(3000) {
            val res1 = r1.await()
            val res2 = r2.await()
            unloadJob.join()
            assertEquals("cancelled", res1.finishReason)
            assertEquals("cancelled", res2.finishReason)
        }
        assertEquals("Queued R2 must never enter native runInference", 1, bridge.runCount.get())
        assertFalse(coordinator.isLoaded)
        assertNull(coordinator.loadedConfig)
    }

    @Test
    fun `loadModelSafe stops active inference before waiting and cancels queued requests`() = runBlocking(Dispatchers.Default) {
        val bridge = FakeNativeBridge()
        val ioDispatcher = TrackingIoDispatcher()
        val coordinator = EngineCoordinator(bridge, ioDispatcher)
        val model1 = createDummyModelFile("m1.gguf")
        val model2 = createDummyModelFile("m2.gguf")
        assertTrue(coordinator.loadModelSafe(model1.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m1"))

        val r1Started = CountDownLatch(1)
        bridge.onRunInference = { _, stopFlag, _, outStats ->
            r1Started.countDown()
            val deadline = System.currentTimeMillis() + 3000
            while (!stopFlag.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            outStats[0] = 4
            outStats[1] = 0
            outStats[2] = if (stopFlag.get()) 2 else 0
            ByteArray(0)
        }

        val r1 = async {
            coordinator.runInferenceSafe("p1", 16, 0.7f, false, AtomicBoolean(false)) {}
        }
        assertTrue(r1Started.await(3, TimeUnit.SECONDS))

        val r2QueuedOnMutex = CountDownLatch(1)
        ioDispatcher.notifyAfterNextDispatch(r2QueuedOnMutex)
        val r2 = async {
            coordinator.runInferenceSafe("p2", 16, 0.7f, false, AtomicBoolean(false)) {}
        }
        assertTrue(r2QueuedOnMutex.await(3, TimeUnit.SECONDS))

        val loaded = withTimeout(3000) {
            coordinator.loadModelSafe(model2.absolutePath, nThreads = 4, nCtx = 2048, modelId = "m2")
        }
        assertTrue(loaded)
        assertEquals("cancelled", withTimeout(3000) { r1.await().finishReason })
        assertEquals("cancelled", withTimeout(3000) { r2.await().finishReason })
        assertEquals("m2", coordinator.loadedModelId)
        assertEquals(2048 to 4, coordinator.loadedConfig)
        assertEquals(1, bridge.runCount.get())
    }

    @Test
    fun `stopInferenceIfMatching only stops matching active token`() = runBlocking(Dispatchers.Default) {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val modelFile = createDummyModelFile()
        assertTrue(coordinator.loadModelSafe(modelFile.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m1"))

        val r1Started = CountDownLatch(1)
        val r1Continue = CountDownLatch(1)
        bridge.onRunInference = { _, stopFlag, _, outStats ->
            r1Started.countDown()
            r1Continue.await(3, TimeUnit.SECONDS)
            outStats[0] = 3
            outStats[1] = 1
            outStats[2] = if (stopFlag.get()) 2 else 0
            if (stopFlag.get()) ByteArray(0) else "done".toByteArray(Charsets.UTF_8)
        }

        val token1 = AtomicBoolean(false)
        val token2 = AtomicBoolean(false)

        val r1 = async {
            coordinator.runInferenceSafe("p1", 16, 0.7f, false, token1) {}
        }
        assertTrue(r1Started.await(3, TimeUnit.SECONDS))

        // Non-matching token must NOT stop R1
        coordinator.stopInferenceIfMatching(token2)
        assertTrue(token2.get())
        assertEquals(0, bridge.stopCount.get())

        r1Continue.countDown()
        val res1 = withTimeout(3000) { r1.await() }
        assertEquals("stop", res1.finishReason)
        assertEquals("done", res1.text)

        // Matching token stops active inference
        val token3 = AtomicBoolean(false)
        val r3Started = CountDownLatch(1)
        bridge.onRunInference = { _, stopFlag, _, outStats ->
            r3Started.countDown()
            val deadline = System.currentTimeMillis() + 3000
            while (!stopFlag.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            outStats[0] = 3
            outStats[1] = 0
            outStats[2] = if (stopFlag.get()) 2 else 0
            ByteArray(0)
        }
        val r3 = async {
            coordinator.runInferenceSafe("p3", 16, 0.7f, false, token3) {}
        }
        assertTrue(r3Started.await(3, TimeUnit.SECONDS))
        coordinator.stopInferenceIfMatching(token3)
        assertEquals(1, bridge.stopCount.get())
        assertEquals("cancelled", withTimeout(3000) { r3.await().finishReason })
    }

    @Test
    fun `runInferenceSafe started during pending loadModelSafe is cancelled rather than running on new model`() = runBlocking(Dispatchers.Default) {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val model1 = createDummyModelFile("m1_pending.gguf")
        val model2 = createDummyModelFile("m2_pending.gguf")
        assertTrue(coordinator.loadModelSafe(model1.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m1"))

        val r1Started = CountDownLatch(1)
        val r1Release = CountDownLatch(1)
        bridge.onRunInference = { _, _, _, outStats ->
            r1Started.countDown()
            r1Release.await(3, TimeUnit.SECONDS)
            outStats[0] = 2
            outStats[1] = 0
            outStats[2] = 2
            ByteArray(0)
        }

        val r1 = async {
            coordinator.runInferenceSafe("p1", 16, 0.7f, false, AtomicBoolean(false)) {}
        }
        assertTrue(r1Started.await(3, TimeUnit.SECONDS))

        // Start loadModelSafe while R1 holds inferenceMutex
        val loadJob = async {
            coordinator.loadModelSafe(model2.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m2")
        }
        val deadline = System.currentTimeMillis() + 3000
        while (bridge.stopCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(bridge.stopCount.get() >= 1)

        // R3 arrives while loadModelSafe is pending (m1 is still active, m2 not loaded yet)
        val r3 = coordinator.runInferenceSafe("p3_for_m1", 16, 0.7f, false, AtomicBoolean(false)) {}
        assertEquals("cancelled", r3.finishReason)

        r1Release.countDown()
        assertEquals("cancelled", withTimeout(3000) { r1.await().finishReason })
        assertTrue(withTimeout(3000) { loadJob.await() })
        assertEquals(1, bridge.runCount.get())
    }

    @Test
    fun `runInferenceSafe succeeds immediately after loadModelSafe releases mutex even before caller dispatcher resumes`() = runBlocking(Dispatchers.Default) {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val modelFile = createDummyModelFile("m_resume.gguf")

        val resumeDispatched = CountDownLatch(1)
        val releaseCallerResume = CountDownLatch(1)
        val pausedCallerDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context, Runnable {
                    if (coordinator.isLoaded) {
                        // Delay post-withContext resumption back onto caller dispatcher
                        resumeDispatched.countDown()
                        releaseCallerResume.await(3, TimeUnit.SECONDS)
                    }
                    block.run()
                })
            }
        }

        val loadJob = async {
            withContext(pausedCallerDispatcher) {
                coordinator.loadModelSafe(modelFile.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m_resume")
            }
        }

        assertTrue(resumeDispatched.await(3, TimeUnit.SECONDS))
        // loadModelSafe has finished loading on ioDispatcher and released inferenceMutex,
        // even though its caller dispatcher has not resumed yet.
        val res = withTimeout(3000) {
            coordinator.runInferenceSafe("hello", 16, 0.7f, false, AtomicBoolean(false)) {}
        }
        assertEquals("stop", res.finishReason)
        assertEquals("ok", res.text)

        releaseCallerResume.countDown()
        assertTrue(withTimeout(3000) { loadJob.await() })
    }

    @Test
    fun `runInferenceSafe without loaded model returns error and sets InferenceFailed`() = runBlocking {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)

        val result = coordinator.runInferenceSafe("hello", 16, 0.7f, false, AtomicBoolean(false)) {}
        assertEquals("error", result.finishReason)
        assertEquals("", result.text)
        assertTrue(coordinator.error.value is LlamaEngine.LlamaError.InferenceFailed)
        assertEquals(0, bridge.runCount.get())
    }

    @Test
    fun `runInferenceSafe maps native finish codes and propagates CancellationException`() = runBlocking {
        val bridge = FakeNativeBridge()
        val coordinator = EngineCoordinator(bridge, Dispatchers.IO)
        val modelFile = createDummyModelFile("m_codes.gguf")
        assertTrue(coordinator.loadModelSafe(modelFile.absolutePath, nThreads = 2, nCtx = 1024, modelId = "m_codes"))

        bridge.onRunInference = { _, _, _, outStats ->
            outStats[0] = 12
            outStats[1] = 16
            outStats[2] = 1
            "maxed".toByteArray(Charsets.UTF_8)
        }
        val lengthRes = coordinator.runInferenceSafe("p", 16, 0.7f, false, null) {}
        assertEquals("length", lengthRes.finishReason)
        assertEquals("maxed", lengthRes.text)
        assertEquals(12, lengthRes.promptTokens)
        assertEquals(16, lengthRes.completionTokens)

        bridge.onRunInference = { _, _, _, outStats ->
            outStats[0] = 2048
            outStats[1] = 0
            outStats[2] = 4
            ByteArray(0)
        }
        val overflowRes = coordinator.runInferenceSafe("p", 16, 0.7f, false, null) {}
        assertEquals("context_overflow", overflowRes.finishReason)
        assertTrue(coordinator.error.value is LlamaEngine.LlamaError.InferenceFailed)

        bridge.onRunInference = { _, _, onTokenBytes, _ ->
            onTokenBytes("partial".toByteArray(Charsets.UTF_8))
            ByteArray(0)
        }
        var caughtCancel = false
        try {
            coordinator.runInferenceSafe("p", 16, 0.7f, false, null) {
                throw CancellationException("caller cancelled")
            }
        } catch (_: CancellationException) {
            caughtCancel = true
        }
        assertTrue("CancellationException must not be swallowed", caughtCancel)
        assertFalse(coordinator.isInferring.value)
    }
}
