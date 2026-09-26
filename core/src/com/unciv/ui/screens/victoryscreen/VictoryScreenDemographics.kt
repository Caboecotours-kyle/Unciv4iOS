package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import kotlin.math.roundToInt
import yairm210.purity.annotations.Readonly

class VictoryScreenDemographics(
    worldScreen: WorldScreen,
    portrait: Boolean
) : Table(BaseScreen.skin) {
    private val playerCiv = worldScreen.selectedGameView.civView.getCiv()

    private enum class RankLabels { Rank, Value, Best, Average, Worst }

    init {
        defaults().pad(5f)
        val majorCivs = worldScreen.gameInfo.civilizations.filter { it.isMajorCiv() }

        if (portrait) buildPortrait(majorCivs)
        else buildLandscape(majorCivs)
    }

    /** One block per demographic, its rank lines listed below it, so the page fits a phone's width */
    private fun buildPortrait(majorCivs: List<Civilization>) {
        top()
        for (category in RankingType.filteredEntries(playerCiv.gameInfo.gameParameters)) {
            val title = Table()
            category.getImage()?.let { title.add(it).size(Constants.defaultFontSize.toFloat()).padRight(5f) }
            title.add(category.label.toLabel(fontSize = 20))
            add(title).colspan(2).padTop(15f).row()
            addSeparator().colspan(2).fillX()

            val sorted = majorCivs.filter { it.isAlive() || it == playerCiv }
                .map { VictoryScreen.CivWithStat(it, it.getStatForDemographics(category)) }
                .sortedByDescending { it.value }
            for (rankLabel in RankLabels.entries) {
                val value = when (rankLabel) {
                    RankLabels.Rank -> (sorted.indexOfFirst { it.civ == playerCiv } + 1).toLabel()
                    RankLabels.Value -> sorted.firstOrNull { it.civ == playerCiv && playerCiv.isMajorCiv() && !playerCiv.isDefeated() }
                        ?.let { VictoryScreenCivGroup(it, playerCiv) } ?: continue
                    RankLabels.Best -> VictoryScreenCivGroup(sorted.first(), playerCiv)
                    RankLabels.Average -> (sorted.sumOf { it.value }.toFloat() / sorted.size).roundToInt().toLabel()
                    RankLabels.Worst -> VictoryScreenCivGroup(sorted.last(), playerCiv)
                }
                add(rankLabel.name.toLabel()).left()
                add(value).left().row()
            }
        }
    }

    private fun buildLandscape(majorCivs: List<Civilization>) {
        buildDemographicsHeaders()

        for (rankLabel in RankLabels.entries)   {
            if (rankLabel == RankLabels.Value) {
                // playerCiv is not necessarily alive nor major, and the `first` below would throw
                if (playerCiv.isDefeated() || !playerCiv.isMajorCiv()) continue
            }
            row()
            add(rankLabel.name.toLabel())

            for (category in RankingType.filteredEntries(playerCiv.gameInfo.gameParameters)) {
                // Use turn-start snapshots so mid-turn army/economy changes cannot be used to
                // probe Best/Worst values of other civilizations (see linked issue).
                val aliveMajorCivsSorted = majorCivs.filter { it.isAlive() || it == playerCiv }
                    .map { VictoryScreen.CivWithStat(it, it.getStatForDemographics(category)) }
                    .sortedByDescending { it.value }

                fun addRankCivGroup(civEntry: VictoryScreen.CivWithStat) {
                    add(VictoryScreenCivGroup(civEntry, playerCiv)).fillX()
                }

                @Suppress("NON_EXHAUSTIVE_WHEN") // RankLabels.Demographic treated above
                when (rankLabel) {
                    RankLabels.Rank -> add((aliveMajorCivsSorted.indexOfFirst { it.civ == playerCiv } + 1).toLabel())
                    RankLabels.Value -> addRankCivGroup(aliveMajorCivsSorted.first { it.civ == playerCiv })
                    RankLabels.Best -> addRankCivGroup(aliveMajorCivsSorted.first())
                    RankLabels.Average -> add((aliveMajorCivsSorted.sumOf { it.value }.toFloat() / aliveMajorCivsSorted.size).roundToInt().toLabel())
                    RankLabels.Worst -> addRankCivGroup(aliveMajorCivsSorted.last())
                }
            }
        }
    }

    /**
     * Ranking value frozen at the end of the last completed turn, as recorded in [Civilization.statsHistory].
     * Falls back to a live value only when no history exists yet (e.g. very early game).
     */
    @Readonly
    private fun Civilization.getStatForDemographics(category: RankingType): Int {
        val history = statsHistory
        val snapshot = history[gameInfo.turns] ?: history.maxByOrNull { it.key }?.value
        return snapshot?.get(category) ?: getStatForRanking(category)
    }

    private fun buildDemographicsHeaders() {
        val demoLabel = Table().apply { defaults().pad(5f) }

        demoLabel.add("Demographic".toLabel()).row()
        demoLabel.addSeparator().fillX()
        add(demoLabel)

        for (category in RankingType.filteredEntries(playerCiv.gameInfo.gameParameters)) {
            val headers = Table().apply { defaults().pad(5f) }
            val textAndIcon = Table().apply { defaults() }
            val columnImage = category.getImage()
            if (columnImage != null)
                textAndIcon.add(columnImage).center()
                    .size(Constants.defaultFontSize.toFloat() * 0.75f)
                    .padRight(2f).padTop(-2f)
            textAndIcon.add(category.label.toLabel()).row()
            headers.add(textAndIcon)
            headers.addSeparator()
            add(headers)
        }
    }
}
