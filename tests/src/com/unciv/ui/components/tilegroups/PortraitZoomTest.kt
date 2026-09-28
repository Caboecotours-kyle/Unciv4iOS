package com.unciv.ui.components.tilegroups

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
}
