package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitDialog
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import kotlin.math.max
import kotlin.math.roundToInt

/** Building blocks of the portrait More options screen, sized in 393pt phone units.
 *  Every control mirrors a real legacy widget (select box or checkbox) and changes it the way a user would,
 *  so that widget's existing listener stays the only code that writes the game setup. */
internal class PortraitOptionControls(val unit: Float) {
    companion object {
        /** 393pt phone less 16pt margins */
        const val CONTENT_WIDTH = 361f
        /** Smallest tap target: 48.9pt on a 320pt phone. Smaller visuals sit centred inside one */
        const val TARGET = 60f
        val BACKGROUND: Color = Color.valueOf("122536")
        val SHEET: Color = Color.valueOf("132a40")
        val TRACK = Color(1f, 1f, 1f, .07f)
        val LINE = Color(1f, 1f, 1f, .08f)
        val MUTED: Color = Color.valueOf("b7cde0")
        val INK: Color = Color.valueOf("132435")

        fun of(screen: BaseScreen) = PortraitOptionControls(PortraitDialog.scale(screen.stage))

        /** Closes the topmost bottom sheet on [stage], for Back; returns whether there was one */
        fun closeSheet(stage: Stage): Boolean {
            val sheet = stage.root.children.lastOrNull { it is SheetOverlay } ?: return false
            sheet.remove()
            return true
        }
    }

    /** Disclosures stay open while their owning table rebuilds itself */
    private val openDisclosures = HashSet<String>()

    fun s(value: Float) = value * unit
    private fun font(value: Float) = (value * unit).roundToInt()
    val contentWidth get() = s(CONTENT_WIDTH)

    val target get() = s(TARGET)

    fun label(text: String, size: Float = 16f, color: Color = Color.WHITE, translate: Boolean = true): Label =
        if (translate) text.toLabel(color, font(size), hideIcons = true)
        else "".toLabel(color, font(size)).apply { setText(text) }

    fun panel(color: Color) = PortraitDialog.panel(color)

    /** Adds a grey heading and [content] below it, both across the full width of [table] */
    fun section(table: Table, title: String?, content: Actor, gap: Float = 20f) {
        if (title != null)
            table.add(label(title, 13f, MUTED)).colspan(2).left().padTop(s(gap)).padBottom(s(8f)).row()
        table.add(content).colspan(2).growX().padTop(if (title == null) s(gap / 2f) else 0f).row()
    }

    /** Grey [title] with the live value of [slider] on the right, for a slider built with [UncivSlider.TipType.None] */
    fun sliderTitle(title: String, slider: UncivSlider): Table {
        val value = object : Label("", label("", 15f).style) {
            override fun act(delta: Float) {
                super.act(delta)
                // the precision UncivSlider gives its own tip
                val format = when {
                    slider.stepSize > 0.99f -> "%.0f"
                    slider.stepSize > 0.099f -> "%.1f"
                    slider.stepSize > 0.0099f -> "%.2f"
                    else -> "%.3f"
                }
                val shown = format.format(slider.value)
                if (!textEquals(shown)) setText(shown)
            }
        }
        return Table().apply {
            add(label(title, 13f, MUTED)).growX().left()
            add(value).right()
        }
    }

    /** [slider] with a finger-sized track, and steppers in place of its own small minus and plus discs.
     *  The steppers tap those discs, so stepping, snapping and the change callback stay the slider's own */
    fun slider(slider: UncivSlider): Table {
        val discs = slider.children.filterIsInstance<IconCircleGroup>()
        slider.children.firstOrNull { it is Slider }?.let { slider.getCell(it).height(target) }
        val row = Table()
        fun stepper(disc: IconCircleGroup?, glyph: String) {
            if (disc == null) return
            disc.remove()
            val button = object : Table() {
                override fun act(delta: Float) {
                    super.act(delta)
                    color.a = if (disc.touchable == Touchable.enabled) 1f else .45f
                }
            }
            button.touchable = Touchable.enabled
            button.add(Table().apply {
                background = panel(TRACK)
                pad(0f)
                add(label(glyph, 20f, translate = false))
            }).size(s(40f))
            button.onClick { if (disc.touchable == Touchable.enabled) disc.activate() }
            row.add(button).size(target)
        }
        stepper(discs.firstOrNull(), "-")
        row.add(slider).growX().minWidth(0f)
        stepper(discs.getOrNull(1), "+")
        return row
    }

    /** One rounded card holding [rows], separated by hairlines */
    fun group(rows: List<Actor>): Table = Table().apply {
        background = panel(TRACK)
        pad(0f)
        for ((index, row) in rows.withIndex()) {
            if (index > 0) add(ImageGetter.getWhiteDot().apply { color = LINE })
                .height(max(1f, s(1f))).prefWidth(0f).growX().padLeft(s(16f)).row()
            add(row).growX().row()
        }
    }

    fun switches(boxes: List<CheckBox>) = group(boxes.map { BoundSwitch(it) })

    /** Segmented control ([chips] false) or wrapping chips over [box] */
    fun choices(box: TranslatedSelectBox, chips: Boolean = false, width: Float = contentWidth): Table =
        BoundChoices(box, chips, width)

    /** Chips toggling membership of [values], the in/out set a [MultiCheckboxTable] would edit */
    fun toggleChips(options: Collection<String>, values: HashSet<String>, onUpdate: (String) -> Unit): Table {
        val chips = options.map { name ->
            val chip = option(label(name, 15f).apply { setEllipsis(true) }, chips = true)
            fun restyle() = styleOption(chip, name in values, chips = true, disabled = false)
            chip.onClick {
                if (!values.remove(name)) values.add(name)
                restyle()
                onUpdate(name)
            }
            restyle()
            chip
        }
        return flow(chips, contentWidth)
    }

    /** Radio list over [box], [onPicked] runs after a tap selected an entry */
    fun radios(box: SelectBox<*>, onPicked: () -> Unit = {}): Table =
        group(box.items.map { item -> BoundRadio(box, item, onPicked) })

    /** Row showing the current value of [box]; opens a bottom sheet of all its entries.
     *  Without [showTitle] only the value shows, for several pickers side by side */
    fun pickerRow(screen: BaseScreen, title: String, box: SelectBox<*>, showTitle: Boolean = true): Table {
        val value = label("", 15f, if (showTitle) MUTED else Color.WHITE, translate = false).apply {
            setEllipsis(true)
            setAlignment(if (showTitle) Align.right else Align.center)
        }
        val row = object : Table() {
            override fun act(delta: Float) {
                super.act(delta)
                val shown = box.selected?.toString() ?: ""
                if (!value.textEquals(shown)) value.setText(shown)
                color.a = if (box.isDisabled) .45f else 1f
            }
        }
        row.background = panel(TRACK)
        row.pad(0f)
        row.touchable = Touchable.enabled
        if (showTitle) row.add(label(title, 16f)).left().padLeft(s(16f))
        row.add(value).growX().minWidth(0f).right().padLeft(s(if (showTitle) 12f else 8f)).padRight(s(8f))
        row.add(ImageGetter.getImage("OtherIcons/ArrowRight", MUTED)).size(s(14f)).padRight(s(16f))
        row.onClick { if (!box.isDisabled) openSheet(screen, title, box, row) }
        return Table().apply { add(row).growX().minHeight(target) }
    }

    /** Header that shows or hides [content]; remembers being open under [key] */
    fun disclosure(key: String, title: String, content: Actor): Table {
        val wrapper = Table()
        val chevron = ImageGetter.getImage("OtherIcons/ArrowRight", MUTED).apply {
            setSize(s(14f), s(14f))
            setOrigin(Align.center)
        }
        val header = Table().apply {
            background = panel(TRACK)
            pad(0f)
            touchable = Touchable.enabled
            add(label(title, 16f)).growX().left().padLeft(s(16f))
            add(chevron).size(s(14f)).padRight(s(16f))
        }
        wrapper.add(header).growX().minHeight(target).row()
        val body = wrapper.add().growX().padTop(s(8f))
        fun show() {
            val open = key in openDisclosures
            body.setActor(if (open) content else null)
            chevron.rotation = if (open) -90f else 0f
        }
        header.onClick {
            if (!openDisclosures.remove(key)) openDisclosures.add(key)
            show()
            wrapper.invalidateHierarchy()
        }
        show()
        return wrapper
    }

    fun button(text: String, kind: PortraitDialog.Kind, action: () -> Unit): TextButton =
        text.toTextButton(PortraitDialog.buttonStyle(kind), hideIcons = true).apply {
            label.setFontSize(font(16f))
            label.wrap = true
            label.setAlignment(Align.center)
            onClick { action() }
        }

    /** A [size] disc centred in a [target] square; give its cell at least that size */
    fun iconButton(icon: String, size: Float = 48f, fill: Color = TRACK, action: () -> Unit): Table = Table().apply {
        touchable = Touchable.enabled
        add(Table().apply {
            background = panel(fill)
            pad(0f)
            add(ImageGetter.getImage(icon, Color.WHITE)).size(s(size * .44f))
        }).size(s(size))
        onClick { action() }
    }

    /** A tap target around the pill [styleOption] paints: a 48pt chip, or a segment inset 4pt in its track */
    private fun option(text: Label, chips: Boolean): Table {
        val pill = Table().apply {
            pad(0f)
            add(text).growX().minWidth(0f).padLeft(s(14f)).padRight(s(14f))
        }
        return Table().apply {
            touchable = Touchable.enabled
            if (chips) add(pill).growX().height(s(48f))
            else add(pill).grow().pad(s(4f))
        }
    }

    private fun styleOption(option: Table, selected: Boolean, chips: Boolean, disabled: Boolean) {
        val pill = option.children.first() as Table
        pill.background = when {
            selected -> panel(Color.WHITE)
            chips -> panel(TRACK)
            else -> null
        }
        pill.children.first().color = (if (selected) INK else Color.WHITE).cpy().apply { if (disabled) a = .45f }
    }

    /** Lays [options] out left to right, starting a new line whenever [width] would be exceeded */
    private fun flow(options: List<Table>, width: Float): Table {
        val lines = Table().left()
        var line = Table()
        var lineWidth = 0f
        for (option in options) {
            val optionWidth = max(option.prefWidth, target) + s(8f)
            if (lineWidth > 0f && lineWidth + optionWidth > width) {
                lines.add(line).left().row()
                line = Table()
                lineWidth = 0f
            }
            line.add(option).height(target).minWidth(target).maxWidth(width).padRight(s(8f))
            lineWidth += optionWidth
        }
        lines.add(line).left()
        return lines
    }

    private inner class BoundChoices(
        private val box: TranslatedSelectBox,
        private val chips: Boolean,
        width: Float
    ) : Table() {
        private val options = ArrayList<Pair<String, Table>>()
        private var shown: String? = null
        private var shownDisabled = false

        init {
            for (item in box.items) {
                val text = label(item.toString(), 15f, translate = false).apply {
                    setAlignment(Align.center)
                    if (chips) setEllipsis(true) else wrap = true
                }
                val option = option(text, chips)
                option.onClick { if (!box.isDisabled) box.setSelected(item.value) }
                options += item.value to option
            }
            if (chips) {
                left()
                add(flow(options.map { it.second }, width)).left()
            } else {
                background = panel(TRACK)
                pad(0f)
                for ((_, option) in options) add(option).growX().uniformX().minHeight(target)
            }
            restyle()
        }

        override fun act(delta: Float) {
            super.act(delta)
            if (box.selected?.value != shown || box.isDisabled != shownDisabled) restyle()
        }

        private fun restyle() {
            shown = box.selected?.value
            shownDisabled = box.isDisabled
            for ((value, option) in options) styleOption(option, value == shown, chips, shownDisabled)
        }
    }

    private inner class BoundSwitch(private val box: CheckBox) : Table() {
        /** A pill from two end circles and the rect between them; opaque tints, so the overlaps don't show */
        private val track = listOf(
            ImageGetter.getCircle(Color.WHITE, s(28f)),
            ImageGetter.getCircle(Color.WHITE, s(28f)).apply { x = s(18f) },
            ImageGetter.getWhiteDot().apply { setBounds(s(14f), 0f, s(18f), s(28f)) })
        private val knob = ImageGetter.getCircle(Color.WHITE, s(22f))
        private var shown: Boolean? = null
        private var shownDisabled = false

        init {
            pad(0f)
            touchable = Touchable.enabled
            val text = label(box.text.toString(), 16f, translate = false).apply { wrap = true }
            val switch = Group().apply {
                setSize(s(46f), s(28f))
                for (part in track) addActor(part)
                addActor(knob)
            }
            add(text).growX().left().pad(s(12f), s(16f), s(12f), s(12f))
            add(switch).size(s(46f), s(28f)).padRight(s(16f))
            onClick { if (!box.isDisabled) box.isChecked = !box.isChecked }
            restyle()
        }

        override fun act(delta: Float) {
            super.act(delta)
            if (box.isChecked != shown || box.isDisabled != shownDisabled) restyle()
        }

        override fun getPrefHeight() = max(super.getPrefHeight(), target)

        private fun restyle() {
            shown = box.isChecked
            shownDisabled = box.isDisabled
            // off: 18% white over the card, which is itself TRACK over the page
            val tint = if (box.isChecked) PortraitDialog.YELLOW
                else BACKGROUND.cpy().lerp(Color.WHITE, TRACK.a).lerp(Color.WHITE, .18f)
            for (part in track) part.color = tint
            knob.setPosition(if (box.isChecked) s(21f) else s(3f), s(3f))
            color.a = if (box.isDisabled) .45f else 1f
        }
    }

    private inner class BoundRadio(
        private val box: SelectBox<*>,
        private val item: Any?,
        onPicked: () -> Unit
    ) : Table() {
        private val dot = ImageGetter.getCircle(Color.WHITE, s(24f))
        private val check = ImageGetter.getImage("OtherIcons/Checkmark", INK)
        private var shown: Boolean? = null

        init {
            pad(0f)
            touchable = Touchable.enabled
            val indicator = Group().apply {
                setSize(s(24f), s(24f))
                addActor(dot)
                check.setBounds(s(6f), s(6f), s(12f), s(12f))
                addActor(check)
            }
            add(label(item.toString(), 16f, translate = false).apply { wrap = true }).growX().left()
                .pad(s(14f), s(16f), s(14f), s(12f))
            add(indicator).size(s(24f)).padRight(s(16f))
            onClick {
                if (box.isDisabled) return@onClick
                @Suppress("UNCHECKED_CAST")
                (box as SelectBox<Any?>).selected = item
                onPicked()
            }
            restyle()
        }

        override fun act(delta: Float) {
            super.act(delta)
            if ((box.selected == item) != shown) restyle()
        }

        override fun getPrefHeight() = max(super.getPrefHeight(), target)

        private fun restyle() {
            val selected = box.selected == item
            shown = selected
            dot.color = if (selected) PortraitDialog.YELLOW.cpy() else Color(1f, 1f, 1f, .18f)
            check.isVisible = selected
        }
    }

    /** Bottom sheet listing every entry of [box], dismissed by picking, the close disc, or the scrim */
    /** A bottom sheet over the Stage, open only while [owner], the row that opened it, is shown */
    private class SheetOverlay(private val owner: Actor) : Group() {
        override fun act(delta: Float) {
            super.act(delta)
            if (!owner.isShown()) remove()
        }

        private fun Actor.isShown(): Boolean {
            if (stage == null) return false
            var actor: Actor? = this
            while (actor != null) {
                if (!actor.isVisible) return false
                actor = actor.parent
            }
            return true
        }
    }

    private fun openSheet(screen: BaseScreen, title: String, box: SelectBox<*>, owner: Actor) {
        val stage = screen.stage
        val canvas = screen.portraitCanvasBounds()
        val safe = screen.safeAreaBoundsInWorld()
        val overlay = SheetOverlay(owner).apply { setBounds(canvas.x, canvas.y, canvas.width, canvas.height) }
        val scrim = ImageGetter.getWhiteDot().apply {
            color = Color(10f / 255f, 22f / 255f, 34f / 255f, .6f)
            setBounds(0f, 0f, canvas.width, canvas.height)
            touchable = Touchable.enabled
            onClick { overlay.remove() }
        }
        overlay.addActor(scrim)

        val list = radios(box) { overlay.remove() }
        val scroll = AutoScrollPane(list).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val sheet = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("",
                BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape, SHEET)
            pad(s(8f), s(16f), s(16f) + (safe.y - canvas.y), s(16f))
            touchable = Touchable.enabled
            add(label(title, 18f)).growX().left().padLeft(s(4f))
            add(iconButton("OtherIcons/Close", 44f) { overlay.remove() }).size(target).row()
            add(scroll).colspan(2).width(contentWidth).padTop(s(8f))
        }
        val sheetHeight = minOf(sheet.prefHeight, safe.height * .84f)
        sheet.setBounds(safe.x - canvas.x, 0f, safe.width, sheetHeight)
        overlay.addActor(sheet)
        stage.addActor(overlay)
        sheet.layout()
        scroll.layout()
        val index = box.selectedIndex
        if (index >= 0) scroll.scrollY = index * (target + s(1f)) - scroll.height / 2f
    }
}
