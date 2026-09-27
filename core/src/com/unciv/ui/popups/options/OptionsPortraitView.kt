package com.unciv.ui.popups.options

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitDialog
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.max
import kotlin.math.roundToInt

/** Tokens of the portrait Settings mock, in points of a 393pt phone scaled by [u] */
internal class OptionsPortraitStyle(stage: Stage) {
    /** One design point. Never below one real point, so 48 stays a 48pt touch target on narrow phones */
    val u = max(PortraitDialog.scale(stage), stage.width / Gdx.graphics.width.coerceAtLeast(1))

    fun px(value: Float) = value * u
    fun font(value: Float) = (value * u).roundToInt()

    fun label(text: String, size: Float, color: Color = Color.WHITE, wrap: Boolean = true): Label =
        text.toLabel(color, font(size)).apply { this.wrap = wrap }

    fun header(text: String) = label(text.uppercase(), 13.5f, INK3)

    val screen: Drawable = BaseScreen.skinStrings.getUiBackground("General/Popup/Background", tintColor = SCREEN)
    val card = PortraitDialog.panel(CARD)
    val line: Drawable = ImageGetter.getWhiteDotDrawable().tint(LINE)

    fun sliderStyle() = Slider.SliderStyle(
        (ImageGetter.getWhiteDotDrawable().tint(TRACK) as BaseDrawable).apply { minHeight = px(8f) },
        (ImageGetter.getCircleDrawable().tint(PortraitDialog.YELLOW) as BaseDrawable).apply {
            minWidth = px(30f); minHeight = px(30f)
        }
    )

    fun button(text: String, primary: Boolean, action: () -> Unit): TextButton {
        val style = if (primary) PortraitDialog.buttonStyle(PortraitDialog.Kind.Primary)
            else PortraitDialog.buttonStyle(PortraitDialog.Kind.Ghost).apply {
                up = PortraitDialog.panel(SECONDARY)
                down = PortraitDialog.panel(SECONDARY.darken(.15f))
                over = up
            }
        return text.toTextButton(style).apply {
            label.setFontSize(font(18f))
            label.wrap = true
            label.setAlignment(Align.center)
            onClick(action)
        }
    }

    fun disc(icon: String, action: () -> Unit) = ImageGetter.getImage(icon)
        .apply { setSize(px(22f), px(22f)) }
        .surroundWithCircle(px(48f), resizeActor = false, color = HEADER_DISC).apply {
            touchable = Touchable.enabled
            onClick(action)
        }

    companion object {
        val SCREEN: Color = Color.valueOf("0f2133")
        val CARD: Color = Color.valueOf("182d43")
        val LINE = Color(1f, 1f, 1f, .1f)
        val INK2: Color = Color.valueOf("b7cde0")
        val INK3: Color = Color.valueOf("7f9ab2")
        val TRACK: Color = Color.valueOf("304a66")
        val SLIDER_DISC: Color = Color.valueOf("223a54")
        val HEADER_DISC = Color(1f, 1f, 1f, .08f)
        val SECONDARY: Color = Color.valueOf("264262")

        /** Pages that only exist on some platforms or setups get their own group on the home list */
        val conditionalPages = setOf(OptionsPopupPages.Multiplayer, OptionsPopupPages.Keys,
            OptionsPopupPages.ModCheck, OptionsPopupPages.Debug)

        fun iconColor(page: OptionsPopupPages): Color = Color.valueOf(when (page) {
            OptionsPopupPages.About -> "6d8bab"
            OptionsPopupPages.Display -> "2f7fe0"
            OptionsPopupPages.Gameplay -> "43a047"
            OptionsPopupPages.Automation -> "8e5bd6"
            OptionsPopupPages.Language -> "00a2b8"
            OptionsPopupPages.Sound -> "e0784a"
            OptionsPopupPages.Multiplayer -> "d84b8f"
            OptionsPopupPages.Keys -> "7a8b3a"
            OptionsPopupPages.Advanced -> "5a6b7d"
            OptionsPopupPages.ModCheck -> "d0453c"
            OptionsPopupPages.Debug -> "44525f"
        })
    }
}

/** A row that joins adjacent rows of the same parent Table into one rounded card with dividers */
internal open class PortraitRow(protected val style: OptionsPortraitStyle) : Table(BaseScreen.skin) {
    private fun isRowAt(index: Int) = (parent as? Table)?.cells?.let { index in 0 until it.size && it[index].actor is PortraitRow } == true

    override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
        val cells = (parent as? Table)?.cells
        var index = -1
        if (cells != null) for (i in 0 until cells.size) if (cells[i].actor === this) { index = i; break }
        val joinedAbove = index >= 0 && isRowAt(index - 1)
        val joinedBelow = index >= 0 && isRowAt(index + 1)
        batch.setColor(1f, 1f, 1f, color.a * parentAlpha)
        if (!joinedAbove && !joinedBelow) {
            style.card.draw(batch, x, y, width, height)
            return
        }
        // Draw one taller card clipped to this row, so the joined edges stay square and the outline continuous
        val overhang = style.px(40f)
        val below = if (joinedBelow) overhang else 0f
        val above = if (joinedAbove) overhang else 0f
        batch.flush()
        if (clipBegin(x, y, width, height)) {
            style.card.draw(batch, x, y - below, width, height + below + above)
            batch.flush()
            clipEnd()
        }
        if (joinedAbove)
            style.line.draw(batch, x + style.px(16f), y + height - 1f, width - style.px(16f), 1f)
    }
}

/** Mock toggle: 52x32 track, yellow when on, white knob */
private class Toggle(private val style: OptionsPortraitStyle, var isOn: Boolean) : Widget() {
    private val circle = ImageGetter.getCircleDrawable()
    private val fill = ImageGetter.getWhiteDotDrawable()

    override fun getPrefWidth() = style.px(52f)
    override fun getPrefHeight() = style.px(32f)

    override fun draw(batch: Batch, parentAlpha: Float) {
        validate()
        // Pill track: two end circles joined by a rectangle
        val track = if (isOn) PortraitDialog.YELLOW else OptionsPortraitStyle.TRACK
        batch.setColor(track.r, track.g, track.b, track.a * color.a * parentAlpha)
        circle.draw(batch, x, y, height, height)
        circle.draw(batch, x + width - height, y, height, height)
        fill.draw(batch, x + height / 2, y, width - height, height)
        batch.setColor(1f, 1f, 1f, color.a * parentAlpha)
        val inset = style.px(3f)
        val size = height - 2 * inset
        circle.draw(batch, if (isOn) x + width - inset - size else x + inset, y + inset, size, size)
    }
}

/** Boolean setting: wrapped label on the left, switch on the right, the whole row is the tap target */
internal class SwitchRow(style: OptionsPortraitStyle, text: String, initial: Boolean, onToggle: (Boolean) -> Unit) : PortraitRow(style) {
    init {
        pad(style.px(8f), style.px(16f), style.px(8f), style.px(12f))
        add(style.label(text, 16.5f)).growX().minHeight(style.px(40f)).padRight(style.px(12f))
        val toggle = Toggle(style, initial)
        add(toggle)
        touchable = Touchable.enabled
        onClick {
            toggle.isOn = !toggle.isOn
            onToggle(toggle.isOn)
        }
    }
}

/** Label stacked over a full-width control, for select boxes and other pickers */
internal class FieldRow(style: OptionsPortraitStyle, label: Label, control: Actor) : PortraitRow(style) {
    init {
        pad(style.px(12f), style.px(16f), style.px(14f), style.px(12f))
        add(label).growX().row()
        add(control).growX().minWidth(0f).minHeight(style.px(48f)).padTop(style.px(10f))
    }
}

/** Label and current value over a slider with 48pt minus and plus discs */
internal class SliderRow(
    style: OptionsPortraitStyle, text: String, private val slider: UncivSlider, step: Float,
    private val getTipText: ((Float) -> String)?
) : PortraitRow(style) {
    private val value = style.label("", 17f, wrap = false)
    private var shown = Float.NaN
    // Same precision UncivSlider uses for its tip
    private val format = when {
        step > 0.99f -> "%.0f"
        step > 0.099f -> "%.1f"
        step > 0.0099f -> "%.2f"
        else -> "%.3f"
    }
    val sliderCell: Cell<UncivSlider>

    init {
        pad(style.px(12f), style.px(16f), style.px(14f), style.px(12f))
        add(style.label(text, 15f, OptionsPortraitStyle.INK2)).growX()
        add(value).right().padLeft(style.px(12f)).row()
        restyle()
        sliderCell = add(slider).colspan(2).growX().minWidth(0f).padTop(style.px(10f))
    }

    /** UncivSlider cells are minus, slider, plus: enlarge the discs, keep their click and snap logic */
    private fun restyle() {
        (slider.cells[1].actor as Slider).style = style.sliderStyle()
        slider.cells[1].pad(0f, style.px(12f), 0f, style.px(12f)).minHeight(style.px(48f))
        for (index in listOf(0, 2)) {
            val disc = slider.cells[index].actor as? IconCircleGroup ?: continue
            val size = style.px(48f)
            disc.setSize(size, size)
            disc.circle.setSize(size, size)
            disc.circle.color.set(OptionsPortraitStyle.SLIDER_DISC.r, OptionsPortraitStyle.SLIDER_DISC.g,
                OptionsPortraitStyle.SLIDER_DISC.b, disc.circle.color.a)
            val fontSize = style.font(24f)
            (disc.actor as? Label)?.run {
                setFontSize(fontSize)
                setAlignment(Align.center)
                setBounds(0f, 0f, size, size)
            }
            slider.cells[index].pad(0f)
        }
        slider.invalidateHierarchy()
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (slider.value == shown) return
        shown = slider.value
        value.setText(getTipText?.invoke(shown) ?: format.format(shown))
    }
}

/** A home list entry: colored icon disc, page name, chevron */
private class CategoryRow(style: OptionsPortraitStyle, page: OptionsPopupPages, language: String, action: () -> Unit) : PortraitRow(style) {
    init {
        pad(style.px(8f), style.px(12f), style.px(8f), style.px(12f))
        val icon = page.getIcon(language).apply { setSize(style.px(24f), style.px(24f)) }
        add(icon.surroundWithCircle(style.px(42f), resizeActor = false, color = OptionsPortraitStyle.iconColor(page)))
            .padRight(style.px(14f))
        add(style.label(page.label, 17.5f)).growX().minHeight(style.px(48f))
        add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = OptionsPortraitStyle.INK3 })
            .size(style.px(16f)).padLeft(style.px(12f))
        touchable = Touchable.enabled
        onClick(action)
    }
}

/**
 *  Portrait Settings: a category home and one focused page at a time.
 *
 *  Pages stay inside the popup's [TabbedPager] (its header hidden), so page activation,
 *  replacement and scrolling work exactly as in landscape.
 */
internal class OptionsPortraitView(
    private val popup: OptionsPopup,
    private val style: OptionsPortraitStyle,
    private val tabs: TabbedPager,
    private val pages: List<OptionsPopupPages>
) : Table(BaseScreen.skin) {
    /** The page shown, `null` while on the home list */
    var page: OptionsPopupPages? = null
        private set

    private val back = style.disc("OtherIcons/BackArrow") { showHome() }
    private val title = style.label("Settings", 21f, wrap = false).apply {
        setAlignment(Align.center)
        setEllipsis(true)
    }
    private val body: Cell<Actor?>
    private val footer = Table()
    private val home: AutoScrollPane

    init {
        val header = Table()
        header.pad(style.px(8f), style.px(12f), style.px(8f), style.px(12f))
        header.add(back).size(style.px(48f))
        header.add(title).growX().minWidth(0f).padLeft(style.px(8f)).padRight(style.px(8f))
        header.add(style.disc("OtherIcons/Close") { popup.close() }).size(style.px(48f))
        add(header).growX().row()

        home = AutoScrollPane(homeList(), BaseScreen.skin).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        body = add().grow()
        row()

        add(ImageGetter.getWhiteDot().apply { color = OptionsPortraitStyle.LINE }).height(1f).growX().row()
        footer.pad(style.px(12f))
        add(footer).growX()

        keyShortcuts.add(KeyCharAndCode.BACK) { if (page != null) showHome() else popup.close() }
    }

    private fun homeList() = Table().apply {
        top() // the pane stretches a short list to its height, keep the rows under the header
        pad(style.px(2f), style.px(12f), style.px(24f), style.px(12f))
        val (conditional, main) = pages.partition { it in OptionsPortraitStyle.conditionalPages }
        for (group in listOf(main, conditional)) {
            if (group.isEmpty()) continue
            if (cells.size > 0) add().height(style.px(18f)).row()
            for (page in group)
                add(CategoryRow(style, page, popup.settings.language) { showPage(page) }).growX().row()
        }
    }

    fun showHome() {
        tabs.selectPage(-1, false)
        page = null
        body.setActor(home)
        refresh()
    }

    fun showPage(page: OptionsPopupPages) {
        this.page = page
        body.setActor(tabs)
        refresh()
        popup.validate() // pages measure and scroll against the pager size on activation
        tabs.selectPage(pages.indexOf(page))
    }

    private fun refresh() {
        back.isVisible = page != null
        title.setText((page?.label ?: "Settings").tr())
        footer.clear()
        if (page != null)
            footer.add(style.button("All settings", primary = false) { showHome() })
                .growX().uniformX().minHeight(style.px(56f)).padRight(style.px(12f))
        footer.add(style.button("Done", primary = true) { popup.close() })
            .growX().uniformX().minHeight(style.px(56f))
    }
}
