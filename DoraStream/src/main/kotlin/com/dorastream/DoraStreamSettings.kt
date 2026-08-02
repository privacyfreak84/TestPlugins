package com.dorastream

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Byse bundles all dub languages into one HLS manifest (multiple
 * EXT-X-MEDIA audio renditions) rather than exposing separate URLs per
 * language like every other server here does. Cloudstream's own in-app
 * track switcher is how you're meant to pick between them - but that's
 * the exact UI that freezes playback on TV when opened. This setting
 * lets loadLinks rewrite Byse's manifest so the preferred language is
 * already marked DEFAULT=YES before the player ever sees it, so the
 * track switcher never needs to be touched.
 */
object DoraStreamBysePref {
    private const val PREFS = "DoraStreamByse"
    private const val KEY_LANG = "PREFERRED_AUDIO_LANG"

    // Values match the language codes we look for in Byse's manifest
    // (EXT-X-MEDIA LANGUAGE="xx" / NAME="..."). "" means no preference -
    // leave the manifest's own default alone.
    val OPTIONS = listOf("" to "No preference", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu")

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun getPreferredLang(): String = prefs?.getString(KEY_LANG, "").orEmpty()

    fun setPreferredLang(lang: String) {
        prefs?.edit()?.putString(KEY_LANG, lang)?.apply()
    }
}

class DoraStreamSettingsDialog : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        root.addView(TextView(ctx).apply {
            text = "Preferred Byse audio language"
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(ctx).apply {
            text = "Byse bundles all dubs into one stream - this picks which one " +
                    "loads by default so you don't need to open the track switcher."
            textSize = 12f
            setPadding(0, 0, 0, 16)
        })

        val current = DoraStreamBysePref.getPreferredLang()

        DoraStreamBysePref.OPTIONS.forEach { (code, label) ->
            root.addView(Button(ctx).apply {
                text = if (code == current) "\u2713 $label" else label
                setOnClickListener {
                    DoraStreamBysePref.setPreferredLang(code)
                    dismiss()
                }
            })
        }

        return root
    }
}