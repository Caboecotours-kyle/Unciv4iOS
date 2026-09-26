package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories.EmpireOverviewTabState
import kotlin.math.abs

/** Colors and small builders shared by the portrait overview sheet and its pages. */
internal object OverviewPortraitStyle {
    const val WIDTH = 393f
    const val PAD = 14f
    const val CONTENT = WIDTH - 2 * PAD
    val NAVY: Color = Color.valueOf("122536")
    val RAIL: Color = Color.valueOf("0f2030")
    val CARD: Color = Color.valueOf("1c3249")
    val SEG: Color = Color.valueOf("16283a")
    val INK2: Color = Color.valueOf("b7cde0")
    val INK3: Color = Color.valueOf("7f9ab2")
    val POS: Color = Color.valueOf("9ff08a")
    val NEG: Color = Color.valueOf("ff8f86")
    val YELLOW: Color = Color.valueOf("ffc93c")
    val LINE = Color(1f, 1f, 1f, .12f)
    val CHIP = Color(1f, 1f, 1f, .06f)
    val ON = Color(1f, 1f, 1f, .1f)

    fun bg(color: Color): Drawable = BaseScreen.skinStrings.getUiBackground(
        "", BaseScreen.skinStrings.roundedEdgeRectangleShape, color)

    fun label(text: String, color: Color = Color.WHITE, size: Int = 16, align: Int = Align.left) =
        text.toLabel(color, size, align)

    fun wrapped(text: String, color: Color = Color.WHITE, size: Int = 16, align: Int = Align.left) =
        label(text, color, size, align).apply { wrap = true }

    fun divider() = Image(ImageGetter.getWhiteDotDrawable().tint(LINE))

    fun Table.addDivider() { add(divider()).growX().height(1f).row() }

    fun heading(text: String) = label(text, INK3, 14)

    fun empty(text: String) = wrapped(text, INK2, 17, Align.center)

    fun card() = Table().apply { background = bg(CARD); pad(12f) }

    fun tag(text: String, color: Color = INK2) = Table().apply {
        background = bg(CHIP)
        pad(3f, 8f, 3f, 8f)
        add(label(text, color, 13))
    }

    /** A breakdown line: name left, value right. */
    fun brow(name: String, value: String, valueColor: Color = Color.WHITE, bold: Boolean = false) = Table().apply {
        val left = wrapped(name, if (bold) Color.WHITE else INK2, 15)
        add(left).growX().minHeight(32f)
        add(label(value, valueColor, if (bold) 16 else 15, Align.right)).right().padLeft(8f)
    }

    fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

    fun colorOf(value: Int) = if (value < 0) NEG else Color.WHITE

    /** 48pt sort/filter chip; the active one is inverted like the mock. */
    fun chip(text: String, on: Boolean, icon: Actor? = null, action: () -> Unit) = Table().apply {
        background = bg(if (on) Color.WHITE else CHIP)
        touchable = Touchable.enabled
        pad(0f, 13f, 0f, 13f)
        if (icon != null) add(icon).size(18f).padRight(6f)
        add(label(text, if (on) NAVY else INK2, 14))
        onActivation { action() }
    }

    /** One line of chips that scrolls sideways, independent of the page scroll. */
    fun chipRow(chips: List<Actor>, scrollToActive: Actor? = null): Actor {
        val row = Table()
        for (chip in chips) row.add(chip).height(48f).padRight(6f)
        val scroll = AutoScrollPane(row)
        scroll.setScrollingDisabled(false, true)
        scroll.setOverscroll(false, false)
        if (scrollToActive != null) {
            scroll.layout()
            row.validate()
            scroll.scrollTo(scrollToActive.x, 0f, scrollToActive.width, 48f, true, false)
            scroll.updateVisualScroll()
        }
        return scroll
    }

    /** Segmented control: [options] with the [selected] index pressed. */
    fun segmented(options: List<String>, selected: Int, fontSize: Int = 15, onSelect: (Int) -> Unit) = Table().apply {
        background = bg(SEG)
        pad(3f)
        options.forEachIndexed { index, option ->
            val on = index == selected
            val button = Table().apply {
                if (on) background = bg(Color.WHITE)
                touchable = Touchable.enabled
                pad(0f, 12f, 0f, 12f)
                add(label(option, if (on) NAVY else INK2, fontSize))
                onActivation { if (!on) onSelect(index) }
            }
            add(button).height(42f).growX()
        }
    }

    /** Circled go-to button with a 48pt target. */
    fun goButton(action: () -> Unit) = ImageGetter.getImage("OtherIcons/ArrowRight").apply {
        color = INK2
        setSize(16f, 16f)
    }.surroundWithCircle(40f, resizeActor = false, color = CHIP).apply {
        touchable = Touchable.enabled
        onActivation { action() }
    }

    /** A value with a stat icon in front, used in compact yield lines. */
    fun iconValue(icon: Actor, value: String, color: Color = INK2, iconSize: Float = 15f) = Table().apply {
        add(icon).size(iconSize).padRight(3f)
        add(label(value, color, 14))
    }
}

/** Base for the phone pages: same data, persistence and commands as the landscape tab, laid out for 393pt. */
internal abstract class PortraitOverviewPage(
    viewingPlayer: com.unciv.view.CivView,
    overviewScreen: EmpireOverviewScreen,
    persistedData: EmpireOverviewTabPersistableData?
) : EmpireOverviewTab(viewingPlayer, overviewScreen, persistedData) {
    val civ get() = viewingPlayer.getCiv()
    /** Where the page scroll was when the player last left this page. */
    open var scrollMemory = 0f
    /** Actor the sheet should scroll to after [select]. */
    var focus: Actor? = null

    abstract fun subtitle(): String

    /** Rebuild from the model. Called when shown, after resume, and after any in-page state change. */
    abstract fun update()

    /** Rebuild and let the sheet refresh its header and keep the scroll where it was. */
    fun refresh() = overviewScreen.portraitSheet?.refreshPage(this)

    val icons get() = overviewScreen.portraitStatIcons
}

/** Map-backed overview sheet for portrait phones: header with swipe-down close, page scroll, category rail at the thumb. */
internal class EmpireOverviewPortraitSheet(
    private val screen: EmpireOverviewScreen,
    private val states: Map<EmpireOverviewCategories, EmpireOverviewTabState>,
    private val bottomPad: Float
) : Table() {
    private val handle = Table()
    private val heading = Table()
    private val title = OverviewPortraitStyle.label("", size = 23)
    private val subtitle = OverviewPortraitStyle.label("", OverviewPortraitStyle.INK2, 14)
    private val content = object : Table() {
        override fun getPrefWidth() = OverviewPortraitStyle.WIDTH
        override fun getMinWidth() = OverviewPortraitStyle.WIDTH
    }.top()
    private val scroll = AutoScrollPane(content).apply {
        setScrollingDisabled(true, false)
        setOverscroll(false, true)
    }
    private val rail = Table()
    private val railScroll = AutoScrollPane(rail).apply {
        setScrollingDisabled(false, true)
        setOverscroll(false, false)
    }
    private val railButtons = HashMap<EmpireOverviewCategories, Table>()
    var active: EmpireOverviewCategories? = null
        private set

    init {
        with(OverviewPortraitStyle) {
            background = BaseScreen.skinStrings.getUiBackground("", BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape, NAVY)
            handle.add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .3f) }).size(44f, 5f).padTop(8f)
            handle.touchable = Touchable.enabled
            heading.touchable = Touchable.enabled
            heading.pad(10f, 16f, 6f, 16f)
            val titles = Table()
            titles.add(title).left().row()
            titles.add(subtitle).left().padTop(1f)
            heading.add(titles).expandX().left()
            val close = ImageGetter.getImage("OtherIcons/Close").apply {
                color = INK2
                setSize(18f, 18f)
            }.surroundWithCircle(48f, resizeActor = false, color = Color(1f, 1f, 1f, .09f))
            close.onActivation { screen.game.popScreen() }
            close.keyShortcuts.add(KeyCharAndCode.BACK)
            heading.add(close).size(48f)

            buildRail()
            add(handle).growX().height(13f).row()
            add(heading).growX().row()
            add(scroll).grow().prefHeight(0f).row()
            val railBox = Table().apply { background = bg(RAIL) }
            railBox.add(divider()).growX().height(1f).row()
            railBox.add(railScroll).growX().pad(8f, 10f, bottomPad, 10f)
            add(railBox).growX()
        }
        addListener(SwipeDownToClose())
    }

    private fun buildRail() {
        for (category in EmpireOverviewCategories.entries) {
            val state = states[category] ?: continue
            if (state == EmpireOverviewTabState.Hidden) continue
            val button = Table().apply {
                touchable = if (state == EmpireOverviewTabState.Normal) Touchable.enabled else Touchable.disabled
                if (state != EmpireOverviewTabState.Normal) color.a = .3f
            }
            railButtons[category] = button
            if (state == EmpireOverviewTabState.Normal) {
                button.onActivation { show(category) }
                button.keyShortcuts.add(category.shortcutKey)
            }
            rail.add(button).size(78f, 60f).padRight(6f)
        }
    }

    private fun styleRailButton(category: EmpireOverviewCategories, on: Boolean) {
        val button = railButtons[category] ?: return
        button.clearChildren()
        button.background = if (on) OverviewPortraitStyle.bg(OverviewPortraitStyle.ON) else null
        val tint = if (on) Color.WHITE else OverviewPortraitStyle.INK3
        val icon = railIcon(category).apply { color = tint }
        button.add(icon).size(24f).row()
        button.add(OverviewPortraitStyle.label(category.name, tint, 12)).padTop(4f)
    }

    private fun railIcon(category: EmpireOverviewCategories): Image = when {
        category == EmpireOverviewCategories.Trades -> ImageGetter.getImage("OtherIcons/Swap")
        category.iconName.startsWith("StatIcons/") -> screen.portraitStatIcons.image(category.iconName.removePrefix("StatIcons/"))
        else -> ImageGetter.getImage(category.iconName)
    }

    fun isEnabled(category: EmpireOverviewCategories) = states[category] == EmpireOverviewTabState.Normal

    fun show(category: EmpireOverviewCategories) {
        if (!isEnabled(category)) return
        val page = screen.getPortraitPage(category)
        if (active != category) saveScroll()
        active = category
        screen.persistState.last = category
        for (key in railButtons.keys) styleRailButton(key, key == category)
        content.clear()
        content.add(page).width(OverviewPortraitStyle.CONTENT).pad(4f, OverviewPortraitStyle.PAD, 40f, OverviewPortraitStyle.PAD).top()
        page.update()
        updateHeader(category, page)
        restoreScroll(page.scrollMemory)
        keepRailButtonVisible(category)
    }

    fun saveScroll() {
        val category = active ?: return
        (screen.pageObjects[category] as? PortraitOverviewPage)?.scrollMemory = scroll.scrollY
    }

    /** Every category is disabled, e.g. a spectator before anything happened. */
    fun showNothing() {
        for (key in railButtons.keys) styleRailButton(key, false)
        title.setText("Overview".tr())
        subtitle.setText("")
        content.clear()
        content.add(OverviewPortraitStyle.empty("Nothing to show yet")).width(OverviewPortraitStyle.CONTENT).padTop(40f)
    }

    /** Rebuild the active page in place, keeping the page scroll. */
    fun refreshPage(page: PortraitOverviewPage) {
        val category = active ?: return
        if (screen.pageObjects[category] !== page) return
        val y = scroll.scrollY
        page.update()
        updateHeader(category, page)
        restoreScroll(y)
    }

    private fun updateHeader(category: EmpireOverviewCategories, page: PortraitOverviewPage) {
        title.setText(category.name.tr())
        subtitle.setText(page.subtitle().tr())
    }

    private fun restoreScroll(y: Float) {
        validate()
        scroll.layout()
        scroll.scrollY = y
        scroll.updateVisualScroll()
    }

    /** Scroll the page so [actor] is centered, used after an external selection. */
    fun scrollTo(actor: Actor) {
        validate()
        scroll.layout()
        val pos = actor.localToActorCoordinates(content, Vector2(0f, 0f))
        scroll.scrollTo(pos.x, pos.y, actor.width, actor.height, false, true)
        scroll.updateVisualScroll()
    }

    private fun keepRailButtonVisible(category: EmpireOverviewCategories) {
        val button = railButtons[category] ?: return
        validate()
        railScroll.layout()
        rail.validate()
        railScroll.scrollTo(button.x, 0f, button.width, button.height, true, false)
        railScroll.updateVisualScroll()
    }

    /** Dragging the handle or header down moves the sheet with the finger; past [threshold] it closes like ×. The page scroll is unaffected. */
    private inner class SwipeDownToClose : InputListener() {
        private val slop = 10f
        private val threshold = 80f
        private var startX = 0f
        private var startY = 0f
        private var restY = 0f
        private var tracking = false
        private var dragging = false

        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            if (tracking) { restore(); return false }
            if (pointer != 0 || button != 0 || y < heading.y || hasActions()) return false
            startX = event.stageX
            startY = event.stageY
            restY = this@EmpireOverviewPortraitSheet.y
            tracking = true
            dragging = false
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            if (!tracking || pointer != 0) return
            if (Gdx.input.isTouched(1)) { restore(); return }
            val dx = event.stageX - startX
            val down = startY - event.stageY
            if (!dragging) {
                val slopStage = slop * scaleY
                if (abs(dx) > slopStage && abs(dx) >= down) { tracking = false; return }
                if (down <= slopStage || down < abs(dx)) return
                dragging = true
                // The drag owns this touch now: no title or × activation on release
                event.stage.cancelTouchFocusExcept(this, this@EmpireOverviewPortraitSheet)
            }
            this@EmpireOverviewPortraitSheet.y = restY - down.coerceAtLeast(0f)
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            if (!tracking || pointer != 0) return
            val closes = dragging && !event.isTouchFocusCancel && !Gdx.input.isTouched(1) && startY - event.stageY >= threshold * scaleY
            if (!closes) { restore(); return }
            tracking = false
            touchable = Touchable.disabled
            addAction(Actions.sequence(
                Actions.moveTo(this@EmpireOverviewPortraitSheet.x, restY - height * scaleY, .15f, Interpolation.fastSlow),
                Actions.run { screen.game.popScreen() }))
        }

        private fun restore() {
            if (dragging && this@EmpireOverviewPortraitSheet.y != restY)
                addAction(Actions.moveTo(this@EmpireOverviewPortraitSheet.x, restY, .12f, Interpolation.fastSlow))
            tracking = false
            dragging = false
        }
    }
}
