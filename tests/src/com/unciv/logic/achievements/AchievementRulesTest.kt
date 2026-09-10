package com.unciv.logic.achievements

import com.unciv.logic.city.City
import com.unciv.models.ruleset.BeliefType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(org.junit.runners.Parameterized::class)
@org.junit.runners.Parameterized.UseParametersRunnerFactory(com.unciv.testing.TestRunnerFactory::class)
class AchievementRulesTest(private val baseRuleset: com.unciv.models.metadata.BaseRuleset) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters() = com.unciv.models.metadata.BaseRuleset.entries.map { arrayOf(it) }
    }
    private fun fixture(nation: String = "Rome") = AchievementTestFixture(nation, baseRuleset)
    private fun buildings(city: City, vararg names: String) = names.forEach { city.cityConstructions.addBuilding(it) }
    private fun allies(f: AchievementTestFixture, count: Int) = f.test.ruleset.nations.values
        .filter { it.isCityState }.groupBy { it.cityStateType }.values
        .let { groups -> groups.map { it.first() } + groups.flatMap { it.drop(1) } }.take(count)
        .mapIndexed { i, nation -> f.test.addCiv(nation).also { f.city(-8 + i * 4, 4, civ = it) } }

    @Test fun aSettlerVictoryWithOneOpponentOnlyUnlocksTheSixBeginnerClues() {
        val f = fixture()
        f.state.difficulty = "Settler"
        f.state.aiOpponents = 1
        f.history.completed.addAll(AchievementCatalog.byId.keys)
        for (route in AchievementCatalog.victoryRoutes) {
            assertEquals(setOf("N01", "N02", "N03", "N05", "N06", "N09"), f.results(route = route))
        }
        f.history.completed.clear()
        for (route in AchievementCatalog.victoryRoutes) assertEquals(setOf("N01"), f.results(route = route))
        assertFalse("N01" in f.results())
        assertFalse("N01" in AchievementRules.evaluate(f.game, true, f.opponents[0], "Domination"))
    }

    @Test fun specialistVictoriesRememberExpansionConquestAndWonderRestrictions() {
        val f = fixture()
        assertTrue("N17" in f.results(route = "Scientific"))
        assertTrue("N18" in f.results(route = "Cultural"))
        assertTrue("N19" in f.results(route = "Domination"))
        assertTrue("N20" in f.results(route = "Diplomatic"))
        f.history.maximumCities = 5
        assertFalse("N17" in f.results(route = "Scientific"))
        assertFalse("N18" in f.results(route = "Cultural"))
        f.history.maximumCities = 4
        f.history.foreignCaptures.add("captured")
        assertFalse("N17" in f.results(route = "Scientific"))
        assertTrue("N18" in f.results(route = "Cultural"))
        f.history.foundedCities.add("second-founded-city")
        assertFalse("N19" in f.results(route = "Domination"))
        f.history.builtWonders.putAll(mapOf("one" to "city", "two" to "city"))
        assertTrue("N20" in f.results(route = "Diplomatic"))
        f.history.builtWonders["three"] = "city"
        assertFalse("N20" in f.results(route = "Diplomatic"))
    }

    @Test fun extremeWinsExcludeTimeAndRequireFourOpponents() {
        val f = fixture()
        for (route in AchievementCatalog.victoryRoutes) {
            f.state.difficulty = "King"
            assertFalse("N36" in f.results(route = route))
            f.state.difficulty = "Emperor"
            assertEquals(route != "Time", "N36" in f.results(route = route))
            assertFalse("N37" in f.results(route = route))
            f.state.difficulty = "Deity"
            assertEquals(route != "Time", "N37" in f.results(route = route))
        }
        f.state.aiOpponents = 3
        assertFalse("N37" in f.results(route = "Scientific"))
        f.state.aiOpponents = 4
        f.history.maximumCities = 4
        assertFalse("N36" in f.results(route = "Scientific"))
        f.history.maximumCities = 3
        f.history.foreignCaptures.add("captured")
        assertFalse("N36" in f.results(route = "Scientific"))
        assertTrue("N37" in f.results(route = "Scientific"))
        assertFalse("N01" in f.results(route = "Unknown"))
    }

    @Test fun universityCityRequiresPopulationBuildingsOwnershipAndTurnEnd() {
        val f = fixture()
        f.capital.population.setPopulation(15)
        assertFalse("N11" in f.results(end = true))
        buildings(f.capital, "Library", "University")
        assertTrue("N11" in f.results(end = true))
        assertFalse("N11" in f.results())
        f.capital.population.setPopulation(14)
        assertFalse("N11" in f.results(end = true))
        val foreign = f.opponents[0].getCapital()!!
        buildings(foreign, "Library", "University")
        foreign.population.setPopulation(15)
        foreign.moveToCiv(f.player)
        assertFalse("N11" in f.results(end = true))
    }

    @Test fun bankingNeedsThreeCompleteSelfFoundedCitiesAndUnspentGold() {
        val f = fixture()
        val cities = listOf(f.capital, f.city(-4, 0), f.city(0, 0))
        cities.forEach { buildings(it, "Market") }
        cities.take(2).forEach { buildings(it, "Bank") }
        f.player.addGold(1000 - f.player.gold)
        assertFalse("N12" in f.results(end = true))
        buildings(cities.last(), "Bank")
        assertTrue("N12" in f.results(end = true))
        assertFalse("N12" in f.results())
        f.player.addGold(-1)
        assertFalse("N12" in f.results(end = true))
        f.player.addGold(1)
        cities.last().moveToCiv(f.opponents[0])
        assertFalse("N12" in f.results(end = true))
    }

    @Test fun alliancesRequireDiverseLivingPartnersAndGoldAtTheSameSnapshot() {
        val f = fixture()
        val states = allies(f, 5)
        assertEquals(5, states.size)
        states.take(2).forEach { it.allyCiv = f.player }
        assertFalse("N13" in f.results(end = true))
        states[2].allyCiv = f.player
        assertTrue("N13" in f.results(end = true))
        assertFalse("N13" in f.results())
        f.player.addGold(3000 - f.player.gold)
        assertFalse("N23" in f.results(end = true))
        states[3].allyCiv = f.player
        assertTrue("N23" in f.results(end = true))
        assertFalse("N25" in f.results(end = true))
        states[4].allyCiv = f.player
        assertTrue("N25" in f.results(end = true))
        f.player.addGold(-1)
        assertFalse("N23" in f.results(end = true))
        states[4].allyCiv = null
        assertFalse("N25" in f.results(end = true))
    }

    @Test fun aTallPeacefulCityCannotEraseHistoricalOverexpansion() {
        val f = fixture()
        f.capital.population.setPopulation(24)
        assertFalse("N21" in f.results(end = true))
        f.capital.population.setPopulation(25)
        assertTrue("N21" in f.results(end = true))
        assertFalse("N21" in f.results())
        f.history.maximumCities = 4
        assertFalse("N21" in f.results(end = true))
        f.history.maximumCities = 3
        f.history.foreignCaptures.add("captured")
        assertFalse("N21" in f.results(end = true))
    }

    @Test fun fourProsperousCitiesRequireTwentyPopulationTenHappinessAndNoConquest() {
        val f = fixture()
        val cities = listOf(f.capital, f.city(-4, 0), f.city(0, 0), f.city(4, 0))
        cities.forEach { it.population.setPopulation(20) }
        f.player.stats.happiness = 10
        assertTrue("N29" in f.results(end = true))
        assertFalse("N29" in f.results())
        cities.last().population.setPopulation(19)
        f.player.stats.happiness = 10
        assertFalse("N29" in f.results(end = true))
        cities.last().population.setPopulation(20)
        f.player.stats.happiness = 9
        assertFalse("N29" in f.results(end = true))
        f.player.stats.happiness = 10
        f.history.foreignCaptures.add("captured")
        assertFalse("N29" in f.results(end = true))
    }

    @Test fun aWonderCapitalRequiresEightActualCompletionsInOneStillOwnedCity() {
        val f = fixture()
        val wonders = f.test.ruleset.buildings.values.filter { it.isWonder }.take(8)
        for (wonder in wonders.take(7)) f.capital.cityConstructions.completeConstruction(wonder)
        val other = f.city(0, 0)
        other.cityConstructions.completeConstruction(wonders.last())
        assertFalse("N30" in f.results())
        f.history.builtWonders[wonders.last().name] = AchievementTracker.cityKey(f.capital)
        assertTrue("N30" in f.results())
        f.state.difficulty = "King"
        assertFalse("N30" in f.results())
        f.state.difficulty = "Emperor"
        f.capital.moveToCiv(f.opponents[0])
        assertFalse("N30" in f.results())
    }

    @Test fun religionNeedsRealMajoritiesAcrossDistinctForeignCivilizations() {
        val f = fixture()
        org.junit.Assume.assumeTrue(f.game.isReligionEnabled())
        val religion = f.test.addReligion(f.player)
        religion.addBelief(f.test.ruleset.beliefs.values.first { it.type == BeliefType.Founder })
        val own = listOf(f.capital) + listOf(-8, -4, 0, 4, 8, -8, -4, 0, 4).mapIndexed { i, x -> f.city(x, if (i < 5) 0 else 4, 10) }
        val foreign = f.opponents.take(3).map { it.getCapital()!! } + listOf(f.city(-4, -4, civ = f.opponents[0]), f.city(0, -4, civ = f.opponents[1]))
        val cities = own + foreign
        cities.forEach { it.population.setPopulation(10) }
        own.take(2).forEach { it.religion.addPressure(religion.name, 100000) }
        assertFalse("N14" in f.results(end = true))
        own[2].religion.addPressure(religion.name, 100000)
        assertTrue("N14" in f.results(end = true))
        for (city in cities.take(14)) city.religion.addPressure(religion.name, 100000)
        assertFalse("N33" in f.results(end = true))
        cities.last().religion.addPressure(religion.name, 100000)
        assertTrue("N33" in f.results(end = true))
        assertFalse("N33" in f.results())
        assertFalse("N24" in f.results(end = true))
        religion.addBelief(f.test.ruleset.beliefs.values.first { it.type == BeliefType.Enhancer })
        assertTrue("N24" in f.results(end = true))
        foreign[2].moveToCiv(f.opponents[0])
        assertFalse("N33" in f.results(end = true)) // Only two foreign civilizations remain.
        assertFalse("N24" in f.results(end = true)) // The former third capital is no longer a capital.
        f.player.religionManager.religion = f.test.addReligion(f.opponents[0])
        assertFalse("N14" in f.results(end = true))
    }

    @Test fun oneCityScienceRequiresTheSwitchAndTheCompleteOwnershipHistory() {
        val f = fixture()
        assertFalse("N38" in f.results(route = "Scientific"))
        f.state.oneCityChallenge = true
        assertTrue("N38" in f.results(route = "Scientific"))
        assertFalse("N38" in f.results(route = "Time"))
        val before = f.state.clone()
        val acquired = f.city(0, 0, civ = f.opponents[0])
        acquired.moveToCiv(f.player)
        acquired.moveToCiv(f.opponents[0])
        assertEquals(1, f.player.cities.size)
        assertFalse("N38" in f.results(route = "Scientific"))
        f.game.achievements = before
        assertTrue("N38" in f.results(route = "Scientific"))
    }

    @Test fun wonderlessCultureRemembersAcquiredWorldWondersButAllowsNationalWonders() {
        val f = fixture()
        f.capital.cityConstructions.addBuilding("National College")
        assertTrue("N39" in f.results(route = "Cultural"))
        assertFalse("N39" in f.results(route = "Scientific"))
        val acquired = f.city(0, 0, civ = f.opponents[0])
        acquired.cityConstructions.addBuilding("The Pyramids")
        acquired.moveToCiv(f.player)
        acquired.moveToCiv(f.opponents[0])
        assertFalse("N39" in f.results(route = "Cultural"))
    }
}
