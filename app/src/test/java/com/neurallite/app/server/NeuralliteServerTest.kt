package com.neurallite.app.server

import com.neurallite.app.engine.InferenceResult
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.models.ModelInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NeuralliteServerTest {

    private class FakeBackend : InferenceBackend {
        override var isLoaded: Boolean = false
        override var modelInfoJson: String? = null
        override var activeModel: ModelInfo? = null

        var lastPrompt: String = ""
        var lastMaxTokens: Int = -1
        var lastTemperature: Float = -1f
        var lastParseSpecial: Boolean = false
        var tokensToEmit: List<String> = listOf("Hello", " ", "world", "🚀")
        var promptTokenCount: Int = 7
        var delayPerTokenMs: Long = 0L
        var stopCalledLatch: CountDownLatch? = null
        var overflowPrompt: Boolean = false

        override suspend fun runInference(
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            parseSpecial: Boolean,
            cancelToken: AtomicBoolean,
            onToken: (String) -> Unit
        ): InferenceResult {
            lastPrompt = prompt
            lastMaxTokens = maxTokens
            lastTemperature = temperature
            lastParseSpecial = parseSpecial

            if (overflowPrompt) {
                return InferenceResult("", 4096, 0, "context_overflow")
            }

            val sb = StringBuilder()
            var count = 0
            for (tok in tokensToEmit) {
                if (cancelToken.get()) {
                    stopCalledLatch?.countDown()
                    return InferenceResult(sb.toString(), promptTokenCount, count, "cancelled")
                }
                if (delayPerTokenMs > 0) {
                    Thread.sleep(delayPerTokenMs)
                }
                if (cancelToken.get()) {
                    stopCalledLatch?.countDown()
                    return InferenceResult(sb.toString(), promptTokenCount, count, "cancelled")
                }
                sb.append(tok)
                count++
                onToken(tok)
                if (count >= maxTokens) {
                    return InferenceResult(sb.toString(), promptTokenCount, count, "length")
                }
            }
            return InferenceResult(sb.toString(), promptTokenCount, count, "stop")
        }

        override fun stopCurrentInference() {
            stopCalledLatch?.countDown()
        }

        override fun stopInferenceIfMatching(cancelToken: AtomicBoolean) {
            cancelToken.set(true)
            stopCalledLatch?.countDown()
        }
    }

    private lateinit var backend: FakeBackend
    private lateinit var server: NeuralliteServer
    private val events = CopyOnWriteArrayList<ServerEvent>()
    private val token = "test-secret-token-1234"
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        events.clear()
        backend = FakeBackend()
        server = NeuralliteServer(
            port = 0,
            apiToken = token,
            corsEnabled = true,
            defaultTemperature = 0.7f,
            defaultMaxTokens = 512,
            backend = backend,
            onEvent = { events.add(it) }
        )
        server.start()
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private data class HttpResult(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: String
    )

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        val hdrs = conn.headerFields
        conn.disconnect()
        return HttpResult(code, hdrs, text)
    }

    @Test
    fun `health returns 200 and reports model state`() {
        val unloaded = request("GET", "/health")
        assertEquals(200, unloaded.code)
        val unloadedJson = JSONObject(unloaded.body)
        assertEquals("ok", unloadedJson.getString("status"))
        assertTrue(unloadedJson.isNull("model"))

        backend.isLoaded = true
        backend.modelInfoJson = "{\"name\":\"tinyllama\"}"
        val loaded = request("GET", "/health")
        assertEquals(200, loaded.code)
        assertEquals("{\"name\":\"tinyllama\"}", JSONObject(loaded.body).getString("model"))
    }

    @Test
    fun `v1 endpoints enforce bearer authentication and support token rotation`() {
        assertEquals(401, request("GET", "/v1/models").code)
        assertEquals(401, request("GET", "/v1/models", headers = mapOf("Authorization" to token)).code)
        assertEquals(401, request("GET", "/v1/models", headers = mapOf("Authorization" to "Bearer wrong")).code)

        val ok = request("GET", "/v1/models", headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals(200, ok.code)
        assertEquals("neurallite-local", JSONObject(ok.body).getJSONArray("data").getJSONObject(0).getString("id"))

        val lowerBearer = request("GET", "/v1/models", headers = mapOf("Authorization" to "bearer $token"))
        assertEquals(200, lowerBearer.code)

        // Live token rotation
        server.apiToken = "rotated-token-5678"
        assertEquals(401, request("GET", "/v1/models", headers = mapOf("Authorization" to "Bearer $token")).code)
        assertEquals(200, request("GET", "/v1/models", headers = mapOf("Authorization" to "Bearer rotated-token-5678")).code)
    }

    @Test
    fun `chat completions returns 503 when unloaded and 400 on invalid payloads`() {
        val unloaded = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}]}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(503, unloaded.code)

        backend.isLoaded = true
        val malformed = request(
            "POST",
            "/v1/chat/completions",
            body = "{not valid json",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(400, malformed.code)

        val emptyMessages = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[]}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(400, emptyMessages.code)
    }

    @Test
    fun `non-streaming chat decodes UTF-8 without US-ASCII corruption and populates usage`() {
        backend.isLoaded = true
        backend.activeModel = ModelCatalog.getModelById("tinyllama_1b_q8")
        val promptText = "Café 🚀 日本語 привет"

        val payload = JSONObject().apply {
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", promptText)
            }))
            put("stream", false)
        }.toString()

        // Intentionally omit "; charset=utf-8" in Content-Type to verify NanoHTTPD US-ASCII bug fix
        val res = request(
            "POST",
            "/v1/chat/completions",
            body = payload,
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )

        assertEquals(200, res.code)
        assertTrue("Prompt must preserve multi-byte UTF-8", backend.lastPrompt.contains(promptText))
        assertTrue("TinyLlama Zephyr template expected", backend.lastPrompt.contains("<|user|>"))
        assertTrue(backend.lastParseSpecial)

        val json = JSONObject(res.body)
        val choice = json.getJSONArray("choices").getJSONObject(0)
        assertEquals("Hello world🚀", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))

        val usage = json.getJSONObject("usage")
        assertEquals(7, usage.getInt("prompt_tokens"))
        assertEquals(4, usage.getInt("completion_tokens"))
        assertEquals(11, usage.getInt("total_tokens"))

        val completed = events.filterIsInstance<ServerEvent.RequestCompleted>().last()
        assertEquals("POST", completed.method)
        assertEquals("/v1/chat/completions", completed.path)
        assertEquals(200, completed.statusCode)
        assertEquals(4, completed.tokensGenerated)
    }

    @Test
    fun `streaming SSE bypasses gzip compression and reports final token count`() {
        backend.isLoaded = true
        backend.activeModel = ModelCatalog.getModelById("llama3_8b_q4km")

        val payload = """{"messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        val conn = (URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            doOutput = true
            outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }

        assertEquals(200, conn.responseCode)
        assertNull("SSE must never be gzipped", conn.contentEncoding)
        assertTrue(conn.contentType.startsWith("text/event-stream"))

        val lines = conn.inputStream.bufferedReader(Charsets.UTF_8).readLines()
        conn.disconnect()

        val dataLines = lines.filter { it.startsWith("data: ") }
        assertTrue(dataLines.size >= 3)
        assertEquals("data: [DONE]", dataLines.last())

        val finalChunkJson = JSONObject(dataLines[dataLines.size - 2].removePrefix("data: "))
        assertEquals("stop", finalChunkJson.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))

        // Wait briefly for finally block to emit RequestCompleted
        val deadline = System.currentTimeMillis() + 2000
        while (events.filterIsInstance<ServerEvent.RequestCompleted>().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val completed = events.filterIsInstance<ServerEvent.RequestCompleted>().last()
        assertEquals("POST", completed.method)
        assertEquals(4, completed.tokensGenerated)
    }

    @Test
    fun `streaming completions endpoint works with stop sequences`() {
        backend.isLoaded = true
        backend.tokensToEmit = listOf("Once ", "upon", " STOP", " ignored")

        val payload = """{"prompt":"Tell a story","stop":" STOP","stream":true}"""
        val res = request(
            "POST",
            "/v1/completions",
            body = payload,
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, res.code)
        val combinedText = res.body.lines()
            .filter { it.startsWith("data: ") && it != "data: [DONE]" }
            .joinToString("") { line ->
                JSONObject(line.removePrefix("data: "))
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .optString("text", "")
            }
        assertEquals("Once upon", combinedText)
        assertFalse(res.body.contains("ignored"))
        assertTrue(res.body.contains("data: [DONE]"))
    }

    @Test
    fun `sse client disconnect cancels active inference`() {
        backend.isLoaded = true
        backend.tokensToEmit = (1..20).map { "tok$it " }
        backend.delayPerTokenMs = 60L
        val latch = CountDownLatch(1)
        backend.stopCalledLatch = latch

        val body = """{"messages":[{"role":"user","content":"long"}],"stream":true}"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        Socket("127.0.0.1", server.listeningPort).use { sock ->
            val out = sock.getOutputStream()
            val req = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            // Read headers + first SSE chunk then drop socket
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("data: ")) break
            }
        }

        assertTrue("Expected backend cancellation after client disconnect",
            latch.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `cors toggle controls headers and unknown paths return 404`() {
        val preflightOn = request("OPTIONS", "/v1/chat/completions")
        assertEquals(200, preflightOn.code)
        assertEquals("*", preflightOn.headers["Access-Control-Allow-Origin"]?.firstOrNull())

        server.corsEnabled = false
        val preflightOff = request("OPTIONS", "/v1/chat/completions")
        assertEquals(200, preflightOff.code)
        assertNull(preflightOff.headers["Access-Control-Allow-Origin"])

        val notFound = request("GET", "/unknown/route")
        assertEquals(404, notFound.code)
    }

    @Test
    fun `stop sequence filter handles partial multi-token stop sequences without delaying normal tokens`() {
        val filter = StopSequenceFilter(listOf("</s>", "<|user|>"))

        val r1 = filter.push("Hello")
        assertEquals("Hello", r1.textToEmit)
        assertFalse(r1.matchedStop)

        // "<" is a prefix of both stop strings -> held back briefly
        val r2 = filter.push("<")
        assertEquals("", r2.textToEmit)
        assertFalse(r2.matchedStop)

        // "< 5" breaks the stop prefix -> flushes cleanly
        val r3 = filter.push(" 5")
        assertEquals("< 5", r3.textToEmit)
        assertFalse(r3.matchedStop)

        // Multi-token stop match
        val r4 = filter.push(" done<")
        assertEquals(" done", r4.textToEmit)
        assertFalse(r4.matchedStop)

        val r5 = filter.push("/s>extra")
        assertEquals("", r5.textToEmit)
        assertTrue(r5.matchedStop)
    }

    @Test
    fun `keep-alive connection does not desync after 401 or 503 on POST with body`() {
        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 3000
            val out = sock.getOutputStream()
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

            fun readHttpResponse(): Pair<Int, String> {
                val statusLine = reader.readLine() ?: error("EOF waiting for status line")
                val code = statusLine.split(" ")[1].toInt()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toInt()
                    }
                }
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n == -1) break
                    read += n
                }
                return code to String(buf, 0, read)
            }

            // 1. Unauthorized POST with body over keep-alive
            val body1 = """{"messages":[{"role":"user","content":"hello keep-alive"}]}"""
            val body1Bytes = body1.toByteArray(Charsets.UTF_8)
            out.write((
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body1Bytes.size}\r\n" +
                    "Connection: keep-alive\r\n\r\n"
                ).toByteArray(Charsets.UTF_8))
            out.write(body1Bytes)
            out.flush()

            val (code1, _) = readHttpResponse()
            assertEquals(401, code1)

            // 2. Authorized POST while no model is loaded (503) on the SAME socket
            out.write((
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body1Bytes.size}\r\n" +
                    "Connection: keep-alive\r\n\r\n"
                ).toByteArray(Charsets.UTF_8))
            out.write(body1Bytes)
            out.flush()

            val (code2, _) = readHttpResponse()
            assertEquals(503, code2)

            // 3. Subsequent GET /health on the SAME socket must return 200 (not 400 stream desync)
            out.write((
                "GET /health HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8))
            out.flush()

            val (code3, body3) = readHttpResponse()
            assertEquals(200, code3)
            assertEquals("ok", JSONObject(body3).getString("status"))
        }
    }

    @Test
    fun `chunked transfer encoding POST decodes UTF-8 correctly`() {
        backend.isLoaded = true
        val json = """{"messages":[{"role":"user","content":"Chunked 🚀 Café"}]}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        val split = bytes.size / 2
        val part1 = bytes.copyOfRange(0, split)
        val part2 = bytes.copyOfRange(split, bytes.size)

        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 3000
            val out = sock.getOutputStream()
            out.write((
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8))
            out.write("${part1.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
            out.write(part1)
            out.write("\r\n${part2.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
            out.write(part2)
            out.write("\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()

            val respText = sock.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            assertTrue("Expected HTTP 200 OK, got:\n$respText", respText.startsWith("HTTP/1.1 200"))
            assertTrue(backend.lastPrompt.contains("Chunked 🚀 Café"))
        }
    }

    @Test
    fun `streaming context overflow emits SSE error and logs 400 status`() {
        backend.isLoaded = true
        backend.overflowPrompt = true

        val res = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"too long"}],"stream":true}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, res.code)
        assertTrue(res.body.contains("\"error\""))
        assertTrue(res.body.contains("exceeds context window"))
        assertTrue(res.body.contains("data: [DONE]"))

        val deadline = System.currentTimeMillis() + 2000
        while (events.filterIsInstance<ServerEvent.RequestCompleted>().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val completed = events.filterIsInstance<ServerEvent.RequestCompleted>().last()
        assertEquals(400, completed.statusCode)
    }

    @Test
    fun `openai compatibility handles array content parts, max_completion_tokens, include_usage, and single model route`() {
        backend.isLoaded = true

        val modelRes = request(
            "GET",
            "/v1/models/neurallite-local",
            headers = mapOf("Authorization" to "Bearer $token")
        )
        assertEquals(200, modelRes.code)
        assertEquals("neurallite-local", JSONObject(modelRes.body).getString("id"))

        val payload = """
            {
              "messages": [
                {"role": "developer", "content": "Be brief"},
                {"role": "user", "content": [{"type": "text", "text": "Part A "}, {"type": "text", "text": "Part B"}]}
              ],
              "max_completion_tokens": 3,
              "stream": true,
              "stream_options": {"include_usage": true}
            }
        """.trimIndent()
        val res = request(
            "POST",
            "/v1/chat/completions",
            body = payload,
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, res.code)
        assertEquals(3, backend.lastMaxTokens)
        assertTrue(backend.lastPrompt.contains("Be brief"))
        assertTrue(backend.lastPrompt.contains("Part A Part B"))
        assertTrue(res.body.contains("\"usage\""))
        assertTrue(res.body.contains("\"total_tokens\":10"))
    }

    @Test
    fun `parallel chat requests execute safely when serialized by backend`() {
        val lock = ReentrantLock()
        val serialBackend = object : InferenceBackend {
            override val isLoaded: Boolean = true
            override val modelInfoJson: String = "{\"name\":\"fake\"}"
            override val activeModel: ModelInfo? = ModelCatalog.getModelById("tinyllama_1b_q8")

            override suspend fun runInference(
                prompt: String,
                maxTokens: Int,
                temperature: Float,
                parseSpecial: Boolean,
                cancelToken: AtomicBoolean,
                onToken: (String) -> Unit
            ): InferenceResult = lock.withLock {
                Thread.sleep(25)
                onToken("ok")
                InferenceResult("ok", 5, 1, "stop")
            }

            override fun stopCurrentInference() {}
            override fun stopInferenceIfMatching(cancelToken: AtomicBoolean) {}
        }

        val parallelServer = NeuralliteServer(
            port = 0,
            apiToken = token,
            backend = serialBackend
        ).also { it.start() }

        try {
            val pool = Executors.newFixedThreadPool(6)
            val tasks = (1..6).map { idx ->
                Callable {
                    val conn = (URL("http://127.0.0.1:${parallelServer.listeningPort}/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        outputStream.use {
                            it.write("""{"messages":[{"role":"user","content":"req $idx"}]}""".toByteArray(Charsets.UTF_8))
                        }
                    }
                    val code = conn.responseCode
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    code to body
                }
            }
            val results = pool.invokeAll(tasks).map { it.get(5, TimeUnit.SECONDS) }
            pool.shutdown()
            results.forEach { (code, body) ->
                assertEquals(200, code)
                assertEquals("ok", JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
            }
        } finally {
            parallelServer.stop()
        }
    }

    private data class RawHttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            }
            if (b == '\n'.code) {
                return out.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            }
            out.write(b)
        }
    }

    private fun readRawHttpResponse(input: BufferedInputStream): RawHttpResponse {
        val statusLine = readAsciiLine(input) ?: error("EOF waiting for HTTP status line")
        val code = statusLine.split(" ")[1].toInt()
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull()
        val body = if (contentLength != null) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return RawHttpResponse(code, headers, body)
    }

    private fun createFakeSession(
        targetServer: NeuralliteServer,
        method: NanoHTTPD.Method,
        uri: String,
        headers: Map<String, String>,
        inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
    ): NanoHTTPD.IHTTPSession = object : NanoHTTPD.IHTTPSession {
        private val normalizedHeaders = headers.entries.associateTo(HashMap()) { (k, v) ->
            k.lowercase() to v
        }

        override fun execute() {}
        override fun getCookies(): NanoHTTPD.CookieHandler = targetServer.CookieHandler(emptyMap())
        override fun getHeaders(): MutableMap<String, String> = normalizedHeaders
        override fun getInputStream(): InputStream = inputStream
        override fun getMethod(): NanoHTTPD.Method = method
        override fun getParms(): MutableMap<String, String> = HashMap()
        override fun getParameters(): MutableMap<String, MutableList<String>> = HashMap()
        override fun getQueryParameterString(): String = ""
        override fun getUri(): String = uri
        override fun parseBody(files: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "127.0.0.1"
    }

    @Test
    fun `invalid or negative Content-Length returns 400 with Connection close across routes`() {
        backend.isLoaded = false
        val cases = listOf(
            "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: abc\r\n\r\n",
            "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: -5\r\n\r\n",
            "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: \r\n\r\n",
            "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 99999999999999999999999\r\n\r\n",
            "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: xyz\r\n\r\n",
            "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: -5\r\n\r\n",
            "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\nContent-Length: abc\r\n\r\n",
            "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\nContent-Length: -1\r\n\r\n",
            "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\nTransfer-Encoding: chunked\r\nContent-Length: -5\r\n\r\n",
            "OPTIONS /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: +5\r\n\r\n",
            "GET /does-not-exist HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: -42\r\n\r\n"
        )

        for (rawReq in cases) {
            Socket("127.0.0.1", server.listeningPort).use { sock ->
                sock.soTimeout = 3000
                val out = sock.getOutputStream()
                out.write(rawReq.toByteArray(Charsets.US_ASCII))
                out.flush()

                val input = BufferedInputStream(sock.getInputStream())
                val resp = readRawHttpResponse(input)
                assertEquals("Expected 400 for request:\n$rawReq", 400, resp.code)
                assertEquals("close", resp.headers["connection"]?.lowercase())
                assertTrue(JSONObject(resp.body).has("error"))
            }
        }
    }

    @Test
    fun `pipelined GET and POST without Content-Length preserve keep-alive and do not steal next request`() {
        // 1. Two pipelined GET /health requests in one TCP write
        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 3000
            val out = sock.getOutputStream()
            val pipelinedGets =
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n" +
                    "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
            out.write(pipelinedGets.toByteArray(Charsets.US_ASCII))
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val r1 = readRawHttpResponse(input)
            assertEquals(200, r1.code)
            assertEquals("keep-alive", r1.headers["connection"]?.lowercase())
            assertEquals("ok", JSONObject(r1.body).getString("status"))

            val r2 = readRawHttpResponse(input)
            assertEquals(200, r2.code)
            assertEquals("ok", JSONObject(r2.body).getString("status"))
        }

        // 2. Pipelined POST without Content-Length followed by GET /health
        backend.isLoaded = true
        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 3000
            val out = sock.getOutputStream()
            val pipelinedPostThenGet =
                "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n\r\n" +
                    "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
            out.write(pipelinedPostThenGet.toByteArray(Charsets.US_ASCII))
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val postResp = readRawHttpResponse(input)
            assertEquals(400, postResp.code)
            assertTrue(postResp.body.contains("Empty request body"))

            val getResp = readRawHttpResponse(input)
            assertEquals(200, getResp.code)
            assertEquals("ok", JSONObject(getResp.body).getString("status"))
        }

        // 3. Pipelined GET /health followed by POST /v1/chat/completions with body in one TCP write
        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 3000
            val out = sock.getOutputStream()
            val postBody = """{"messages":[{"role":"user","content":"pipelined"}]}"""
            val postBodyBytes = postBody.toByteArray(Charsets.UTF_8)
            val pipelinedGetThenPost =
                "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n" +
                    "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${postBodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(pipelinedGetThenPost.toByteArray(Charsets.US_ASCII))
            out.write(postBodyBytes)
            out.flush()

            val input = BufferedInputStream(sock.getInputStream())
            val getResp = readRawHttpResponse(input)
            assertEquals(200, getResp.code)
            assertEquals("keep-alive", getResp.headers["connection"]?.lowercase())

            val postResp = readRawHttpResponse(input)
            assertEquals(200, postResp.code)
            assertTrue(backend.lastPrompt.contains("pipelined"))
        }
    }

    @Test
    fun `socket timeout during body read returns 500 with Connection close without second read`() {
        backend.isLoaded = true
        val readCalls = AtomicInteger(0)
        val timeoutStream = object : InputStream() {
            override fun read(): Int {
                readCalls.incrementAndGet()
                throw SocketTimeoutException("Simulated socket timeout")
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readCalls.incrementAndGet()
                throw SocketTimeoutException("Simulated socket timeout")
            }
        }

        val session = createFakeSession(
            targetServer = server,
            method = NanoHTTPD.Method.POST,
            uri = "/v1/chat/completions",
            headers = mapOf(
                "authorization" to "Bearer $token",
                "content-type" to "application/json",
                "content-length" to "64"
            ),
            inputStream = timeoutStream
        )

        val response = server.serve(session)
        assertEquals(500, response.status.requestStatus)
        assertEquals("close", response.getHeader("connection")?.lowercase())
        assertEquals(1, readCalls.get())

        val chunkedReadCalls = AtomicInteger(0)
        val chunkedTimeoutStream = object : InputStream() {
            override fun read(): Int {
                chunkedReadCalls.incrementAndGet()
                throw SocketTimeoutException("Simulated chunked socket timeout")
            }
        }
        val chunkedSession = createFakeSession(
            targetServer = server,
            method = NanoHTTPD.Method.POST,
            uri = "/v1/chat/completions",
            headers = mapOf(
                "authorization" to "Bearer $token",
                "content-type" to "application/json",
                "transfer-encoding" to "chunked"
            ),
            inputStream = chunkedTimeoutStream
        )

        val chunkedResponse = server.serve(chunkedSession)
        assertEquals(500, chunkedResponse.status.requestStatus)
        assertEquals("close", chunkedResponse.getHeader("connection")?.lowercase())
        assertEquals(1, chunkedReadCalls.get())
    }

    @Test
    fun `streaming respects Connection close and handles server stop without deadlock or ErrorOccurred`() {
        backend.isLoaded = true

        // 1a. Respects client's Connection: close header
        Socket("127.0.0.1", server.listeningPort).use { sock ->
            sock.soTimeout = 3000
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            out.write((
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer $token\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: error("Missing status line")
            assertTrue(statusLine.contains("200"))
            val hdrs = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    hdrs[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            assertEquals("close", hdrs["connection"]?.lowercase())
            val bodyText = reader.readText()
            assertTrue(bodyText.contains("data: [DONE]"))
        }

        // 1b. Preserves Connection: keep-alive when client does not ask to close
        val keepAliveSession = createFakeSession(
            targetServer = server,
            method = NanoHTTPD.Method.POST,
            uri = "/v1/chat/completions",
            headers = mapOf(
                "authorization" to "Bearer $token",
                "content-type" to "application/json",
                "content-length" to """{"messages":[{"role":"user","content":"hi"}],"stream":true}""".toByteArray(Charsets.UTF_8).size.toString()
            ),
            inputStream = ByteArrayInputStream("""{"messages":[{"role":"user","content":"hi"}],"stream":true}""".toByteArray(Charsets.UTF_8))
        )
        server.serve(keepAliveSession).use { keepAliveResp ->
            assertEquals(200, keepAliveResp.status.requestStatus)
            assertEquals("keep-alive", keepAliveResp.getHeader("connection")?.lowercase())
            assertTrue(keepAliveResp.data.readBytes().toString(Charsets.UTF_8).contains("data: [DONE]"))
        }

        // 2. Streaming on already-stopped server returns 503 with Connection: close
        val stoppedEvents = CopyOnWriteArrayList<ServerEvent>()
        val cancellingBackend = object : InferenceBackend {
            override val isLoaded: Boolean = true
            override val modelInfoJson: String? = null
            override val activeModel: ModelInfo? = null
            val startedLatch = CountDownLatch(1)

            override suspend fun runInference(
                prompt: String,
                maxTokens: Int,
                temperature: Float,
                parseSpecial: Boolean,
                cancelToken: AtomicBoolean,
                onToken: (String) -> Unit
            ): InferenceResult {
                startedLatch.countDown()
                delay(5000)
                return InferenceResult("", 1, 0, "stop")
            }

            override fun stopCurrentInference() {}
            override fun stopInferenceIfMatching(cancelToken: AtomicBoolean) {}
        }

        val cancelServer = NeuralliteServer(
            port = 0,
            apiToken = token,
            backend = cancellingBackend,
            onEvent = { stoppedEvents.add(it) }
        ).also { it.start() }

        try {
            val streamBody = """{"messages":[{"role":"user","content":"wait"}],"stream":true}"""
                .toByteArray(Charsets.UTF_8)
            val liveSession = createFakeSession(
                targetServer = cancelServer,
                method = NanoHTTPD.Method.POST,
                uri = "/v1/chat/completions",
                headers = mapOf(
                    "authorization" to "Bearer $token",
                    "content-type" to "application/json",
                    "content-length" to streamBody.size.toString()
                ),
                inputStream = ByteArrayInputStream(streamBody)
            )

            val liveResponse = cancelServer.serve(liveSession)
            try {
                assertEquals(200, liveResponse.status.requestStatus)
                assertTrue("Streaming coroutine did not start", cancellingBackend.startedLatch.await(2, TimeUnit.SECONDS))

                val executor = Executors.newSingleThreadExecutor()
                try {
                    val readFuture = executor.submit(Callable {
                        liveResponse.data.readBytes().toString(Charsets.UTF_8)
                    })
                    cancelServer.stop()
                    val streamOutput = readFuture.get(2, TimeUnit.SECONDS)
                    assertFalse("CancellationException must not write SSE error", streamOutput.contains("\"error\""))
                } finally {
                    executor.shutdownNow()
                }
            } finally {
                liveResponse.close()
            }

            assertTrue(
                "CancellationException must not emit ServerEvent.ErrorOccurred",
                stoppedEvents.filterIsInstance<ServerEvent.ErrorOccurred>().isEmpty()
            )

            // Calling streaming after stop() must immediately return 503 with Connection: close
            val postStopSession = createFakeSession(
                targetServer = cancelServer,
                method = NanoHTTPD.Method.POST,
                uri = "/v1/chat/completions",
                headers = mapOf(
                    "authorization" to "Bearer $token",
                    "content-type" to "application/json",
                    "content-length" to streamBody.size.toString()
                ),
                inputStream = ByteArrayInputStream(streamBody)
            )
            val postStopResp = cancelServer.serve(postStopSession)
            assertEquals(503, postStopResp.status.requestStatus)
            assertEquals("close", postStopResp.getHeader("connection")?.lowercase())
        } finally {
            cancelServer.stop()
        }
    }

    @Test
    fun `json null fields in role, text, stop, and prompt do not become literal null`() {
        backend.isLoaded = true

        val nullRole = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":null,"content":"hello"}]}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(400, nullRole.code)

        val nullPrompt = request(
            "POST",
            "/v1/completions",
            body = """{"prompt":null}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(400, nullPrompt.code)

        val nullPromptArray = request(
            "POST",
            "/v1/completions",
            body = """{"prompt":[null]}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(400, nullPromptArray.code)

        backend.tokensToEmit = listOf("alpha ", "null", " beta", " END")
        val nullTextAndStop = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":[{"type":"text","text":null},{"type":"text","text":"real text"}]}],"stop":[" END",null]}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, nullTextAndStop.code)
        assertFalse(backend.lastPrompt.contains("null"))
        assertTrue(backend.lastPrompt.contains("real text"))
        val content = JSONObject(nullTextAndStop.body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        assertEquals("alpha null beta", content)
    }

    @Test
    fun `temperature validation rejects Float overflow and out-of-range values and clamps tiny positives`() {
        backend.isLoaded = true

        val invalidTemps = listOf("1e39", "-0.1", "2.0000001", "2.5", "\"hot\"")
        for (tempExpr in invalidTemps) {
            val res = request(
                "POST",
                "/v1/chat/completions",
                body = """{"messages":[{"role":"user","content":"hi"}],"temperature":$tempExpr}""",
                headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
            )
            assertEquals("Expected 400 for temperature=$tempExpr", 400, res.code)
        }

        val subnormalRes = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}],"temperature":1e-38}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, subnormalRes.code)
        assertEquals(0.0f, backend.lastTemperature, 0.0f)

        val zeroRes = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}],"temperature":0.0}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, zeroRes.code)
        assertEquals(0.0f, backend.lastTemperature, 0.0f)

        val maxRes = request(
            "POST",
            "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}],"temperature":2.0}""",
            headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")
        )
        assertEquals(200, maxRes.code)
        assertEquals(2.0f, backend.lastTemperature, 1e-5f)
    }
}
