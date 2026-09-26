package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.utils.viewport.Viewport
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.widgets.ZoomableScrollPane

class CityMapHolder : ZoomableScrollPane(20f, 20f) {

    init {
        setupZoomPanListeners()
    }

    fun setDefaultZoom(viewport: Viewport) {
        val map = actor as TileGroupMap<*>
        if (map.mapVerticalScale == 1f) return
        val defaultZoom = map.getDefaultZoom(viewport)
        maxZoom = maxOf(maxZoom, defaultZoom)
        zoom(defaultZoom)
    }

    private fun setupZoomPanListeners() {

        fun setActHit() {
            val isEnabled = !isZooming() && !isPanning
            (stage as UncivStage).performPointerEnterExitEvents = isEnabled
            val tileGroupMap = actor as TileGroupMap<*>
            tileGroupMap.shouldAct = isEnabled
            tileGroupMap.shouldHit = isEnabled
        }

        onPanStartListener = { setActHit() }
        onPanStopListener = { setActHit() }
        onZoomStartListener = { setActHit() }
        onZoomStopListener = { setActHit() }
    }

}
