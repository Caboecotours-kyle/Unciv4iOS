package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.MapGeneratedMainType
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitDialog
import com.unciv.ui.screens.basescreen.portraitCanvasBounds

/** The approved portrait More options: Map, Rules, Players and Mods tabs over the very tables, and so the same
 *  [GameSetupInfo][com.unciv.models.metadata.GameSetupInfo], the landscape layout edits. Changes apply as they are
 *  made, Done or the back disc only return to the leader card. */
internal class PortraitMoreOptionsView(
    private val screen: NewGameScreen,
    private val gameOptions: GameOptionsTable,
    private val mapOptions: MapOptionsTable,
    players: PlayerPickerTable,
    private val onClose: () -> Unit,
) : Group() {
    enum class Tab(val title: String) { Map("Map"), Rules("Rules"), Players("Players"), Mods("Mods") }

    private val controls = PortraitOptionControls.of(screen)
    private val body = Table()
    private val scroll = AutoScrollPane(body)
    private val tabButtons = LinkedHashMap<Tab, Table>()
    private val scrollPositions = HashMap<Tab, Float>()
    private val summary = controls.label("", 13f, PortraitOptionControls.MUTED, translate = false)
    private val playersTab = Table()
    var tab = Tab.Map
        private set

    init {
        val c = controls
        val canvas = screen.portraitCanvasBounds()
        val safe = screen.safeAreaBoundsInWorld()
        setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
        addActor(ImageGetter.getWhiteDot().apply {
            color = PortraitOptionControls.BACKGROUND
            setBounds(0f, 0f, canvas.width, canvas.height)
            touchable = Touchable.enabled  // nothing behind reacts while this is open
        })

        val root = Table()
        root.setBounds(safe.x - canvas.x, safe.y - canvas.y, safe.width, safe.height)
        addActor(root)

        val header = Table()
        // the 48pt disc sits 16pt in, centred in its larger tap target
        header.add(c.iconButton("OtherIcons/BackArrow", 48f, PortraitDialog.NAVY) { close() }).size(c.target)
        header.add(c.label("More options", 24f)).growX().left().padLeft(c.s(6f))
        root.add(header).growX().pad(c.s(2f), c.s(10f), 0f, c.s(16f)).row()

        val tabs = Table().apply {
            background = c.panel(PortraitOptionControls.TRACK)
            pad(0f)
        }
        for (each in Tab.entries) {
            // the whole segment is the tap target, the selection pill is inset within it
            val pill = Table().apply {
                pad(0f)
                add(c.label(each.title, 15f))
            }
            val button = Table().apply {
                touchable = Touchable.enabled
                add(pill).grow().pad(c.s(4f))
                onClick { select(each) }
            }
            tabs.add(button).growX().uniformX().height(c.target)
            tabButtons[each] = pill
        }
        root.add(tabs).growX().pad(c.s(8f), c.s(16f), c.s(6f), c.s(16f)).row()

        body.top().pad(0f, c.s(16f), c.s(24f), c.s(16f))
        scroll.setScrollingDisabled(true, false)
        scroll.setOverscroll(false, false)
        scroll.setFadeScrollBars(true)
        root.add(scroll).grow().row()

        summary.setEllipsis(true)
        summary.setAlignment(Align.center)
        val footer = Table().apply {
            background = c.panel(PortraitDialog.NAVY)
            pad(c.s(12f), c.s(16f), c.s(14f), c.s(16f))
            touchable = Touchable.enabled
            add(summary).growX().minWidth(0f).row()
            add(c.button("Done", PortraitDialog.Kind.Primary) { close() }.apply { label.setFontScale(label.fontScaleX * 18f / 16f) })
                .growX().height(c.target).padTop(c.s(10f))
        }
        root.add(footer).growX().pad(0f, c.s(8f), c.s(8f), c.s(8f))

        // The civilization list scrolls with the page instead of inside it, as in the narrow landscape layout
        (players.playerListTable.parent as? ScrollPane)?.setScrollingDisabled(true, true)
        players.background = null
        playersTab.add(players).width(c.contentWidth).padTop(c.s(12f)).row()
        playersTab.add(gameOptions.portraitPlayersTable).growX()
        select(tab)
    }

    /** Shows this over the leader card on [tab], or on the last tab used */
    fun open(tab: Tab?) {
        if (stage == null) screen.stage.addActor(this)
        select(tab ?: this.tab)
    }

    val isOpen get() = stage != null

    private fun content(tab: Tab): Actor = when (tab) {
        Tab.Map -> mapOptions
        Tab.Rules -> gameOptions
        Tab.Players -> playersTab
        Tab.Mods -> gameOptions.portraitModsTable
    }

    private fun select(next: Tab) {
        if (next != tab || body.hasChildren()) scrollPositions[tab] = scroll.scrollY
        tab = next
        body.clearChildren()
        body.add(content(next)).width(controls.contentWidth).top()
        for ((each, button) in tabButtons) {
            val selected = each == next
            button.background = if (selected) controls.panel(Color.WHITE) else null
            button.children.first().color = if (selected) PortraitOptionControls.INK else Color.WHITE
        }
        scroll.layout()
        scroll.scrollY = scrollPositions[next] ?: 0f
        scroll.updateVisualScroll()
    }

    override fun act(delta: Float) {
        super.act(delta)
        val setup = screen.gameSetupInfo
        val map = setup.mapParameters
        val game = setup.gameParameters
        val source = mapOptions.mapTypeSelectBox.selected.value
        val mapText = if (source == MapGeneratedMainType.generated) "${map.type.tr()} · ${map.mapSize.name.tr()}"
            else source.tr()
        val text = "$mapText · ${game.difficulty.tr()} · ${game.speed.tr()}"
        if (!summary.textEquals(text)) summary.setText(text)
    }

    fun close() {
        scrollPositions[tab] = scroll.scrollY
        remove()
        onClose()
    }
}
