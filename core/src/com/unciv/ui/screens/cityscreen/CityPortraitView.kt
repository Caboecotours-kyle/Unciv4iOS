package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RejectionReason
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.logic.city.CityFocus
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.tilegroups.CityTileState
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.CityScreenConstructionMenu
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.pickerscreens.CityRenamePopup
import com.unciv.view.TileView
import kotlin.math.abs
import kotlin.math.ceil
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
        private val YELLOW_INK = Color.valueOf("3a2a00")
        private val NAVY2 = Color.valueOf("1c3249")
        private val NAVY_INK = Color.valueOf("132435")
        private val LINE = Color(1f, 1f, 1f, .12f)
        private val OFF = Color(1f, 1f, 1f, .08f)
        private val CHIP = Color(1f, 1f, 1f, .06f)
        private val ICON_BG = Color.valueOf("2a4866")
        private val PROD = Color.valueOf("ff9a3c")
        private val FOOD_GREEN = Color.valueOf("9ff08a")
        private val RED = Color.valueOf("ffb1aa")
        private val FAITH = Color.valueOf("f3ecd2")
        private val FAITH_INK = Color.valueOf("4a3f1c")
        private fun bg(color: Color): Drawable = BaseScreen.skinStrings.getUiBackground(
            "", BaseScreen.skinStrings.roundedEdgeRectangleShape, color)
    }

    /** One construction row as the landscape list shows it */
    internal class ConstructionRow(val construction: IConstruction, val turns: String, val rejection: RejectionReason?,
                                   val stats: Stats?, val resources: List<Pair<String, Int>>)
    internal class BuyOption(val stat: Stat, val cost: Int, val allowed: Boolean)
    internal class BuyRow(val construction: INonPerpetualConstruction, val percentDone: Int,
                          val options: List<BuyOption>, val blocked: Boolean)
    /** The Build and Buy lists: too slow for the GL thread, so [CityScreen.updateAsync] gathers them like the landscape list */
    internal class Constructions(val categories: List<Pair<String, List<ConstructionRow>>>, val buyable: List<BuyRow>)
    /** What the sheet shows beyond the CityScreen selection, handed to the re-created screen on resize */
    internal class State(val row: String?, val queueEntry: Int, val detailOpen: Boolean, val cityDetailsOpen: Boolean,
                         val tileDetailOpen: Boolean, val scrollY: Float, val constructions: Constructions?, val collapsed: Boolean,
                         val peekScrollY: Float, val peekTab: Tab)

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
    private var detailOpen = false
    private var cityDetailsOpen = false
    private var tileDetailOpen = false
    private var collapsed = false
    private var expandedY = 0f
    private var expandedYInitialized = false
    private val peekHeight = 64f
    private var peekScrollY = 0f
    private var peekTab = selectedTab
    /** The list row whose actions are expanded; keys carry the construction name so a changed queue never matches */
    private var selectedRow: String? = null
    /** Latest background gather; null until the first one lands */
    internal var constructions: Constructions? = null

    init {
        background = bg(NAVY)
        touchable = Touchable.enabled
        defaults().minWidth(0f).width(sheetWidth)
        constructionDetails.background = bg(CARD)
        tileDetails.background = bg(CARD)
        // The inner cards already supply padding within their 369pt width.
        constructionDetails.pad(0f)
        tileDetails.pad(0f)
        val handle = Table().apply { add(Image(ImageGetter.getWhiteDotDrawable().tint(INK3))).size(36f, 4f) }
        // Handle and heading together are the drag zone, so the 12pt handle row stays as drawn
        handle.touchable = Touchable.enabled
        heading.touchable = Touchable.enabled
        add(handle).growX().height(12f).row()
        add(heading).growX().row()
        add(yields).growX().row()
        add(scroll).grow().prefHeight(0f).row()
        add(tabs).growX().height(72f)
        addListener(SwipeCitySheet())
    }

    /** A header swipe reveals the map; the same sheet, tab and selection remain ready below it. */
    private inner class SwipeCitySheet : InputListener() {
        private val slop = 10f
        private val threshold = 80f
        private var startX = 0f
        private var startY = 0f
        private var restY = 0f
        private var tracking = false
        private var dragging = false

        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            if (tracking) { snapBack(); return false }
            if (pointer != 0 || button != 0 || y < heading.y) return false
            if (!expandedYInitialized) {
                expandedY = this@CityPortraitView.y
                expandedYInitialized = true
            }
            clearActions()
            startX = event.stageX
            startY = event.stageY
            restY = this@CityPortraitView.y
            tracking = true
            dragging = false
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            if (!tracking || pointer != 0) return
            if (Gdx.input.isTouched(1)) { snapBack(); return }
            val dx = event.stageX - startX
            val travel = (if (collapsed) event.stageY - startY else startY - event.stageY)
            if (!dragging) {
                val slopStage = slop * scaleY
                if (abs(dx) > slopStage && abs(dx) >= travel) { snapBack(); return }
                if (travel <= slopStage || travel < abs(dx)) return
                dragging = true
                // The drag owns this touch now: no title or × activation on release
                event.stage.cancelTouchFocusExcept(this, this@CityPortraitView)
            }
            this@CityPortraitView.y = if (collapsed) (restY + travel.coerceAtLeast(0f)).coerceAtMost(expandedY)
                else restY - travel.coerceAtLeast(0f)
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            if (!tracking || pointer != 0) return
            val travel = (if (collapsed) event.stageY - startY else startY - event.stageY)
            if (event.isTouchFocusCancel || Gdx.input.isTouched(1)) {
                snapBack()
                return
            }
            tracking = false
            if (collapsed && (!dragging || travel >= threshold * scaleY)) expand()
            else if (!collapsed && dragging && travel >= threshold * scaleY) collapse()
            else snapBack()
        }

        private fun snapBack() {
            val targetY = if (collapsed) collapsedY() else expandedY
            if (this@CityPortraitView.y != targetY)
                addAction(Actions.moveTo(this@CityPortraitView.x, targetY, .12f, Interpolation.fastSlow))
            tracking = false
            dragging = false
        }
    }

    private fun collapsedY() = expandedY - (height - peekHeight) * scaleY

    private fun refreshForPeek() {
        val savedScroll = scroll.scrollY
        refresh()
        validate()
        scroll.scrollY = savedScroll
        scroll.updateVisualScroll()
    }

    private fun collapse() {
        peekScrollY = scroll.scrollY
        peekTab = selectedTab
        collapsed = true
        refreshForPeek()
        screen.setMapPeekCollapsed(true)
        addAction(Actions.moveTo(x, collapsedY(), .15f, Interpolation.fastSlow))
    }

    private fun expand() {
        collapsed = false
        refreshForPeek()
        if (selectedTab == peekTab) {
            scroll.scrollY = peekScrollY
            scroll.updateVisualScroll()
        }
        screen.setMapPeekCollapsed(false)
        addAction(Actions.moveTo(x, expandedY, .15f, Interpolation.fastSlow))
    }

    fun showTile(tile: TileView) {
        selectedTab = Tab.Tiles
        detailOpen = false
        tileDetailOpen = false
        screen.selectTile(tile)
        refresh()
    }

    fun state() = State(selectedRow, screen.selectedQueueEntry, detailOpen, cityDetailsOpen, tileDetailOpen,
        scroll.scrollY, constructions, collapsed, peekScrollY, peekTab)

    /** Resize re-creates the screen with the same selection; this puts the rest of the sheet back */
    fun restore(state: State) {
        selectedRow = state.row
        screen.selectedQueueEntry = state.queueEntry
        detailOpen = state.detailOpen
        cityDetailsOpen = state.cityDetailsOpen
        tileDetailOpen = state.tileDetailOpen
        if (constructions == null) constructions = state.constructions
        expandedY = y
        expandedYInitialized = true
        collapsed = state.collapsed
        peekScrollY = state.peekScrollY
        peekTab = state.peekTab
        refresh()
        validate()
        if (collapsed) {
            y = collapsedY()
            screen.setMapPeekCollapsed(true)
        }
        scroll.scrollY = state.scrollY
        scroll.updateVisualScroll()
    }

    fun refresh() {
        val oldScroll = scroll.scrollY
        yields.isVisible = !collapsed
        scroll.isVisible = !collapsed
        tabs.isVisible = !collapsed
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
        if (collapsed) {
            heading.pad(0f, 16f, 0f, 16f)
            heading.add(city.name.toLabel(fontSize = 20, hideIcons = true).apply { setEllipsis(true) })
                .minWidth(0f).growX().left().height(48f)
            heading.add("Swipe up".toLabel(INK2, 13)).right()
            return
        }
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
                    if (selectedTab == tab && !detailOpen && !cityDetailsOpen && !tileDetailOpen) return@onClick
                    selectedTab = tab
                    detailOpen = false
                    cityDetailsOpen = false
                    tileDetailOpen = false
                    selectedRow = null
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

    private fun text(text: String, color: Color = Color.WHITE, size: Int = 16) =
        text.toLabel(color, size, hideIcons = true).apply { setEllipsis(true) }

    private fun line() = Image(ImageGetter.getWhiteDotDrawable().tint(LINE))

    private fun amount(value: Float) = if (value == value.toInt().toFloat()) value.toInt().tr() else "%.1f".format(value)

    /** Small yield icons with values, the way the mock rows show stats */
    private fun statLine(stats: Stats, color: Color = INK2): Table = Table().left().apply {
        for ((stat, value) in stats) {
            add(screen.portraitStatIcons.image(stat.name)).size(16f).padRight(2f)
            add(amount(value).toLabel(color, 13)).padRight(8f)
        }
    }

    private fun tag(text: String, color: Color): Table = Table().apply {
        background = bg(color.cpy().apply { a = .16f })
        add(text.toLabel(color, 12, hideIcons = true)).pad(3f, 8f, 3f, 8f)
    }

    /** 48pt tall action; [fill] null draws the quiet mini style */
    private fun pill(text: String, fill: Color? = null, ink: Color = Color.WHITE, enabled: Boolean = true,
                     action: () -> Unit): Table = Table().apply {
        background = bg(if (!enabled || fill == null) OFF else fill)
        add(text.toLabel(if (enabled) ink else INK3, 15)).pad(0f, 14f, 0f, 14f)
        if (enabled) {
            touchable = Touchable.enabled
            onClickSuppressive { action() }
        }
    }

    /** Actions under a selected row, wrapped without shrinking their touch targets. */
    private fun strip(vararg lines: List<Actor>) {
        val strip = Table().left()
        val available = contentWidth - 56f
        for (actors in lines.filter { it.isNotEmpty() }) {
            var line = Table().left()
            var used = 0f
            for (actor in actors) {
                val preferred = (actor as? Layout)?.prefWidth ?: actor.width
                val width = preferred.coerceAtMost(available)
                val gap = if (used == 0f) 0f else 8f
                if (used > 0f && used + gap + width > available) {
                    strip.add(line).left().padBottom(8f).row()
                    line = Table().left()
                    used = 0f
                }
                if (actor is com.badlogic.gdx.scenes.scene2d.ui.Label && preferred > available) actor.wrap = true
                val cell = line.add(actor).height(48f)
                if (used > 0f) cell.padLeft(8f)
                if (preferred > available) cell.width(available)
                used += width + if (used == 0f) 0f else 8f
            }
            strip.add(line).left().padBottom(8f).row()
        }
        content.add(strip).width(available).padLeft(56f).left().row()
    }

    /** A 66pt list row: icon, title, detail lines, optional trailing actor, bottom rule. */
    private fun listRow(selected: Boolean, icon: Actor, title: String, titleColor: Color = Color.WHITE,
                        trailing: Actor? = null, details: Table.() -> Unit = {}, onTap: (() -> Unit)?): Table {
        val row = Table().apply {
            if (selected) background = bg(YELLOW.cpy().apply { a = .08f })
            pad(8f, 4f, 8f, 4f)
        }
        row.add(icon).size(46f).padRight(10f)
        val info = Table().left()
        info.add(text(title, titleColor, 17)).minWidth(0f).growX().left().row()
        info.details()
        row.add(info).minWidth(0f).growX().left()
        if (trailing != null) row.add(trailing).padLeft(8f)
        if (onTap != null) {
            row.touchable = Touchable.enabled
            row.onClick { onTap() }
        }
        content.add(row).minHeight(66f).row()
        return row
    }

    private fun rule() = content.add(line()).height(1f).row()

    private fun iconBox(actor: Actor) = Table().apply {
        background = bg(ICON_BG)
        add(actor).size(32f)
    }

    /** Tapping the open row closes it; tapping another opens that one and runs [select]. */
    private fun toggleRow(key: String, select: () -> Unit) {
        if (selectedRow == key) {
            selectedRow = null
            screen.clearSelection()
            screen.selectedQueueEntry = -1
        } else {
            selectedRow = key
            select()
        }
        refresh()
    }

    private fun openDetails(construction: IConstruction, queueIndex: Int) {
        screen.selectConstruction(construction)
        screen.selectedQueueEntry = queueIndex
        detailOpen = true
        refresh()
    }

    private fun turnsText(construction: IConstruction): String {
        if (construction is PerpetualConstruction) return city.getProductionTooltip(construction).trim()
        val constructions = city.constructions
        val turns = constructions.turnsToConstruction(construction.name,
            construction is Building || !constructions.isBeingConstructedOrEnqueued(construction.name))
        return if (turns == Int.MAX_VALUE) "∞" else "$turns turns"
    }

    private fun turnsLabel(row: ConstructionRow) =
        if (row.construction is PerpetualConstruction) null else text(row.turns, INK2, 13)

    private fun constructionRow(construction: IConstruction, rejection: RejectionReason?): ConstructionRow {
        // Can hit a comodification while the GL thread changes the city; landscape retries once too
        val turns = try { turnsText(construction) } catch (_: Exception) { turnsText(construction) }
        val resources = city.getResourceRequirementsPerTurn(construction).entries +
            city.getStockpiledResourceRequirements(construction).entries
        return ConstructionRow(construction, turns, rejection, (construction as? Building)?.let { city.getBuildingStats(it) },
            resources.map { it.key to it.value })
    }

    /** Runs off the GL thread from [CityScreen.updateAsync]: the landscape list's filter, categories and rejections */
    internal fun gatherConstructions(): Constructions {
        if (screen.isSpying) return Constructions(emptyList(), emptyList())
        val constructions = city.constructions
        val displayed = (city.getRuleset().units.values.asSequence() + city.getRuleset().buildings.values.asSequence())
            .filter { constructions.shouldBeDisplayed(it) }.toList()
        val rejections = displayed.associateWith { entry ->
            constructions.getRejectionReasons(entry).filter { it.isImportantRejection() }
                .minByOrNull { it.getRejectionPrecedence() }
        }
        val shown = rejections.filterNot { (entry, rejection) ->
            entry is Building && rejection?.type == RejectionReasonType.RequiresBuildingInThisCity
                && rejections.keys.any {
                    it is Building && (it.name == entry.requiredBuilding || it.replaces == entry.requiredBuilding
                        || it.hasTagUnique(entry.requiredBuilding!!, city.getState()))
                }
        }
        val rejectionOf = shown.mapKeys { it.key.name }
        val disabled = city.getDisabledConstructions()
        val perpetual = PerpetualConstruction.perpetualConstructionsMap.values.filter { constructions.shouldBeDisplayed(it) }
        val categories = listOf<Pair<String, List<IConstruction>>>(
            "Units" to shown.keys.filter { it is BaseUnit && it.name !in disabled },
            "Buildings" to shown.keys.filter { it is Building && !it.isAnyWonder() && it.name !in disabled },
            "Wonders" to shown.keys.filter { it is Building && it.isWonder && it.name !in disabled },
            "National Wonders" to shown.keys.filter { it is Building && it.isNationalWonder && it.name !in disabled },
            "Other" to perpetual.filter { it.name !in disabled },
            "Disabled" to (shown.keys + perpetual).filter { it.name in disabled },
        ).map { (category, entries) -> category to entries.map { constructionRow(it, rejectionOf[it.name]) } }

        val buyable = if (!screen.canChangeState) emptyList() else displayed
            .filter { city.canBePurchasedWithAnyStat(it) }
            .sortedWith(compareBy({ it !is BaseUnit }, { it.name }))
            .map {
                val cost = city.getConstructionProductionCost(it).coerceAtLeast(1)
                BuyRow(it, constructions.getWorkDone(it.name) * 100 / cost, buyOptions(it),
                    canBuyHere() && constructions.isConstructionPurchaseBlockedByUnit(it))
            }
        return Constructions(categories, buyable)
    }

    /** Same visibility gate as the landscape construction panel's buy buttons */
    private fun canBuyHere() = screen.canChangeState &&
        (!city.isPuppet() || city.hasMatchingUnique(UniqueType.MayBuyConstructionsInPuppets))

    /** The currencies the landscape buy buttons show, with their enable rule */
    private fun buyOptions(construction: IConstruction, stats: Collection<Stat> = Stat.statsUsableToBuy): List<BuyOption> {
        if (construction !is INonPerpetualConstruction || !canBuyHere()) return emptyList()
        return stats.filter { city.canBePurchasedWithStat(construction, it) }.map { stat ->
            val cost = city.constructions.getStatBuyCost(construction, stat)!!
            BuyOption(stat, cost, city.constructions.isConstructionPurchaseAllowed(construction, stat, cost))
        }
    }

    /** One pill per buy option, with the landscape confirm path */
    private fun buyPills(construction: IConstruction, queueIndex: Int,
                         options: List<BuyOption> = buyOptions(construction)): List<Actor> {
        if (construction !is INonPerpetualConstruction) return emptyList()
        return options.map { option ->
            val fill = if (option.stat == Stat.Faith) FAITH else YELLOW
            val ink = if (option.stat == Stat.Faith) FAITH_INK else YELLOW_INK
            pill("${option.cost.tr()}${option.stat.character}", fill, ink, option.allowed) {
                buy(construction, option.stat, queueIndex)
            }
        }
    }

    private fun blockedHint(construction: IConstruction): List<Actor> =
        if (construction is INonPerpetualConstruction && canBuyHere()
            && city.constructions.isConstructionPurchaseBlockedByUnit(construction))
            listOf("Move unit out of city first".toLabel(RED, 13))
        else emptyList()

    /** Mirrors BuyButtonFactory's click: CreatesOneImprovement buildings pick or reuse their tile first */
    private fun buy(construction: INonPerpetualConstruction, stat: Stat, queueIndex: Int) {
        if (!screen.canChangeState) return
        screen.selectConstruction(construction)
        screen.selectedQueueEntry = queueIndex
        if (construction is Building && construction.hasCreateOneImprovementUnique()) {
            if (queueIndex < 0) return screen.startPickTileForCreatesOneImprovement(construction, stat, true)
            val improvement = city.getImprovementToCreate(construction) ?: return
            return buyButtons.askToBuyConstruction(construction, stat,
                city.constructions.getTileForImprovement(improvement.name))
        }
        buyButtons.askToBuyConstruction(construction, stat)
    }

    private fun constructionMenu(row: Table, construction: IConstruction) {
        if (screen.canCityBeChanged()) row.addContextMenu {
            CityScreenConstructionMenu(screen.stage, row, city, construction) { screen.updateAsync() }
        }
    }

    private fun buildTab() {
        if (screen.isSpying) {
            section("Construction is hidden while spying")
            return
        }
        val constructions = city.constructions
        val queue = constructions.constructionQueue
        val current = queue.firstOrNull()?.let { constructions.getConstruction(it) }
        if (current != null) currentCard(current)
        else content.add(text("Pick a construction", size = 18)).left().pad(12f).row()
        if (queue.size > 1) {
            section("Up next")
            queue.forEachIndexed { index, name -> if (index > 0) queueRow(index, constructions.getConstruction(name)) }
        }
        availableConstructions()
    }

    private fun currentCard(construction: IConstruction) {
        val key = "q-0-${construction.name}"
        val card = Table().apply {
            background = bg(NAVY2)
            pad(14f)
            touchable = Touchable.enabled
            onClick { toggleRow(key) { screen.selectConstruction(construction); screen.selectedQueueEntry = 0 } }
        }
        card.add(ImageGetter.getConstructionPortrait(construction.name, 52f)).size(52f).padRight(12f)
        val info = Table().left()
        info.add(text(construction.name, size = 18)).minWidth(0f).growX().left().row()
        info.add(text(turnsText(construction), INK2, 13)).minWidth(0f).growX().left().padTop(3f)
        card.add(info).minWidth(0f).growX().left()
        buyPills(construction, 0, buyOptions(construction, listOf(Stat.Gold))).firstOrNull()?.let { card.add(it).height(48f).padLeft(8f) }
        if (construction is INonPerpetualConstruction) {
            val cost = city.getConstructionProductionCost(construction).coerceAtLeast(1)
            val done = city.constructions.getWorkDone(construction.name)
            card.row()
            val progress = Table()
            progress.add(ImageGetter.ProgressBar(contentWidth - 28f, 9f, false)
                .setBackground(Color.WHITE.cpy().apply { a = .12f })
                .setProgress(PROD, (done.toFloat() / cost).coerceIn(0f, 1f))).left().row()
            progress.add("$done/$cost${Fonts.production}".toLabel(INK3, 12)).left().padTop(4f)
            card.add(progress).colspan(card.columns).left().padTop(12f)
        }
        content.add(card).padTop(4f).row()
        if (selectedRow == key) {
            val otherPills = buyPills(construction, 0, buyOptions(construction, Stat.statsUsableToBuy - Stat.Gold))
            strip(queueControls(0, construction), otherPills + detailsButton(construction, 0), blockedHint(construction))
        }
        constructionMenu(card, construction)
    }

    private fun queueRow(index: Int, construction: IConstruction) {
        val key = "q-$index-${construction.name}"
        val facts = constructionRow(construction, null)
        val row = listRow(selectedRow == key, ImageGetter.getConstructionPortrait(construction.name, 46f),
            construction.name, trailing = turnsLabel(facts),
            details = { constructionFacts(facts) }) {
            toggleRow(key) { screen.selectConstruction(construction); screen.selectedQueueEntry = index }
        }
        if (selectedRow == key)
            strip(queueControls(index, construction),
                buyPills(construction, index) + detailsButton(construction, index), blockedHint(construction))
        rule()
        constructionMenu(row, construction)
    }

    /** Landscape queue arrows and stop button: same commands, same reassign afterwards */
    private fun queueControls(index: Int, construction: IConstruction): List<Actor> {
        if (!screen.canCityBeChanged()) return emptyList()
        val queue = city.constructions.constructionQueue
        fun stillAt() = queue.getOrNull(index) == construction.name
        fun move(movePriority: (Int) -> Int?) {
            if (!stillAt()) return
            val newIndex = movePriority(index) ?: return
            screen.selectConstruction(construction)
            screen.selectedQueueEntry = newIndex
            selectedRow = "q-$newIndex-${construction.name}"
            city.tryReassignPopulation()
            screen.updateAsync()
        }
        val controls = ArrayList<Actor>()
        if (index > 0) controls += pill("Move up") { move(city::tryRaisePriority) }
        if (index < queue.lastIndex) controls += pill("Move down") { move(city::tryLowerPriority) }
        controls += pill("Remove", ink = RED) {
            if (stillAt() && city.tryRemoveFromQueue(index, false)) city.tryReassignPopulation()
            // Like landscape: select the entry that moved up, or the new last one
            val next = index.coerceAtMost(queue.lastIndex)
            if (next >= 0) {
                screen.selectConstructionFromQueue(next)
                screen.selectedQueueEntry = next
                selectedRow = "q-$next-${queue[next]}"
            } else {
                selectedRow = null
                screen.clearSelection()
                screen.selectedQueueEntry = -1
            }
            screen.updateAsync()
        }
        return controls
    }

    private fun detailsButton(construction: IConstruction, queueIndex: Int): Actor =
        pill("Details") { openDetails(construction, queueIndex) }

    /** Stat line, wonder tag, resource needs and the landscape's most important rejection */
    private fun Table.constructionFacts(row: ConstructionRow) {
        val construction = row.construction
        val rejection = row.rejection
        if (construction is PerpetualConstruction) {
            add(text(row.turns, INK2, 13)).minWidth(0f).growX().left().padTop(3f).row()
            return
        }
        val line = Table().left()
        if (row.stats != null) line.add(statLine(row.stats))
        if (construction is BaseUnit) {
            val stats = Table().left()
            for ((icon, value) in listOf("Strength" to construction.strength,
                "Ranged" to construction.rangedStrength, "Movement" to construction.movement)) {
                if (value == 0) continue
                stats.add(screen.portraitStatIcons.image(icon)).size(16f).padRight(2f)
                stats.add(value.tr().toLabel(INK2, 13)).padRight(8f)
            }
            line.add(stats)
        }
        if (construction is Building && construction.isWonder) line.add(tag("World wonder", YELLOW)).padLeft(4f)
        if (construction is Building && construction.isNationalWonder) line.add(tag("National wonder", YELLOW)).padLeft(4f)
        val resourceColor = if (rejection?.type == RejectionReasonType.ConsumesResources) RED else INK2
        for ((resource, amount) in row.resources) {
            line.add(amount.tr().toLabel(resourceColor, 13)).padLeft(6f)
            line.add(ImageGetter.getResourcePortrait(resource, 15f)).padLeft(2f)
        }
        add(line).left().padTop(3f).row()
        if (rejection != null && rejection.type != RejectionReasonType.ConsumesResources)
            add(ColorMarkupLabel(rejection.errorMessage, RED, fontSize = 13).apply { wrap = true })
                .minWidth(0f).growX().left().padTop(3f).row()
    }

    /** The landscape "available constructions" list, from the latest background gather */
    private fun availableConstructions() {
        val data = constructions ?: return section(Constants.loading)
        for ((category, rows) in data.categories) {
            if (rows.isEmpty()) continue
            section(category)
            for (row in rows) availableRow(row)
        }
    }

    private fun availableRow(facts: ConstructionRow) {
        val construction = facts.construction
        val key = "c-${construction.name}"
        val icon = ImageGetter.getConstructionPortrait(construction.name, 46f)
        if (facts.rejection != null) icon.color.a = .5f
        val row = listRow(selectedRow == key, icon, construction.name, if (facts.rejection == null) Color.WHITE else RED,
            trailing = turnsLabel(facts),
            details = { constructionFacts(facts) }) {
            toggleRow(key) { screen.selectConstruction(construction); screen.selectedQueueEntry = -1 }
        }
        if (selectedRow == key) {
            val addToQueue = if (canQueue(construction)) listOf(pill("Add to queue", YELLOW, YELLOW_INK) {
                screen.queueConstruction(construction)
                selectedRow = null
                screen.updateAsync()
            }) else emptyList()
            strip(addToQueue, buyPills(construction, -1) + detailsButton(construction, -1), blockedHint(construction))
        }
        rule()
        constructionMenu(row, construction)
    }

    /** CityConstructionsTable.cannotAddConstructionToQueue, which queueConstruction checks again */
    private fun canQueue(construction: IConstruction): Boolean {
        val constructions = city.constructions
        return !constructions.isQueueFull() && screen.canChangeState && !city.isPuppet()
            && constructions.isBuildable(construction)
            && !(construction is PerpetualConstruction && constructions.isBeingConstructedOrEnqueued(construction.name))
    }

    /** Drill-ins share one back pill; it returns to the tab list where the sheet left it */
    private fun back(label: String, onBack: () -> Unit) {
        content.add(pill("‹  $label") { onBack(); refresh() }).left().height(48f).row()
    }

    /** A legacy drill-in widget at its own layout, shrunk to the sheet when it is wider */
    private inner class Fitted(private val actor: Table) : WidgetGroup() {
        init { addActor(actor) }
        private val fit get() = (contentWidth / actor.prefWidth).coerceAtMost(1f)
        override fun getPrefWidth() = actor.prefWidth * fit
        override fun getPrefHeight() = actor.prefHeight * fit
        override fun layout() {
            actor.isTransform = fit < 1f
            actor.setScale(fit)
            actor.setBounds((width - actor.prefWidth * fit) / 2f, 0f, actor.prefWidth, actor.prefHeight)
        }
    }

    private fun drillIn(actor: Table) = content.add(Fitted(actor)).padTop(12f).row()

    private fun constructionDetail() {
        val construction = screen.selectedConstruction ?: return
        back(selectedTab.label) { detailOpen = false }
        constructionDetails.update(construction)
        drillIn(constructionDetails)
    }

    private fun buildingsTab() {
        if (cityDetailsOpen) {
            back(Tab.Buildings.label) { cityDetailsOpen = false }
            val stats = CityStatsTable(screen) { building ->
                cityDetailsOpen = false
                openDetails(building, -1)
            }
            // Its own list scrolls inside; give it the sheet's visible height rather than a fixed box
            stats.update((scroll.height - 72f).coerceAtLeast(340f))
            drillIn(stats)
            return
        }
        section("Built in ${city.name}")
        for (building in city.getBuiltBuildings().sortedBy { it.name }) {
            val key = "b-${building.name}"
            val free = screen.hasFreeBuilding(building)
            listRow(selectedRow == key, ImageGetter.getConstructionPortrait(building.name, 46f), building.name,
                details = {
                    val line = Table().left()
                    line.add(statLine(city.getBuildingStats(building)))
                    if (building.isWonder) line.add(tag("World wonder", YELLOW)).padLeft(4f)
                    if (building.isNationalWonder) line.add(tag("National wonder", YELLOW)).padLeft(4f)
                    if (free) line.add(tag("Free", INK2)).padLeft(4f)
                    add(line).left().padTop(3f)
                }) { toggleRow(key) { screen.selectConstruction(building); screen.selectedQueueEntry = -1 } }
            if (selectedRow == key) strip(sellButton(building, free) + detailsButton(building, -1))
            rule()
        }

        section("City")
        listRow(false, iconBox(ImageGetter.getImage("OtherIcons/Search")), "City details",
            trailing = "›".toLabel(INK3, 24), details = {
                add(text("Stats, religion, resources", INK2, 13)).minWidth(0f).growX().left().padTop(3f)
            }) {
            cityDetailsOpen = true
            scroll.scrollY = 0f
            refresh()
        }
        rule()
        if (screen.canChangeState) {
            listRow(false, iconBox(ImageGetter.getImage("OtherIcons/Pencil")), "Rename city",
                trailing = "›".toLabel(INK3, 24)) {
                CityRenamePopup(screen, city) { screen.game.replaceCurrentScreen { CityScreen(screen.cityView) } }
            }
            rule()
        }
        val cities = screen.viewableCities
        if (cities.size > 1) {
            val index = cities.indexOfFirst { it == city }
            val previous = cities[(index - 1 + cities.size) % cities.size]
            val next = cities[(index + 1) % cities.size]
            val paging = Table().left()
            paging.add(pill("‹  ${previous.name}") { screen.page(-1) }).height(48f).padRight(8f)
            paging.add(pill("${next.name}  ›") { screen.page(1) }).height(48f)
            content.add(paging).left().padTop(12f).row()
        }
        content.add(cityAction()).left().height(48f).padTop(12f).row()
    }

    /** ConstructionInfoTable's sell button: same gates, same confirm popup, same command */
    private fun sellButton(building: Building, free: Boolean): List<Actor> {
        if (!building.isSellable()) return emptyList()
        val amount = city.getGoldForSellingBuilding(building.name)
        val sellText = "{Sell} $amount " + Fonts.gold
        val enabled = !free && !city.isPuppet() && screen.canChangeState &&
            (!city.hasSoldBuildingThisTurn() || city.isGodModeEnabled())
        return listOf(pill(sellText, YELLOW, YELLOW_INK, enabled) {
            screen.closeAllPopups()
            ConfirmPopup(screen, "Are you sure you want to sell this [${building.name}]?", sellText,
                restoreDefault = { screen.updateAsync() }) {
                city.trySellBuilding(building)
                selectedRow = null
                screen.clearSelection()
                screen.updateAsync()
            }.open()
        })
    }

    /** The landscape annex / raze / stop razing button: same choice, greyed out where landscape disables it */
    private fun cityAction(): Actor {
        val canAnnex = !city.viewingCiv().hasUnique(UniqueType.MayNotAnnexCities)
        return when {
            city.isPuppet() && canAnnex -> pill("Annex city", YELLOW, YELLOW_INK, screen.canChangeState) {
                city.tryAnnexCity()
                screen.updateAsync()
            }
            !city.isBeingRazed() -> pill("Raze city", ink = RED,
                enabled = screen.canChangeState && city.canBeDestroyed() && canAnnex) {
                city.trySetRazing(true)
                screen.updateAsync()
            }
            else -> pill("Stop razing city", enabled = screen.canChangeState) {
                city.trySetRazing(false)
                screen.updateAsync()
            }
        }
    }

    private fun tileName(tile: TileView): String =
        (listOf(tile.baseTerrain) + tile.terrainFeatures + listOfNotNull(tile.getViewableResource(city.viewingCiv())?.name))
            .joinToString(", ") { it.tr(hideIcons = true) }

    private fun tileIcon(tile: TileView): Actor {
        val resource = tile.getViewableResource(city.viewingCiv())
        val improvement = tile.getShownImprovement()
        return when {
            tile.isCityCenter() -> iconBox(ImageGetter.getImage("TileIcons/CityCenter"))
            resource != null -> ImageGetter.getResourcePortrait(resource.name, 46f)
            improvement != null -> ImageGetter.getImprovementPortrait(improvement, 46f)
            else -> iconBox(ImageGetter.getImage("OtherIcons/Hexagon").apply { color = tile.getBaseTerrain().getColor() })
        }
    }

    private fun tileTags(tile: TileView, state: CityTileState): Table = Table().left().apply {
        when {
            city.isWorked(tile) -> add(tag(if (tile.isLocked()) "Locked" else "Worked", FOOD_GREEN))
            tile.isCityCenter() && city.isOwnedTile(tile) -> add(tag("City center", INK2))
            state == CityTileState.BLOCKADED -> add(tag("Blockaded", RED))
            !screen.isSpying && city.isOwnedTile(tile) && state == CityTileState.WORKABLE ->
                add(tag("Unworked", INK2))
        }
        if (city.canBuyTile(tile)) {
            val cost = city.getGoldCostOfTile(tile)
            val affordable = city.viewingCiv().hasStatToBuy(Stat.Gold, cost)
            add(tag("${cost.tr()}${Fonts.gold}", if (affordable) YELLOW else RED)).padLeft(4f)
        }
    }

    private fun tilesTab() {
        if (tileDetailOpen && screen.selectedTile != null) {
            back(Tab.Tiles.label) { tileDetailOpen = false }
            tileDetails.update(screen.selectedTile)
            drillIn(tileDetails)
            return
        }
        val states = screen.tileStates()
        val selected = screen.selectedTile
        if (selected != null) tileCard(selected, states[selected] ?: CityTileState.NONE)
        else section("Tap a tile on the map or below")

        if (!screen.isSpying && city.isOwnedByViewer()) {
            section("Border expansion")
            val next = screen.nextTileToOwn
            if (next == null) {
                listRow(false, iconBox(ImageGetter.getImage("OtherIcons/HexagonOutline")), "No eligible tile", onTap = null)
                rule()
            } else {
                // CityExpansionManager credits culture.toInt() each turn, so fractional income cannot start a countdown.
                val culturePerTurn = city.getCurrentCityStats().culture.toInt()
                val remaining = city.getCultureToNextTile() - city.getCultureStored()
                val turns = when {
                    remaining <= culturePerTurn -> 1
                    culturePerTurn > 0 -> ceil(remaining.toDouble() / culturePerTurn).toInt()
                    else -> null
                }
                val explored = city.viewingCiv().hasExplored(next)
                val tileLabel = if (explored) tileName(next) else "Unexplored tile"
                val progress = "${city.getCultureStored()} / ${city.getCultureToNextTile()} culture"
                listRow(false, iconBox(ImageGetter.getImage("OtherIcons/HexagonOutline")), "Next border tile",
                    trailing = (turns?.let { "[$it] turns" } ?: "Paused").toLabel(if (turns == null) INK2 else YELLOW, 13),
                    details = {
                        add(text(tileLabel, INK2, 13)).left().row()
                        add(text("$progress · +$culturePerTurn per turn", INK3, 12)).left().padTop(2f)
                    }, onTap = if (explored) ({ screen.selectTile(next); scroll.scrollY = 0f; refresh() }) else null)
                rule()
            }
        }

        val tiles = states.keys.filter { city.isOwnedTile(it) || city.canBuyTile(it) }
        val owned = tiles.count { city.isOwnedTile(it) }
        section("City tiles · ${tiles.count { city.isWorked(it) }} of $owned worked")
        val sorted = tiles.sortedWith(compareBy<TileView>(
            { !it.isCityCenter() }, { !city.isWorked(it) }, { states[it] != CityTileState.WORKABLE },
            { !city.isOwnedTile(it) }, { if (city.canBuyTile(it)) city.getGoldCostOfTile(it) else 0 }))
        for (tile in sorted) {
            listRow(tile == selected, tileIcon(tile), tileName(tile), details = {
                add(statLine(tile.getTileStats(city.viewingCiv(), city))).left().padTop(3f).row()
            }, trailing = tileTags(tile, states[tile] ?: CityTileState.NONE)) {
                if (tile == selected) screen.clearSelection() else screen.selectTile(tile)
                scroll.scrollY = 0f
                refresh()
            }
            rule()
        }
    }

    /** The selected tile inline: what the legacy tile table shows, with the map's worker toggle and buy */
    private fun tileCard(tile: TileView, state: CityTileState) {
        val card = Table().apply {
            background = bg(NAVY2)
            pad(14f)
        }
        card.add(tileIcon(tile)).size(52f).padRight(12f)
        val info = Table().left()
        info.add(text(tileName(tile), size = 18)).minWidth(0f).growX().left().row()
        info.add(statLine(tile.getTileStats(city.viewingCiv(), city), Color.WHITE)).left().padTop(4f).row()
        info.add(tileTags(tile, state)).left().padTop(4f)
        card.add(info).minWidth(0f).growX().left().row()

        val actions = Table().left()
        if (state == CityTileState.WORKABLE && screen.canChangeState && !city.isPuppet()) {
            val assign = !tile.providesYield()
            val enabled = !assign || city.getFreePopulation() > 0
            actions.add(pill(if (assign) "Assign citizen" else "Unassign", if (assign) YELLOW else null,
                if (assign) YELLOW_INK else Color.WHITE, enabled) { screen.toggleTileWorked(tile) })
                .height(48f).padRight(8f)
        }
        if (city.isWorked(tile)) {
            val locked = tile.isLocked()
            actions.add(pill(if (locked) "Unlock" else "Lock", enabled = screen.canChangeState) {
                if (locked) city.tryUnlockTile(tile) else city.tryLockTile(tile)
                screen.updateAsync()
            }).height(48f).padRight(8f)
        }
        if (city.canBuyTile(tile)) {
            val cost = city.getGoldCostOfTile(tile)
            actions.add(pill("Buy for [$cost] gold", YELLOW, YELLOW_INK,
                screen.canChangeState && city.viewingCiv().hasStatToBuy(Stat.Gold, cost)) { screen.askToBuyTile(tile) })
                .height(48f).padRight(8f)
        }
        actions.add(pill("Details") { tileDetailOpen = true; refresh() }).height(48f)
        card.add(actions).colspan(2).left().padTop(12f)
        content.add(card).padTop(4f).row()
    }

    private fun citizensTab() {
        val canChange = screen.canCityBeChanged()
        listRow(false, iconBox(ImageGetter.getConstructionPortrait("Worker", 32f)),
            "${city.getPopulationCount()} citizens",
            trailing = pill("Reset", enabled = canChange) {
                city.tryReassignPopulation(resetLocked = true)
                screen.updateAsync()
            }, details = {
                add(text("${city.getFreePopulation()} unassigned", INK2, 13)).minWidth(0f).growX().left().padTop(3f)
            }, onTap = null)
        rule()

        section("Citizen focus")
        val focusGrid = Table().left()
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
            val active = city.getCityFocus() == focus
            val chip = Table().apply {
                background = bg(if (active) Color.WHITE else CHIP)
                pad(0f, 6f, 0f, 6f)
                if (icon != null) add(screen.portraitStatIcons.image(icon.name)).size(20f).padRight(6f)
                add(Label(label.tr(hideIcons = true, hideStats = true), BaseScreen.skin).apply {
                    color = if (active) NAVY_INK else if (canChange) Color.WHITE else INK3
                    setFontScale(14f / Fonts.ORIGINAL_FONT_SIZE)
                    if (icon == null) setAlignment(Align.center)
                    wrap = true
                }).minWidth(0f).growX()
                if (canChange) {
                    touchable = Touchable.enabled
                    onClick { city.trySetCityFocus(focus); screen.updateAsync() }
                }
            }
            val cell = focusGrid.add(chip).minWidth(0f).width((contentWidth - 16f) / 3f).height(48f).padBottom(8f)
            if (count % 3 != 2) cell.padRight(8f)
            if (++count % 3 == 0) focusGrid.row()
        }
        content.add(focusGrid).left().row()

        listRow(false, iconBox(screen.portraitStatIcons.image("Food")), "Avoid growth",
            trailing = toggle(city.avoidGrowth, canChange) { city.tryToggleAvoidGrowth(); screen.updateAsync() },
            details = {
                add(text("Keep food from adding citizens", INK2, 13)).minWidth(0f).growX().left().padTop(3f)
            }, onTap = null)
        rule()

        val specialists = city.getMaxSpecialists().asSequence().sortedBy { it.key }
            .filter { city.getRuleset().specialists.containsKey(it.key) }.toList()
        if (specialists.isEmpty()) return
        section("Specialists")
        if (canChange) {
            val segment = Table().apply { background = bg(CHIP) }
            for ((label, manual) in listOf("Manual" to true, "Auto" to false)) {
                val on = city.manualSpecialists == manual
                segment.add(Table().apply {
                    if (on) background = bg(Color.WHITE)
                    add(label.toLabel(if (on) NAVY_INK else INK2, 14))
                    touchable = Touchable.enabled
                    onClick {
                        if (on) return@onClick
                        if (manual) city.tryEnableManualSpecialists() else city.tryDisableManualSpecialists()
                        screen.updateAsync()
                    }
                }).width(90f).height(40f).pad(4f)
            }
            content.add(segment).left().padBottom(6f).row()
        }
        for ((name, max) in specialists) {
            val assigned = city.getNewSpecialists()[name]
            val specialist = city.getRuleset().specialists[name]!!
            val stepper = Table()
            if (screen.canChangeState) {
                val canRemove = assigned > 0 && !city.isPuppet()
                val canAdd = assigned < max && !city.isPuppet() && city.getFreePopulation() > 0
                stepper.add(pill("−", enabled = canRemove) {
                    city.tryUnassignSpecialist(name)
                    screen.updateAsync()
                }).size(48f).padRight(6f)
                stepper.add(pill("+", enabled = canAdd) {
                    city.tryAssignSpecialist(name)
                    screen.updateAsync()
                }).size(48f)
            }
            listRow(false, iconBox(ImageGetter.getSpecialistIcon(specialist.colorObject)), name, trailing = stepper,
                details = {
                    val line = Table().left()
                    line.add("$assigned / $max".toLabel(INK2, 13)).padRight(8f)
                    line.add(statLine(city.getStatsOfSpecialist(name)))
                    add(line).left().padTop(3f)
                }, onTap = null)
            rule()
        }
    }

    private fun toggle(on: Boolean, enabled: Boolean, action: () -> Unit): Table = Table().apply {
        background = bg(if (on) YELLOW else CHIP)
        val knob = Image(ImageGetter.getCircleDrawable()).apply { color = if (on) YELLOW_INK else INK3 }
        // 56x32 track with the knob on the right when on
        add(knob).size(24f).pad(4f, if (on) 28f else 4f, 4f, if (on) 4f else 28f)
        if (enabled) {
            touchable = Touchable.enabled
            onClick { action() }
        }
    }

    private fun buyTab() {
        if (!screen.canChangeState) {
            section("Purchases are unavailable")
            return
        }
        section("Buy now")
        val data = constructions
        if (data == null) section(Constants.loading)
        else for (row in data.buyable) {
            val construction = row.construction
            val pills = Table()
            for (buyPill in buyPills(construction, -1, row.options)) pills.add(buyPill).height(48f).padLeft(6f)
            listRow(false, ImageGetter.getConstructionPortrait(construction.name, 46f), construction.name,
                trailing = pills, details = {
                    if (row.percentDone > 0) add(text("In progress, ${row.percentDone}%", INK2, 13)).left().padTop(3f).row()
                    if (row.blocked) add("Move unit out of city first".toLabel(RED, 13)).left().padTop(3f).row()
                }) { openDetails(construction, -1) }
            rule()
        }
        val tile = screen.nextTileToOwn
        if (tile != null) {
            section("Expand the city")
            listRow(false, iconBox(ImageGetter.getImage("OtherIcons/HexagonOutline")), "Buy a tile",
                trailing = "›".toLabel(INK3, 24), details = {
                    add(text("From [${city.getGoldCostOfTile(tile)}] gold", INK2, 13)).left().padTop(3f)
                }) {
                selectedTab = Tab.Tiles
                tileDetailOpen = false
                screen.selectTile(tile)
                scroll.scrollY = 0f
                refresh()
            }
            rule()
        }
    }
}
