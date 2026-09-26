package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.unciv.models.ruleset.Ruleset
import com.unciv.ui.images.ImageGetter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Civ's tech tree re-laid into [LANES] vertical lanes so it only scrolls vertically (portrait, DESIGN.md "Tech").
 * Eras are bands from top to bottom; each tech column becomes one or more rows, and every tech takes the lane
 * closest to the average lane of its prerequisites, keeping the column's left-to-right order.
 *
 * All y values are measured from the top of the tree, growing downwards.
 */
internal class TechTreeLanes(ruleset: Ruleset, width: Float) {
    class Band(val era: String, val top: Float, val height: Float)

    val laneX = List(LANES) { width * (2 * it + 1) / (2 * LANES) }
    /** Center of each tech's disc */
    val centers = HashMap<String, Vector2>()
    val bands = ArrayList<Band>()
    val height: Float

    init {
        val columns = ruleset.technologies.values.filter { it.column != null }
            .groupBy { it.column!!.columnNumber }.toSortedMap().values
        var y = 0f
        var bandEra: String? = null
        var bandTop = 0f
        for (techs in columns) {
            val era = techs.first().era()
            if (era != bandEra) {
                if (bandEra != null) bands += Band(bandEra, bandTop, y - bandTop)
                bandEra = era
                bandTop = y
                y += BAND_HEADER
            }
            fun barycenter(techName: String): Float {
                val placed = ruleset.technologies[techName]!!.prerequisites.mapNotNull { centers[it]?.x }
                return if (placed.isEmpty()) width / 2 else placed.average().toFloat()
            }
            val sorted = techs.sortedWith(compareBy({ barycenter(it.name) }, { it.row })).map { it.name }
            val rowCount = ceil(sorted.size / LANES.toFloat()).toInt()
            for (rowIndex in 0 until rowCount) {
                val row = sorted.filterIndexed { i, _ -> i % rowCount == rowIndex }
                val lanes = bestLanes(row.map { barycenter(it) })
                row.forEachIndexed { i, techName -> centers[techName] = Vector2(laneX[lanes[i]], y + DISC_CENTER) }
                y += ROW_HEIGHT
            }
        }
        if (bandEra != null) bands += Band(bandEra, bandTop, y - bandTop)
        height = y + 24f
    }

    /** The increasing lane indices that put each target closest to its lane */
    private fun bestLanes(targets: List<Float>): List<Int> {
        var best: List<Int> = List(targets.size) { it }
        var bestCost = Float.MAX_VALUE
        fun choose(start: Int, picked: MutableList<Int>) {
            if (picked.size == targets.size) {
                val cost = picked.withIndex().sumOf { (i, lane) -> abs(laneX[lane] - targets[i]).toDouble() }.toFloat()
                if (cost < bestCost) { bestCost = cost; best = picked.toList() }
                return
            }
            for (lane in start..LANES - (targets.size - picked.size)) {
                picked.add(lane)
                choose(lane + 1, picked)
                picked.removeAt(picked.lastIndex)
            }
        }
        choose(0, ArrayList())
        return best
    }

    companion object {
        const val LANES = 4
        const val BAND_HEADER = 50f
        const val ROW_HEIGHT = 162f
        const val DISC_CENTER = 46f
    }
}

/**
 * Draws the prerequisite links of the portrait tech tree: solid curves with a dark casing so crossings stay readable.
 * Coordinates are in this actor's space (y up); set [Link.color], [Link.width] and [Link.hot] before drawing.
 */
internal class TechTreeLines : Actor() {
    class Link(val prerequisite: String, val tech: String, val points: FloatArray) {
        var color: Color = Color.WHITE
        var width = 3f
        /** Drawn above the other links */
        var hot = false
        var dashed = false
    }

    val links = ArrayList<Link>()
    private val dot = ImageGetter.getWhiteDotDrawable().region
    var casingColor: Color = Color.BLACK

    init { touchable = Touchable.disabled }

    /**
     * Adds a link from ([x1], [y1]) down to ([x2], [y2]): a vertical S-curve, or, when that would cross one of the
     * [obstacles] (other techs), a detour down the nearest of the [gutters] between lanes so it can't be misread.
     */
    fun addLink(
        prerequisite: String, tech: String, x1: Float, y1: Float, x2: Float, y2: Float,
        obstacles: List<Rectangle>, gutters: List<Float>,
    ) {
        val direct = curve(x1, y1, x2, y2)
        val blocked = obstacles.any { box -> crosses(direct, box) }
        val turn = 44f
        if (!blocked || gutters.isEmpty() || y1 - y2 < 3 * turn) {
            links += Link(prerequisite, tech, direct)
            return
        }
        val gutter = gutters.minBy { abs(it - x1) + abs(it - x2) }
        links += Link(prerequisite, tech,
            curve(x1, y1, gutter, y1 - turn) + floatArrayOf(gutter, y2 + turn) + curve(gutter, y2 + turn, x2, y2))
    }

    /** Whether the polyline [points] passes through [box], checked every few units along each piece */
    private fun crosses(points: FloatArray, box: Rectangle): Boolean {
        for (i in 0 until points.size / 2 - 1) {
            val x1 = points[2 * i]
            val y1 = points[2 * i + 1]
            val x2 = points[2 * i + 2]
            val y2 = points[2 * i + 3]
            val steps = (Vector2.dst(x1, y1, x2, y2) / 6f).toInt() + 1
            for (step in 0..steps)
                if (box.contains(x1 + (x2 - x1) * step / steps, y1 + (y2 - y1) * step / steps)) return true
        }
        return false
    }

    /** A cubic bezier from ([x1], [y1]) to ([x2], [y2]) with vertical tangents at both ends, as x,y pairs */
    private fun curve(x1: Float, y1: Float, x2: Float, y2: Float): FloatArray {
        val segments = if (x1 == x2) 1 else 20
        val points = FloatArray((segments + 1) * 2)
        val midY = (y1 + y2) / 2
        for (i in 0..segments) {
            val t = i / segments.toFloat()
            val u = 1 - t
            points[2 * i] = u * u * u * x1 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x2
            points[2 * i + 1] = u * u * u * y1 + 3 * u * u * t * midY + 3 * u * t * t * midY + t * t * t * y2
        }
        return points
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val oldColor = batch.color.cpy()
        for (link in links) drawLink(batch, link, casingColor, link.width + 5f, false, parentAlpha)
        for (link in links) if (!link.hot) drawLink(batch, link, link.color, link.width, link.dashed, parentAlpha)
        for (link in links) if (link.hot) drawLink(batch, link, link.color, link.width, link.dashed, parentAlpha)
        batch.color = oldColor
    }

    private fun drawLink(batch: Batch, link: Link, color: Color, width: Float, dashed: Boolean, parentAlpha: Float) {
        batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        val p = link.points
        var travelled = 0f // along the link, to place dashes evenly on curves and straights alike
        for (i in 0 until p.size / 2 - 1) {
            val x1 = x + p[2 * i]
            val y1 = y + p[2 * i + 1]
            val x2 = x + p[2 * i + 2]
            val y2 = y + p[2 * i + 3]
            val length = Vector2.dst(x1, y1, x2, y2)
            if (!dashed) segment(batch, x1, y1, x2, y2, width)
            else {
                // 7 on, 6 off, like the mock
                var from = 0f
                while (from < length) {
                    val phase = (travelled + from) % DASH_PERIOD
                    val to = minOf(length, from + if (phase < DASH_ON) DASH_ON - phase else DASH_PERIOD - phase)
                    if (phase < DASH_ON) segment(batch,
                        x1 + (x2 - x1) * from / length, y1 + (y2 - y1) * from / length,
                        x1 + (x2 - x1) * to / length, y1 + (y2 - y1) * to / length, width, capped = false)
                    from = to
                }
            }
            travelled += length
        }
    }

    /** A straight piece with square ends pushed out by half the width, so consecutive pieces overlap without gaps */
    private fun segment(batch: Batch, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, capped: Boolean = true) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0f) return
        val half = width / 2
        val cap = if (capped) half else 0f
        val startX = x1 - dx / length * cap
        val startY = y1 - dy / length * cap
        val angle = MathUtils.atan2(dy, dx) * MathUtils.radiansToDegrees
        batch.draw(dot, startX, startY - half, 0f, half, length + 2 * cap, width, 1f, 1f, angle)
    }

    private companion object {
        const val DASH_ON = 7f
        const val DASH_PERIOD = 13f
    }
}
