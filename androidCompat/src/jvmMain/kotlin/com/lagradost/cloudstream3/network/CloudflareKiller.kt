package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * The Android app's CloudflareKiller. A response from Cloudflare refusing the request (403 or 503)
 * opens the page in the desktop's browser ([DesktopWebView]) until Cloudflare's check passes and
 * gives its cf_clearance cookie; the request is then sent again with the browser's cookies and user
 * agent. A site that still refuses it, because it only trusts the browser itself, gets the request
 * from inside the browser. Without a browser, requests go through unchanged.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** Gets the headers with the saved cookies, with the browser's user agent */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if (!isRefused(response)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        println("INFO $TAG: Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }

            else -> return@runBlocking proceed(request, cookies)
        }

        println("WARN $TAG: Failed cloudflare at: ${request.url}")
        return@runBlocking chain.proceed(request)
    }

    /**
     * Returns true if the cf cookies were found in the browser. Also saves the cookies.
     */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        val cookie = runCatching { DesktopWebView.engine?.cookies(request.url.toString()) }.getOrNull() ?: return false
        return cookie.contains("cf_clearance").also { solved ->
            if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
        }
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers = getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        val response = app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
        if (!isRefused(response)) return response
        val fetched = runCatching { DesktopWebView.engine?.fetch(request) }.getOrNull() ?: return response
        response.close()
        return fetched
    }

    private fun isRefused(response: Response) = response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (DesktopWebView.engine == null) return null
        if (!trySolveWithSavedCookies(request)) {
            println("INFO $TAG: Loading the browser to solve cloudflare for ${request.url}")
            WebViewResolver(
                // Never exit based on url
                Regex(".^"),
                // Cloudflare needs the browser's own user agent
                userAgent = null,
                useOkhttp = false,
                // Match every url for the requestCallBack
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(request.url.toString()) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}
