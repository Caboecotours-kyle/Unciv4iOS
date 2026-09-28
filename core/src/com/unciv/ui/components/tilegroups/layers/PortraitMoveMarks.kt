package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Stroke styles of the portrait move range. Widths are in logical points and hold at every zoom. */
enum class RangeStroke(val width: Float, val color: Color, val shadowWidth: Float = 0f, val shadowAlpha: Float = 0f,
                       val dash: Float = 0f, val gap: Float = 0f) {
    /** Around the area reachable this turn */
    ThisTurn(2.8f, Color.WHITE, 5.5f, .4f),
    /** Around the area reachable next turn */
    NextTurn(2f, Color.WHITE, 4f, .35f, dash = 8f, gap = 3f),
    /** Around an enemy the unit can attack */
    Attack(2.6f, Color(237 / 255f, 41 / 255f, 57 / 255f, 1f));


    companion object {
        /** The mock's outline shadow, #081928 */
        val shadow = Color(8 / 255f, 25 / 255f, 40 / 255f, 1f)
    }
}

/** One tile's share of the portrait move range outlines. It sits in the tile's row just under the tile's units, so a
 *  unit in front of a line covers it the way it covers the ground; widths are converted from points when drawn, so a
 *  pinch needs no rebuild and nothing is allocated per frame. Segment coordinates are relative to this actor. */
internal class RangeEdges(private val tileGroup: TileGroup) : Actor() {
    /** Per segment: start x, start y, length, cos, sin, degrees, stroke ordinal */
    private var segments = FloatArray(7 * 8)
    private var count = 0
    private val dot: TextureRegion = ImageGetter.getWhiteDotDrawable().region

    init { touchable = Touchable.disabled }

    fun add(stroke: RangeStroke, from: Vector2, to: Vector2) {
        if (segments.size < (count + 1) * 7) segments = segments.copyOf(segments.size * 2)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0f) return
        val i = count++ * 7
        segments[i] = from.x
        segments[i + 1] = from.y
        segments[i + 2] = length
        segments[i + 3] = dx / length
        segments[i + 4] = dy / length
        segments[i + 5] = atan2(dy, dx) * 180f / Math.PI.toFloat()
        segments[i + 6] = stroke.ordinal.toFloat()
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val points = tileGroup.portraitPointScale
        val old = batch.packedColor
        // Shadows first, then the white rings, then red on top where they share an edge
        for (pass in 0..2) for (s in 0 until count) {
            val i = s * 7
            val stroke = RangeStroke.entries[segments[i + 6].toInt()]
            val onTop = stroke == RangeStroke.Attack
            val width = when {
                pass == 0 -> stroke.shadowWidth
                pass == 1 && !onTop -> stroke.width
                pass == 2 && onTop -> stroke.width
                else -> 0f
            } * points
            if (width == 0f) continue
            if (pass == 0) batch.setColor(RangeStroke.shadow.r, RangeStroke.shadow.g, RangeStroke.shadow.b, stroke.shadowAlpha * parentAlpha)
            else batch.setColor(stroke.color.r, stroke.color.g, stroke.color.b, parentAlpha)
            // Lines reach half a line width past their ends, so neighbouring edges close their corners
            val cap = stroke.width * points / 2f
            if (stroke.dash == 0f) line(batch, i, -cap, segments[i + 2] + cap, width)
            else {
                // Round-capped dashes in the mock: the shadow dash runs one shadow cap past the line dash
                val extra = if (pass == 0) (stroke.shadowWidth - stroke.width) / 2f * points else 0f
                val period = (stroke.dash + stroke.gap) * points
                var start = 0f
                while (start < segments[i + 2]) {
                    line(batch, i, start - extra, min(start + stroke.dash * points, segments[i + 2]) + extra, width)
                    start += period
                }
            }
        }
        batch.packedColor = old
    }

    /** Draws segment [i] from [start] to [end] along it, [width] wide */
    private fun line(batch: Batch, i: Int, start: Float, end: Float, width: Float) {
        val cos = segments[i + 3]
        val sin = segments[i + 4]
        batch.draw(dot, x + segments[i] + cos * start + sin * width / 2f, y + segments[i + 1] + sin * start - cos * width / 2f,
            0f, 0f, max(0f, end - start), width, 1f, 1f, segments[i + 5])
    }
}

/** A flat hexagon tint under a tile's units: red on an enemy the unit can attack, yellow on the chosen destination */
internal fun TileLayer.rangeFill(color: Color, alpha: Float, scale: Float): Image =
    getGroundImage(strings.hexagon).apply {
        touchable = Touchable.disabled
        setHexagonSize(scale)
        this.color = color.cpy().apply { a = alpha }
    }

/** Moves this colour's red, green and blue [amount] of the way toward [target]'s, keeping its alpha */
internal fun Color.shadeToward(target: Color, amount: Float) {
    r += (target.r - r) * amount
    g += (target.g - g) * amount
    b += (target.b - b) * amount
}
