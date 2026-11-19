package app.wattlo

import android.content.Context
import android.content.Intent

/**
 * Hooks for the surfaces of the host app (the watch has a tile and a complication, the phone has widgets).
 * Each app sets them in its Application.onCreate, which runs before any worker, receiver or service of the process.
 */
object Surfaces {
    /** Called after the cached schedule or the error changed, or a display setting changed. */
    @Volatile var onChange: ((Context) -> Unit)? = null

    /** Called when only the time of the last fetch changed. Defaults to [onChange]. */
    @Volatile var onFetched: ((Context) -> Unit)? = null

    /** The intent that opens the app's main screen (from a notification). Defaults to the launcher intent. */
    @Volatile var openApp: ((Context) -> Intent)? = null

    internal fun changed(c: Context) { onChange?.invoke(c) }
    internal fun fetched(c: Context) { (onFetched ?: onChange)?.invoke(c) }
    internal fun openIntent(c: Context): Intent =
        openApp?.invoke(c) ?: c.packageManager.getLaunchIntentForPackage(c.packageName) ?: Intent()
}
