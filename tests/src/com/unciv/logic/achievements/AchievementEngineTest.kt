package com.unciv.logic.achievements

import com.unciv.json.json
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal class AchievementTestFixture(nation: String = "Rome") {
    val test = TestGame().apply { makeHexagonalMap(12) }
    val game = test.gameInfo
    val player = test.addCiv(test.ruleset.nations.getValue(nation), isPlayer = true)
    val opponents = listOf("Greece", "China", "Egypt", "Rome").filter { it != nation }.take(3)
        .map { test.addCiv(test.ruleset.nations.getValue(it)) }
    val capital = test.addCity(player, test.getTile(-8, -8))
    val state: AchievementGameState
    val history get() = state.history
    init {
        // Engine fixtures do not initialize an application settings directory.
        com.unciv.UncivGame.Current.settings.tutorialTasksCompleted.add("Meet another civilization")
        opponents.forEachIndexed { i, civ -> test.addCity(civ, test.getTile(8, 5 + i * 2)) }
        game.currentPlayer = player.civID
        game.currentPlayerCiv = player
        game.gameParameters.victoryTypes.addAll(AchievementCatalog.victoryRoutes)
        game.gameParameters.baseRuleset = BaseRuleset.Civ_V_GnK.fullName
        state = AchievementGameState.create(game, true, false)!!.apply {
            recordingVersion = AchievementCatalog.firstCompleteRecordingVersion
            initializationComplete = true
        }
        game.achievements = state
        game.getCities().forEach { AchievementTracker.cityFounded(it) }
    }
    fun unit(name: String, x: Int, y: Int, civ: Civilization = player): MapUnit =
        civ.units.placeUnitNearTile(HexCoord(x, y), test.ruleset.units.getValue(name))!!
    fun city(x: Int, y: Int, population: Int = 1, civ: Civilization = player): City =
        test.addCity(civ, test.getTile(x, y), initialPopulation = population)
    fun kill(attacker: MapUnit, x: Int, y: Int, civ: Civilization = opponents[0]) {
        val victim = unit("Warrior", x, y, civ).apply { health = 1 }
        if (!player.isAtWarWith(civ)) {
            player.diplomacyFunctions.makeCivilizationsMeet(civ)
            player.getDiplomacyManager(civ)!!.declareWar()
        }
        attacker.currentMovement = attacker.getMaxMovement().toFloat()
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(victim))
    }
    fun results(end: Boolean = false, route: String? = null) =
        AchievementRules.evaluate(game, end, player.takeIf { route != null }, route)
    fun nextTurn() {
        game.turns++
        AchievementTracker.startTurn(player)
        player.units.getCivUnits().forEach { it.currentMovement = it.getMaxMovement().toFloat(); it.attacksThisTurn = 0 }
    }
}

@RunWith(BaseTestRunner::class)
class AchievementEngineTest {

    @After fun clearService() { AchievementTracker.service = null }

    @Test fun battleKillsAndEarnedPromotionsUseRealSources() {
        val f = AchievementTestFixture()
        val veteran = f.unit("Swordsman", 0, 0)
        repeat(3) { f.kill(veteran, 1 + it, 0) }
        veteran.promotions.addPromotion("Drill I", isFree = true)
        veteran.promotions.XP = 100
        veteran.promotions.addPromotion("Shock I")
        assertFalse("A01" in f.results())
        veteran.promotions.addPromotion("Shock II")
        assertTrue("A01" in f.results())
        val facts = f.history.units.getValue(veteran.id.toString())
        assertEquals(3, facts.majorMilitaryKills)
        assertEquals(2, facts.earnedPromotions)
        veteran.upgrade.performUpgrade(f.test.ruleset.units.getValue("Longswordsman"), isFree = true)
        assertTrue("A01" in f.results())
    }

    @Test fun barbarianKillsDoNotBecomeMajorCivilizationKills() {
        val f = AchievementTestFixture()
        val veteran = f.unit("Swordsman", 0, 0)
        val barbarians = f.test.addBarbarianCiv()
        val victim = f.unit("Warrior", 1, 0, barbarians).apply { health = 1 }
        Battle.attack(MapUnitCombatant(veteran), MapUnitCombatant(victim))
        assertEquals(0, f.history.units[veteran.id.toString()]?.majorMilitaryKills ?: 0)
    }

    @Test fun chuKoNuKillsMustShareAPlayerTurn() {
        val f = AchievementTestFixture("China")
        val unit = f.unit("Chu-Ko-Nu", 0, 0)
        f.kill(unit, 0, 1)
        f.nextTurn()
        f.kill(unit, 1, 1)
        assertFalse("A13" in f.results())
        f.kill(unit, 1, 0)
        assertTrue("A13" in f.results())
    }

    @Test fun conquestWaitsForOwnershipAndCompleteBattleLosses() {
        val f = AchievementTestFixture()
        val attacker = f.unit("Warrior", 0, 0)
        val lostUnit = f.unit("Warrior", 0, 3)
        val first = f.city(1, 0, civ = f.opponents[0])
        val second = f.city(3, 0, civ = f.opponents[0])
        AchievementTracker.cityBattleWon(attacker, first)
        AchievementTracker.settle(f.game)
        assertTrue(f.history.foreignCaptures.isEmpty())
        first.puppetCity(f.player)
        val before = AchievementTracker.militaryRoster(f.game)
        AchievementTracker.beginAction(f.game)
        AchievementTracker.cityBattleWon(attacker, second)
        second.puppetCity(f.player)
        assertFalse("A02" in f.results())
        lostUnit.destroy()
        AchievementTracker.battleEnded(f.game, before, true)
        assertEquals(2, f.history.foreignCaptures.size)
        assertFalse("A02" in f.results())
    }

    @Test fun twoForeignCitiesWithNoLossUnlockAndLaterLossDoesNotRevoke() {
        val f = AchievementTestFixture()
        val unit = f.unit("Warrior", 0, 0)
        for (city in listOf(f.city(1, 0, civ = f.opponents[0]), f.city(3, 0, civ = f.opponents[0]))) {
            AchievementTracker.cityBattleWon(unit, city)
            city.puppetCity(f.player)
        }
        assertTrue("A02" in f.results())
        AchievementTracker.militaryLost(f.player)
        assertTrue("A02" in f.results())
        assertEquals(2, f.history.foreignCaptures.size)
    }

    @Test fun directLiberationIsNotOwnershipButTemporaryCityTransfersAreHistory() {
        val f = AchievementTestFixture()
        val unit = f.unit("Warrior", 0, 0)
        val city = f.city(1, 0, civ = f.opponents[0])
        city.moveToCiv(f.opponents[1])
        AchievementTracker.cityBattleWon(unit, city)
        city.liberateCity(f.player)
        assertTrue(f.history.foreignCaptures.isEmpty())
        assertFalse(AchievementRules.capturedAnyCity in f.history.restrictions)
        city.moveToCiv(f.player)
        city.moveToCiv(f.opponents[0])
        assertEquals(2, f.history.maximumCities)
        assertTrue(AchievementRules.foreignCity in f.history.restrictions)
        assertEquals(1, f.player.cities.size)
    }

    @Test fun wondersRequireProductionInTwoSelfFoundedCitiesInSameTurn() {
        val f = AchievementTestFixture("Egypt")
        val second = f.city(0, 0)
        f.capital.cityConstructions.addBuilding("The Pyramids")
        second.cityConstructions.addBuilding("Stonehenge")
        assertFalse("A04" in f.results())
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Great Library"))
        f.nextTurn()
        second.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Oracle"))
        assertFalse("A04" in f.results())
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Great Lighthouse"))
        assertTrue("A04" in f.results())
        assertTrue(AchievementRules.worldWonder in f.history.restrictions)
        assertEquals(3, f.history.builtWonders.size)
    }

    @Test fun industrialEraAndNationalWondersCannotCompleteTwinWonders() {
        val f = AchievementTestFixture("Egypt")
        val second = f.city(0, 0)
        f.player.tech.addTechnology("Industrialization")
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("The Pyramids"))
        second.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue("Stonehenge"))
        assertFalse("A04" in f.results())
    }

    @Test fun militaryCreationSourcesAndPoliciesKeepHistoricalRestrictions() {
        val f = AchievementTestFixture()
        f.unit("Warrior", 0, 0) // gift/initial placement is allowed for A27.
        assertFalse(AchievementRules.trainedMilitary in f.history.restrictions)
        f.capital.cityConstructions.completeConstruction(f.test.ruleset.units.getValue("Warrior"))
        assertTrue(AchievementRules.trainedMilitary in f.history.restrictions)
        val oldBranch = f.game.clone()
        f.player.tech.addTechnology("Industrialization", showNotification = false)
        assertTrue(AchievementRules.modernTechnologyOrUnit in f.history.restrictions)
        assertFalse(AchievementRules.modernTechnologyOrUnit in oldBranch.achievements!!.history.restrictions)
        f.player.policies.freePolicies = 1
        f.player.policies.adopt(f.test.ruleset.policies.getValue("Rationalism"))
        f.player.policies.removePolicy(f.test.ruleset.policies.getValue("Rationalism"), assumeWasFree = true)
        assertTrue(AchievementRules.rationalism in f.history.restrictions)
    }

    @Test fun modernMilitaryGiftsViolateOldWorldEvenWithoutTheTechnology() {
        val f = AchievementTestFixture()
        assertFalse(f.player.tech.isResearched("Replaceable Parts"))
        f.unit("Great War Infantry", 0, 0)
        assertTrue(AchievementRules.modernTechnologyOrUnit in f.history.restrictions)
        assertFalse(AchievementRules.trainedMilitary in f.history.restrictions)
    }

    @Test fun successfulChosenUpgradesIncludeZeroCostButFailedAndAutomaticOnesDoNot() {
        val f = AchievementTestFixture()
        val unit = f.unit("Warrior", 0, 0)
        unit.upgrade.performUpgrade(f.test.ruleset.units.getValue("Swordsman"), isFree = true)
        assertFalse(AchievementRules.trainedMilitary in f.history.restrictions)
        val successor = f.player.units.getCivUnits().single { it.id == unit.id }
        successor.upgrade.performUpgrade(f.test.ruleset.units.getValue("Longswordsman"), isFree = true, playerInitiated = true)
        assertTrue(AchievementRules.trainedMilitary in f.history.restrictions)
    }

    @Test fun greatImprovementProvenanceSurvivesRepairButNotReplacementOrPreview() {
        val f = AchievementTestFixture("Babylon")
        val city = f.city(0, 0)
        val tile = f.test.getTile(0, 1)
        city.expansion.takeOwnership(tile)
        city.workedTiles.add(tile.position)
        val scientist = f.unit("Great Scientist", 0, 1)
        tile.setImprovement("Academy", f.player, scientist)
        val key = AchievementGameState.tileKey(tile)
        assertEquals("Academy", f.history.greatImprovements[key])
        tile.clone().setImprovement("Farm", f.player)
        assertEquals("Academy", f.history.greatImprovements[key])
        tile.improvementIsPillaged = true
        tile.setImprovement("Repair", f.player)
        assertEquals("Academy", f.history.greatImprovements[key])
        tile.removeImprovement()
        tile.setImprovement("Academy", f.opponents[0], null)
        assertFalse(key in f.history.greatImprovements)
    }

    @Test fun goldenAgeInterruptionResetsTheAttemptWhileExtensionKeepsIt() {
        val f = AchievementTestFixture("Persia")
        val unit = f.unit("Warrior", 0, 0)
        f.player.goldenAges.enterGoldenAge()
        fun capture(x: Int) {
            val city = f.city(x, 0, civ = f.opponents[0])
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
        assertFalse("A14" in f.results())
        capture(8)
        capture(10)
        assertTrue("A14" in f.results())
    }

    @Test fun keshiksRequireRealPostKillMovementAndDistanceForTwoSurvivors() {
        val f = AchievementTestFixture("Mongolia")
        val first = f.unit("Keshik", 0, 0)
        val second = f.unit("Keshik", 4, 0)
        f.kill(first, 0, 1)
        f.kill(second, 4, 1)
        assertFalse("A35" in f.results(end = true))
        first.movement.moveToTile(f.test.getTile(-2, 0))
        second.movement.moveToTile(f.test.getTile(2, 0))
        assertTrue("A35" in f.results(end = true))
        AchievementTracker.discontinuousMovement(second)
        assertFalse("A35" in f.results(end = true))
    }

    @Test fun mountainCrossingUsesRealPathAndCompletesAfterThePartySurvives() {
        val f = AchievementTestFixture("Carthage")
        f.unit("Great General", -4, -4)
        assertTrue("Mountain" in f.player.passableImpassables)
        val city = f.city(2, 0, civ = f.opponents[0])
        f.player.diplomacyFunctions.makeCivilizationsMeet(f.opponents[0])
        f.player.getDiplomacyManager(f.opponents[0])!!.declareWar()
        val first = f.unit("Warrior", -1, 0)
        val second = f.unit("Warrior", -1, 3)
        for (y in listOf(0, 3)) {
            f.test.setTileTerrain(HexCoord(0, y), "Mountain")
            for (x in -1..1) f.test.getTile(x, y).setRoadStatus(RoadStatus.Road, f.player)
        }
        first.movement.moveToTile(f.test.getTile(1, 0))
        second.movement.moveToTile(f.test.getTile(1, 3))
        assertTrue(f.history.unit(first.id).crossedMountainThisTurn)
        assertTrue(f.history.unit(second.id).crossedMountainThisTurn)
        AchievementTracker.cityBattleWon(first, city)
        city.puppetCity(f.player)
        assertFalse("A36" in f.results())
        AchievementTracker.settle(f.game, atTurnEnd = true)
        assertTrue("A36" in f.results())
    }

    @Test fun historyRoundTripAndUndoDeepCopyNestedEvents() {
        val f = AchievementTestFixture("Mongolia")
        val unit = f.unit("Keshik", 0, 0)
        f.kill(unit, 0, 1)
        f.history.romanBuildings["city"] = hashSetOf("Granary")
        val copy = f.state.clone()
        copy.history.unit(unit.id).keshikAttacks.clear()
        copy.history.romanBuildings.getValue("city").clear()
        assertEquals(1, f.history.unit(unit.id).keshikAttacks.size)
        assertEquals(setOf("Granary"), f.history.romanBuildings["city"])
        val restored = json().fromJson(AchievementGameState::class.java, json().toJson(f.state))
        assertEquals(1, restored.history.unit(unit.id).majorMilitaryKills)
        assertEquals(1, restored.history.unit(unit.id).keshikAttacks.size)
        assertEquals(0, restored.actionDepth)
    }
}
