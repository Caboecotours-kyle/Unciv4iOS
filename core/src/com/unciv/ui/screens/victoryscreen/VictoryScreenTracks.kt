package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/** Portrait "Our status": one card per victory type, a track of its milestones that fills as you
 *  complete them, rivals as dots at their own progress, then the milestone details. */
class VictoryScreenTracks(worldScreen: WorldScreen, stageWidth: Float) : Table(BaseScreen.skin) {
    private val worldScreen = worldScreen
    private val playerCiv = worldScreen.selectedGameView.civView.getCiv()
    private val scale = stageWidth / 393f
    private val cardWidth = stageWidth - 2 * outerPad * scale
    private val trackWidth = cardWidth - 2 * cardPad * scale
    private val secondary = colorFromRGB(178, 205, 222)

    private class Step(val name: String, val complete: Boolean, val partial: Boolean = false,
                            val icon: () -> Actor)

    init {
        top()
        pad(outerPad * scale)
        defaults().width(cardWidth).padBottom(10f * scale)
        add(getScoreBar()).row()
        for ((index, victory) in worldScreen.selectedGameView.civView.getVictoriesShownInVictoryScreen().withIndex())
            add(getTrackCard(victory, index == 0)).row()
    }

    private fun getScoreBar(): Table {
        val score = playerCiv.calculateTotalScore().toInt()
        val known = worldScreen.gameInfo.civilizations.filter {
            it != playerCiv && it.isMajorCiv() && playerCiv.knows(it) &&
                VictoryScreen.canViewCivStats(worldScreen.gameInfo, playerCiv, it)
        }
        val detail = if (known.isEmpty()) "Rival scores hidden" else {
            val leader = known.maxByOrNull { it.calculateTotalScore() }!!
            "${1 + known.count { it.calculateTotalScore() > score }} of ${known.size + 1} known · ${leader.civName} ${leader.calculateTotalScore().toInt()}"
        }
        val bar = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("VictoryScreen/PortraitScore",
                BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(31, 53, 75))
            pad(10f * scale)
        }
        bar.add(RankingType.Score.getImage() ?: ImageGetter.getVictoryTypeIcon("Time", 28f * scale))
            .size(28f * scale).padRight(10f * scale)
        val labels = Table()
        labels.add("Score $score".toLabel(Color.WHITE, (16f * scale).toInt())).left().row()
        labels.add(detail.toLabel(secondary, (12f * scale).toInt())).left().row()
        bar.add(labels).growX().left()
        val turnsLeft = (worldScreen.gameInfo.gameParameters.maxTurns - worldScreen.gameInfo.turns).coerceAtLeast(0)
        val turns = Table()
        turns.add(turnsLeft.toString().toLabel(Color.WHITE, (15f * scale).toInt())).right().row()
        turns.add("turns left".toLabel(secondary, (11f * scale).toInt())).right().row()
        bar.add(turns).right()
        return bar
    }

    private fun getTrackCard(victory: Victory, openInitially: Boolean): Table {
        val steps = getSteps(victory, playerCiv)
        val card = Table()
        card.background = BaseScreen.skinStrings.getUiBackground("VictoryScreen/Track",
            BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(30, 54, 78))
        card.pad(cardPad * scale)
        card.defaults().growX().left()

        val total = steps.size
        val done = steps.count { it.complete }
        val header = Table()
        val icon = ImageGetter.getVictoryTypeIcon(victory.name, 30f * scale, Color.WHITE)
        header.add(icon.surroundWithCircle(46f * scale, resizeActor = false,
            color = when (victory.name) {
                "Scientific" -> colorFromRGB(42, 111, 176)
                "Cultural" -> colorFromRGB(122, 69, 184)
                "Domination" -> colorFromRGB(184, 67, 59)
                "Diplomatic" -> colorFromRGB(47, 143, 106)
                else -> colorFromRGB(61, 100, 132)
            })).size(46f * scale).padRight(10f * scale)
        header.add(victory.name.toLabel(Color.WHITE, (18f * scale).toInt())).growX()
        header.add("${steps.count { it.complete }} of ${steps.size}".toLabel(Color.WHITE,
            (15f * scale).toInt())).padLeft(8f * scale)
        card.add(header).height(48f * scale).row()

        val pips = Table().apply { left() }
        val gap = 5f * scale
        val pipWidth = if (steps.size <= 7) (trackWidth - (steps.size - 1) * gap) / steps.size.coerceAtLeast(1)
            else 44f * scale
        for (step in steps) {
            val pip = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("VictoryScreen/Step",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                    if (step.complete) colorFromRGB(91, 81, 49) else if (step.partial) colorFromRGB(66, 76, 79)
                    else colorFromRGB(43, 66, 88))
            }
            val image = step.icon()
            if (!step.complete) image.color.a = if (step.partial) .72f else .42f
            pip.add(image).size(27f * scale)
            pips.add(pip).size(pipWidth, 44f * scale).padRight(gap)
        }
        card.add(ScrollPane(pips).apply { setScrollingDisabled(false, true); fadeScrollBars = false })
            .width(trackWidth).height(48f * scale).padTop(8f * scale).row()

        val next = victory.milestoneObjects.firstOrNull { !it.hasBeenCompletedBy(playerCiv) }
        val nextText = next?.getVictoryScreenButtonHeaderText(false, playerCiv)?.tr() ?: "Victory complete"
        card.add("→  $nextText".toLabel(secondary, (13f * scale).toInt()).apply { wrap = true })
            .width(trackWidth).minHeight(38f * scale).padTop(8f * scale).row()

        val details = Table().apply { top() }
        details.add(getTrack(victory, done, total)).width(trackWidth).height(trackHeight * scale).row()
        val rivals = playerCiv.gameInfo.civilizations.filter {
            it != playerCiv && it.isMajorCiv() && !it.isDefeated() && playerCiv.knows(it) &&
                victory in it.victoryManager.getAvailableVictories()
        }
        for (rival in rivals) {
            val row = Table()
            row.add(ImageGetter.getNationPortrait(rival.nation, 30f * scale)).size(30f * scale).padRight(8f * scale)
            row.add(rival.civName.toLabel(Color.WHITE, (13f * scale).toInt(), hideIcons = true)).growX().left()
            row.add("${getSteps(victory, rival).count { it.complete }} of $total"
                .toLabel(secondary, (12f * scale).toInt()))
            details.add(row).width(trackWidth).height(40f * scale).row()
        }
        val unknownCount = playerCiv.gameInfo.civilizations.count {
            it != playerCiv && it.isMajorCiv() && !it.isDefeated() && !playerCiv.knows(it)
        }
        if (unknownCount > 0) {
            val text = if (playerCiv.shouldHideCivCount()) "Other civilizations not met" else
                "$unknownCount civilizations not met"
            details.add(text.toLabel(secondary, (12f * scale).toInt())).height(32f * scale).left().row()
        }
        val detailHeight = trackHeight * scale + rivals.size * 40f * scale +
            if (unknownCount > 0) 32f * scale else 0f
        val detailCell = card.add(details).width(trackWidth).height(if (openInitially) detailHeight else 0f)
        card.row()
        details.isVisible = openInitially
        var expanded = openInitially
        header.onClick {
            expanded = !expanded
            details.isVisible = expanded
            detailCell.height(if (expanded) detailHeight else 0f)
            card.invalidateHierarchy()
        }

        return card
    }

    private fun getSteps(victory: Victory, civ: Civilization): List<Step> {
        val steps = ArrayList<Step>()
        for (milestone in victory.milestoneObjects) {
            val complete = milestone.hasBeenCompletedBy(civ)
            when (milestone.type) {
                MilestoneType.AddedSSPartsInCapital -> {
                    val seen = HashMap<String, Int>()
                    for (part in victory.requiredSpaceshipParts) {
                        val count = seen[part] ?: 0
                        seen[part] = count + 1
                        steps += Step(part, count < civ.victoryManager.currentsSpaceshipParts[part], icon = {
                            ImageGetter.getConstructionPortrait(part, 27f * scale)
                        })
                    }
                }
                MilestoneType.CompletePolicyBranches -> {
                    val needed = milestone.params.firstOrNull()?.toIntOrNull() ?: 1
                    val branches = civ.gameInfo.ruleset.policyBranches.values
                        .sortedByDescending { branch -> branch.policies.any { civ.policies.isAdopted(it.name) } }
                        .sortedByDescending { it in civ.policies.completedBranches }
                        .take(needed)
                    for (branch in branches) {
                        steps += Step(branch.name, branch in civ.policies.completedBranches,
                            branch.policies.any { civ.policies.isAdopted(it.name) }, icon = {
                                val path = "PolicyBranchIcons/${branch.name}"
                                if (ImageGetter.imageExists(path)) ImageGetter.getImage(path)
                                else ImageGetter.getVictoryTypeIcon(victory.name, 27f * scale)
                            })
                    }
                }
                MilestoneType.CaptureAllCapitals -> {
                    val capitals = civ.gameInfo.getCities().filter { it.isOriginalCapital }
                    val owners = civ.gameInfo.civilizations.filter { it.isMajorCiv() &&
                        (it == playerCiv || !playerCiv.shouldHideCivCount() || playerCiv.knows(it)) }
                    for (owner in owners) {
                        if (owner != playerCiv && !playerCiv.knows(owner)) {
                            steps += Step("Unknown", false, icon = { ImageGetter.getRandomNationPortrait(27f * scale) })
                            continue
                        }
                        val capital = capitals.firstOrNull { it.foundingCivObject == owner }
                        steps += Step(owner.civName, capital?.civ == civ || capital == null && owner.isDefeated(), icon = {
                            ImageGetter.getNationPortrait(owner.nation, 27f * scale)
                        })
                    }
                    if (playerCiv.shouldHideCivCount() && civ.gameInfo.civilizations.any {
                            it.isMajorCiv() && it != playerCiv && !playerCiv.knows(it)
                        }) steps += Step("Unknown", false, icon = { ImageGetter.getRandomNationPortrait(27f * scale) })
                }
                else -> {
                    val name = milestone.params.firstOrNull() ?: victory.name
                    steps += Step(name, complete, icon = {
                        if (milestone.type == MilestoneType.BuiltBuilding ||
                            milestone.type == MilestoneType.BuildingBuiltGlobally)
                            ImageGetter.getConstructionPortrait(name, 27f * scale)
                        else ImageGetter.getVictoryTypeIcon(victory.name, 27f * scale)
                    })
                }
            }
        }
        return steps
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

        // unmet rivals stay anonymous, and uncounted when the game hides how many civs there are
        val hideCivCount = playerCiv.shouldHideCivCount()
        val rivals = playerCiv.gameInfo.civilizations.filter {
            it != playerCiv && it.isMajorCiv() && !it.isDefeated() && victory in it.victoryManager.getAvailableVictories()
                && (!hideCivCount || playerCiv.knows(it))
        }
        val dotsAtStep = HashMap<Int, Int>()
        for (rival in rivals) {
            val step = getSteps(victory, rival).count { it.complete }
            val stacked = dotsAtStep[step] ?: 0 // getOrDefault is missing on the iOS runtime
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
