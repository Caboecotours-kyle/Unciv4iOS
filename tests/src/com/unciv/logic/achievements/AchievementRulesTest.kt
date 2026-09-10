package com.unciv.logic.achievements

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

    @Test fun firstWinIncludesEveryOfficialRouteAtLowestDifficultyWithOneOpponent() {
        val routes = mapOf("Scientific" to "N17", "Cultural" to "N18", "Domination" to "N19", "Diplomatic" to "N20", "Time" to null)
        for ((route, medal) in routes) {
            val f = fixture()
            f.state.difficulty = "Settler"
            f.state.aiOpponents = 1
            f.state.enabledVictories = hashSetOf(route)
            val result = f.results(route = route)
            assertTrue(route, "N01" in result)
            assertEquals(setOfNotNull(medal), result.intersect(setOf("N17", "N18", "N19", "N20")))
            assertFalse("N36" in result)
            assertFalse("N01" in f.results())
            assertFalse("N01" in AchievementRules.evaluate(f.game, true, f.opponents[0], route))
        }
    }

    @Test fun highDifficultyWinsIncludeTimeAndOnlyExplicitChallengesAddRestrictions() {
        val f = fixture()
        for (route in AchievementCatalog.victoryRoutes) {
            f.state.difficulty = "King"
            assertFalse("N36" in f.results(route = route))
            f.state.difficulty = "Emperor"
            assertTrue("N36" in f.results(route = route))
            assertFalse("N37" in f.results(route = route))
            f.state.difficulty = "Deity"
            assertTrue("N37" in f.results(route = route))
        }
        assertFalse("N01" in f.results(route = "Unknown"))
    }

    @Test fun populationAndGoldUseTheSameEndOfTurnSnapshotAndDoNotSumPastIncome() {
        val f = fixture()
        for ((population, first, second) in listOf(Triple(9, false, false), Triple(10, true, false), Triple(19, true, false), Triple(20, true, true))) {
            f.capital.population.setPopulation(population)
            assertEquals(first, "N11" in f.results(end = true))
            assertEquals(second, "N21" in f.results(end = true))
            assertFalse("N11" in f.results())
        }
        f.player.addGold(499 - f.player.gold)
        assertFalse("N12" in f.results(end = true))
        f.player.addGold(1)
        assertTrue("N12" in f.results(end = true))
        assertFalse("N12" in f.results())
        f.player.addGold(1499)
        assertFalse("N23" in f.results(end = true))
        f.player.addGold(1)
        assertTrue("N23" in f.results(end = true))
        f.player.addGold(-2000)
        assertFalse("N12" in f.results(end = true))
    }

    @Test fun cityStateAlliancesRequireOneOrThreeSimultaneousLivingAllies() {
        val f = fixture()
        val states = f.test.ruleset.nations.values.filter { it.isCityState }.take(3).mapIndexed { i, nation ->
            f.test.addCiv(nation).also { f.city(-8 + i * 4, 0, civ = it) }
        }
        assertFalse("N13" in f.results(end = true))
        states[0].allyCiv = f.player
        assertTrue("N13" in f.results(end = true))
        assertFalse("N13" in f.results())
        states[1].allyCiv = f.player
        assertFalse("N25" in f.results(end = true))
        states[2].allyCiv = f.player
        assertTrue("N25" in f.results(end = true))
        states[0].allyCiv = null
        assertFalse("N25" in f.results(end = true))
    }

    @Test fun fourProsperousCitiesNeedFifteenPopulationAndNonnegativeHappiness() {
        val f = fixture()
        val cities = listOf(f.capital, f.city(-4, 0), f.city(0, 0), f.city(4, 0))
        cities.forEach { it.population.setPopulation(15) }
        f.player.stats.happiness = 0
        assertTrue("N29" in f.results(end = true))
        assertFalse("N29" in f.results())
        cities.last().population.setPopulation(14)
        f.player.stats.happiness = 0
        assertFalse("N29" in f.results(end = true))
        cities.last().population.setPopulation(15)
        f.city(8, 0, 1)
        f.player.stats.happiness = -1
        assertFalse("N29" in f.results(end = true))
        f.player.stats.happiness = 0
        assertTrue("N29" in f.results(end = true)) // A fifth small city is allowed.
        cities.last().moveToCiv(f.opponents[0])
        f.player.stats.happiness = 0
        assertFalse("N29" in f.results(end = true))
    }

    @Test fun eightDifferentWorldWondersNeedPrinceAndOneGame() {
        val f = fixture()
        val wonders = f.test.ruleset.buildings.values.filter { it.isWonder }.take(8)
        for (wonder in wonders.take(7)) f.capital.cityConstructions.completeConstruction(wonder)
        assertFalse("N30" in f.results())
        f.capital.cityConstructions.completeConstruction(wonders.last())
        assertTrue("N30" in f.results())
        f.state.difficulty = "Warlord"
        assertFalse("N30" in f.results())
    }

    @Test fun religionCoverageCountsOwnFoundedMajorityAndCurrentForeignOwnership() {
        val f = fixture()
        org.junit.Assume.assumeTrue(f.game.isReligionEnabled())
        val religion = f.test.addReligion(f.player)
        religion.addBelief(f.test.ruleset.beliefs.values.first { it.type == com.unciv.models.ruleset.BeliefType.Founder })
        val owned = listOf(f.capital) + listOf(-8, -4, 0, 4, 8, -8).mapIndexed { i, x -> f.city(x, if (i == 5) 4 else 0, 10) }
        val foreign = f.opponents.map { it.getCapital()!! }
        val cities = owned + foreign
        cities.forEach { it.population.setPopulation(10) }
        for (city in cities.take(9)) city.religion.addPressure(religion.name, 100000)
        assertFalse("N33" in f.results(end = true))
        cities.last().religion.addPressure(religion.name, 100000)
        assertTrue("N33" in f.results(end = true))
        assertFalse("N33" in f.results())
        foreign[0].moveToCiv(f.player)
        assertFalse("N33" in f.results(end = true))
        foreign[0].moveToCiv(f.opponents[0])
        val otherReligion = f.test.addReligion(f.opponents[0])
        f.player.religionManager.religion = otherReligion
        assertFalse("N33" in f.results(end = true))
    }

    @Test fun oneCityScienceRequiresTheSwitchAndTheCompleteOwnershipHistory() {
        val f = fixture()
        f.state.difficulty = "Emperor"
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

    @Test fun wonderlessCultureAllowsNationalWondersButRemembersAcquiredWorldWonders() {
        val f = fixture()
        f.state.difficulty = "Emperor"
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
