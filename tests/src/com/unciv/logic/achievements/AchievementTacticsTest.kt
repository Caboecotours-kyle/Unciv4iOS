package com.unciv.logic.achievements

import com.unciv.logic.map.tile.RoadStatus
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AchievementTacticsTest {
    @Test fun capitalNeedsFiveOtherConnectedSelfFoundedCities() {
        val f = AchievementTestFixture()
        f.capital.population.setPopulation(8)
        val cities = listOf(-8, -4, 0, 4, 8).map { f.city(it, 0, 8) }
        f.player.tech.addTechnology("The Wheel")
        f.game.tileMap.values.forEach { it.setRoadStatus(RoadStatus.Road, f.player) }
        f.player.cache.updateCitiesConnectedToCapital()
        f.player.stats.happiness = 0
        assertTrue("A23" in f.results(end = true))
        cities[0].population.setPopulation(7)
        assertFalse("A23" in f.results(end = true))
        cities[0].population.setPopulation(8)
        cities[0].moveToCiv(f.opponents[0])
        f.player.stats.happiness = 0
        assertFalse("A23" in f.results(end = true))
    }

    @Test fun combinedArmsIncludesTheFinalOccupierButExcludesZeroDamageAndOldTurns() {
        val f = AchievementTestFixture()
        val city = f.city(3, 0, civ = f.opponents[0])
        val archer = f.unit("Archer", 0, 0)
        val catapult = f.unit("Catapult", 0, 2)
        val warrior = f.unit("Warrior", 1, 0)
        AchievementTracker.cityDamaged(archer, city, 1)
        f.nextTurn()
        AchievementTracker.cityDamaged(catapult, city, 1)
        AchievementTracker.cityDamaged(archer, city, 0)
        AchievementTracker.cityBattleWon(warrior, city)
        city.puppetCity(f.player)
        assertFalse("A30" in f.results())
        city.moveToCiv(f.opponents[0])
        AchievementTracker.cityDamaged(archer, city, 1)
        AchievementTracker.cityBattleWon(warrior, city)
        city.puppetCity(f.player)
        assertTrue("A30" in f.results())
    }

    @Test fun englishNavalCapturesRequireOwnShipDamageInTheCaptureTurn() {
        val f = AchievementTestFixture("England")
        for (x in -4..6) for (y in -3..0) f.test.getTile(x, y).apply { baseTerrain = "Coast"; setTerrainTransients() }
        val ship = f.unit("Ship of the Line", 0, -2)
        val melee = f.unit("Trireme", -2, -2)
        val cities = listOf(-2, 2, 6).map { f.city(it, 1, civ = f.opponents[0]) }
        cities.forEach {
            assertTrue(it.isCoastal())
            AchievementTracker.cityDamaged(ship, it, 10)
            AchievementTracker.cityBattleWon(melee, it)
            it.puppetCity(f.player)
        }
        assertTrue("A31" in f.results())
        assertEquals(3, f.history.navalCaptures.size)
    }

    @Test fun romanBuildingTriplesNeedTheSameThreeTypesAcrossFourCurrentCities() {
        val f = AchievementTestFixture("Rome")
        val cities = listOf(-6, -2, 2, 6).map { f.city(it, 0) }
        val names = listOf("Monument", "Granary", "Library", "Market")
        names.forEach { f.capital.cityConstructions.addBuilding(it) }
        cities.forEachIndexed { index, city ->
            names.filterIndexed { i, _ -> i != index }.forEach {
                city.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue(it))
            }
        }
        assertFalse("A32" in f.results()) // Each type occurs three times; there is no common triple.
        cities.forEachIndexed { index, city ->
            city.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue(names[index]))
        }
        assertTrue("A32" in f.results())
    }

    @Test fun samuraiMatchingRetainsASecondLineageEvenWhenTheCapitalWasPreviouslyCaptured() {
        val f = AchievementTestFixture("Japan")
        val first = f.unit("Samurai", 0, 0)
        val second = f.unit("Samurai", 3, 0)
        f.kill(first, 0, 1)
        f.kill(second, 3, 1)
        val capitals = f.opponents.take(2).map { it.getCapital()!! }
        capitals.forEach {
            AchievementTracker.cityBattleWon(first, it)
            it.puppetCity(f.player)
        }
        assertFalse("A34" in f.results())
        capitals[0].moveToCiv(f.opponents[0])
        second.upgrade.performUpgrade(f.test.ruleset.units.getValue("Rifleman"), isFree = true)
        val successor = f.player.units.getCivUnits().single { it.id == second.id }
        AchievementTracker.cityBattleWon(successor, capitals[0])
        capitals[0].puppetCity(f.player)
        assertTrue("A34" in f.results())
        assertEquals(2, f.history.foreignCaptures.size)
    }

    @Test fun mountainPartyAllowsItsMemberThroughTurnThreeButNotTurnFour() {
        fun attempt(delay: Int, member: Boolean): Boolean {
            val f = AchievementTestFixture("Carthage")
            val first = f.unit("Warrior", 0, 0)
            val second = f.unit("Warrior", 3, 0)
            f.history.unit(first.id).crossedMountainThisTurn = true
            f.history.unit(second.id).crossedMountainThisTurn = true
            AchievementTracker.finishTurnFacts(f.player)
            repeat(delay) { f.nextTurn() }
            val city = f.city(6, 0, civ = f.opponents[0])
            val captor = if (member) first else f.unit("Warrior", 4, 2)
            AchievementTracker.cityBattleWon(captor, city)
            city.puppetCity(f.player)
            return "A36" in f.results()
        }
        assertTrue(attempt(3, true))
        assertFalse(attempt(4, true))
        assertFalse(attempt(1, false))
    }

    @Test fun modernGiftAndCaptureAcquisitionRecordTheSameRestriction() {
        val f = AchievementTestFixture()
        val modern = f.unit("Great War Infantry", 0, 0, f.opponents[0])
        assertFalse(AchievementRules.modernTechnologyOrUnit in f.history.restrictions)
        modern.gift(f.player)
        assertTrue(AchievementRules.modernTechnologyOrUnit in f.history.restrictions)
    }
}
