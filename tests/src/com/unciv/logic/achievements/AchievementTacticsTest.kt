package com.unciv.logic.achievements

import com.unciv.logic.map.tile.RoadStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(org.junit.runners.Parameterized::class)
@org.junit.runners.Parameterized.UseParametersRunnerFactory(com.unciv.testing.TestRunnerFactory::class)
class AchievementTacticsTest(private val baseRuleset: com.unciv.models.metadata.BaseRuleset, private val speed: String) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "{0}, {1}")
        fun parameters() = com.unciv.models.metadata.BaseRuleset.entries.flatMap { base ->
            listOf("Quick", "Standard", "Epic", "Marathon").map { arrayOf<Any>(base, it) }
        }
    }
    private fun fixture(nation: String = "Rome") = AchievementTestFixture(nation, baseRuleset, speed)

    @Test fun threeSelfFoundedCitiesMustStayConnectedToCapital() {
        val f = fixture()
        f.player.tech.addTechnology("The Wheel")
        f.game.tileMap.values.forEach { it.setRoadStatus(RoadStatus.Road, f.player) }
        f.player.cache.updateCitiesConnectedToCapital()
        assertFalse("N10" in f.results(end = true))
        val city = f.city(0, 0)
        f.city(-4, 0)
        f.player.cache.updateCitiesConnectedToCapital()
        assertFalse("N10" in f.results(end = true))
        f.city(4, 0)
        f.player.cache.updateCitiesConnectedToCapital()
        assertTrue("N10" in f.results(end = true))
        assertFalse("N10" in f.results())
        city.moveToCiv(f.opponents[0])
        f.player.cache.updateCitiesConnectedToCapital()
        assertFalse("N10" in f.results(end = true))
    }

    @Test fun romanInfrastructureRequiresFivePopulousCitiesWithFourBuildings() {
        val f = fixture("Rome")
        val cities = listOf(f.capital, f.city(-4, 0), f.city(0, 0), f.city(4, 0), f.city(8, 0))
        for (city in cities) {
            city.population.setPopulation(10)
            for (name in listOf("Monument", "Library", "Barracks")) city.cityConstructions.addBuilding(name)
        }
        for (city in cities.take(4)) city.cityConstructions.addBuilding("Granary")
        assertFalse("N28" in f.results(end = true))
        val granary = f.test.ruleset.buildings.getValue("Granary")
        granary.cost = f.test.ruleset.technologies.getValue(granary.requiredTech!!).column!!.buildingCost
        f.player.addGold(5000)
        val beforeGold = f.player.gold
        assertTrue(cities.last().cityConstructions.purchaseConstruction("Granary", -1, automatic = false))
        assertTrue("Gold before=$beforeGold after=${f.player.gold}", f.player.gold < beforeGold)
        assertTrue("N28" in f.results(end = true))
        assertFalse("N28" in f.results())
        cities.last().moveToCiv(f.opponents[0])
        assertFalse("N28" in f.results(end = true))
    }

    @Test fun threeForeignCapturesNeedCompletedOwnershipButMilitaryLossesAreAllowed() {
        val f = fixture()
        val unit = f.unit("Warrior", 0, 0)
        f.history.unit(unit.id).earnedPromotions = 3
        val casualty = f.unit("Warrior", 0, 3)
        val cities = listOf(f.city(2, 0, civ = f.opponents[0]), f.city(4, 0, civ = f.opponents[0]), f.city(6, 0, civ = f.opponents[1]))
        AchievementTracker.cityBattleWon(unit, cities[0])
        AchievementTracker.settle(f.game)
        assertFalse("N08" in f.results())
        cities[0].puppetCity(f.player)
        assertTrue("N08" in f.results())
        assertFalse("N32" in f.results())
        AchievementTracker.beginAction(f.game)
        AchievementTracker.cityBattleWon(unit, cities[1])
        cities[1].puppetCity(f.player)
        casualty.destroy()
        assertFalse("N32" in f.results())
        AchievementTracker.endAction(f.game)
        assertFalse("N32" in f.results())
        AchievementTracker.cityBattleWon(unit, cities[2])
        cities[2].puppetCity(f.player)
        assertTrue("N32" in f.results())
    }

    @Test fun duplicateCitiesDifferentTurnsTradesAndDirectLiberationDoNotQualifyAsTwoCaptures() {
        val f = fixture()
        val unit = f.unit("Warrior", 0, 0)
        val first = f.city(2, 0, civ = f.opponents[0])
        AchievementTracker.cityBattleWon(unit, first)
        first.puppetCity(f.player)
        first.moveToCiv(f.opponents[0])
        AchievementTracker.cityBattleWon(unit, first)
        first.puppetCity(f.player)
        assertEquals(1, f.history.foreignCaptures.size)
        assertFalse("N32" in f.results())
        f.nextTurn()
        val second = f.city(4, 0, civ = f.opponents[0])
        AchievementTracker.cityBattleWon(unit, second)
        second.puppetCity(f.player)
        assertFalse("N32" in f.results())
        val liberated = f.city(6, 0, civ = f.opponents[0])
        liberated.moveToCiv(f.opponents[1])
        AchievementTracker.cityBattleWon(unit, liberated)
        liberated.liberateCity(f.player)
        val traded = f.city(8, 0, civ = f.opponents[0])
        traded.moveToCiv(f.player)
        assertEquals(2, f.history.foreignCaptures.size)
        assertFalse("N32" in f.results())
    }

    @Test fun chuKoNuKillsMustUseOneUnitOnePlayerTurnAndMajorCivilizationVictims() {
        val f = fixture("China")
        val unit = f.unit("Chu-Ko-Nu", 0, 0)
        f.history.unit(unit.id).earnedPromotions = 4
        f.kill(unit, 0, 1)
        f.nextTurn()
        f.kill(unit, 1, 1)
        assertFalse("N34" in f.results())
        val second = f.unit("Chu-Ko-Nu", 4, 0)
        f.kill(second, 4, 1)
        assertFalse("N34" in f.results())
        f.kill(unit, 1, 0)
        assertFalse("N34" in f.results())
        f.history.unit(unit.id).earnedPromotions = 3
        assertFalse("N34" in f.results(end = true))
        f.history.unit(unit.id).earnedPromotions = 4
        assertTrue("N34" in f.results(end = true))
        unit.destroy()
        assertFalse("N34" in f.results(end = true))
    }

    @Test fun persianContinuousGoldenAgeAllowsExtensionButResetsAfterInterruption() {
        val f = fixture("Persia")
        val unit = f.unit("Warrior", 0, 0)
        f.player.goldenAges.enterGoldenAge()
        fun capture(x: Int, opponent: Int = 0) {
            val city = f.city(x, 0, civ = f.opponents[opponent])
            AchievementTracker.cityBattleWon(unit, city)
            city.puppetCity(f.player)
        }
        capture(2)
        f.player.goldenAges.enterGoldenAge()
        capture(4)
        assertEquals(2, f.history.goldenAgeCaptures.size)
        while (f.player.goldenAges.isGoldenAge()) f.player.goldenAges.endTurn(0)
        f.player.goldenAges.enterGoldenAge()
        capture(6)
        assertFalse("N35" in f.results())
        capture(8)
        capture(10)
        assertFalse("N35" in f.results())
        capture(12, 1)
        assertTrue("N35" in f.results())
    }
    @Test fun harborConnectionsAreAcceptedWithoutAnyRoads() {
        val f = fixture()
        val cities = listOf(f.city(-4, 0), f.city(0, 0), f.city(4, 0))
        for (tile in f.game.tileMap.values.filter { !it.isCityCenter() })
            f.test.setTileTerrain(tile.position, "Coast")
        f.player.tech.addTechnology("Compass")
        f.capital.cityConstructions.addBuilding("Harbor")
        cities.forEach { it.cityConstructions.addBuilding("Harbor") }
        f.player.cache.updateCitiesConnectedToCapital()
        assertTrue(cities.all { it.isConnectedToCapital() })
        assertTrue("N10" in f.results(end = true))
    }

    @Test fun aPendingConquestCannotLaterBecomeACaptureThroughCityTrade() {
        val f = fixture()
        val unit = f.unit("Warrior", 0, 0)
        val city = f.city(2, 0, civ = f.opponents[0])
        AchievementTracker.cityBattleWon(unit, city)
        city.moveToCiv(f.player)
        AchievementTracker.settle(f.game)
        assertFalse("N08" in f.results())
        assertTrue(f.history.foreignCaptures.isEmpty())
    }

}
