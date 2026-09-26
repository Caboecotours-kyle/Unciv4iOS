package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.math.Vector2
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.tilegroups.MapProjection
import kotlin.math.atan2
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView
import com.unciv.view.TileView
import com.unciv.ui.components.tilegroups.TileGroup
import kotlin.math.PI
import kotlin.math.atan

class TileLayerBorders(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    data class BorderSegment(
        var images: List<Image>,
        var isLeftConcave: Boolean = false,
        var isRightConcave: Boolean = false,
    ) {
        override fun hashCode(): Int {
            var result = images.hashCode()
            result = 31 * result + if (isLeftConcave) 1231 else 1237
            result = 31 * result + if (isRightConcave) 1231 else 1237
            return result
        }
    }

    private val portraitImages = ArrayList<Image>()
    private var portraitKey = ""

    private var previousTileOwner: ForeignCivView? = null
    private val borderSegments = HashMap<TileView, BorderSegment>()

    fun reset() {
        portraitImages.forEach { removeOwnedActor(it) }
        portraitImages.clear()
        portraitKey = ""
        if (borderSegments.isNotEmpty()) {
            for (borderSegment in borderSegments.values)
                for (image in borderSegment.images)
                    removeOwnedActor(image)
            borderSegments.clear()
        }
    }


    private fun updateBorders() {

        // This is longer than it could be, because of performance -
        // before fixing, about half (!) the time of update() was wasted on
        // removing all the border images and putting them back again!

        val tileView = tileGroup.tileView
        val tileOwner = tileView.getOwner()

        // If owner changed - clear previous borders
        if (previousTileOwner != tileOwner)
            reset()

        previousTileOwner = tileOwner

        // No owner - no borders
        if (tileOwner == null)
            return

        val tileMapView = tileView.getTileMap()

        // Setup new borders
        val civOuterColor = tileOwner.getOuterColor()
        val civInnerColor = tileOwner.getInnerColor()
        for (neighbor in tileView.getVisibleNeighbors()) {
            var shouldRemoveBorderSegment = false
            var shouldAddBorderSegment = false

            var borderSegmentShouldBeLeftConcave = false
            var borderSegmentShouldBeRightConcave = false

            val neighborOwner = neighbor.getOwner()
            if (neighborOwner == tileOwner && borderSegments.containsKey(neighbor)) { // the neighbor used to not belong to us, but now it's ours
                shouldRemoveBorderSegment = true
            }
            else if (neighborOwner != tileOwner) {
                val leftSharedNeighbor = tileMapView.getLeftSharedNeighbor(tileView, neighbor)
                val rightSharedNeighbor = tileMapView.getRightSharedNeighbor(tileView, neighbor)

                // If a shared neighbor doesn't exist (because it's past a map edge), we act as if it's our tile for border concave/convex-ity purposes.
                // This is because we do not draw borders against non-existing tiles either.
                borderSegmentShouldBeLeftConcave = leftSharedNeighbor == null || leftSharedNeighbor.getOwner() == tileOwner
                borderSegmentShouldBeRightConcave = rightSharedNeighbor == null || rightSharedNeighbor.getOwner() == tileOwner

                if (!borderSegments.containsKey(neighbor)) { // there should be a border here but there isn't
                    shouldAddBorderSegment = true
                }
                else if (
                        borderSegmentShouldBeLeftConcave != borderSegments[neighbor]!!.isLeftConcave ||
                        borderSegmentShouldBeRightConcave != borderSegments[neighbor]!!.isRightConcave
                ) { // the concave/convex-ity of the border here is wrong
                    shouldRemoveBorderSegment = true
                    shouldAddBorderSegment = true
                }
            }

            if (shouldRemoveBorderSegment) {
                for (image in borderSegments[neighbor]!!.images)
                    removeOwnedActor(image)
                borderSegments.remove(neighbor)
            }
            if (shouldAddBorderSegment) {
                val images = mutableListOf<Image>()
                val borderSegment = BorderSegment(
                    images,
                    borderSegmentShouldBeLeftConcave,
                    borderSegmentShouldBeRightConcave
                )
                borderSegments[neighbor] = borderSegment

                val borderShapeString = when {
                    borderSegment.isLeftConcave && borderSegment.isRightConcave -> "Concave"
                    !borderSegment.isLeftConcave && !borderSegment.isRightConcave -> "Convex"
                    !borderSegment.isLeftConcave && borderSegment.isRightConcave -> "ConvexConcave"
                    borderSegment.isLeftConcave && !borderSegment.isRightConcave -> "ConcaveConvex"
                    else -> error("This shouldn't happen?")
                }

                val relativeWorldPosition = tileMapView.getNeighborTilePositionAsWorldCoords(tileView, neighbor)

                val sign = if (relativeWorldPosition.x < 0) -1 else 1
                val angle = sign * (atan(sign * relativeWorldPosition.y / relativeWorldPosition.x) * 180 / PI - 90.0).toFloat()

                val innerBorderImage = getGroundImage(
                    strings.orFallback { getBorder(borderShapeString,"Inner") }
                ).setHexagonSize()

                addOwnedActor(innerBorderImage)
                images.add(innerBorderImage)
                innerBorderImage.rotateBy(angle)
                innerBorderImage.color = civOuterColor

                val outerBorderImage = getGroundImage(
                    strings.orFallback { getBorder(borderShapeString, "Outer") }
                ).setHexagonSize()

                addOwnedActor(outerBorderImage)
                images.add(outerBorderImage)
                outerBorderImage.rotateBy(angle)
                outerBorderImage.color = civInnerColor
            }
        }

    }

    private fun updatePortraitBorders() {
        val tileView = tileGroup.tileView
        val owner = tileView.getOwner()
        val neighbors = tileView.getVisibleNeighbors().filter { it.getOwner() != owner }.toList()
        val key = "${owner?.civName}:${tileGroup.strategicView}:" + neighbors.joinToString { it.position().toString() }
        if (key == portraitKey) return
        reset()
        portraitKey = key
        if (owner == null) return
        val outerColor = owner.getOuterColor()
        // Light outer colors, such as Greece's white, disappear on snow and clouds.
        val outlineColor = if (minOf(outerColor.r, outerColor.g, outerColor.b) >= 0.9f)
            owner.getInnerColor() else outerColor
        val tint = getGroundImage(strings.hexagon).setHexagonSize()
        tint.color = outlineColor.cpy().apply { a = 0.12f }
        addOwnedActor(tint)
        portraitImages.add(tint)
        val radius = MapProjection.TILE_RADIUS * 0.93f
        val insetRatio = 1f - radius / MapProjection.TILE_RADIUS
        val tileMapView = tileView.getTileMap()
        val tilePosition = tileView.position()
        for (neighbor in neighbors) {
            // The cached clock vector points back from the neighbor; do not normalize it in place.
            val direction = Vector2(tileMapView.getNeighborTilePositionAsWorldCoords(tileView, neighbor)).scl(-1f).nor()
            val midpoint = Vector2(direction).scl(radius * MapProjection.COS_30)
            val tangent = Vector2(-direction.y, direction.x).scl(radius / 2f)
            val from = strings.projection.project(Vector2(midpoint).add(tangent))
            val to = strings.projection.project(Vector2(midpoint).sub(tangent))
            val thickness = if (tileGroup.strategicView) 7f else 3.2f * 40f / 31f
            addPortraitLine(from, to, thickness, outlineColor)

            // Adjacent owned tiles inset toward different centers; bridge their shared vertex.
            for (shared in arrayOf(tileMapView.getLeftSharedNeighbor(tileView, neighbor),
                    tileMapView.getRightSharedNeighbor(tileView, neighbor))) {
                if (shared?.getOwner() != owner) continue
                val sharedPosition = shared.position()
                if (tilePosition.x > sharedPosition.x ||
                    tilePosition.x == sharedPosition.x && tilePosition.y > sharedPosition.y) continue
                val sharedCenter = strings.projection.project(Vector2(
                    tileMapView.getNeighborTilePositionAsWorldCoords(tileView, shared))
                    .scl(-MapProjection.TILE_RADIUS))
                val endpoint = if (from.dst2(sharedCenter) < to.dst2(sharedCenter)) from else to
                addPortraitLine(endpoint, Vector2(endpoint).mulAdd(sharedCenter, insetRatio),
                    thickness, outlineColor)
            }
        }
    }

    private fun addPortraitLine(from: Vector2, to: Vector2, thickness: Float, color: Color) {
        val delta = Vector2(to).sub(from)
        val line = ImageGetter.getWhiteDot().apply {
            setSize(delta.len(), thickness)
            setOrigin(0f, thickness / 2f)
            setPosition(tileX + tileGroup.groundCenterX + from.x,
                tileY + tileGroup.groundCenterY + from.y - thickness / 2f)
            rotation = atan2(delta.y, delta.x) * 180f / PI.toFloat()
            this.color = color
        }
        addOwnedActor(line)
        portraitImages.add(line)
    }

    override fun doUpdate(viewingCiv: CivView?) {
        if (strings.projection.tilted) updatePortraitBorders() else updateBorders()
    }
}
