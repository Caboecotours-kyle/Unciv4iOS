package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.map.HexMath
import com.unciv.ui.components.tilegroups.MapProjection
import com.unciv.ui.components.tilegroups.layers.RangeStroke
import com.unciv.view.MapUnitView
import com.unciv.view.TileView

/**
 * Portrait move range, direction A of the terrain proposal: the map outside this turn's reach dims, one white line
 * rings the tiles reachable this turn and a dashed one those reachable next turn, and attackable enemies get a red
 * hex. Built once per [WorldMapTileUpdater] update.
 */
internal object PortraitMoveRange {
    /** The mock's dim, #10243a at 30% outside the range and 14% on next turn's tiles */
    private val dim = Color(0x10243aff)
    private const val DIM = .3f
    private const val DIM_NEXT_TURN = .14f
    private val attackFill = Color(237 / 255f, 41 / 255f, 57 / 255f, 1f)

    private val from = Vector2()
    private val to = Vector2()
    private val offset = Vector2()

    fun WorldMapHolder.showPortraitMoveRange(unitView: MapUnitView, reachable: Collection<TileView>, attackable: Set<TileView>) {
        val unitTile = unitView.getTile()
        val thisTurn = HashSet<TileView>(reachable).apply { add(unitTile) }
        // Out of moves: nothing to ring, and the pathfinder has no route either
        if (thisTurn.size > 1) {
            val nextTurn = unitView.getReachableTilesNextTurn().filterTo(HashSet()) { it !in thisTurn }
            val civ = worldScreen.selectedGameView.civView
            for ((tileView, group) in tileGroups) {
                if (tileView in thisTurn || tileView in attackable) continue
                if (!tileView.isForceVisible() && !civ.hasExplored(tileView)) continue
                val amount = if (tileView in nextTurn) DIM_NEXT_TURN else DIM
                group.layerTerrain.shade(dim, amount)
                group.layerFeatures.shade(dim, amount)
            }
            outline(thisTurn, RangeStroke.ThisTurn, attackable) { true }
            outline(nextTurn, RangeStroke.NextTurn, attackable) { it !in thisTurn }
        }
        showPortraitAttack(attackable)
    }

    /** Enemies the selected unit can attack: a red hex under them, ringed in red; the crosshair stays */
    fun WorldMapHolder.showPortraitAttack(attackable: Set<TileView>) {
        for (tileView in attackable) {
            val overlay = tileGroups[tileView]?.layerOverlay ?: continue
            overlay.showRangeFill(attackFill, .28f)
            for (clock in CLOCK) edge(tileView, clock, tileView, 1f, RangeStroke.Attack)
        }
    }

    /** Rings [area]: every edge from a tile in it to one outside, the outside one passing [outside] */
    private inline fun WorldMapHolder.outline(area: Set<TileView>, stroke: RangeStroke, attackable: Set<TileView>,
                                              outside: (TileView) -> Boolean) {
        val tileMap = tileMap
        for (tileView in area) for (clock in CLOCK) {
            val neighbor = tileMap.getClockPositionNeighborTile(tileView.getTile(), clock)
                ?.let { tileView.getTileMap().getTile(it) }
            if (neighbor != null && (neighbor in area || !outside(neighbor))) continue
            // Each shared edge is drawn once, by the tile drawn later, so props behind it never cover it;
            // an attackable neighbour draws it so its red lies on top
            val drawer = when {
                neighbor == null -> tileView
                neighbor in attackable -> neighbor
                (tileGroups[neighbor]?.y ?: Float.MAX_VALUE) < (tileGroups[tileView]?.y ?: 0f) -> neighbor
                else -> tileView
            }
            edge(tileView, clock, drawer, 1f, stroke)
        }
    }

    /** Adds [tileView]'s edge toward [clock] to [drawer]'s outline, [scale] 1 being the whole hex */
    private fun WorldMapHolder.edge(tileView: TileView, clock: Int, drawer: TileView, scale: Float, stroke: RangeStroke) {
        val overlay = tileGroups[drawer]?.layerOverlay ?: return
        val projection = currentTileSetStrings.projection
        // The clock vector points back from the neighbour; it is shared, so copy before scaling
        val direction = offset.set(HexMath.getClockPositionToWorldVector(clock)).scl(-1f).nor()
        val radius = MapProjection.TILE_RADIUS * scale
        val midX = direction.x * radius * MapProjection.COS_30
        val midY = direction.y * radius * MapProjection.COS_30
        projection.project(from.set(midX - direction.y * radius / 2f, midY + direction.x * radius / 2f))
        projection.project(to.set(midX + direction.y * radius / 2f, midY - direction.x * radius / 2f))
        if (drawer != tileView) {
            // The same edge seen from the neighbour's centre, a lattice step away
            projection.project(direction.scl(MapProjection.TILE_RADIUS * 2f * MapProjection.COS_30))
            from.sub(direction)
            to.sub(direction)
        }
        overlay.addRangeEdge(stroke, from, to)
    }

    private val CLOCK = intArrayOf(12, 2, 4, 6, 8, 10)
}
