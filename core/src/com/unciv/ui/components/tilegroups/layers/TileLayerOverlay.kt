package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.view.CivView
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter

class TileLayerOverlay(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    private var highlight: Image? = null // for blue and red circles/emphasis on the tile
    private var crosshair: Image? = null // for when a unit is targeted
    private var goodCityLocationIndicator: Image? = null
    private var fog: Image? = null
    private var unexplored: Image? = null
    /** Portrait move range marks, drawn under this tile's units */
    private var rangeFill: Image? = null
    private var rangeEdges: RangeEdges? = null

    private fun getHighlight() = getGroundImage(strings.highlight).setHexagonSize()
    private fun getCrosshair() = getGroundImage(strings.crosshair).setHexagonSize()
    private fun getGoodCityLocationIndicator() = ImageGetter.getImage("OtherIcons/Cities").setHexagonSize(0.25f)
    private fun getFog() = getGroundImage(strings.crosshatchHexagon).setHexagonSize().apply {
        color = Color.WHITE.cpy().apply { a = 0.2f }
    }
    private fun getUnexplored(): Image {
        val location = strings.getMapImageLocation(strings.unexploredTile)
        return (if (location != strings.unexploredTile) ImageGetter.getImage(location)
            else getGroundImage(location)).setHexagonSize()
    }

    fun showCrosshair(alpha: Float = 1f) {
        if (crosshair == null) {
            crosshair = getCrosshair()
            addOwnedActor(crosshair!!)
            determineVisibility()
        }
        crosshair?.color?.a = alpha
    }

    fun hideCrosshair() {
        if (crosshair == null) return
        removeOwnedActor(crosshair!!)
        crosshair = null
        determineVisibility()
    }

    fun showHighlight(color: Color = Color.WHITE, alpha: Float = 0.3f) {
        if (highlight == null) {
            highlight = getHighlight()
            addOwnedActor(highlight!!)
            determineVisibility()
        }
        highlight?.color = color.cpy().apply { a = alpha }
    }

    fun hideHighlight() {
        if (highlight == null) return
        removeOwnedActor(highlight!!)
        highlight = null
        determineVisibility()
    }

    fun showGoodCityLocationIndicator() {
        if (goodCityLocationIndicator != null) return
        goodCityLocationIndicator = getGoodCityLocationIndicator()
        addOwnedActor(goodCityLocationIndicator!!)
        determineVisibility()
    }

    fun hideGoodCityLocationIndicator() {
        if (goodCityLocationIndicator == null) return
        removeOwnedActor(goodCityLocationIndicator!!)
        goodCityLocationIndicator = null
        determineVisibility()
    }

    /** Portrait: tints the ground hexagon under this tile's units, [scale] 1 being the whole hex */
    fun showRangeFill(color: Color, alpha: Float, scale: Float = 1f) {
        rangeFill?.let { removeOwnedActor(it) }
        rangeFill = rangeFill(color, alpha, scale).also { addOwnedActorBelow(it, tileGroup.layerUnitArt) }
        determineVisibility()
    }

    /** Portrait: adds a move range outline segment, [from] and [to] relative to this tile's ground centre */
    fun addRangeEdge(stroke: RangeStroke, from: Vector2, to: Vector2) {
        val edges = rangeEdges ?: RangeEdges(tileGroup).also {
            it.setPosition(tileX + tileGroup.groundCenterX, tileY + tileGroup.groundCenterY)
            addOwnedActorBelow(it, tileGroup.layerUnitArt)
            rangeEdges = it
            determineVisibility()
        }
        edges.add(stroke, from, to)
    }

    fun hideRange() {
        rangeFill?.let { removeOwnedActor(it) }
        rangeFill = null
        rangeEdges?.let { removeOwnedActor(it) }
        rangeEdges = null
        determineVisibility()
    }

    fun reset() {
        hideRange()
        hideHighlight()
        hideCrosshair()
        hideGoodCityLocationIndicator()
        determineVisibility()
    }

    override fun doUpdate(viewingCiv: CivView?) {
        val isViewable = isViewable(viewingCiv)

        setFog(isViewable)

        if (viewingCiv == null) return

        setUnexplored(viewingCiv)

        val tileView = tileGroup.tileView
        val improvement = tileView.getRuleset().tileImprovements[tileView.getShownImprovement()]
        if (improvement?.isBarbarianCampEquivalent() == true && viewingCiv.hasExplored(tileView))
            showHighlight(Color.RED)
    }

    fun setUnexplored(viewingCiv: CivView) {
        val unexploredShouldBeVisible = !viewingCiv.hasExplored(tileGroup.tileView)
        val unexploredIsVisible = unexplored != null
        if (unexploredIsVisible && !unexploredShouldBeVisible) {
            removeOwnedActor(unexplored!!)
            unexplored = null
            determineVisibility()
        } else if (!unexploredIsVisible && unexploredShouldBeVisible
                && ImageGetter.imageExists(strings.unexploredTile)) {
            unexplored = getUnexplored()
            addOwnedActor(unexplored!!)
            determineVisibility()
        }
    }

    private fun setFog(isViewable: Boolean) {
        val fogShouldBeVisible = !isViewable
        val fogIsVisible = fog != null
        if (fogIsVisible && !fogShouldBeVisible) {
            removeOwnedActor(fog!!)
            fog = null
            determineVisibility()
        } else if (!fogIsVisible && fogShouldBeVisible) {
            fog = getFog()
            addOwnedActor(fog!!)
            determineVisibility()
        }
    }

    override fun determineVisibility() {
        isVisible = fog != null || unexplored != null || highlight != null || crosshair != null || goodCityLocationIndicator != null
            || rangeFill != null || rangeEdges != null
    }

}
