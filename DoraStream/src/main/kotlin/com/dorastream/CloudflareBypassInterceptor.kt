package com.dorastream

import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Replays whatever Cloudflare session [CloudflareBypassDialog] most recently
 * captured. This does NOT solve challenges itself - it only injects a
 * previously-solved cookie + User-Agent onto outgoing requests, same as
 * CinemacityCFBypassInterceptor does for that plugin.
 */
object CloudflareBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        DoraStreamCfState.userAgent?.let { ua ->
            builder.header("User-Agent", ua)
        }

        DoraStreamCfState.cookies?.let { savedCookies ->
            val existing = original.header("Cookie").orEmpty()
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val fresh = savedCookies
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val merged = (fresh + existing).distinct().joinToString("; ")
            builder.header("Cookie", merged)
        }

        return chain.proceed(builder.build())
    }
}

/**
 * True if [html]/[title] look like an unsolved Cloudflare/DDoS-Guard
 * challenge page rather than real site content. Cheap heuristic: same
 * title-matching approach used inside CloudflareBypassDialog's poll loop.
 */
fun looksLikeCfChallenge(title: String, html: String): Boolean {
    val lowerTitle = title.lowercase()
    val challengeTitle = listOf(
        "just a moment", "checking your browser", "attention required", "ddos-guard", "one more step",
    ).any { lowerTitle.contains(it) }
    val challengeMarkers = listOf("cf-chl-", "cf_chl_", "challenges.cloudflare.com", "/cdn-cgi/challenge-platform/")
        .any { html.contains(it) }
    return challengeTitle || challengeMarkers
}

/**
 * Ensures a valid Cloudflare session exists for [targetUrl], showing the
 * bypass dialog if needed. Call this once, before your first `app.get(...)`
 * for a session (e.g. at the top of getMainPage/search/load), rather than on
 * every single request - the captured cookie is reused across requests until
 * it's rejected again.
 *
 * Returns true if a session is present afterwards (either it was already
 * cached, or the dialog captured one). Must be called from a coroutine;
 * internally hops to Main to show the dialog since Fragment APIs require it.
 */
suspend fun ensureCloudflareSession(targetUrl: String): Boolean {
    if (DoraStreamCfState.cookies != null) return true

    val fragmentActivity = CommonActivity.activity as? androidx.fragment.app.FragmentActivity
        ?: return false

    return withContext(Dispatchers.Main) {
        CloudflareBypassDialog.showAndWait(fragmentActivity.supportFragmentManager, targetUrl)
    }
}