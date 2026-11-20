package app.wattlo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Keeps the shared settings ([Settings.Synced]) equal on the watch and the phone through the Wear OS Data Layer.
 *
 * Each device writes its own data item at [PATH] (a data item is owned by the node that wrote it) with the values
 * and the time of the change. The other device takes them when they are newer than its own change: the newest
 * change wins. A device's own item arrives back in its listener too, but it is never newer than itself, so nothing
 * loops. Data Layer keeps the item until the other device connects, so settings changed while the phone and the
 * watch were apart arrive later. Without Google Play services (or without a paired device) every call here is a
 * quiet no-op and the app works on its own settings.
 */
/** Whether the phone has a watch to talk to: none, a watch without the app, or a watch with the app. */
enum class WatchState { NONE, NO_APP, READY }

/** [nodeIds] are the connected watches, in case the app has to be installed on one of them. */
data class WatchInfo(val state: WatchState, val nodeIds: List<String>)

object SettingsSync {
    const val PATH = "/settings"
    private const val TAG = "SettingsSync"

    /** The watch app advertises it (res/values/wear.xml of :app), so the phone can tell watches that have the app. */
    const val WATCH_CAPABILITY = "wattlo_watch"

    /**
     * Asks the Data Layer about the watch; [done] runs on the main thread, with null when the answer is unknown
     * (no Google Play services, or the request failed).
     */
    fun watchInfo(c: Context, done: (WatchInfo?) -> Unit) {
        runCatching {
            val app = c.applicationContext
            Wearable.getCapabilityClient(app).getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
                .addOnSuccessListener { cap ->
                    // nodes with the app, whether or not they are near right now: changes will reach them later
                    if (cap.nodes.isNotEmpty()) return@addOnSuccessListener done(WatchInfo(WatchState.READY, cap.nodes.map { it.id }))
                    Wearable.getNodeClient(app).connectedNodes
                        .addOnSuccessListener { nodes ->
                            done(WatchInfo(if (nodes.isEmpty()) WatchState.NONE else WatchState.NO_APP, nodes.map { it.id }))
                        }
                        .addOnFailureListener { done(null) }
                }
                .addOnFailureListener { done(null) }
        }.onFailure { done(null) }
    }

    /** Send the current shared settings to the other device. */
    fun push(c: Context) {
        runCatching {
            val v = Settings.synced(c)
            Log.i(TAG, "push queue=${v.queue} ts=${Settings.syncTs(c)}")
            val req = PutDataMapRequest.create(PATH).also {
                it.dataMap.putLong("ts", Settings.syncTs(c))
                it.dataMap.putString("region", v.region)
                it.dataMap.putString("source", v.source)
                it.dataMap.putString("queue", v.queue)
                it.dataMap.putBoolean("maybeOff", v.maybeOff)
                it.dataMap.putInt("notifyOff", v.notifyOff)
                it.dataMap.putInt("notifyOn", v.notifyOn)
                it.dataMap.putInt("quietFrom", v.quietFrom)
                it.dataMap.putInt("quietTo", v.quietTo)
                it.dataMap.putBoolean("notifyToday", v.notifyToday)
                it.dataMap.putBoolean("notifyTomorrow", v.notifyTomorrow)
                it.dataMap.putInt("tileRings", v.tileRings)
                it.dataMap.putBoolean("tileInnerLabels", v.tileInnerLabels)
                it.dataMap.putBoolean("tileShowNext", v.tileShowNext)
                it.dataMap.putInt("wearAppRings", v.wearAppRings)
                it.dataMap.putBoolean("wearAppInnerLabels", v.wearAppInnerLabels)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(c.applicationContext).putDataItem(req)
                .addOnFailureListener { Log.w(TAG, "push failed: $it") }
        }.onFailure { Log.w(TAG, "push unavailable: $it") }
    }

    /** Catch up with what the other device sent while this app was not running. Cheap; call on process start. */
    fun pull(c: Context) {
        runCatching {
            val uri = Uri.Builder().scheme("wear").path(PATH).build() // no host: the items of all nodes
            Wearable.getDataClient(c.applicationContext).getDataItems(uri)
                .addOnSuccessListener { items ->
                    try {
                        // the newest of the items; handle() ignores it unless it beats this device's own change
                        items.map { DataMapItem.fromDataItem(it).dataMap }
                            .maxByOrNull { it.getLong("ts") }?.let { handle(c, it) }
                    } finally { items.release() }
                }
                .addOnFailureListener { Log.w(TAG, "pull failed: $it") }
        }.onFailure { Log.w(TAG, "pull unavailable: $it") }
    }

    internal fun handle(c: Context, m: DataMap) {
        val app = c.applicationContext
        val v = Settings.Synced(
            m.getString("source", ""), m.getString("queue", ""), m.getBoolean("maybeOff", true),
            m.getInt("notifyOff", 0), m.getInt("notifyOn", 0), m.getInt("quietFrom", 0), m.getInt("quietTo", 0),
            m.getBoolean("notifyToday", false), m.getBoolean("notifyTomorrow", false),
            m.getInt("tileRings", 1), m.getBoolean("tileInnerLabels", true), m.getBoolean("tileShowNext", true),
            m.getInt("wearAppRings", 1), m.getBoolean("wearAppInnerLabels", true),
            m.getString("region", Region.POLTAVA.name), // an older device sends none
        )
        val dataChanged = Settings.applySynced(app, v, m.getLong("ts")) ?: return
        Log.i(TAG, "applied queue=${v.queue} ts=${m.getLong("ts")} (source/queue changed: $dataChanged)")
        Fetcher.notifyAll(app) // redraw the surfaces, re-plan the reminders
        if (dataChanged) Refresh.now(app) // the cache was cleared: fetch the schedule of the new queue
    }
}

/** Receives the other device's settings while the app is not running. */
class SettingsSyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == SettingsSync.PATH) {
                SettingsSync.handle(applicationContext, DataMapItem.fromDataItem(e.dataItem).dataMap)
            }
        }
    }
}
