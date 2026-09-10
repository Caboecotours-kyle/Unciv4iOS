package com.unciv.logic.achievements

import com.unciv.logic.map.MapType
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AchievementRulesTest {
    @Test fun victoryAwardsRequireTheWinnerRouteAndHistoricalRestrictions() {
        val f = AchievementTestFixture()
        f.state.oneCityChallenge = true
        f.state.difficulty = "Deity"
        assertTrue(f.results().isEmpty())
        assertTrue(AchievementRules.evaluate(f.game, winner = f.opponents[0], victoryRoute = "Scientific").isEmpty())
        assertTrue(f.results(route = "Time").isEmpty())
        assertEquals(setOf("A05", "A10", "A21", "A22", "A25", "A37"), f.results(route = "Scientific"))
        assertEquals(setOf("A06", "A10", "A21", "A22", "A28", "A38"), f.results(route = "Cultural"))
        assertEquals(setOf("A07", "A08", "A21", "A22", "A27"), f.results(route = "Diplomatic"))
        assertEquals(setOf("A09", "A21", "A22"), f.results(route = "Domination"))
        f.history.maximumCities = 4
        f.history.restrictions.addAll(listOf(AchievementRules.worldWonder, AchievementRules.modernTechnologyOrUnit,
            AchievementRules.declaredWar, AchievementRules.giftedCityStateGold, AchievementRules.rationalism,
            AchievementRules.trainedMilitary))
        for (route in AchievementCatalog.victoryRoutes)
            assertEquals(setOf("A21", "A22"), f.results(route = route))
        f.state.difficulty = "Prince"
        assertTrue(f.results(route = "Scientific").isEmpty())
    }

    @Test fun recapturingASelfFoundedCityAlsoBreaksPeacefulVictory() {
        val f = AchievementTestFixture()
        val unit = f.unit("Warrior", 0, 0)
        val own = f.city(1, 0)
        own.moveToCiv(f.opponents[0])
        AchievementTracker.cityBattleWon(unit, own)
        own.puppetCity(f.player)
        assertTrue(f.history.foreignCaptures.isEmpty())
        assertFalse("A10" in f.results(route = "Scientific"))
    }

    @Test fun sixBuiltWondersInThreeFoundingCitiesSurviveOwnershipLoss() {
        val f = AchievementTestFixture("Egypt")
        val cities = listOf(f.capital, f.city(0, 0), f.city(4, 0))
        val wonders = listOf("The Pyramids", "Stonehenge", "The Great Library", "The Oracle", "The Great Lighthouse", "Colossus")
        wonders.forEachIndexed { index, name ->
            cities[index / 2].cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue(name))
        }
        assertTrue("A11" in f.results(route = "Cultural"))
        cities[1].moveToCiv(f.opponents[0])
        assertTrue("A11" in f.results(route = "Cultural"))
        f.history.builtWonders.remove(wonders.last())
        assertFalse("A11" in f.results(route = "Cultural"))
    }

    @Test fun academiesNeedFourOwnedWorkingCitiesAndRealScientistProvenance() {
        val f = AchievementTestFixture("Babylon")
        val cities = listOf(f.city(-4, 0), f.city(0, 0), f.city(4, 0), f.city(8, 0))
        val tiles = cities.map { city ->
            val tile = city.getCenterTile().neighbors.first { !it.isCityCenter() }
            city.expansion.takeOwnership(tile)
            tile.setImprovement("Academy", f.player, f.unit("Great Scientist", tile.position.x, tile.position.y))
            city.workedTiles.add(tile.position)
            tile
        }
        assertTrue("A12" in f.results(route = "Scientific"))
        tiles[0].improvementIsPillaged = true
        assertFalse("A12" in f.results(route = "Scientific"))
        tiles[0].setImprovement("Repair", f.player)
        assertTrue("A12" in f.results(route = "Scientific"))
        cities[0].workedTiles.clear()
        assertFalse("A12" in f.results(route = "Scientific"))
        cities[0].workedTiles.add(tiles[0].position)
        tiles[0].setImprovement("Academy", f.player, f.unit("Great Engineer", -3, 2))
        assertFalse("A12" in f.results(route = "Scientific"))
    }

    @Test fun sameCityMustWorkThreeDifferentUnpillagedGreatImprovements() {
        val f = AchievementTestFixture()
        val city = f.city(0, 0, 4)
        val tiles = city.getCenterTile().neighbors.take(3).toList()
        listOf("Academy" to "Great Scientist", "Manufactory" to "Great Engineer", "Customs house" to "Great Merchant")
            .forEachIndexed { i, (improvement, name) ->
                val tile = tiles[i]
                city.expansion.takeOwnership(tile)
                tile.setImprovement(improvement, f.player, f.unit(name, tile.position.x, tile.position.y))
                city.workedTiles.add(tile.position)
            }
        city.workedTiles.clear()
        city.workedTiles.addAll(tiles.map { it.position })
        assertFalse("A24" in f.results())
        assertTrue("A24" in f.results(end = true))
        tiles[0].improvementIsPillaged = true
        assertFalse("A24" in f.results(end = true))
        tiles[0].setImprovement("Customs house", f.player, f.unit("Great Merchant", 3, 3))
        assertFalse("A24" in f.results(end = true))
    }

    @Test fun populationAchievementsUseOneFinalHappySnapshotAndHistory() {
        val f = AchievementTestFixture("India")
        f.capital.population.setPopulation(20)
        val cities = listOf(f.city(-4, 0, 20), f.city(0, 0, 20))
        f.player.stats.happiness = 0
        assertFalse("A15" in f.results())
        assertTrue("A15" in f.results(end = true))
        f.player.stats.happiness = -1
        assertFalse("A15" in f.results(end = true))
        f.player.stats.happiness = 0
        f.history.maximumCities = 4
        assertFalse("A15" in f.results(end = true))
        f.city(4, 0, 10)
        f.player.stats.happiness = 0
        assertTrue("A03" in f.results(end = true))
        cities[0].moveToCiv(f.opponents[0])
        f.player.stats.happiness = 0
        assertFalse("A03" in f.results(end = true))
    }

    @Test fun sixCultureCitiesMustAllBeSelfFoundedAndStillOwnedAtVictory() {
        val f = AchievementTestFixture()
        f.capital.population.setPopulation(10)
        val cities = listOf(-8, -4, 0, 4, 8).map { f.city(it, 0, 10) }
        assertTrue("A26" in f.results(route = "Cultural"))
        cities[0].population.setPopulation(9)
        assertFalse("A26" in f.results(route = "Cultural"))
        cities[0].population.setPopulation(10)
        cities[0].moveToCiv(f.opponents[0])
        assertFalse("A26" in f.results(route = "Cultural"))
    }

    @Test fun cityStateAllianceCountsOnlyLivingAlliesAndKeepsOwnershipHistory() {
        val f = AchievementTestFixture("Greece")
        f.state.cityStates = 8
        val states = f.test.ruleset.nations.values.filter { it.isCityState }.take(6).mapIndexed { i, nation ->
            f.test.addCiv(nation).also {
                f.city(-10 + i * 3, -3, civ = it)
                it.allyCiv = f.player
            }
        }
        assertTrue("A16" in f.results(route = "Diplomatic"))
        states[0].allyCiv = null
        assertFalse("A16" in f.results(route = "Diplomatic"))
        states[0].allyCiv = f.player
        val city = states[0].cities.first()
        city.moveToCiv(f.player)
        city.moveToCiv(states[0])
        assertFalse("A16" in f.results(route = "Diplomatic"))
    }

    @Test fun twoContinentsCountTheInitialLandmassesAndSelfFoundedCenters() {
        val f = AchievementTestFixture()
        val cities = listOf(f.city(-4, 0), f.city(0, 0), f.city(4, 0), f.city(8, 0))
        f.state.mapType = MapType.twoContinents
        f.state.targetLandmasses = arrayListOf(
            cities.take(2).mapTo(HashSet()) { AchievementGameState.tileKey(it.getCenterTile()) },
            cities.drop(2).mapTo(HashSet()) { AchievementGameState.tileKey(it.getCenterTile()) })
        assertTrue("A17" in f.results(route = "Scientific"))
        cities[0].moveToCiv(f.opponents[0])
        assertFalse("A17" in f.results(route = "Scientific"))
    }

    @Test fun archipelagoCountsDistinctCitiesAndNavalConquestSources() {
        val f = AchievementTestFixture()
        f.state.mapType = MapType.archipelago
        f.history.foreignCaptures.addAll(listOf("a", "b", "c"))
        f.history.navalCaptures.add("a")
        assertFalse("A18" in f.results(route = "Domination"))
        f.history.navalCaptures.add("b")
        assertTrue("A18" in f.results(route = "Domination"))
        f.state.mapType = MapType.pangaea
        assertFalse("A18" in f.results(route = "Domination"))
    }

    @Test fun veteranArmyRequiresThreeSurvivingLogicalUnitsWithEarnedPromotions() {
        val f = AchievementTestFixture()
        val units = listOf(0, 3, 6).map { f.unit("Warrior", it, 0) }
        units.forEach {
            it.promotions.XP = 1000
            for (name in listOf("Shock I", "Shock II", "Shock III")) it.promotions.addPromotion(name)
        }
        assertTrue("A29" in f.results(route = "Domination"))
        units[0].destroy()
        assertFalse("A29" in f.results(route = "Domination"))
    }

    @Test fun koreanScienceRestrictionIsHistoricalAndCivilizationSpecific() {
        val f = AchievementTestFixture("Korea")
        assertTrue("A33" in f.results(route = "Scientific"))
        AchievementTracker.flag(f.player, AchievementRules.scientistResearch)
        assertFalse("A33" in f.results(route = "Scientific"))
    }
}
