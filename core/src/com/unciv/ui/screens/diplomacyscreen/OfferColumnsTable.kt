package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AskNumberPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.ForeignCivView
import com.unciv.view.TradeView

/** This is the class that holds the 4 columns of the offers (ours/theirs/ offered/available) in trade */
class OfferColumnsTable(
    private val tradeView: TradeView,
    private val screen: DiplomacyScreen,
    private val ourCiv: ForeignCivView,
    private val theirCiv: ForeignCivView,
    private val onChange: () -> Unit
): Table(BaseScreen.skin) {
    private val portrait = screen.isPortrait()
    private val scale = if (portrait) 1f else screen.stage.width / 393f
    private val verdictTitle = "Add items to make an offer".toLabel(Color.WHITE, (16f * scale).toInt())
    private val verdictDetail = "They decide on their turn".toLabel(colorFromRGB(174, 199, 217), (12f * scale).toInt())
    private val ourWorth = "".toLabel(colorFromRGB(174, 199, 217), (12f * scale).toInt())
    private val theirCost = "".toLabel(colorFromRGB(174, 199, 217), (12f * scale).toInt())
    private val verdictMarker = ImageGetter.getWhiteDot()

    fun addOffer(offer: TradeOffer, offerList: TradeOffersList, correspondingOfferList: TradeOffersList) {
        offerList.add(offer.copy())
        if (offer.type == TradeOfferType.Treaty) correspondingOfferList.add(offer.copy())
        onChange()
    }

    private fun offerClickImplementation(
        offer: TradeOffer,
        invert: Boolean,
        list: TradeOffersList,
        counterList: TradeOffersList,
        civ: ForeignCivView
    ) {
        when (offer.type) {
            TradeOfferType.Gold -> openGoldSelectionPopup(offer, list, civ.gold)
            TradeOfferType.Gold_Per_Turn -> openGoldSelectionPopup(offer, list, civ.getGoldPerTurn())
            else -> addOffer(if (invert) offer.copy(amount = -offer.amount) else offer, list, counterList)
        }
    }

    private val ourAvailableOffersTable = OffersListScroll("OurAvail", portrait, screen.portraitWidth) {
        offerClickImplementation(it, false, tradeView.ourStagedOffers(), tradeView.theirStagedOffers(), ourCiv)
    }
    private val ourOffersTable = OffersListScroll("OurTrade", portrait, screen.portraitWidth) {
        offerClickImplementation(it, true, tradeView.ourStagedOffers(), tradeView.theirStagedOffers(), ourCiv)
    }
    private val theirOffersTable = OffersListScroll("TheirTrade", portrait, screen.portraitWidth) {
        offerClickImplementation(it, true, tradeView.theirStagedOffers(), tradeView.ourStagedOffers(), theirCiv)
    }
    private val theirAvailableOffersTable = OffersListScroll("TheirAvail", portrait, screen.portraitWidth) {
        offerClickImplementation(it, false, tradeView.theirStagedOffers(), tradeView.ourStagedOffers(), theirCiv)
    }

    init {
        defaults().pad(5f)

        val isPortraitMode = screen.isNarrowerThan4to3()

        val columnWidth = screen.getTradeColumnsWidth() - 20f // Subtract padding: ours and OffersListScroll's

        if (portrait) {
            val pileWidth = screen.portraitWidth - 28f * scale
            val insideWidth = pileWidth - 8f * scale
            val tray = Table().apply { top() }
            val tabs = Table()
            val ourPile = "You give".toTextButton()
            val theirPile = "${theirCiv.civName} gives".toTextButton()
            for (pile in listOf(ourPile, theirPile)) {
                pile.style = TextButton.TextButtonStyle(pile.style).apply {
                    up = null
                    down = null
                    over = null
                }
            }
            val ourTab = "Our items".toTextButton()
            val theirTab = "${theirCiv.civName}'s items".toTextButton()
            val activeTabStyle = TextButton.TextButtonStyle(ourTab.style).apply {
                up = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/ActiveTradeTab",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, Color.WHITE)
                down = up
                fontColor = colorFromRGB(20, 37, 54)
            }
            val inactiveTabStyle = TextButton.TextButtonStyle(ourTab.style).apply {
                up = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/InactiveTradeTab",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(29, 50, 69))
                down = up
                fontColor = colorFromRGB(154, 183, 203)
            }
            val ourFrame = Table().apply { pad(2f * scale) }
            val theirFrame = Table().apply { pad(2f * scale) }
            var ourItemsActive = true
            fun showTray() {
                tray.clear()
                tray.add(if (ourItemsActive) ourAvailableOffersTable else theirAvailableOffersTable)
                    .width(pileWidth).minHeight(80f * scale).prefHeight(178f * scale).growY()
                ourFrame.background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/OurPileBorder",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                    if (ourItemsActive) colorFromRGB(49, 147, 225) else colorFromRGB(45, 69, 89))
                theirFrame.background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/TheirPileBorder",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
                    if (ourItemsActive) colorFromRGB(45, 69, 89) else colorFromRGB(213, 85, 80))
                ourTab.style = if (ourItemsActive) activeTabStyle else inactiveTabStyle
                theirTab.style = if (ourItemsActive) inactiveTabStyle else activeTabStyle
            }
            ourPile.onClick { ourItemsActive = true; showTray() }
            theirPile.onClick { ourItemsActive = false; showTray() }
            ourTab.onClick { ourItemsActive = true; showTray() }
            theirTab.onClick { ourItemsActive = false; showTray() }
            val ourHeader = Table()
            ourHeader.add(ImageGetter.getCircle(colorFromRGB(49, 147, 225), 8f * scale))
                .size(8f * scale).padLeft(8f * scale).padRight(5f * scale)
            ourHeader.add(ourPile).width(insideWidth * .50f).height(48f * scale).left()
            ourHeader.add(ourWorth).growX().right().padRight(8f * scale)
            val ourBox = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/OurPile",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(27, 50, 70))
                top()
            }
            ourBox.add(ourHeader).width(insideWidth).height(48f * scale).row()
            ourBox.add(ourOffersTable).width(insideWidth).minHeight(56f * scale).prefHeight(88f * scale).growY().row()
            ourFrame.add(ourBox).width(insideWidth).growY()
            add(ourFrame).width(pileWidth).minHeight(108f * scale).prefHeight(140f * scale).growY().row()
            val theirHeader = Table()
            theirHeader.add(ImageGetter.getCircle(colorFromRGB(213, 85, 80), 8f * scale))
                .size(8f * scale).padLeft(8f * scale).padRight(5f * scale)
            theirHeader.add(theirPile).width(insideWidth * .50f).height(48f * scale).left()
            theirHeader.add(theirCost).growX().right().padRight(8f * scale)
            val theirBox = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/TheirPile",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(27, 50, 70))
                top()
            }
            theirBox.add(theirHeader).width(insideWidth).height(48f * scale).row()
            theirBox.add(theirOffersTable).width(insideWidth).minHeight(56f * scale).prefHeight(88f * scale).growY().row()
            theirFrame.add(theirBox).width(insideWidth).growY()
            add(theirFrame).width(pileWidth).minHeight(108f * scale).prefHeight(140f * scale).growY().padTop(8f * scale).row()

            val verdict = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/TradeVerdict",
                    tintColor = colorFromRGB(21, 44, 64))
            }
            verdict.add(ImageGetter.getNationPortrait(theirCiv.getCiv().nation, 42f * scale))
                .size(42f * scale).padLeft(8f * scale).padRight(10f * scale)
            val verdictText = Table()
            verdictText.add(verdictTitle).left().row()
            verdictText.add(verdictDetail).left().row()
            verdict.add(verdictText).growX().left()
            val meter = Group().apply { setSize(72f * scale, 10f * scale) }
            meter.addActor(ImageGetter.getWhiteDot().apply {
                color = Color.WHITE.cpy().apply { a = .15f }
                setBounds(0f, 0f, 72f * scale, 10f * scale)
            })
            verdictMarker.color = Color.GOLD
            verdictMarker.setSize(8f * scale, 10f * scale)
            meter.addActor(verdictMarker)
            verdict.add(meter).size(72f * scale, 10f * scale).padRight(12f * scale)
            add(verdict).width(pileWidth).height(62f * scale).padTop(10f * scale).row()

            tabs.add(ourTab).width(pileWidth / 2).height(48f * scale)
            tabs.add(theirTab).width(pileWidth / 2).height(48f * scale)
            val trayBox = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/TradeTray",
                    tintColor = colorFromRGB(15, 32, 48))
            }
            trayBox.add(tabs).width(pileWidth).height(48f * scale).row()
            trayBox.add(tray).width(pileWidth).minHeight(80f * scale).prefHeight(178f * scale).growY().row()
            add(trayBox).width(pileWidth).minHeight(128f * scale).prefHeight(232f * scale).growY().padTop(12f * scale).row()
            showTray()
        } else if (!isPortraitMode) {
            // In landscape, arrange in 4 panels: ours left / theirs right ; items top / offers bottom.
            add("Our items".tr())
            add("[${theirCiv.civName}]'s items".tr()).row()

            add(ourAvailableOffersTable).prefSize(columnWidth, screen.stage.height / 2)
            add(theirAvailableOffersTable).prefSize(columnWidth, screen.stage.height / 2).row()

            addSeparator().height(2f)

            add("Our trade offer".tr())
            add("[${theirCiv.civName}]'s trade offer".tr()).row()
            add(ourOffersTable).size(columnWidth, screen.stage.height / 3)
            add(theirOffersTable).size(columnWidth, screen.stage.height / 3)
        } else {
            // In portrait, this will arrange the items lists vertically
            // and the offers still side-by-side below that
            add("Our items".tr()).colspan(2).row()
            add(ourAvailableOffersTable).height(screen.stage.height / 4f).colspan(2).row()

            addSeparator().height(2f)

            add("[${theirCiv.civName}]'s items".tr()).colspan(2).row()
            add(theirAvailableOffersTable).height(screen.stage.height / 4f).colspan(2).row()

            addSeparator().height(5f)

            add("Our trade offer".tr())
            add("[${theirCiv.civName}]'s trade offer".tr()).row()
            add(ourOffersTable).height(screen.stage.height / 4f).width(columnWidth)
            add(theirOffersTable).height(screen.stage.height / 4f).width(columnWidth)
        }
        pack()
        update()
    }

    fun update() {
        val ourFilteredOffers = tradeView.ourAvailableOffers().without(tradeView.ourStagedOffers())
        val theirFilteredOffers = tradeView.theirAvailableOffers().without(tradeView.theirStagedOffers())
        val ourUntradables = ourCiv.getPerTurnResourcesWithOriginsForTrade()
            .removeAll(Constants.tradable)
        val theirUntradables = theirCiv.getPerTurnResourcesWithOriginsForTrade()
            .removeAll(Constants.tradable)
        ourAvailableOffersTable.update(ourFilteredOffers, tradeView.theirAvailableOffers(), ourUntradables, ourCiv, theirCiv)
        ourOffersTable.update(tradeView.ourStagedOffers(), tradeView.theirAvailableOffers(), ourCiv = ourCiv, theirCiv = theirCiv)
        theirOffersTable.update(tradeView.theirStagedOffers(), tradeView.ourAvailableOffers(), ourCiv = ourCiv, theirCiv = theirCiv)
        theirAvailableOffersTable.update(theirFilteredOffers, tradeView.ourAvailableOffers(), theirUntradables, ourCiv, theirCiv)
        if (portrait) updateVerdict()
    }

    private fun updateVerdict() {
        val ours = tradeView.ourStagedOffers()
        val theirs = tradeView.theirStagedOffers()
        if (ours.isEmpty() && theirs.isEmpty()) {
            ourWorth.setText("")
            theirCost.setText("")
            verdictTitle.setText("Add items to make an offer")
            verdictDetail.setText("They decide on their turn")
            verdictMarker.setPosition(32f * scale, 0f)
            return
        }
        val trade = Trade().apply {
            ourOffers.addAll(ours.map { it.copy() })
            theirOffers.addAll(theirs.map { it.copy() })
        }.reverse()
        val evaluator = TradeEvaluation()
        val partner = theirCiv.getCiv()
        val player = ourCiv.getCiv()
        val worth = trade.theirOffers.sumOf {
            evaluator.evaluateBuyCostWithInflation(it, partner, player, trade).toLong()
        }
        val cost = trade.ourOffers.sumOf {
            evaluator.evaluateSellCostWithInflation(it, partner, player, trade).toLong()
        }
        ourWorth.setText("Worth $worth to them")
        theirCost.setText("Costs them $cost")
        if (partner.isHuman()) {
            verdictTitle.setText("Offer ready")
            verdictDetail.setText("They decide on their turn")
            verdictMarker.setPosition(32f * scale, 0f)
            return
        }
        val balance = evaluator.getTradeAcceptability(trade, partner, player, includeDiplomaticGifts = true)
        verdictTitle.setText(if (balance >= 0) "Likely to accept" else "Unlikely to accept")
        verdictTitle.color = if (balance >= 0) colorFromRGB(159, 240, 138) else Color.GOLD
        verdictDetail.setText(if (balance >= 0) "They value your side enough" else "They value your side less")
        val fraction = if (worth + cost <= 0L) .5f else
            (worth.toDouble() / (worth + cost).toDouble()).toFloat().coerceIn(0f, 1f)
        verdictMarker.setPosition((64f * fraction) * scale, 0f)
    }

    private fun openGoldSelectionPopup(offer: TradeOffer, ourOffers: TradeOffersList, maxGold: Int) {
        val existingGoldOffer = ourOffers.firstOrNull { it.type == offer.type }
        if (existingGoldOffer != null)
            offer.amount = existingGoldOffer.amount
        AskNumberPopup(
            screen,
            label = "Enter the amount of gold",
            icon = ImageGetter.getStatIcon("Gold").surroundWithCircle(80f),
            defaultValue = offer.amount,
            amountButtons =
            if (offer.type == TradeOfferType.Gold) listOf(50, 500)
            else listOf(5, 15),
            bounds = IntRange(0, maxGold),
            actionOnOk = { userInput ->
                offer.amount = userInput
                if (existingGoldOffer == null)
                    ourOffers.add(offer)
                else existingGoldOffer.amount = offer.amount
                if (offer.amount == 0) ourOffers.remove(offer)
                onChange()
            }
        ).open()
    }
}
