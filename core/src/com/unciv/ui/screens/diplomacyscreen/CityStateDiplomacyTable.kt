package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
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
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Quest
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.tile.ResourceType
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
import com.unciv.ui.screens.basescreen.BaseScreen

class CityStateDiplomacyTable(private val diplomacyScreen: DiplomacyScreen) {
    val viewingCiv = diplomacyScreen.viewingCiv

    fun getCityStateDiplomacyTable(otherCiv: Civilization): Table {
        if (diplomacyScreen.isPortrait()) return getPortraitCityStateTable(otherCiv)
        return getClassicCityStateTable(otherCiv)
    }

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

    private fun getPortraitCityStateTable(otherCiv: Civilization): Table {
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
        val scale = 1f
        val width = diplomacyScreen.portraitWidth
        val railWidth = 120f * scale
        val bodyHeight = (diplomacyScreen.portraitHeight - 262f * scale).coerceAtLeast(360f * scale)
        val trackHeight = bodyHeight - 12f * scale
        val trackLength = trackHeight - 56f * scale
        val maximum = maxOf(100, current, rivals.maxOfOrNull { it.second } ?: 0)
        val root = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/CityStatePortrait",
                tintColor = colorFromRGB(18, 37, 54))
            top()
        }
        val header = Table()
        header.add(ImageGetter.getImage("CityStateIcons/${otherCiv.cityStateType.name}")
            .surroundWithCircle(48f * scale, color = otherCiv.nation.getOuterColor()))
            .size(48f * scale).padRight(10f * scale)
        val name = Table().apply { left() }
        name.add(otherCiv.civName.toLabel(Color.WHITE, (22f * scale).toInt(), hideIcons = true)).left().row()
        name.add("${otherCiv.cityStateType.name} · ${manager.relationshipLevel().name} ($current)".toLabel(
            colorFromRGB(170, 198, 218), (13f * scale).toInt())).left().row()
        header.add(name).growX().left()
        root.add(header).width(width - 28f * scale).height(72f * scale).left().padLeft(14f * scale).row()

        val content = Table().apply { top() }
        val track = Group().apply { setSize(railWidth, trackHeight) }
        val trackLine = ImageGetter.getWhiteDot().apply {
            color = Color.WHITE.cpy().apply { a = 0.12f }
            setSize(14f * scale, trackLength)
            setPosition(51f * scale, 26f * scale)
        }
        track.addActor(trackLine)
        fun position(value: Int): Float = 26f * scale +
            (value.coerceIn(0, maximum).toFloat() / maximum) * trackLength
        for ((value, label, color) in listOf(
            Triple(30, "Friend", colorFromRGB(143, 216, 255)),
            Triple(60, "Ally", Color.GOLD)
        )) {
            val y = position(value)
            val line = ImageGetter.getWhiteDot().apply {
                this.color = color
                setBounds(44f * scale, y, 72f * scale, 3f * scale)
            }
            track.addActor(line)
            track.addActor("$label\n$value".toLabel(color, (12f * scale).toInt()).apply {
                setAlignment(Align.right)
                setBounds(0f, y - 21f * scale, 42f * scale, 42f * scale)
            })
        }
        data class InfluenceMarker(
            var value: Int,
            val group: Group,
            val portrait: Group,
            val label: Label,
        )
        val markers = mutableListOf<InfluenceMarker>()
        fun marker(civ: Civilization, value: Int, preview: Boolean = false): InfluenceMarker {
            val marker = Group().apply {
                setSize(railWidth, trackHeight)
                touchable = Touchable.disabled
            }
            val circle = Group().apply {
                setSize(36f * scale, 36f * scale)
            }
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
        content.add(track).width(railWidth).height(trackHeight).top().padRight(6f * scale)

        val actions = Table().apply { top(); defaults().padBottom(8f * scale) }
        val actionWidth = width - railWidth - 34f * scale
        actions.add("Ways to gain".toLabel(colorFromRGB(174, 199, 217), (16f * scale).toInt()))
            .left().padBottom(8f * scale).row()
        val summary = "Tap a way to gain to see where it moves you".toLabel(
            colorFromRGB(185, 209, 225), (14f * scale).toInt()).apply { wrap = true }
        val actionHolder = Table()
        var selectedAction = false
        var selectedFrame: Table? = null
        fun select(delta: Int, label: String, enabled: Boolean = true, action: () -> Unit) {
            val result = current + delta
            preview.group.isVisible = delta != 0
            preview.value = result
            preview.label.setText(result.toString())
            layoutMarkers()
            val lead = rivals.maxOfOrNull { it.second }
            val projectedAlly = otherCiv.diplomacy.values
                .filter { it.otherCiv.isMajorCiv() && !it.otherCiv.isDefeated() }
                .maxByOrNull { if (it.otherCiv == viewingCiv) manager.getInfluence() + delta else it.getInfluence() }
                ?.otherCiv
            summary.setText(if (delta == 0) "Influence rests at ${manager.getCityStateInfluenceRestingPoint().toInt() + 10} after a pledge"
                else "You would reach $result${if (result >= 60 && projectedAlly == viewingCiv) ": Ally" else if (result >= 30) ": Friend" else ""}" +
                    (if (lead != null && result > lead) ", ${result - lead} ahead of your closest rival" else ""))
            actionHolder.clear()
            val button = label.toTextButton()
            button.onClick(action)
            if (!enabled) button.disable()
            diplomacyScreen.stylePortraitPrimary(button)
            actionHolder.add(button).growX().height(56f * scale)
        }
        fun row(title: String, detail: String, delta: Int, enabled: Boolean = true,
                icon: String? = null, action: () -> Unit) {
            val frame = Table().apply { pad(2f * scale) }
            val inside = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/Way",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(31, 52, 71))
            }
            val iconTile = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/WayIcon",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(45, 79, 108))
            }
            iconTile.add(when (icon) {
                "gift" -> ImageGetter.getStatIcon("Gold")
                "pledge" -> ImageGetter.getImage("OtherIcons/Shield")
                else -> ImageGetter.getImage(NotificationIcon.Quest)
            }).size(29f * scale)
            inside.add(iconTile).size(42f * scale).padLeft(7f * scale).padRight(8f * scale)
            val labels = Table().apply { left() }
            labels.add(title.toLabel(if (enabled) Color.WHITE else colorFromRGB(137, 160, 177),
                (15f * scale).toInt()).apply { wrap = true }).width(115f * scale).left().row()
            labels.add(detail.toLabel(colorFromRGB(174, 199, 217), (12f * scale).toInt()).apply {
                wrap = true
            }).width(115f * scale).left().row()
            inside.add(labels).width(115f * scale).growX().left()
            if (delta > 0) {
                val gain = Table().apply {
                    background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/WayGain",
                        BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(43, 79, 104))
                }
                gain.add("+$delta".toLabel(colorFromRGB(143, 216, 255), (15f * scale).toInt()))
                inside.add(gain).size(44f * scale, 30f * scale).padRight(6f * scale)
            } else inside.add().width(6f * scale)
            frame.add(inside).width(actionWidth - 4f * scale).height(66f * scale)
            fun choose() {
                selectedFrame?.background = null
                frame.background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/SelectedWay",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, Color.GOLD)
                selectedFrame = frame
                select(delta, title, enabled, action)
            }
            frame.onClick { choose() }
            actions.add(frame).width(actionWidth).height(70f * scale).row()
            if (enabled && !selectedAction) {
                choose()
                selectedAction = true
            }
        }
        for (quest in quests) {
            val delta = quest.quest.influence.toInt()
            val detail = if (quest.quest.duration > 0)
                "[${quest.getRemainingTurns()}] turns remaining" else quest.getDescription()
            row(quest.quest.name, detail, delta) { quest.onClickAction() }
        }
        for (amount in listOf(250, 500, 1000)) {
            val delta = giftInfluence(amount)
            val enabled = viewingCiv.gold >= amount && !diplomacyScreen.isNotPlayersTurn() &&
                !viewingCiv.isAtWarWith(otherCiv)
            row("Gift [$amount] gold", "You have [${viewingCiv.gold}] gold", delta, enabled, "gift") {
                otherCiv.cityStateFunctions.receiveGoldGift(viewingCiv, amount)
                diplomacyScreen.updateLeftSideTable(otherCiv)
                diplomacyScreen.updateRightSide(otherCiv)
            }
        }
        if (manager.diplomaticStatus != DiplomaticStatus.Protector) {
            val enabled = !diplomacyScreen.isNotPlayersTurn() &&
                otherCiv.cityStateFunctions.otherCivCanPledgeProtection(viewingCiv)
            val restingPoint = manager.getCityStateInfluenceRestingPoint().toInt() + 10
            row("Pledge to protect", "Influence will rest at [$restingPoint]", 0, enabled, "pledge") {
                ConfirmPopup(diplomacyScreen, "Declare Protection of [${otherCiv.civName}]?", "Pledge to protect", true) {
                    otherCiv.cityStateFunctions.addProtectorCiv(viewingCiv)
                    diplomacyScreen.updateLeftSideTable(otherCiv)
                    diplomacyScreen.updateRightSide(otherCiv)
                }.open()
            }
        } else {
            row("Revoke protection", "End your pledge to protect", 0,
                !diplomacyScreen.isNotPlayersTurn() && otherCiv.cityStateFunctions.otherCivCanWithdrawProtection(viewingCiv), "pledge") {
                ConfirmPopup(diplomacyScreen, "Revoke protection for [${otherCiv.civName}]?", "Revoke Protection") {
                    otherCiv.cityStateFunctions.removeProtectorCiv(viewingCiv)
                    diplomacyScreen.updateLeftSideTable(otherCiv)
                    diplomacyScreen.updateRightSide(otherCiv)
                }.open()
            }
        }
        val allOptions = "All options".toTextButton()
        allOptions.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getClassicCityStateTable(otherCiv))).grow()
        }
        actions.add(allOptions).width(actionWidth).height(48f * scale).row()
        content.add(ScrollPane(actions).apply { fadeScrollBars = false }).width(actionWidth)
            .height(bodyHeight).top()
        root.add(content).width(width).height(bodyHeight).row()
        val footer = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/CityStateFooter",
                tintColor = colorFromRGB(15, 32, 48))
            defaults().pad(7f * scale)
        }
        footer.add(summary).width(width - 28f * scale).height(44f * scale).left().row()
        val buttons = Table()
        val tribute = "Tribute".toTextButton()
        tribute.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getDemandTributeTable(otherCiv))).grow()
        }
        if (diplomacyScreen.isNotPlayersTurn() || viewingCiv.isAtWarWith(otherCiv)) tribute.disable()
        buttons.add(tribute).width(100f * scale).height(56f * scale).padRight(8f * scale)
        if (!selectedAction) actionHolder.add("Pick a way to gain".toTextButton().apply { disable() })
        buttons.add(actionHolder).width(width - 136f * scale).height(56f * scale)
        footer.add(buttons).width(width - 28f * scale).row()
        root.add(footer).width(width).height(120f * scale)
        return root
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
        revokeProtectionButton.onClick {
            ConfirmPopup(diplomacyScreen, "Revoke protection for [${otherCiv.civName}]?", "Revoke Protection") {
                otherCiv.cityStateFunctions.removeProtectorCiv(viewingCiv)
                diplomacyScreen.updateLeftSideTable(otherCiv)
                diplomacyScreen.updateRightSide(otherCiv)
            }.open()
        }
        if (diplomacyScreen.isNotPlayersTurn() || !otherCiv.cityStateFunctions.otherCivCanWithdrawProtection(viewingCiv))
            revokeProtectionButton.disable()
        return revokeProtectionButton
    }

    private fun getPledgeToProtectButton(otherCiv: Civilization): TextButton {
        val protectionButton = "Pledge to protect".toTextButton()
        protectionButton.onClick {
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
        if (diplomacyScreen.isNotPlayersTurn() || !otherCiv.cityStateFunctions.otherCivCanPledgeProtection(viewingCiv))
            protectionButton.disable()
        return protectionButton
    }

    private fun getNegotiatePeaceCityStateButton(
        otherCiv: Civilization,
        otherCivDiplomacyManager: DiplomacyManager
    ): TextButton {
        val peaceButton = "Negotiate Peace".toTextButton()
        peaceButton.onClick {
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

    private fun getImproveTilesButton(
        otherCiv: Civilization,
        otherCivDiplomacyManager: DiplomacyManager
    ): TextButton? {
        if (otherCiv.cities.isEmpty()) return null
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

        if (!needsImprovements) return null


        val improveTileButton = "Gift Improvement".toTextButton()
        improveTileButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(ScrollPane(getImprovementGiftTable(otherCiv)))
        }


        if (diplomacyScreen.isNotPlayersTurn() || otherCivDiplomacyManager.getInfluence() < 60)
            improveTileButton.disable()
        return improveTileButton
    }

    private fun getDiplomaticMarriageButton(otherCiv: Civilization): TextButton? {
        if (!viewingCiv.hasUnique(UniqueType.CityStateCanBeBoughtForGold))
            return null

        val diplomaticMarriageButton =
            "Diplomatic Marriage ([${otherCiv.cityStateFunctions.getDiplomaticMarriageCost()}] Gold)".toTextButton()
        diplomaticMarriageButton.onClick {
            val newCities = otherCiv.cities
            otherCiv.cityStateFunctions.diplomaticMarriage(viewingCiv)
            UncivGame.Current.popScreen() // The other civ will no longer exist
            for (city in newCities)
                viewingCiv.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, city.id))   // Player gets to choose between annex and puppet
        }
        if (diplomacyScreen.isNotPlayersTurn() || !otherCiv.cityStateFunctions.canBeMarriedBy(viewingCiv))
            diplomaticMarriageButton.disable()
        return diplomaticMarriageButton
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
                        viewingCiv.addGold(-200)
                        improvableTile.stopWorkingOnImprovement()
                        improvableTile.setImprovement(tileImprovement, otherCiv)
                        otherCiv.cache.updateCivResources()
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
