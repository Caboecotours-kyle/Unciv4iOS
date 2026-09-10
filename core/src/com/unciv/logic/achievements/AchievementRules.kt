package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization

/** Evaluates recorded facts and the fully settled game state. It never infers past actions. */
object AchievementRules {
    const val foreignCity = "foreignCity"
    const val cityStateCity = "cityStateCity"
    const val worldWonder = "worldWonder"
    const val modernTechnologyOrUnit = "modernTechnologyOrUnit"
    const val declaredWar = "declaredWar"
    const val capturedAnyCity = "capturedAnyCity"
    const val giftedCityStateGold = "giftedCityStateGold"
    const val rationalism = "rationalism"
    const val trainedMilitary = "trainedMilitary"
    const val scientistResearch = "scientistResearch"

    fun evaluate(game: GameInfo, atTurnEnd: Boolean = false,
                 winner: Civilization? = null, victoryRoute: String? = null): Set<String> {
        val state = game.achievements ?: return emptySet()
        if (state.ended) return emptySet()
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return emptySet()
        val history = state.history
        val result = HashSet(history.completed)
        val ownedUnits = player.units.getCivUnits().filter { !it.isDestroyed }.toList()
        val ownCities = player.cities.filter { it.foundingCivObject == player }
        fun add(id: String, fulfilled: Boolean) { if (fulfilled) result.add(id) }

        add("A01", ownedUnits.any {
            val unit = history.units[it.id.toString()]
            unit != null && unit.majorMilitaryKills >= 3 && unit.earnedPromotions >= 2
        })
        add("A32", romanCitiesShareThreeBuildings(player, history))

        if (atTurnEnd) {
            val happy = player.getHappiness() >= 0
            add("A03", happy && ownCities.count { it.population.population >= 10 } >= 4)
            add("A15", happy && history.maximumCities <= 3 && foreignCity !in history.restrictions &&
                player.cities.size == 3 && ownCities.size == 3 && ownCities.sumOf { it.population.population } >= 60)
            val capital = player.getCapital()
            add("A23", happy && capital != null && capital in ownCities && capital.population.population >= 8 &&
                ownCities.count { it != capital && it.population.population >= 8 && it.isConnectedToCapital() } >= 5)
            add("A24", player.cities.any { city -> workedGreatImprovements(city, history).toSet().size >= 3 })
            add("A35", ownedUnits.count { unit ->
                history.units[unit.id.toString()]?.keshikAttacks.orEmpty().any { attack ->
                    attack.moved && game.tileMap.values.firstOrNull { AchievementGameState.tileKey(it) == attack.origin }
                        ?.aerialDistanceTo(unit.getTile())?.let { it >= 2 } == true
                }
            } >= 2)
        }

        if (winner == player && victoryRoute in AchievementCatalog.victoryRoutes) {
            val science = victoryRoute == "Scientific"
            val culture = victoryRoute == "Cultural"
            val diplomacy = victoryRoute == "Diplomatic"
            val domination = victoryRoute == "Domination"
            val flags = history.restrictions
            add("A05", science && history.maximumCities <= 1)
            add("A06", culture && worldWonder !in flags)
            add("A07", diplomacy && history.maximumCities <= 3)
            add("A08", diplomacy && giftedCityStateGold !in flags)
            add("A09", domination && modernTechnologyOrUnit !in flags)
            add("A10", (science || culture) && declaredWar !in flags && capturedAnyCity !in flags)
            add("A11", culture && history.builtWonders.size >= 6 && history.builtWonders.values.toSet().size >= 3)
            add("A12", science && ownCities.count { "Academy" in workedGreatImprovements(it, history) } >= 4)
            add("A16", diplomacy && cityStateCity !in flags &&
                game.civilizations.count { it.isCityState && it.allyCiv == player && !it.isDefeated() } >= 6)
            add("A17", science && state.targetLandmasses.size == 2 && state.targetLandmasses.all { land ->
                ownCities.count { AchievementGameState.tileKey(it.getCenterTile()) in land } >= 2
            })
            add("A18", domination && history.foreignCaptures.size >= 3 && history.navalCaptures.size >= 2)
            add("A21", true)
            add("A22", true)
            add("A25", science && rationalism !in flags)
            add("A26", culture && ownCities.count { it.population.population >= 10 } >= 6)
            add("A27", diplomacy && trainedMilitary !in flags)
            add("A28", culture && history.maximumCities <= 1)
            add("A29", domination && ownedUnits.count {
                (history.units[it.id.toString()]?.earnedPromotions ?: 0) >= 3
            } >= 3)
            add("A33", science && scientistResearch !in flags)
            add("A37", science && history.maximumCities <= 1)
            add("A38", culture && worldWonder !in flags)
        }
        return result.filterTo(HashSet()) {
            val definition = AchievementCatalog.byId[it]
            definition != null && !definition.isCollection && AchievementCatalog.ineligibility(definition, state) == null
        }
    }

    private fun workedGreatImprovements(city: City, history: AchievementHistory): List<String> =
        city.getWorkedTiles().mapNotNull { tile ->
            val expected = history.greatImprovements[AchievementGameState.tileKey(tile)]
            expected?.takeIf { tile.improvement == it && !tile.improvementIsPillaged && tile.owningCity == city }
        }.toList()

    private fun romanCitiesShareThreeBuildings(player: Civilization, history: AchievementHistory): Boolean {
        val sets = player.cities.filter { it.foundingCivObject == player && !it.isCapital() }.map { city ->
            val built = city.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
            history.romanBuildings[AchievementTracker.cityKey(city)].orEmpty().intersect(built)
        }.filter { it.size >= 3 }
        if (sets.size < 4) return false
        // A common triple must occur in four cities; three separate popular buildings are insufficient.
        val counts = HashMap<List<String>, Int>()
        for (buildings in sets) {
            val names = buildings.sorted()
            for (a in 0 until names.size - 2) for (b in a + 1 until names.size - 1) for (c in b + 1 until names.size) {
                val triple = listOf(names[a], names[b], names[c])
                val count = (counts[triple] ?: 0) + 1
                if (count >= 4) return true
                counts[triple] = count
            }
        }
        return false
    }
}
