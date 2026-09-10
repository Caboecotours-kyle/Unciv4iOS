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
    val isCollection: Boolean = false,
    val minimumOpponents: Int = 3
) {
    // Avoid the generated Boolean.hashCode(boolean), which RoboVM does not provide.
    override fun hashCode(): Int = id.hashCode()
}

/** Stable rule identities. Display strings and artwork are separate from eligibility. */
object AchievementCatalog {
    const val version = 3
    const val localPreview = true

    // V1/V2 saves lack the source facts required by this preview. Never upgrade them on load.
    const val recordingVersion = 3
    const val firstCompleteRecordingVersion = 3

    val difficulties = listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity")
    val victoryRoutes = setOf("Scientific", "Cultural", "Domination", "Diplomatic", "Time")

    val definitions = listOf(
        AchievementDefinition("N01", AchievementTier.Simple),
        AchievementDefinition("N02", AchievementTier.Simple),
        AchievementDefinition("N03", AchievementTier.Simple),
        AchievementDefinition("N04", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N05", AchievementTier.Simple),
        AchievementDefinition("N06", AchievementTier.Simple),
        AchievementDefinition("N07", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N08", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N09", AchievementTier.Simple),
        AchievementDefinition("N10", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N11", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N12", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N13", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N14", AchievementTier.Intermediate, minimumDifficulty = "Prince", requiresReligion = true),
        AchievementDefinition("N15", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N16", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N17", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N18", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N19", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N20", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N21", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N22", AchievementTier.Intermediate, minimumDifficulty = "Prince"),
        AchievementDefinition("N23", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N24", AchievementTier.Hard, minimumDifficulty = "King", requiresReligion = true),
        AchievementDefinition("N25", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N26", AchievementTier.Hard, minimumDifficulty = "King", isCollection = true),
        AchievementDefinition("N27", AchievementTier.Hard, minimumDifficulty = "King", civilization = "Egypt"),
        AchievementDefinition("N28", AchievementTier.Hard, minimumDifficulty = "King", civilization = "Rome"),
        AchievementDefinition("N29", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N30", AchievementTier.Extreme, minimumDifficulty = "Emperor", minimumOpponents = 4),
        AchievementDefinition("N31", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N32", AchievementTier.Hard, minimumDifficulty = "King"),
        AchievementDefinition("N33", AchievementTier.Hard, minimumDifficulty = "King", requiresReligion = true),
        AchievementDefinition("N34", AchievementTier.Extreme, minimumDifficulty = "Emperor", civilization = "China", requiredUnit = "Chu-Ko-Nu", minimumOpponents = 4),
        AchievementDefinition("N35", AchievementTier.Extreme, minimumDifficulty = "Emperor", civilization = "Persia", minimumOpponents = 4),
        AchievementDefinition("N36", AchievementTier.Extreme, minimumDifficulty = "Emperor", minimumOpponents = 4),
        AchievementDefinition("N37", AchievementTier.Extreme, minimumDifficulty = "Deity", minimumOpponents = 4),
        AchievementDefinition("N38", AchievementTier.Extreme, minimumDifficulty = "Emperor", oneCityChallenge = true, minimumOpponents = 4),
        AchievementDefinition("N39", AchievementTier.Extreme, minimumDifficulty = "Emperor", minimumOpponents = 4),
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
        definition.minimumDifficulty != null && state.aiOpponents < definition.minimumOpponents -> "Too few AI opponents"
        definition.civilization != null && definition.civilization != state.civilization -> "A different civilization is required"
        definition.oneCityChallenge && !state.oneCityChallenge -> "One City Challenge is required"
        definition.requiredUnit != null && definition.requiredUnit !in state.availableUnits -> "This ruleset does not support the required unit"
        definition.requiresReligion && !state.religionEnabled -> "Religion must be enabled"
        else -> null
    }
}
