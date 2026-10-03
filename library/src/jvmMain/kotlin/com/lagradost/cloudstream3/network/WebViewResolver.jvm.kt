package com.lagradost.cloudstream3.network

import com.lagradost.api.Log
import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.requestCreator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking

private const val TAG = "WebViewResolver"

/**
 * When used as Interceptor additionalUrls cannot be returned, use WebViewResolver(...).resolveUsingWebView(...)
 * @param interceptUrl will stop the WebView when reaching this url.
 * @param additionalUrls this will make resolveUsingWebView also return all other requests matching the list of Regex.
 * @param userAgent if null then will use the default user agent
 * @param useOkhttp will try to use the okhttp client as much as possible, but this might cause some requests to fail. Disable for cloudflare.
 * @param script pass custom js to execute
 * @param scriptCallback will be called with the result from custom js
 * @param timeout close webview after timeout
 * */
actual class WebViewResolver actual constructor(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex>,
    val userAgent: String?,
    // The browser makes every request itself: desktop has no way to hand them to okhttp
    @Suppress("unused") val useOkhttp: Boolean,
    val script: String?,
    val scriptCallback: ((String) -> Unit)?,
    val timeout: Long
) :
    Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixedRequest = resolveUsingWebView(request).first
            chain.proceed(fixedRequest ?: request)
        }
    }

    actual companion object {
        actual val DEFAULT_TIMEOUT = 60_000L
        actual var webViewUserAgent: String? = null
            get() = field ?: DesktopWebView.engine?.userAgent?.also { field = it }

        /** The Android app's, which Android extensions call */
        @JvmName("getWebViewUserAgent1")
        fun getWebViewUserAgent(): String? = webViewUserAgent
    }

    actual suspend fun resolveUsingWebView(
        url: String,
        referer: String?,
        method: String,
        requestCallBack: (Request) -> Boolean,
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    actual suspend fun resolveUsingWebView(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        method: String,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        return try {
            resolveUsingWebView(
                requestCreator(method, url, referer = referer, headers = headers), requestCallBack
            )
        } catch (e: java.lang.IllegalArgumentException) {
            logError(e)
            debugException { "ILLEGAL URL IN resolveUsingWebView!" }
            return null to emptyList()
        }
    }

    /** As on Android: stops at the first request matching [interceptUrl], collecting those matching [additionalUrls] */
    actual suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        val engine = DesktopWebView.engine
        if (engine == null) {
            Log.w(TAG, "No browser to load ${request.url}")
            return null to emptyList()
        }
        var fixedRequest: Request? = null
        val extraRequestList = Collections.synchronizedList(ArrayList<Request>())
        try {
            engine.load(request, userAgent, script, scriptCallback, timeout) { seen ->
                val url = seen.url.toString()
                when {
                    interceptUrl.containsMatchIn(url) -> {
                        fixedRequest = seen
                        requestCallBack(seen)
                        true
                    }

                    additionalUrls.any { it.containsMatchIn(url) } -> {
                        extraRequestList.add(seen)
                        requestCallBack(seen)
                    }

                    else -> false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
        }
        engine.userAgent?.let { webViewUserAgent = it }
        return fixedRequest to extraRequestList.toList()
    }
}

