package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Durable per-Game-Center-player results. Native callbacks carry [accountKey] to reject stale sessions. */
class AchievementService(private val directory: File, private val changed: () -> Unit = {}) {
    var accountKey: String = File(directory, "last-account").takeIf { it.isFile }?.readText().orEmpty()
        private set
    private var profile = store(accountKey).load()
    private val blockedGames = HashSet<String>()

    private fun store(key: String): AchievementStore {
        require(key.isEmpty() || key.matches(Regex("[0-9a-f]{64}"))) { "Invalid achievement account key" }
        return AchievementStore(File(directory, key.ifEmpty { "unbound" }))
    }

    @Synchronized fun bindAccount(gamePlayerId: String): String {
        require(gamePlayerId.isNotBlank())
        val key = playerKey(gamePlayerId)
        val guestStore = store("")
        var guest = guestStore.load()
        if (guest.claimedByAccount.isEmpty()) guest = guestStore.update { it.claimedByAccount = key }
        val target = store(key)
        val next = if (guest.claimedByAccount == key) target.update { current ->
            val merged = current.mergedWith(guest)
            current.unlocks = merged.unlocks
            current.victories = merged.victories
            current.disqualifiedGames = merged.disqualifiedGames
            addCollections(current)
        } else target.load()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create achievement directory")
        val temporary = File(directory, "last-account.tmp")
        FileOutputStream(temporary).use { it.write(key.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if (!temporary.renameTo(File(directory, "last-account"))) throw IOException("Cannot persist achievement account")
        accountKey = key
        profile = next
        changed()
        return key
    }

    private fun ownerForGame(game: GameInfo): String {
        val owner = game.achievements?.accountKey.orEmpty()
        return if (owner.isNotEmpty()) owner else store("").load().claimedByAccount
    }

    @Synchronized fun canContribute(game: GameInfo): Boolean {
        val state = game.achievements ?: return false
        if (state.accountKey.isNotEmpty() && !state.accountKey.matches(Regex("[0-9a-f]{64}"))) return false
        return game.gameId !in blockedGames && ownerForGame(game) == accountKey && state.canContribute(game, profile)
    }

    @Synchronized fun record(game: GameInfo, ids: Set<String>, victoryRoute: String? = null) {
        val state = game.achievements ?: return
        if (!canContribute(game)) return
        val victoryQualifies = victoryRoute in AchievementCatalog.victoryRoutes &&
            AchievementCatalog.ineligibility(AchievementCatalog.byId.getValue("A19"), state) == null
        if (ids.all { it in profile.unlocks } && (!victoryQualifies || game.gameId in profile.victories)) return
        fun record(id: String = "") = AchievementRecord().apply {
            persistedAt = System.currentTimeMillis()
            gameId = game.gameId
            civilization = state.civilization
            difficulty = state.difficulty
            achievementId = id
            this.victoryRoute = victoryRoute.orEmpty()
            turn = game.turns
            catalogVersion = state.catalogVersion
            ruleVersion = 1
        }
        profile = store(accountKey).update { next ->
            ids.forEach { next.recordUnlock(record(it)) }
            if (victoryQualifies) next.recordVictory(record())
            addCollections(next)
        }
        changed()
    }

    @Synchronized fun disqualify(game: GameInfo) {
        if (game.achievements == null) return
        blockedGames.add(game.gameId)
        val owner = ownerForGame(game)
        if (owner.isNotEmpty() && !owner.matches(Regex("[0-9a-f]{64}"))) return
        val updated = store(owner).update { it.disqualifiedGames.add(game.gameId) }
        if (owner == accountKey) { profile = updated; changed() }
    }

    @Synchronized fun pending(): Set<String> = profile.unlocks.keys.intersect(AchievementCatalog.byId.keys) - profile.reportedIds
    @Synchronized fun completed(): Set<String> = profile.unlocks.keys.intersect(AchievementCatalog.byId.keys)
    @Synchronized fun isBoundTo(gamePlayerId: String): Boolean = accountKey == playerKey(gamePlayerId)

    private fun playerKey(gamePlayerId: String) = MessageDigest.getInstance("SHA-256")
        .digest(gamePlayerId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized fun acknowledge(key: String, ids: Set<String>) {
        if (key != accountKey) return
        profile = store(key).update { it.reportedIds.addAll(ids.intersect(it.unlocks.keys)) }
    }

    @Synchronized fun restoreCompleted(key: String, ids: Set<String>) {
        if (key != accountKey) return
        val known = ids.intersect(AchievementCatalog.byId.keys)
        if (known.all { it in profile.unlocks && it in profile.reportedIds }) return
        profile = store(key).update { next ->
            for (id in known) {
                if (id !in next.unlocks) next.recordUnlock(AchievementRecord().apply {
                    achievementId = id
                    catalogVersion = AchievementCatalog.version
                    ruleVersion = 1
                    // No fabricated game, date, civilization or difficulty for a Game Center restoration.
                })
                next.reportedIds.add(id)
            }
            addCollections(next)
        }
        changed()
    }

    /** Only custom facts, not game saves or Game Center's completed-state cache. */
    @Synchronized fun cloudFacts(): AchievementProfile = AchievementProfile().also {
        it.victories.putAll(profile.victories)
        it.disqualifiedGames.addAll(profile.disqualifiedGames)
    }

    @Synchronized fun mergeCloudFacts(key: String, incoming: AchievementProfile) {
        if (key != accountKey) return
        val combined = profile.mergedWith(AchievementProfile().apply {
            victories.putAll(incoming.victories)
            disqualifiedGames.addAll(incoming.disqualifiedGames)
        })
        if (combined.victories == profile.victories && combined.disqualifiedGames == profile.disqualifiedGames) return
        profile = store(key).update { next ->
            val facts = AchievementProfile().apply {
                victories.putAll(incoming.victories)
                disqualifiedGames.addAll(incoming.disqualifiedGames)
            }
            val merged = next.mergedWith(facts)
            next.victories = merged.victories
            next.disqualifiedGames = merged.disqualifiedGames
            addCollections(next)
        }
        changed()
    }

    private fun addCollections(profile: AchievementProfile) {
        val fulfilled = listOf(
            "A19" to (profile.collectedCivilizations().size >= 5),
            "A20" to (profile.matchedVictoryRoutes("A20") == 4),
            "A39" to (profile.completedCivilizationChallenges().size >= 6),
            "A40" to (profile.matchedVictoryRoutes("A40") == 4)
        )
        for ((id, complete) in fulfilled) if (complete) profile.recordUnlock(AchievementRecord().apply {
            achievementId = id
            persistedAt = System.currentTimeMillis()
            catalogVersion = AchievementCatalog.version
            ruleVersion = 1
        })
    }
}
