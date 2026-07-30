package com.dorastream

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Replays whatever Cloudflare session [DoraStreamCfState] currently holds.
 * Does NOT solve challenges itself - only injects a previously-solved
 * cookie + UA, plus a couple of client-hint headers that match what a real
 * Android WebView sends (helps the request look consistent with the browser
 * that originally solved the challenge).
 */
object CloudflareBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .removeHeader("X-Requested-With")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")

        DoraStreamCfState.userAgent?.let { ua -> builder.header("User-Agent", ua) }

        DoraStreamCfState.cookies?.let { savedCookies ->
            val existing = original.header("Cookie").orEmpty()
                .split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val fresh = savedCookies
                .split(";").map { it.trim() }.filter { it.isNotEmpty() }
            builder.header("Cookie", (fresh + existing).distinct().joinToString("; "))
        }

        return chain.proceed(builder.build())
    }
}

/**
 * Ensures at most one Cloudflare-solve attempt (and therefore at most one
 * dialog) is ever active system-wide. Any caller that arrives while a solve
 * is already in flight does NOT decide anything or recheck anything itself
 * - it just awaits the one attempt already running and gets the same
 * result everyone else gets. Once that attempt finishes (dialog closed,
 * inFlight cleared), the next caller to hit a blocked response starts a
 * fresh attempt from scratch. This is what actually prevents a second
 * dialog from a caller's own recheck going badly, not just from two
 * dialogs opening at the literal same instant.
 */
object DoraStreamCfBypass {
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<Boolean>? = null

    suspend fun solveOnce(targetUrl: String, solver: suspend (String) -> Boolean): Boolean {
        val existing = mutex.withLock {
            inFlight?.let { return@withLock it }
            val deferred = CompletableDeferred<Boolean>()
            inFlight = deferred
            null
        }
        if (existing != null) return existing.await()

        val result = runCatching { solver(targetUrl) }.getOrDefault(false)
        mutex.withLock {
            inFlight?.complete(result)
            inFlight = null
        }
        return result
    }
}

/**
 * True only for an actual, currently-blocked challenge response - gated on
 * status code first so a normal 200 page can never be misidentified just
 * because Cloudflare's Bot Management script tag happens to be present
 * sitewide (it is, on plenty of pages that aren't actually blocking us).
 */
fun isCloudflareBlocked(response: NiceResponse): Boolean {
    if (response.code != 403 && response.code != 503) return false
    val lower = response.text.lowercase()
    return lower.contains("<title>just a moment") ||
            lower.contains("id=\"challenge-form\"") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("checking your browser before accessing")
}

/** Also writes the captured cookies into Cloudstream's own CookieJar, so any
 * request elsewhere in the app (extractors, etc.) that doesn't explicitly
 * pass CloudflareBypassInterceptor still carries a valid session. */
fun injectCookiesToApp(url: String) {
    val savedCookies = DoraStreamCfState.cookies ?: return
    runCatching {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookies = savedCookies.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { Cookie.parse(httpUrl, it) }
        if (cookies.isNotEmpty()) {
            app.baseClient.cookieJar.saveFromResponse(httpUrl, cookies)
        }
    }
}

/**
 * Headers for Cloudstream's own image loader to attach when fetching a
 * poster/thumbnail URL - this is a SEPARATE pipeline from app.get() calls
 * and does not automatically pick up cookies from app.baseClient's jar or
 * anything CloudflareBypassInterceptor injects. Without this, HTML pages
 * load fine (since those go through our explicit interceptor) while every
 * poster image quietly 403s, because the image loader has no idea it needs
 * a Cloudflare session at all. Set as `posterHeaders` on the SearchResponse
 * / LoadResponse Cloudstream builds.
 */
fun cfHeaders(mainUrl: String): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    headers["referer"] = "$mainUrl/"
    DoraStreamCfState.userAgent?.let { headers["User-Agent"] = it }

    var combinedCookies = DoraStreamCfState.cookies.orEmpty()
    runCatching {
        val httpUrl = mainUrl.toHttpUrlOrNull() ?: return@runCatching
        val jarCookies = app.baseClient.cookieJar.loadForRequest(httpUrl)
        if (jarCookies.isNotEmpty()) {
            val jarCookieString = jarCookies.joinToString("; ") { "${it.name}=${it.value}" }
            combinedCookies = if (combinedCookies.isNotBlank()) {
                "$combinedCookies; $jarCookieString"
            } else {
                jarCookieString
            }
        }
    }
    if (combinedCookies.isNotBlank()) headers["Cookie"] = combinedCookies

    return headers
}

/**
 * Shows the bypass dialog and waits for it, unconditionally (caller is
 * responsible for deciding whether a fresh solve is actually needed).
 */
private suspend fun showBypassDialog(targetUrl: String): Boolean {
    val fragmentActivity = com.lagradost.cloudstream3.CommonActivity.activity
            as? androidx.fragment.app.FragmentActivity ?: return false

    return withContext(Dispatchers.Main) {
        CloudflareBypassDialog.showAndWait(fragmentActivity.supportFragmentManager, targetUrl)
    }
}

/**
 * The main entry point every request should go through instead of a bare
 * app.get(). If blocked, defers entirely to DoraStreamCfBypass.solveOnce -
 * this caller does not decide whether to show a dialog itself, it just
 * asks for a solve and waits for whatever the single in-flight attempt
 * (its own, or one already running for someone else) produces.
 */
suspend fun cfSafeGet(
    url: String,
    getter: suspend (String) -> NiceResponse,
): NiceResponse {
    val raw = getter(url)
    if (!isCloudflareBlocked(raw)) return raw

    val solved = DoraStreamCfBypass.solveOnce(url) { targetUrl ->
        DoraStreamCfState.cookies = null
        val ok = showBypassDialog(targetUrl)
        if (ok) injectCookiesToApp(targetUrl)
        ok
    }

    return if (solved) getter(url) else raw
}