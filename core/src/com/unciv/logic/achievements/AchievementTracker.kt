package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.utils.Log
import java.io.IOException

/** Small direct hooks from successful engine mutations; never called by load/setTransients. */
object AchievementTracker {
    var service: AchievementService? = null

    fun beginAction(game: GameInfo) { game.achievements?.let { it.actionDepth++ } }
    fun endAction(game: GameInfo, successful: Boolean = true) {
        game.achievements?.let { it.actionDepth-- }
        if (successful) settle(game)
    }

    fun militaryRoster(game: GameInfo): Set<Int> {
        val state = game.achievements ?: return emptySet()
        if (state.ended || state.recordingVersion != AchievementCatalog.firstCompleteRecordingVersion) return emptySet()
        return game.civilizations.firstOrNull { it.civID == state.playerId }?.units?.getCivUnits()
            ?.filter { it.isMilitary() && !it.isDestroyed }?.map { it.id }?.toSet().orEmpty()
    }

    fun battleEnded(game: GameInfo, before: Set<Int>, successful: Boolean) {
        if (!militaryRoster(game).containsAll(before)) game.achievements?.history?.militaryLossThisTurn = true
        endAction(game, successful)
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
        if (atTurnEnd || terminal) finishTurnFacts(player)
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

    fun startTurn(civ: Civilization) {
        state(civ)?.history?.startTurn(civ.gameInfo.turns)
    }

    fun improvementChanged(tile: Tile, unit: MapUnit?) {
        if (!tile.tileMap.hasGameInfo() || tile.tileMap[tile.position] !== tile) return // Ignore preview clones.
        val history = tile.tileMap.gameInfo.achievements?.takeIf { !it.ended }?.history ?: return
        val key = AchievementGameState.tileKey(tile)
        history.greatImprovements.remove(key)
        if (unit == null || state(unit.civ) == null || !unit.isGreatPerson()) return
        val improvement = tile.improvement ?: return
        if (improvement == "Academy" && !unit.isGreatPersonOfType("Science")) return
        if (improvement in setOf("Academy", "Landmark", "Manufactory", "Customs house", "Holy site"))
            history.greatImprovements[key] = improvement
    }
    private fun state(civ: Civilization): AchievementGameState? {
        val state = civ.gameInfo.achievements ?: return null
        if (state.ended || state.playerId != civ.civID ||
            state.recordingVersion != AchievementCatalog.firstCompleteRecordingVersion) return null
        return state
    }

    fun flag(civ: Civilization, restriction: String) {
        state(civ)?.history?.restrictions?.add(restriction)
    }

    fun cityKey(city: City): String = city.civ.gameInfo.achievements?.history?.cityKeys
        ?.get(AchievementGameState.tileKey(city.getCenterTile())).orEmpty()

    fun cityFounded(city: City) {
        val state = city.civ.gameInfo.achievements ?: return
        if (state.ended || state.recordingVersion != AchievementCatalog.firstCompleteRecordingVersion) return
        val history = state.history
        history.cityKeys[AchievementGameState.tileKey(city.getCenterTile())] = (++history.nextCitySerial).toString()
        cityAcquired(city)
    }

    fun cityAcquired(city: City) {
        val history = state(city.civ)?.history ?: return
        history.maximumCities = maxOf(history.maximumCities, city.civ.cities.size)
        if (city.foundingCivObject != city.civ) history.restrictions.add(AchievementRules.foreignCity)
        if (city.foundingCivObject?.isCityState == true) history.restrictions.add(AchievementRules.cityStateCity)
        if (city.cityConstructions.getBuiltBuildings().any { it.isWonder })
            history.restrictions.add(AchievementRules.worldWonder)
    }

    fun buildingOwned(city: City, building: Building) {
        if (building.isWonder) flag(city.civ, AchievementRules.worldWonder)
    }

    fun buildingProduced(city: City, building: Building, capitalHadBuilding: Boolean) {
        val history = state(city.civ)?.history ?: return
        if (city.foundingCivObject != city.civ) return
        if (building.isWonder) {
            history.builtWonders[building.name] = cityKey(city)
            val industrial = city.getRuleset().eras["Industrial era"]?.eraNumber ?: return
            if (city.civ.getEraNumber() < industrial) {
                history.wonderCitiesThisTurn.add(cityKey(city))
                if (history.wonderCitiesThisTurn.size >= 2) history.completed.add("A04")
            }
        } else if (!building.isNationalWonder && !city.isCapital() && capitalHadBuilding) {
            history.romanBuildings.getOrPut(cityKey(city)) { HashSet() }.add(building.name)
        }
    }

    fun technologyAcquired(civ: Civilization, technology: String) {
        val ruleset = civ.gameInfo.ruleset
        val era = ruleset.technologies[technology]?.era() ?: return
        val industrial = ruleset.eras["Industrial era"]?.eraNumber ?: return
        if ((ruleset.eras[era]?.eraNumber ?: -1) >= industrial)
            flag(civ, AchievementRules.modernTechnologyOrUnit)
    }

    fun unitAcquired(unit: MapUnit) {
        if (!unit.isMilitary()) return
        val ruleset = unit.civ.gameInfo.ruleset
        val industrial = ruleset.eras["Industrial era"]?.eraNumber ?: return
        if (unit.baseUnit.requiredTechs().any { tech ->
            val era = ruleset.technologies[tech]?.era()
            (ruleset.eras[era]?.eraNumber ?: -1) >= industrial
        }) flag(unit.civ, AchievementRules.modernTechnologyOrUnit)
    }

    fun militaryProducedOrPurchased(civ: Civilization, unit: BaseUnit) {
        if (unit.isMilitary) flag(civ, AchievementRules.trainedMilitary)
    }

    fun promotionEarned(unit: MapUnit) {
        state(unit.civ)?.history?.unit(unit.id)?.let { it.earnedPromotions++ }
    }

    fun killedMilitaryUnit(killer: MapUnit, victimId: Int, victimWasMajorMilitary: Boolean,
                           attackOrigin: Tile?, wasAttacker: Boolean) {
        if (!victimWasMajorMilitary) return
        val history = state(killer.civ)?.history ?: return
        val unit = history.unit(killer.id)
        unit.majorMilitaryKills++
        if (killer.name == "Samurai") unit.samuraiMilitaryKill = true
        if (!wasAttacker || !killer.civ.isCurrentPlayer()) return
        if (killer.name == "Chu-Ko-Nu") {
            unit.chuKoNuKillsThisTurn.add(victimId.toString())
            if (unit.chuKoNuKillsThisTurn.size >= 2) history.completed.add("A13")
        }
        if (killer.name == "Keshik" && attackOrigin != null && !killer.isEmbarked()) {
            unit.keshikAttacks.add(AchievementRetreat().apply { origin = AchievementGameState.tileKey(attackOrigin) })
        }
    }

    fun militaryLost(civ: Civilization) {
        state(civ)?.history?.militaryLossThisTurn = true
    }

    fun militaryClass(unit: MapUnit): String = when {
        !unit.isMilitary() || unit.isNuclearWeapon() || unit.hasUnique(UniqueType.SelfDestructs) -> ""
        unit.baseUnit.isAirUnit() -> "air"
        unit.baseUnit.isWaterUnit && unit.baseUnit.isMelee() -> "navalMelee"
        unit.baseUnit.isWaterUnit && unit.baseUnit.isRanged() -> "navalRanged"
        unit.type.name == "Siege" -> "siege"
        unit.baseUnit.isLandUnit && unit.baseUnit.isMelee() -> "landMelee"
        unit.baseUnit.isLandUnit && unit.baseUnit.isRanged() -> "landRanged"
        else -> ""
    }

    fun cityDamaged(unit: MapUnit, city: City, damage: Int) {
        val history = state(unit.civ)?.history ?: return
        if (damage <= 0 || !unit.civ.isCurrentPlayer()) return
        val type = militaryClass(unit)
        if (type.isEmpty()) return
        history.cityDamageClasses.getOrPut(cityKey(city)) { HashSet() }.add(type)
        if (unit.name == "Ship of the Line") history.shipOfTheLineDamage.add(cityKey(city))
    }

    fun cityBattleWon(unit: MapUnit, city: City) {
        val history = state(unit.civ)?.history ?: return
        history.pendingCaptures[cityKey(city)] = AchievementCapture().apply {
            unitId = unit.id
            turn = unit.civ.gameInfo.turns
            foreign = city.foundingCivObject?.let { it != unit.civ && it.isMajorCiv() } == true
            originalCapital = city.isOriginalCapital
            coastal = city.isCoastal()
            militaryClass = militaryClass(unit)
            hadSamuraiKill = history.units[unit.id.toString()]?.samuraiMilitaryKill == true
            crossedMountain = history.units[unit.id.toString()]?.crossedMountainThisTurn == true
        }
    }

    /** Invoked only after the conquest choice and any enclosing battle are fully settled. */
    fun settleCaptures(game: GameInfo) {
        val state = game.achievements ?: return
        val history = state.history
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return
        for ((cityId, capture) in history.pendingCaptures.toMap()) {
            val city = game.getCities().firstOrNull { cityKey(it) == cityId }
            if (city == null) { history.pendingCaptures.remove(cityId); continue }
            if (city.civ != player) continue // The human may still need to choose puppet/liberate.
            history.pendingCaptures.remove(cityId)
            history.restrictions.add(AchievementRules.capturedAnyCity)
            if (!capture.foreign) continue
            history.foreignCaptures.add(cityId)
            if (capture.militaryClass == "navalMelee") history.navalCaptures.add(cityId)
            if (capture.hadSamuraiKill && capture.originalCapital) {
                history.samuraiCapitals.getOrPut(capture.unitId.toString()) { HashSet() }.add(cityId)
                val pairs = history.samuraiCapitals.entries.toList()
                if (pairs.any { a -> pairs.any { b -> a.key != b.key && a.value.any { x -> b.value.any { it != x } } } })
                    history.completed.add("A34")
            }
            if (player.goldenAges.isGoldenAge()) {
                history.goldenAgeCaptures.add(cityId)
                if (history.goldenAgeCaptures.size >= 3) history.completed.add("A14")
            }
            if (capture.turn == history.turn && player.isCurrentPlayer()) {
                history.capturedThisTurn.add(cityId)
                val classes = history.cityDamageClasses[cityId].orEmpty() + capture.militaryClass.takeIf { it.isNotEmpty() }.orEmpty()
                if (classes.count { it.isNotEmpty() } >= 3) history.completed.add("A30")
                if (capture.coastal && capture.militaryClass == "navalMelee" && cityId in history.shipOfTheLineDamage) {
                    history.englishCaptures.add(cityId)
                    if (history.englishCaptures.size >= 3) history.completed.add("A31")
                }
                if (capture.crossedMountain) history.unit(capture.unitId).cityCapturedAfterCrossing = true
            }
            if (history.mountainParties.any { capture.unitId.toString() in it.members && capture.turn in it.turn..it.turn + 3 })
                history.completed.add("A36")
        }
        if (history.capturedThisTurn.size >= 2 && !history.militaryLossThisTurn)
            history.completed.add("A02")
    }

    fun cityLiberated(city: City) {
        city.civ.gameInfo.achievements?.history?.pendingCaptures?.remove(cityKey(city))
    }

    fun goldenAgeEnded(civ: Civilization) {
        state(civ)?.history?.goldenAgeCaptures?.clear()
    }

    fun discontinuousMovement(unit: MapUnit) {
        val facts = state(unit.civ)?.history?.units?.get(unit.id.toString()) ?: return
        facts.keshikAttacks.clear()
        facts.mountainEntry = ""
        facts.crossedMountainThisTurn = false
        facts.cityCapturedAfterCrossing = false
    }

    /** The caller passes the actually traversed path, ending at the final reachable tile. */
    fun landMovement(unit: MapUnit, path: List<Tile>) {
        val state = state(unit.civ) ?: return
        if (!unit.civ.isCurrentPlayer() || path.size < 2) return
        if (path.any { it.isWater } || !unit.baseUnit.isLandUnit) {
            discontinuousMovement(unit)
            return
        }
        val facts = state.history.unit(unit.id)
        facts.keshikAttacks.forEach { it.moved = true }
        if (!unit.isMilitary() || "Mountain" !in unit.civ.passableImpassables || unit.hasUnique(UniqueType.CanPassImpassable)) return
        for ((from, to) in path.zipWithNext()) {
            if (from.baseTerrain != "Mountain" && to.baseTerrain == "Mountain") facts.mountainEntry = AchievementGameState.tileKey(from)
            if (from.baseTerrain == "Mountain" && to.baseTerrain != "Mountain") {
                if (facts.mountainEntry.isNotEmpty() && facts.mountainEntry != AchievementGameState.tileKey(to))
                    facts.crossedMountainThisTurn = true
                facts.mountainEntry = ""
            }
        }
    }

    fun finishTurnFacts(civ: Civilization) {
        val history = state(civ)?.history ?: return
        val crossed = civ.units.getCivUnits().filter { unit ->
            !unit.isDestroyed && unit.getTile().baseTerrain != "Mountain" &&
                history.units[unit.id.toString()]?.crossedMountainThisTurn == true
        }.map { it.id.toString() }.toHashSet()
        if (crossed.size >= 2) {
            if (crossed.any { history.units[it]?.cityCapturedAfterCrossing == true }) history.completed.add("A36")
            if (history.mountainParties.none { it.turn == history.turn })
                history.mountainParties.add(AchievementMountainParty().apply { turn = history.turn; members = crossed })
        }
    }
}
