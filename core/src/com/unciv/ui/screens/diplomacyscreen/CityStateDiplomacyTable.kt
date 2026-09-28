package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.civilization.managers.quests.AssignedQuest
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Quest
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.popups.tinted

class CityStateDiplomacyTable(private val diplomacyScreen: DiplomacyScreen) {
    val viewingCiv = diplomacyScreen.viewingCiv

    fun getCityStateDiplomacyTable(otherCiv: Civilization): Table = getClassicCityStateTable(otherCiv)

    private fun getClassicCityStateTable(otherCiv: Civilization): Table {
        val otherCivDiplomacyManager = otherCiv.getDiplomacyManager(viewingCiv)!!

        val diplomacyTable = getCityStateDiplomacyTableHeader(otherCiv)

        diplomacyTable.addSeparator()

        val giveGiftButton = "Give a Gift".toTextButton()
        giveGiftButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getGoldGiftTable(otherCiv)))
        }
        diplomacyTable.add(giveGiftButton).row()
        if (diplomacyScreen.isNotPlayersTurn() || viewingCiv.isAtWarWith(otherCiv)) giveGiftButton.disable()

        val improveTileButton = getImproveTilesButton(otherCiv, otherCivDiplomacyManager)
        if (improveTileButton != null) diplomacyTable.add(improveTileButton).row()

        if (otherCivDiplomacyManager.diplomaticStatus != DiplomaticStatus.Protector)
            diplomacyTable.add(getPledgeToProtectButton(otherCiv)).row()
        else
            diplomacyTable.add(getRevokeProtectionButton(otherCiv)).row()

        val demandTributeButton = "Demand Tribute".toTextButton()
        demandTributeButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getDemandTributeTable(otherCiv)))
        }
        diplomacyTable.add(demandTributeButton).row()
        if (diplomacyScreen.isNotPlayersTurn() || viewingCiv.isAtWarWith(otherCiv)) demandTributeButton.disable()

        val diplomacyManager = viewingCiv.getDiplomacyManager(otherCiv)!!
        if (!viewingCiv.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)) {
            if (viewingCiv.isAtWarWith(otherCiv))
                diplomacyTable.add(getNegotiatePeaceCityStateButton(otherCiv, diplomacyManager)).row()
            else diplomacyTable.add(diplomacyScreen.getDeclareWarButton(diplomacyManager, otherCiv)).row()
        }

        if (otherCiv.getCapital() != null && viewingCiv.hasExplored(otherCiv.getCapital()!!.getCenterTile()))
            diplomacyTable.add(diplomacyScreen.getGoToOnMapButton(otherCiv)).row()

        val diplomaticMarriageButton = getDiplomaticMarriageButton(otherCiv)
        if (diplomaticMarriageButton != null) diplomacyTable.add(diplomaticMarriageButton).row()

        for (assignedQuest in otherCiv.questManager.getAssignedQuestsFor(viewingCiv)) {
            diplomacyTable.addSeparator()
            diplomacyTable.add(getQuestTable(assignedQuest)).row()
        }

        for (target in otherCiv.getKnownCivs().filter { otherCiv.questManager.isWarWithMajorActive(it) && viewingCiv != it }) {
            diplomacyTable.addSeparator()
            diplomacyTable.add(getWarWithMajorTable(target, otherCiv)).row()
        }

        return diplomacyTable
    }

    /** One way to gain influence on the portrait page */
    private class Way(val title: String, val detail: String, val delta: Int, val enabled: Boolean, val icon: String,
                      val previewText: String?, val action: () -> Unit)

    /** Portrait: the city-state's page on the tray sheet. The rail shows where each way to gain influence would move
     *  you against your rivals; the ways are tray rows (the picked one ticked), and the footer holds Tribute and the
     *  picked way's action. "All options" pushes every classic action as rows. */
    fun showPortraitPage(otherCiv: Civilization) {
        val manager = otherCiv.getDiplomacyManager(viewingCiv)!!
        val current = manager.getInfluence().toInt()
        val rivals = otherCiv.getKnownCivs().filter {
            it.isMajorCiv() && !it.isDefeated() && it != viewingCiv && viewingCiv.knows(it)
        }.mapNotNull { rival ->
            otherCiv.getDiplomacyManager(rival)?.let { rival to it.getInfluence().toInt() }
        }.sortedByDescending { it.second }.toList()
        val quests = otherCiv.questManager.getAssignedQuestsFor(viewingCiv)
        // A gold gift completes GiveGold quests immediately, in addition to its normal influence gain.
        val giftQuestBonus = quests.filter { it.questNameInstance == QuestName.GiveGold }
            .sumOf { it.getInfluence().toInt() }
        fun giftInfluence(amount: Int) =
            otherCiv.cityStateFunctions.influenceGainedByGift(viewingCiv, amount) + giftQuestBonus

        val sheet = diplomacyScreen.portraitPage(otherCiv.civName,
            "${otherCiv.cityStateType.name.tr()}  ·  ${manager.relationshipLevel().name.tr()} ($current)",
            cityStateLead(otherCiv), { diplomacyScreen.showPortraitList() }, otherCiv.nation.makeLink())
        sheet.minShare = .75f

        val ways = ArrayList<Way>()
        for (quest in quests) {
            val detail = if (quest.quest.duration > 0)
                "[${quest.getRemainingTurns()}] turns remaining" else quest.getDescription()
            ways += Way(quest.quest.name, detail, quest.quest.influence.toInt(), true, NotificationIcon.Quest, null) { quest.onClickAction() }
        }
        for (amount in listOf(250, 500, 1000)) {
            val enabled = viewingCiv.gold >= amount && !diplomacyScreen.isNotPlayersTurn() &&
                !viewingCiv.isAtWarWith(otherCiv)
            ways += Way("Gift [$amount] gold", "You have [${viewingCiv.gold}] gold", giftInfluence(amount), enabled, "StatIcons/Gold", null) {
                otherCiv.cityStateFunctions.receiveGoldGift(viewingCiv, amount)
                diplomacyScreen.updateLeftSideTable(otherCiv)
                diplomacyScreen.updateRightSide(otherCiv)
            }
        }
        if (manager.diplomaticStatus != DiplomaticStatus.Protector) {
            val restingPoint = manager.getCityStateInfluenceRestingPoint().toInt() + 10
            ways += Way("Pledge to protect", "Influence will rest at [$restingPoint]", 0,
                !diplomacyScreen.isNotPlayersTurn() && otherCiv.cityStateFunctions.otherCivCanPledgeProtection(viewingCiv),
                "OtherIcons/Shield", "Influence rests at $restingPoint after a pledge") { pledgeToProtect(otherCiv) }
        } else {
            val restingPoint = manager.getCityStateInfluenceRestingPoint().toInt() - 10
            ways += Way("Revoke protection", "End your pledge to protect", 0,
                !diplomacyScreen.isNotPlayersTurn() && otherCiv.cityStateFunctions.otherCivCanWithdrawProtection(viewingCiv),
                "OtherIcons/Shield", "Influence rests at $restingPoint after revoking protection") { revokeProtection(otherCiv) }
        }

        // The influence rail: you and your rivals on a track with the Friend and Ally marks
        val scale = sheet.p
        val railWidth = 120f * scale
        val trackHeight = maxOf(300f * scale, (ways.size + 1) * sheet.rowHeight + 30f * scale)
        val trackLength = trackHeight - 56f * scale
        val maximum = maxOf(100, current, rivals.maxOfOrNull { it.second } ?: 0)
        val track = Group().apply { setSize(railWidth, trackHeight) }
        track.addActor(ImageGetter.getWhiteDot().apply {
            color = Color.WHITE.cpy().apply { a = 0.12f }
            setSize(14f * scale, trackLength)
            setPosition(51f * scale, 26f * scale)
        })
        fun position(value: Int): Float = 26f * scale +
            (value.coerceIn(0, maximum).toFloat() / maximum) * trackLength
        for ((value, label, color) in listOf(
            Triple(30, "Friend", colorFromRGB(143, 216, 255)),
            Triple(60, "Ally", Color.GOLD)
        )) {
            val y = position(value)
            track.addActor(ImageGetter.getWhiteDot().apply {
                this.color = color
                setBounds(44f * scale, y, 72f * scale, 3f * scale)
            })
            track.addActor("$label\n$value".toLabel(color, (12f * scale).toInt()).apply {
                setAlignment(Align.right)
                setBounds(0f, y - 21f * scale, 42f * scale, 42f * scale)
            })
        }
        data class InfluenceMarker(var value: Int, val group: Group, val portrait: Group, val label: Label)
        val markers = mutableListOf<InfluenceMarker>()
        fun marker(civ: Civilization, value: Int, preview: Boolean = false): InfluenceMarker {
            val marker = Group().apply {
                setSize(railWidth, trackHeight)
                touchable = Touchable.disabled
            }
            val circle = Group().apply { setSize(36f * scale, 36f * scale) }
            if (preview) circle.addActor(ImageGetter.getCircle(Color.GOLD, 36f * scale))
            circle.addActor(ImageGetter.getNationPortrait(civ.nation, 30f * scale).apply {
                setPosition(3f * scale, 3f * scale)
            })
            marker.addActor(circle)
            val label = value.toString().toLabel(Color.WHITE, (13f * scale).toInt()).apply {
                setSize(48f * scale, 25f * scale)
            }
            marker.addActor(label)
            track.addActor(marker)
            return InfluenceMarker(value, marker, circle, label).also { markers.add(it) }
        }
        fun layoutMarkers() {
            val visible = markers.filter { it.group.isVisible }.sortedBy { position(it.value) }
            val spacing = 28f * scale
            var lastCenter = 10f * scale - spacing
            val centers = visible.map {
                maxOf(position(it.value), lastCenter + spacing).also { center -> lastCenter = center }
            }.toMutableList()
            if (centers.isNotEmpty()) {
                centers[centers.lastIndex] = minOf(centers.last(), trackHeight - 20f * scale)
                for (index in centers.lastIndex - 1 downTo 0)
                    centers[index] = minOf(centers[index], centers[index + 1] - spacing)
            }
            for ((index, item) in visible.withIndex()) {
                item.portrait.setPosition(39f * scale, centers[index] - 18f * scale)
                item.label.setPosition(78f * scale, centers[index] - 12f * scale)
            }
        }
        for ((rival, score) in rivals) marker(rival, score)
        marker(viewingCiv, current)
        val preview = marker(viewingCiv, current, preview = true)
        preview.group.isVisible = false
        layoutMarkers()

        val waysCard = Table()
        val summary = "Tap a way to gain to see where it moves you".toLabel(TrayColors.INK2, sheet.font(14f), hideIcons = true)
            .apply { wrap = true }
        fun footer(way: Way?) {
            val column = Table()
            column.add(summary).growX().left().padBottom(sheet.pt(10f)).row()
            val buttons = Table()
            val tributeAllowed = !diplomacyScreen.isNotPlayersTurn() && !viewingCiv.isAtWarWith(otherCiv)
            buttons.add(diplomacyScreen.portraitButton("Tribute", TraySheet.ButtonKind.Secondary, null, tributeAllowed) {
                showPortraitTribute(otherCiv)
            }.apply { name = "portrait-tribute" }).padRight(sheet.pt(10f))
            buttons.add(diplomacyScreen.portraitButton(way?.title ?: "Pick a way to gain", TraySheet.ButtonKind.Primary, null,
                way?.enabled == true) { way?.action?.invoke() }.apply { name = "portrait-way-action" }).growX()
            column.add(buttons).growX()
            sheet.footer().add(column).growX().padRight(0f)
        }
        /** Picks way [index] (-1: none can be taken): moves the preview marker, ticks its row, puts its action in the footer */
        fun select(index: Int) {
            val way = ways.getOrNull(index)
            if (way != null) {
                val result = current + way.delta
                preview.group.isVisible = way.delta != 0
                preview.value = result
                preview.label.setText(result.toString())
                layoutMarkers()
                val lead = rivals.maxOfOrNull { it.second }
                val projectedAlly = otherCiv.diplomacy.values
                    .filter { it.otherCiv.isMajorCiv() && !it.otherCiv.isDefeated() }
                    .maxByOrNull { if (it.otherCiv == viewingCiv) manager.getInfluence() + way.delta else it.getInfluence() }
                    ?.otherCiv
                summary.setText(way.previewText ?: ("You would reach $result${if (result >= 60 && projectedAlly == viewingCiv) ": Ally" else if (result >= 30) ": Friend" else ""}" +
                        (if (lead != null && result > lead) ", ${result - lead} ahead of your closest rival" else "")))
            }
            waysCard.clear()
            waysCard.add(sheet.card(ways.mapIndexed { i, it ->
                val icon = if (it.icon.startsWith("StatIcons/")) ImageGetter.getStatIcon(it.icon.removePrefix("StatIcons/"))
                    else ImageGetter.getImage(it.icon)
                sheet.row(it.title, it.detail, sheet.disc(icon, recolor = !it.icon.startsWith("StatIcons/")),
                    value = if (it.delta > 0) "+${it.delta}" else null, chevron = false,
                    state = when {
                        i == index -> TraySheet.RowState.Selected
                        !it.enabled -> TraySheet.RowState.Disabled
                        else -> TraySheet.RowState.Normal
                    }) { if (i != index) select(i) }.apply { name = "portrait-way-$i" }
            })).growX()
            footer(way)
            sheet.fit()
        }

        sheet.addToBody(sheet.section("Ways to gain influence"))
        val content = Table()
        content.add(track).width(railWidth).height(trackHeight).top().padRight(sheet.pt(4f))
        val right = Table().top()
        right.add(waysCard).growX().row()
        right.add(sheet.card(listOf(sheet.row("All options", lead = sheet.disc(ImageGetter.getImage("OtherIcons/Options"))) {
            showPortraitAllOptions(otherCiv)
        }.apply { name = "portrait-all-options" }))).growX().padTop(sheet.pt(8f))
        content.add(right).growX().top()
        sheet.addToBody(content)
        select(ways.indexOfFirst { it.enabled })
    }

    /** The city-state's type icon on its colour, as a tray lead */
    private fun cityStateLead(otherCiv: Civilization): Actor {
        val sheet = diplomacyScreen.traySheet
        return ImageGetter.getImage("CityStateIcons/${otherCiv.cityStateType.name}").apply { color = otherCiv.nation.getInnerColor() }
            .surroundWithCircle(sheet.pt(52f), color = otherCiv.nation.getOuterColor())
    }

    /** Portrait "All options": what the city-state is and offers, then every classic action as a row with its rules */
    private fun showPortraitAllOptions(otherCiv: Civilization) {
        val sheet = diplomacyScreen.portraitPage(otherCiv.civName, "All options", cityStateLead(otherCiv),
            { showPortraitPage(otherCiv) }, otherCiv.nation.makeLink())
        val manager = otherCiv.getDiplomacyManager(viewingCiv)!!
        val notMyTurn = diplomacyScreen.isNotPlayersTurn()
        val atWar = viewingCiv.isAtWarWith(otherCiv)
        fun disc(icon: String) = if (icon.startsWith("StatIcons/")) sheet.disc(ImageGetter.getStatIcon(icon.removePrefix("StatIcons/")), recolor = false)
            else sheet.disc(ImageGetter.getImage(icon))
        fun state(enabled: Boolean) = if (enabled) TraySheet.RowState.Normal else TraySheet.RowState.Disabled
        fun row(title: String, icon: String, enabled: Boolean, meta: String? = null, action: () -> Unit) =
            sheet.row(title, meta, disc(icon), state = state(enabled), onTap = if (enabled) action else null)

        sheet.addToBody(getPortraitInfo(otherCiv), 4f)

        val rows = ArrayList<Actor>()
        rows += row("Give a Gift", "OtherIcons/Present", !notMyTurn && !atWar) { showPortraitGoldGifts(otherCiv) }
        if (needsImprovements(otherCiv))
            rows += row("Gift Improvement", "OtherIcons/Improvements", !notMyTurn && manager.getInfluence() >= 60) { showPortraitImprovementGifts(otherCiv) }
        rows += if (manager.diplomaticStatus != DiplomaticStatus.Protector)
            row("Pledge to protect", "OtherIcons/Shield", !notMyTurn && otherCiv.cityStateFunctions.otherCivCanPledgeProtection(viewingCiv)) { pledgeToProtect(otherCiv) }
        else row("Revoke Protection", "OtherIcons/Shield", !notMyTurn && otherCiv.cityStateFunctions.otherCivCanWithdrawProtection(viewingCiv)) { revokeProtection(otherCiv) }
        rows += row("Demand Tribute", "StatIcons/Gold", !notMyTurn && !atWar) { showPortraitTribute(otherCiv) }
        if (otherCiv.getCapital() != null && viewingCiv.hasExplored(otherCiv.getCapital()!!.getCenterTile()))
            rows += diplomacyScreen.getGoToOnMapRow(otherCiv)
        if (viewingCiv.hasUnique(UniqueType.CityStateCanBeBoughtForGold))
            rows += row("Diplomatic Marriage ([${otherCiv.cityStateFunctions.getDiplomaticMarriageCost()}] Gold)", "OtherIcons/Capital",
                !notMyTurn && otherCiv.cityStateFunctions.canBeMarriedBy(viewingCiv)) { diplomaticMarriage(otherCiv) }
        if (!viewingCiv.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)) {
            if (atWar) {
                val turnsLeft = if (manager.hasFlag(DiplomacyFlags.DeclaredWar)) manager.getFlag(DiplomacyFlags.DeclaredWar) else 0
                rows += row("Negotiate Peace", "OtherIcons/Diplomacy", canNegotiatePeace(otherCiv, manager),
                    if (turnsLeft > 0) "${turnsLeft.tr()}${Fonts.turn}" else null) { negotiatePeace(otherCiv) }
            } else rows += diplomacyScreen.getDeclareWarRow(viewingCiv.getDiplomacyManager(otherCiv)!!, otherCiv)
        }
        sheet.addToBody(sheet.card(rows), 10f)

        val questRows = otherCiv.questManager.getAssignedQuestsFor(viewingCiv).map { assignedQuest ->
            val quest = assignedQuest.quest
            val title = if (quest.influence > 0) "[${quest.name}] (+[${quest.influence.toInt()}] influence)" else quest.name
            val meta = listOfNotNull(
                assignedQuest.getDescription().tr(),
                if (quest.duration > 0) "[${assignedQuest.getRemainingTurns()}] turns remaining".tr() else null,
                if (quest.isGlobal()) assignedQuest.assignerCiv.questManager.getScoreStringForGlobalQuest(assignedQuest).takeIf { it.isNotEmpty() }?.tr() else null
            ).joinToString("\n")
            sheet.row(title, meta, sheet.disc(ImageGetter.getImage(NotificationIcon.Quest))) { assignedQuest.onClickAction() }
        }.toList()
        if (questRows.isNotEmpty()) {
            sheet.addToBody(sheet.section("Quests"))
            sheet.addToBody(sheet.card(questRows))
        }
        val wars = otherCiv.getKnownCivs().filter { otherCiv.questManager.isWarWithMajorActive(it) && viewingCiv != it }.map { target ->
            val progress = if (viewingCiv.knows(target)) "Currently you have killed [${otherCiv.questManager.unitsKilledSoFar(target, viewingCiv)}] of their military units."
                else "You need to find them first!"
            sheet.row("War against [${target.civName}]",
                "We need you to help us defend against [${target.civName}]. Killing [${otherCiv.questManager.unitsToKill(target)}] of their military units would slow their offensive.".tr() +
                    "\n" + progress.tr(), sheet.disc(ImageGetter.getImage("OtherIcons/Pillage")))
        }.toList()
        if (wars.isNotEmpty()) sheet.addToBody(sheet.card(wars), 10f)
        sheet.fit()
    }

    /** Type, personality, resources, ally, protectors, standing and bonuses, as readable wrapping lines in a card */
    private fun getPortraitInfo(otherCiv: Civilization): Table {
        val sheet = diplomacyScreen.traySheet
        val manager = otherCiv.getDiplomacyManager(viewingCiv)!!
        val info = Table().left()
        info.background = sheet.art.rounded(sheet.pt(18f)).tinted(TrayColors.CARD)
        info.pad(sheet.pt(12f), sheet.pt(14f), sheet.pt(12f), sheet.pt(14f))
        fun line(text: String, color: Color = TrayColors.INK2) =
            info.add(text.toLabel(color, sheet.font(14.5f), hideIcons = true).apply { wrap = true }).growX().left().padBottom(sheet.pt(4f)).row()
        line("{Type}:  {${otherCiv.cityStateType.name}}", TrayColors.INK)
        line("{Personality}:  {${otherCiv.cityStatePersonality}}", TrayColors.INK)
        val resources = otherCiv.cityStateFunctions.getCityStateResourcesForAlly().filter { it.resource.resourceType != ResourceType.Bonus }
        if (resources.isNotEmpty()) {
            val row = Table().left()
            row.add("{Resources}:  ".toLabel(TrayColors.INK, sheet.font(14.5f))).padRight(sheet.pt(6f))
            for (supplyList in resources) {
                val chip = Table()
                chip.touchable = Touchable.enabled
                chip.add(ImageGetter.getResourcePortrait(supplyList.resource.name, sheet.pt(24f))).padRight(sheet.pt(4f))
                chip.add(supplyList.amount.tr().toLabel(TrayColors.INK, sheet.font(14.5f)))
                chip.onClick { diplomacyScreen.openCivilopedia(supplyList.resource.makeLink()) }
                row.add(chip).minHeight(sheet.touch).minWidth(sheet.touch).padRight(sheet.pt(8f))
            }
            info.add(row).left().row()
        }
        otherCiv.cityStateFunctions.updateAllyCivForCityState()
        val ally = otherCiv.allyCiv
        if (ally != null) {
            val allyInfluence = otherCiv.getDiplomacyManager(ally)!!.getInfluence().toInt()
            val allyName = if (!viewingCiv.knows(ally) && ally != viewingCiv) "Unknown civilization" else ally.civName
            line("Ally: [$allyName] with [$allyInfluence] Influence")
        }
        val protectors = otherCiv.cityStateFunctions.getProtectorCivs()
        if (protectors.isNotEmpty())
            line("{Protected by}: " + protectors.joinToString(", ") {
                if (!viewingCiv.knows(it) && it.civName != viewingCiv.civName) "Unknown civilization".tr() else it.civName.tr()
            })
        line("{Our relationship}: {${manager.relationshipLevel().name}} (${manager.getInfluence().toInt()})", TrayColors.INK)
        val nextLevelString = when {
            otherCiv.isAtWarWith(viewingCiv) -> ""
            manager.getInfluence().toInt() < 30 -> "Reach 30 for friendship."
            ally == viewingCiv -> ""
            else -> "Reach highest influence above 60 for alliance."
        }
        if (nextLevelString.isNotEmpty()) line(nextLevelString)
        val relationLevel = manager.relationshipIgnoreAfraid()
        if (relationLevel >= RelationshipLevel.Friend) {
            val turnsToRelationshipChange = manager.getTurnsToRelationshipChange()
            if (turnsToRelationshipChange != 0) line("Relationship changes in another [$turnsToRelationshipChange] turns")
        }
        val gameContext = GameContext(viewingCiv)
        for ((header, bonusLevel) in listOf("When Friends:" to RelationshipLevel.Friend, "When Allies:" to RelationshipLevel.Ally)) {
            val bonuses = CityStateFunctions.getCityStateBonuses(otherCiv.cityStateType, bonusLevel).filterNot { it.isHiddenToUsers() }
            if (bonuses.none()) continue
            val active = relationLevel == bonusLevel
            info.add(header.toLabel(if (active) Color.valueOf("8ff07e") else TrayColors.INK, sheet.font(14.5f)))
                .left().padTop(sheet.pt(6f)).padBottom(sheet.pt(2f)).row()
            for (bonus in bonuses)
                info.add(ColorMarkupLabel(bonus.getDisplayText(), sheet.font(14f),
                    if (active && bonus.conditionalsApply(gameContext)) Color.valueOf("8ff07e") else TrayColors.SECTION).apply { wrap = true })
                    .growX().left().padBottom(sheet.pt(2f)).row()
        }
        val unitName = otherCiv.cityStateUniqueUnit
        if (unitName != null) {
            val techNames = viewingCiv.gameInfo.ruleset.units[unitName]!!.requiredTechs()
            val isOrAre = if (techNames.count() == 1) "is" else "are"
            line("[${otherCiv.civName}] is able to provide [${unitName}] once [${techNames.joinToString(" and ")}] [${isOrAre}] researched.")
        }
        return info
    }

    /** Portrait Give a Gift: one row per amount with the influence it buys */
    private fun showPortraitGoldGifts(otherCiv: Civilization) {
        val sheet = diplomacyScreen.portraitPage("Give a Gift", otherCiv.civName, cityStateLead(otherCiv),
            { showPortraitAllOptions(otherCiv) }, otherCiv.nation.makeLink())
        val rows = listOf(250, 500, 1000).map { giftAmount ->
            val influenceAmount = otherCiv.cityStateFunctions.influenceGainedByGift(viewingCiv, giftAmount)
            val enabled = viewingCiv.gold >= giftAmount && !diplomacyScreen.isNotPlayersTurn()
            sheet.row("Gift [$giftAmount] gold (+[$influenceAmount] influence)", lead = sheet.disc(ImageGetter.getStatIcon("Gold"), recolor = false),
                state = if (enabled) TraySheet.RowState.Normal else TraySheet.RowState.Disabled, chevron = false,
                onTap = if (enabled) ({
                    otherCiv.cityStateFunctions.receiveGoldGift(viewingCiv, giftAmount)
                    diplomacyScreen.updateLeftSideTable(otherCiv)
                    diplomacyScreen.updateRightSide(otherCiv)
                }) else null)
        }
        sheet.addToBody(sheet.card(rows), 4f)
        sheet.fit()
    }

    /** Portrait Gift Improvement: one row per improvable resource tile */
    private fun showPortraitImprovementGifts(otherCiv: Civilization) {
        val sheet = diplomacyScreen.portraitPage("Gift Improvement", otherCiv.civName, cityStateLead(otherCiv),
            { showPortraitAllOptions(otherCiv) }, otherCiv.nation.makeLink())
        val rows = ArrayList<Actor>()
        for (improvableTile in getImprovableResourceTiles(otherCiv))
            for (tileImprovement in otherCiv.gameInfo.ruleset.tileImprovements.values) {
                if (!improvableTile.tileResource!!.isImprovedBy(tileImprovement.name)
                    || !improvableTile.improvementFunctions.canBuildImprovement(tileImprovement, otherCiv.state)) continue
                val enabled = viewingCiv.gold >= 200
                rows += sheet.row("Build [${tileImprovement}] on [${improvableTile.tileResource}] (200 Gold)",
                    lead = sheet.disc(ImageGetter.getImage("OtherIcons/Improvements")),
                    state = if (enabled) TraySheet.RowState.Normal else TraySheet.RowState.Disabled, chevron = false,
                    onTap = if (enabled) ({
                        buildImprovementGift(otherCiv, improvableTile, tileImprovement)
                        diplomacyScreen.updateRightSide(otherCiv)
                    }) else null)
            }
        if (rows.isNotEmpty()) sheet.addToBody(sheet.card(rows), 4f)
        sheet.fit()
    }

    /** Portrait Tribute: how willing they are and why, then taking gold or a worker */
    private fun showPortraitTribute(otherCiv: Civilization) {
        val sheet = diplomacyScreen.portraitPage("Tribute Willingness", otherCiv.civName, cityStateLead(otherCiv),
            { showPortraitPage(otherCiv) }, otherCiv.nation.makeLink())
        val willingness = Table().left()
        willingness.background = sheet.art.rounded(sheet.pt(18f)).tinted(TrayColors.CARD)
        willingness.pad(sheet.pt(12f), sheet.pt(14f), sheet.pt(12f), sheet.pt(14f))
        val tributeModifiers = otherCiv.cityStateFunctions.getTributeModifiers(viewingCiv, requireWholeList = true)
        for (item in tributeModifiers) {
            val color = if (item.value >= 0) Color.valueOf("8ff07e") else TrayColors.REASON
            willingness.add(item.key.toLabel(color, sheet.font(14.5f), hideIcons = true).apply { wrap = true }).growX().left()
            willingness.add(item.value.tr().toLabel(color, sheet.font(14.5f))).right().padLeft(sheet.pt(8f)).row()
        }
        willingness.add("Sum:".toLabel(TrayColors.INK, sheet.font(14.5f))).left().padTop(sheet.pt(4f))
        willingness.add(tributeModifiers.values.sum().toLabel().apply { color = TrayColors.INK }).right().padTop(sheet.pt(4f)).row()
        willingness.add("At least 0 to take gold, at least 30 and size 4 city for worker".toLabel(TrayColors.INK2, sheet.font(13.5f))
            .apply { wrap = true }).colspan(2).growX().left().padTop(sheet.pt(8f))
        sheet.addToBody(willingness, 4f)
        val gold = otherCiv.cityStateFunctions.getTributeWillingness(viewingCiv, demandingWorker = false) >= 0
        val worker = otherCiv.cityStateFunctions.getTributeWillingness(viewingCiv, demandingWorker = true) >= 0
        sheet.addToBody(sheet.card(listOf(
            sheet.row("Take [${otherCiv.cityStateFunctions.goldGainedByTribute()}] gold (-15 Influence)",
                lead = sheet.disc(ImageGetter.getStatIcon("Gold"), recolor = false), chevron = false,
                state = if (gold) TraySheet.RowState.Normal else TraySheet.RowState.Disabled,
                onTap = if (gold) ({ otherCiv.cityStateFunctions.tributeGold(viewingCiv); diplomacyScreen.updateRightSide(otherCiv) }) else null),
            sheet.row("Take worker (-50 Influence)", lead = sheet.disc(ImageGetter.getImage("OtherIcons/Improvements")), chevron = false,
                state = if (worker) TraySheet.RowState.Normal else TraySheet.RowState.Disabled,
                onTap = if (worker) ({ otherCiv.cityStateFunctions.tributeWorker(viewingCiv); diplomacyScreen.updateRightSide(otherCiv) }) else null)
        )), 10f)
        sheet.fit()
    }

    private fun getCityStateDiplomacyTableHeader(otherCiv: Civilization): Table {
        val otherCivDiplomacyManager = otherCiv.getDiplomacyManager(viewingCiv)!!

        val diplomacyTable = Table()
        diplomacyTable.defaults().pad(2.5f)

        diplomacyTable.add(LeaderIntroTable(otherCiv)).padBottom(15f).row()

        diplomacyTable.add("{Type}:  {${otherCiv.cityStateType.name}}".toLabel()).row()
        diplomacyTable.add("{Personality}:  {${otherCiv.cityStatePersonality}}".toLabel()).row()

        if (otherCiv.detailedCivResources.any { it.resource.resourceType != ResourceType.Bonus }) {
            val resourcesTable = Table()
            resourcesTable.add("{Resources}:  ".toLabel()).padRight(10f)
            val cityStateResources = otherCiv.cityStateFunctions.getCityStateResourcesForAlly()
            for (supplyList in cityStateResources) {
                if (supplyList.resource.resourceType == ResourceType.Bonus)
                    continue
                val name = supplyList.resource.name
                val wrapper = Table()
                val image = ImageGetter.getResourcePortrait(name, 30f)
                wrapper.add(image).padRight(5f)
                wrapper.add(supplyList.amount.toLabel())
                resourcesTable.add(wrapper).padRight(20f)
                wrapper.addTooltip(name, 18f)
                wrapper.onClick {
                    diplomacyScreen.openCivilopedia(supplyList.resource.makeLink())
                }
            }
            diplomacyTable.add(resourcesTable).row()
        }
        diplomacyTable.row().padTop(15f)

        otherCiv.cityStateFunctions.updateAllyCivForCityState()
        val ally = otherCiv.allyCiv
        if (ally != null) {
            val allyInfluence = otherCiv.getDiplomacyManager(ally)!!.getInfluence().toInt()
            val allyName = if (!viewingCiv.knows(ally) && ally != viewingCiv)
                "Unknown civilization"
            else ally.civName
            diplomacyTable
                .add("Ally: [$allyName] with [$allyInfluence] Influence".toLabel())
                .row()
        }

        val protectors = otherCiv.cityStateFunctions.getProtectorCivs()
        if (protectors.isNotEmpty()) {
            val newProtectors = arrayListOf<String>()
            for (protector in protectors) {
                if (!viewingCiv.knows(protector) && protector.civName != viewingCiv.civName)
                    newProtectors.add("Unknown civilization".tr())
                else
                    newProtectors.add(protector.civName.tr())
            }
            val protectorString = "{Protected by}: " + newProtectors.joinToString(", ")
            diplomacyTable.add(protectorString.toLabel().apply {
                wrap = true
                setAlignment(Align.center)
            }).width(diplomacyScreen.rightSideLabelWidth()).row()
        }

        val atWar = otherCiv.isAtWarWith(viewingCiv)

        val nextLevelString = when {
            atWar -> ""
            otherCivDiplomacyManager.getInfluence().toInt() < 30 -> "Reach 30 for friendship."
            ally == viewingCiv -> ""
            else -> "Reach highest influence above 60 for alliance."
        }
        diplomacyTable.add(diplomacyScreen.getRelationshipTable(otherCivDiplomacyManager)).row()
        if (nextLevelString.isNotEmpty()) {
            diplomacyTable.add(nextLevelString.toLabel()).row()
        }
        diplomacyTable.row().padTop(15f)

        val relationLevel = otherCivDiplomacyManager.relationshipIgnoreAfraid()
        if (relationLevel >= RelationshipLevel.Friend) {
            // RelationshipChange = Ally -> Friend or Friend -> Favorable
            val turnsToRelationshipChange = otherCivDiplomacyManager.getTurnsToRelationshipChange()
            if (turnsToRelationshipChange != 0)
                diplomacyTable.add("Relationship changes in another [$turnsToRelationshipChange] turns".toLabel())
                    .row()
        }

        fun addBonusLabels(header: String, bonusLevel: RelationshipLevel, currentRelationLevel: RelationshipLevel) {

            val bonuses = CityStateFunctions
                .getCityStateBonuses(otherCiv.cityStateType, bonusLevel)
                .filterNot { it.isHiddenToUsers() }
            if (bonuses.none()) return
            
            val headerColor = if (currentRelationLevel == bonusLevel) Color.GREEN else Color.WHITE
            diplomacyTable.add(header.toLabel(fontColor = headerColor).apply { setAlignment(Align.center) }).row()
            val gameContext = GameContext(viewingCiv)
            for (bonus in bonuses) {
                val bonusLabelColor = if (currentRelationLevel == bonusLevel && bonus.conditionalsApply(gameContext))
                    Color.GREEN else Color.GRAY
                val bonusLabel = ColorMarkupLabel(bonus.getDisplayText(), bonusLabelColor)
                    .apply { setAlignment(Align.center) }
                diplomacyTable.add(bonusLabel).row()
            }
        }
        addBonusLabels("When Friends:", RelationshipLevel.Friend, relationLevel)
        addBonusLabels("When Allies:", RelationshipLevel.Ally, relationLevel)

        if (otherCiv.cityStateUniqueUnit != null) {
            val unitName = otherCiv.cityStateUniqueUnit
            val techNames = viewingCiv.gameInfo.ruleset.units[otherCiv.cityStateUniqueUnit]!!.requiredTechs()
            val techAndTech = techNames.joinToString(" and ")
            val isOrAre = if (techNames.count() == 1) "is" else "are"
            diplomacyTable.add("[${otherCiv.civName}] is able to provide [${unitName}] once [${techAndTech}] [${isOrAre}] researched.".toLabel(fontSize = Constants.defaultFontSize)).row()
        }

        return diplomacyTable
    }


    private fun getRevokeProtectionButton(otherCiv: Civilization): TextButton {
        val revokeProtectionButton = "Revoke Protection".toTextButton()
        revokeProtectionButton.onClick { revokeProtection(otherCiv) }
        if (diplomacyScreen.isNotPlayersTurn() || !otherCiv.cityStateFunctions.otherCivCanWithdrawProtection(viewingCiv))
            revokeProtectionButton.disable()
        return revokeProtectionButton
    }

    private fun revokeProtection(otherCiv: Civilization) {
        ConfirmPopup(diplomacyScreen, "Revoke protection for [${otherCiv.civName}]?", "Revoke Protection") {
            otherCiv.cityStateFunctions.removeProtectorCiv(viewingCiv)
            diplomacyScreen.updateLeftSideTable(otherCiv)
            diplomacyScreen.updateRightSide(otherCiv)
        }.open()
    }

    private fun getPledgeToProtectButton(otherCiv: Civilization): TextButton {
        val protectionButton = "Pledge to protect".toTextButton()
        protectionButton.onClick { pledgeToProtect(otherCiv) }
        if (diplomacyScreen.isNotPlayersTurn() || !otherCiv.cityStateFunctions.otherCivCanPledgeProtection(viewingCiv))
            protectionButton.disable()
        return protectionButton
    }

    private fun pledgeToProtect(otherCiv: Civilization) {
        ConfirmPopup(
            diplomacyScreen,
            "Declare Protection of [${otherCiv.civName}]?",
            "Pledge to protect",
            true
        ) {
            otherCiv.cityStateFunctions.addProtectorCiv(viewingCiv)
            diplomacyScreen.updateLeftSideTable(otherCiv)
            diplomacyScreen.updateRightSide(otherCiv)
        }.open()
    }

    private fun getNegotiatePeaceCityStateButton(
        otherCiv: Civilization,
        otherCivDiplomacyManager: DiplomacyManager
    ): TextButton {
        val peaceButton = "Negotiate Peace".toTextButton()
        peaceButton.onClick { negotiatePeace(otherCiv) }
        val cityStatesAlly = otherCiv.allyCiv
        val atWarWithItsAlly = viewingCiv.getKnownCivs()
            .any { it == cityStatesAlly && it.isAtWarWith(viewingCiv) }
        if (diplomacyScreen.isNotPlayersTurn() || atWarWithItsAlly) peaceButton.disable()

        if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclaredWar)) {
            peaceButton.disable() // Can't trade for 10 turns after war was declared
            val turnsLeft = otherCivDiplomacyManager.getFlag(DiplomacyFlags.DeclaredWar)
            peaceButton.setText(peaceButton.text.toString() + "\n${turnsLeft.tr()}" + Fonts.turn)
        }

        return peaceButton
    }

    /** The same rules as the classic Negotiate Peace button */
    private fun canNegotiatePeace(otherCiv: Civilization, otherCivDiplomacyManager: DiplomacyManager): Boolean {
        val cityStatesAlly = otherCiv.allyCiv
        val atWarWithItsAlly = viewingCiv.getKnownCivs().any { it == cityStatesAlly && it.isAtWarWith(viewingCiv) }
        return !diplomacyScreen.isNotPlayersTurn() && !atWarWithItsAlly && !otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclaredWar)
    }

    private fun negotiatePeace(otherCiv: Civilization) {
        ConfirmPopup(
            diplomacyScreen,
            "Peace with [${otherCiv.civName}]?",
            "Negotiate Peace",
            true
        ) {
            val tradeLogic = TradeLogic(viewingCiv, otherCiv)
            tradeLogic.currentTrade.ourOffers.add(
                TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = viewingCiv.gameInfo.speed)
            )
            tradeLogic.currentTrade.theirOffers.add(
                TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = viewingCiv.gameInfo.speed)
            )
            tradeLogic.acceptTrade()
            diplomacyScreen.updateLeftSideTable(otherCiv)
            diplomacyScreen.updateRightSide(otherCiv)
        }.open()
    }

    private fun getImproveTilesButton(
        otherCiv: Civilization,
        otherCivDiplomacyManager: DiplomacyManager
    ): TextButton? {
        if (!needsImprovements(otherCiv)) return null


        val improveTileButton = "Gift Improvement".toTextButton()
        improveTileButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getImprovementGiftTable(otherCiv)))
        }


        if (diplomacyScreen.isNotPlayersTurn() || otherCivDiplomacyManager.getInfluence() < 60)
            improveTileButton.disable()
        return improveTileButton
    }

    private fun needsImprovements(otherCiv: Civilization): Boolean {
        if (otherCiv.cities.isEmpty()) return false
        val improvableResourceTiles = getImprovableResourceTiles(otherCiv)
        val improvements =
            otherCiv.gameInfo.ruleset.tileImprovements.filter { it.value.turnsToBuild != -1 }
        var needsImprovements = false

        for (improvableTile in improvableResourceTiles)
            for (tileImprovement in improvements.values)
                if (improvableTile.tileResource!!.isImprovedBy(tileImprovement.name)
                    && improvableTile.improvementFunctions.canBuildImprovement(tileImprovement, otherCiv.state)
                )
                    needsImprovements = true
        return needsImprovements
    }

    private fun getDiplomaticMarriageButton(otherCiv: Civilization): TextButton? {
        if (!viewingCiv.hasUnique(UniqueType.CityStateCanBeBoughtForGold))
            return null

        val diplomaticMarriageButton =
            "Diplomatic Marriage ([${otherCiv.cityStateFunctions.getDiplomaticMarriageCost()}] Gold)".toTextButton()
        diplomaticMarriageButton.onClick { diplomaticMarriage(otherCiv) }
        if (diplomacyScreen.isNotPlayersTurn() || !otherCiv.cityStateFunctions.canBeMarriedBy(viewingCiv))
            diplomaticMarriageButton.disable()
        return diplomaticMarriageButton
    }

    private fun diplomaticMarriage(otherCiv: Civilization) {
        val newCities = otherCiv.cities
        otherCiv.cityStateFunctions.diplomaticMarriage(viewingCiv)
        UncivGame.Current.popScreen() // The other civ will no longer exist
        for (city in newCities)
            viewingCiv.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, city.id))   // Player gets to choose between annex and puppet
    }

    private fun getGoldGiftTable(otherCiv: Civilization): Table {
        val diplomacyTable = getCityStateDiplomacyTableHeader(otherCiv)
        diplomacyTable.addSeparator()

        for (giftAmount in listOf(250, 500, 1000)) {
            val influenceAmount = otherCiv.cityStateFunctions.influenceGainedByGift(viewingCiv, giftAmount)
            val giftButton =
                "Gift [$giftAmount] gold (+[$influenceAmount] influence)".toTextButton()
            giftButton.onClick {
                otherCiv.cityStateFunctions.receiveGoldGift(viewingCiv, giftAmount)
                diplomacyScreen.updateLeftSideTable(otherCiv)
                diplomacyScreen.updateRightSide(otherCiv)
            }
            diplomacyTable.add(giftButton).row()
            if (viewingCiv.gold < giftAmount || diplomacyScreen.isNotPlayersTurn()) giftButton.disable()
        }

        val backButton = "Back".toTextButton()
        backButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getCityStateDiplomacyTable(otherCiv)))
        }
        diplomacyTable.add(backButton)
        return diplomacyTable
    }

    private fun getImprovableResourceTiles(otherCiv:Civilization) = otherCiv.cities.flatMap { it.getTiles() }.filter {
        val resource = it.tileResource
        otherCiv.canSeeResource(resource) &&
            resource.resourceType != ResourceType.Bonus &&
            (it.improvement == null || !resource.isImprovedBy(it.improvement!!))
    }

    private fun getImprovementGiftTable(otherCiv: Civilization): Table {
        val improvementGiftTable = getCityStateDiplomacyTableHeader(otherCiv)
        improvementGiftTable.addSeparator()

        val improvableResourceTiles = getImprovableResourceTiles(otherCiv)
        val tileImprovements =
            otherCiv.gameInfo.ruleset.tileImprovements

        for (improvableTile in improvableResourceTiles) {
            for (tileImprovement in tileImprovements.values) {
                if (improvableTile.tileResource!!.isImprovedBy(tileImprovement.name)
                    && improvableTile.improvementFunctions.canBuildImprovement(tileImprovement, otherCiv.state)
                ) {
                    val improveTileButton =
                        "Build [${tileImprovement}] on [${improvableTile.tileResource}] (200 Gold)".toTextButton()
                    improveTileButton.onClick {
                        buildImprovementGift(otherCiv, improvableTile, tileImprovement)
                        diplomacyScreen.rightSideTable.clear()
                        diplomacyScreen.rightSideTable.add(ScrollPane(getCityStateDiplomacyTable(otherCiv)))
                    }
                    if (viewingCiv.gold < 200)
                        improveTileButton.disable()
                    improvementGiftTable.add(improveTileButton).row()
                }
            }
        }

        val backButton = "Back".toTextButton()
        backButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getCityStateDiplomacyTable(otherCiv)))
        }
        improvementGiftTable.add(backButton)
        return improvementGiftTable

    }

    private fun buildImprovementGift(otherCiv: Civilization, improvableTile: Tile, tileImprovement: TileImprovement) {
        viewingCiv.addGold(-200)
        improvableTile.stopWorkingOnImprovement()
        improvableTile.setImprovement(tileImprovement, otherCiv)
        otherCiv.cache.updateCivResources()
    }

    private fun getDemandTributeTable(otherCiv: Civilization): Table {
        val diplomacyTable = getCityStateDiplomacyTableHeader(otherCiv)
        diplomacyTable.addSeparator()
        diplomacyTable.add("Tribute Willingness".toLabel()).row()
        val modifierTable = Table()
        val tributeModifiers = otherCiv.cityStateFunctions.getTributeModifiers(viewingCiv, requireWholeList = true)
        for (item in tributeModifiers) {
            val color = if (item.value >= 0) Color.GREEN else Color.RED
            modifierTable.add(item.key.toLabel(color))
            modifierTable.add(item.value.tr().toLabel(color)).row()
        }
        modifierTable.add("Sum:".toLabel())
        modifierTable.add(tributeModifiers.values.sum().toLabel()).row()
        diplomacyTable.add(modifierTable).row()
        diplomacyTable.add("At least 0 to take gold, at least 30 and size 4 city for worker".toLabel()).row()
        diplomacyTable.addSeparator()

        val demandGoldButton = "Take [${otherCiv.cityStateFunctions.goldGainedByTribute()}] gold (-15 Influence)".toTextButton()
        demandGoldButton.onClick {
            otherCiv.cityStateFunctions.tributeGold(viewingCiv)
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getCityStateDiplomacyTable(otherCiv)))
        }
        diplomacyTable.add(demandGoldButton).row()
        if (otherCiv.cityStateFunctions.getTributeWillingness(viewingCiv, demandingWorker = false) < 0)   demandGoldButton.disable()

        val demandWorkerButton = "Take worker (-50 Influence)".toTextButton()
        demandWorkerButton.onClick {
            otherCiv.cityStateFunctions.tributeWorker(viewingCiv)
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getCityStateDiplomacyTable(otherCiv)))
        }
        diplomacyTable.add(demandWorkerButton).row()
        if (otherCiv.cityStateFunctions.getTributeWillingness(viewingCiv, demandingWorker = true) < 0)    demandWorkerButton.disable()

        val backButton = "Back".toTextButton()
        backButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getCityStateDiplomacyTable(otherCiv)))
        }
        diplomacyTable.add(backButton)
        return diplomacyTable
    }

    private fun getQuestTable(assignedQuest: AssignedQuest): Table {
        val questTable = Table()
        questTable.defaults().pad(10f)

        val quest: Quest = assignedQuest.quest
        val remainingTurns: Int = assignedQuest.getRemainingTurns()
        val title = if (quest.influence > 0)
            "[${quest.name}] (+[${quest.influence.toInt()}] influence)"
        else
            quest.name
        val description = assignedQuest.getDescription()

        questTable.add(title.toLabel(fontSize = Constants.headingFontSize)).row()
        questTable.add(description.toLabel().apply { wrap = true; setAlignment(Align.center) })
            .width(diplomacyScreen.stage.width / 2).row()
        if (quest.duration > 0)
            questTable.add("[${remainingTurns}] turns remaining".toLabel()).row()
        if (quest.isGlobal()) {
            val leaderString = assignedQuest.assignerCiv.questManager.getScoreStringForGlobalQuest(assignedQuest)
            if (leaderString.isNotEmpty())
                questTable.add(leaderString.toLabel()).row()
        }

        questTable.onClick {
            assignedQuest.onClickAction()
        }
        return questTable
    }

    private fun getWarWithMajorTable(target: Civilization, otherCiv: Civilization): Table {
        val warTable = Table()
        warTable.defaults().pad(10f)

        val title = "War against [${target.civName}]"
        val description = "We need you to help us defend against [${target.civName}]. Killing [${otherCiv.questManager.unitsToKill(target)}] of their military units would slow their offensive."
        val progress = if (viewingCiv.knows(target)) "Currently you have killed [${otherCiv.questManager.unitsKilledSoFar(target, viewingCiv)}] of their military units."
        else "You need to find them first!"

        warTable.add(title.toLabel(fontSize = Constants.headingFontSize)).row()
        warTable.add(description.toLabel().apply { wrap = true; setAlignment(Align.center) })
            .width(diplomacyScreen.stage.width / 2).row()
        warTable.add(progress.toLabel().apply { wrap = true; setAlignment(Align.center) })
            .width(diplomacyScreen.stage.width / 2).row()

        return warTable
    }
}
