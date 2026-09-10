package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset

enum class AchievementTier { Introductory, Advanced, Challenge, Expert, Pinnacle }

data class AchievementDefinition(
    val id: String,
    val tier: AchievementTier,
    val minimumDifficulty: String? = "Prince",
    val civilization: String? = null,
    val mapType: String? = null,
    val oneCityChallenge: Boolean = false,
    val minimumCityStates: Int = 0,
    val requiredUnit: String? = null,
    val isCollection: Boolean = false,
    val introducedIn: Int = 1,
    val ruleVersion: Int = 1
) {
    // Avoid the generated Boolean.hashCode(boolean), which RoboVM does not provide.
    override fun hashCode(): Int = id.hashCode()
}

/** Stable rule identities. Display strings and artwork are separate from eligibility. */
object AchievementCatalog {
    const val version = 1

    // Version 0 development saves remain ineligible; version 1 records the complete source history.
    const val recordingVersion = 1
    const val firstCompleteRecordingVersion = 1

    val difficulties = listOf("Settler", "Chieftain", "Warlord", "Prince", "King", "Emperor", "Immortal", "Deity")
    val victoryRoutes = setOf("Scientific", "Cultural", "Domination", "Diplomatic")

    val definitions = listOf(
        AchievementDefinition("A01", AchievementTier.Introductory, null),
        AchievementDefinition("A02", AchievementTier.Advanced, null),
        AchievementDefinition("A03", AchievementTier.Introductory, null),
        AchievementDefinition("A04", AchievementTier.Introductory, null),
        AchievementDefinition("A05", AchievementTier.Challenge, oneCityChallenge = true),
        AchievementDefinition("A06", AchievementTier.Challenge),
        AchievementDefinition("A07", AchievementTier.Advanced),
        AchievementDefinition("A08", AchievementTier.Advanced),
        AchievementDefinition("A09", AchievementTier.Challenge),
        AchievementDefinition("A10", AchievementTier.Advanced),
        AchievementDefinition("A11", AchievementTier.Challenge, civilization = "Egypt"),
        AchievementDefinition("A12", AchievementTier.Advanced, civilization = "Babylon"),
        AchievementDefinition("A13", AchievementTier.Introductory, civilization = "China", requiredUnit = "Chu-Ko-Nu"),
        AchievementDefinition("A14", AchievementTier.Advanced, civilization = "Persia"),
        AchievementDefinition("A15", AchievementTier.Challenge, civilization = "India"),
        AchievementDefinition("A16", AchievementTier.Advanced, civilization = "Greece", minimumCityStates = 8),
        AchievementDefinition("A17", AchievementTier.Challenge, mapType = MapType.twoContinents),
        AchievementDefinition("A18", AchievementTier.Advanced, mapType = MapType.archipelago),
        AchievementDefinition("A19", AchievementTier.Challenge, isCollection = true),
        AchievementDefinition("A20", AchievementTier.Challenge, isCollection = true),
        AchievementDefinition("A21", AchievementTier.Expert, "Emperor"),
        AchievementDefinition("A22", AchievementTier.Expert, "Deity"),
        AchievementDefinition("A23", AchievementTier.Advanced, null),
        AchievementDefinition("A24", AchievementTier.Introductory, null),
        AchievementDefinition("A25", AchievementTier.Challenge),
        AchievementDefinition("A26", AchievementTier.Challenge),
        AchievementDefinition("A27", AchievementTier.Expert),
        AchievementDefinition("A28", AchievementTier.Advanced, oneCityChallenge = true),
        AchievementDefinition("A29", AchievementTier.Challenge),
        AchievementDefinition("A30", AchievementTier.Advanced),
        AchievementDefinition("A31", AchievementTier.Advanced, civilization = "England", requiredUnit = "Ship of the Line"),
        AchievementDefinition("A32", AchievementTier.Introductory, civilization = "Rome"),
        AchievementDefinition("A33", AchievementTier.Advanced, civilization = "Korea"),
        AchievementDefinition("A34", AchievementTier.Expert, civilization = "Japan", requiredUnit = "Samurai"),
        AchievementDefinition("A35", AchievementTier.Advanced, civilization = "Mongolia", requiredUnit = "Keshik"),
        AchievementDefinition("A36", AchievementTier.Challenge, civilization = "Carthage"),
        AchievementDefinition("A37", AchievementTier.Pinnacle, "Deity", oneCityChallenge = true),
        AchievementDefinition("A38", AchievementTier.Pinnacle, "Deity"),
        AchievementDefinition("A39", AchievementTier.Expert, isCollection = true),
        AchievementDefinition("A40", AchievementTier.Expert, "Emperor", isCollection = true)
    )
    val byId = definitions.associateBy { it.id }
    fun gameCenterId(id: String): String {
        require(id in byId)
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
        definition.id !in state.availableIds -> "Start a new game to attempt this achievement"
        !meetsDifficulty(state.difficulty, definition.minimumDifficulty) -> "The difficulty is too low"
        definition.minimumDifficulty != null && state.aiOpponents < 3 -> "At least three AI opponents are required"
        definition.minimumDifficulty != null && !state.enabledVictories.containsAll(victoryRoutes) -> "All four major victory routes are required"
        definition.civilization != null && definition.civilization != state.civilization -> "A different civilization is required"
        definition.mapType != null && definition.mapType != state.mapType -> "A different map type is required"
        definition.oneCityChallenge && !state.oneCityChallenge -> "One City Challenge is required"
        definition.minimumCityStates > state.cityStates -> "More starting city-states are required"
        definition.requiredUnit != null && definition.requiredUnit !in state.availableUnits -> "This ruleset does not support the required unit"
        definition.id == "A17" && state.targetLandmasses.size < 2 -> "Two separate landmasses are required"
        definition.id == "A36" && state.baseRuleset != BaseRuleset.Civ_V_GnK.fullName -> "This ruleset does not support the required ability"
        else -> null
    }
}
