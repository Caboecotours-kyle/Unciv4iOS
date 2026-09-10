package com.unciv.logic.achievements

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AchievementFoundationTest {
    private fun newGame(aiCount: Int = 3): TestGame = TestGame().apply {
        makeHexagonalMap(3)
        addCiv(ruleset.nations.getValue("Rome"), isPlayer = true)
        listOf("Greece", "China", "Egypt").take(aiCount).forEach { addCiv(ruleset.nations.getValue(it)) }
        gameInfo.gameParameters.victoryTypes.addAll(AchievementCatalog.victoryRoutes)
    }

    private fun state(testGame: TestGame) = AchievementGameState.create(testGame.gameInfo, true, false)!!

    @Test fun catalogMatchesTheApprovedFortyAndTierBudgets() {
        assertEquals((1..40).map { "A%02d".format(it) }.toSet(), AchievementCatalog.byId.keys)
        assertEquals(40, AchievementCatalog.definitions.size)
        assertEquals(listOf(6, 14, 12, 6, 2), AchievementTier.entries.map { tier ->
            AchievementCatalog.definitions.count { it.tier == tier }
        })
        assertEquals(listOf(6, 29, 2, 3), listOf(null, "Prince", "Emperor", "Deity").map { difficulty ->
            AchievementCatalog.definitions.count { it.minimumDifficulty == difficulty }
        })
        assertEquals(12, AchievementCatalog.civilizationAchievements.size)
        assertEquals(12, AchievementCatalog.civilizationAchievements.values.toSet().size)
    }

    @Test fun difficultyUsesBuiltInOrderAndRejectsUnknownValues() {
        assertTrue(AchievementCatalog.meetsDifficulty("Deity", "Prince"))
        assertFalse(AchievementCatalog.meetsDifficulty("King", "Emperor"))
        assertFalse(AchievementCatalog.meetsDifficulty("王子", null))
        assertFalse(AchievementCatalog.meetsDifficulty("Deity", "Unknown"))
        assertTrue(AchievementCatalog.meetsDifficulty("Settler", null))
    }

    @Test fun newSinglePlayerGameFreezesCatalogAndActualOpponents() {
        val game = newGame()
        game.gameInfo.gameParameters.numberOfCityStates = 8
        val state = state(game)
        assertEquals(game.gameInfo.gameId, state.gameId)
        assertEquals("Rome", state.civilization)
        assertEquals(3, state.aiOpponents)
        assertEquals(0, state.cityStates) // Real placed civilizations, not the requested setting.
        assertEquals(AchievementCatalog.byId.keys, state.availableIds)
        assertNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("A32"), state))
    }

    @Test fun globalInvalidSettingsNeverProduceARecord() {
        val mutations: List<(GameInfo) -> Unit> = listOf(
            { it.gameParameters.mods.add("Example mod") },
            { it.gameParameters.baseRuleset = "Example base mod" },
            { it.gameParameters.isOnlineMultiplayer = true },
            { it.civilizations[1].playerType = PlayerType.Human },
            { it.civilizations[0].playerType = PlayerType.AI },
            { it.gameParameters.startingEra = "Industrial era" },
            { it.gameParameters.godMode = true },
            { it.simulateUntilWin = true },
            { it.difficulty = "Custom" },
            { it.tileMap.mapParameters.type = MapType.empty }
        )
        for ((index, mutate) in mutations.withIndex()) {
            val game = newGame().gameInfo
            mutate(game)
            assertNull("Invalid case $index", AchievementGameState.create(game, true, false))
        }
        val game = newGame().gameInfo
        assertNull(AchievementGameState.create(game, false, false))
        assertNull(AchievementGameState.create(game, true, true))
        assertNull(AchievementGameState.create(newGame(0).gameInfo, true, false))
    }

    @Test fun sixOpenDifficultyAchievementsWorkWithOneOpponentAndNoVictories() {
        val game = newGame(1)
        game.gameInfo.difficulty = "Settler"
        game.gameInfo.gameParameters.victoryTypes.clear()
        val state = state(game)
        val applicable = AchievementCatalog.definitions.filter { AchievementCatalog.ineligibility(it, state) == null }
        assertEquals(setOf("A01", "A02", "A03", "A04", "A23", "A24"), applicable.map { it.id }.toSet())
    }

    @Test fun princeRequiresThreeOpponentsAndAllFourRoutes() {
        val definition = AchievementCatalog.byId.getValue("A06")
        assertNotNull(AchievementCatalog.ineligibility(definition, state(newGame(2))))
        val state = state(newGame())
        assertNull(AchievementCatalog.ineligibility(definition, state))
        state.enabledVictories.remove("Diplomatic")
        assertNotNull(AchievementCatalog.ineligibility(definition, state))
    }

    @Test fun individualRequirementsAndNewCatalogIdsAreNotInferred() {
        val state = state(newGame())
        for (id in listOf("A05", "A13", "A16", "A17", "A18", "A22"))
            assertNotNull(id, AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue(id), state))
        state.oneCityChallenge = true
        assertNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("A05"), state))
        assertNotNull(AchievementCatalog.ineligibility(AchievementDefinition("A41", AchievementTier.Introductory, null, introducedIn = 2), state))
    }

    @Test fun missingUnitAndMissingCivilizationAbilityAreInapplicable() {
        val state = state(newGame())
        state.civilization = "China"
        state.availableUnits.remove("Chu-Ko-Nu")
        assertNotNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("A13"), state))
        state.civilization = "Carthage"
        state.baseRuleset = BaseRuleset.Civ_V_Vanilla.fullName
        assertNotNull(AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("A36"), state))
    }

    @Test fun legacySaveAndCloneDoNotCreateEligibility() {
        val legacy = json().fromJson(GameInfo::class.java, "{gameId:legacy}")
        assertNull(legacy.achievements)
        assertNull(legacy.clone().achievements)
        legacy.gameId = "renamed-or-imported"
        assertNull(json().fromJson(GameInfo::class.java, json().toJson(legacy)).achievements)
    }

    @Test fun saveRoundTripAndUndoCloneKeepIndependentState() {
        val game = newGame().gameInfo
        game.achievements = AchievementGameState.create(game, true, false)
        game.achievements!!.targetLandmasses.add(hashSetOf("0,0", "1,0"))
        val copy = game.clone()
        copy.achievements!!.availableIds.remove("A01")
        copy.achievements!!.targetLandmasses[0].clear()
        copy.achievements!!.ended = true
        assertTrue("A01" in game.achievements!!.availableIds)
        assertEquals(2, game.achievements!!.targetLandmasses[0].size)
        assertFalse(game.achievements!!.ended)
        val loaded = json().fromJson(GameInfo::class.java, json().toJson(game))
        assertEquals(game.gameId, loaded.achievements!!.gameId)
        assertEquals(game.achievements!!.availableIds, loaded.achievements!!.availableIds)
        assertEquals(game.achievements!!.targetLandmasses, loaded.achievements!!.targetLandmasses)
    }

    @Test fun developmentRecordingCannotEarnAwards() {
        val game = newGame().gameInfo
        val state = AchievementGameState.create(game, true, false)!!
        assertTrue(state.canContribute(game, AchievementProfile()))
        state.recordingVersion = 0
        assertFalse(state.canContribute(game, AchievementProfile()))
        val missingVersions = json().fromJson(AchievementGameState::class.java, "{gameId:old}")
        assertEquals(0, missingVersions.catalogVersion)
        assertEquals(0, missingVersions.recordingVersion)
        state.recordingVersion = AchievementCatalog.firstCompleteRecordingVersion + 1
        assertFalse(state.canContribute(game, AchievementProfile()))
    }

    @Test fun mountainsConnectLandButWaterDoesNot() {
        val game = newGame()
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, Constants.ocean)
        for (x in -2..2) game.setTileTerrain(game.getTile(x, 0).position, Constants.grassland)
        game.setTileTerrain(game.getTile(0, 0).position, Constants.mountain)
        val land = AchievementGameState.findTargetLandmasses(game.tileMap)
        assertEquals(1, land.size)
        assertEquals(5, land.single().size)
        game.setTileTerrain(game.getTile(0, 0).position, Constants.ocean)
        val separate = AchievementGameState.findTargetLandmasses(game.tileMap)
        assertEquals(listOf(2, 2), separate.map { it.size })
        assertTrue(AchievementGameState.tileKey(game.getTile(-2, 0)) in separate[0])
    }

    @Test fun actualUpgradeAndFailedUpgradeKeepUnitIdentity() {
        val game = newGame()
        val civ = game.gameInfo.civilizations.first()
        val unit = game.addUnit("Warrior", civ, game.getTile(0, 0))
        val id = unit.id
        unit.upgrade.performUpgrade(game.ruleset.units.getValue("Spearman"), isFree = true)
        val upgraded = civ.units.getUnitById(id)!!
        assertEquals("Spearman", upgraded.name)
        upgraded.health = 71
        upgraded.upgrade.performUpgrade(game.ruleset.units.getValue("Trireme"), isFree = true)
        val restored = civ.units.getUnitById(id)!!
        assertEquals("Spearman", restored.name)
        assertEquals(71, restored.health)
        assertEquals(1, civ.units.getCivUnits().count())
    }
}
