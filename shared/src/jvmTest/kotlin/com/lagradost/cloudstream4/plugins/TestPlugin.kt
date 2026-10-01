package com.lagradost.cloudstream4.plugins

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

class TestProvider : MainAPI() {
    override var name = "Desktop Test Provider"
    override var mainUrl = "https://example.invalid"
}

/** Packed into a jar by the tests, the same way a real cross-platform plugin is published */
@CloudstreamPlugin
class TestPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TestProvider())
        loads++
    }

    override fun beforeUnload() {
        unloads++
    }

    companion object {
        var loads = 0
        var unloads = 0
    }
}

/** Has no annotation, so it must never be picked as the plugin class */
class NotAPlugin
