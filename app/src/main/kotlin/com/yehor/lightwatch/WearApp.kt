package com.yehor.lightwatch

import android.app.Application
import android.content.Intent
import androidx.wear.tiles.TileService

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Surfaces.onChange = { c ->
            TileService.getUpdater(c).requestUpdate(LightTileService::class.java)
            LightComplicationService.requestUpdate(c)
        }
        Surfaces.onFetched = { c -> TileService.getUpdater(c).requestUpdate(LightTileService::class.java) }
        Surfaces.openApp = { c -> Intent(c, MainActivity::class.java) }
    }
}
