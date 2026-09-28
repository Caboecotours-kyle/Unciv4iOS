package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.popups.TraySheet
import kotlin.math.roundToInt

class MajorCivDiplomacyTable(private val diplomacyScreen: DiplomacyScreen) {
    val viewingCiv = diplomacyScreen.viewingCiv

    fun getMajorCivDiplomacyTable(otherCiv: Civilization): Table {
        val otherCivDiplomacyManager = otherCiv.getDiplomacyManager(viewingCiv)!!

        val diplomacyTable = Table()
        diplomacyTable.defaults().pad(10f)

        val helloText: String
        val helloVoice: String
        if (otherCivDiplomacyManager.isRelationshipLevelLE(RelationshipLevel.Enemy)) {
            helloText = otherCiv.nation.hateHello
            helloVoice = "${otherCiv.civName}.hateHello"
        } else {
            helloText = otherCiv.nation.neutralHello
            helloVoice = "${otherCiv.civName}.neutralHello"
        }
        val leaderIntroTable = LeaderIntroTable(otherCiv, helloText)
        diplomacyTable.add(leaderIntroTable).row()
        diplomacyTable.addSeparator()

        val diplomaticRelationshipsCanChange =
            !viewingCiv.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)

        val diplomacyManager = viewingCiv.getDiplomacyManager(otherCiv)!!

        if (!viewingCiv.isAtWarWith(otherCiv)) {
            diplomacyTable.add(getTradeButton(otherCiv)).row()


            if (!diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                diplomacyTable.add(getDeclareFriendshipButton(otherCiv)).row()

            if (!diplomacyManager.hasFlag(DiplomacyFlags.Denunciation)
                && !diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
            ) diplomacyTable.add(getDenounceButton(otherCiv, diplomacyManager)).row()

            if (diplomaticRelationshipsCanChange)
                diplomacyTable.add(diplomacyScreen.getDeclareWarButton(diplomacyManager, otherCiv)).row()

        } else if (diplomaticRelationshipsCanChange) {
            val negotiatePeaceButton =
                getNegotiatePeaceMajorCivButton(otherCiv, otherCivDiplomacyManager)

            diplomacyTable.add(negotiatePeaceButton).row()
        }


        val demandsButton = "Demands".toTextButton()
        demandsButton.onClick {
            diplomacyScreen.rightSideTable.clear()
            diplomacyScreen.rightSideTable.add(getDemandsTable(viewingCiv, otherCiv))
        }
        diplomacyTable.add(demandsButton).row()
        if (diplomacyScreen.isNotPlayersTurn()) demandsButton.disable()

        if (otherCiv.getCapital() != null && viewingCiv.hasExplored(otherCiv.getCapital()!!.getCenterTile()))
            diplomacyTable.add(diplomacyScreen.getGoToOnMapButton(otherCiv)).row()

        if (otherCiv.isHuman())
            diplomacyTable.add(diplomacyScreen.getHumanRelationshipTable(otherCivDiplomacyManager)).row()
        else {
            diplomacyTable.add(diplomacyScreen.getRelationshipTable(otherCivDiplomacyManager)).row()
            diplomacyTable.add(getDiplomacyModifiersTable(otherCivDiplomacyManager)).row()
        }
        val promisesTable = getPromisesTable(diplomacyManager, otherCivDiplomacyManager)
        if (promisesTable != null) diplomacyTable.add(promisesTable).row()

        // Starting playback here assumes the MajorCivDiplomacyTable is shown immediately
        UncivGame.Current.musicController.playVoice(helloVoice)

        return diplomacyTable
    }


    /** Portrait: the civ's page on the tray sheet (mock B "Civ detail"): leader header with back and close, where you
     *  stand as chips, what they think of you, every action as a full-width row (Declare war last) and Trade in the footer */
    fun showPortraitPage(otherCiv: Civilization, playVoice: Boolean = true) {
        val otherCivDiplomacyManager = otherCiv.getDiplomacyManager(viewingCiv)!!
        val diplomacyManager = viewingCiv.getDiplomacyManager(otherCiv)!!
        val hate = otherCivDiplomacyManager.isRelationshipLevelLE(RelationshipLevel.Enemy)
        val helloText = if (hate) otherCiv.nation.hateHello else otherCiv.nation.neutralHello
        val helloVoice = if (hate) "${otherCiv.civName}.hateHello" else "${otherCiv.civName}.neutralHello"
        val sheet = diplomacyScreen.portraitPage(otherCiv.nation.leaderName.ifEmpty { otherCiv.civName }, otherCiv.civName,
            diplomacyScreen.leaderLead(otherCiv)) { diplomacyScreen.showPortraitList() }
        sheet.minShare = .75f
        val atWar = viewingCiv.isAtWarWith(otherCiv)
        val notMyTurn = diplomacyScreen.isNotPlayersTurn()
        val canChange = !viewingCiv.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)

        if (helloText.isNotEmpty()) sheet.addToBody(diplomacyScreen.portraitQuote(helloText), 4f)

        val relationship = if (otherCiv.isHuman()) otherCivDiplomacyManager.run {
            when {
                diplomaticStatus == DiplomaticStatus.DefensivePact -> Constants.defensivePact
                hasFlag(DiplomacyFlags.DeclarationOfFriendship) -> RelationshipLevel.Friend.name
                diplomaticStatus == DiplomaticStatus.War -> RelationshipLevel.Enemy.name
                else -> RelationshipLevel.Neutral.name
            }.tr()
        } else "${otherCivDiplomacyManager.relationshipLevel().name.tr()}  ${otherCivDiplomacyManager.opinionOfOtherCiv().toInt()}"
        val status = when (diplomacyManager.diplomaticStatus) {
            DiplomaticStatus.DefensivePact -> Constants.defensivePact
            else -> diplomacyManager.diplomaticStatus.name
        }
        sheet.addToBody(sheet.statGrid(listOf(
            sheet.statChip(ImageGetter.getImage("OtherIcons/DiplomacyW").apply { color = TrayColors.INK2 }, relationship),
            sheet.statChip(null, status.tr())
        ), 2).apply { name = "portrait-civ-stats" }, 10f).padLeft(sheet.pt(4f)).padRight(sheet.pt(4f))

        // Why they feel this way, and promises in force: readable, wrapping lines
        val notes = Table().left()
        if (!otherCiv.isHuman()) for ((text, value) in getDiplomacyModifiers(otherCivDiplomacyManager))
            notes.add(text.toLabel(if (value < 0) TrayColors.REASON else Color.valueOf("8ff07e"), sheet.font(14f), hideIcons = true)
                .apply { wrap = true }).growX().left().padBottom(sheet.pt(4f)).row()
        for (text in getPromises(diplomacyManager, otherCivDiplomacyManager))
            notes.add(text.toLabel(TrayColors.INK2, sheet.font(14f), hideIcons = true).apply { wrap = true })
                .growX().left().padBottom(sheet.pt(4f)).row()
        if (notes.hasChildren()) sheet.addToBody(notes, 10f).padLeft(sheet.pt(16f)).padRight(sheet.pt(16f))

        fun disc(icon: String) = sheet.disc(ImageGetter.getImage(icon))
        fun state(enabled: Boolean) = if (enabled) TraySheet.RowState.Normal else TraySheet.RowState.Disabled
        val rows = ArrayList<Actor>()
        if (!atWar) {
            if (!diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) {
                val enabled = !notMyTurn && !friendshipOffered(otherCiv)
                rows += sheet.row("Offer Declaration of Friendship ([30] turns)", lead = disc("OtherIcons/Diplomacy"), state = state(enabled),
                    onTap = if (enabled) ({ offerFriendship(otherCiv); showPortraitPage(otherCiv, false) }) else null)
            }
            if (!diplomacyManager.hasFlag(DiplomacyFlags.Denunciation) && !diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                rows += sheet.row("Denounce ([30] turns)", lead = disc("OtherIcons/ExclamationMark"), state = state(!notMyTurn),
                    onTap = if (!notMyTurn) ({ confirmDenounce(otherCiv, diplomacyManager) }) else null)
        } else if (canChange) {
            val turnsLeft = if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclaredWar))
                otherCivDiplomacyManager.getFlag(DiplomacyFlags.DeclaredWar) else 0
            val enabled = !notMyTurn && turnsLeft == 0 // Can't trade for 10 turns after war was declared
            rows += sheet.row("Negotiate Peace", meta = if (turnsLeft > 0) "${turnsLeft.tr()}${Fonts.turn}" else null,
                lead = disc("OtherIcons/Diplomacy"), state = state(enabled), onTap = if (enabled) ({ negotiatePeace(otherCiv) }) else null)
        }
        rows += sheet.row("Demands", lead = disc("OtherIcons/Present"), state = state(!notMyTurn),
            onTap = if (!notMyTurn) ({ showPortraitDemands(otherCiv) }) else null)
        if (otherCiv.getCapital() != null && viewingCiv.hasExplored(otherCiv.getCapital()!!.getCenterTile()))
            rows += diplomacyScreen.getGoToOnMapRow(otherCiv)
        if (!atWar && canChange) rows += diplomacyScreen.getDeclareWarRow(diplomacyManager, otherCiv)
        sheet.addToBody(sheet.card(rows), 10f)

        if (!atWar) sheet.footer().add(diplomacyScreen.portraitButton("Trade", TraySheet.ButtonKind.Primary, "OtherIcons/Swap", !notMyTurn) {
            diplomacyScreen.setTrade(otherCiv).apply { offerColumnsTable.update() }
        }.apply { name = "portrait-trade" }).growX().padRight(0f)
        sheet.fit()

        // Starting playback here assumes the page is shown immediately
        if (playVoice) UncivGame.Current.musicController.playVoice(helloVoice)
    }

    /** Portrait Demands: one row per demand, off once made or agreed; back returns to the civ's page */
    private fun showPortraitDemands(otherCiv: Civilization) {
        val sheet = diplomacyScreen.portraitPage("Demands", otherCiv.civName, diplomacyScreen.leaderLead(otherCiv)) {
            showPortraitPage(otherCiv, false)
        }
        val diplomacyManager = viewingCiv.getDiplomacyManager(otherCiv)!!
        val rows = Demand.entries.filter { it.show(viewingCiv) }.map { demand ->
            val done = otherCiv.popupAlerts.any { it.type == demand.demandAlert && it.value == viewingCiv.civID } // Already demanded
                || diplomacyManager.hasFlag(demand.agreedToDemand) // already agreed
            sheet.row(demand.demandText, lead = sheet.disc(ImageGetter.getImage("OtherIcons/Present")),
                state = if (done) TraySheet.RowState.Disabled else TraySheet.RowState.Normal, chevron = false,
                onTap = if (done) null else ({
                    otherCiv.popupAlerts.add(PopupAlert(demand.demandAlert, viewingCiv.civID))
                    showPortraitDemands(otherCiv)
                }))
        }
        if (rows.isNotEmpty()) sheet.addToBody(sheet.card(rows), 4f)
        sheet.fit()
    }

    private fun getNegotiatePeaceMajorCivButton(
        otherCiv: Civilization,
        otherCivDiplomacyManager: DiplomacyManager
    ): TextButton {
        val negotiatePeaceButton = "Negotiate Peace".toTextButton()
        negotiatePeaceButton.onClick { negotiatePeace(otherCiv) }

        if (diplomacyScreen.isNotPlayersTurn()) negotiatePeaceButton.disable()

        if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclaredWar)) {
            negotiatePeaceButton.disable() // Can't trade for 10 turns after war was declared
            val turnsLeft = otherCivDiplomacyManager.getFlag(DiplomacyFlags.DeclaredWar)
            negotiatePeaceButton.setText(negotiatePeaceButton.text.toString() + "\n${turnsLeft.tr()}" + Fonts.turn)
        }
        return negotiatePeaceButton
    }

    private fun negotiatePeace(otherCiv: Civilization) {
        val tradeTable = diplomacyScreen.setTrade(otherCiv)
        val peaceTreaty = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = viewingCiv.gameInfo.speed)
        tradeTable.tradeView.theirStagedOffers().add(peaceTreaty)
        tradeTable.tradeView.ourStagedOffers().add(peaceTreaty)
        tradeTable.offerColumnsTable.update()
        tradeTable.enableOfferButton(true)
    }

    private fun getDenounceButton(
        otherCiv: Civilization,
        diplomacyManager: DiplomacyManager
    ): TextButton {
        val denounceButton = "Denounce ([30] turns)".toTextButton()
        denounceButton.onClick { confirmDenounce(otherCiv, diplomacyManager) }
        if (diplomacyScreen.isNotPlayersTurn()) denounceButton.disable()
        return denounceButton
    }

    private fun confirmDenounce(otherCiv: Civilization, diplomacyManager: DiplomacyManager) {
        ConfirmPopup(diplomacyScreen, "Denounce [${otherCiv.civName}]?", "Denounce ([30] turns)") {
            diplomacyManager.denounce()
            diplomacyScreen.updateLeftSideTable(otherCiv)
            diplomacyScreen.setRightSideFlavorText(
                otherCiv,
                if (otherCiv.nation.denounced.isNotEmpty()) otherCiv.nation.denounced else "We will remember this.",
                "Very well."
            )

            val music = UncivGame.Current.musicController
            music.playVoice("${otherCiv.nation.name}.denounced")
        }.open()
    }

    private fun getDeclareFriendshipButton(otherCiv: Civilization): TextButton {
        val declareFriendshipButton =
            "Offer Declaration of Friendship ([30] turns)".toTextButton()
        declareFriendshipButton.onClick {
            offerFriendship(otherCiv)
            declareFriendshipButton.disable()
        }
        if (diplomacyScreen.isNotPlayersTurn() || friendshipOffered(otherCiv))
            declareFriendshipButton.disable()
        return declareFriendshipButton
    }

    private fun offerFriendship(otherCiv: Civilization) =
        otherCiv.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, viewingCiv.civID))

    private fun friendshipOffered(otherCiv: Civilization) =
        otherCiv.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == viewingCiv.civID }

    private fun getTradeButton(otherCiv: Civilization): TextButton {
        val tradeButton = "Trade".toTextButton()
        tradeButton.onClick {
            diplomacyScreen.setTrade(otherCiv).apply {
                offerColumnsTable.update()
            }
        }
        if (diplomacyScreen.isNotPlayersTurn()) tradeButton.disable()
        return tradeButton
    }

    private fun getPromisesTable(
        diplomacyManager: DiplomacyManager,
        otherCivDiplomacyManager: DiplomacyManager
    ): Table? {
        val promisesTable = Table()
        for (text in getPromises(diplomacyManager, otherCivDiplomacyManager))
            promisesTable.add(text.toLabel(Color.LIGHT_GRAY)).row()
        return if (promisesTable.cells.isEmpty) null else promisesTable
    }

    private fun getPromises(diplomacyManager: DiplomacyManager, otherCivDiplomacyManager: DiplomacyManager): List<String> {
        val promises = ArrayList<String>()
        for (demand in Demand.entries){
            if (otherCivDiplomacyManager.hasFlag(demand.agreedToDemand)) {
                val turnsLeft = otherCivDiplomacyManager.getFlag(demand.agreedToDemand)
                promises += demand.wePromisedText.fillPlaceholders(turnsLeft.toString())
            }
            if (diplomacyManager.hasFlag(demand.agreedToDemand)) {
                val turnsLeft = diplomacyManager.getFlag(demand.agreedToDemand)
                promises += demand.theyPromisedText.fillPlaceholders(turnsLeft.toString())
            }
        }
        return promises
    }

    private fun getDiplomacyModifiersTable(otherCivDiplomacyManager: DiplomacyManager): Table {
        val diplomacyModifiersTable = Table()
        for ((text, value) in getDiplomacyModifiers(otherCivDiplomacyManager)) {
            val color = if (value < 0) Color.RED else Color.GREEN
            diplomacyModifiersTable.add(text.toLabel(color)).row()
        }
        return diplomacyModifiersTable
    }

    /** What the other leader thinks of us and why, as "text +n" with its value */
    private fun getDiplomacyModifiers(otherCivDiplomacyManager: DiplomacyManager): List<Pair<String, Float>> {
        val modifiers = ArrayList<Pair<String, Float>>()
        for (modifier in otherCivDiplomacyManager.diplomaticModifiers) {
            // Angry about attacked CS and destroyed CS do not stack
            if (modifier.key == DiplomaticModifiers.AttackedProtectedMinor.name
                && otherCivDiplomacyManager.hasModifier(DiplomaticModifiers.DestroyedProtectedMinor))
                continue

            val diplomaticModifier = DiplomaticModifiers.safeValueOf(modifier.key)
                ?: continue // This modifier is from the future, you cannot understand it yet
            var text = diplomaticModifier.text.tr() + " "
            if (modifier.value > 0) text += "+"
            text += modifier.value.roundToInt()
            modifiers += text to modifier.value
        }
        return modifiers
    }

    private fun getDemandsTable(viewingCiv: Civilization, otherCiv: Civilization): Table {
        val demandsTable = Table()
        demandsTable.defaults().pad(10f)

        val diplomacyManager = viewingCiv.getDiplomacyManager(otherCiv)!!

        for (demand in Demand.entries) {
            if (!demand.show(viewingCiv))
                continue

            val button = demand.demandText.toTextButton()

            if (otherCiv.popupAlerts.any { it.type == demand.demandAlert && it.value == viewingCiv.civID } // Already demanded
                || diplomacyManager.hasFlag(demand.agreedToDemand)) { // already agreed
                button.disable()
            } else {
                button.onClick {
                    otherCiv.popupAlerts.add(PopupAlert(demand.demandAlert, viewingCiv.civID))
                    button.disable()
                }
            }
            demandsTable.add(button).row()
        }

        demandsTable.add(Constants.close.toTextButton().onClick { diplomacyScreen.updateRightSide(otherCiv) })
        return demandsTable
    }
}
