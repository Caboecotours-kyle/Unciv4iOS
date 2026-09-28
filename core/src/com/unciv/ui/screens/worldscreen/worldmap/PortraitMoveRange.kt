package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.unciv.logic.map.HexMath
import com.unciv.ui.components.tilegroups.MapProjection
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.components.tilegroups.layers.MoveRoute
import com.unciv.ui.components.tilegroups.layers.RangeStroke
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import com.unciv.view.MapUnitView
import com.unciv.view.TileView

/** A move worked out off the GL thread: [turns] turn ends from [origin], walked tile by tile as [steps]. [jumps] holds
 *  the indexes of steps reached by a turn that could not be retraced, which the route must not join with dots. */
internal class PortraitRoute(val origin: TileView, val turns: Int, val steps: List<TileView>, val jumps: Set<Int>) {
    val destination get() = steps.last()

    companion object {
        /** Expands [turnEnds] as [MapUnitView.getShortestPath] returns them; null when there is no path or nothing to walk */
        fun of(unitView: MapUnitView, turnEnds: List<TileView>): PortraitRoute? {
            if (turnEnds.isEmpty()) return null
            val legs = try { unitView.getStepsAlongPath(turnEnds) } catch (_: Exception) { turnEnds.map { null } }
            val steps = ArrayList<TileView>()
            val jumps = HashSet<Int>()
            for ((turn, leg) in legs.withIndex()) {
                if (leg != null) steps.addAll(leg)
                else {
                    jumps.add(steps.size)
                    steps.add(turnEnds[turn])
                }
            }
            // A path to the unit's own tile walks nowhere
            if (steps.isEmpty()) return null
            return PortraitRoute(unitView.getTile(), turnEnds.size, steps, jumps)
        }
    }
}

/**
 * Portrait move range, direction A of the terrain proposal: the map outside this turn's reach dims, one white line
 * rings the tiles reachable this turn and a dashed one those reachable next turn, the chosen destination gets a yellow
 * hex, a dotted route and a turns badge, and attackable enemies a red hex. Built once per [WorldMapTileUpdater] update.
 */
internal object PortraitMoveRange {
    /** The mock's dim, #10243a at 30% outside the range and 14% on next turn's tiles */
    private val dim = Color(0x10243aff)
    private const val DIM = .3f
    private const val DIM_NEXT_TURN = .14f
    private val attackFill = Color(237 / 255f, 41 / 255f, 57 / 255f, 1f)
    private val destinationFill = RangeStroke.Destination.color

    private val from = Vector2()
    private val to = Vector2()
    private val offset = Vector2()

    fun WorldMapHolder.showPortraitMoveRange(unitView: MapUnitView, reachable: Collection<TileView>, attackable: Set<TileView>) {
        val unitTile = unitView.getTile()
        val thisTurn = HashSet<TileView>(reachable).apply { add(unitTile) }
        // Out of moves: nothing to ring, and the pathfinder has no route either
        if (thisTurn.size > 1) {
            val nextTurn = nextTurnReach(unitView).filterTo(HashSet()) { it !in thisTurn }
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
        showRoute(unitView)
    }

    /** The last unit's next-turn reach and the state it was found in: preview and selection refreshes reuse it */
    private var nextTurnKey = ""
    private var nextTurnTiles: List<TileView> = emptyList()

    /** [MapUnitView.getReachableTilesNextTurn], found again only when something it depends on may have changed: the
     *  unit, its tile, its moves, its turn, where and how far its civ's units stand (their moves also reveal the map
     *  and block tiles) and its diplomatic standing (war, open borders). Other civs only move between turns. */
    private fun WorldMapHolder.nextTurnReach(unitView: MapUnitView): List<TileView> {
        val unit = unitView.getUnit()
        val civ = unit.civ
        var units = 0L
        for (other in civ.units.getCivUnits())
            units = (units * 31 + other.id) * 31 + other.currentTile.zeroBasedIndex * 31L + other.currentMovement.toRawBits()
        var diplomacy = 0L
        for (manager in civ.diplomacy.values)
            diplomacy = diplomacy * 31 + manager.otherCivName.hashCode() * 4L + manager.diplomaticStatus.ordinal * 2L +
                if (manager.hasOpenBorders) 1 else 0
        val key = "${System.identityHashCode(unitView.getTile().getTileMap())} ${civ.gameInfo.turns} ${unit.id} " +
            "${unit.currentTile.zeroBasedIndex} ${unit.currentMovement} ${unit.getMaxMovement()} $units $diplomacy"
        if (key != nextTurnKey) {
            nextTurnTiles = unitView.getReachableTilesNextTurn()
            nextTurnKey = key
        }
        return nextTurnTiles
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

    /** The chosen destination: the route previewed for this unit, or the one it is already ordered along */
    private fun WorldMapHolder.showRoute(unitView: MapUnitView) {
        val preview = portraitRoutes[unitView]
        val route = when {
            preview != null -> preview.takeIf { it.origin == unitView.getTile() }
            unitView.isMoving() -> standingRoute(unitView)
            else -> null
        }
        val destination = route?.destination ?: if (preview == null && unitView.isMoving()) unitView.getMovementDestination() else null
        if (destination == null || destination == unitView.getTile()) return
        portraitRouteTarget = destination
        val overlay = tileGroups[destination]?.layerOverlay ?: return
        overlay.showRangeFill(destinationFill, .18f, .9f)
        for (clock in CLOCK) edge(destination, clock, destination, .9f, RangeStroke.Destination)
        if (route != null) setMoveRoute(route(unitView, route))
    }

    /** The route's points, stepping from the unit's ground centre through relative positions so a wrapped map stays whole */
    private fun WorldMapHolder.route(unitView: MapUnitView, route: PortraitRoute): MoveRoute {
        val unitGroup: WorldTileGroup = tileGroups[unitView.getTile()]!!
        val points = FloatArray(route.steps.size * 2 + 2)
        var previous = route.origin
        val tileMapView = previous.getTileMap()
        for ((i, step) in route.steps.withIndex()) {
            currentTileSetStrings.projection.project(offset.set(tileMapView.getRelativeWorldPosition(previous, step))
                .scl(MapProjection.TILE_RADIUS))
            points[i * 2 + 2] = points[i * 2] + offset.x
            points[i * 2 + 3] = points[i * 2 + 1] + offset.y
            previous = step
        }
        // Bounds around the whole route (and the badge), so the map's culling keeps it while any of it is on screen
        var minX = 0f; var minY = 0f; var maxX = 0f; var maxY = 0f
        for (i in points.indices step 2) {
            minX = minOf(minX, points[i]); maxX = maxOf(maxX, points[i])
            minY = minOf(minY, points[i + 1]); maxY = maxOf(maxY, points[i + 1])
        }
        val pad = MapProjection.TILE_RADIUS
        for (i in points.indices step 2) {
            points[i] -= minX - pad
            points[i + 1] -= minY - pad
        }
        val walked = BooleanArray(route.steps.size) { it !in route.jumps }
        return MoveRoute(unitGroup, points, walked, route.turns).apply {
            setBounds(unitGroup.x + unitGroup.groundCenterX + minX - pad, unitGroup.y + unitGroup.groundCenterY + minY - pad,
                maxX - minX + 2 * pad, maxY - minY + 2 * pad)
        }
    }

    /** A unit ordered somewhere further than this turn: its route is found once per position, off the GL thread */
    private fun WorldMapHolder.standingRoute(unitView: MapUnitView): PortraitRoute? {
        val unit = unitView.getUnit()
        val key = "${unit.id} ${unit.currentTile.position} ${unit.currentMovement} ${unit.action}"
        if (key == standingRouteKey) return standingRoute
        standingRouteKey = key
        standingRoute = null
        val destination = unitView.getMovementDestination()
        Concurrency.run("PortraitRoute") {
            val route = try { PortraitRoute.of(unitView, unitView.getShortestPath(destination)) } catch (_: Exception) { null }
            launchOnGLThread {
                if (standingRouteKey != key) return@launchOnGLThread
                standingRoute = route
                if (route != null) worldScreen.shouldUpdate = true
            }
        }
        return null
    }

    private val CLOCK = intArrayOf(12, 2, 4, 6, 8, 10)
}
