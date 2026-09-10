package com.unciv.logic.achievements

import com.unciv.logic.VictoryData
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AchievementServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearService() { AchievementTracker.service = null }

    @Test fun durableOfflineQueueSurvivesRestartAndAcknowledgesOnlySuccess() {
        val f = AchievementTestFixture()
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        service.record(f.game, setOf("A01"))
        assertEquals(setOf("A01"), AchievementService(directory).pending())
        service.acknowledge(service.accountKey, setOf("A01"))
        val restarted = AchievementService(directory)
        assertEquals(setOf("A01"), restarted.completed())
        assertTrue(restarted.pending().isEmpty())
        service.record(f.game, setOf("A01"))
        assertTrue(service.pending().isEmpty())
    }

    @Test fun firstOfflineAccountIsClaimedOnceAndSwitchingNeverMovesTheQueueOrOldGame() {
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        val guestGame = AchievementTestFixture().game
        service.record(guestGame, setOf("A01"))
        val first = service.bindAccount("player-one")
        assertEquals(setOf("A01"), service.pending())
        AchievementTracker.service = service
        val ownedGame = AchievementTestFixture().game
        assertEquals(first, ownedGame.achievements!!.accountKey)
        val second = service.bindAccount("player-two")
        assertNotEquals(first, second)
        assertTrue(service.completed().isEmpty())
        service.record(guestGame, setOf("A02"))
        service.record(ownedGame, setOf("A03"))
        service.restoreCompleted(first, setOf("A04"))
        assertTrue(service.completed().isEmpty())
        service.bindAccount("player-one")
        service.record(guestGame, setOf("A02"))
        service.record(ownedGame, setOf("A03"))
        assertEquals(setOf("A01", "A02", "A03"), service.pending())
        service.acknowledge(second, setOf("A01"))
        assertTrue("A01" in service.pending())
        assertEquals(first, AchievementService(directory).accountKey)
    }

    @Test fun systemRestorationBuildsMetaAchievementWithoutInventingGameFacts() {
        val service = AchievementService(temporary.newFolder())
        val key = service.bindAccount("player")
        val ids = setOf("A11", "A12", "A13", "A14", "A15", "A16")
        service.restoreCompleted(key, ids + "A99")
        assertEquals(ids + "A39", service.completed())
        assertEquals(setOf("A39"), service.pending())
        assertTrue(service.cloudFacts().victories.isEmpty())
        assertTrue(service.cloudFacts().unlocks.isEmpty())
        service.restoreCompleted(key, emptySet())
        assertEquals(ids + "A39", service.completed())
    }

    @Test fun cloudContributionsAreDeduplicatedAndMatchDifferentCivilizationsToRoutes() {
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        val key = service.bindAccount("player")
        AchievementTracker.service = service
        for ((nation, route) in listOf("Rome" to "Scientific", "Egypt" to "Cultural",
            "China" to "Diplomatic", "Greece" to "Domination", "India" to "Scientific")) {
            val f = AchievementTestFixture(nation)
            f.state.difficulty = "Emperor"
            service.record(f.game, emptySet(), route)
            service.record(f.game, emptySet(), "Cultural")
        }
        assertEquals(setOf("A19", "A20", "A40"), service.completed())
        assertEquals(5, service.cloudFacts().victories.size)
        val other = AchievementService(temporary.newFolder())
        other.bindAccount("player")
        other.mergeCloudFacts(key, service.cloudFacts())
        other.mergeCloudFacts(key, service.cloudFacts())
        assertEquals(service.completed(), other.completed())
        assertEquals(5, other.cloudFacts().victories.size)
    }

    @Test fun permanentDisqualificationSurvivesAnEarlierSaveAndServiceRestart() {
        val directory = temporary.newFolder()
        val f = AchievementTestFixture()
        val oldBranch = f.game.clone()
        val service = AchievementService(directory)
        service.record(f.game, setOf("A01"))
        AchievementTracker.service = service
        AchievementTracker.disqualify(f.game)
        val restarted = AchievementService(directory)
        assertFalse(restarted.canContribute(oldBranch))
        restarted.record(oldBranch, setOf("A02"))
        assertEquals(setOf("A01"), restarted.completed())
    }

    @Test fun terminalOperationRecordsProcessAndVictoryBeforeClosingTheGame() {
        val directory = temporary.newFolder()
        val f = AchievementTestFixture()
        val service = AchievementService(directory)
        AchievementTracker.service = service
        f.history.completed.add("A02")
        f.game.victoryData = VictoryData(f.player.civID, "Scientific", f.game.turns)
        AchievementTracker.settle(f.game)
        assertTrue(f.state.ended)
        assertTrue("A02" in service.pending())
        assertTrue("A10" in service.pending())
        assertEquals(1, service.cloudFacts().victories.size)
        f.history.completed.add("A04")
        AchievementTracker.settle(f.game)
        assertFalse("A04" in service.pending())
    }

    @Test fun failedPersistenceNeverAcknowledgesAndRetriesTheTerminalFacts() {
        val directory = temporary.newFolder()
        val f = AchievementTestFixture()
        val service = AchievementService(directory)
        AchievementTracker.service = service
        val blocker = java.io.File(directory, "unbound")
        blocker.writeText("unavailable directory")
        f.history.completed.add("A01")
        f.game.victoryData = VictoryData(f.player.civID, "Scientific", f.game.turns)
        AchievementTracker.settle(f.game)
        assertFalse(f.state.ended)
        assertTrue(service.pending().isEmpty())
        assertTrue(blocker.delete())
        AchievementTracker.settle(f.game)
        assertTrue(f.state.ended)
        assertTrue("A01" in service.pending())
        assertTrue("A01" in AchievementService(directory).pending())
    }

    @Test fun importedAccountPathCannotReadAnotherDirectory() {
        val f = AchievementTestFixture()
        f.state.accountKey = "../../other-player"
        val service = AchievementService(temporary.newFolder())
        assertFalse(service.canContribute(f.game))
        service.record(f.game, setOf("A01"))
        assertTrue(service.pending().isEmpty())
    }
}
