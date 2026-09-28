package com.unciv.ui.components.tilegroups

import com.unciv.json.json
import com.unciv.models.tilesets.TileSetConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitZoomTest {
    @Test fun unitsGrowSlowerThanTheHexPastTheReferenceZoom() {
        assertEquals(1f, MapProjection.damping(1.0f), 0f)
        assertEquals(1f, MapProjection.damping(MapProjection.REFERENCE_ZOOM), 0f)
        assertEquals(0.849f, MapProjection.damping(2.1f), 0.001f)
        assertEquals(0.700f, MapProjection.damping(2.9f), 0.001f)
        // on screen a unit grows as zoom^0.4: 1.27x from Today to Close
        assertEquals(1.269f, 2.9f / 1.6f * MapProjection.damping(2.9f), 0.001f)
        assertEquals(0.5f, MapProjection.iconScale(3.2f), 0f)
    }

    @Test fun detailTiersSwitchAtTheContractZooms() {
        assertEquals(0, MapProjection.lodTier(1.84f))
        assertEquals(1, MapProjection.lodTier(1.85f))
        assertEquals(1, MapProjection.lodTier(2.49f))
        assertEquals(2, MapProjection.lodTier(2.5f))
    }

    @Test fun propsParseMergeInDrawOrderAndClone() {
        val parsed = json().fromJson(TileSetConfig::class.java, """
            {"props": {"TileSets/Polytopia/Tilted/Tiles/Forest2": [
                {"image": "Props/Tuft", "x": 0.18, "y": -0.10, "w": 0.08, "lod": 1},
                {"image": "Props/Pine", "x": -0.20, "y": 0.06, "w": 0.22},
                {"image": "Props/Rows", "w": 0.8, "ground": true}
            ]}}""")
        val config = TileSetConfig()
        config.updateConfig(parsed)
        val props = config.props.getValue("TileSets/Polytopia/Tilted/Tiles/Forest2")
        assertEquals(listOf("Props/Rows", "Props/Pine", "Props/Tuft"), props.map { it.image })
        assertEquals(1, props[2].lod)
        assertEquals(0.22f, props[1].w, 0f)
        assertEquals(true, props[0].ground)
        assertEquals(3, config.clone().props.getValue("TileSets/Polytopia/Tilted/Tiles/Forest2").size)
        assertEquals(0, TileSetConfig().props.size)
    }
}
