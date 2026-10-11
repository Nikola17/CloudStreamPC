package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * Desktop version of the Android app's `Plugin` class. Most extensions extend it
 * instead of [BasePlugin], so it has to exist with the same JVM signatures.
 */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null

    var openSettings: ((context: Context) -> Unit)? = null
}
