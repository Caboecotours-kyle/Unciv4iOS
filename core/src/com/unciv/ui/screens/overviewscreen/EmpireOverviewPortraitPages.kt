package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.components.ISortableGridContentProvider
import com.unciv.ui.components.YearTextUtil
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.widgets.SortableGrid.SortDirection
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.components.widgets.UnitIconGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.UnitUpgradeMenu
import com.unciv.ui.screens.basescreen.BaseScreen.Companion.skinStrings
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.CARD
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.CHIP
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.CONTENT
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.INK2
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.INK3
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.NEG
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.ON
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.POS
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.YELLOW
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.addDivider
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.bg
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.brow
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.card
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.chip
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.chipRow
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.colorOf
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.empty
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.heading
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.iconValue
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.label
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.segmented
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.signed
import com.unciv.ui.screens.overviewscreen.OverviewPortraitStyle.wrapped
import com.unciv.ui.screens.pickerscreens.PromotionPickerScreen
import com.unciv.ui.screens.pickerscreens.UnitRenamePopup
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import com.unciv.utils.Concurrency
import com.unciv.view.CivView
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import java.util.Collections
import kotlin.math.roundToInt

/** Builds the phone page for [category]; each keeps the landscape tab's persisted data class. */
internal fun createPortraitPage(
    category: EmpireOverviewCategories,
    viewingPlayer: CivView,
    screen: EmpireOverviewScreen,
    persisted: EmpireOverviewTab.EmpireOverviewTabPersistableData?
): PortraitOverviewPage = when (category) {
    EmpireOverviewCategories.Cities -> CitiesPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Stats -> StatsPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Trades -> TradesPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Units -> UnitsPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Politics -> PoliticsPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Resources -> ResourcesPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Religion -> ReligionPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Wonders -> WondersPortraitPage(viewingPlayer, screen, persisted)
    EmpireOverviewCategories.Notifications -> NotificationsPortraitPage(viewingPlayer, screen, persisted)
}

//region Shared page pieces

private fun rowCard() = Table().apply {
    background = bg(CARD)
    pad(9f, 12f, 9f, 12f)
}

private fun Table.addRow(row: Actor) { add(row).growX().minHeight(66f).padTop(6f).row() }

private fun Table.addSection(title: String) { add(heading(title)).left().padTop(14f).padBottom(2f).row() }

private fun Table.addEmpty(text: String) { add(empty(text)).growX().pad(40f, 10f, 40f, 10f).row() }

private fun <T : Actor> T.tappable(action: () -> Unit): T {
    touchable = Touchable.enabled
    onActivation { action() }
    return this
}

/** A 48pt square target around a small actor. */
private fun target(actor: Actor, size: Float = 40f, action: () -> Unit) =
    Table().apply { add(actor).size(size) }.tappable(action)

/** Non-interactive chip, for facts next to real chips. */
private fun infoChip(text: String, color: Color = INK2, icon: Actor? = null) = Table().apply {
    background = bg(CHIP)
    pad(0f, 11f, 0f, 11f)
    if (icon != null) add(icon).size(18f).padRight(6f)
    add(label(text, color, 14))
}

private fun sortText(name: String, on: Boolean, direction: SortDirection) = name.tr() + when {
    !on -> ""
    direction == SortDirection.Ascending -> " ${Fonts.sortUpArrow}"
    direction == SortDirection.Descending -> " ${Fonts.sortDownArrow}"
    else -> ""
}

/** Same order as SortableGrid: current column inverts, a new column starts at its default. */
private fun nextDirection(sameColumn: Boolean, current: SortDirection, default: SortDirection) =
    when (if (sameColumn) current else SortDirection.None) {
        SortDirection.Ascending -> SortDirection.Descending
        SortDirection.Descending -> SortDirection.Ascending
        else -> default
    }

private fun <T> sortRows(data: List<T>, comparator: Comparator<T>, direction: SortDirection) = when (direction) {
    SortDirection.None -> data
    SortDirection.Ascending -> data.sortedWith(comparator)
    SortDirection.Descending -> data.sortedWith(Collections.reverseOrder(comparator))
}

private fun blink(actor: Actor) = actor.addAction(Actions.repeat(3, Actions.sequence(Actions.fadeOut(.17f), Actions.fadeIn(.17f))))

private fun centerMapOn(position: HexCoord) {
    val worldScreen = UncivGame.Current.resetToWorldScreen()
    worldScreen.mapHolder.setCenterPosition(position)
}

//endregion
//region Cities

internal class CitiesPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    override val persistableData = (persistedData as? CityOverviewTab.CityTabPersistableData) ?: CityOverviewTab.CityTabPersistableData()

    private val columns = CityOverviewTabColumn.getColumns(civ)
        .filter { it.isVisible(gameInfo) && it.defaultSort != SortDirection.None }
    /** These already show in the row, so the value on the right would repeat them */
    private val shownInRow = setOf(
        CityOverviewTabColumn.CityColumn, CityOverviewTabColumn.Status, CityOverviewTabColumn.ConstructionIcon,
        CityOverviewTabColumn.Construction, CityOverviewTabColumn.Population, CityOverviewTabColumn.WLTK,
        CityOverviewTabColumn.Garrison, CityOverviewTabColumn.Religion
    )
    private val yields = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture)
    private val showReligion = CityOverviewTabColumn.Religion.isVisible(gameInfo)

    override fun subtitle() = "[${civ.cities.size}] cities"

    private fun same(a: ISortableGridContentProvider<City, EmpireOverviewScreen>, b: ISortableGridContentProvider<City, EmpireOverviewScreen>) =
        a == b || a is CityOverviewTabColumn.CityWideResourceColumn && b is CityOverviewTabColumn.CityWideResourceColumn && a.resource.name == b.resource.name

    private fun activeColumn() = columns.firstOrNull { same(it, persistableData.sortedBy) } ?: CityOverviewTabColumn.CityColumn

    private fun columnName(column: ISortableGridContentProvider<City, EmpireOverviewScreen>) = when (column) {
        CityOverviewTabColumn.CityColumn -> "Name"
        CityOverviewTabColumn.ConstructionIcon -> "Turns"
        else -> column.headerTip.substringBefore('\n')
    }

    private fun columnIcon(column: ISortableGridContentProvider<City, EmpireOverviewScreen>): Actor? = when {
        column is CityOverviewTabColumn.CityWideResourceColumn -> ImageGetter.getResourcePortrait(column.resource.name, 18f)
        column is CityOverviewTabColumn && Stat.safeValueOf(column.name) != null -> icons.image(column.name)
        column == CityOverviewTabColumn.Population -> icons.image("Population")
        else -> null
    }

    private fun sortBy(column: ISortableGridContentProvider<City, EmpireOverviewScreen>) {
        val direction = nextDirection(same(column, persistableData.sortedBy), persistableData.direction, column.defaultSort)
        persistableData.sortedBy = column
        persistableData.direction = direction
        refresh()
    }

    override fun update() {
        clear()
        top()
        val sortColumn = activeColumn()
        var activeChip: Actor? = null
        val chips = columns.map { column ->
            val on = column == sortColumn
            chip(sortText(columnName(column), on, persistableData.direction), on, columnIcon(column)) { sortBy(column) }
                .also { if (on) activeChip = it }
        }
        add(chipRow(chips, activeChip)).growX().padBottom(4f).row()

        val cities = sortRows(civ.cities, sortColumn.getComparator(), persistableData.direction)
        if (cities.isEmpty()) {
            addEmpty("No cities yet")
            return
        }
        for (city in cities) addRow(cityRow(city, sortColumn))
        add(totals(cities, sortColumn)).growX().padTop(10f).row()
    }

    private fun openCity(city: City) =
        overviewScreen.game.pushScreen { CityScreen(GUI.getWorldScreen().selectedGameView.getCityView(city)) }

    private fun cityRow(city: City, sortColumn: ISortableGridContentProvider<City, EmpireOverviewScreen>): Table {
        val row = rowCard()
        val main = Table().tappable { openCity(city) }
        val disc = label(city.population.population.tr(), size = 18)
            .surroundWithCircle(46f, resizeActor = false, color = ON)
        main.add(disc).size(46f).padRight(10f)

        val info = Table()
        val nameLine = Table()
        nameLine.add(city.name.toLabel(Color.WHITE, 17, hideIcons = true).apply { wrap = true })
            .width(195f).left()
        if (city.isCapital()) nameLine.add(ImageGetter.getImage("OtherIcons/Star").apply { color = YELLOW }).size(14f).padLeft(6f)
        CityOverviewTabColumn.Status.getEntryActor(city, 26f, overviewScreen)?.let { nameLine.add(it).padLeft(6f) }
        if (showReligion)
            CityOverviewTabColumn.Religion.getEntryActor(city, 26f, overviewScreen)?.let { nameLine.add(it).padLeft(6f) }
        info.add(nameLine).left().row()

        for (lineStats in yields.chunked(2)) {
            val yieldLine = Table()
            for (stat in lineStats) {
                val value = CityOverviewTabColumn.valueOf(stat.name).getEntryValue(city)
                yieldLine.add(iconValue(icons.image(stat.name), value.tr(), if (value < 0) NEG else INK2))
                    .padRight(9f)
            }
            info.add(yieldLine).left().padTop(3f).row()
        }

        val construction = (CityOverviewTabColumn.Construction.getEntryActor(city, 0f, overviewScreen) as? Label)
            ?.text?.toString()?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("  ")
        if (!construction.isNullOrEmpty()) info.add(wrapped(construction, INK3, 14)).width(275f).padTop(2f).row()
        main.add(info).growX()
        row.add(main).growX()

        if (sortColumn !in shownInRow)
            sortColumn.getEntryActor(city, 28f, overviewScreen)?.let {
                row.row()
                row.add(Table().apply {
                    add(label(columnName(sortColumn), INK3, 13, hideIcons = true)).left().expandX()
                    add(it).right()
                }).growX().padTop(4f)
            }
        row.row()

        val strip = mutableListOf<Actor>()
        if (city.isWeLoveTheKingDayActive())
            strip += infoChip("[${city.getFlag(CityFlags.WeLoveTheKing)}] turns", YELLOW, ImageGetter.getImage("OtherIcons/WLTK 1"))
        else if (city.demandedResource.isNotEmpty())
            strip += chip("Demanding [${city.demandedResource}]", false, ImageGetter.getResourcePortrait(city.demandedResource, 18f)) {
                overviewScreen.showOneTimeNotification(gameInfo.getExploredResourcesNotification(city.civ, city.demandedResource))
            }
        city.getGarrison()?.let { unit ->
            strip += chip(unit.displayName(), false, ImageGetter.getConstructionPortrait(unit.baseUnit.getIconName(), 18f)) {
                overviewScreen.select(EmpireOverviewCategories.Units, unit.id.toString())
            }
        }
        if (strip.isNotEmpty()) row.add(chipRow(strip)).growX().padTop(6f)
        return row
    }

    private fun totals(cities: List<City>, sortColumn: ISortableGridContentProvider<City, EmpireOverviewScreen>) = card().apply {
        add(label("Total", INK2, 15)).left().row()
        for (lineStats in yields.chunked(2)) {
            val line = Table()
            for (stat in lineStats) {
                val sum = cities.sumOf { CityOverviewTabColumn.valueOf(stat.name).getEntryValue(it) }
                line.add(iconValue(icons.image(stat.name), sum.tr(), colorOf(sum))).padRight(12f)
            }
            add(line).left().padTop(4f).row()
        }
        if (sortColumn !in shownInRow && Stat.safeValueOf((sortColumn as? CityOverviewTabColumn)?.name ?: "") !in yields) {
            val total = sortColumn.getTotalsActor(cities) ?: return@apply
            val extra = Table()
            extra.add(label(columnName(sortColumn), INK2, 15)).left().expandX()
            extra.add(total).right()
            add(extra).growX().padTop(6f)
        }
    }
}

//endregion
//region Stats

internal class StatsPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    private val isReligionEnabled = viewingPlayer.isReligionEnabled()
    private val unhappinessUniques = sequenceOf(
        UniqueType.ConditionalWhenAboveAmountStatResource,
        UniqueType.ConditionalWhenBetweenStatResource,
        UniqueType.ConditionalWhenBelowAmountStatResource,
    ).flatMap { conditionalType ->
        viewingPlayer.getMatchingUniques(conditionalType)
            .filter { it.params.last() == "Happiness" }
            .sortedBy { it.type }
    }.filterNot { it.isHiddenToUsers() }.toSet()

    /** Kept across rebuilds so a drag on it survives the numbers updating */
    private val slider: UncivSlider? =
        if (!viewingPlayer.ruleset.modOptions.hasUnique(UniqueType.ConvertGoldToScience)) null
        else UncivSlider(0f, 1f, 0.1f,
            initial = viewingPlayer.getGoldPercentConvertedToScience(),
            getTipText = UncivSlider::formatPercent
        ) {
            viewingPlayer.trySetGoldPercentConvertedToScience(it)
            viewingPlayer.tryUpdateAllCityStats()
            fillBodies()
        }
    /** Holds [slider] once; the tables around it are refilled in place so a drag is never cut by a rebuild */
    private val sliderBox: Table? = slider?.let {
        Table().apply {
            add(label("Convert gold to science", INK2, 15)).left().padTop(8f).row()
            add(it).growX().padTop(6f).row()
        }
    }
    private val summaryBody = Table()
    private val goldBody = Table()
    private val restBody = Table()

    override fun subtitle() = "Turn [${gameInfo.turns}]"

    override fun update() {
        overviewScreen.game.settings.addCompletedTutorialTask("See your stats breakdown")
        clear()
        top()
        slider?.isDisabled = !GUI.isAllowedChangeState()
        add(summaryBody).growX().row()
        add(card().apply {
            add(goldBody).growX().row()
            if (sliderBox == null) return@apply
            addDivider()
            add(sliderBox).growX().row()
        }).growX().padTop(10f).row()
        add(restBody).growX().row()
        fillBodies()
    }

    private fun fillBodies() {
        summaryBody.clear()
        goldBody.clear()
        restBody.clear()
        val statMap = viewingPlayer.getStatMapForNextTurn()
        val happiness = viewingPlayer.getHappinessBreakdown()

        val summaryValues = mutableListOf<Actor>()
        val shown = listOfNotNull(Stat.Gold, Stat.Science, Stat.Culture, if (isReligionEnabled) Stat.Faith else null)
        for (stat in shown) {
            val total = statMap.values.sumOf { it[stat].toDouble() }.roundToInt()
            summaryValues += iconValue(icons.image(stat.name), signed(total), colorOf(total), 20f)
        }
        val happy = happiness.values.sum().roundToInt()
        summaryValues += iconValue(icons.image("Happiness"), happy.tr(), colorOf(happy), 20f)
        summaryBody.add(card().apply {
            for (lineValues in summaryValues.chunked(3)) {
                val line = Table()
                for (value in lineValues) line.add(value).expandX().left()
                add(line).growX().minHeight(36f).row()
            }
        }).growX().row()
        summaryBody.add(breakdown(icons.image("Happiness"), "Happiness", happiness.map { it.key to it.value })).growX().padTop(10f).row()
        if (unhappinessUniques.isNotEmpty()) summaryBody.add(unhappinessCard()).growX().padTop(10f).row()

        goldBody.fillBreakdown(icons.image(Stat.Gold.name), Stat.Gold.name, statMap.map { it.key to it.value[Stat.Gold] })

        restBody.add(statCard(Stat.Science, statMap)).growX().padTop(10f).row()
        restBody.add(statCard(Stat.Culture, statMap)).growX().padTop(10f).row()
        if (isReligionEnabled) restBody.add(statCard(Stat.Faith, statMap)).growX().padTop(10f).row()
        restBody.add(greatPeopleCard()).growX().padTop(10f).row()
        val score = viewingPlayer.calculateScoreBreakdown()
        val scoreIcon = ImageGetter.getImage("OtherIcons/Score").apply { color = Color.FIREBRICK }
        restBody.add(breakdown(scoreIcon, "Score", score.map { it.key to it.value.toFloat() }, signedValues = false)).growX().padTop(10f).row()
    }

    private fun cardHead(icon: Actor, title: String) = Table().apply {
        add(icon).size(22f).padRight(8f)
        add(label(title, Color.WHITE, 18, hideIcons = true)).left().expandX()
    }

    private fun statCard(stat: Stat, statMap: com.unciv.models.stats.StatMap) =
        breakdown(icons.image(stat.name), stat.name, statMap.map { it.key to it.value[stat] })

    /** Same rows as the landscape table: values rounding to zero are skipped, then the total */
    private fun breakdown(icon: Actor, title: String, entries: List<Pair<String, Float>>, signedValues: Boolean = true) =
        card().apply { fillBreakdown(icon, title, entries, signedValues) }

    private fun Table.fillBreakdown(icon: Actor, title: String, entries: List<Pair<String, Float>>, signedValues: Boolean = true) {
        add(cardHead(icon, title)).growX().padBottom(6f).row()
        addDivider()
        for ((source, value) in entries) {
            val rounded = value.roundToInt()
            if (rounded == 0) continue
            add(brow(source, if (signedValues) signed(rounded) else rounded.tr(), colorOf(rounded))).growX().row()
        }
        val total = entries.sumOf { it.second.toDouble() }.roundToInt()
        addDivider()
        add(brow("Total", if (signedValues) signed(total) else total.tr(), colorOf(total), bold = true)).growX().row()
    }

    private fun unhappinessCard() = card().apply {
        add(cardHead(ImageGetter.getStatIcon("Malcontent"), "Unhappiness")).growX().padBottom(6f).row()
        addDivider()
        add(MarkupRenderer.render(
            unhappinessUniques.map { FormattedLine(it) },
            labelWidth = CONTENT - 24f,
            iconDisplay = FormattedLine.IconDisplay.NoLink
        )).growX().row()
    }

    private fun greatPeopleCard() = card().apply {
        val icon = ImageGetter.getStatIcon("Specialist").apply { color = Color.ROYAL }
        add(cardHead(icon, "Great person points")).growX().padBottom(6f).row()
        addDivider()
        val grid = Table()
        grid.add()
        grid.add(label("Current points", INK3, 13)).right().padLeft(8f)
        grid.add(label("Points per turn", INK3, 13)).right().padLeft(8f).row()
        val perTurn = viewingPlayer.getGreatPersonPointsForNextTurn()
        var rows = 0
        for ((greatPerson, points) in viewingPlayer.getGreatPersonPointsCounter()) {
            val required = viewingPlayer.getPointsRequiredForGreatPerson(greatPerson)
            grid.add(wrapped(greatPerson, INK2, 15)).growX().minHeight(32f)
            grid.add(label("$points/$required", Color.WHITE, 15)).right().padLeft(8f)
            grid.add(label(signed(perTurn[greatPerson]), POS, 15)).right().padLeft(8f).row()
            rows++
        }
        val pointsForNextGeneral = viewingPlayer.getPointsForNextGreatGeneralCounter()
        for ((unit, points) in viewingPlayer.getGreatGeneralPointsCounter()) {
            grid.add(wrapped(unit, INK2, 15)).growX().minHeight(32f)
            grid.add(label("$points/${pointsForNextGeneral[unit]}", Color.WHITE, 15)).right().padLeft(8f)
            grid.add().row()
            rows++
        }
        if (rows == 0) add(label("No great person points yet", INK2, 15)).left().minHeight(32f).row()
        else add(grid).growX().row()
    }
}

//endregion
//region Trades

internal class TradesPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {

    private fun pending() = civ.diplomacy.values.flatMap { diplomacy ->
        diplomacy.otherCiv.tradeRequests.filter { it.requestingCiv == civ.civID }
            .map { it.trade.reverse() to diplomacy.otherCiv }
    }

    /** Same order as landscape: by the longest duration in our side of the first trade */
    private fun current() = civ.diplomacy.values.filter { it.trades.isNotEmpty() }
        .sortedBy { diplomacy -> diplomacy.trades.first().ourOffers.maxOfOrNull { it.duration } ?: 0 }
        .flatMap { diplomacy -> diplomacy.trades.map { it to diplomacy.otherCiv } }

    override fun subtitle() = "[${pending().size}] pending, [${current().size}] current"

    override fun update() {
        clear()
        top()
        val pending = pending()
        val current = current()
        if (pending.isEmpty() && current.isEmpty()) {
            addEmpty("No trades yet")
            return
        }
        if (pending.isNotEmpty()) {
            addSection("Pending trades")
            for ((trade, otherCiv) in pending) addRow(tradeCard(trade, otherCiv))
        }
        if (current.isNotEmpty()) {
            addSection("Current trades")
            for ((trade, otherCiv) in current) addRow(tradeCard(trade, otherCiv))
        }
    }

    private fun tradeCard(trade: Trade, otherCiv: Civilization) = Table().apply {
        val width = (CONTENT - 6f) / 2
        add(side(civ, trade.ourOffers)).width(width).fillY().top()
        add(side(otherCiv, trade.theirOffers)).width(width).fillY().top().padLeft(6f)
    }

    private fun side(owner: Civilization, offers: TradeOffersList) = Table().apply {
        background = bg(owner.nation.getOuterColor())
        pad(10f)
        top()
        val ink = owner.nation.getInnerColor()
        val title = Table()
        title.add(ImageGetter.getNationPortrait(owner.nation, 28f)).size(28f).padRight(6f)
        title.add(owner.civName.toLabel(ink, 15, hideIcons = true).apply { wrap = true }).growX()
        add(title).growX().padBottom(6f).row()
        for (offer in offers)
            add(wrapped(offer.getOfferText(), ink, 14, Align.center)).growX().padTop(4f).row()
    }
}

//endregion
//region Units

internal class UnitsPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    override val persistableData = (persistedData as? UnitOverviewTab.UnitTabPersistableData) ?: UnitOverviewTab.UnitTabPersistableData()
    override var scrollMemory: Float
        get() = persistableData.scrollY ?: 0f
        set(value) { persistableData.scrollY = value }

    private val columns = UnitOverviewTabColumn.entries.filter { it.defaultSort != SortDirection.None }
    private var supplyOpen = civ.stats.getUnitSupplyDeficit() > 0
    private var pendingSelection: String? = null

    override fun subtitle() = "[${civ.units.getCivUnitsSize()}] units"

    private fun columnName(column: UnitOverviewTabColumn) = when (column) {
        UnitOverviewTabColumn.RangedStrength -> "Ranged strength"
        UnitOverviewTabColumn.ClosestCity -> "Closest city"
        else -> column.name
    }

    private fun sortBy(column: UnitOverviewTabColumn) {
        val direction = nextDirection(persistableData.sortedBy == column, persistableData.direction, column.defaultSort)
        persistableData.sortedBy = column
        persistableData.direction = direction
        refresh()
    }

    /** Selection from a city garrison or after rename/promote/upgrade: the rebuilt row gets focus and blinks */
    override fun select(selection: String): Float? {
        pendingSelection = selection
        return null
    }

    override fun update() {
        clear()
        top()
        add(supplyCard()).growX().padBottom(8f).row()

        var activeChip: Actor? = null
        val chips = columns.map { column ->
            val on = column == persistableData.sortedBy
            chip(sortText(columnName(column), on, persistableData.direction), on) { sortBy(column) }
                .also { if (on) activeChip = it }
        }
        add(chipRow(chips, activeChip)).growX().padBottom(4f).row()

        val sortColumn = persistableData.sortedBy
        val units = sortRows(viewingPlayer.getUnits(), sortColumn.getComparator(), persistableData.direction)
        if (units.isEmpty()) addEmpty("No units yet")
        for (unit in units) {
            val row = unitRow(unit)
            addRow(row)
            if (unit.id.toString() == pendingSelection) {
                focus = row
                blink(row)
            }
        }
        if (units.isNotEmpty()) add(unitTotals(units)).growX().padTop(10f).row()
        pendingSelection = null
    }

    private fun unitTotals(units: List<MapUnitView>) = card().apply {
        val promotable = units.count { it.getPromotions().canBePromoted() }
        val upgradeable = units.count { UnitActionsUpgrade.getUpgradeActionAnywhere(it.getUnit()).any() }
        val hurt = units.count { it.unitHealth < 100 }
        val xp = units.sumOf { it.getPromotions().XP }
        val counts = Table()
        counts.add(label("Total [${units.size}]", Color.WHITE, 16)).left().expandX()
        counts.add(iconValue(ImageGetter.getImage("OtherIcons/Star"), promotable.tr(), YELLOW)).padLeft(10f)
        counts.add(iconValue(ImageGetter.getImage("OtherIcons/Increase"), upgradeable.tr(), INK2)).padLeft(10f)
        add(counts).growX().row()
        val details = Table()
        details.add(label("[$hurt] hurt", if (hurt > 0) NEG else INK2, 14)).left().expandX()
        details.add(label("XP [$xp]", INK2, 14)).right()
        add(details).growX().padTop(4f)
    }

    private fun supplyCard() = card().apply {
        val stats = civ.stats
        val deficit = stats.getUnitSupplyDeficit()
        val head = Table().tappable {
            supplyOpen = !supplyOpen
            refresh()
        }
        if (deficit > 0) head.add(ImageGetter.getImage("OtherIcons/ExclamationMark").apply { color = Color.FIREBRICK }).size(20f).padRight(8f)
        head.add(label("Unit Supply", Color.WHITE, 16)).left().expandX()
        head.add(label("${civ.units.getCivUnitsSize()}/${stats.getUnitSupply()}", if (deficit > 0) NEG else INK2, 15)).padRight(10f)
        head.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply {
            color = INK3
            setSize(14f, 14f)
            setOrigin(Align.center)
            rotation = if (supplyOpen) 90f else -90f
        }).size(14f)
        add(head).growX().minHeight(36f).row()
        if (!supplyOpen) return@apply
        addDivider()
        add(brow("Base Supply", stats.getBaseUnitSupply().tr())).growX().row()
        add(brow("Cities", stats.getUnitSupplyFromCities().tr())).growX().row()
        add(brow("Population", stats.getUnitSupplyFromPop().tr())).growX().row()
        addDivider()
        add(brow("Total Supply", stats.getUnitSupply().tr())).growX().row()
        add(brow("In Use", civ.units.getCivUnitsSize().tr())).growX().row()
        addDivider()
        add(brow("Supply Deficit", deficit.tr(), colorOf(-deficit))).growX().row()
        add(brow("Production Penalty", "${stats.getUnitSupplyProductionPenalty().toInt()}%")).growX().row()
        if (deficit > 0)
            add(wrapped("Increase your supply or reduce the amount of units to remove the production penalty", NEG, 14)).growX().padTop(4f).row()
    }

    private fun goTo(unit: MapUnitView) {
        GUI.resetToWorldScreen()
        GUI.getMap().setCenterPosition(unit.getTile().position(), forceSelectUnit = unit.getUnit())
    }

    private fun afterChange(unit: MapUnitView) {
        refresh()
        overviewScreen.select(EmpireOverviewCategories.Units, unit.id.toString())
    }

    private fun unitRow(unit: MapUnitView): Table {
        val row = rowCard()
        val main = Table().tappable { goTo(unit) }
        val idle = unit.isIdle()
        main.add(UnitIconGroup(unit, 40f).apply { if (!idle) color.a = .5f }).size(40f).padRight(10f)
        val info = Table()
        info.add(unit.displayName().toLabel(if (idle) Color.WHITE else INK2, 17, hideIcons = true).apply { wrap = true })
            .width(235f).left().row()
        val numbers = mutableListOf<Actor>()
        UnitOverviewTabColumn.Strength.getEntryString(unit)?.let { numbers += iconValue(icons.image("Strength"), it) }
        UnitOverviewTabColumn.RangedStrength.getEntryString(unit)?.let { numbers += iconValue(icons.image("Ranged"), it) }
        numbers += iconValue(icons.image("Movement"), unit.getMovementString())
        UnitOverviewTabColumn.Health.getEntryString(unit)?.let { numbers += label("${Fonts.health} $it", NEG, 14) }
        UnitOverviewTabColumn.XP.getEntryString(unit)?.takeIf { it.isNotEmpty() }?.let { numbers += label("XP $it", INK2, 14) }
        for (lineValues in numbers.chunked(2)) {
            val line = Table()
            for (value in lineValues) line.add(value).padRight(12f)
            info.add(line).left().padTop(3f).row()
        }
        UnitOverviewTabColumn.Action.getEntryString(unit)?.let { info.add(wrapped(it, INK3, 14)).width(235f).padTop(2f).row() }
        main.add(info).width(235f)
        row.add(main).growX()

        val rename = ImageGetter.getImage("OtherIcons/Pencil").apply { color = INK2; setSize(18f, 18f) }
            .surroundWithCircle(40f, resizeActor = false, color = CHIP)
        row.add(target(rename) { UnitRenamePopup(overviewScreen, unit.getUnit()) { afterChange(unit) } }).size(48f).padLeft(6f)
        row.row()

        val strip = mutableListOf<Actor>()
        promotionsChip(unit)?.let { strip += it }
        strip += upgradeChips(unit)
        closestCityTile(unit)?.let { tile ->
            val name = tile.owningCity()?.name ?: return@let
            val inCity = unit.getTile() == tile
            strip += chip(name, false, ImageGetter.getImage("OtherIcons/Cities").apply {
                color = if (inCity) Color.FOREST.brighten(0.5f) else INK2
            }) { centerMapOn(tile.position()) }
        }
        if (strip.isNotEmpty()) row.add(chipRow(strip)).colspan(2).growX().padTop(6f)
        return row
    }

    private fun closestCityTile(unit: MapUnitView): TileView? =
        unit.getTile().getVisibleTilesInDistance(3).firstOrNull { it.isCityCenter() }

    private fun canEnable() = civ.isCurrentPlayer() && GUI.isAllowedChangeState()

    /** Existing promotions plus the promote star; opens the picker like the landscape cell */
    private fun promotionsChip(unit: MapUnitView): Actor? {
        val promotions = unit.getPromotions()
        val canPromote = canEnable() && promotions.canBePromoted()
        val owned = promotions.getPromotions(true).toList()
        if (owned.isEmpty() && !promotions.canBePromoted()) return null
        val chip = Table().apply {
            background = bg(CHIP)
            pad(0f, 10f, 0f, 10f)
        }
        for (promotion in owned) chip.add(ImageGetter.getPromotionPortrait(promotion.name, 24f)).size(24f).padRight(3f)
        if (promotions.canBePromoted()) {
            val ready = canEnable() && unit.hasMovement() && unit.attacksThisTurn == 0
            chip.add(ImageGetter.getImage("OtherIcons/Star").apply {
                color = if (ready) Color.GOLDENROD else Color.GOLDENROD.darken(0.25f)
            }).size(22f).padLeft(3f)
        }
        if (!canPromote && promotions.promotions.isEmpty()) return chip
        return chip.tappable {
            overviewScreen.game.pushScreen {
                PromotionPickerScreen(unit.getUnit()) { afterChange(unit) }
            }
        }
    }

    private fun upgradeChips(unit: MapUnitView): List<Actor> {
        val canEnable = canEnable()
        return UnitActionsUpgrade.getUpgradeActionAnywhere(unit.getUnit()).map { action ->
            val enable = canEnable && action.action != null
            val upgradeAction = action as UpgradeUnitAction
            val target = upgradeAction.unitToUpgradeTo
            val icon = ImageGetter.getUnitIcon(target, if (enable) Color.GREEN else Color.GREEN.darken(0.5f))
            lateinit var upgradeChip: Actor
            upgradeChip = chip(target.name, false, icon) {
                UnitUpgradeMenu(overviewScreen.stage, upgradeChip, unit.getUnit(), upgradeAction, enable) { afterChange(unit) }
            }
            upgradeChip
        }.toList()
    }
}

//endregion
//region Politics

internal class PoliticsPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    override val persistableData = (persistedData as? GlobalPoliticsOverviewTable.DiplomacyTabPersistableData)
        ?: GlobalPoliticsOverviewTable.DiplomacyTabPersistableData()

    override fun subtitle() = "[${civ.diplomacyFunctions.getKnownCivsSorted(includeCityStates = false).count()}] civilizations met"

    override fun update() {
        clear()
        top()
        val selected = if (persistableData.showDiagram) 1 else 0
        add(segmented(listOf("Civilizations", "Diagram"), selected) {
            persistableData.showDiagram = it == 1
            refresh()
        }).growX().padBottom(6f).row()
        if (persistableData.showDiagram) updateDiagram() else updateList()
    }

    private fun civName(other: Civilization) =
        if (civ.knows(other) || other == civ) other.civName else "an unknown civilization"

    //region List

    private fun updateList() {
        val civs = (if (viewingPlayer.isSpectator()) emptySequence() else sequenceOf(civ)) +
            civ.diplomacyFunctions.getKnownCivsSorted(includeCityStates = false)
        val wonders = WonderInfo().collectInfo(civ)
        var any = false
        for (other in civs) {
            addRow(civCard(other, wonders))
            any = true
        }
        if (!any) addEmpty("No civilizations met yet")
    }

    private fun civCard(other: Civilization, wonders: Array<WonderInfo.WonderInfo>) = rowCard().apply {
        val head = Table()
        head.add(ImageGetter.getNationPortrait(other.nation, 44f)).size(44f).padRight(10f)
        val names = Table()
        names.add(label(other.nation.leaderName, Color.WHITE, 17)).left().row()
        names.add(other.civName.toLabel(INK2, 14, hideIcons = true)).left().row()
        names.add(label(other.tech.era.name, INK3, 14)).left()
        head.add(names).growX()
        add(head).growX().row()

        val policies = other.policies.branches.filter { other.policies.isAdopted(it.name) }.map { branch ->
            val count = 1 + branch.policies.count {
                it.policyBranchType != PolicyBranchType.BranchComplete && other.policies.isAdopted(it.name)
            }
            "[${branch.name}]: $count"
        }
        if (policies.isNotEmpty()) {
            add(heading("Social policies")).left().padTop(8f).row()
            for (line in policies) add(wrapped(line, INK2, 15)).growX().row()
        }

        val owned = wonders.filter { it.civ == other }
        if (owned.isNotEmpty()) {
            add(heading("Wonders")).left().padTop(8f).row()
            for (wonder in owned) {
                val location = wonder.location
                if (location != null && location.isVisible(civ))
                    add(label(wonder.name, Color.WHITE, 15).tappable { centerMapOn(location.position) }).left().minHeight(40f).row()
                else add(label(wonder.name, INK2, 15)).left().minHeight(32f).row()
            }
        }

        val relations = relations(other)
        if (relations.isNotEmpty()) {
            add(heading("Relations")).left().padTop(8f).row()
            for (line in relations) add(line).growX().padTop(2f).row()
        }
    }

    /** The landscape relations column, one line each */
    private fun relations(other: Civilization): List<Actor> {
        if (!civ.knows(other) && other != civ) return emptyList()
        if (other.isDefeated()) return listOf(label("{Defeated} ${Fonts.death}", INK2, 15))
        val lines = mutableListOf<Actor>()
        fun line(text: String, color: Color, turns: Int? = null) {
            val row = Table()
            row.add(ColorMarkupLabel(text, color, fontSize = 15).apply { wrap = true }).growX()
            if (turns != null) row.add(label("($turns ${Fonts.turn})", INK3, 14)).padLeft(6f)
            lines += row
        }
        for (third in other.getKnownCivs())
            if (other.isAtWarWith(third)) line("At war with [${civName(third)}]", Color.RED)
        for (third in other.getKnownCivs()) {
            val diplomacy = other.getDiplomacyManager(third) ?: continue
            if (diplomacy.hasFlag(DiplomacyFlags.DefensivePact))
                line("Defensive pact with [${civName(third)}]", Color.CYAN, diplomacy.getFlag(DiplomacyFlags.DefensivePact))
            else if (diplomacy.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                line("Friends with [${civName(third)}]", Color.GREEN, diplomacy.getFlag(DiplomacyFlags.DeclarationOfFriendship))
        }
        for (third in other.getKnownCivs()) {
            val diplomacy = other.getDiplomacyManager(third) ?: continue
            if (diplomacy.hasFlag(DiplomacyFlags.Denunciation))
                line("Denounced [${civName(third)}]", Color.RED, diplomacy.getFlag(DiplomacyFlags.Denunciation))
        }
        for (cityState in gameInfo.getAliveCityStates())
            if (cityState.getDiplomacyManager(other)?.isRelationshipLevelEQ(RelationshipLevel.Ally) == true)
                line("Allied with [${civName(cityState)}]", Color.CYAN)
        return lines
    }

    //endregion
    //region Diagram

    /** Same as GlobalPoliticsOverviewTable.hideCityStateCount */
    private fun Civilization.hideCityStateCount(): Boolean {
        if (!gameInfo.gameParameters.randomNumberOfCityStates) return false
        val knownCivs = 1 + getKnownCivs().count { it.isCityState }
        if (knownCivs >= gameInfo.gameParameters.maxNumberOfCityStates) return false
        if (hasUnique(UniqueType.OneTimeRevealEntireMap)) return false
        return true
    }

    private fun updateDiagram() {
        val includeCityStates = persistableData.includeCityStates
        add(chip(Constants.cityStates, includeCityStates) {
            persistableData.includeCityStates = !persistableData.includeCityStates
            refresh()
        }).left().height(48f).padBottom(6f).row()

        val hideCount = civ.shouldHideCivCount() || includeCityStates && civ.hideCityStateCount()
        val relevantCount = if (hideCount) "?" else gameInfo.civilizations.count {
            !it.isSpectator() && !it.isBarbarian && (includeCityStates || !it.isCityState)
        }.tr()
        val undefeated = (if (viewingPlayer.isSpectator()) emptyList() else listOf(civ)) +
            civ.diplomacyFunctions.getKnownCivsSorted(includeCityStates)
        val defeated = civ.diplomacyFunctions.getKnownCivsSorted(includeCityStates, true).filter { it.isDefeated() }.toList()

        if (undefeated.any { it != civ })
            add(GlobalPoliticsDiagramGroup(undefeated, CONTENT)).size(CONTENT, CONTENT).row()

        val info = card()
        info.add(wrapped("[$relevantCount] Civilizations in the game", Color.WHITE, 16)).growX().row()
        if (!viewingPlayer.isSpectator()) {
            info.add(heading("Our Civilization:")).left().padTop(8f).row()
            val score = if (civ.isDefeated()) Fonts.death.toString() else civ.calculateTotalScore().toInt().tr()
            info.add(miniCiv(civ, score)).growX().row()
        }
        civ.getTurnsTillNextDiplomaticVote()?.let {
            info.add(wrapped("Turns until the next\ndiplomacy victory vote: [$it]", INK2, 15)).growX().padTop(6f).row()
        }
        add(info).growX().padTop(8f).row()

        addCivList("alive", undefeated.filter { it != civ })
        addCivList("defeated", defeated)
    }

    private fun addCivList(aliveOrDefeated: String, civs: List<Civilization>) {
        addSection("Known and $aliveOrDefeated ([${civs.size}])")
        if (civs.isEmpty()) return
        val list = card()
        for ((index, other) in civs.withIndex()) {
            if (index > 0) list.addDivider()
            list.add(miniCiv(other, if (other.isCityState) null else other.calculateTotalScore().toInt().tr())).growX().row()
        }
        add(list).growX().row()
    }

    private fun miniCiv(other: Civilization, score: String?) = Table().apply {
        add(ImageGetter.getNationPortrait(other.nation, 30f)).size(30f).pad(9f, 0f, 9f, 10f)
        add(other.civName.toLabel(Color.WHITE, 16, hideIcons = true)).left().expandX()
        if (score != null) add(label(score, INK2, 15)).right()
        if (other.isDefeated() || viewingPlayer.isSpectator() || other == civ) return@apply
        add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = INK3 }).size(14f).padLeft(10f)
        tappable {
            UncivGame.Current.pushScreen { DiplomacyScreen(viewingPlayer, viewingPlayer.gameView.getForeignCivView(other)) }
        }
    }

    //endregion
}

//endregion
//region Resources

internal class ResourcesPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    /** Kept so the landscape axis choice survives; the phone list replaces both layouts */
    override val persistableData = (persistedData as? ResourcesOverviewTab.ResourcesTabPersistableData)
        ?: ResourcesOverviewTab.ResourcesTabPersistableData()

    /** Same origins and captions as the landscape tab's extra rows */
    private enum class Extra(val caption: String) {
        Unimproved("Unimproved"),
        CelebratingWLTK("We Love The King Day"),
        DemandingWLTK("WLTK demand"),
        TradeOffer("Trade offer"),
        Stockpile("Stockpiled resources"),
    }

    private companion object { const val TOTAL = "Total" }

    private var drilldown = ResourceSupplyList()
    private var extra = ResourceSupplyList(keepZeroAmounts = true)
    private var resources = listOf<TileResource>()

    override fun subtitle() = "[${resources.size}] resources"

    private fun load() {
        drilldown = civ.detailedCivResources
        extra = extraDrilldown()
        resources = (drilldown.asSequence() + extra).map { it.resource }
            .filter {
                it.resourceType != ResourceType.Bonus &&
                    !it.hasUnique(UniqueType.NotShownOnWorldScreen, civ.state) &&
                    !it.isCityWide
            }
            .distinct()
            .sortedWith(
                compareBy<TileResource> { it.resourceType }
                    .thenBy(UncivGame.Current.settings.getCollatorFromLocale()) { it.name.tr(hideIcons = true) }
            )
            .toList()
    }

    override fun update() {
        load()
        clear()
        top()
        if (resources.isEmpty()) {
            addEmpty("No resources yet")
            return
        }
        var lastType: ResourceType? = null
        for (resource in resources) {
            if (resource.resourceType != lastType) {
                lastType = resource.resourceType
                addSection(resource.resourceType.name)
            }
            addRow(resourceRow(resource))
        }
    }

    private fun ResourceSupplyList.getOrZero(resource: TileResource, origin: String) = get(resource, origin)?.amount ?: 0

    /** Same deficit rules as the landscape tab */
    private fun ResourceSupplyList.isDeficit(resource: TileResource, origin: String, amount: Int): Boolean {
        if (resource.isStockpiled && origin != Extra.Stockpile.name)
            return amount < -getOrZero(resource, Extra.Stockpile.name)
        if (origin != TOTAL) return amount < 0 && sumBy(resource) < 0
        if (amount != 0) return amount < 0
        return extra.getOrZero(resource, Extra.DemandingWLTK.name) > 0
    }

    private fun ResourceSupplyList.amountLabel(resource: TileResource, origin: String, amount: Int, size: Int = 14): Label {
        val color = if (isDeficit(resource, origin, amount)) NEG else Color.WHITE
        val text = (if (resource.isStockpiled && amount >= 0 && origin != Extra.Stockpile.name) "+" else "") + amount.toString()
        return label(text, color, size, Align.right)
    }

    private fun isAlliedAndUnimproved(tile: Tile): Boolean {
        val owner = tile.getOwner() ?: return false
        if (owner != civ && !(owner.isCityState && owner.allyCiv == civ)) return false
        return tile.countAsUnimproved()
    }

    private fun resourceRow(resource: TileResource) = rowCard().apply {
        val portrait = target(ImageGetter.getResourcePortrait(resource.name, 36f), 36f) {
            overviewScreen.showOneTimeNotification(gameInfo.getExploredResourcesNotification(civ, resource.name))
        }
        add(portrait).size(48f).top().padRight(8f)

        val info = Table()
        val totalDeficit = drilldown.isDeficit(resource, TOTAL, drilldown.sumBy(resource))
        val name = resource.name.toLabel(if (totalDeficit) NEG else Color.WHITE, 17, hideIcons = true)
            .tappable { overviewScreen.openCivilopedia(resource.makeLink()) }
        info.add(name).left().minHeight(40f).row()
        for (origin in drilldown.origins()) {
            val amount = drilldown.get(resource, origin)?.amount ?: continue
            val line = Table()
            line.add(label(origin.removeSuffix("+"), INK3, 14)).left().expandX()
            line.add(drilldown.amountLabel(resource, origin, amount))
            info.add(line).growX().row()
        }
        add(info).growX().top()
        add(drilldown.amountLabel(resource, TOTAL, drilldown.sumBy(resource), 20)).top().padTop(8f).padLeft(10f).row()

        val strip = mutableListOf<Actor>()
        for (origin in Extra.entries) {
            val amount = extra.get(resource, origin.name)?.amount ?: continue
            val color = if (extra.isDeficit(resource, origin.name, amount)) NEG else INK2
            val text = "${origin.caption.tr()} $amount"
            strip += if (origin == Extra.Unimproved) chip(text, false) {
                overviewScreen.showOneTimeNotification(
                    gameInfo.getExploredResourcesNotification(civ, resource, filter = { isAlliedAndUnimproved(it) })
                )
            } else infoChip(text, color)
        }
        if (strip.isNotEmpty()) add(chipRow(strip)).colspan(3).growX().padTop(6f)
    }

    private fun Tile.countAsUnimproved(): Boolean {
        val resource = tileResource
        return civ.canSeeResource(resource) &&
            resource.resourceType != ResourceType.Bonus &&
            !providesResources(civ)
    }

    /** Same as ResourcesOverviewTab.getExtraDrilldown */
    private fun extraDrilldown(): ResourceSupplyList {
        val list = ResourceSupplyList(keepZeroAmounts = true)
        fun City.addUnimproved() {
            for (tile in getTiles())
                if (tile.countAsUnimproved()) list.add(tile.tileResource!!, Extra.Unimproved.name)
        }
        for (city in civ.cities) {
            if (city.demandedResource.isNotEmpty()) {
                val wltkResource = gameInfo.ruleset.tileResources[city.demandedResource]!!
                list.add(wltkResource, if (city.isWeLoveTheKingDayActive()) Extra.CelebratingWLTK.name else Extra.DemandingWLTK.name)
            }
            city.addUnimproved()
        }
        for (otherCiv in civ.getKnownCivs()) {
            for (trade in otherCiv.tradeRequests.filter { it.requestingCiv == civ.civID })
                for (offer in trade.trade.theirOffers.filter { it.type == TradeOfferType.Strategic_Resource || it.type == TradeOfferType.Luxury_Resource })
                    list.add(gameInfo.ruleset.tileResources[offer.name]!!, Extra.TradeOffer.name, offer.amount)
            if (!otherCiv.isCityState || otherCiv.allyCiv != civ) continue
            for (city in otherCiv.cities) city.addUnimproved()
        }
        for (resource in gameInfo.ruleset.tileResources.values) {
            if (resource.resourceType != ResourceType.Strategic) continue
            if (viewingPlayer.canSeeResource(resource)) list.add(resource, "No source", 0)
        }
        for (resourceName in civ.resourceStockpiles.keys) {
            val resource = gameInfo.ruleset.tileResources[resourceName] ?: continue
            if (resource.hasUnique(UniqueType.NotShownOnWorldScreen, civ.state)) continue
            list.add(resource, Extra.Stockpile.name, civ.getResourceAmount(resource))
        }
        return list
    }
}

//endregion
//region Religion

internal class ReligionPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    override val persistableData = (persistedData as? ReligionOverviewTab.ReligionTabPersistableData)
        ?: ReligionOverviewTab.ReligionTabPersistableData()

    private fun religions() = gameInfo.civilizations.mapNotNull { it.religionManager.religion }

    override fun subtitle(): String {
        val religions = religions()
        return "[${religions.count { !it.isPantheon() }}] religions, [${religions.count { it.isPantheon() }}] pantheons"
    }

    override fun select(selection: String): Float? {
        persistableData.selectedReligion = selection
        return null
    }

    override fun update() {
        clear()
        top()
        add(statusCard()).growX().row()

        val religions = religions()
        if (religions.isEmpty()) {
            addEmpty("No religions or pantheons yet")
            return
        }
        val selectedName = persistableData.selectedReligion
        var activeChip: Actor? = null
        val chips = religions.map { religion ->
            val on = religion.name == selectedName
            val image = if (religion.isPantheon()) {
                if (civ.knows(religion.foundingCiv) || civ == religion.foundingCiv)
                    ImageGetter.getNationPortrait(religion.foundingCiv.nation, 36f)
                else ImageGetter.getRandomNationPortrait(36f)
            } else ImageGetter.getReligionPortrait(religion.getIconName(), 36f)
            Table().apply {
                background = bg(if (on) Color.WHITE else CHIP)
                add(image).size(36f)
            }.tappable {
                persistableData.selectedReligion = religion.name
                refresh()
            }.also { if (on) activeChip = it }
        }
        addSection("Religions and pantheons")
        add(chipRow(chips, activeChip)).growX().row()

        val religion = selectedName?.let { gameInfo.religions[it] }
        if (religion == null) {
            add(wrapped("Click an icon to see the stats of this religion", INK2, 15, Align.center)).growX().padTop(16f).row()
            return
        }
        focus = activeChip

        val stats = card()
        stats.add(label(religion.getReligionDisplayName(), Color.CORAL, 20)).left().padBottom(4f).row()
        stats.add(brow(if (religion.isPantheon()) "Pantheon Name:" else "Religion Name:", religion.getReligionDisplayName().tr())).growX().row()
        val foundingCivName = if (civ.knows(religion.foundingCiv) || civ == religion.foundingCiv) religion.foundingCivName
            else Constants.unknownNationName
        stats.add(brow("Founding Civ:", foundingCivName.tr())).growX().row()
        if (religion.isMajorReligion()) {
            gameInfo.getCities().firstOrNull { it.isHolyCityOf(religion.name) }?.let { holyCity ->
                val cityName = if (civ.hasExplored(holyCity.getCenterTile())) holyCity.name.tr(hideIcons = true)
                    else Constants.unknownNationName.tr()
                stats.add(brow("Holy City:", cityName)).growX().row()
            }
        }
        val manager = religion.foundingCiv.religionManager
        stats.add(brow("Cities following this religion:", manager.numberOfCitiesFollowingThisReligion().tr())).growX().row()
        stats.add(brow("Followers of this religion:", manager.numberOfFollowersFollowingThisReligion("in all cities").tr())).growX().row()
        add(stats).growX().padTop(10f).row()

        for (belief in religion.getAllBeliefsOrdered()) {
            val description = MarkupRenderer.render(belief.getCivilopediaTextLines(withHeader = true), labelWidth = CONTENT - 24f) {
                overviewScreen.openCivilopedia(it)
            }
            add(card().apply { add(description).growX() }).growX().padTop(8f).row()
        }
    }

    private fun statusCard() = card().apply {
        val manager = civ.religionManager
        val header = if (civ.shouldHideCivCount()) "Religions to be founded: [?]"
            else "Religions to be founded: [${manager.remainingFoundableReligions()}]"
        add(label(header, Color.WHITE, 16)).left().minHeight(32f).row()
        for ((text, number) in manager.remainingFoundableReligionsBreakdown())
            add(brow(text, number.tr())).growX().row()
        addDivider()
        if (manager.canGenerateProphet(ignoreFaithAmount = true)) {
            val text = "Minimal Faith required for\nthe next [great prophet equivalent]:"
                .fillPlaceholders(manager.getGreatProphetEquivalent()!!.name)
            add(brow(text, (manager.faithForNextGreatProphet() + 1).tr())).growX().row()
        }
        add(brow("Religious status:", manager.religionState.toString().tr())).growX().row()
    }
}

//endregion
//region Wonders

internal class WondersPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    private var wonders = WonderInfo().collectInfo(civ).filter { it.status != WonderInfo.WonderStatus.Hidden }

    override fun subtitle() = "[${wonders.count { it.status == WonderInfo.WonderStatus.Owned }}] of [${wonders.size}] owned"

    override fun update() {
        wonders = WonderInfo().collectInfo(civ).filter { it.status != WonderInfo.WonderStatus.Hidden }
        clear()
        top()
        if (wonders.isEmpty()) {
            addEmpty("No wonders known yet")
            return
        }
        var lastGroup = ""
        for (wonder in wonders) {
            if (wonder.groupName != lastGroup) {
                lastGroup = wonder.groupName
                val groupRow = Table()
                groupRow.add(label(lastGroup, wonder.groupColor, 14)).left().padRight(8f)
                groupRow.add(ImageGetter.getDot(wonder.groupColor)).height(2f).growX()
                add(groupRow).growX().padTop(14f).padBottom(2f).row()
            }
            addRow(wonderRow(wonder))
        }
    }

    private fun wonderRow(wonder: WonderInfo.WonderInfo) = rowCard().apply {
        val image = wonder.getImage()
        if (image != null) add(Table().apply { add(image) }.tappable { overviewScreen.openCivilopedia(wonder.makeLink()) })
            .size(48f).padRight(10f)
        else add().size(48f).padRight(10f)

        val info = Table()
        info.add(wonder.getNameColumn().toLabel(Color.WHITE, 17, hideIcons = true).apply { wrap = true }).growX().row()
        val statusColor = if (wonder.status == WonderInfo.WonderStatus.Owned) POS else INK2
        info.add(label(wonder.getStatusColumn(), statusColor, 14)).left().padTop(2f).row()
        add(info).growX().row()

        val locationText = wonder.getLocationColumn()
        val location = wonder.location
        if (locationText.isEmpty()) return@apply
        if (location != null)
            add(chip(locationText, false) { centerMapOn(location.position) }).colspan(2).left().height(48f).padTop(6f)
        else add(label(locationText, INK3, 14)).colspan(2).left().padTop(4f)
    }
}

//endregion
//region Notifications

internal class NotificationsPortraitPage(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : PortraitOverviewPage(viewingPlayer, overviewScreen, persistedData) {
    override val persistableData = (persistedData as? NotificationsOverviewTable.NotificationsTabPersistableData)
        ?: NotificationsOverviewTable.NotificationsTabPersistableData()
    override var scrollMemory: Float
        get() = persistableData.scrollY ?: 0f
        set(value) { persistableData.scrollY = value }

    /** Same highlight colors and counts as the landscape log */
    private val highlightColor1 = skinStrings.getUIColor("OverviewScreen/NotificationLog/HighlightColor1", Color.valueOf("#f0ead6"))
    private val highlightColor2 = skinStrings.getUIColor("OverviewScreen/NotificationLog/HighlightColor2", Color.valueOf("#f5c993"))
    private val highlightCount1: Int
    private val highlightCount2: Int

    private var showEarlier = false
    private var category: NotificationCategory? = null
    /** Built once, turn by turn off the GL queue like landscape, then re-attached on every rebuild */
    private val earlier = Table()
    private var earlierLoaded = false

    init {
        val count = civ.notifications.size
        highlightCount1 = civ.notificationCountAtStartTurn ?: count
        highlightCount2 = persistableData.lastCount.takeIf { persistableData.lastTurn == gameInfo.turns } ?: highlightCount1
        persistableData.lastCount = count
        persistableData.lastTurn = gameInfo.turns
    }

    override fun subtitle() = if (showEarlier) "Earlier" else "This turn"

    override fun update() {
        clear()
        top()
        add(segmented(listOf("Now", "Earlier"), if (showEarlier) 1 else 0) {
            showEarlier = it == 1
            refresh()
        }).growX().padBottom(6f).row()
        if (showEarlier) updateEarlier() else updateNow()
    }

    private fun updateNow() {
        val notifications = civ.notifications
        if (notifications.isEmpty()) {
            addEmpty("No events this turn")
            return
        }
        val present = NotificationCategory.entries.filter { c -> notifications.any { it.category == c } }
        if (category !in present) category = null
        var activeChip: Actor? = null
        val chips = mutableListOf<Actor>()
        chips += chip("All", category == null) { category = null; refresh() }.also { if (category == null) activeChip = it }
        for (c in present)
            chips += chip(c.name, category == c) { category = c; refresh() }.also { if (category == c) activeChip = it }
        add(chipRow(chips, activeChip)).growX().padBottom(4f).row()

        for (c in present) {
            if (category != null && c != category) continue
            addSection(c.name)
            for ((index, notification) in notifications.withIndex())
                if (notification.category == c) addRow(notificationRow(notification, highlight(index)))
        }
    }

    private fun highlight(index: Int) = when {
        index >= highlightCount2 -> highlightColor2
        index >= highlightCount1 -> highlightColor1
        else -> null
    }

    private fun updateEarlier() {
        if (civ.notificationsLog.isEmpty()) {
            addEmpty("No earlier notifications")
            return
        }
        add(earlier).growX().row()
        if (earlierLoaded) return
        earlierLoaded = true
        addTurnsAsync(civ.notificationsLog.asReversed().iterator())
    }

    /** One past turn per GL frame, like landscape, so a long log cannot freeze input */
    private fun addTurnsAsync(iterator: Iterator<Civilization.NotificationsLog>) {
        if (!iterator.hasNext()) return
        val log = iterator.next()
        Concurrency.runOnGLThread {
            earlier.add(turnSection(log.turn, log.notifications)).growX().row()
            earlier.invalidateHierarchy()
            addTurnsAsync(iterator)
        }
    }

    private fun turnSection(turn: Int, notifications: List<Notification>): Table {
        val section = Table()
        fun fill() {
            section.clear()
            val open = turn !in persistableData.closedTurns
            val head = Table().tappable {
                if (!persistableData.closedTurns.remove(turn)) persistableData.closedTurns.add(turn)
                fill()
            }
            head.add(label(turnText(turn), Color.WHITE, 15)).left().expandX()
            head.add(label(notifications.size.tr(), INK3, 14)).padRight(10f)
            head.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply {
                color = INK3
                setSize(14f, 14f)
                setOrigin(Align.center)
                rotation = if (open) -90f else 90f
            }).size(14f)
            section.add(head).growX().minHeight(48f).padTop(8f).row()
            section.addDivider()
            if (!open) return
            for (c in NotificationCategory.entries) {
                val matches = notifications.filter { it.category == c }
                if (matches.isEmpty()) continue
                if (c != NotificationCategory.General) section.add(heading(c.name)).left().padTop(8f).row()
                for (notification in matches) section.addRow(notificationRow(notification, null))
            }
        }
        fill()
        return section
    }

    private fun turnText(turn: Int): String {
        val offset = turn - gameInfo.turns
        val year = YearTextUtil.toYearText(gameInfo.getYear(offset), civ.isLongCountDisplay())
        return (if (offset == 0) "{Current turn} | " else "") + "${Fonts.turn} {$turn} | $year"
    }

    private fun notificationRow(notification: Notification, highlight: Color?) = rowCard().apply {
        val iconsWidth = notification.icons.size * 34f
        if (highlight != null) add(ImageGetter.getDot(highlight)).size(6f, 30f).padRight(8f)
        val text = ColorMarkupLabel(notification.text, Color.WHITE, fontSize = 16).apply { wrap = true }
        add(text).width((CONTENT - 24f - iconsWidth - (if (highlight != null) 14f else 0f) - 8f).coerceAtLeast(120f)).padRight(8f)
        notification.addNotificationIconsTo(this, gameInfo.ruleset, 30f)
        if (notification.actions.isNotEmpty()) tappable { overviewScreen.showOneTimeNotification(notification) }
    }
}

//endregion
