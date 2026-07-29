package com.dorastream

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shows a REAL, visible WebView pointed at [targetUrl] so a live Cloudflare
 * challenge (Turnstile checkbox, "Just a moment...", DDoS-Guard, etc.) can
 * actually be solved - either automatically by the JS challenge running in a
 * real browser engine, or by the user tapping the checkbox if one appears.
 *
 * Every [POLL_INTERVAL_MS] it checks CookieManager for a fresh cf_clearance
 * cookie AND makes sure the page title no longer looks like a challenge page
 * before declaring success - checking the cookie alone is not enough, since
 * Cloudflare can hand out a cookie before the challenge has actually cleared.
 *
 * On success, the cookie string + the WebView's live User-Agent are stashed
 * in [DoraStreamCfState] so [CloudflareBypassInterceptor] can replay them on
 * ordinary (non-WebView) requests until they expire.
 */
class CloudflareBypassDialog(
    private val targetUrl: String,
    private val onFinished: (success: Boolean) -> Unit,
) : BottomSheetDialogFragment() {

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 90_000L
        private const val CURSOR_SIZE_PX = 24f
        private const val CURSOR_STEP_PX = 50f

        private val CHALLENGE_TITLES = listOf(
            "just a moment",
            "just a moment...",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "one more step",
        )

        private fun isChallengeTitle(title: String): Boolean {
            val lower = title.lowercase(Locale.ROOT)
            return CHALLENGE_TITLES.any { lower.contains(it) }
        }

        /**
         * Suspends the calling coroutine until the dialog finishes (either by
         * solving the challenge or timing out / being dismissed). Must be
         * called from a context where [fragmentManager] is available - i.e.
         * with an Activity reference already in hand.
         */
        suspend fun showAndWait(
            fragmentManager: androidx.fragment.app.FragmentManager,
            targetUrl: String,
        ): Boolean = suspendCancellableCoroutine { cont ->
            val dialog = CloudflareBypassDialog(targetUrl) { success ->
                if (cont.isActive) cont.resume(success)
            }
            dialog.show(fragmentManager, "DoraStreamCfBypass")
        }
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var cookiesSaved = false
    private var pollElapsedMs = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val targetHost: String by lazy {
        runCatching {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(targetUrl)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return

            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost).orEmpty()
            val title = webView?.title.orEmpty()
            val challenge = isChallengeTitle(title)
            val hasClearance = Regex("cf_clearance=[^;]{15,}").containsMatchIn(cookieStr)
            val hasDdgCookie = cookieStr.contains("__ddg1_") || cookieStr.contains("__ddg2_")

            when {
                hasClearance && !challenge -> saveCookiesAndFinish(cookieStr)
                hasDdgCookie && !challenge -> saveCookiesAndFinish(cookieStr)
                pollElapsedMs >= POLL_TIMEOUT_MS -> {
                    updateStatus("Timed out waiting for the challenge to clear.")
                    finish(success = false)
                }
                else -> {
                    pollElapsedMs += POLL_INTERVAL_MS
                    updateStatus("Waiting for Cloudflare... (${pollElapsedMs / 1000}s)")
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    private var pendingCursorView: android.view.View? = null

    override fun onStart() {
        super.onStart()
        // BottomSheetDialogFragment's underlying Dialog isn't always fully
        // ready when onCreateView runs - re-attach here too as a safety net
        // so the D-pad listener reliably ends up registered either way.
        pendingCursorView?.let { cv ->
            dialog?.setOnKeyListener { _, keyCode, event -> handleTvRemoteKey(keyCode, event, cv) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): android.view.View {
        val screenHeight = requireContext().resources.displayMetrics.heightPixels
        val webViewHeight = (screenHeight * 0.7).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val title = TextView(requireContext()).apply {
            text = "DoraStream — solving Cloudflare challenge"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        root.addView(title)

        val status = TextView(requireContext()).apply {
            text = "Loading challenge page..."
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        statusText = status
        root.addView(status)

        val progress = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        root.addView(progress)

        // WebView sits inside a FrameLayout rather than being added to root
        // directly, so the TV cursor dot below can be layered on top of it
        // at an arbitrary (x, y) via translationX/Y.
        val wvContainer = FrameLayout(requireContext())
        webView = buildWebView()
        wvContainer.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Android TV has no touchscreen, so there's no way to tap a
        // Turnstile checkbox directly. This adds a small on-screen dot that
        // a D-pad remote can move around and "click" - dispatching a
        // synthetic touch event into the WebView at the dot's position.
        // Harmless no-op on touch devices; it only reacts to D-pad key
        // events, which touchscreens don't send.
        val cursorView = android.view.View(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(CURSOR_SIZE_PX.toInt(), CURSOR_SIZE_PX.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.RED)
                setStroke(4, android.graphics.Color.WHITE)
            }
            elevation = 100f
        }
        wvContainer.addView(cursorView)
        pendingCursorView = cursorView

        wvContainer.post {
            if (cursorX == 0f && cursorY == 0f && wvContainer.width > 0) {
                cursorX = wvContainer.width / 2f
                cursorY = wvContainer.height / 2f
                cursorView.translationX = cursorX
                cursorView.translationY = cursorY
            }
        }

        // setOnKeyListener is only reachable once the underlying Dialog
        // exists, which BottomSheetDialogFragment creates for us - safe to
        // grab it here since onCreateView runs after that.
        dialog?.setOnKeyListener { _, keyCode, event ->
            handleTvRemoteKey(keyCode, event, cursorView)
        }

        root.addView(
            wvContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, webViewHeight),
        )

        return root
    }

    private var cursorX = 0f
    private var cursorY = 0f

    private fun handleTvRemoteKey(keyCode: Int, event: KeyEvent, cursorView: android.view.View): Boolean {
        val wv = webView ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                cursorY = max(cursorY - CURSOR_STEP_PX, 0f)
                if (cursorY <= 10f) wv.scrollBy(0, -CURSOR_STEP_PX.toInt())
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                cursorY = min(cursorY + CURSOR_STEP_PX, wv.height - CURSOR_SIZE_PX.toFloat())
                if (cursorY >= wv.height - CURSOR_SIZE_PX - 10f) wv.scrollBy(0, CURSOR_STEP_PX.toInt())
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                cursorX = max(cursorX - CURSOR_STEP_PX, 0f)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                cursorX = min(cursorX + CURSOR_STEP_PX, wv.width - CURSOR_SIZE_PX.toFloat())
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val time = SystemClock.uptimeMillis()
                val cx = cursorX + CURSOR_SIZE_PX / 2f
                val cy = cursorY + CURSOR_SIZE_PX / 2f
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, cx, cy, 0)
                val up = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_UP, cx, cy, 0)
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                true
            }
            else -> false
        }

        if (handled) {
            cursorView.translationX = cursorX
            cursorView.translationY = cursorY
        }
        return handled
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            userAgentString = wv.settings.userAgentString.replace("; wv", "")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Kick off the first poll once the page has actually loaded,
                // not immediately on dialog creation.
                if (pollElapsedMs == 0L) {
                    handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
                }
            }
        }
        wv.loadUrl(targetUrl)
        return wv
    }

    private fun saveCookiesAndFinish(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(pollRunnable)

        DoraStreamCfState.cookies = cookieStr
        DoraStreamCfState.cookieHost = targetHost
        DoraStreamCfState.userAgent = webView?.settings?.userAgentString

        updateStatus("Done - cookies captured.")
        webView?.postDelayed({ finish(success = true) }, 800L)
    }

    private fun finish(success: Boolean) {
        if (isAdded) {
            onFinished(success)
            dismissAllowingStateLoss()
        }
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread { statusText?.text = msg }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            handler.removeCallbacks(pollRunnable)
            onFinished(false)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(pollRunnable)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}

/** Holds the most recently captured Cloudflare session for DoraStream. */
object DoraStreamCfState {
    private const val PREFS = "dorastream_cf"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_HOST = "cookie_host"
    private const val KEY_UA = "user_agent"

    private var appContext: android.content.Context? = null
    private var restored = false
    private var restoring = false

    var cookies: String? = null
        set(value) { field = value; persist() }
    var cookieHost: String? = null
        set(value) { field = value; persist() }
    var userAgent: String? = null
        set(value) { field = value; persist() }

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        restore()
    }

    private fun restore() {
        if (restored) return
        restored = true
        val ctx = appContext ?: return
        restoring = true
        val prefs = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        cookies = prefs.getString(KEY_COOKIES, null)
        cookieHost = prefs.getString(KEY_HOST, null)
        userAgent = prefs.getString(KEY_UA, null)
        restoring = false
    }

    private fun persist() {
        if (restoring) return
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putString(KEY_COOKIES, cookies)
            .putString(KEY_HOST, cookieHost)
            .putString(KEY_UA, userAgent)
            .apply()
    }
}