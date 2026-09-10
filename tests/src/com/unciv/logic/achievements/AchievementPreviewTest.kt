package com.unciv.logic.achievements

import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(BaseTestRunner::class)
class AchievementPreviewTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun reset() { AchievementTracker.service = null }

    @Test fun previewStartsSeparatelyWithoutReusingOrDeletingExistingAwards() {
        val root = temporary.newFolder()
        val old = AchievementStore(File(root, "unbound"))
        old.update { it.recordUnlock(AchievementRecord().apply { achievementId = "N01"; catalogVersion = 2 }) }
        val before = File(root, "unbound/profile.json").readBytes()
        val previewDirectory = File(root, "preview-v3")
        val preview = AchievementService(previewDirectory)
        assertTrue(preview.completed().isEmpty())
        val f = AchievementTestFixture()
        preview.record(f.game, setOf("N03"))
        assertEquals(setOf("N03"), AchievementService(previewDirectory).completed())
        assertArrayEquals(before, File(root, "unbound/profile.json").readBytes())
        assertEquals(setOf("N01"), AchievementService(root).completed())
        f.state.catalogVersion = 2
        f.state.recordingVersion = 2
        preview.record(f.game, setOf("N02"))
        assertEquals(setOf("N03"), preview.completed())
    }

    @Test fun goldenAgeWonderWindowDoesNotCountTwoWondersInOneCityOrPastGoldenAges() {
        val f = AchievementTestFixture()
        val other = f.city(0, 0)
        fun build(name: String, city: com.unciv.logic.city.City) =
            city.cityConstructions.completeConstruction(f.test.ruleset.buildings.getValue(name))
        f.player.goldenAges.enterGoldenAge()
        build("The Great Library", f.capital)
        build("The Oracle", f.capital)
        assertFalse("N04" in f.results())
        while (f.player.goldenAges.isGoldenAge()) f.player.goldenAges.endTurn(0)
        assertTrue(f.history.goldenAgeWonderCities.isEmpty())
        f.player.goldenAges.enterGoldenAge()
        build("Stonehenge", other)
        assertFalse("N04" in f.results())
        build("Colossus", f.capital)
        assertTrue("N04" in f.results())
    }

    @Test fun veteranCaptureRequiresThreeEarnedPromotionsAndCanUseARecapturedCity() {
        val f = AchievementTestFixture()
        val unit = f.unit("Warrior", 0, 0)
        val city = f.city(2, 0, civ = f.opponents[0])
        f.history.unit(unit.id).earnedPromotions = 2
        AchievementTracker.cityBattleWon(unit, city)
        city.puppetCity(f.player)
        assertFalse("N08" in f.results())
        city.moveToCiv(f.opponents[0])
        f.history.unit(unit.id).earnedPromotions = 3
        AchievementTracker.cityBattleWon(unit, city)
        city.puppetCity(f.player)
        assertTrue("N08" in f.results())
        assertEquals(1, f.history.foreignCaptures.size)
    }

    @Test fun specializedPolicyProgressCannotIgnoreEarlierFourthCityOwnership() {
        val f = AchievementTestFixture()
        f.player.policies.freePolicies = 30
        f.history.maximumCities = 4
        for (name in listOf("Tradition", "Liberty")) {
            val branch = f.test.ruleset.policyBranches.getValue(name)
            f.player.policies.adopt(branch)
            branch.policies.dropLast(1).forEach { f.player.policies.adopt(it) }
        }
        assertEquals(2, f.player.policies.completedBranches.size)
        assertFalse("N22" in f.results())
        assertEquals(1, f.player.cities.size)
    }

    @Test fun twelveWondersInOneGameAndLowDifficultyGamesCannotCompleteTheCollection() {
        val service = AchievementService(temporary.newFolder())
        val f = AchievementTestFixture()
        val wonders = f.test.ruleset.buildings.values.filter { it.isWonder }.take(12).map { it.name }
        f.history.builtWonders.putAll(wonders.associateWith { "city" })
        f.state.difficulty = "Prince"
        service.record(f.game, emptySet())
        assertTrue(service.cloudFacts().v2BuiltWonders.isEmpty())
        f.state.difficulty = "King"
        service.record(f.game, emptySet())
        service.record(f.game.clone(), emptySet())
        assertEquals(1, service.cloudFacts().wonderGameIds.size)
        assertFalse("N26" in service.completed())
        for (index in 1..2) {
            val next = AchievementTestFixture()
            next.history.builtWonders[wonders.first()] = "city"
            service.record(next.game, emptySet())
            assertEquals(index == 2, "N26" in service.completed())
        }
    }

    @Test fun newBranchFactsSurviveSerializationAndDoNotShareMutableSets() {
        val f = AchievementTestFixture()
        f.history.tradePartners.add("one")
        f.history.goldenAgeWonderCities.add("city")
        f.history.goldenAgeCapturedCivilizations.add("rival")
        f.history.unit(42).clearedEncampments.add("tile")
        f.history.unit(42).majorMilitaryKills = 9
        val restored = json().fromJson(AchievementGameState::class.java, json().toJson(f.state))
        val clone = restored.clone()
        clone.history.tradePartners.clear()
        clone.history.goldenAgeWonderCities.clear()
        clone.history.goldenAgeCapturedCivilizations.clear()
        clone.history.unit(42).clearedEncampments.clear()
        assertEquals(setOf("one"), restored.history.tradePartners)
        assertEquals(setOf("city"), restored.history.goldenAgeWonderCities)
        assertEquals(setOf("rival"), restored.history.goldenAgeCapturedCivilizations)
        assertEquals(setOf("tile"), restored.history.unit(42).clearedEncampments)
        assertEquals(9, restored.history.unit(42).majorMilitaryKills)
    }
}
