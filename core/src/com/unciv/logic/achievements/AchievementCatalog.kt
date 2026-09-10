package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset

enum class AchievementTier { Simple, Intermediate, Hard, Extreme }

data class AchievementDefinition(
    val id: String,
    val tier: AchievementTier,
    val minimumDifficulty: String? = null,
    val civilization: String? = null,
    val oneCityChallenge: Boolean = false,
    val requiredUnit: String? = null,
    val requiresReligion: Boolean = false,
    val isCollection: Boolean = false
) {
    // Avoid the generated Boolean.hashCode(boolean), which RoboVM does not provide.
    override fun hashCode(): Int = id.hashCode()
}

/** Stable rule identities. Display strings and artwork are separate from eligibility. */
object AchievementCatalog {
    const val version = 2

    // V1 saves lack the first-action and religion facts required by this catalog. Never upgrade on load.
    const val recordingVersion = 2
    const val firstCompleteRecordingVersion = 2

    val difficulties = listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity")
    val victoryRoutes = setOf("Scientific", "Cultural", "Domination", "Diplomatic", "Time")

    val definitions = listOf(
        AchievementDefinition("N01", AchievementTier.Simple),
        AchievementDefinition("N02", AchievementTier.Simple),
        AchievementDefinition("N03", AchievementTier.Simple),
        AchievementDefinition("N04", AchievementTier.Simple),
        AchievementDefinition("N05", AchievementTier.Simple),
        AchievementDefinition("N06", AchievementTier.Simple),
        AchievementDefinition("N07", AchievementTier.Simple),
        AchievementDefinition("N08", AchievementTier.Simple),
        AchievementDefinition("N09", AchievementTier.Simple),
        AchievementDefinition("N10", AchievementTier.Simple),
        AchievementDefinition("N11", AchievementTier.Simple),
        AchievementDefinition("N12", AchievementTier.Simple),
        AchievementDefinition("N13", AchievementTier.Simple),
        AchievementDefinition("N14", AchievementTier.Simple, requiresReligion = true),
        AchievementDefinition("N15", AchievementTier.Simple),
        AchievementDefinition("N16", AchievementTier.Simple),
        AchievementDefinition("N17", AchievementTier.Intermediate),
        AchievementDefinition("N18", AchievementTier.Intermediate),
        AchievementDefinition("N19", AchievementTier.Intermediate),
        AchievementDefinition("N20", AchievementTier.Intermediate),
        AchievementDefinition("N21", AchievementTier.Intermediate),
        AchievementDefinition("N22", AchievementTier.Intermediate),
        AchievementDefinition("N23", AchievementTier.Intermediate),
        AchievementDefinition("N24", AchievementTier.Intermediate, requiresReligion = true),
        AchievementDefinition("N25", AchievementTier.Intermediate),
        AchievementDefinition("N26", AchievementTier.Intermediate, isCollection = true),
        AchievementDefinition("N27", AchievementTier.Intermediate, civilization = "Egypt"),
        AchievementDefinition("N28", AchievementTier.Intermediate, civilization = "Rome"),
        AchievementDefinition("N29", AchievementTier.Hard),
        AchievementDefinition("N30", AchievementTier.Hard, minimumDifficulty = "Prince"),
        AchievementDefinition("N31", AchievementTier.Hard),
        AchievementDefinition("N32", AchievementTier.Hard),
        AchievementDefinition("N33", AchievementTier.Hard, requiresReligion = true),
        AchievementDefinition("N34", AchievementTier.Hard, civilization = "China", requiredUnit = "Chu-Ko-Nu"),
        AchievementDefinition("N35", AchievementTier.Hard, civilization = "Persia"),
        AchievementDefinition("N36", AchievementTier.Hard, minimumDifficulty = "Emperor"),
        AchievementDefinition("N37", AchievementTier.Extreme, minimumDifficulty = "Deity"),
        AchievementDefinition("N38", AchievementTier.Extreme, minimumDifficulty = "Emperor", oneCityChallenge = true),
        AchievementDefinition("N39", AchievementTier.Extreme, minimumDifficulty = "Emperor"),
        AchievementDefinition("N40", AchievementTier.Extreme, isCollection = true)
    )
    val byId = definitions.associateBy { it.id }

    // Retired V1 awards remain readable, but only the independent V2 catalog is submitted.
    val legacyIds = (1..40).mapTo(HashSet()) { "A%02d".format(it) }
    val reportableIds = byId.keys
    val knownIds = reportableIds + legacyIds
    fun gameCenterId(id: String): String {
        require(id in reportableIds) { "This achievement has no Game Center mapping" }
        return "com.aishuati.unciv.achievement.${id.lowercase()}"
    }
    val civilizationAchievements = definitions.filter { it.civilization != null }.associate { it.id to it.civilization!! }

    fun meetsDifficulty(actual: String, minimum: String?): Boolean {
        val rank = difficulties.indexOf(actual)
        if (rank < 0) return false
        if (minimum == null) return true
        val minimumRank = difficulties.indexOf(minimum)
        return minimumRank >= 0 && rank >= minimumRank
    }

    /** Only called for a newly created game, before any save/load or compatibility migration. */
    fun newGameIneligibility(game: GameInfo, generatedMap: Boolean, hadMods: Boolean): String? {
        val parameters = game.gameParameters
        return when {
            hadMods || parameters.mods.isNotEmpty() -> "Mods are not supported"
            BaseRuleset.entries.none { it.fullName == parameters.baseRuleset } -> "A built-in ruleset is required"
            parameters.isOnlineMultiplayer -> "Single-player is required"
            game.civilizations.count { it.isHuman() } != 1 -> "Exactly one human player is required"
            game.civilizations.none { it.isHuman() && it.isMajorCiv() } -> "Spectators cannot earn achievements"
            game.civilizations.none { it.isAI() && it.isMajorCiv() } -> "At least one AI opponent is required"
            !generatedMap || game.tileMap.mapParameters.type !in MapType.allValues -> "A generated map is required"
            parameters.startingEra != "Ancient era" -> "An Ancient era start is required"
            parameters.godMode -> "God mode is not supported"
            game.simulateUntilWin -> "AI simulation is not supported"
            game.difficulty !in difficulties -> "A built-in difficulty is required"
            else -> null
        }
    }

    /** Initial per-achievement applicability; ordinary branch history is checked by its evaluator. */
    fun ineligibility(definition: AchievementDefinition, state: AchievementGameState): String? = when {
        state.aiOpponents < 1 -> "At least one AI opponent is required"
        definition.id !in state.availableIds -> "Start a new game to attempt this achievement"
        !meetsDifficulty(state.difficulty, definition.minimumDifficulty) -> "The difficulty is too low"
        definition.minimumDifficulty != null && state.aiOpponents < 3 -> "At least three AI opponents are required"
        definition.civilization != null && definition.civilization != state.civilization -> "A different civilization is required"
        definition.oneCityChallenge && !state.oneCityChallenge -> "One City Challenge is required"
        definition.requiredUnit != null && definition.requiredUnit !in state.availableUnits -> "This ruleset does not support the required unit"
        definition.requiresReligion && !state.religionEnabled -> "Religion must be enabled"
        else -> null
    }
}
