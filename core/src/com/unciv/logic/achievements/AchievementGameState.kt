package com.unciv.logic.achievements

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.map.MapType
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile

/** Branch-local facts only. Permanent unlocks and disqualifications belong in the profile. */
class AchievementGameState : IsPartOfGameInfoSerialization {
    // Fixed defaults: Gdx omits default-valued fields, so loading must never upgrade a marker.
    var storageVersion = 0
    var catalogVersion = 0
    var recordingVersion = 0
    var gameId = ""
    var playerId = ""
    var civilization = ""
    var difficulty = ""
    var baseRuleset = ""
    var speed = ""
    var mapType = ""
    var aiOpponents = 0
    var cityStates = 0
    var oneCityChallenge = false
    var availableIds = HashSet<String>()
    var enabledVictories = HashSet<String>()
    var availableUnits = HashSet<String>()
    var targetLandmasses = ArrayList<HashSet<String>>()
    var ended = false
    var history = AchievementHistory()
    var accountKey = ""
    var initializationComplete = false
    @Transient var actionDepth = 0

    fun clone() = AchievementGameState().also {
        it.storageVersion = storageVersion
        it.catalogVersion = catalogVersion
        it.recordingVersion = recordingVersion
        it.gameId = gameId
        it.playerId = playerId
        it.civilization = civilization
        it.difficulty = difficulty
        it.baseRuleset = baseRuleset
        it.speed = speed
        it.mapType = mapType
        it.aiOpponents = aiOpponents
        it.cityStates = cityStates
        it.oneCityChallenge = oneCityChallenge
        it.availableIds.addAll(availableIds)
        it.enabledVictories.addAll(enabledVictories)
        it.availableUnits.addAll(availableUnits)
        targetLandmasses.mapTo(it.targetLandmasses) { land -> HashSet(land) }
        it.ended = ended
        it.history = history.clone()
        it.accountKey = accountKey
        it.initializationComplete = initializationComplete
    }

    fun canContribute(game: GameInfo, profile: AchievementProfile): Boolean =
        recordingVersion >= AchievementCatalog.firstCompleteRecordingVersion &&
            recordingVersion <= AchievementCatalog.recordingVersion &&
            gameId == game.gameId && !ended && gameId !in profile.disqualifiedGames

    companion object {
        fun create(game: GameInfo, generatedMap: Boolean, hadMods: Boolean): AchievementGameState? {
            if (AchievementCatalog.newGameIneligibility(game, generatedMap, hadMods) != null) return null
            val player = game.civilizations.single { it.isHuman() }
            return AchievementGameState().apply {
                storageVersion = 1
                catalogVersion = AchievementCatalog.version
                recordingVersion = AchievementCatalog.recordingVersion
                gameId = game.gameId
                playerId = player.civID
                accountKey = AchievementTracker.service?.accountKey.orEmpty()
                civilization = player.nation.name
                difficulty = game.difficulty
                baseRuleset = game.gameParameters.baseRuleset
                speed = game.gameParameters.speed
                mapType = game.tileMap.mapParameters.type
                aiOpponents = game.civilizations.count { it.isAI() && it.isMajorCiv() }
                cityStates = game.civilizations.count { it.isCityState }
                oneCityChallenge = game.gameParameters.oneCityChallenge
                history.startTurn(game.turns)
                availableIds.addAll(AchievementCatalog.byId.keys)
                enabledVictories.addAll(game.gameParameters.victoryTypes)
                AchievementCatalog.definitions.mapNotNull { it.requiredUnit }
                    .filterTo(availableUnits) { it in game.ruleset.units }
                if (mapType == MapType.twoContinents)
                    targetLandmasses.addAll(findTargetLandmasses(game.tileMap))
            }
        }

        fun tileKey(tile: Tile) = "${tile.position.x},${tile.position.y}"

        /** Includes impassable land and uses actual neighbors, including world-wrap edges. */
        fun findTargetLandmasses(map: TileMap): List<HashSet<String>> {
            val remaining = map.values.filter { it.isLand }.toMutableSet()
            val groups = ArrayList<Set<Tile>>()
            while (remaining.isNotEmpty()) {
                val start = remaining.first()
                remaining.remove(start)
                val group = hashSetOf(start)
                val queue = ArrayDeque<Tile>()
                queue.add(start)
                while (queue.isNotEmpty()) {
                    for (neighbor in queue.removeFirst().neighbors) {
                        if (remaining.remove(neighbor)) {
                            group.add(neighbor)
                            queue.add(neighbor)
                        }
                    }
                }
                groups.add(group)
            }
            val coordinates = compareBy<Tile> { it.position.x }.thenBy { it.position.y }
            return groups.sortedWith(compareByDescending<Set<Tile>> { it.size }
                .thenBy { it.minWith(coordinates).position.x }
                .thenBy { it.minWith(coordinates).position.y })
                .take(2).map { group -> group.mapTo(HashSet()) { tileKey(it) } }
        }
    }
}
