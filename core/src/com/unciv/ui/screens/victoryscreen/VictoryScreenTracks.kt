package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Victory
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** Portrait "Our status": one card per victory type, a track of its milestones that fills as you
 *  complete them, rivals as dots at their own progress, then the milestone details. */
class VictoryScreenTracks(worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    private val playerCiv = worldScreen.selectedGameView.civView.getCiv()
    private val cardWidth = worldScreen.stage.width - 2 * outerPad
    private val trackWidth = cardWidth - 2 * cardPad

    init {
        top()
        pad(outerPad)
        defaults().width(cardWidth).padBottom(outerPad)
        for (victory in worldScreen.selectedGameView.civView.getVictoriesShownInVictoryScreen())
            add(getTrackCard(victory)).row()
    }

    private fun getTrackCard(victory: Victory): Table {
        val card = Table()
        card.background = BaseScreen.skinStrings.getUiBackground("VictoryScreen/Track",
            BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(35, 58, 88))
        card.pad(cardPad)
        card.defaults().growX().left()

        val total = victory.milestoneObjects.size
        val done = playerCiv.victoryManager.amountMilestonesCompleted(victory)
        val header = Table()
        val icon = ImageGetter.getVictoryTypeIcon(victory.name, 28f).apply { color = colorFromRGB(19, 36, 53) }
        header.add(icon.surroundWithCircle(44f, resizeActor = false, color = colorFromRGB(238, 245, 251))).size(44f).padRight(10f)
        header.add("[${victory.name}] Victory".toLabel(Color.WHITE, 20).apply { wrap = true }).growX()
        header.add("${done.tr()}/${total.tr()}".toLabel(Color.WHITE, 20)).padLeft(10f)
        card.add(header).padBottom(10f).row()

        card.add(getTrack(victory, done, total)).width(trackWidth).height(trackHeight).padBottom(10f).row()

        var firstIncomplete = true
        for (milestone in victory.milestoneObjects) {
            val status = when {
                milestone.hasBeenCompletedBy(playerCiv) -> Victory.CompletionStatus.Completed
                firstIncomplete -> { firstIncomplete = false; Victory.CompletionStatus.Partially }
                else -> Victory.CompletionStatus.Incomplete
            }
            for (button in milestone.getVictoryScreenButtons(status, playerCiv)) {
                button.label.wrap = true
                card.add(button).fillX().padBottom(6f).row()
            }
        }

        if (victory.victoryScreenHeader.isNotEmpty())
            card.add(victory.victoryScreenHeader.toLabel(Color.LIGHT_GRAY, 16).apply { wrap = true }).padTop(4f).row()
        return card
    }

    /** Segments you have filled, with each rival's portrait standing where its own progress ends */
    private fun getTrack(victory: Victory, done: Int, total: Int): Group {
        val track = Group()
        track.setSize(trackWidth, trackHeight)
        val segments = total.coerceAtLeast(1)
        val segmentWidth = (trackWidth - (segments - 1) * segmentGap) / segments
        for (i in 0 until segments) {
            val segment = ImageGetter.getWhiteDot()
            segment.color = if (i < done) Color.GOLD else Color(1f, 1f, 1f, 0.15f)
            segment.setBounds(i * (segmentWidth + segmentGap), 0f, segmentWidth, segmentHeight)
            track.addActor(segment)
        }

        val rivals = playerCiv.gameInfo.civilizations.filter {
            it != playerCiv && it.isMajorCiv() && !it.isDefeated() && victory in it.victoryManager.getAvailableVictories()
        }
        val dotsAtStep = HashMap<Int, Int>()
        for (rival in rivals) {
            val step = rival.victoryManager.amountMilestonesCompleted(victory)
            val stacked = dotsAtStep.getOrDefault(step, 0)
            dotsAtStep[step] = stacked + 1
            val dot = getRivalDot(rival)
            val x = (step.toFloat() / segments * trackWidth - dotSize / 2 + stacked * dotSize * 0.6f)
                .coerceIn(0f, trackWidth - dotSize)
            dot.setPosition(x, segmentHeight + 4f)
            track.addActor(dot)
        }
        return track
    }

    private fun getRivalDot(rival: Civilization) =
        VictoryScreenCivGroup.getCivImageAndColors(rival, playerCiv, VictoryScreenCivGroup.DefeatedPlayerStyle.GREYED_OUT)
            .first.apply { setSize(dotSize, dotSize); setOrigin(Align.center) }

    companion object {
        private const val outerPad = 14f
        private const val cardPad = 12f
        private const val segmentHeight = 10f
        private const val segmentGap = 4f
        private const val dotSize = 30f
        private const val trackHeight = segmentHeight + 4f + dotSize
    }
}
