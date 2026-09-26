package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.logic.city.CityFocus
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.CityScreenConstructionMenu
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.TileView
import kotlin.math.roundToInt

/** The five city jobs in a map-backed sheet. The existing city widgets remain available as drill-ins. */
internal class CityPortraitView(private val screen: CityScreen, private val sheetWidth: Float) : Table() {
    enum class Tab(val label: String, val icon: String) {
        Build("Build", "OtherIcons/Settings"), Buildings("Buildings", "Library"),
        Tiles("Tiles", "OtherIcons/HexagonOutline"), Citizens("Citizens", "Worker"),
        Buy("Buy", "OtherIcons/Resources")
    }

    companion object {
        var selectedTab = Tab.Build
        private val NAVY = Color(18f / 255f, 37f / 255f, 54f / 255f, .93f)
        private val CARD = Color.valueOf("20394f")
        private val INK2 = Color.valueOf("b7cde0")
        private val INK3 = Color.valueOf("8eacc2")
        private val YELLOW = Color.valueOf("ffc93c")
        private fun bg(color: Color): Drawable = BaseScreen.skinStrings.getUiBackground(
            "", BaseScreen.skinStrings.roundedEdgeRectangleShape, color)
    }

    private val city = screen.cityView
    private val contentWidth = sheetWidth - 24f
    private val content = object : Table() {
        override fun getPrefWidth() = sheetWidth
        override fun getMinWidth() = sheetWidth
    }.top()
    private val scroll = AutoScrollPane(content).apply {
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
    }
    private val heading = Table()
    private val yields = Table()
    private val tabs = Table()
    private val constructionDetails = ConstructionInfoTable(screen)
    private val tileDetails = CityScreenTileTable(screen)
    private val buyButtons = BuyButtonFactory(screen)
    private var detailOpen = screen.selectedConstruction != null
    private var cityDetailsOpen = false

    init {
        background = bg(NAVY)
        defaults().minWidth(0f).width(sheetWidth)
        constructionDetails.background = bg(CARD)
        tileDetails.background = bg(CARD)
        val handle = Table().apply { add(Image(ImageGetter.getWhiteDotDrawable().tint(INK3))).size(36f, 4f) }
        add(handle).growX().height(12f).row()
        add(heading).growX().row()
        add(yields).growX().row()
        add(scroll).grow().prefHeight(0f).row()
        add(tabs).growX().height(72f)
    }

    fun showTile(tile: TileView) {
        selectedTab = Tab.Tiles
        detailOpen = false
        screen.selectTile(tile)
        refresh()
    }

    fun refresh() {
        val oldScroll = scroll.scrollY
        drawHeading()
        drawYields()
        drawTabs()
        content.clear()
        content.pad(10f, 12f, 18f, 12f).defaults().minWidth(0f).width(contentWidth)
        if (detailOpen && screen.selectedConstruction != null) {
            constructionDetail()
        } else when (selectedTab) {
            Tab.Build -> buildTab()
            Tab.Buildings -> buildingsTab()
            Tab.Tiles -> tilesTab()
            Tab.Citizens -> citizensTab()
            Tab.Buy -> buyTab()
        }
        scroll.layout()
        scroll.scrollY = if (detailOpen) 0f else oldScroll
        scroll.updateVisualScroll()
    }

    private fun drawHeading() {
        heading.clear()
        heading.pad(7f, 14f, 7f, 14f)
        val titleWidth = sheetWidth - 14f * 2f - 48f * 2f - 9f
        val population = Table().apply {
            background = bg(Color.valueOf("2f85c8"))
            add(city.getPopulationCount().toString().toLabel(fontSize = 20)).center()
        }
        heading.add(population).size(48f).padRight(9f)
        val title = Table().left()
        title.add((city.name + if (city.isCapital()) " ★" else "")
            .toLabel(fontSize = 23, hideIcons = true).apply { setEllipsis(true) })
            .minWidth(0f).width(titleWidth).left().row()
        val growth = when {
            city.isStarving() -> "Loses population in ${city.getNumTurnsToStarvation()} turns"
            city.isGrowing() -> "Grows in ${city.getNumTurnsToNewPopulation()} turns"
            else -> "Growth stopped"
        }
        title.add(growth.toLabel(INK2, 13).apply { setEllipsis(true) })
            .minWidth(0f).width(titleWidth).left()
        title.touchable = Touchable.enabled
        title.onClick { screen.page(1) }
        heading.add(title).minWidth(0f).width(titleWidth).left()
        val close = Table().apply {
            background = bg(Color.valueOf("315066"))
            touchable = Touchable.enabled
            add("×".toLabel(INK2, 30)).center()
            onClick { screen.exit() }
        }
        heading.add(close).size(48f)
    }

    private fun drawYields() {
        yields.clear()
        val stats = city.getCurrentCityStats()
        for (stat in listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)) {
            val item = Table()
            item.add(screen.portraitStatIcons.image(stat.name)).size(22f).row()
            val amount = if (stat == Stat.Happiness) city.getHappinessList().values.sum() else stats[stat]
            item.add(amount.roundToInt().toString().toLabel(fontSize = 14)).padTop(3f)
            yields.add(item).growX().uniformX().height(50f)
        }
        yields.row()
        yields.add(Image(ImageGetter.getWhiteDotDrawable().tint(Color(1f, 1f, 1f, .12f))))
            .colspan(6).growX().height(1f)
    }

    private fun drawTabs() {
        tabs.clear()
        tabs.background = bg(Color.valueOf("182f42"))
        tabs.pad(0f, 12f, 0f, 12f)
        for (tab in Tab.entries) {
            val active = tab == selectedTab
            val button = Table().apply {
                touchable = Touchable.enabled
                if (active) background = bg(Color.valueOf("29465d"))
                val icon: Actor = when (tab) {
                    Tab.Buildings, Tab.Citizens -> ImageGetter.getConstructionPortrait(tab.icon, 25f)
                    else -> ImageGetter.getImage(tab.icon)
                }
                icon.color = if (active) Color.WHITE else INK3
                add(icon)
                    .size(25f).padTop(6f).row()
                add(tab.label.toLabel(if (active) Color.WHITE else INK3, 12)).padTop(2f)
                onClick {
                    if (selectedTab == tab && !detailOpen) return@onClick
                    selectedTab = tab
                    detailOpen = false
                    cityDetailsOpen = false
                    scroll.scrollY = 0f
                    refresh()
                }
            }
            tabs.add(button).growX().uniformX().minWidth(48f).height(62f).pad(4f)
        }
    }

    private fun section(text: String) {
        content.add(text.toLabel(INK3, 14).apply { wrap = true }).padTop(15f).padBottom(6f).row()
    }

    private fun buildTab() {
        if (screen.isSpying) {
            section("Construction is hidden while spying")
            return
        }
        val queue = city.constructions.constructionQueue
        val current = queue.firstOrNull()?.let { city.constructions.getConstruction(it) }
        if (current != null) {
            val card = constructionRow(current, current = true) {
                screen.selectConstruction(current)
                screen.selectedQueueEntry = 0
                detailOpen = true
                refresh()
            }
            content.add(card).height(78f).row()
        } else {
            content.add("Pick a construction".toLabel(fontSize = 18)).left().pad(12f).row()
        }
        if (queue.size > 1) {
            section("Up next")
            queue.drop(1).forEachIndexed { index, name ->
                val construction = city.constructions.getConstruction(name)
                content.add(constructionRow(construction) {
                    screen.selectConstruction(construction)
                    screen.selectedQueueEntry = index + 1
                    detailOpen = true
                    refresh()
                }).height(66f).padBottom(4f).row()
            }
        }
        section("Can build")
        val available = (city.getRuleset().units.values.asSequence() +
            city.getRuleset().buildings.values.asSequence() +
            PerpetualConstruction.perpetualConstructionsMap.values.asSequence())
            .filter { city.constructions.shouldBeDisplayed(it) }
            .sortedWith(compareBy({ !city.constructions.isBuildable(it) }, { it.name }))
        for (construction in available) {
            val row = constructionRow(construction) {
                if (screen.selectedConstruction == construction && city.constructions.canAddToQueue(construction)) {
                    screen.queueConstruction(construction)
                    screen.updateAsync()
                } else {
                    screen.selectConstruction(construction)
                    detailOpen = true
                    refresh()
                }
            }
            content.add(row).height(66f).padBottom(4f).row()
        }
    }

    private fun constructionRow(construction: IConstruction, current: Boolean = false, built: Boolean = false,
                                onSelect: () -> Unit): Table {
        val row = Table().apply {
            background = bg(if (current) Color.valueOf("2b485d") else CARD)
            pad(7f)
            touchable = Touchable.enabled
            onClick { onSelect() }
        }
        row.add(ImageGetter.getConstructionPortrait(construction.name, 42f)).size(44f).padRight(10f)
        val details = Table().left()
        details.add(construction.name.toLabel(fontSize = 17, hideIcons = true)).left().row()
        val turns = if (built) "Built" else if (construction is PerpetualConstruction) city.getProductionTooltip(construction)
            else city.constructions.turnsToConstruction(construction.name).let { if (it == Int.MAX_VALUE) "∞ turns" else "$it turns" }
        details.add(turns.toLabel(INK2, 13)).left().padTop(3f).row()
        if (current && construction is INonPerpetualConstruction) {
            val cost = city.getConstructionProductionCost(construction).coerceAtLeast(1)
            val progress = city.constructions.getWorkDone(construction.name).toFloat() / cost
            details.add(ImageGetter.ProgressBar(145f, 6f, false)
                .setBackground(Color.WHITE.cpy().apply { a = .16f })
                .setProgress(YELLOW, progress.coerceIn(0f, 1f)))
                .left().padTop(7f)
        }
        row.add(details).growX().left()
        if (current && construction is INonPerpetualConstruction) {
            val price = city.constructions.getStatBuyCost(construction, Stat.Gold)
            if (price != null && city.constructions.isConstructionPurchaseAllowed(construction, Stat.Gold, price)) {
                val buy = "$price".toTextButton().apply {
                    color = YELLOW
                    onClickSuppressive {
                        screen.selectConstruction(construction)
                        buyButtons.askToBuyConstruction(construction, Stat.Gold)
                    }
                }
                row.add(buy).minWidth(58f).height(48f)
            }
        } else if (!current && selectedTab == Tab.Build && city.constructions.canAddToQueue(construction)) {
            val add = "+".toTextButton()
            add.onClickSuppressive {
                screen.queueConstruction(construction)
                screen.updateAsync()
            }
            row.add(add).size(48f)
        }
        if (screen.canCityBeChanged()) row.addContextMenu {
            CityScreenConstructionMenu(screen.stage, row, city, construction) { screen.updateAsync() }
        }
        return row
    }

    private fun constructionDetail() {
        val construction = screen.selectedConstruction ?: return
        val back = "‹  ${selectedTab.label}".toTextButton()
        back.onClick { detailOpen = false; screen.selectedQueueEntry = -1; refresh() }
        content.add(back).left().height(48f).row()
        constructionDetails.update(construction)
        content.add(constructionDetails).center().padTop(12f).row()
        val index = screen.selectedQueueEntry
        if (index >= 0 && screen.canCityBeChanged()) {
            val actions = Table()
            if (index > 0) actions.add("Move up".toTextButton().onClick {
                city.tryRaisePriority(index)
                screen.updateAsync()
            }).height(48f).pad(4f)
            if (index < city.constructions.constructionQueue.lastIndex) actions.add("Move down".toTextButton().onClick {
                city.tryLowerPriority(index)
                screen.updateAsync()
            }).height(48f).pad(4f)
            actions.add("Remove".toTextButton().onClick {
                city.tryRemoveFromQueue(index, automatic = false)
                screen.selectedQueueEntry = -1
                detailOpen = false
                screen.updateAsync()
            }).height(48f).pad(4f)
            content.add(actions).padTop(12f).row()
        }
    }

    private fun buildingsTab() {
        section("Built in ${city.name}")
        for (building in city.getBuiltBuildings().sortedBy { it.name }) {
            content.add(constructionRow(building, built = true) {
                screen.selectConstruction(building)
                detailOpen = true
                refresh()
            }).height(66f).padBottom(4f).row()
        }
        section("City details")
        if (cityDetailsOpen) {
            content.add("‹  Buildings".toTextButton().onClick {
                cityDetailsOpen = false
                refresh()
            }).height(48f).row()
            section(city.name)
            val stats = CityStatsTable(screen)
            stats.update(340f)
            content.add(stats).center().row()
        } else {
            content.add("Stats, religion and management".toTextButton().onClick {
                cityDetailsOpen = true
                scroll.scrollY = 0f
                refresh()
            }).height(52f).row()
        }
        val actions = Table()
        actions.add("Previous city".toTextButton().onClick { screen.page(-1) }).height(48f).pad(3f)
        actions.add("Next city".toTextButton().onClick { screen.page(1) }).height(48f).pad(3f)
        content.add(actions).padTop(10f).row()
        if (screen.canChangeState) {
            val cityAction = when {
                city.isPuppet() -> "Annex city" to { city.tryAnnexCity() }
                city.isBeingRazed() -> "Stop razing city" to { city.trySetRazing(false) }
                city.canBeDestroyed() -> "Raze city" to { city.trySetRazing(true) }
                else -> null
            }
            if (cityAction != null) content.add(cityAction.first.toTextButton().onClick {
                cityAction.second()
                screen.updateAsync()
            }).height(48f).padTop(8f).row()
        }
    }

    private fun tilesTab() {
        val selected = screen.selectedTile
        if (selected != null) {
            tileDetails.update(selected)
            content.add(tileDetails).center().padBottom(8f).row()
        }
        section("City tiles")
        for (tile in city.getTiles().sortedBy { it.position().toString() }) {
            val name = listOfNotNull(tile.getViewableResource(city.viewingCiv())?.name, tile.baseTerrain)
                .joinToString(" · ")
            val row = Table().apply {
                background = bg(CARD)
                touchable = Touchable.enabled
                pad(8f)
                add(name.toLabel(fontSize = 16, hideIcons = true)).growX().left()
                add((if (city.isWorked(tile)) "Worked" else if (city.canBuyTile(tile)) "Buy" else "View")
                    .toLabel(INK2, 13))
                onClick { showTile(tile) }
            }
            content.add(row).height(54f).padBottom(4f).row()
        }
    }

    private fun citizensTab() {
        section("${city.getFreePopulation()} unassigned of ${city.getPopulationCount()}")
        val controls = Table()
        controls.add(actionCard("Reset citizens") {
            city.tryReassignPopulation(resetLocked = true)
            screen.updateAsync()
        }).width((contentWidth - 8f) / 2f).height(52f).padRight(4f)
        controls.add(actionCard("Avoid growth", city.avoidGrowth) {
            city.tryToggleAvoidGrowth()
            screen.updateAsync()
        }).width((contentWidth - 8f) / 2f).height(52f).padLeft(4f)
        content.add(controls).row()
        section("Citizen focus")
        val focusGrid = Table()
        var count = 0
        for (focus in CityFocus.entries) {
            if (!focus.tableEnabled || focus == CityFocus.FaithFocus && !city.viewingCiv().isReligionEnabled()) continue
            val icon = focus.stat ?: when (focus) {
                CityFocus.GoldGrowthFocus -> Stat.Gold
                CityFocus.ProductionGrowthFocus -> Stat.Production
                else -> null
            }
            val label = when (focus) {
                CityFocus.GoldGrowthFocus -> "Gold + Food"
                CityFocus.ProductionGrowthFocus -> "Prod + Food"
                else -> icon?.name ?: focus.label
            }
            focusGrid.add(actionCard(label, city.getCityFocus() == focus, icon = icon) {
                city.trySetCityFocus(focus)
                screen.updateAsync()
            }).minWidth(0f).width((contentWidth - 16f) / 2f).height(52f).pad(4f)
            if (++count % 2 == 0) focusGrid.row()
        }
        content.add(focusGrid).row()
        if (city.getMaxSpecialists().isNotEmpty()) {
            section("Specialists")
            content.add(actionCard(if (city.manualSpecialists) "Manual specialists" else "Auto specialists",
                city.manualSpecialists) {
                if (city.manualSpecialists) city.tryDisableManualSpecialists() else city.tryEnableManualSpecialists()
                screen.updateAsync()
            }).height(52f).row()
            for ((name, max) in city.getMaxSpecialists().asSequence().sortedBy { it.key }) {
                val assigned = city.getNewSpecialists()[name]
                val row = Table().apply {
                    background = bg(CARD)
                    pad(5f)
                    add("$name  $assigned/$max".toLabel(fontSize = 16, hideIcons = true)).growX().left()
                    add(actionCard("−", enabled = assigned > 0) {
                        city.tryUnassignSpecialist(name)
                        screen.updateAsync()
                    }).size(48f).padRight(5f)
                    add(actionCard("+", enabled = assigned < max && city.getFreePopulation() > 0) {
                        city.tryAssignSpecialist(name)
                        screen.updateAsync()
                    }).size(48f)
                }
                content.add(row).height(62f).padBottom(6f).row()
            }
        }
        section("Tap city tiles on the map or use the Tiles tab to assign workers")
    }

    private fun actionCard(text: String, selected: Boolean = false, enabled: Boolean = screen.canCityBeChanged(),
                           icon: Stat? = null,
                           action: () -> Unit): Table = Table().apply {
        background = bg(if (selected) YELLOW else CARD)
        touchable = if (enabled) Touchable.enabled else Touchable.disabled
        if (icon != null) add(screen.portraitStatIcons.image(icon.name)).size(26f).padRight(7f)
        add(text.toLabel(if (selected) Color.valueOf("2b320e") else if (enabled) Color.WHITE else INK3,
            15, hideIcons = true).apply {
            if (icon != null) setText(text.tr(hideIcons = true, hideStats = true))
        }).center().pad(5f)
        if (enabled) onClick { action() }
    }

    private fun buyTab() {
        if (!screen.canChangeState) {
            section("Purchases are unavailable")
            return
        }
        section("Choose something to buy")
        val buyable = (city.getRuleset().units.values.asSequence() + city.getRuleset().buildings.values.asSequence())
            .filter { city.constructions.shouldBeDisplayed(it) && city.canBePurchasedWithAnyStat(it) }
            .sortedBy { it.name }
        for (construction in buyable) {
            content.add(constructionRow(construction) {
                screen.selectConstruction(construction)
                detailOpen = true
                refresh()
            }).height(66f).padBottom(4f).row()
        }
        val tile = city.chooseNewTileToOwn()
        if (tile != null) {
            section("Expand the city")
            content.add("Buy a tile".toTextButton().onClick { screen.askToBuyTile(tile) })
                .height(48f).row()
        }
    }
}
