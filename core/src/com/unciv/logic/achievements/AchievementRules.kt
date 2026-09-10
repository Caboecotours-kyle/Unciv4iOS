package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/** Evaluates source facts and fully settled state. Old saves never acquire missing V2 history. */
object AchievementRules {
    const val worldWonder = "worldWonder"

    fun evaluate(game: GameInfo, atTurnEnd: Boolean = false,
                 winner: Civilization? = null, victoryRoute: String? = null): Set<String> {
        val state = game.achievements ?: return emptySet()
        if (state.ended || state.recordingVersion != AchievementCatalog.recordingVersion ||
            state.catalogVersion != AchievementCatalog.version || state.gameId != game.gameId) return emptySet()
        val player = game.civilizations.firstOrNull { it.civID == state.playerId } ?: return emptySet()
        val history = state.history
        val result = HashSet(history.completed)
        val ownCities = player.cities.filter { it.foundingCivObject == player }
        fun add(id: String, fulfilled: Boolean) { if (fulfilled) result.add(id) }

        add("N02", history.foundedCities.size >= 2)
        add("N05", history.builtWonders.isNotEmpty())
        add("N08", history.foreignCaptures.isNotEmpty())
        add("N27", history.builtWonders.size >= 3)
        add("N30", history.builtWonders.size >= 8)
        add("N31", player.units.getCivUnits().any {
            it.isMilitary() && !it.isDestroyed && (history.units[it.id.toString()]?.earnedPromotions ?: 0) >= 5
        })

        if (atTurnEnd) {
            add("N10", ownCities.any { !it.isCapital() && it.isConnectedToCapital() })
            add("N11", player.cities.any { it.population.population >= 10 })
            add("N12", player.gold >= 500)
            val allies = game.civilizations.count { it.isCityState && it.allyCiv == player && !it.isDefeated() }
            add("N13", allies >= 1)
            add("N21", player.cities.any { it.population.population >= 20 })
            add("N23", player.gold >= 2000)
            add("N25", allies >= 3)
            add("N28", ownCities.count { city ->
                val buildings = city.cityConstructions.getBuiltBuildings().map { it.name }.toSet()
                "Monument" in buildings && "Granary" in buildings
            } >= 4)
            add("N29", player.getHappiness() >= 0 && ownCities.count { it.population.population >= 15 } >= 4)
            if (game.isReligionEnabled()) {
                val religion = player.religionManager.religion
                if (religion != null && religion.foundingCivName == player.civID && religion.isMajorReligion()) {
                    val cities = game.getCities().filter { it.religion.getMajorityReligionName() == religion.name }.toList()
                    add("N33", cities.size >= 10 && cities.count { it.civ != player && it.civ.isMajorCiv() } >= 3)
                }
            }
        }

        if (winner == player && victoryRoute in AchievementCatalog.victoryRoutes) {
            add("N01", true)
            add("N17", victoryRoute == "Scientific")
            add("N18", victoryRoute == "Cultural")
            add("N19", victoryRoute == "Domination")
            add("N20", victoryRoute == "Diplomatic")
            add("N36", true)
            add("N37", true)
            add("N38", victoryRoute == "Scientific" && history.maximumCities <= 1)
            add("N39", victoryRoute == "Cultural" && worldWonder !in history.restrictions)
        }
        return result.filterTo(HashSet()) {
            val definition = AchievementCatalog.byId[it]
            definition != null && !definition.isCollection && AchievementCatalog.ineligibility(definition, state) == null
        }
    }
}
