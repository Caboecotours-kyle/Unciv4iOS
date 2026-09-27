package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.popups.hasOpenPopups

/**
 * Plays the short reaction of a unit to an order given from the portrait HUD, once the order shows in the game.
 * Orders that first open a confirmation or a picker wait for the first map update after it closes; one that
 * changed nothing (cancelled) is dropped there. Bounded: one order at a time, checked against one unit and tile.
 */
internal class PortraitUnitReactions(private val holder: WorldMapHolder) {
    /** Keeps game values only: the tile groups it was ordered on may be rebuilt (a rotation) before it resolves */
    private class Order(val type: UnitActionType, val unit: MapUnit, val tile: Tile, val strings: TileSetStrings,
                        val sprite: List<SpriteCopy>, val civ: Civilization, val gold: Int, val promotions: Int,
                        val pillage: List<Any?>) {
        /** The picker confirmed the build; also what runs after the hammer lands (the next unit) */
        var accepted = false
        var then: (() -> Unit)? = null
    }

    private var pending: Order? = null
    private var selectedId = -1

    private val builds = setOf(UnitActionType.ConstructImprovement, UnitActionType.Repair)
    private val guards = setOf(UnitActionType.Fortify, UnitActionType.FortifyUntilHealed, UnitActionType.Guard)
    private val sleeps = setOf(UnitActionType.Sleep, UnitActionType.SleepUntilHealed)
    private val upgrades = setOf(UnitActionType.Upgrade, UnitActionType.Transform)
    private val vanishes = setOf(UnitActionType.DisbandUnit, UnitActionType.GiftUnit)
    /** Orders that may use the unit up; it rises in gold only when it really is gone */
    private val expends = setOf(UnitActionType.CreateImprovement, UnitActionType.HurryResearch, UnitActionType.HurryPolicy,
        UnitActionType.HurryWonder, UnitActionType.HurryBuilding, UnitActionType.ConductTradeMission,
        UnitActionType.FoundReligion, UnitActionType.EnhanceReligion, UnitActionType.SpreadReligion,
        UnitActionType.RemoveHeresy, UnitActionType.TriggerUnique, UnitActionType.AddInCapital)
    private val reactive = builds + guards + sleeps + upgrades + vanishes + expends +
        setOf(UnitActionType.Pillage, UnitActionType.Promote)

    private fun tilted() = holder.currentTileSetStrings.projection.tilted
    private fun groupOf(tile: Tile) = holder.tileGroups[holder.worldScreen.selectedGameView.tileMapView.getTile(tile)]
    /** Counts instant promotions (Heal Instantly) too, which are spent rather than kept */
    private fun promotions(unit: MapUnit) = unit.promotions.numberOfPromotions + unit.promotions.promotions.size
    private fun pillageState(tile: Tile) = listOf(tile.improvement, tile.improvementIsPillaged, tile.roadStatus, tile.roadIsPillaged)

    /** Called just before an order runs; returns whether a reaction may follow. */
    fun order(unit: MapUnit, type: UnitActionType): Boolean {
        pending = null
        if (!tilted() || type !in reactive) return false
        val group = groupOf(unit.getTile()) ?: return false
        val slot = holder.slotOf(group, unit.id) ?: return false
        if (!group.layerUnitArt.isVisible || !slot.spriteGroup.isVisible) return false
        val civ = unit.civ
        pending = Order(type, unit, unit.getTile(), holder.currentTileSetStrings, SpriteCopy.of(slot), civ, civ.gold,
            promotions(unit), pillageState(unit.getTile()))
        return true
    }

    /** The improvement picker hook: the build was confirmed; [next] (the next unit) waits for the hammer. */
    fun accepted(unitId: Int, next: () -> Unit) {
        val order = pending?.takeIf { it.unit.id == unitId && it.type in builds }
        if (order == null) return next()
        order.accepted = true
        order.then = next
    }

    /**
     * Plays the reaction once the order shows; returns the unit id whose stand-in plays, or null when there is none
     * (yet). Waits while a confirmation or picker is still open. A deferred next-unit step runs exactly once, at once
     * when nothing plays (landscape, cancelled, nothing changed).
     */
    fun resolve(): Int? {
        val order = pending ?: return null
        val screen = holder.worldScreen
        if (screen.hasOpenPopups() || screen.game.screen !== screen) return null
        pending = null
        val reacting = play(order)
        order.then?.let { next ->
            if (reacting == null) return@let next()
            val table = screen.bottomUnitTable
            val selection = Triple(table.selectedUnit, table.selectedCity, table.selectedSpy)
            holder.motion.afterUnit(reacting) {
                if (Triple(table.selectedUnit, table.selectedCity, table.selectedSpy) == selection) next()
            }
        }
        return reacting
    }

    private fun play(order: Order): Int? {
        // Portrait only, on the map as it is now: after a rotation the stored sprite and tile group are stale
        if (!tilted()) return null
        val group = groupOf(order.tile) ?: return null
        val rebuilt = holder.currentTileSetStrings !== order.strings
        val motion = holder.motion
        val unit = order.unit
        val id = unit.id
        val sprite = if (!rebuilt) order.sprite else holder.slotOf(group, id)?.let { SpriteCopy.of(it) } ?: return null
        val gone = unit.isDestroyed || unit.civ != order.civ
        val clip = when (order.type) {
            in builds -> {
                val tile = order.tile
                val building = !gone && unit.getTile() == tile && tile.improvementInProgress != null &&
                    (order.type == UnitActionType.Repair || order.accepted)
                if (building) motion.BuildClip(id, group, sprite) else null
            }
            in guards -> if (!gone && (unit.isFortified() || unit.isGuarding())) motion.FortifyClip(id, group, sprite) else null
            in sleeps -> if (!gone && unit.isSleeping()) motion.SleepClip(id, group, sprite) else null
            UnitActionType.Pillage -> if (!gone && pillageState(order.tile) != order.pillage)
                motion.PillageClip(id, group, sprite, unit.civ.gold - order.gold) else null
            UnitActionType.Promote -> if (!gone && promotions(unit) > order.promotions)
                motion.PromoteClip(id, group, sprite) else null
            // The new unit keeps the id and takes the tile of the one it replaces
            in upgrades -> if (unit.isDestroyed && order.tile.getUnits().any { it.id == id && it !== unit })
                motion.UpgradeClip(id, group, sprite) { holder.slotOf(group, id)?.let { SpriteCopy.of(it) } } else null
            in vanishes -> if (gone) motion.VanishClip(id, group, sprite) else null
            in expends -> if (unit.isDestroyed) motion.ExpendClip(id, group, sprite) else null
            else -> null
        } ?: return null
        motion.finishUnit(clip.unitId)
        motion.play(clip)
        return clip.unitId
    }

    /** After each tile update: a pending order that took effect reacts, and a sleeping unit just selected snores once. */
    fun observe() {
        resolve()
        val selected = holder.worldScreen.bottomUnitTable.selectedUnit
        val id = selected?.id ?: -1
        if (id == selectedId) return
        selectedId = id
        if (selected == null || !tilted() || !selected.isSleeping()) return
        val group = holder.tileGroups[selected.getTile()] ?: return
        if (group.layerUnitArt.isVisible) holder.motion.snore(group)
    }
}
