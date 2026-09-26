package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.map.HexMath

/** The presentation plane only. Game hex coordinates and distances remain unchanged. */
class MapProjection(val verticalScale: Float) {
    val tilted get() = verticalScale != 1f

    fun project(point: Vector2): Vector2 {
        if (!tilted) return point
        val x = point.x
        val y = point.y
        return point.set(COS_30 * x - 0.5f * y, (0.5f * x + COS_30 * y) * verticalScale)
    }

    fun inverse(point: Vector2): Vector2 {
        if (!tilted) return point
        val x = point.x
        val y = point.y / verticalScale
        return point.set(COS_30 * x + 0.5f * y, -0.5f * x + COS_30 * y)
    }

    fun contains(x: Float, y: Float, radius: Float): Boolean {
        if (!tilted) return HexMath.isWithinHex(x, y, radius)
        val flatY = y / verticalScale
        return HexMath.isWithinHex(COS_30 * x + 0.5f * flatY, -0.5f * x + COS_30 * flatY, radius)
    }

    companion object {
        const val COS_30 = 0.8660254f
        const val TILE_RADIUS = 40f
        const val PORTRAIT_RADIUS = 31f
        const val DEFAULT_ZOOM = 1.6f
    }
}
