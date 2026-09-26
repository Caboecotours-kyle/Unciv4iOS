package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Victory
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.equalizeColumns
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

class VictoryScreenGlobalVictory(
    worldScreen: WorldScreen
) : Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    private val header = Table()
    /** Portrait stacks one section per victory instead of side-by-side columns under a shared header */
    private val portrait = worldScreen.isPortrait()

    init {
        align(Align.top)

        val majorCivs = worldScreen.gameInfo.civilizations.asSequence().filter { it.isMajorCiv() }
        val civView = worldScreen.selectedGameView.civView
        val victoriesToShow = civView.getVictoriesShownInVictoryScreen()

        defaults().pad(10f)
        for (victory in victoriesToShow) {
            if (portrait) {
                add("[${victory.name}] Victory".toLabel(fontSize = 20)).padBottom(0f).row()
                add(getColumn(majorCivs, victory, civView.getCiv())).row()
                continue
            }
            header.add("[${victory.name}] Victory".toLabel()).pad(10f)
            add(getColumn(majorCivs, victory, civView.getCiv()))
        }
        header.addSeparator(Color.GRAY)
    }

    private fun getColumn(
        majorCivs: Sequence<Civilization>,
        victory: Victory,
        playerCiv: Civilization
    ) = Table().apply {
        defaults().pad(10f)
        val sortedCivs = majorCivs.filter { victory in it.victoryManager.getAvailableVictories() }.sortedWith(
            compareBy<Civilization> { it.isDefeated() }
            .thenBy { it.victoryManager.amountMilestonesCompleted(victory) }
        )
        for (civ in sortedCivs) {
            val buttonText = civ.victoryManager.getNextMilestone(victory)
                ?.getVictoryScreenButtonHeaderText(false, civ)
                ?: "Done!"
            add(VictoryScreenCivGroup(civ, buttonText, playerCiv)).fillX().row()
        }
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (!portrait) equalizeColumns(header, this)
    }

    override fun getFixedContent() = if (portrait) null else header
}
