package com.dorastream

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
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

/** One mutex, shared across every request this provider makes. */
object DoraStreamCfBypass {
    val mutex = Mutex()
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
 * app.get(). Mirrors the double-checked-locking pattern used by the
 * confirmed-working DoraBash provider: check once outside the lock (fast
 * path, no contention on the common case), and if blocked, take the mutex,
 * check AGAIN inside it (in case a concurrent caller already solved it
 * while we were waiting), and only then show the dialog. This is what
 * prevents every concurrent request from popping its own dialog at once.
 */
suspend fun cfSafeGet(
    url: String,
    getter: suspend (String) -> NiceResponse,
): NiceResponse {
    val raw = getter(url)
    if (!isCloudflareBlocked(raw)) return raw

    return DoraStreamCfBypass.mutex.withLock {
        val recheck = getter(url)
        if (!isCloudflareBlocked(recheck)) return@withLock recheck

        // Still blocked even after acquiring the lock - the cached cookie
        // (if any) isn't working, so drop it and force a fresh solve.
        DoraStreamCfState.cookies = null
        val solved = showBypassDialog(url)
        if (solved) injectCookiesToApp(url)

        if (solved) getter(url) else recheck
    }
}