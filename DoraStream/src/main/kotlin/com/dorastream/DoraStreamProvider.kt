package com.dorastream

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DoraStreamProvider : Plugin() {
    override fun load(context: Context) {
        DoraStreamCfState.init(context)
        DoraStreamBysePref.init(context)
        registerMainAPI(DoraStream())
        openSettings = { ctx ->
            (ctx as? FragmentActivity)?.let { activity ->
                DoraStreamSettingsDialog().show(activity.supportFragmentManager, "DoraStreamSettings")
            }
        }
    }
}