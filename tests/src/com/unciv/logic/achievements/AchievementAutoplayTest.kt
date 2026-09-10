package com.unciv.logic.achievements

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.AutoPlay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*

/** Temporary autoplay acceptance tests for the user-requested achievement validation build. */
@RunWith(BaseTestRunner::class)
class AchievementAutoplayTest {
    @get:Rule val temporary = TemporaryFolder()
    @After fun clearService() { AchievementTracker.service = null }

    private fun automate(fixture: AchievementTestFixture) {
        fixture.player.policies.freePolicies = 1
        val autoplay = AutoPlay(GameSettings.GameSettingsAutoPlay().apply { fullAutoPlayAI = true })
        val screen = mock(WorldScreen::class.java)
        `when`(screen.gameInfo).thenReturn(fixture.game)
        autoplay.runAutoPlayJobInNewThread("achievement-autoplay-test", screen) {
            NextTurnAutomation.automateCivMoves(fixture.player)
        }
        runBlocking { withTimeout(10_000) { autoplay.autoPlayJob!!.join() } }
        assertFalse(autoplay.autoPlayJob!!.isCancelled)
        assertFalse(autoplay.autoPlayTurnInProgress)
        assertTrue(fixture.player.policies.getAdoptedPolicies().isNotEmpty())
    }

    @Test fun fullAutoplayCanEarnAndPersistThePolicyAchievementFromAnActualAiAction() {
        val fixture = AchievementTestFixture()
        val directory = temporary.newFolder()
        val service = AchievementService(directory)
        AchievementTracker.service = service
        assertFalse("N03" in service.completed())
        automate(fixture)
        assertFalse(fixture.state.ended)
        assertTrue("N03" in AchievementService(directory).completed())
        assertTrue("N03" in service.pending())
    }

    @Test fun godModeStillDisqualifiesAutoplayResults() {
        val fixture = AchievementTestFixture()
        val service = AchievementService(temporary.newFolder())
        AchievementTracker.service = service
        fixture.game.gameParameters.godMode = true
        automate(fixture)
        assertTrue(fixture.state.ended)
        assertTrue(service.completed().isEmpty())
        assertFalse(service.canContribute(fixture.game))
    }

    @Test fun autoplayDoesNotRestoreAPreviouslyDisqualifiedGame() {
        val fixture = AchievementTestFixture()
        val service = AchievementService(temporary.newFolder())
        AchievementTracker.service = service
        AchievementTracker.disqualify(fixture.game)
        automate(fixture)
        assertTrue(fixture.state.ended)
        assertTrue(service.completed().isEmpty())
        assertFalse(service.canContribute(fixture.game))
    }
}
