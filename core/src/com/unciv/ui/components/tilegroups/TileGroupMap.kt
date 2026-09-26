package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.utils.viewport.Viewport
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.TileMap
import com.unciv.ui.components.tilegroups.layers.*
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round


/**
 * A (potentially partial) map view
 * @param T [TileGroup] or a subclass ([WorldTileGroup], [CityTileGroup])
 * @param tileGroups Source of [TileGroup]s to include, will be **iterated several times**.
 * @param tileGroupsToUnwrap For these, coordinates will be unwrapped using [TileMap.getUnwrappedPosition]
 */
class TileGroupMap<T: TileGroup>(
    val mapHolder: ZoomableScrollPane,
    tileGroups: Iterable<T>,
    val worldWrap: Boolean = false,
    tileGroupsToUnwrap: Set<T>? = null
): Group() {

    companion object {
        /** Vertical size of a hex in world coordinates, or the distance between the centers of any two opposing edges
         *  (the hex is oriented so it has corners to the left and right of the center and its upper and lower bounds are horizontal edges) */
        const val groupSize = 50f

        /** Length of the diagonal of a hex, or distance between two opposing corners */
        const val groupSizeDiagonal = groupSize * 1.1547005f  // groupSize * sqrt(4/3)

        /** Horizontal displacement per hex, meaning the increase in overall map size (in world coordinates) when adding a column.
         *  On the hex, this can be visualized as the horizontal distance between the leftmost corner and the
         *  line connecting the two corners at 2 and 4 o'clock. */
        const val groupHorizontalAdvance = groupSizeDiagonal * 3 / 4
        //TODO magic numbers that **seem** like they might depend on these values can be found in
        //   TileGroupMap.getPositionalVector, TileGroup.updateArrows, TileGroup.updateRoadImages
        //   and other places. I can't understand them so I'm leaving cleanup of hardcoding to someone else.
    }

    /** If the [act] method should be performed. If this is false, every child within this [TileGroupMap] will not get their [act] method called
     * and thus not perform any [com.badlogic.gdx.scenes.scene2d.Action]s.
     * Most children here already do not do anything in their [act] methods. However, even iterating through all of them */
    var shouldAct = true
    var shouldHit = true

    private var topX = -Float.MAX_VALUE
    private var topY = -Float.MAX_VALUE
    private var bottomX = Float.MAX_VALUE
    private var bottomY = Float.MAX_VALUE

    private var drawTopX = 0f
    private var drawBottomX = 0f

    private var maxVisibleMapWidth = 0f

    /** All top-level layer container groups in render order (bottom to top).
     *  Used by the world-wrap draw path to reposition tile-level actors. */
    private val allMapLayers: List<Group>
    private val groundHitLayer: Group
    private val hitPoint = Vector2()

    /** Cached expanded rectangle used to avoid per-frame allocation when culling borders/city buttons. */
    private val expandedCullingArea = Rectangle()

    /** TileGroups in the same sorted order used for allMapLayers registration,
     *  so world-wrap can reposition the click-target by index. */
    private val sortedTileGroups: List<T>
    val mapVerticalScale = tileGroups.firstOrNull()?.mapVerticalScale ?: 1f
    val projection = MapProjection(mapVerticalScale)
    private val groundCenter = Vector2(tileGroups.firstOrNull()?.groundCenterX ?: groupSize / 2f,
        tileGroups.firstOrNull()?.groundCenterY ?: groupSize / 2f)
    private val flatMinimum = Vector2(Float.MAX_VALUE, Float.MAX_VALUE)
    private val flatMaximum = Vector2(-Float.MAX_VALUE, -Float.MAX_VALUE)
    val flatWidth get() = flatMaximum.x - flatMinimum.x
    val flatHeight get() = flatMaximum.y - flatMinimum.y
    val wrapVector get() = projection.project(Vector2(flatWidth, 0f))
    private val originalFlatXs: FloatArray
    private val wrapOffsets: IntArray
    private var lastWrapCameraX = Float.NaN
    private lateinit var boardLayer: Group
    private val tiltedRows = ArrayList<Group>()
    private val registeredMapLayers: List<TileMapLayer<*>>

    init {

        for (tileGroup in tileGroups) {
            val positionalVector = if (tileGroupsToUnwrap?.contains(tileGroup) == true) {
                HexMath.hex2WorldCoords(
                    tileGroup.tileView.getTile().tileMap.getUnwrappedPosition(tileGroup.tileView.position())
                )
            } else {
                HexMath.hex2WorldCoords(tileGroup.tileView.position())
            }

            positionalVector.scl(MapProjection.TILE_RADIUS)
            flatMinimum.x = min(flatMinimum.x, positionalVector.x)
            flatMinimum.y = min(flatMinimum.y, positionalVector.y)
            flatMaximum.x = max(flatMaximum.x, positionalVector.x + if (worldWrap) 60f else 54f)
            flatMaximum.y = max(flatMaximum.y, positionalVector.y + groupSize)
            projection.project(positionalVector)
            tileGroup.setPosition(positionalVector.x, positionalVector.y)

            topX =
                    if (worldWrap)
                    // Well it's not pretty but it works
                    // The resulting topX was always missing 1.2 * groupSize in every possible
                    // combination of map size and shape
                        max(topX, tileGroup.x + groupSize * 1.2f)
                    else
                        max(topX, tileGroup.x + groupSize + 4f)

            topY = max(topY, tileGroup.y + groupSize)
            bottomX = min(bottomX, tileGroup.x)
            bottomY = min(bottomY, tileGroup.y)
        }

        for (group in tileGroups) {
            group.moveBy(-bottomX, -bottomY)
        }

        drawTopX = topX - bottomX
        drawBottomX = bottomX - bottomX

        val numberOfTilegroups = tileGroups.count()

        val terrainMapLayer     = TerrainMapLayer(numberOfTilegroups)
        val featureMapLayer     = FeaturesMapLayer(numberOfTilegroups)
        val borderMapLayer      = BordersMapLayer(numberOfTilegroups)
        val resourceMapLayer    = ResourceMapLayer(numberOfTilegroups, actable = true)
        val improvementMapLayer = ImprovementMapLayer(numberOfTilegroups, actable = true)
        // TileLayerMisc.workedIcon may receive touches, so the container must forward them
        val miscMapLayer        = MiscMapLayer(numberOfTilegroups, touchable = true)
        val yieldMapLayer       = YieldMapLayer(numberOfTilegroups)
        val unitSpriteMapLayer  = UnitSpriteMapLayer(numberOfTilegroups)
        val overlayMapLayer     = OverlayMapLayer(numberOfTilegroups)
        // TileGroups themselves provide click detection; not TileLayer subclasses so plain Group
        val tileGroupLayer      = object: Group(){
            init {
                isTransform = false
                touchable = Touchable.childrenOnly
                children.ensureCapacity(numberOfTilegroups)
            }

            // only exists to register clicks, so no act or render required
            override fun act(delta: Float) {}
            override fun draw(batch: Batch, parentAlpha: Float) {}
        }
        groundHitLayer = tileGroupLayer
        val unitFlagMapLayer    = UnitFlagMapLayer(numberOfTilegroups, actable = true)
        // CityButton wrapper Groups are Touchable.childrenOnly, so the container must forward touches
        val cityButtonMapLayer  = CityButtonMapLayer(numberOfTilegroups, actable = true, touchable = true)

        // Upright art behind a nearer row must be drawn first.
        val sortedGroups = tileGroups.sortedByDescending { it.y }

        boardLayer = object : Group() {
            init { isTransform = false; touchable = Touchable.disabled }
            override fun act(delta: Float) {}
        }
        for (group in sortedGroups) {
            // Only tilted maps interleave terrain and units. Non-transform groups preserve batching.
            val row = if (mapVerticalScale != 1f) Group().apply {
                isTransform = false
                touchable = Touchable.disabled
                userObject = group
                boardLayer.addActor(this)
                tiltedRows.add(this)
            } else null
            // Register each layer with its tile's absolute position; images are flushed
            // from each layer's internal buffer into the shared TileMapLayer.
            terrainMapLayer.add(group.layerTerrain, group.x, group.y, row ?: terrainMapLayer)
            featureMapLayer.add(group.layerFeatures, group.x, group.y, row ?: featureMapLayer)
            borderMapLayer.add(group.layerBorders, group.x, group.y, row ?: borderMapLayer)
            resourceMapLayer.add(group.layerResource, group.x, group.y)
            improvementMapLayer.add(group.layerImprovement, group.x, group.y)
            miscMapLayer.add(group.layerMisc, group.x, group.y)
            yieldMapLayer.add(group.layerYield, group.x, group.y)
            unitSpriteMapLayer.add(group.layerUnitArt, group.x, group.y, row ?: unitSpriteMapLayer)
            overlayMapLayer.add(group.layerOverlay, group.x, group.y, row ?: overlayMapLayer)
            unitFlagMapLayer.add(group.layerUnitFlag, group.x, group.y)
            cityButtonMapLayer.add(group.layerCityButton, group.x, group.y)
        }

        sortedTileGroups = sortedGroups
        originalFlatXs = FloatArray(sortedGroups.size) { index ->
            val group = sortedGroups[index]
            toFlat(Vector2(group.x, group.y).add(groundCenter)).x
        }
        wrapOffsets = IntArray(sortedGroups.size)

        for (group in tileGroups) tileGroupLayer.addActor(group)

        allMapLayers = (if (mapVerticalScale != 1f) listOf(boardLayer) else emptyList()) + listOf(
            terrainMapLayer,
            featureMapLayer,
            borderMapLayer,
            resourceMapLayer,
            improvementMapLayer,
            tileGroupLayer,       // TileGroups for click detection; kept below miscMapLayer so
                                  // miscMapLayer is hit-tested first (city-screen workedIcon clicks)
            miscMapLayer,
            yieldMapLayer,
            unitSpriteMapLayer,
            overlayMapLayer,
            unitFlagMapLayer,
            cityButtonMapLayer
        )

        registeredMapLayers = allMapLayers.filterIsInstance<TileMapLayer<*>>()

        children.ensureCapacity(allMapLayers.size)
        for (mapLayer in allMapLayers) addActor(mapLayer)

        val mapWidth = topX - bottomX
        val mapHeight = topY - bottomY

        // Each container group must cover the full map so TileGroupMap's culling check always
        // passes for it (actual per-tile culling is done inside each container via its own
        // cullingArea, which is propagated from TileGroupMap's cullingArea in draw()).
        for (mapLayer in allMapLayers) mapLayer.setSize(mapWidth, mapHeight)
        for (row in tiltedRows) row.setSize(mapWidth, mapHeight)

        // there are tiles "below the zero",
        // so we zero out the starting position of the whole board so they will be displayed as well
        setSize(mapWidth, mapHeight)

        cullingArea = Rectangle(0f, 0f, mapWidth, mapHeight)

        maxVisibleMapWidth = mapWidth - groupSize * 1.5f
    }

    /**
     * Returns the positional coordinates of the TileGroupMap center.
     */
    fun getPositionalVector(stageCoords: Vector2): Vector2 {
        if (!projection.tilted) return Vector2(bottomX, bottomY).add(stageCoords)
            .sub(groupSize / 2f, groupSize / 2f).scl(1f / MapProjection.TILE_RADIUS)
        return projection.inverse(Vector2(stageCoords).sub(groundCenter).add(bottomX, bottomY))
            .scl(1f / MapProjection.TILE_RADIUS)
    }

    /** Coordinates in the original flat map, including its original origin. */
    fun toFlat(point: Vector2): Vector2 = projection.inverse(point.sub(groundCenter).add(bottomX, bottomY))
        .sub(flatMinimum).add(groundCenter)

    fun fromFlat(point: Vector2): Vector2 = projection.project(point.sub(groundCenter).add(flatMinimum))
        .sub(bottomX, bottomY).add(groundCenter)

    fun flatViewport(viewport: Rectangle): Rectangle {
        val corners = arrayOf(Vector2(viewport.x, viewport.y), Vector2(viewport.x + viewport.width, viewport.y),
            Vector2(viewport.x, viewport.y + viewport.height), Vector2(viewport.x + viewport.width, viewport.y + viewport.height))
        corners.forEach { toFlat(it) }
        val left = corners.minOf { it.x }
        val bottom = corners.minOf { it.y }
        return Rectangle(left, bottom, corners.maxOf { it.x } - left, corners.maxOf { it.y } - bottom)
    }

    /** Raw Scene2D scale for one mock map unit per logical point at normalized zoom 1. */
    fun portraitZoomScale(viewport: Viewport): Float =
        MapProjection.PORTRAIT_RADIUS / MapProjection.TILE_RADIUS * portraitUnitsPerPoint(viewport)

    fun portraitUnitsPerPoint(viewport: Viewport): Float =
        ((viewport as? SafeAreaViewport)?.safeAreaBoundsInWorld?.width ?: viewport.worldWidth) / 393f

    fun getDefaultZoom(viewport: Viewport): Float =
        if (!projection.tilted) 1f else MapProjection.DEFAULT_ZOOM * portraitZoomScale(viewport)

    /** Move every layer of a tile to the same periodic copy, including its ground hit target. */
    fun updateWrappedPositions() {
        if (!worldWrap || !projection.tilted) return
        val camera = toFlat(Vector2(mapHolder.scrollX, mapHolder.maxY - mapHolder.scrollY))
        if (camera.x == lastWrapCameraX) return
        lastWrapCameraX = camera.x
        val wrap = wrapVector
        var changed = false
        for ((i, group) in sortedTileGroups.withIndex()) {
            val copies = round((camera.x - originalFlatXs[i]) / flatWidth).toInt()
            val previous = wrapOffsets[i]
            if (copies == previous) continue
            wrapOffsets[i] = copies
            val dx = (copies - previous) * wrap.x
            val dy = (copies - previous) * wrap.y
            for (layer in registeredMapLayers) {
                val tileLayer = layer.tileLayers[i]
                tileLayer.tileX += dx
                tileLayer.tileY += dy
                tileLayer.forEachOwnedActor { it.moveBy(dx, dy) }
            }
            group.moveBy(dx, dy)
            changed = true
        }
        for (child in children) {
            if (child in allMapLayers) continue
            val flatX = toFlat(Vector2(child.x, child.y).add(groundCenter)).x
            val copies = round((camera.x - flatX) / flatWidth)
            if (copies != 0f) child.moveBy(copies * wrap.x, copies * wrap.y)
        }
        if (changed) boardLayer.children.sort { a, b ->
            (b.userObject as TileGroup).y.compareTo((a.userObject as TileGroup).y)
        }
    }

    override fun act(delta: Float) {
        if (shouldAct)
            super.act(delta)
    }

    /** An overview can keep its own direct-child city markers interactive without game actions. */
    fun disableGameplayInput() {
        for (layer in allMapLayers) layer.touchable = Touchable.disabled
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? {
        if (!shouldHit || !isVisible || touchable && this.touchable == Touchable.disabled) return null
        if (!projection.tilted || sortedTileGroups.firstOrNull() !is WorldTileGroup)
            return super.hit(x, y, touchable)
        // Explicit overlays and city banners receive taps before the projected ground.
        for (index in children.size - 1 downTo 0) {
            val child = children[index]
            if (child in allMapLayers && child !is CityButtonMapLayer) continue
            child.parentToLocalCoordinates(hitPoint.set(x, y))
            child.hit(hitPoint.x, hitPoint.y, touchable)?.let { return it }
        }
        return groundHitLayer.hit(x, y, touchable)
    }

    override fun draw(batch: Batch?, parentAlpha: Float) {
        // Propagate the viewport culling area into each layer container so that individual tile
        // actors are culled correctly. Container groups span the full map so they are never
        // culled at the TileGroupMap level; the real per-tile work happens inside each container.
        val ca = cullingArea
        // Border actors are rotated images whose pre-rotation bounding boxes sit at the south
        // edge of the hexagon. After 180° rotation the visual content moves to the north edge,
        // up to ~groupSize units above the pre-rotation box top. Without expansion, tiles whose
        // center is just south of the viewport get their border actors incorrectly culled.
        // City-button wrappers have size 0×0, so any button that extends above a tile just south
        // of the viewport is also culled too aggressively — expand for those as well.
        val expand = groupSize * 1.5f
        expandedCullingArea.set(ca.x - expand, ca.y - expand, ca.width + expand * 2, ca.height + expand * 2)
        var rowIndex = 0
        while (rowIndex < tiltedRows.size) tiltedRows[rowIndex++].cullingArea = expandedCullingArea
        for (mapLayer in allMapLayers) {
            // Generic type parameters are erased at runtime, so we check the element type instead.
            // Borders use rotated images (visual content displaced from pre-rotation bounds) and
            // city-button wrappers have size 0×0 — both need an expanded culling area so they
            // aren't incorrectly culled when a tile center is just south of the viewport.
            val firstLayer = (mapLayer as? TileMapLayer<*>)?.tileLayers?.firstOrNull()
            val culling = if (firstLayer is TileLayerBorders || firstLayer is TileLayerCityButton) expandedCullingArea else ca
            mapLayer.cullingArea = culling
        }

        updateWrappedPositions()
        if (worldWrap && !projection.tilted) {
            // Prevent flickering when zoomed out so you can see entire map
            val visibleMapWidth =
                    if (mapHolder.width > maxVisibleMapWidth) maxVisibleMapWidth
                    else mapHolder.width

            // Where is viewport's boundaries
            val rightSide = mapHolder.scrollX + visibleMapWidth / 2f
            val leftSide = mapHolder.scrollX - visibleMapWidth / 2f

            // Have we looked beyond map?
            val diffRight = rightSide - drawTopX
            val diffLeft = leftSide - drawBottomX

            val beyondRight = diffRight >= 0f
            val beyondLeft = diffLeft <= 0f

            if (beyondRight || beyondLeft) {

                // If we looked beyond - reposition needed tiles from the other side
                // and update topX and bottomX accordingly.

                var newBottomX = Float.MAX_VALUE
                var newTopX = -Float.MAX_VALUE

                // Reposition tile-level actors via tileLayers (each TileLayer owns its actors).
                // Bounds are tracked via the reference layer's tileX values.
                val referenceMapLayer = registeredMapLayers.first()

                for ((i, referenceTileLayer) in referenceMapLayer.tileLayers.withIndex()) {
                    val shouldMove = when {
                        beyondRight -> referenceTileLayer.tileX - drawBottomX <= diffRight
                        beyondLeft  -> referenceTileLayer.tileX + groupSize + 4f >= drawTopX + diffLeft
                        else        -> false
                    }
                    if (shouldMove) {
                        val dx = if (beyondRight) width else -width
                        for (mapLayer in registeredMapLayers) {
                            val tl = mapLayer.tileLayers[i]
                            tl.tileX += dx
                            tl.forEachOwnedActor { it.x += dx }
                        }
                        sortedTileGroups[i].x += dx
                    }
                    newBottomX = min(newBottomX, referenceTileLayer.tileX)
                    newTopX    = max(newTopX,    referenceTileLayer.tileX + groupSize + 4f)
                }

                // Also reposition any actors added directly to this group (e.g. transient overlays).
                for (child in children) {
                    if (child !in allMapLayers) {
                        if (beyondRight) {
                            if (child.x - drawBottomX <= diffRight)
                                child.x += width
                        } else if (beyondLeft) {
                            if (child.x + groupSize + 4f >= drawTopX + diffLeft)
                                child.x -= width
                        }
                    }
                }

                drawBottomX = newBottomX
                drawTopX = newTopX
            }
        }
        super.draw(batch, parentAlpha)
    }
}
