package android.content

/**
 * Minimal stand-in for Android's Context so that extensions written against
 * `Plugin.load(context: Context)` can be loaded on the desktop. Calls into
 * Android-only APIs still fail, but only inside the extension that makes them.
 */
open class Context
