package com.unciv.ui.screens.worldscreen.worldmap

import com.unciv.logic.map.HexCoord

internal class SeenUnit(val id: Int, val civID: String, val position: HexCoord, val landUnit: Boolean,
                        val civilian: Boolean, val sprite: List<SpriteCopy>)

/** What the viewing civ could see on the map. Values only, so a snapshot can outlive the screen that took it. */
internal class SeenMap(val gameId: String, val gameIdentity: Int, val civID: String, val turn: Int, val maxUnitId: Int,
                       val units: Map<Int, SeenUnit>, val cities: Map<String, HexCoord>)

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
                !unit.getBaseUnit().isWaterUnit, unit.isCivilian(), SpriteCopy.of(slot))
        }
        if (tile.isCityCenter() && group.layerCityButton.hasButton())
            tile.owningCity()?.let { cities[it.getCity().id] = tile.position() }
    }
    val maxUnitId = gameInfo.civilizations.maxOfOrNull { civ -> civ.units.getCivUnits().maxOfOrNull { it.id } ?: 0 } ?: 0
    return SeenMap(gameInfo.gameId, System.identityHashCode(gameInfo), worldScreen.selectedGameView.civView.getCiv().civID,
        gameInfo.turns, maxUnitId, units, cities)
}
