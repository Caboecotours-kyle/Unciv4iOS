package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOfferType.*
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.ForeignCivView
import kotlin.math.min
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * Widget for one fourth of an [OfferColumnsTable] - instantiated for ours/theirs × available/traded
 * @param persistenceID  Part of ID added to [ExpanderTab.persistenceID] to distinguish the four usecases
 * @param onOfferClicked What to do when a tradeButton is clicked
 */
class OffersListScroll(
    private val persistenceID: String,
    private val portraitMode: Boolean = false,
    private val portraitWidth: Float = 393f,
    private val onOfferClicked: (TradeOffer) -> Unit
) : ScrollPane(null) {
    private val portraitTray = portraitMode && persistenceID.endsWith("Avail")
    private val scale = portraitWidth / 393f
    val table = Table(BaseScreen.skin).apply { defaults().pad(5f) }


    private val expanderTabs = HashMap<TradeOfferType, ExpanderTab>()

    init {
        fadeScrollBars=false
        setScrollbarsVisible(true)
        if (portraitTray) setScrollingDisabled(false, true)
    }

    /**
     * @param offersToDisplay The offers which should be displayed as buttons
     * @param otherSideOffers The list of other side's offers to compare with whether these offers are unique
     * @param untradableOffers Things we got from sources that we can't trade on, displayed for completeness - should be aggregated per resource to "All" origin
     */
    fun update(
        offersToDisplay: TradeOffersList,
        otherSideOffers: TradeOffersList,
        untradableOffers: ResourceSupplyList = ResourceSupplyList.emptyList,
        ourCiv: ForeignCivView,
        theirCiv: ForeignCivView
    ) {
        table.clear()
        expanderTabs.clear()
        if (portraitMode && !portraitTray && offersToDisplay.isEmpty()) {
            val hint = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("DiplomacyScreen/EmptyPile",
                    BaseScreen.skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(37, 62, 82))
            }
            val text = if (persistenceID == "OurTrade") "Tap your items below" else "Tap their items below"
            hint.add(text.toLabel(colorFromRGB(156, 184, 205), (14f * scale).toInt()))
            table.add(hint).width(portraitWidth - 48f * scale).height(60f * scale).row()
            actor = table
            return
        }

        for (offerType in TradeOfferType.entries) {
            val labelName = when(offerType) {
                Embassy, Gold, Gold_Per_Turn, Treaty, Agreement, Introduction -> ""
                Luxury_Resource -> "Luxury resources"
                Strategic_Resource -> "Strategic resources"
                Stockpiled_Resource -> "Stockpiled resources"
                Technology -> "Technologies"
                WarDeclaration -> "Declarations of war"
                PeaceProposal -> "Peace Proposals"
                City -> "Cities"
            }
            val offersOfType = offersToDisplay.filter { it.type == offerType }
            if (!portraitMode && labelName.isNotEmpty() && offersOfType.any()) {
                expanderTabs[offerType] = ExpanderTab(labelName, persistenceID = "Trade.$persistenceID.$offerType") {
                    it.defaults().pad(5f)
                }
            }
        }

        val trayColumns = Table(BaseScreen.skin).apply { left() }
        var trayColumn = Table(BaseScreen.skin)
        var trayItems = 0
        for (offerType in TradeOfferType.entries) {
            val offersOfType = offersToDisplay.filter { it.type == offerType }
                .sortedWith(compareBy(
                    { if (UncivGame.Current.settings.orderTradeOffersByAmount) -it.amount else 0 },
                    { if (it.type==City) it.getOfferText() else it.name.tr() }
                ))

            if (expanderTabs.containsKey(offerType)) {
                expanderTabs[offerType]!!.innerTable.clear()
                table.add(expanderTabs[offerType]!!).row()
            }

            for (offer in offersOfType) {
                val tradeLabel = offer.getOfferText(untradableOffers.sumBy(offer.name))
                val tradeIcon = when (offer.type) {
                    Embassy -> ImageGetter.getImage("OtherIcons/Star")
                    Luxury_Resource, Strategic_Resource ->
                        ImageGetter.getResourcePortrait(offer.name, 30f)
                    WarDeclaration, PeaceProposal ->
                        ImageGetter.getNationPortrait(ourCiv.ruleset.nations[offer.name]!!, 30f)
                    else -> null
                }
                val tradeButton = IconTextButton(tradeLabel, tradeIcon).apply {
                    if (tradeIcon != null)
                        iconCell.size(30f)
                    label.setAlignment(Align.center)
                    labelCell.pad(5f).grow()
                    if (portraitTray) {
                        clearChildren()
                        label.wrap = true
                        label.setFontScale(13f * scale / Fonts.ORIGINAL_FONT_SIZE)
                        if (tradeIcon != null) add(tradeIcon).size(27f * scale).padBottom(2f * scale).row()
                        add(label).width(72f * scale).growY()
                    } else if (portraitMode) {
                        clearChildren()
                        label.wrap = true
                        label.setAlignment(Align.left)
                        label.setFontScale(14f * scale / Fonts.ORIGINAL_FONT_SIZE)
                        if (tradeIcon != null) add(tradeIcon).size(28f * scale).padRight(8f * scale)
                        add(label).width(if (tradeIcon != null) 242f * scale else 278f * scale).left()
                        add((if (offer.type == Gold || offer.type == Gold_Per_Turn) "Edit" else "×")
                            .toLabel()).width(34f * scale).right()
                    }
                }

                val amountPerClick =
                    when (offer.type) {
                        Gold -> 50
                        Treaty -> Int.MAX_VALUE
                        else -> 1
                    }

                if (offer.isTradable() && offer.name != Constants.peaceTreaty // can't disable peace treaty!
                    && (offer.name != Constants.researchAgreement // If we have a research agreement make sure the total gold of both Civs is higher than the total cost
                        // If both civs combined can pay for the research agreement, don't disable it. One can offer the other it's gold.
                        || (ourCiv.gold + theirCiv.gold > ourCiv.getResearchAgreementCost(theirCiv) * 2))) {

                    // highlight unique suggestions
                    if (offerType in listOf(Luxury_Resource, Strategic_Resource)
                            && otherSideOffers.all { it.type != offer.type || it.name != offer.name || it.amount < 0}) // we can 'have' negative amounts of resources 
                        tradeButton.color = Color.GREEN

                    tradeButton.onClick {
                        val amountTransferred = min(amountPerClick, offer.amount)
                        onOfferClicked(offer.copy(amount = amountTransferred))
                    }
                }
                else tradeButton.disable()  // for instance, we have negative gold


                if (portraitTray) {
                    trayColumn.add(tradeButton).width(80f * scale).height(80f * scale)
                        .pad(3f * scale).row()
                    trayItems++
                    if (trayItems % 2 == 0) {
                        trayColumns.add(trayColumn).top()
                        trayColumn = Table(BaseScreen.skin)
                    }
                } else {
                    val cell = if (expanderTabs.containsKey(offerType))
                        expanderTabs[offerType]!!.innerTable.add(tradeButton)
                    else table.add(tradeButton)
                    if (portraitMode) cell.width(portraitWidth - 54f * scale).height(56f * scale)
                    cell.row()
                }
            }
        }
        if (portraitTray && trayItems % 2 != 0) trayColumns.add(trayColumn).top()
        actor = if (portraitTray) trayColumns else table
    }
}
