package com.lagradost.cloudstream4.network

import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopHttpTest {
    private val hits = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/fresh") { exchange ->
            hits.incrementAndGet()
            val body = "repo list".toByteArray()
            exchange.responseHeaders.add("Cache-Control", "max-age=300")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    private fun get(path: String) = DesktopHttp.client.newCall(Request.Builder().url(base + path).build()).execute().use { it.body.string() }

    @Test
    fun answersRepeatedRequestsFromItsCacheWhileTheServerSaysTheyAreFresh() {
        assertEquals("repo list", get("/fresh"))
        assertEquals("repo list", get("/fresh"))
        assertEquals(1, hits.get(), "the second request came from the cache")
    }

    @Test
    fun theExtensionsUseIt() {
        DesktopHttp.install(0)
        assertSame(DesktopHttp.client, app.baseClient)
    }

    @Test
    fun everyDnsChoiceBuildsAClient() {
        for (dns in DesktopHttp.dnsProviders.keys) {
            DesktopHttp.install(dns)
            assertSame(DesktopHttp.client, app.baseClient)
            assertTrue(DesktopHttp.client.cache != null)
        }
        DesktopHttp.install(0)
    }

    @Test
    fun theUncachedCallsSkipTheCache() {
        DesktopHttp.uncached.newCall(Request.Builder().url("$base/fresh").build()).execute().close()
        DesktopHttp.uncached.newCall(Request.Builder().url("$base/fresh").build()).execute().close()
        assertEquals(2, hits.get())
    }
}
