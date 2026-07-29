package com.dorastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DoraStreamProvider : Plugin() {
    override fun load(context: Context) {
        DoraStreamCfState.init(context)
        registerMainAPI(DoraStream())
    }
}