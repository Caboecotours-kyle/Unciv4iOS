package com.unciv.logic.achievements

import com.unciv.json.json
import java.util.UUID

/** Immutable once submitted. The enclosing profile stores its original JSON, including future fields. */
class AchievementRecord {
    var recordId = UUID.randomUUID().toString()
    var persistedAt = 0L
    var gameId = ""
    var civilization = ""
    var difficulty = ""
    var victoryRoute = ""
    var achievementId = ""
    var turn = 0
    // Fixed missing-field defaults must not grant older records a newer catalog epoch.
    var catalogVersion = 0
    var ruleVersion = 0
    var shareTemplateVersion = 0
    var facts = HashMap<String, String>()
}

/** Independent of GameInfo: loading an earlier game cannot remove an award or a disqualification. */
class AchievementProfile {
    var storageVersion = 1
    var unlocks = HashMap<String, String>()
    var victories = HashMap<String, String>()
    var disqualifiedGames = HashSet<String>()
    var reportedIds = HashSet<String>()
    var claimedByAccount = ""

    fun clone() = AchievementProfile().also {
        it.storageVersion = storageVersion
        it.unlocks.putAll(unlocks)
        it.victories.putAll(victories)
        it.disqualifiedGames.addAll(disqualifiedGames)
        it.reportedIds.addAll(reportedIds)
        it.claimedByAccount = claimedByAccount
    }

    fun recordUnlock(record: AchievementRecord): Boolean {
        require(record.achievementId in AchievementCatalog.byId)
        if (record.achievementId in unlocks) return false
        unlocks[record.achievementId] = json().toJson(record)
        return true
    }

    /** This device keeps its first persisted candidate, even if a later branch wins differently. */
    fun recordVictory(record: AchievementRecord): Boolean {
        require(record.gameId.isNotEmpty() && record.victoryRoute in AchievementCatalog.victoryRoutes)
        if (record.gameId in victories) return false
        victories[record.gameId] = json().toJson(record)
        return true
    }

    /** Commutative cloud conflict resolution. Delivery acknowledgements remain local. */
    fun mergedWith(other: AchievementProfile) = clone().apply {
        storageVersion = maxOf(storageVersion, other.storageVersion)
        mergeRecords(unlocks, other.unlocks)
        mergeRecords(victories, other.victories)
        disqualifiedGames.addAll(other.disqualifiedGames)
    }

    fun victoryRecords(achievementId: String): List<AchievementRecord> {
        val definition = AchievementCatalog.byId.getValue(achievementId)
        return victories.values.map(::decode).filter {
            it.catalogVersion >= definition.introducedIn &&
                it.victoryRoute in AchievementCatalog.victoryRoutes &&
                AchievementCatalog.meetsDifficulty(it.difficulty, definition.minimumDifficulty)
        }
    }

    fun collectedCivilizations() = victoryRecords("A19").map { it.civilization }.toSet()

    fun completedCivilizationChallenges() = unlocks.keys
        .mapNotNull { AchievementCatalog.civilizationAchievements[it] }.toSet()

    /** Maximum matching of real civilization-route pairs, not independent totals. */
    fun matchedVictoryRoutes(achievementId: String): Int {
        val candidates = victoryRecords(achievementId).groupBy { it.victoryRoute }
        val routes = AchievementCatalog.victoryRoutes.toList()
        fun match(index: Int, used: Set<String>): Int {
            if (index == routes.size) return 0
            var best = match(index + 1, used)
            for (civ in candidates[routes[index]].orEmpty().map { it.civilization }.distinct()) {
                if (civ !in used) best = maxOf(best, 1 + match(index + 1, used + civ))
            }
            return best
        }
        return match(0, emptySet())
    }

    companion object {
        fun decode(value: String): AchievementRecord = json().fromJson(AchievementRecord::class.java, value)

        private fun mergeRecords(target: HashMap<String, String>, incoming: HashMap<String, String>) {
            for ((key, value) in incoming) {
                val previous = target[key]
                if (previous == null || compareRecords(value, previous) < 0) target[key] = value
            }
        }

        private fun compareRecords(a: String, b: String): Int {
            val left = decode(a)
            val right = decode(b)
            return compareValuesBy(left, right, { it.persistedAt }, { it.recordId })
                .takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}
