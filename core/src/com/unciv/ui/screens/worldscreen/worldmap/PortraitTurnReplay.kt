package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.WorldTileGroup
import kotlin.math.max
import kotlin.math.min

internal class SeenUnit(val id: Int, val civID: String, val position: HexCoord, val landUnit: Boolean,
                        val civilian: Boolean, val sprite: List<SpriteCopy>, val health: Int = 100, val baseUnit: String = "")

/** What the viewing civ could see on the map. Values only, so a snapshot can outlive the screen that took it. */
internal class SeenMap(val gameId: String, val gameIdentity: Int, val civID: String, val turn: Int, val maxUnitId: Int,
                       val units: Map<Int, SeenUnit>, val cities: Map<String, HexCoord>)

/** Carries the map seen just before a turn ends to the world screen that is built after it. */
internal object PendingTurnReplay {
    @Volatile var seen: SeenMap? = null
}

/** Units are read from their sprite slots, so exactly what is drawn counts as seen. */
internal fun WorldMapHolder.seeMap(): SeenMap {
    val gameInfo = worldScreen.gameInfo
    val units = HashMap<Int, SeenUnit>()
    val cities = HashMap<String, HexCoord>()
    for (group in tileGroups.values) {
        val tile = group.tileView
        for (unit in listOfNotNull(tile.militaryUnit, tile.civilianUnit)) {
            val slot = group.layerUnitArt.getSpriteSlot(unit)?.takeIf { it.unitId == unit.id } ?: continue
            units[unit.id] = SeenUnit(unit.id, unit.civ().civID, tile.position(),
                !unit.getBaseUnit().isWaterUnit, unit.isCivilian(), SpriteCopy.of(slot), unit.unitHealth, unit.getBaseUnit().name)
        }
        if (tile.isCityCenter() && group.layerCityButton.hasButton())
            tile.owningCity()?.let { cities[it.getCity().id] = tile.position() }
    }
    val maxUnitId = gameInfo.civilizations.maxOfOrNull { civ -> civ.units.getCivUnits().maxOfOrNull { it.id } ?: 0 } ?: 0
    return SeenMap(gameInfo.gameId, System.identityHashCode(gameInfo), worldScreen.selectedGameView.civView.getCiv().civID,
        gameInfo.turns, maxUnitId, units, cities)
}

/** Civs in the order they move, starting with [viewingCiv]. */
internal fun turnOrder(gameInfo: GameInfo, viewingCiv: Civilization): List<Civilization> {
    val all = gameInfo.civilizations
    val start = all.indexOf(viewingCiv).coerceAtLeast(0)
    return all.drop(start) + all.take(start)
}

/** Turn progress counts city steps in turn order, so the civ whose cities are being processed is a fair guess of who moves. */
internal fun estimateMovingCiv(order: List<Civilization>, fraction: Float): Civilization? {
    val others = order.drop(1).filter { it.isAlive() && !it.isSpectator() }
    if (others.isEmpty()) return order.firstOrNull()
    val totalCities = order.sumOf { it.cities.size }
    var step = fraction * (3 + totalCities * 2) - 1 - order.first().cities.size
    for (civ in others) {
        step -= civ.cities.size * 2
        if (step < 0f) return civ
    }
    return others.last()
}

/**
 * Plays back, civ by civ in turn order, what visibly changed while the turn passed: hops, spawns, founded cities
 * and defeated units. Bounded to 8 animated units and 1.6 s per civ and 6 s overall; a tap skips to the end.
 */
internal class TurnReplay(
    private val holder: WorldMapHolder,
    private val before: SeenMap,
    private val after: SeenMap,
    private val viewingCiv: Civilization,
    private val onFinished: (showToast: Boolean) -> Unit,
    /** Only the viewing civ's own units, with no turn HUD: automated moves played on the same screen */
    val ownMovesOnly: Boolean = false,
) {
    var currentCiv: Civilization? = null
        private set
    private var skipping = false
    private var showToast = true
    private var cameraMoved = false
    private val scrollX = holder.scrollX
    private val scrollY = holder.scrollY
    private val motion = holder.motion
    private val gameInfo = holder.worldScreen.gameInfo
    private val groups = holder.tileGroups.values.associateBy { it.tileView.position() }
    private val existingIds = gameInfo.civilizations.flatMap { civ -> civ.units.getCivUnits().map { it.id } }.toHashSet()

    private class Track(val civID: String, val priority: Int, val tile: TileGroup, val natural: Float,
                        val play: (delay: Float, speed: Float) -> Unit)

    fun start() {
        currentCiv = viewingCiv
        val byCiv = buildTracks().groupBy { it.civID }
        val plan = turnOrder(gameInfo, viewingCiv).mapNotNull { civ ->
            byCiv[civ.civID]?.sortedWith(compareBy<Track> { it.priority }.thenBy { !holder.isInView(it.tile) })
                ?.take(8)?.let { civ to it }
        }.filter { !ownMovesOnly || it.first == viewingCiv }
        val glides = plan.map { (_, tracks) -> if (tracks.none { holder.isInView(it.tile) }) .35f else 0f }
        val durations = plan.map { (_, tracks) -> min(1.6f, tracks.maxOf { it.natural }) }
        val lead = .3f
        val gap = .15f
        val total = lead + glides.sum() + durations.sum() + gap * plan.size
        val squeeze = max(1f, total / 6f)
        var cursor = lead / squeeze
        for ((index, entry) in plan.withIndex()) {
            val (civ, tracks) = entry
            val glide = glides[index] / squeeze
            motion.play(motion.Cue(cursor) {
                currentCiv = civ
                if (glide > 0f && !skipping && tracks.none { holder.isInView(it.tile) }) {
                    val x = tracks.map { it.tile.x + it.tile.groundCenterX }.average().toFloat()
                    val y = tracks.map { it.tile.y + it.tile.groundCenterY }.average().toFloat()
                    if (holder.glideTo(x, y, glide) > 0f) cameraMoved = true
                }
            })
            cursor += glide
            for (track in tracks) track.play(cursor, max(1f, track.natural / 1.6f) * squeeze)
            cursor += (durations[index] + gap) / squeeze
        }
        motion.play(motion.Cue(cursor) { finish() })
    }

    /** A tap on the map or the Next disc lands everything on its end state at once. */
    fun skip() {
        skipping = true
        motion.finishAll()
    }

    /** The screen is going away: end silently. */
    fun stop() {
        skipping = true
        showToast = false
        cameraMoved = false
    }

    private fun finish() {
        currentCiv = null
        if (cameraMoved) holder.scrollTo(scrollX, scrollY, immediately = skipping || UncivGame.Current.settings.reduceMotion)
        if (holder.turnReplay === this) holder.turnReplay = null
        onFinished(showToast)
    }

    private fun buildTracks(): List<Track> {
        val tracks = ArrayList<Track>()
        val civView = holder.worldScreen.selectedGameView.civView
        val newCities = after.cities.filterKeys { it !in before.cities }
        fun replaced(civID: String, position: HexCoord, isNew: (Int) -> Boolean, units: Collection<SeenUnit>) =
            units.any { it.civID == civID && it.position == position && isNew(it.id) }

        for (old in before.units.values) {
            val from = groups[old.position] ?: continue
            val now = after.units[old.id]
            when {
                now != null && now.position != old.position -> {
                    val to = groups[now.position] ?: continue
                    val path = findPath(old, from, to)
                    if (path != null) tracks.add(Track(old.civID, 3, to, (path.size - 1) * .34f) { delay, speed ->
                        motion.play(motion.HopClip(delay, speed, old.id, path, old.sprite, old.landUnit, { now.sprite }, .34f))
                    }) else tracks.add(Track(old.civID, 3, to, .5f) { delay, speed ->
                        motion.play(motion.FadeOutClip(delay, speed, from, old.sprite))
                        motion.play(motion.SpawnClip(delay + .25f / speed, speed, old.id, to, now.sprite, ring = false))
                    })
                }
                // Own units that healed where they stood: green pluses and "+N"
                now != null -> if (now.health > old.health && old.civID == viewingCiv.civID && from.isViewable(civView))
                    tracks.add(Track(old.civID, 4, from, 1.5f) { delay, speed ->
                        motion.play(motion.HealClip(delay, speed, from, now.health - old.health))
                    })
                old.id in existingIds -> tracks.add(Track(old.civID, 3, from, .25f) { delay, speed ->
                    motion.play(motion.FadeOutClip(delay, speed, from, old.sprite))
                })
                // A settler that founded a city sinks as part of the settle
                old.civilian && newCities.containsValue(old.position) -> Unit
                replaced(old.civID, old.position, { it > before.maxUnitId }, after.units.values) -> Unit
                !from.isViewable(civView) -> Unit
                else -> {
                    // Played with the civ whose unit now stands nearest, most likely the attacker
                    val attacker = after.units.values.filter { it.civID != old.civID }
                        .mapNotNull { unit -> groups[unit.position]?.let { unit to it.tileView.aerialDistanceTo(from.tileView) } }
                        .filter { it.second <= 2 }.minByOrNull { it.second }?.first?.civID
                    tracks.add(Track(attacker ?: old.civID, 1, from, .94f) { delay, speed ->
                        motion.play(motion.DefeatClip(delay, speed, from, old.sprite))
                    })
                }
            }
        }
        for (now in after.units.values) {
            if (now.id in before.units) continue
            val tile = groups[now.position] ?: continue
            val isNew = now.id > before.maxUnitId
            if (isNew && replaced(now.civID, now.position, { it !in existingIds }, before.units.values)) continue
            val ring = isNew && tile.tileView.isCityCenter()
            tracks.add(Track(now.civID, 2, tile, if (ring) .42f else .3f) { delay, speed ->
                motion.play(motion.SpawnClip(delay, speed, now.id, tile, now.sprite, ring))
            })
        }
        for ((id, position) in newCities) {
            val city = gameInfo.getCities().firstOrNull { it.id == id } ?: continue
            if (city.turnAcquired < before.turn) continue
            val center = groups[position] ?: continue
            val territory = city.getTiles().mapNotNull { groups[it.position] }.toList()
            val settler = before.units.values.firstOrNull {
                it.position == position && it.civilian && it.civID == city.civ.civID && it.id !in after.units
            }?.sprite
            tracks.add(Track(city.civ.civID, 0, center, 2.05f) { delay, speed ->
                motion.play(motion.SettleClip(delay, speed, center, territory, settler))
            })
        }
        return tracks
    }

    /** Breadth-first over explored tiles, at most 8 steps. Land units cross between land and water at most once. */
    private fun findPath(unit: SeenUnit, from: WorldTileGroup, to: WorldTileGroup): List<TileGroup>? {
        fun water(group: WorldTileGroup) = !group.tileView.isLand
        fun passable(group: WorldTileGroup): Boolean {
            val tile = group.tileView
            if (!tile.isExplored() || tile.isImpassible()) return false
            return unit.landUnit || water(group) || tile.isCityCenter()
        }
        val crossing = unit.landUnit && water(from) != water(to)
        val previous = HashMap<WorldTileGroup, WorldTileGroup>()
        var frontier = listOf(from)
        repeat(8) {
            val next = ArrayList<WorldTileGroup>()
            for (group in frontier) for (neighbor in group.tileView.getVisibleNeighbors()) {
                val candidate = groups[neighbor.position()] ?: continue
                if (candidate == from || candidate in previous || !passable(candidate)) continue
                if (unit.landUnit && water(candidate) != water(group) && (!crossing || water(group) == water(to))) continue
                previous[candidate] = group
                if (candidate == to) return generateSequence(to) { previous[it] }.toList().reversed()
                next.add(candidate)
            }
            frontier = next
        }
        return null
    }
}
