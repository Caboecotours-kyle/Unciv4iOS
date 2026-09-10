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
        assertEquals("Cultural", AchievementProfile.decode(profile.victories.values.single()).victoryRoute)
        assertEquals(1, profile.mergedWith(profile.clone()).victories.size)
    }

    @Test fun cloudVictoryConflictConvergesByTimeThenIdInEitherOrder() {
        val a = AchievementProfile().apply { recordVictory(record("same", route = "Scientific", time = 200)) }
        val b = AchievementProfile().apply { recordVictory(record("same", route = "Cultural", time = 100)) }
        assertEquals(a.mergedWith(b).victories, b.mergedWith(a).victories)
        assertEquals("Cultural", AchievementProfile.decode(a.mergedWith(b).victories.values.single()).victoryRoute)
        val c = AchievementProfile().apply {
            recordVictory(record("same", route = "Domination", time = 100).apply { recordId = "0000" })
        }
        assertEquals("Domination", AchievementProfile.decode(b.mergedWith(c).victories.values.single()).victoryRoute)
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
        assertTrue(merged.reportedIds.isEmpty())
    }

    @Test fun wonderCollectionUnionIsIdempotentAndDoesNotAliasSnapshots() {
        val a = AchievementProfile().apply { v2BuiltWonders.addAll(listOf("The Pyramids", "Stonehenge")) }
        val b = AchievementProfile().apply { v2BuiltWonders.addAll(listOf("Stonehenge", "The Oracle")) }
        val merged = a.mergedWith(b)
        assertEquals(a.mergedWith(b).v2BuiltWonders, b.mergedWith(a).v2BuiltWonders)
        assertEquals(3, merged.mergedWith(merged).v2BuiltWonders.size)
        merged.v2BuiltWonders.clear()
        assertEquals(2, a.v2BuiltWonders.size)
        val restored = json().fromJson(AchievementProfile::class.java, json().toJson(a))
        assertEquals(a.v2BuiltWonders, restored.v2BuiltWonders)
        assertTrue(json().fromJson(AchievementProfile::class.java, "{storageVersion:1}").v2BuiltWonders.isEmpty())
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
