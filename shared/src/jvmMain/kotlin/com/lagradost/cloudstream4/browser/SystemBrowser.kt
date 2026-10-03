package com.lagradost.cloudstream4.browser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.network.DesktopWebView
import com.lagradost.cloudstream4.AppDirs
import com.lagradost.cloudstream4.android.DesktopAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The browser behind extensions' WebViewResolver and CloudflareKiller on desktop: Microsoft Edge,
 * which comes with Windows, or Chrome, run with its own profile in the app's cache folder and driven
 * through the DevTools protocol. Its window stays off screen, unless a page waits on Cloudflare's
 * check for a few seconds: then it shows, so the user can tick the box. The browser closes after a
 * few idle minutes, and when the app exits.
 */
class SystemBrowser(
    private val executable: File,
    private val profile: File = AppDirs.cache.resolve("browser").toFile(),
    /** Where the window opens: off screen, unless someone needs to watch it */
    private val hidden: Boolean = true,
) : DesktopWebView {
    private val json = jacksonObjectMapper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private class Connection(val process: Process, val socket: WebSocket)

    @Volatile private var connection: Connection? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonNode>>()
    /** Events of each page, by its DevTools session */
    private val sessions = ConcurrentHashMap<String, Channel<JsonNode>>()
    private val loads = AtomicInteger(0)
    private var idleJob: Job? = null

    @Volatile override var userAgent: String? = null
        private set

    /** Called with the browser's process id before its window comes forward, which Windows must allow */
    var onShow: ((Long) -> Unit)? = null

    override suspend fun load(
        request: Request,
        userAgent: String?,
        script: String?,
        onScript: ((String) -> Unit)?,
        timeoutMs: Long,
        onRequest: (Request) -> Boolean,
    ) {
        loads.incrementAndGet()
        idleJob?.cancel()
        try {
            start()
            val target = call("Target.createTarget", mapOf("url" to "about:blank")).path("targetId").asText()
            try {
                val session = call("Target.attachToTarget", mapOf("targetId" to target, "flatten" to true)).path("sessionId").asText()
                val events = Channel<JsonNode>(Channel.UNLIMITED)
                sessions[session] = events
                try {
                    browse(request, userAgent, script, onScript, timeoutMs, onRequest, target, session, events)
                } finally {
                    sessions.remove(session)
                    events.close()
                }
            } finally {
                runCatching { call("Target.closeTarget", mapOf("targetId" to target)) }
            }
        } finally {
            if (loads.decrementAndGet() == 0) idleJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                if (loads.get() == 0) stop()
            }
        }
    }

    private suspend fun browse(
        request: Request,
        userAgent: String?,
        script: String?,
        onScript: ((String) -> Unit)?,
        timeoutMs: Long,
        onRequest: (Request) -> Boolean,
        target: String,
        session: String,
        events: Channel<JsonNode>,
    ) {
        call("Network.enable", session = session)
        if (userAgent != null) call("Network.setUserAgentOverride", mapOf("userAgent" to userAgent), session)
        // Pictures, fonts and media are not needed to find links, as on Android
        call("Network.setBlockedURLs", mapOf("urls" to BLOCKED), session)
        val extra = request.headers.toMap().filterKeys { it.lowercase() !in setOf("user-agent", "referer", "cookie") }
        if (extra.isNotEmpty()) call("Network.setExtraHTTPHeaders", mapOf("headers" to extra), session)
        val referer = request.header("Referer")
        call("Page.navigate", buildMap { put("url", request.url.toString()); if (referer != null) put("referrer", referer) }, session)

        val shown = CompletableDeferred<Unit>()
        val checker = showWhenChallenged(request, target, session, shown)
        try {
            withTimeoutOrNull(timeoutMs) {
                for (event in events) {
                    if (event.path("method").asText() != "Network.requestWillBeSent") continue
                    val sent = event.path("params").path("request")
                    val seen = toRequest(sent) ?: continue
                    if (script != null) {
                        runCatching { evaluateJson(script, session) }.getOrNull()?.let { onScript?.invoke(it) }
                    }
                    if (onRequest(seen)) break
                }
            }
        } finally {
            checker.cancel()
            if (shown.isCompleted) runCatching { place(target, onScreen = false, session) }
        }
    }

    /** A page still on Cloudflare's check after a few seconds needs the user: the window comes on screen */
    private fun showWhenChallenged(request: Request, target: String, session: String, shown: CompletableDeferred<Unit>) = scope.launch {
        delay(SHOW_AFTER_MS)
        val title = runCatching { evaluate("document.title", session) }.getOrNull().orEmpty()
        if (CHALLENGE_TITLES.any { title.contains(it, ignoreCase = true) }) {
            DesktopAndroid.toastHandler?.invoke("${request.url.host} checks you are human: tick the box in the browser window")
            runCatching { place(target, onScreen = true, session) }
            shown.complete(Unit)
        }
    }

    /** Moves the page's window on screen for the user, or back off screen */
    private suspend fun place(target: String, onScreen: Boolean, session: String) {
        val window = call("Browser.getWindowForTarget", mapOf("targetId" to target)).path("windowId").asInt()
        val bounds = if (onScreen) mapOf("left" to 120, "top" to 80, "width" to 900, "height" to 720) else mapOf("left" to OFF_SCREEN, "top" to OFF_SCREEN)
        call("Browser.setWindowBounds", mapOf("windowId" to window, "bounds" to bounds + ("windowState" to "normal")))
        if (onScreen) {
            connection?.process?.pid()?.let { onShow?.invoke(it) }
            call("Target.activateTarget", mapOf("targetId" to target))
            call("Page.bringToFront", session = session)
        }
    }

    private fun toRequest(sent: JsonNode): Request? = runCatching {
        val url = sent.path("url").asText()
        if (!url.startsWith("http")) return null
        Request.Builder().url(url).apply {
            sent.path("headers").properties().forEach { (name, value) -> header(name, value.asText()) }
            val method = sent.path("method").asText("GET")
            if (method != "GET") {
                val body = sent.path("postData").asText("").toByteArray()
                method(method, if (method == "HEAD") null else body.toRequestBody())
            }
        }.build()
    }.getOrNull()

    private suspend fun evaluate(expression: String, session: String): String? =
        call("Runtime.evaluate", mapOf("expression" to expression, "returnByValue" to true), session).path("result").path("value").takeIf { !it.isMissingNode }?.asText()

    /** The script's result as JSON, like Android's evaluateJavascript */
    private suspend fun evaluateJson(expression: String, session: String): String {
        val value = call("Runtime.evaluate", mapOf("expression" to expression, "returnByValue" to true, "awaitPromise" to true), session).path("result").path("value")
        return if (value.isMissingNode) "null" else json.writeValueAsString(value)
    }

    override suspend fun fetch(request: Request): Response? {
        loads.incrementAndGet()
        idleJob?.cancel()
        try {
            start()
            val target = call("Target.createTarget", mapOf("url" to "about:blank")).path("targetId").asText()
            try {
                val session = call("Target.attachToTarget", mapOf("targetId" to target, "flatten" to true)).path("sessionId").asText()
                val events = Channel<JsonNode>(Channel.UNLIMITED)
                sessions[session] = events
                try {
                    return fetchIn(target, session, events, request)
                } finally {
                    sessions.remove(session)
                    events.close()
                }
            } finally {
                runCatching { call("Target.closeTarget", mapOf("targetId" to target)) }
            }
        } catch (e: Exception) {
            println("WARN SystemBrowser: fetch of ${request.url} failed: $e")
            return null
        } finally {
            if (loads.decrementAndGet() == 0) idleJob = scope.launch {
                delay(IDLE_CLOSE_MS)
                if (loads.get() == 0) stop()
            }
        }
    }

    private suspend fun fetchIn(target: String, session: String, events: Channel<JsonNode>, request: Request): Response? =
        if (request.method == "GET") navigateIn(target, session, events, request) else fetchInPage(session, events, request)

    /** Loads a GET request as the page itself, which Cloudflare trusts more than a script's fetch() */
    private suspend fun navigateIn(target: String, session: String, events: Channel<JsonNode>, request: Request): Response? {
        val url = request.url
        call("Network.enable", session = session)
        call("Page.enable", session = session)
        call("Network.setBlockedURLs", mapOf("urls" to BLOCKED), session)
        val extra = request.headers.toMap().filterKeys { it.lowercase() !in setOf("user-agent", "referer", "cookie") }
        if (extra.isNotEmpty()) call("Network.setExtraHTTPHeaders", mapOf("headers" to extra), session)
        call("Page.navigate", buildMap { put("url", url.toString()); request.header("Referer")?.let { put("referrer", it) } }, session)
        // The page's last document: Cloudflare's check marks its page, and loads the real one once passed
        var document: JsonNode? = null
        var finished = false
        val shown = CompletableDeferred<Unit>()
        val checker = showWhenChallenged(request, target, session, shown)
        try {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                for (event in events) {
                    val params = event.path("params")
                    when (event.path("method").asText()) {
                        "Network.responseReceived" -> if (params.path("type").asText() == "Document") {
                            document = params
                            finished = false
                        }

                        "Network.loadingFinished" -> if (params.path("requestId").asText() == document?.path("requestId")?.asText()) {
                            finished = true
                        }

                        // Once the page is up, it is either the real one or Cloudflare's check, which loads the real one when passed
                        "Page.loadEventFired" -> if (finished) {
                            val marked = (document?.path("response")?.path("headers") as? ObjectNode)?.fields()?.asSequence()
                                ?.any { it.key.equals("cf-mitigated", ignoreCase = true) && it.value.asText() == "challenge" } == true
                            val title = runCatching { evaluate("document.title", session) }.getOrNull().orEmpty()
                            if (!marked && CHALLENGE_TITLES.none { title.contains(it, ignoreCase = true) }) break
                        }
                    }
                }
            }
        } finally {
            checker.cancel()
            if (shown.isCompleted) runCatching { place(target, onScreen = false, session) }
        }
        val done = document?.takeIf { finished } ?: return null
        val response = done.path("response")
        val body = call("Network.getResponseBody", mapOf("requestId" to done.path("requestId").asText()), session)
        val bytes = if (body.path("base64Encoded").asBoolean()) Base64.getDecoder().decode(body.path("body").asText())
        else body.path("body").asText().toByteArray()
        val headers = Headers.Builder().apply {
            (response.path("headers") as? ObjectNode)?.fields()?.forEach { (name, value) ->
                // Several values of a header come joined by new lines
                value.asText().split('\n').forEach { runCatching { addUnsafeNonAscii(name, it) } }
            }
            // The body is already decoded
            removeAll("Content-Encoding")
            removeAll("Content-Length")
        }.build()
        println("INFO SystemBrowser: loaded $url in the page: ${response.path("status").asInt()}")
        return Response.Builder()
            .request(request.newBuilder().url(response.path("url").asText(url.toString())).build())
            .protocol(Protocol.HTTP_1_1)
            .code(response.path("status").asInt())
            .message(response.path("statusText").asText(""))
            .headers(headers)
            .body(bytes.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }

    private suspend fun fetchInPage(session: String, events: Channel<JsonNode>, request: Request): Response? {
        // fetch() only reaches the page's own site: a small file of it is opened first
        val url = request.url
        val origin = "${url.scheme}://${url.host}" + if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""
        call("Page.enable", session = session)
        call("Page.navigate", mapOf("url" to "$origin/robots.txt"), session)
        withTimeoutOrNull(20_000) {
            for (event in events) if (event.path("method").asText() == "Page.loadEventFired") break
        }
        val init = buildMap<String, Any?> {
            put("method", request.method)
            put("credentials", "include")
            put("headers", request.headers.toMap().filterKeys { it.lowercase() !in FORBIDDEN_FETCH_HEADERS })
            request.header("Referer")?.let { put("referrer", it) }
            request.body?.let { body ->
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                put("body", buffer.readUtf8())
            }
        }
        val script = """
            (async () => {
              const r = await fetch(${json.writeValueAsString(url.toString())}, ${json.writeValueAsString(init)});
              const bytes = new Uint8Array(await r.arrayBuffer());
              let binary = "";
              for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
              return { status: r.status, url: r.url, headers: [...r.headers.entries()], body: btoa(binary) };
            })()
        """.trimIndent()
        val evaluated = call("Runtime.evaluate", mapOf("expression" to script, "returnByValue" to true, "awaitPromise" to true), session)
        val result = evaluated.path("result").path("value")
        if (result.isMissingNode || !result.has("status")) {
            println("WARN SystemBrowser: fetch of $url failed in the page: ${evaluated.path("exceptionDetails").path("exception").path("description").asText(evaluated.toString().take(300))}")
            return null
        }
        println("INFO SystemBrowser: fetched $url in the page: ${result.path("status").asInt()}")
        val headers = Headers.Builder().apply {
            result.path("headers").forEach { pair -> runCatching { addUnsafeNonAscii(pair[0].asText(), pair[1].asText()) } }
        }.build()
        val body = Base64.getDecoder().decode(result.path("body").asText())
        return Response.Builder()
            .request(request.newBuilder().url(result.path("url").asText(url.toString())).build())
            .protocol(Protocol.HTTP_1_1)
            .code(result.path("status").asInt())
            .message("")
            .headers(headers)
            .body(body.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }

    override fun cookies(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        if (connection == null) return null
        val all = runBlocking { withTimeout(10_000) { call("Storage.getCookies") } }.path("cookies")
        val matching = all.filter { cookie ->
            val domain = cookie.path("domain").asText().trimStart('.')
            host == domain || host.endsWith(".$domain")
        }
        if (matching.isEmpty()) return null
        return matching.joinToString("; ") { "${it.path("name").asText()}=${it.path("value").asText()}" }
    }

    /** Sends one DevTools command and waits for its result */
    private suspend fun call(method: String, params: Map<String, Any?> = emptyMap(), session: String? = null): JsonNode {
        val socket = connection?.socket ?: throw IllegalStateException("The browser is not running")
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<JsonNode>()
        pending[id] = answer
        val message = buildMap { put("id", id); put("method", method); put("params", params); if (session != null) put("sessionId", session) }
        if (!socket.send(json.writeValueAsString(message))) {
            pending.remove(id)
            throw IllegalStateException("The browser closed")
        }
        val reply = try {
            withTimeout(CALL_TIMEOUT_MS) { answer.await() }
        } finally {
            pending.remove(id)
        }
        reply.path("error").takeIf { !it.isMissingNode }?.let { throw IllegalStateException("$method: ${it.path("message").asText()}") }
        return reply.path("result")
    }

    private suspend fun start() = startLock.withLock {
        connection?.takeIf { it.process.isAlive }?.let { return }
        connection = null
        profile.mkdirs()
        val portFile = profile.resolve("DevToolsActivePort")
        portFile.delete()
        val process = ProcessBuilder(
            executable.absolutePath,
            "--remote-debugging-port=0",
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-sync",
            "--hide-crash-restore-bubble",
            "--disable-features=msImplicitSignin,msEdgeWelcomePage",
            if (hidden) "--window-position=$OFF_SCREEN,$OFF_SCREEN" else "--window-position=120,80",
            "--window-size=1100,800",
            "about:blank",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        // The browser writes its DevTools port and address once it listens
        val lines = withTimeoutOrNull(START_TIMEOUT_MS) { devToolsAddress(portFile, process) }
        if (lines == null) {
            process.destroyForcibly()
            throw IllegalStateException("${executable.name} did not start")
        }
        val opened = CompletableDeferred<Unit>()
        val socket = http.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { json.readTree(text) }.getOrNull() ?: return
                    val id = message.path("id")
                    if (!id.isMissingNode) {
                        pending[id.asInt()]?.complete(message)
                    } else {
                        message.path("sessionId").asText(null)?.let { sessions[it]?.trySend(message) }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t)
                    failAll(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    failAll(IllegalStateException("The browser closed"))
                }
            },
        )
        withTimeout(START_TIMEOUT_MS) { opened.await() }
        connection = Connection(process, socket)
        userAgent = runCatching { call("Browser.getVersion").path("userAgent").asText() }.getOrNull()
        println("INFO SystemBrowser: started ${executable.name}, $userAgent")
    }

    private suspend fun devToolsAddress(portFile: File, process: Process): List<String>? {
        while (process.isAlive) {
            val lines = runCatching { portFile.readLines() }.getOrNull()
            if (lines != null && lines.size >= 2) return lines
            delay(100)
        }
        return null
    }

    private fun failAll(error: Throwable) {
        connection = null
        pending.values.forEach { it.completeExceptionally(error) }
        sessions.values.forEach { it.close() }
    }

    /** Closes the browser, also when the app exits */
    fun stop() {
        val current = connection ?: return
        connection = null
        runCatching { current.socket.send("""{"id":0,"method":"Browser.close"}""") }
        if (!current.process.waitFor(3, TimeUnit.SECONDS)) {
            current.process.descendants().forEach { it.destroyForcibly() }
            current.process.destroyForcibly()
        }
        current.socket.cancel()
    }

    private fun JsonNode.properties(): List<Pair<String, JsonNode>> =
        (this as? ObjectNode)?.fields()?.asSequence()?.map { it.key to it.value }?.toList().orEmpty()

    companion object {
        private const val OFF_SCREEN = -32000
        private const val SHOW_AFTER_MS = 5_000L
        private const val CALL_TIMEOUT_MS = 30_000L
        private const val START_TIMEOUT_MS = 20_000L
        private const val IDLE_CLOSE_MS = 3 * 60_000L
        private const val FETCH_TIMEOUT_MS = 60_000L
        /** Headers a page's fetch() may not set: the browser sends its own */
        private val FORBIDDEN_FETCH_HEADERS = setOf(
            "user-agent", "cookie", "referer", "origin", "host", "content-length", "connection", "accept-encoding",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
        )
        private val CHALLENGE_TITLES = listOf("Just a moment", "Attention Required", "Un instant")
        private val BLOCKED = listOf(
            "*.jpg", "*.jpeg", "*.png", "*.webp", "*.gif", "*.gifv", "*.mp4", "*.mp3", "*.webm", "*.mkv",
            "*.avi", "*.mov", "*.flv", "*.ogg", "*.wav", "*.woff", "*.woff2", "*.ttf", "*.ts", "*.vtt", "*.srt",
            "*/favicon.ico",
        )

        /** Edge, which comes with Windows, else Chrome or Chromium. Null when none is installed */
        fun find(): File? {
            val env = System.getenv()
            val windows = listOfNotNull(env["ProgramFiles(x86)"], env["ProgramFiles"], env["LOCALAPPDATA"]).flatMap { base ->
                listOf("Microsoft\\Edge\\Application\\msedge.exe", "Google\\Chrome\\Application\\chrome.exe").map { File(base, it) }
            }
            val others = listOf(
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/usr/bin/microsoft-edge", "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
            ).map(::File)
            return (windows + others).firstOrNull { it.isFile }
        }

        /** Installs the browser for extensions, when one is found */
        fun install(): SystemBrowser? {
            val executable = find() ?: return null.also { println("WARN SystemBrowser: no Edge or Chrome found, extensions cannot use a WebView") }
            val browser = SystemBrowser(executable)
            DesktopWebView.engine = browser
            Runtime.getRuntime().addShutdownHook(Thread { browser.stop() })
            return browser
        }
    }
}
