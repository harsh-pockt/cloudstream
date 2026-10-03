package com.lagradost.cloudstream4.network

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream4.AppDirs
import com.lagradost.cloudstream4.settings
import com.lagradost.nicehttp.ignoreAllSSLErrors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The one HTTP client of the app. Extensions (through `app`), posters, subtitles, repositories and
 * updates all go through it, so they share one set of open connections and threads instead of each
 * keeping its own, and repeated requests are answered from a disk cache when the server allows it.
 * Built like the Android app's buildDefaultClient, with its DNS over HTTPS choices.
 */
object DesktopHttp {
    /** The Android app's dns_key values: 0 is the system's DNS */
    val dnsProviders: Map<Int, String> = linkedMapOf(
        0 to "None", 1 to "Google", 2 to "Cloudflare", 4 to "AdGuard", 5 to "DNS.WATCH",
        6 to "Quad9", 7 to "DNS.SB", 8 to "Canadian Shield",
    )

    private val pool = ConnectionPool(maxIdleConnections = 10, keepAliveDuration = 2, timeUnit = TimeUnit.MINUTES)
    private val dispatcher = Dispatcher()

    /** Where responses are cached, changed by tests only */
    @Volatile var cacheDir: File = AppDirs.cache.resolve("http").toFile()

    // One cache for every client built here: two caches on one folder would corrupt it
    private val cache by lazy { Cache(cacheDir, CACHE_BYTES) }

    @Volatile private var dns = 0

    @Volatile private var current: OkHttpClient? = null

    /** The client, with the DNS picked in the settings */
    val client: OkHttpClient
        get() = current ?: synchronized(this) { current ?: build(dns).also { current = it } }

    /**
     * Sets the DNS and makes the extensions use the client. Called at start and whenever the DNS
     * setting changes, as the Android app does.
     */
    fun install(dns: Int) {
        synchronized(this) {
            this.dns = dns
            current = build(dns)
        }
        app.baseClient = client
        // For the few extensions whose sites have broken certificates, as on Android
        insecureApp.baseClient = client.newBuilder().ignoreAllSSLErrors().build()
    }

    @Volatile private var uncachedClient: Pair<OkHttpClient, OkHttpClient>? = null

    /**
     * Calls through the client without its cache, for loaders that keep a cache of their own, such as
     * Coil. It follows DNS changes too
     */
    val uncached: Call.Factory = Call.Factory { request ->
        val base = client
        val made = uncachedClient?.takeIf { it.first === base }?.second
            ?: base.newBuilder().cache(null).build().also { uncachedClient = base to it }
        made.newCall(request)
    }

    /** Uses the DNS from the settings now and whenever it changes */
    fun start(scope: CoroutineScope) {
        val dns = settings.general.dns
        install(dns.get())
        scope.launch { dns.changes().drop(1).collect(::install) }
    }

    private fun build(dns: Int): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .cache(cache)
            .followRedirects(true)
            .followSslRedirects(true)
        val (url, ips) = dohServers[dns] ?: return builder.build()
        // The resolver's own lookups go through a client without it, by its fixed addresses
        val bootstrap = builder.build()
        return builder.dns(
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .build()
        ).build()
    }

    /** The Android app's DohProviders */
    private val dohServers = mapOf(
        1 to ("https://dns.google/dns-query" to listOf("8.8.4.4", "8.8.8.8")),
        2 to ("https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
        4 to ("https://dns.adguard.com/dns-query" to listOf("94.140.14.140", "94.140.14.141")),
        5 to ("https://resolver2.dns.watch/dns-query" to listOf("84.200.69.80", "84.200.70.40")),
        6 to ("https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")),
        7 to ("https://doh.dns.sb/dns-query" to listOf("185.222.222.222", "45.11.45.11")),
        8 to ("https://private.canadianshield.cira.ca/dns-query" to listOf("149.112.121.10", "149.112.122.10")),
    )

    /** As on Android */
    private const val CACHE_BYTES = 50L * 1024 * 1024
}
