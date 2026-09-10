package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Building
import com.unciv.utils.Log
import java.io.IOException

/** Direct source hooks. Awards are persisted only after the enclosing operation settles. */
object AchievementTracker {
    var service: AchievementService? = null

    fun beginAction(game: GameInfo) { game.achievements?.let { it.actionDepth++ } }
    fun endAction(game: GameInfo, successful: Boolean = true) {
        game.achievements?.let { it.actionDepth-- }
        if (successful) settle(game)
    }

    inline fun <T> action(game: GameInfo, block: () -> T): T {
        beginAction(game)
        var successful = false
        try {
            val result = block()
            successful = true
            return result
        } finally { endAction(game, successful) }
    }

    fun settle(game: GameInfo, atTurnEnd: Boolean = false, winner: Civilization? = null, victoryRoute: String? = null) {
        val state = game.achievements ?: return
        if (state.ended || !state.initializationComplete || state.actionDepth != 0 ||
            state.recordingVersion != AchievementCatalog.firstCompleteRecordingVersion) return
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return
        if (game.gameParameters.godMode || game.simulateUntilWin) {
            disqualify(game)
            return
        }
        settleCaptures(game)
        val actualWinner = winner ?: game.victoryData?.let { victory -> game.civilizations.firstOrNull { it.civID == victory.winningCiv } }
        val actualRoute = victoryRoute ?: game.victoryData?.victoryType
        val terminal = actualWinner != null || player.isDefeated()
        val ids = AchievementRules.evaluate(game, atTurnEnd || terminal, actualWinner, actualRoute)
        state.history.completed.addAll(ids)
        try {
            service?.record(game, ids, actualRoute.takeIf { actualWinner == player })
        } catch (error: IOException) {
            // Keep branch facts for retry; a disk failure must not interrupt a game or report an uncommitted award.
            Log.error("Could not persist achievements; will retry after the next game action", error)
            return
        }
        if (terminal) state.ended = true
    }

    fun disqualify(game: GameInfo) {
        if (game.achievements?.ended != false) return
        game.achievements?.ended = true
        try {
            service?.disqualify(game)
        } catch (error: IOException) {
            Log.error("Could not persist achievement disqualification; this session remains ineligible", error)
        }
    }

    private fun state(civ: Civilization): AchievementGameState? = civ.gameInfo.achievements?.takeIf {
        !it.ended && it.playerId == civ.civID && it.recordingVersion == AchievementCatalog.recordingVersion
    }

    fun startTurn(civ: Civilization) { state(civ)?.history?.startTurn(civ.gameInfo.turns) }

    /** Event facts are kept on the save branch, including when a profile write must be retried. */
    fun eventCompleted(civ: Civilization, id: String) {
        state(civ)?.history?.completed?.add(id)
        settle(civ.gameInfo)
    }

    fun cityKey(city: City): String = city.civ.gameInfo.achievements?.history?.cityKeys
        ?.get(AchievementGameState.tileKey(city.getCenterTile())).orEmpty()

    fun cityFounded(city: City) {
        val state = city.civ.gameInfo.achievements ?: return
        if (state.ended || state.recordingVersion != AchievementCatalog.recordingVersion) return
        val history = state.history
        val key = (++history.nextCitySerial).toString()
        history.cityKeys[AchievementGameState.tileKey(city.getCenterTile())] = key
        if (city.civ.civID == state.playerId) history.foundedCities.add(key)
        cityAcquired(city)
    }

    fun cityAcquired(city: City) {
        val history = state(city.civ)?.history ?: return
        history.maximumCities = maxOf(history.maximumCities, city.civ.cities.size)
        if (city.cityConstructions.getBuiltBuildings().any { it.isWonder })
            history.restrictions.add(AchievementRules.worldWonder)
    }

    fun buildingOwned(city: City, building: Building) {
        if (building.isWonder) state(city.civ)?.history?.restrictions?.add(AchievementRules.worldWonder)
    }

    fun buildingProduced(city: City, building: Building) {
        if (building.isWonder) state(city.civ)?.history?.builtWonders?.put(building.name, cityKey(city))
    }

    fun improvementBuilt(tile: Tile, unit: MapUnit?) {
        if (!tile.tileMap.hasGameInfo() || tile.tileMap[tile.position] !== tile || unit == null) return
        if (unit.name == "Worker" && tile.improvement in setOf("Farm", "Mine", "Pasture"))
            eventCompleted(unit.civ, "N09")
    }

    fun promotionEarned(unit: MapUnit) {
        state(unit.civ)?.history?.unit(unit.id)?.let { it.earnedPromotions++ }
    }

    fun killedMilitaryUnit(killer: MapUnit, victimId: Int, victimWasMajorMilitary: Boolean,
                           wasAttacker: Boolean) {
        val history = state(killer.civ)?.history ?: return
        history.completed.add("N06")
        if (!victimWasMajorMilitary || !wasAttacker || !killer.civ.isCurrentPlayer() || killer.name != "Chu-Ko-Nu") return
        val unit = history.unit(killer.id)
        unit.chuKoNuKillsThisTurn.add(victimId.toString())
        if (unit.chuKoNuKillsThisTurn.size >= 2) history.completed.add("N34")
    }

    fun tradeCompleted(first: Civilization, second: Civilization, trade: Trade) {
        if (!first.isMajorCiv() || !second.isMajorCiv()) return
        val qualifying = (trade.ourOffers + trade.theirOffers).any {
            (it.type in setOf(TradeOfferType.Gold, TradeOfferType.Gold_Per_Turn,
                TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource, TradeOfferType.Stockpiled_Resource)
                && it.amount > 0) || (it.type == TradeOfferType.Agreement && it.name == "Open Borders")
        }
        if (qualifying) {
            eventCompleted(first, "N15")
            eventCompleted(second, "N15")
        }
    }

    fun cityBattleWon(unit: MapUnit, city: City) {
        val history = state(unit.civ)?.history ?: return
        history.pendingCaptures[cityKey(city)] = AchievementCapture().apply {
            unitId = unit.id
            turn = unit.civ.gameInfo.turns
            foreign = city.foundingCivObject?.let { it != unit.civ && it.isMajorCiv() } == true
        }
    }

    fun cityConquered(city: City) {
        state(city.civ)?.history?.pendingCaptures?.get(cityKey(city))?.ownershipSettled = true
    }

    /** Human conquest choices may finish after the battle, including after saving and loading. */
    fun settleCaptures(game: GameInfo) {
        val state = game.achievements ?: return
        val history = state.history
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return
        for ((cityId, capture) in history.pendingCaptures.toMap()) {
            val city = game.getCities().firstOrNull { cityKey(it) == cityId }
            if (city == null) { history.pendingCaptures.remove(cityId); continue }
            if (city.civ != player || !capture.ownershipSettled) continue
            history.pendingCaptures.remove(cityId)
            if (!capture.foreign || !history.foreignCaptures.add(cityId)) continue
            if (player.goldenAges.isGoldenAge()) {
                history.goldenAgeCaptures.add(cityId)
                if (history.goldenAgeCaptures.size >= 3) history.completed.add("N35")
            }
            if (capture.turn == history.turn && player.isCurrentPlayer()) {
                history.capturedThisTurn.add(cityId)
                if (history.capturedThisTurn.size >= 2) history.completed.add("N32")
            }
        }
    }

    fun cityLiberated(city: City) {
        city.civ.gameInfo.achievements?.history?.pendingCaptures?.remove(cityKey(city))
    }

    fun goldenAgeEnded(civ: Civilization) { state(civ)?.history?.goldenAgeCaptures?.clear() }
}
