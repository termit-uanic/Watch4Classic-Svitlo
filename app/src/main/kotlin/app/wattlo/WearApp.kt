package app.wattlo

import android.app.Application
import android.content.Intent
import androidx.wear.tiles.TileService

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.isWatch = true
        Surfaces.onChange = { c ->
            TileService.getUpdater(c).requestUpdate(WattloTileService::class.java)
            WattloComplicationService.requestUpdate(c)
        }
        Surfaces.onFetched = { c -> TileService.getUpdater(c).requestUpdate(WattloTileService::class.java) }
        Surfaces.openApp = { c -> Intent(c, MainActivity::class.java) }
        Settings.publishOnce(this) // tell the phone how the watch looks, once
        SettingsSync.pull(this)
    }
}
