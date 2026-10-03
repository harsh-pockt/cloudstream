package com.lagradost.cloudstream3.network

import okhttp3.Request
import okhttp3.Response

/**
 * The browser behind WebViewResolver on desktop, which has no Android WebView. The desktop app
 * installs one in [DesktopWebView.engine]; without one, WebViewResolver finds nothing and requests
 * go through unchanged.
 */
interface DesktopWebView {
    /** The browser's own user agent, which cookies it earned only work with */
    val userAgent: String?

    /**
     * Loads [request] in a new page and reports each request the page makes to [onRequest], until
     * [onRequest] returns true or [timeoutMs] passes. [script] runs after each request, its result
     * going to [onScript]. [userAgent] replaces the browser's own when set.
     */
    suspend fun load(
        request: Request,
        userAgent: String?,
        script: String?,
        onScript: ((String) -> Unit)?,
        timeoutMs: Long,
        onRequest: (Request) -> Boolean,
    )

    /** The browser's cookies for [url] as a Cookie header, like Android's CookieManager.getCookie */
    fun cookies(url: String): String?

    /**
     * Makes [request] from inside the browser, on a page of the same site, for sites that refuse
     * any other client even with the browser's cookies. Null when the browser could not make it
     */
    suspend fun fetch(request: Request): Response?

    companion object {
        @Volatile
        var engine: DesktopWebView? = null
    }
}
