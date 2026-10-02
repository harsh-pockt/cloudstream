package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * The Android app's CloudflareKiller. On Android it solves Cloudflare's check in a WebView, which
 * desktop does not have, so here it only adds cookies saved for the host and otherwise passes
 * requests through unchanged. Sites behind the check fail as they would without it.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** Gets the headers with the saved cookies */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = savedCookies[request.url.host] ?: return chain.proceed(request)
        val cookieHeader = cookies.entries.joinToString(" ") { "${it.key}=${it.value};" }
        return chain.proceed(request.newBuilder().header("Cookie", cookieHeader).build())
    }
}
