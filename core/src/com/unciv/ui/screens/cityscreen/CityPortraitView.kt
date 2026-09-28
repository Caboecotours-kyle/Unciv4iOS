package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.city.CityFocus
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.IRulesetObject
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RejectionReason
import com.unciv.models.ruleset.RejectionReasonType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.getTurnsToConstructionString
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.tilegroups.CityTileState
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.CityScreenConstructionMenu
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.TrayArt
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.popups.TraySheet.ButtonKind
import com.unciv.ui.popups.TraySheet.RowState
import com.unciv.ui.popups.TraySheet.Tone
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.popups.tinted
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.pickerscreens.CityRenamePopup
import com.unciv.view.TileView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The five city jobs in the portrait tray sheet (direction B of mocks/drawers-proposal.html): flush with the screen
 * bottom, handle and 72pt header, city yields as stat chips, inset cards, the tab bar as the raised footer. It hosts
 * itself rather than using [TraySheet] as a host because it snaps to a 64pt peek instead of closing; the kit supplies
 * its textures, colours and row builders. The existing city widgets remain available as drill-ins.
 */
internal class CityPortraitView(private val screen: CityScreen) : Table() {
    enum class Tab(val label: String, val icon: String) {
        Build("Build", "OtherIcons/Settings"), Buildings("Buildings", "OtherIcons/Cities"),
        Tiles("Tiles", "OtherIcons/HexagonOutline"), Citizens("Citizens", "UnitIcons/Worker"),
        Buy("Buy", "OtherIcons/Resources")
    }

    companion object {
        var selectedTab = Tab.Build
        private val POPULATION = Color.valueOf("2f85c8")
        private val TAB_ON = Color.valueOf("2b4761")
        private val SLOT_LINE = Color.valueOf("34506b")
        private val PROD = Color.valueOf("ff9a3c")
        private val WONDER = Color.valueOf("ffd35c")
        private val FAITH = Color.valueOf("f3ecd2")
        private val FAITH_INK = Color.valueOf("4a3f1c")
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
                         val scrollY: Float, val listScrollY: Float, val constructions: Constructions?, val collapsed: Boolean,
                         val peekScrollY: Float, val peekTab: Tab)

    private val city = screen.cityView
    private val art = TrayArt(screen.stage)
    /** The kit's builders (rows, cards, chips, buttons) at this screen's scale; never shown itself */
    private val kit = TraySheet(screen, art)
    private fun pt(value: Float) = kit.pt(value)
    private fun font(size: Float) = kit.font(size)
    private val touch = kit.touch

    private val top = Table()
    private val header = Table()
    private val headerCell: Cell<Table>
    private val toolbar = Table()
    private val content = Table().top().left()
    private val scroll = AutoScrollPane(content).apply {
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
    }
    private val paneCell: Cell<AutoScrollPane>
    private val endCell: Cell<Actor>
    private val footerRow = Table()
    /** The raised footer layer: tab bar, or the tile page's actions; it runs under the home indicator */
    private val footer = object : Table() {
        override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
            if (art.released) return
            // The mock's 0 -6px 18px shadow, cast up onto the tray
            batch.setColor(1f, 1f, 1f, .35f * color.a * parentAlpha)
            batch.draw(art.fade, x, y + height, width, pt(14f))
            super.drawBackground(batch, parentAlpha, x, y)
        }
    }
    private val closeButton: Table
    private val constructionDetails = ConstructionInfoTable(screen).apply { portraitHeader = false }
    private val tileDetails = CityScreenTileTable(screen, kit)
    private val buyButtons = BuyButtonFactory(screen)

    private var detailOpen = false
    private var cityDetailsOpen = false
    private var collapsed = false
    /** Where the sheet rests expanded: its bottom on the canvas bottom */
    private var expandedY = 0f
    /** The home indicator strip under the safe area, filled by the sheet */
    private var inset = 0f
    /** Width the body gives its cards */
    private var bodyWidth = 0f
    private var peekScrollY = 0f
    private var peekTab = selectedTab
    /** The list's scroll when a page (tile, construction, city details) was pushed, restored by Back */
    private var listScrollY = 0f
    /** The list row whose actions are expanded; keys carry the construction name so a changed queue never matches */
    private var selectedRow: String? = null
    /** Latest background gather; null until the first one lands */
    internal var constructions: Constructions? = null

    /** Handle (8pt + 5pt) and the collapsed header row: 64pt on a phone, taller only to keep a 48 real point target */
    private val peekHeight get() = pt(13f) + max(pt(51f), touch)

    init {
        name = "portrait-city-sheet"
        touchable = Touchable.enabled
        background = art.rounded(pt(28f), topOnly = true).tinted(TrayColors.SHEET)
        top.touchable = Touchable.enabled
        top.add(Image(art.rounded(pt(2.5f)).tinted(TrayColors.HANDLE))).size(pt(40f), pt(5f)).padTop(pt(8f)).row()
        headerCell = top.add(header).growX().minHeight(pt(72f))
        top.addListener(SwipeCitySheet())
        add(top).growX().row()
        add(toolbar).growX().row()
        paneCell = add(scroll).grow().prefHeight(0f).padLeft(pt(12f)).padRight(pt(12f))
        row()
        @Suppress("UNCHECKED_CAST")
        endCell = add().growX() as Cell<Actor>
        footer.touchable = Touchable.enabled
        footer.background = art.rounded(pt(22f), topOnly = true).tinted(TrayColors.FOOTER)
        footer.add(footerRow).growX()
        // The kit's close: X and the BACK key leave the city (the hidden landscape Exit button is inactive in portrait)
        closeButton = roundButton("OtherIcons/Close", "portrait-sheet-close") { screen.exit() }
            .apply { keyShortcuts.add(KeyCharAndCode.BACK) }
    }

    /** Pins the sheet to the canvas bottom: its fill runs behind the home indicator, its top leaves the map 250pt */
    fun fit() {
        val safe = screen.safeAreaBoundsInWorld()
        val canvas = screen.portraitCanvasBounds()
        inset = (safe.y - canvas.y).coerceAtLeast(0f)
        val left = (safe.x - canvas.x).coerceAtLeast(0f)
        val right = (canvas.x + canvas.width - safe.x - safe.width).coerceAtLeast(0f)
        padLeft(left).padRight(right)
        bodyWidth = canvas.width - left - right - pt(24f)
        footerRow.pad(pt(12f), pt(16f), max(pt(12f), inset), pt(16f))
        val top = max(canvas.y + canvas.height - pt(250f), safe.y + pt(390f))
        expandedY = canvas.y
        setBounds(canvas.x, canvas.y, canvas.width, top - canvas.y)
        if (collapsed) y = collapsedY()
    }

    /** Screen disposal clears the stage without closing anything; leaving the stage frees the sheet's textures */
    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null) art.release()
    }

    private val reduceMotion get() = GUI.getSettings().reduceMotion

    /** A header swipe reveals the map; the same sheet, tab and selection remain ready below it. */
    private inner class SwipeCitySheet : InputListener() {
        private var startX = 0f
        private var startY = 0f
        private var restY = 0f
        private var tracking = false
        private var dragging = false
        private var lastY = 0f
        private var lastTime = 0L
        private var velocity = 0f

        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            if (tracking) { snapBack(); return false }
            if (pointer != 0 || button != 0 || event.target.isDescendantOf(closeButton)) return false
            clearActions()
            startX = event.stageX
            startY = event.stageY
            restY = this@CityPortraitView.y
            lastY = startY
            lastTime = System.nanoTime()
            velocity = 0f
            tracking = true
            dragging = false
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            if (!tracking || pointer != 0) return
            val dx = event.stageX - startX
            val travel = if (collapsed) event.stageY - startY else startY - event.stageY
            if (!dragging) {
                val slop = max(pt(10f), touch / 5f)
                if (abs(dx) > slop && abs(dx) >= travel) { snapBack(); return }
                if (travel <= slop || travel < abs(dx)) return
                dragging = true
                // The drag owns this touch now: no title, back or × activation on release
                event.stage.cancelTouchFocusExcept(this, top)
            }
            val now = System.nanoTime()
            val dt = (now - lastTime) / 1e9f
            if (dt > 0f) velocity = velocity * .4f + (if (collapsed) event.stageY - lastY else lastY - event.stageY) / dt * .6f
            lastY = event.stageY
            lastTime = now
            this@CityPortraitView.y = if (collapsed) (restY + travel.coerceAtLeast(0f)).coerceAtMost(expandedY)
                else restY - travel.coerceAtLeast(0f)
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            if (!tracking || pointer != 0) return
            tracking = false
            if (event.isTouchFocusCancel) { snapBack(); return }
            val travel = if (collapsed) event.stageY - startY else startY - event.stageY
            // Past 72pt or faster than 800pt/s moves to the other snap point; a tap on the peek expands it
            val far = dragging && (travel >= pt(72f) || velocity > pt(800f))
            val tap = !dragging
            dragging = false
            if (collapsed && (tap || far)) expand()
            else if (!collapsed && far) collapse()
            else snapBack()
        }

        private fun snapBack() {
            tracking = false
            dragging = false
            moveTo(if (collapsed) collapsedY() else expandedY, .12f)
        }
    }

    private fun moveTo(targetY: Float, duration: Float) {
        clearActions()
        if (y == targetY) return
        if (reduceMotion) y = targetY
        else addAction(Actions.moveTo(x, targetY, duration, Interpolation.fastSlow))
    }

    /** Only the handle and the compact header stay above the safe area */
    private fun collapsedY() = expandedY - (height - inset - peekHeight)

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
        moveTo(collapsedY(), .15f)
    }

    private fun expand() {
        collapsed = false
        refreshForPeek()
        if (selectedTab == peekTab) {
            scroll.scrollY = peekScrollY
            scroll.updateVisualScroll()
        }
        screen.setMapPeekCollapsed(false)
        moveTo(expandedY, .15f)
    }

    fun showTile(tile: TileView) {
        if (tilePage() == null) listScrollY = scroll.scrollY
        selectedTab = Tab.Tiles
        detailOpen = false
        cityDetailsOpen = false
        screen.selectTile(tile)
        scroll.scrollY = 0f
        refresh()
    }

    fun state() = State(selectedRow, screen.selectedQueueEntry, detailOpen, cityDetailsOpen,
        scroll.scrollY, listScrollY, constructions, collapsed, peekScrollY, peekTab)

    /** Resize re-creates the screen with the same selection; this puts the rest of the sheet back */
    fun restore(state: State) {
        selectedRow = state.row
        screen.selectedQueueEntry = state.queueEntry
        detailOpen = state.detailOpen
        cityDetailsOpen = state.cityDetailsOpen
        listScrollY = state.listScrollY
        if (constructions == null) constructions = state.constructions
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

    /** The tile page shows while the Tiles tab has a selected tile: tapping one on the map or in the list pushes it */
    private fun tilePage(): TileView? =
        if (selectedTab == Tab.Tiles && !detailOpen) screen.selectedTile else null

    /** Back from a pushed page: the list where the sheet left it */
    private fun back(close: () -> Unit) {
        close()
        refresh()
        validate()
        scroll.scrollY = listScrollY
        scroll.updateVisualScroll()
    }

    fun refresh() {
        val oldScroll = scroll.scrollY
        val tile = tilePage()
        val construction = if (detailOpen) screen.selectedConstruction else null
        toolbar.isVisible = !collapsed
        scroll.isVisible = !collapsed
        footer.isVisible = !collapsed
        drawHeader(tile, construction)
        toolbar.clearChildren()
        if (tile == null) toolbar.add(yieldChips()).growX().pad(0f, pt(12f), pt(4f), pt(12f))
        content.clear()
        content.defaults().width(bodyWidth)
        footerRow.clearChildren()
        when {
            construction != null -> constructionDetail(construction)
            tile != null -> tileDetail(tile)
            else -> when (selectedTab) {
                Tab.Build -> buildTab()
                Tab.Buildings -> buildingsTab()
                Tab.Tiles -> tilesTab()
                Tab.Citizens -> citizensTab()
                Tab.Buy -> buyTab()
            }
        }
        content.add().height(pt(12f)).row()
        if (tile == null) footerRow.add(tabBar()).growX()
        if (footerRow.cells.isEmpty) endCell.setActor<Actor>(null).padTop(0f).height(max(pt(24f), inset + pt(8f)))
        else endCell.setActor<Actor>(footer).padTop(pt(4f)).height(Value.prefHeight)
        paneCell.padBottom(if (footerRow.cells.isEmpty) 0f else pt(12f))
        invalidate()
        validate()
        scroll.layout()
        scroll.scrollY = if (construction != null || tile != null) min(oldScroll, scroll.maxY) else oldScroll
        scroll.updateVisualScroll()
    }

    // ---------------- header, yields, tabs ----------------

    /** 44pt round button in a [touch] square target, the kit's header style */
    private fun roundButton(icon: String, name: String, rotation: Float = 0f, action: () -> Unit): Table = Table().apply {
        this.name = name
        touchable = Touchable.enabled
        add(Table().apply {
            background = ImageGetter.getCircleDrawable().tint(TrayColors.CARD)
            val image = ImageGetter.getImage(icon).apply { color = TrayColors.CLOSE_INK }
            image.setSize(pt(18f), pt(18f))
            image.setOrigin(Align.center)
            image.rotation = rotation
            add(image).size(pt(18f))
        }).size(pt(44f))
        onActivation { action() }
    }

    private fun headerTitle(title: String, subtitle: String?, size: Float = 23f): Table = Table().left().apply {
        add(title.toLabel(TrayColors.INK, font(size), hideIcons = true).apply { setEllipsis(true) }).minWidth(0f).growX().left().row()
        if (subtitle != null)
            add(subtitle.toLabel(TrayColors.INK2, font(14.5f), hideIcons = true).apply { setEllipsis(true) })
                .minWidth(0f).growX().left().padTop(pt(3f))
    }

    private fun populationDisc(size: Float): Table = Table().apply {
        background = ImageGetter.getCircleDrawable().tint(POPULATION)
        add(city.getPopulationCount().toString().toLabel(TrayColors.INK, font(size * 23f / 52f)))
    }

    private fun drawHeader(tile: TileView?, construction: IConstruction?) {
        header.clearChildren()
        val target = max(pt(48f), touch)
        if (collapsed) {
            headerCell.minHeight(max(pt(51f), touch))
            header.add(populationDisc(pt(40f))).size(pt(40f)).padRight(pt(12f))
            header.add(headerTitle(city.name, null, 20f)).minWidth(0f).growX()
            // Tapping anywhere on the peek expands it; the chevron says so
            header.add(Table().apply {
                add(ImageGetter.getImage("OtherIcons/ArrowRight").apply {
                    color = TrayColors.INK2
                    setSize(pt(18f), pt(18f))
                    setOrigin(Align.center)
                    rotation = 90f
                }).size(pt(18f))
            }).size(pt(44f)).padLeft(pt(8f))
            header.add(closeButton).size(target).padLeft(pt(8f))
            header.pad(0f, pt(26f), 0f, pt(16f))
            return
        }
        headerCell.minHeight(pt(72f))
        val onBack: (() -> Unit)? = when {
            construction != null -> ({ back { detailOpen = false } })
            tile != null -> ({ back { screen.clearSelection() } })
            selectedTab == Tab.Buildings && cityDetailsOpen -> ({ back { cityDetailsOpen = false } })
            else -> null
        }
        if (onBack != null) header.add(roundButton("OtherIcons/BackArrow", "portrait-sheet-back", action = onBack)).size(target).padRight(pt(4f))
        when {
            construction != null -> {
                // The 52pt portrait opens its Civilopedia entry; its target grows to [target] on a narrow phone
                val lead = Table().apply { add(ImageGetter.getConstructionPortrait(construction.name, pt(52f))).size(pt(52f)) }
                val link = (construction as? IRulesetObject)?.makeLink()
                if (!link.isNullOrEmpty()) {
                    lead.name = "portrait-construction-link"
                    lead.touchable = Touchable.enabled
                    lead.onClick { screen.openCivilopedia(link) }
                }
                val leadSize = max(pt(52f), target)
                header.add(lead).size(leadSize).padRight((pt(12f) - (leadSize - pt(52f)) / 2f).coerceAtLeast(0f))
                val special = PerpetualConstruction.perpetualConstructionsMap[construction.name]
                val subtitle = (special?.let { city.getProductionTooltip(it) } ?: city.constructions.getTurnsToConstructionString(construction))
                    .trim().replace("\n", "   ")
                header.add(headerTitle(construction.name, subtitle.ifEmpty { null })).minWidth(0f).growX()
            }
            tile != null -> header.add(headerTitle(tileName(tile), tileStatus(tile))).minWidth(0f).growX()
            onBack != null -> header.add(headerTitle("City details", city.name)).minWidth(0f).growX()
            else -> {
                header.add(populationDisc(pt(52f))).size(pt(52f)).padRight(pt(12f))
                val growth = when {
                    city.isStarving() -> "Loses population in ${city.getNumTurnsToStarvation()} turns"
                    city.isGrowing() -> "Grows in ${city.getNumTurnsToNewPopulation()} turns"
                    else -> "Growth stopped"
                }
                val title = headerTitle(city.name + if (city.isCapital()) " ★" else "", growth)
                title.touchable = Touchable.enabled
                title.onClick { screen.page(1) }
                header.add(title).minWidth(0f).growX().minHeight(target)
            }
        }
        header.add(closeButton).size(target).padLeft(pt(8f))
        header.pad(pt(6f), pt(if (onBack != null) 12f else 26f), pt(8f), pt(16f))
    }

    /** The mock's strip of six city yields: icon over value on the card colour */
    private fun yieldChips(): Table {
        val stats = city.getCurrentCityStats()
        val chips = listOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith).map { stat ->
            Table().apply {
                background = art.rounded(pt(16f)).tinted(TrayColors.CARD)
                add().minHeight(pt(58f)).width(0f)
                add(Table().apply {
                    add(screen.portraitStatIcons.image(stat.name)).size(pt(21f)).row()
                    add(stats[stat].roundToInt().toString().toLabel(TrayColors.INK, font(16f))).padTop(pt(2f))
                })
            }
        }
        return kit.statGrid(chips, chips.size)
    }

    private fun tabBar(): Table = Table().apply {
        for ((index, tab) in Tab.entries.withIndex()) {
            val active = tab == selectedTab
            val button = Table().apply {
                name = "portrait-city-tab-${tab.name}"
                touchable = Touchable.enabled
                if (active) background = art.rounded(pt(16f)).tinted(TAB_ON)
                add(ImageGetter.getImage(tab.icon).apply { color = if (active) TrayColors.INK else TrayColors.INK2 })
                    .size(pt(24f)).padTop(pt(7f)).row()
                add(tab.label.toLabel(if (active) TrayColors.INK else TrayColors.INK2, font(12f))).padTop(pt(3f)).padBottom(pt(6f))
                onClick {
                    if (selectedTab == tab && !detailOpen && !cityDetailsOpen && tilePage() == null) return@onClick
                    if (tilePage() != null) screen.clearSelection()
                    selectedTab = tab
                    detailOpen = false
                    cityDetailsOpen = false
                    selectedRow = null
                    scroll.scrollY = 0f
                    refresh()
                }
            }
            add(button).growX().uniformX().minWidth(pt(48f)).minHeight(max(pt(56f), touch)).padLeft(if (index > 0) pt(4f) else 0f)
        }
    }

    // ---------------- building blocks ----------------

    private fun section(text: String, extra: String? = null, gap: Float = 0f) =
        content.add(kit.section(text, extra)).padTop(pt(gap)).row()

    private fun hint(text: String) = content.add(text.toLabel(TrayColors.INK2, font(14f), hideIcons = true).apply { wrap = true })
        .width(bodyWidth - pt(32f)).padTop(pt(6f)).padLeft(pt(16f)).padRight(pt(16f)).row()

    private fun card(rows: List<Actor>, gap: Float = 0f) {
        if (rows.isNotEmpty()) content.add(kit.card(rows)).padTop(pt(gap)).row()
    }

    private fun text(text: String, color: Color = TrayColors.INK2, size: Float = 13.5f) =
        text.toLabel(color, font(size), hideIcons = true).apply { setEllipsis(true) }

    private fun amount(value: Float) = if (value == value.toInt().toFloat()) value.toInt().tr() else "%.1f".format(value)

    /** Small yield icons with values, the mock's mini stats */
    private fun statLine(stats: Stats, color: Color = TrayColors.INK): Table = Table().left().apply {
        for ((stat, value) in stats) {
            add(screen.portraitStatIcons.image(stat.name)).size(pt(16f)).padRight(pt(3f))
            add(amount(value).toLabel(color, font(13.5f))).padRight(pt(8f))
        }
    }

    private fun tag(text: String, color: Color): Table = Table().apply {
        // The radius stays under half the tag's height so the nine-patch never overlaps itself
        background = art.rounded(pt(7f)).tinted(color.cpy().apply { a = .16f })
        add(text.toLabel(color, font(12f), hideIcons = true)).pad(pt(3f), pt(8f), pt(3f), pt(8f))
    }

    private enum class Look { Primary, Faith, Quiet, Danger }

    /** Compact action inside a card: [touch] tall, never under 48 real points */
    private fun action(text: String, look: Look = Look.Quiet, enabled: Boolean = true, action: () -> Unit): Table = Table().apply {
        val (fill, ink) = when (look) {
            Look.Primary -> TrayColors.YELLOW to TrayColors.YELLOW_INK
            Look.Faith -> FAITH to FAITH_INK
            Look.Quiet -> TrayColors.FILL to TrayColors.INK
            Look.Danger -> TrayColors.DANGER_BG to TrayColors.DANGER_INK
        }
        background = art.rounded(pt(14f)).tinted(if (enabled) fill else TrayColors.FILL)
        add().minHeight(touch).width(0f)
        add(text.toLabel(if (enabled) ink else TrayColors.INK2, font(15f), hideIcons = true)).pad(0f, pt(14f), 0f, pt(14f))
        if (enabled) {
            touchable = Touchable.enabled
            onClickSuppressive { action() }
        } else color.a = .6f
    }

    /** The kit's 56pt button, greyed out and inert when not [enabled] */
    private fun button(text: String, kind: ButtonKind, enabled: Boolean = true, action: () -> Unit): Table =
        kit.button(text, kind) { action() }.apply {
            if (!enabled) {
                touchable = Touchable.disabled
                color.a = .45f
            }
        }

    /**
     * The kit's 64pt list row ([TraySheet.row]) with an actor for its second line, so stat icons, tags and
     * rejection reasons keep their icons: 44pt lead, 17pt title, trailing value and actor, check when selected.
     */
    private fun listRow(title: String, lead: Actor?, meta: Actor? = null, value: String? = null, valueColor: Color = TrayColors.INK2,
                        trailing: Actor? = null, state: RowState = RowState.Normal, chevron: Boolean? = null,
                        onTap: (() -> Unit)?): Table {
        val row = Table()
        row.add().minHeight(kit.rowHeight).width(0f)
        if (lead != null) {
            if (state == RowState.Disabled) lead.color.a = .5f
            row.add(lead).size(pt(44f)).padRight(pt(14f))
        }
        val info = Table().left()
        val titleColor = when (state) {
            RowState.Disabled -> TrayColors.INK2
            RowState.Danger -> TrayColors.DANGER_INK
            else -> TrayColors.INK
        }
        info.add(title.toLabel(titleColor, font(17f), hideIcons = true).apply { wrap = true }).growX().left().row()
        if (meta != null) info.add(meta).growX().left().padTop(pt(3f))
        row.add(info).growX().minWidth(0f).padTop(pt(9f)).padBottom(pt(9f))
        if (value != null) row.add(value.toLabel(valueColor, font(14.5f), hideIcons = true)).padLeft(pt(8f))
        if (trailing != null) row.add(trailing).padLeft(pt(8f))
        if (state == RowState.Selected) row.add(Table().apply {
            background = ImageGetter.getCircleDrawable().tint(TrayColors.YELLOW)
            add(ImageGetter.getImage("OtherIcons/Checkmark").apply { color = TrayColors.YELLOW_INK }).size(pt(14f))
        }).size(pt(26f)).padLeft(pt(8f))
        val showChevron = chevron ?: (onTap != null && state == RowState.Normal)
        if (showChevron) row.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = TrayColors.CHEVRON })
            .size(pt(14f)).padLeft(pt(8f))
        row.pad(0f, pt(14f), 0f, pt(14f))
        if (onTap != null) {
            row.touchable = Touchable.enabled
            row.onClick { onTap() }
        }
        return row
    }

    /** A selected row with its actions under it, wrapped without shrinking their touch targets, on the selection tint */
    private fun withActions(row: Table, vararg lines: List<Actor>): Table {
        val block = Table()
        block.background = art.rounded(pt(18f)).tinted(TrayColors.SELECTED)
        block.add(row).growX().row()
        val available = bodyWidth - pt(72f) - pt(14f)
        val strip = Table().left()
        for (actors in lines.filter { it.isNotEmpty() }) {
            var line = Table().left()
            var used = 0f
            for (actor in actors) {
                val preferred = (actor as? Layout)?.prefWidth ?: actor.width
                val width = preferred.coerceAtMost(available)
                if (used > 0f && used + pt(8f) + width > available) {
                    strip.add(line).left().padBottom(pt(8f)).row()
                    line = Table().left()
                    used = 0f
                }
                if (actor is Label && preferred > available) actor.wrap = true
                val cell = line.add(actor)
                if (used > 0f) cell.padLeft(pt(8f))
                if (preferred > available) cell.width(available)
                used += width + if (used == 0f) 0f else pt(8f)
            }
            strip.add(line).left().padBottom(pt(8f)).row()
        }
        if (strip.cells.notEmpty()) block.add(strip).left().padLeft(pt(72f)).padRight(pt(14f)).padBottom(pt(4f))
        return block
    }

    /** The empty production slot of the mock: a dashed outline and ring, not a heading */
    private fun slot(title: String, subtitle: String): Stack {
        val bar = { w: Float, h: Float -> Table().apply { add(Image(ImageGetter.getWhiteDotDrawable().tint(TrayColors.INK2))).size(w, h) } }
        val ring = Stack(Dashed(pt(20f)), bar(pt(14f), pt(2.6f)), bar(pt(2.6f), pt(14f)))
        val row = Table()
        row.add().minHeight(pt(64f)).width(0f)
        row.add(ring).size(pt(40f)).padRight(pt(14f))
        row.add(Table().left().apply {
            add(title.toLabel(TrayColors.INK2, font(16f), hideIcons = true).apply { wrap = true }).growX().left().row()
            add(subtitle.toLabel(TrayColors.INK2, font(13.5f), hideIcons = true).apply { wrap = true }).growX().left().padTop(pt(2f))
        }).growX().minWidth(0f)
        row.pad(pt(8f), pt(14f), pt(8f), pt(14f))
        return Stack(Dashed(pt(18f)), row).apply { name = "portrait-empty-slot" }
    }

    /** A 2pt dashed outline with rounded corners of [radius] around its bounds (the mock's --slotline) */
    private inner class Dashed(private val radius: Float) : Widget() {
        private val region = ImageGetter.getWhiteDotDrawable().region
        init { touchable = Touchable.disabled }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val thick = pt(2f)
            val left = x + thick / 2f
            val bottom = y + thick / 2f
            val right = x + width - thick / 2f
            val top = y + height - thick / 2f
            val r = min(radius - thick / 2f, min(right - left, top - bottom) / 2f).coerceAtLeast(0f)
            val straightX = right - left - 2 * r
            val straightY = top - bottom - 2 * r
            val arc = PI.toFloat() / 2f * r
            // Clockwise from the left end of the top edge: top, corner, right, corner, bottom, corner, left, corner
            val segments = floatArrayOf(straightX, arc, straightY, arc, straightX, arc, straightY, arc)
            val length = segments.sum()
            if (length <= 0f) return
            val count = max(4, (length / pt(11f)).roundToInt())
            val step = length / count
            val dash = step * .55f
            batch.setColor(SLOT_LINE.r, SLOT_LINE.g, SLOT_LINE.b, SLOT_LINE.a * color.a * parentAlpha)
            for (i in 0 until count) {
                var s = i * step + step / 2f
                var index = 0
                while (index < segments.lastIndex && s > segments[index]) { s -= segments[index]; index++ }
                var px: Float; var py: Float; var angle: Float
                when (index) {
                    0 -> { px = left + r + s; py = top; angle = 0f }
                    2 -> { px = right; py = top - r - s; angle = -90f }
                    4 -> { px = right - r - s; py = bottom; angle = 180f }
                    6 -> { px = left; py = bottom + r + s; angle = 90f }
                    else -> {
                        val (cx, cy, start) = when (index) {
                            1 -> Triple(right - r, top - r, 90f)
                            3 -> Triple(right - r, bottom + r, 0f)
                            5 -> Triple(left + r, bottom + r, -90f)
                            else -> Triple(left + r, top - r, 180f)
                        }
                        val degrees = start - s / r * 180f / PI.toFloat()
                        val a = degrees * PI.toFloat() / 180f
                        px = cx + cos(a) * r; py = cy + sin(a) * r; angle = degrees - 90f
                    }
                }
                batch.draw(region, px - dash / 2f, py - thick / 2f, dash / 2f, thick / 2f, dash, thick, 1f, 1f, angle)
            }
        }
    }

    /** Rounded progress bar on the tray's track colour */
    private inner class Bar(private val progress: Float) : Widget() {
        private val track = art.rounded(pt(4f)).tinted(TrayColors.TRACK)
        private val fill = art.rounded(pt(4f)).tinted(PROD)
        override fun getPrefHeight() = pt(8f)
        override fun draw(batch: Batch, parentAlpha: Float) {
            if (art.released) return
            batch.setColor(1f, 1f, 1f, color.a * parentAlpha)
            track.draw(batch, x, y, width, height)
            if (progress > 0f) fill.draw(batch, x, y, max(height, width * progress), height)
        }
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
        if (!detailOpen && !cityDetailsOpen && tilePage() == null) listScrollY = scroll.scrollY
        screen.selectConstruction(construction)
        screen.selectedQueueEntry = queueIndex
        detailOpen = true
        scroll.scrollY = 0f
        refresh()
    }

    private fun turnsText(construction: IConstruction): String {
        if (construction is PerpetualConstruction) return city.getProductionTooltip(construction).trim()
        val constructions = city.constructions
        val turns = constructions.turnsToConstruction(construction.name,
            construction is Building || !constructions.isBeingConstructedOrEnqueued(construction.name))
        return if (turns == Int.MAX_VALUE) "∞" else "$turns turns"
    }

    private fun turnsValue(row: ConstructionRow) = if (row.construction is PerpetualConstruction) null else row.turns

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

    /** One action per buy option, with the landscape confirm path */
    private fun buyActions(construction: IConstruction, queueIndex: Int,
                           options: List<BuyOption> = buyOptions(construction)): List<Actor> {
        if (construction !is INonPerpetualConstruction) return emptyList()
        return options.map { option ->
            action("${option.cost.tr()}${option.stat.character}", if (option.stat == Stat.Faith) Look.Faith else Look.Primary,
                option.allowed) { buy(construction, option.stat, queueIndex) }
                .apply { name = "portrait-buy-${option.stat.name}" }
        }
    }

    private fun blockedHint(construction: IConstruction): List<Actor> =
        if (construction is INonPerpetualConstruction && canBuyHere()
            && city.constructions.isConstructionPurchaseBlockedByUnit(construction))
            listOf("Move unit out of city first".toLabel(TrayColors.REASON, font(13.5f)))
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

    // ---------------- Build ----------------

    private fun buildTab() {
        if (screen.isSpying) {
            content.add(kit.emptyState("OtherIcons/Spy_White", "Construction is hidden while spying")).row()
            return
        }
        val constructions = city.constructions
        val queue = constructions.constructionQueue
        val current = queue.firstOrNull()?.let { constructions.getConstruction(it) }
        if (current != null) currentCard(current)
        else content.add(slot("Pick a construction", "Nothing in production")).padTop(pt(8f)).row()
        if (queue.size > 1) {
            section("Up next")
            card(queue.mapIndexedNotNull { index, name -> if (index > 0) queueRow(index, constructions.getConstruction(name)) else null })
        }
        availableConstructions()
    }

    private fun currentCard(construction: IConstruction) {
        val key = "q-0-${construction.name}"
        val selected = selectedRow == key
        val goldBuy = buyActions(construction, 0, buyOptions(construction, listOf(Stat.Gold))).firstOrNull()
        val row = listRow(construction.name, ImageGetter.getConstructionPortrait(construction.name, pt(44f)),
            meta = text(turnsText(construction)), trailing = goldBuy,
            state = if (selected) RowState.Selected else RowState.Normal, chevron = false, onTap = null)
        val block = Table()
        block.add(row).growX().row()
        if (construction is INonPerpetualConstruction) {
            val cost = city.getConstructionProductionCost(construction).coerceAtLeast(1)
            val done = city.constructions.getWorkDone(construction.name)
            val progress = Table()
            progress.add(Bar((done.toFloat() / cost).coerceIn(0f, 1f))).growX().height(pt(8f)).row()
            progress.add("$done/$cost${Fonts.production}".toLabel(TrayColors.INK2, font(12.5f))).left().padTop(pt(4f))
            block.add(progress).growX().pad(0f, pt(72f), pt(12f), pt(14f))
        }
        block.name = "portrait-current-construction"
        block.touchable = Touchable.enabled
        block.onClick { toggleRow(key) { screen.selectConstruction(construction); screen.selectedQueueEntry = 0 } }
        constructionMenu(block, construction)
        val entry = if (!selected) block else {
            val otherBuys = buyActions(construction, 0, buyOptions(construction, Stat.statsUsableToBuy - Stat.Gold))
            withActions(block, queueControls(0, construction), otherBuys + detailsAction(construction, 0), blockedHint(construction))
        }
        card(listOf(entry), 8f)
    }

    private fun queueRow(index: Int, construction: IConstruction): Actor {
        val key = "q-$index-${construction.name}"
        val facts = constructionRow(construction, null)
        val selected = selectedRow == key
        val row = listRow(construction.name, ImageGetter.getConstructionPortrait(construction.name, pt(44f)),
            meta = constructionFacts(facts), value = turnsValue(facts),
            state = if (selected) RowState.Selected else RowState.Normal) {
            toggleRow(key) { screen.selectConstruction(construction); screen.selectedQueueEntry = index }
        }
        constructionMenu(row, construction)
        if (!selected) return row
        return withActions(row, queueControls(index, construction),
            buyActions(construction, index) + detailsAction(construction, index), blockedHint(construction))
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
        if (index > 0) controls += action("Move up") { move(city::tryRaisePriority) }
        if (index < queue.lastIndex) controls += action("Move down") { move(city::tryLowerPriority) }
        controls += action("Remove", Look.Danger) {
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

    private fun detailsAction(construction: IConstruction, queueIndex: Int): Actor =
        action("Details") { openDetails(construction, queueIndex) }

    /** Stat line, wonder tag, resource needs and the landscape's most important rejection */
    private fun constructionFacts(row: ConstructionRow): Table {
        val facts = Table().left()
        val construction = row.construction
        val rejection = row.rejection
        if (construction is PerpetualConstruction) {
            facts.add(text(row.turns)).minWidth(0f).growX().left()
            return facts
        }
        val line = Table().left()
        if (row.stats != null) line.add(statLine(row.stats))
        if (construction is BaseUnit) {
            val stats = Table().left()
            for ((icon, value) in listOf("Strength" to construction.strength,
                "Ranged" to construction.rangedStrength, "Movement" to construction.movement)) {
                if (value == 0) continue
                stats.add(screen.portraitStatIcons.image(icon)).size(pt(16f)).padRight(pt(3f))
                stats.add(value.tr().toLabel(TrayColors.INK, font(13.5f))).padRight(pt(8f))
            }
            line.add(stats)
        }
        if (construction is Building && construction.isWonder) line.add(tag("World wonder", WONDER)).padLeft(pt(4f))
        if (construction is Building && construction.isNationalWonder) line.add(tag("National wonder", WONDER)).padLeft(pt(4f))
        val resourceColor = if (rejection?.type == RejectionReasonType.ConsumesResources) TrayColors.REASON else TrayColors.INK2
        for ((resource, amount) in row.resources) {
            line.add(amount.tr().toLabel(resourceColor, font(13.5f))).padLeft(pt(6f))
            line.add(ImageGetter.getResourcePortrait(resource, pt(15f))).padLeft(pt(2f))
        }
        if (line.cells.notEmpty()) facts.add(line).left().row()
        if (rejection != null && rejection.type != RejectionReasonType.ConsumesResources)
            facts.add(ColorMarkupLabel(rejection.errorMessage, TrayColors.REASON, fontSize = font(13.5f)).apply { wrap = true })
                .minWidth(0f).growX().left().padTop(if (line.cells.notEmpty()) pt(2f) else 0f)
        return facts
    }

    /** The landscape "available constructions" list, from the latest background gather */
    private fun availableConstructions() {
        val data = constructions ?: return hint(Constants.loading)
        for ((category, rows) in data.categories) {
            if (rows.isEmpty()) continue
            section(category)
            card(rows.map { availableRow(it) })
        }
    }

    private fun availableRow(facts: ConstructionRow): Actor {
        val construction = facts.construction
        val key = "c-${construction.name}"
        val selected = selectedRow == key
        val state = when {
            selected -> RowState.Selected
            facts.rejection != null -> RowState.Disabled
            else -> RowState.Normal
        }
        val icon = ImageGetter.getConstructionPortrait(construction.name, pt(44f))
        // A rejected row stays dim while it is open
        if (facts.rejection != null) icon.color.a = .5f
        val row = listRow(construction.name, icon, meta = constructionFacts(facts), value = turnsValue(facts), state = state) {
            toggleRow(key) { screen.selectConstruction(construction); screen.selectedQueueEntry = -1 }
        }
        constructionMenu(row, construction)
        if (!selected) return row
        val addToQueue = if (canQueue(construction)) listOf(action("Add to queue", Look.Primary) {
            screen.queueConstruction(construction)
            selectedRow = null
            screen.updateAsync()
        }.apply { name = "portrait-add-to-queue" }) else emptyList()
        return withActions(row, addToQueue, buyActions(construction, -1) + detailsAction(construction, -1), blockedHint(construction))
    }

    /** CityConstructionsTable.cannotAddConstructionToQueue, which queueConstruction checks again */
    private fun canQueue(construction: IConstruction): Boolean {
        val constructions = city.constructions
        return !constructions.isQueueFull() && screen.canChangeState && !city.isPuppet()
            && constructions.isBuildable(construction)
            && !(construction is PerpetualConstruction && constructions.isBeingConstructedOrEnqueued(construction.name))
    }

    // ---------------- drill-ins ----------------

    /** A legacy drill-in widget laid out on the 393pt canvas, drawn at the sheet's scale (smaller when it is wider) */
    private inner class Fitted(private val actor: Table) : WidgetGroup() {
        init { addActor(actor) }
        private val fit get() = min(kit.p, bodyWidth / actor.prefWidth.coerceAtLeast(1f))
        override fun getPrefWidth() = actor.prefWidth * fit
        override fun getPrefHeight() = actor.prefHeight * fit
        override fun layout() {
            actor.isTransform = fit != 1f
            actor.setScale(fit)
            actor.setBounds((width - actor.prefWidth * fit) / 2f, 0f, actor.prefWidth, actor.prefHeight)
        }
    }

    private fun drillIn(actor: Table) = content.add(Fitted(actor)).padTop(pt(4f)).row()

    private fun constructionDetail(construction: IConstruction) {
        constructionDetails.update(construction)
        drillIn(constructionDetails)
    }

    // ---------------- Buildings ----------------

    private fun buildingsTab() {
        if (cityDetailsOpen) {
            val stats = CityStatsTable(screen) { building ->
                cityDetailsOpen = false
                openDetails(building, -1)
            }
            // Its own list scrolls inside; give it the sheet's visible height rather than a fixed box
            stats.update((scroll.height / kit.p - 72f).coerceAtLeast(340f))
            drillIn(stats)
            return
        }
        section("Built in ${city.name}")
        card(city.getBuiltBuildings().sortedBy { it.name }.toList().map { building ->
            val key = "b-${building.name}"
            val free = screen.hasFreeBuilding(building)
            val selected = selectedRow == key
            val meta = Table().left()
            meta.add(statLine(city.getBuildingStats(building)))
            if (building.isWonder) meta.add(tag("World wonder", WONDER)).padLeft(pt(4f))
            if (building.isNationalWonder) meta.add(tag("National wonder", WONDER)).padLeft(pt(4f))
            if (free) meta.add(tag("Free", TrayColors.INK2)).padLeft(pt(4f))
            val row = listRow(building.name, ImageGetter.getConstructionPortrait(building.name, pt(44f)), meta,
                state = if (selected) RowState.Selected else RowState.Normal) {
                toggleRow(key) { screen.selectConstruction(building); screen.selectedQueueEntry = -1 }
            }
            if (selected) withActions(row, sellAction(building, free) + detailsAction(building, -1)) else row
        })

        section("City")
        val rows = ArrayList<Actor>()
        rows += listRow("City details", kit.disc(ImageGetter.getImage("OtherIcons/Search"), Tone.Fill),
            text("Stats, religion, resources")) {
            listScrollY = scroll.scrollY
            cityDetailsOpen = true
            scroll.scrollY = 0f
            refresh()
        }.apply { name = "portrait-city-details" }
        if (screen.canChangeState)
            rows += listRow("Rename city", kit.disc(ImageGetter.getImage("OtherIcons/Pencil"), Tone.Fill)) {
                CityRenamePopup(screen, city) { screen.game.replaceCurrentScreen { CityScreen(screen.cityView) } }
            }.apply { name = "portrait-rename-city" }
        card(rows)
        val cities = screen.viewableCities
        if (cities.size > 1) {
            val index = cities.indexOfFirst { it == city }
            val previous = cities[(index - 1 + cities.size) % cities.size]
            val next = cities[(index + 1) % cities.size]
            val paging = Table()
            paging.add(button("‹  ${previous.name}", ButtonKind.Secondary) { screen.page(-1) }).growX().uniformX().padRight(pt(8f))
            paging.add(button("${next.name}  ›", ButtonKind.Secondary) { screen.page(1) }).growX().uniformX()
            content.add(paging).padTop(pt(12f)).row()
        }
        content.add(cityAction()).padTop(pt(12f)).row()
    }

    /** ConstructionInfoTable's sell button: same gates, same confirm popup, same command */
    private fun sellAction(building: Building, free: Boolean): List<Actor> {
        if (!building.isSellable()) return emptyList()
        val amount = city.getGoldForSellingBuilding(building.name)
        val sellText = "{Sell} $amount " + Fonts.gold
        val enabled = !free && !city.isPuppet() && screen.canChangeState &&
            (!city.hasSoldBuildingThisTurn() || city.isGodModeEnabled())
        return listOf(action(sellText, Look.Primary, enabled) {
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
            city.isPuppet() && canAnnex -> button("Annex city", ButtonKind.Primary, screen.canChangeState) {
                city.tryAnnexCity()
                screen.updateAsync()
            }
            !city.isBeingRazed() -> button("Raze city", ButtonKind.Destructive,
                screen.canChangeState && city.canBeDestroyed() && canAnnex) {
                city.trySetRazing(true)
                screen.updateAsync()
            }
            else -> button("Stop razing city", ButtonKind.Secondary, screen.canChangeState) {
                city.trySetRazing(false)
                screen.updateAsync()
            }
        }.apply { name = "portrait-city-action" }
    }

    // ---------------- Tiles ----------------

    private fun tileName(tile: TileView): String =
        (listOf(tile.baseTerrain) + tile.terrainFeatures + listOfNotNull(tile.getViewableResource(city.viewingCiv())?.name))
            .joinToString(", ") { it.tr(hideIcons = true) }

    private fun tileIcon(tile: TileView): Actor {
        val resource = tile.getViewableResource(city.viewingCiv())
        val improvement = tile.getShownImprovement()
        return when {
            tile.isCityCenter() -> kit.disc(ImageGetter.getImage("OtherIcons/Star").apply { color = Color.WHITE }, Tone.Yellow, recolor = false)
            resource != null -> ImageGetter.getResourcePortrait(resource.name, pt(44f))
            improvement != null -> ImageGetter.getImprovementPortrait(improvement, pt(44f))
            else -> kit.disc(ImageGetter.getImage("OtherIcons/Hexagon").apply { color = tile.getBaseTerrain().getColor() }, Tone.Fill, recolor = false)
        }
    }

    /** Worked, locked, city center, blockaded or unworked, as the map shows it */
    private fun tileStatus(tile: TileView, state: CityTileState = screen.tileStates()[tile] ?: CityTileState.NONE): String? = when {
        city.isWorked(tile) -> if (tile.isLocked()) "Locked" else "Worked"
        tile.isCityCenter() && city.isOwnedTile(tile) -> "City center"
        state == CityTileState.BLOCKADED -> "Blockaded"
        !screen.isSpying && city.isOwnedTile(tile) && state == CityTileState.WORKABLE -> "Unworked"
        else -> tile.getOwner()?.civName ?: "Unowned"
    }

    /** Quiet status pills: the worked state and the price of a tile the city can buy */
    private fun tilePills(tile: TileView, state: CityTileState): Table = Table().apply {
        val status = if (city.isOwnedTile(tile) || city.isWorked(tile)) tileStatus(tile, state) else null
        when (status) {
            "Worked" -> add(kit.pill(status, TrayColors.GOOD_BG, TrayColors.GOOD_INK))
            "Locked" -> add(kit.pill(status, TrayColors.GOOD_BG, TrayColors.GOOD_INK, "OtherIcons/LockSmall"))
            "Blockaded" -> add(kit.pill(status, TrayColors.DANGER_BG, TrayColors.DANGER_INK))
            "City center" -> add(kit.pill(status, TrayColors.FILL, TrayColors.INK))
            "Unworked" -> add(kit.pill(status, TrayColors.FILL, TrayColors.INK2))
        }
        if (city.canBuyTile(tile)) {
            val cost = city.getGoldCostOfTile(tile)
            val affordable = city.viewingCiv().hasStatToBuy(Stat.Gold, cost)
            add(kit.pill("${cost.tr()}${Fonts.gold}", if (affordable) TrayColors.LOCK_BG else TrayColors.DANGER_BG,
                if (affordable) TrayColors.LOCK_INK else TrayColors.DANGER_INK)).padLeft(if (cells.size > 0) pt(6f) else 0f)
        }
    }

    private fun openTile(tile: TileView) {
        listScrollY = scroll.scrollY
        screen.selectTile(tile)
        scroll.scrollY = 0f
        refresh()
    }

    private fun tilesTab() {
        hint("Tap a tile on the map or below")
        val states = screen.tileStates()
        if (!screen.isSpying && city.isOwnedByViewer()) {
            section("Border expansion")
            val next = screen.nextTileToOwn
            val hex = kit.disc(ImageGetter.getImage("OtherIcons/HexagonOutline"), Tone.Fill)
            if (next == null) card(listOf(listRow("No eligible tile", hex, onTap = null)))
            else {
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
                val progress = "${city.getCultureStored()}/${city.getCultureToNextTile()} culture · +$culturePerTurn per turn"
                val meta = Table().left().apply {
                    add(text(tileLabel)).minWidth(0f).growX().left().row()
                    add(text(progress)).minWidth(0f).growX().left().padTop(pt(2f))
                }
                card(listOf(listRow("Next border tile", hex, meta,
                    value = turns?.let { "[$it] turns" } ?: "Paused",
                    valueColor = if (turns == null) TrayColors.INK2 else TrayColors.YELLOW, chevron = false,
                    onTap = if (explored) ({ openTile(next) }) else null).apply { name = "portrait-next-border-tile" }))
            }
        }

        val tiles = states.keys.filter { city.isOwnedTile(it) || city.canBuyTile(it) }
        val owned = tiles.count { city.isOwnedTile(it) }
        section("City tiles", "${tiles.count { city.isWorked(it) }} of $owned worked")
        val sorted = tiles.sortedWith(compareBy<TileView>(
            { !it.isCityCenter() }, { !city.isWorked(it) }, { states[it] != CityTileState.WORKABLE },
            { !city.isOwnedTile(it) }, { if (city.canBuyTile(it)) city.getGoldCostOfTile(it) else 0 }))
        card(sorted.map { tile ->
            listRow(tileName(tile), tileIcon(tile), statLine(tile.getTileStats(city.viewingCiv(), city)),
                trailing = tilePills(tile, states[tile] ?: CityTileState.NONE)) { openTile(tile) }
        })
    }

    /** One tile pushed inside the sheet: the tile table's chips, rows and actions, its actions in the footer */
    private fun tileDetail(tile: TileView) {
        tileDetails.update(tile)
        content.add(tileDetails).row()
        val actions = tileDetails.trayActions
        if (actions.isEmpty()) return
        val wide = actions.sumOf { (it as Layout).prefWidth.toDouble() } + pt(10f) * (actions.size - 1) > bodyWidth - pt(8f)
        for ((index, actor) in actions.withIndex()) {
            val cell = footerRow.add(actor).growX().uniformX()
            if (wide) { footerRow.row(); if (index < actions.lastIndex) cell.padBottom(pt(10f)) }
            else if (index < actions.lastIndex) cell.padRight(pt(10f))
        }
    }

    // ---------------- Citizens ----------------

    private fun citizensTab() {
        val canChange = screen.canCityBeChanged()
        val population = listRow("${city.getPopulationCount()} citizens", kit.disc(ImageGetter.getImage("StatIcons/Specialist"), Tone.Fill),
            text("${city.getFreePopulation()} unassigned"),
            trailing = action("Reset", enabled = canChange) {
                city.tryReassignPopulation(resetLocked = true)
                screen.updateAsync()
            }.apply { name = "portrait-reset-citizens" }, onTap = null)
        card(listOf(population), 8f)

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
            val active = city.getCityFocus() == focus
            val chip = Table().apply {
                name = "portrait-focus-${focus.name}"
                background = art.rounded(pt(14f)).tinted(if (active) TrayColors.SEGMENT_ON else TrayColors.CARD)
                add().minHeight(touch).width(0f)
                if (icon != null) add(screen.portraitStatIcons.image(icon.name)).size(pt(20f)).padRight(pt(6f))
                add(Label(label.tr(hideIcons = true, hideStats = true), BaseScreen.skin).apply {
                    color = if (active || canChange) TrayColors.INK else TrayColors.INK2
                    setFontScale(font(14f).toFloat() / Fonts.ORIGINAL_FONT_SIZE)
                    if (icon == null) setAlignment(Align.center)
                    wrap = true
                }).minWidth(0f).growX()
                pad(0f, pt(8f), 0f, pt(8f))
                if (active) add(Table().apply {
                    background = ImageGetter.getCircleDrawable().tint(TrayColors.YELLOW)
                    add(ImageGetter.getImage("OtherIcons/Checkmark").apply { color = TrayColors.YELLOW_INK }).size(pt(10f))
                }).size(pt(18f)).padLeft(pt(4f))
                if (canChange) {
                    touchable = Touchable.enabled
                    onClick { city.trySetCityFocus(focus); screen.updateAsync() }
                }
            }
            val cell = focusGrid.add(chip).uniformX().growX().minWidth(0f).padBottom(pt(8f))
            if (count % 3 != 0) cell.padLeft(pt(8f))
            if (++count % 3 == 0) focusGrid.row()
        }
        while (count % 3 != 0) { focusGrid.add().uniformX().growX().padLeft(pt(8f)); count++ }
        content.add(focusGrid).row()

        val avoidLead = kit.disc(screen.portraitStatIcons.image("Food"), Tone.Fill, recolor = false)
        val avoid = kit.toggleRow("Avoid growth", city.avoidGrowth, "Keep food from adding citizens", avoidLead) {
            city.tryToggleAvoidGrowth()
            screen.updateAsync()
        }
        avoid.name = "portrait-avoid-growth"
        if (!canChange) {
            avoid.touchable = Touchable.disabled
            avoid.color.a = .6f
        }
        card(listOf(avoid))

        val specialists = city.getMaxSpecialists().asSequence().sortedBy { it.key }
            .filter { city.getRuleset().specialists.containsKey(it.key) }.toList()
        if (specialists.isEmpty()) return
        section("Specialists")
        if (canChange)
            content.add(kit.segmented(listOf("Manual", "Auto"), if (city.manualSpecialists) 0 else 1) { index ->
                if (index == 0) city.tryEnableManualSpecialists() else city.tryDisableManualSpecialists()
                screen.updateAsync()
            }).padBottom(pt(8f)).row()
        card(specialists.map { (name, max) ->
            val assigned = city.getNewSpecialists()[name]
            val specialist = city.getRuleset().specialists[name]!!
            val stepper = Table()
            if (screen.canChangeState) {
                val canRemove = assigned > 0 && !city.isPuppet()
                val canAdd = assigned < max && !city.isPuppet() && city.getFreePopulation() > 0
                stepper.add(action("−", enabled = canRemove) {
                    city.tryUnassignSpecialist(name)
                    screen.updateAsync()
                }.apply { this.name = "portrait-specialist-remove" }).minWidth(touch).padRight(pt(6f))
                stepper.add(action("+", enabled = canAdd) {
                    city.tryAssignSpecialist(name)
                    screen.updateAsync()
                }.apply { this.name = "portrait-specialist-add" }).minWidth(touch)
            }
            val meta = Table().left()
            meta.add("$assigned / $max".toLabel(TrayColors.INK2, font(13.5f))).padRight(pt(8f))
            meta.add(statLine(city.getStatsOfSpecialist(name)))
            listRow(name, kit.disc(ImageGetter.getSpecialistIcon(specialist.colorObject), Tone.Fill, iconSize = 30f, recolor = false),
                meta, trailing = stepper, onTap = null)
        })
    }

    // ---------------- Buy ----------------

    private fun buyTab() {
        if (!screen.canChangeState) {
            content.add(kit.emptyState("OtherIcons/Resources", "Purchases are unavailable")).row()
            return
        }
        section("Buy now")
        val data = constructions
        if (data == null) hint(Constants.loading)
        else card(data.buyable.map { row ->
            val construction = row.construction
            val buttons = Table()
            for ((index, buy) in buyActions(construction, -1, row.options).withIndex())
                buttons.add(buy).padLeft(if (index > 0) pt(6f) else 0f)
            val meta = Table().left()
            if (row.percentDone > 0) meta.add(text("In progress, ${row.percentDone}%")).left().row()
            if (row.blocked) meta.add("Move unit out of city first".toLabel(TrayColors.REASON, font(13.5f))).left().row()
            listRow(construction.name, ImageGetter.getConstructionPortrait(construction.name, pt(44f)),
                if (meta.cells.isEmpty) null else meta, trailing = buttons, chevron = false) { openDetails(construction, -1) }
        })
        val tile = screen.nextTileToOwn
        if (tile != null) {
            section("Expand the city")
            card(listOf(listRow("Buy a tile", kit.disc(ImageGetter.getImage("OtherIcons/HexagonOutline"), Tone.Fill),
                text("From [${city.getGoldCostOfTile(tile)}] gold")) {
                selectedTab = Tab.Tiles
                detailOpen = false
                listScrollY = 0f
                screen.selectTile(tile)
                scroll.scrollY = 0f
                refresh()
            }.apply { name = "portrait-buy-tile" }))
        }
    }
}
