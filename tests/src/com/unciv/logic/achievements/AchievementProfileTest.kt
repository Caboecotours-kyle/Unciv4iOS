package com.unciv.logic.achievements

import com.unciv.json.json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AchievementProfileTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun record(game: String, civ: String = "Rome", route: String = "Scientific", time: Long = 100) = AchievementRecord().apply {
        gameId = game
        civilization = civ
        victoryRoute = route
        persistedAt = time
        difficulty = "Prince"
        catalogVersion = AchievementCatalog.version
        ruleVersion = 1
        shareTemplateVersion = 1
    }

    @Test fun repeatedUnlockKeepsOriginalEvidenceAndDeliveryState() {
        val profile = AchievementProfile()
        val first = record("one").apply { achievementId = "A01"; facts["kills"] = "3" }
        assertTrue(profile.recordUnlock(first))
        profile.reportedIds.add("A01")
        assertFalse(profile.recordUnlock(record("two").apply { achievementId = "A01" }))
        assertEquals("one", AchievementProfile.decode(profile.unlocks.getValue("A01")).gameId)
        assertEquals("3", AchievementProfile.decode(profile.unlocks.getValue("A01")).facts["kills"])
        assertTrue("A01" in profile.reportedIds)
        first.facts["kills"] = "999"
        assertEquals("3", AchievementProfile.decode(profile.unlocks.getValue("A01")).facts["kills"])
    }

    @Test fun localVictoryUsesFirstPersistedBranchAndCopiesCannotIncreaseCount() {
        val profile = AchievementProfile()
        assertTrue(profile.recordVictory(record("same", route = "Cultural", time = 200)))
        assertFalse(profile.recordVictory(record("same", route = "Scientific", time = 1)))
        assertEquals("Cultural", profile.victoryRecords("A20").single().victoryRoute)
        assertEquals(1, profile.mergedWith(profile.clone()).victories.size)
    }

    @Test fun cloudVictoryConflictConvergesByTimeThenIdInEitherOrder() {
        val a = AchievementProfile().apply { recordVictory(record("same", route = "Scientific", time = 200)) }
        val b = AchievementProfile().apply { recordVictory(record("same", route = "Cultural", time = 100)) }
        assertEquals(a.mergedWith(b).victories, b.mergedWith(a).victories)
        assertEquals("Cultural", a.mergedWith(b).victoryRecords("A20").single().victoryRoute)
        val c = AchievementProfile().apply {
            recordVictory(record("same", route = "Domination", time = 100).apply { recordId = "0000" })
        }
        assertEquals("Domination", b.mergedWith(c).victoryRecords("A20").single().victoryRoute)
        assertEquals(a.mergedWith(b).mergedWith(c).victories, a.mergedWith(b.mergedWith(c)).victories)
    }

    @Test fun cloudUnionKeepsUnlocksDisqualificationsAndLocalDeliveryState() {
        val a = AchievementProfile().apply {
            recordUnlock(record("one").apply { achievementId = "A01" })
            reportedIds.add("A01")
            disqualifiedGames.add("invalid-one")
        }
        val b = AchievementProfile().apply {
            recordUnlock(record("two").apply { achievementId = "A02" })
            disqualifiedGames.add("invalid-two")
        }
        val merged = a.mergedWith(b)
        assertEquals(setOf("A01", "A02"), merged.unlocks.keys)
        assertEquals(setOf("invalid-one", "invalid-two"), merged.disqualifiedGames)
        assertEquals(setOf("A01"), merged.reportedIds)
        merged.disqualifiedGames.clear()
        assertEquals(setOf("invalid-one"), a.disqualifiedGames)
    }

    @Test fun unknownAchievementsAndUnknownRecordFieldsSurviveRoundTripAndMerge() {
        val future = """{"recordId":"future","persistedAt":50,"achievementId":"A99","futureEvidence":{"nested":[1,2,3]}}"""
        val remote = AchievementProfile().apply { unlocks["A99"] = future }
        val restored = json().fromJson(AchievementProfile::class.java, json().toJson(remote))
        val merged = AchievementProfile().mergedWith(restored)
        assertEquals(future, merged.unlocks["A99"])
        assertTrue(merged.completedCivilizationChallenges().isEmpty())
        assertTrue(merged.reportedIds.isEmpty())
    }

    @Test fun fourCivsAndFourRoutesAreInsufficientWithoutDistinctPairing() {
        val profile = AchievementProfile()
        for ((index, route) in AchievementCatalog.victoryRoutes.withIndex())
            profile.recordVictory(record("rome-$index", route = route))
        for (civ in listOf("Greece", "China", "Egypt"))
            profile.recordVictory(record(civ, civ, "Scientific"))
        assertEquals(4, profile.collectedCivilizations().size)
        assertEquals(2, profile.matchedVictoryRoutes("A20"))
        profile.recordVictory(record("china-culture", "China", "Cultural"))
        profile.recordVictory(record("egypt-domination", "Egypt", "Domination"))
        assertEquals(4, profile.matchedVictoryRoutes("A20"))
        assertEquals(0, profile.matchedVictoryRoutes("A40"))
    }

    @Test fun emperorCollectionChecksEachContributionAndCatalogEpoch() {
        val profile = AchievementProfile()
        for ((index, route) in AchievementCatalog.victoryRoutes.withIndex())
            profile.recordVictory(record("game-$index", "civ-$index", route).apply { difficulty = "Emperor" })
        assertEquals(4, profile.matchedVictoryRoutes("A40"))
        val older = AchievementProfile().apply {
            recordVictory(record("old").apply { catalogVersion = 0 })
        }
        assertTrue(older.collectedCivilizations().isEmpty())
        val missingVersion = AchievementProfile.decode("{recordId:old,gameId:old,civilization:Rome,difficulty:Prince,victoryRoute:Scientific}")
        assertEquals(0, missingVersion.catalogVersion)
    }

    @Test fun civilizationCollectionUsesTheExplicitTwelveItemWhitelist() {
        val profile = AchievementProfile()
        for (id in listOf("A11", "A12", "A13", "A14", "A15", "A16", "A01", "A21"))
            profile.recordUnlock(record(id).apply { achievementId = id })
        assertEquals(setOf("Egypt", "Babylon", "China", "Persia", "India", "Greece"), profile.completedCivilizationChallenges())
    }

    @Test fun durableStoreSurvivesRestartAndDoesNotAliasItsReturnedSnapshot() {
        val directory = temporary.newFolder()
        val store = AchievementStore(directory)
        val result = store.update { it.recordUnlock(record("game").apply { achievementId = "A01" }) }
        result.unlocks.clear()
        assertTrue("A01" in AchievementStore(directory).load().unlocks)
        assertFalse(File(directory, "pending.json").exists())
    }

    @Test fun interruptedCommitReplaysPendingStateWithoutLosingPriorAwards() {
        val directory = temporary.newFolder()
        val store = AchievementStore(directory)
        store.update { it.recordUnlock(record("one").apply { achievementId = "A01" }) }
        val pending = store.load().apply { recordUnlock(record("two").apply { achievementId = "A02" }) }
        File(directory, "pending.json").writeText(json().toJson(pending))
        assertEquals(setOf("A01", "A02"), AchievementStore(directory).load().unlocks.keys)
        assertEquals(setOf("A01", "A02"), AchievementStore(directory).load().unlocks.keys)
    }

    @Test fun failedCommitDoesNotReturnSuccessAndCanRecoverAfterRestart() {
        val directory = temporary.newFolder()
        val destination = File(directory, "profile.json")
        val obstruction = File(destination, "obstruction")
        try {
            AchievementStore(directory).update {
                it.recordUnlock(record("one").apply { achievementId = "A01" })
                // Obstruct the final rename after the previous profile was successfully loaded.
                assertTrue(destination.mkdir())
                obstruction.writeText("keep")
            }
            fail("A failed disk commit must not announce success")
        } catch (_: IOException) {
            assertTrue(File(directory, "pending.json").exists())
        }
        assertTrue(obstruction.delete())
        assertTrue(destination.delete())
        assertTrue("A01" in AchievementStore(directory).load().unlocks)
    }

    @Test fun malformedCommittedOrPendingDataIsNotSilentlyReplacedWithEmptyHistory() {
        val directory = temporary.newFolder()
        File(directory, "profile.json").writeText("broken")
        try {
            AchievementStore(directory).load()
            fail("Must retain unreadable history")
        } catch (_: IOException) {
            assertEquals("broken", File(directory, "profile.json").readText())
        }
        File(directory, "profile.json").writeText(json().toJson(AchievementProfile()))
        File(directory, "pending.json").writeText("broken")
        try {
            AchievementStore(directory).load()
            fail("Must retain pending evidence")
        } catch (_: IOException) {
            assertEquals("broken", File(directory, "pending.json").readText())
        }
    }

    @Test fun partialStagingFileIsNotAcceptedAsACommittedAward() {
        val directory = temporary.newFolder()
        File(directory, "pending.tmp").writeText("{unlocks:")
        assertTrue(AchievementStore(directory).load().unlocks.isEmpty())
    }
}
