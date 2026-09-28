package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.math.Vector2
import com.unciv.logic.map.HexMath
import kotlin.math.pow

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
        /** Portrait normalized zoom the map opens at */
        const val DEFAULT_ZOOM = 2.1f
        /** Portrait normalized zoom every unit, prop and icon size is defined at */
        const val REFERENCE_ZOOM = 1.6f
        const val MAX_ZOOM = 2.9f

        /** Units, cities and upright props grow as zoom^0.4 once zoomed in past the reference, so the hex wins */
        fun damping(zoom: Float) = if (zoom > REFERENCE_ZOOM) (zoom / REFERENCE_ZOOM).pow(-0.6f) else 1f

        /** More prop detail shows the closer the zoom */
        fun lodTier(zoom: Float) = when {
            zoom < 1.85f -> 0
            zoom < 2.5f -> 1
            else -> 2
        }

        /** Map-space scale that holds an icon at the screen size it has at the reference zoom */
        fun iconScale(zoom: Float) = REFERENCE_ZOOM / zoom
    }
}
