package com.yehor.lightwatch

import android.app.Application
import androidx.wear.tiles.TileService

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Surfaces.onChange = { c ->
            TileService.getUpdater(c).requestUpdate(LightTileService::class.java)
            LightComplicationService.requestUpdate(c)
        }
    }
}
