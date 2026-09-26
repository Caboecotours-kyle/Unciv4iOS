package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.map.tile.Tile
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen.ImprovementOption
import kotlin.math.roundToInt

/**
 * The portrait, one-handed [ImprovementPickerScreen] (mock `mocks/portrait-options-proposal.html`):
 * cards grouped by what can be ordered now, the current order with its cancel on top, and the selected card with
 * Details and its action pinned at the bottom. Locked cards can be inspected but never ordered.
 * Every tap target is at least [TOUCH] logical, which stays 48pt after the view's 320/393 scale on the narrowest phone.
 *
 * Eligibility, problems and ordering stay in [ImprovementPickerScreen]; this view only shows them and calls back.
 */
internal class ImprovementPickerPortrait(
    private val screen: ImprovementPickerScreen,
    private val tile: Tile,
    options: List<ImprovementOption>,
    contentWidth: Float,
    private val bottomGap: Float,
) : Table() {
    private val cancelOption = options.firstOrNull { it.improvement.name == Constants.cancelImprovementOrder }
    private val cardOptions = options.filter { it !== cancelOption }
    private val listWidth = contentWidth - 24f
    // Card: 2.5 outline each side, icon with its padding, 8 before the divider, the Details column
    private val middleWidth = listWidth - 5f - 80f - 8f - 1f - DETAILS_WIDTH
    private val footerWidth = contentWidth - 24f
    private val cards = HashMap<String, Card>()
    private val footer = Table()
    private var selected: ImprovementOption? = null

    init {
        val body = Table().top()
        body.pad(0f, 12f, 16f, 12f)
        body.add(ownerRow()).left().row()
        if (cancelOption != null) body.add(currentOrder(cancelOption)).width(listWidth).padTop(6f).padBottom(4f).row()
        val available = cardOptions.filter { it.canOrder }
        val locked = cardOptions.filter { !it.canOrder }
        for ((title, group) in listOf("Available now" to available, "Requirements" to locked)) {
            if (group.isEmpty()) continue
            body.add(title.tr().uppercase().toLabel(INK3, 14)).left().pad(14f, 6f, 8f, 0f).row()
            for (option in group) {
                val card = Card(option)
                cards[option.improvement.name] = card
                body.add(card).width(listWidth).padBottom(10f).row()
            }
        }

        add(header()).growX().row()
        add(AutoScrollPane(body).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }).grow().prefHeight(0f).row()
        footer.background = solid(FOOTER)
        add(footer).growX()

        val initial = cardOptions.firstOrNull { it.inProgress }
            ?: available.firstOrNull() ?: cardOptions.firstOrNull()
        if (initial != null) select(initial)
    }

    /** Picks a card for the footer; ordering only ever happens from the footer's action */
    fun select(option: ImprovementOption) {
        selected = option
        screen.selectPortrait(option.improvement)
        for ((name, card) in cards) card.setPicked(name == option.improvement.name)
        rebuildFooter()
    }

    private fun header() = Table().apply {
        pad(0f, 16f, 2f, 4f)
        add("Pick improvement".toLabel(fontSize = 22)).growX().left()
        val close = ImageGetter.getImage("OtherIcons/Close").apply { setSize(18f, 18f) }
            .surroundWithCircle(48f, false, CHIP)
        add(hitArea(close, (TOUCH - 48f) / 2, (TOUCH - 48f) / 2) { screen.game.popScreen() })
    }

    //region Body

    private fun ownerRow(): Actor {
        val owner = tile.getOwner()
        if (owner == null) return "Unowned tile".toLabel(INK2, 16).apply { setAlignment(Align.left) }
        val city = tile.getCity()!!
        val chips = mutableListOf<Actor>()
        val ownerText = if (owner.isCurrentPlayer()) "Tile owned by [${owner.civName}] (You)"
            else "Tile owned by [${owner.civName}] - [${city.name}]"
        chips += chip(ownerText, ImageGetter.getNationPortrait(owner.nation, 22f)) {
            screen.openCivilopedia(owner.nation.makeLink())
        }
        if (owner.isCurrentPlayer())
            chips += chip(city.name, ImageGetter.getImage("OtherIcons/Cities").apply { setSize(20f, 20f) }) {
                screen.openOwnerCity()
            }
        // The chips' own hit slop spaces the rows
        return flow(chips, listWidth, 8f, rowGap = 0f)
    }

    private fun chip(text: String, icon: Actor, action: () -> Unit): Actor {
        val visual = Table()
        visual.background = rounded(CHIP_BLUE, BaseScreen.skinStrings.roundedEdgeRectangleShape)
        visual.pad(0f, 14f, 0f, 16f)
        visual.add(icon).size(22f).padRight(8f)
        val label = text.toLabel(Color.WHITE, 15, hideIcons = true)
        val maxText = listWidth - 60f
        if (label.prefWidth > maxText) {
            label.wrap = true
            visual.add(label).width(maxText).minHeight(48f)
        } else visual.add(label).minHeight(48f)
        return hitArea(visual, 0f, (TOUCH - 48f) / 2, action)
    }

    /** A tappable parent adding invisible hit area around [visual], so it is drawn as designed but tapped at [TOUCH] */
    private fun hitArea(visual: Actor, padX: Float, padY: Float, action: () -> Unit) = Table().apply {
        touchable = Touchable.enabled
        add(visual).pad(padY, padX, padY, padX)
        onClick(action)
    }

    /** The order already running on this tile, with the ruleset's cancel entry as a full-width action */
    private fun currentOrder(cancel: ImprovementOption) = Table().apply {
        background = rounded(ORDER, BaseScreen.skinStrings.roundedEdgeRectangleShape)
        pad(14f)
        val inProgress = tile.improvementInProgress ?: ""
        val top = Table()
        top.add(iconTile(inProgress, 58f, locked = false)).size(58f).padRight(12f)
        val text = Table()
        text.add("Current construction".tr().uppercase().toLabel(ORDER_INK, 13)).left().row()
        text.add(wrapped(inProgress.tr(true), Color.WHITE, 18, listWidth - 98f)).width(listWidth - 98f).left().row()
        if (tile.turnsToImprovement > 0)
            text.add("[${tile.turnsToImprovement}] turns".toLabel(INK2, 14)).left()
        top.add(text).growX().left()
        add(top).growX().row()
        val enabled = cancel.canOrder && GUI.isMyTurn()
        val button = bigButton(cancel.improvement.name.tr(true), if (enabled) DANGER else OFF,
            if (enabled) DANGER_INK else INK3)
        if (enabled) button.onClick { screen.accept(cancel.improvement) }
        // Same as landscape: the shortcut works whenever the entry has no problems
        if (cancel.shortcutKey != null && cancel.canOrder)
            button.keyShortcuts.add(cancel.shortcutKey) { screen.accept(cancel.improvement) }
        add(button).growX().padTop(12f)
    }

    private inner class Card(val option: ImprovementOption) : Table() {
        private val inner = Table()

        init {
            touchable = Touchable.enabled
            pad(2.5f)
            inner.pad(0f) // the picked background's nine-patch padding would push it past the card
            inner.add(iconTile(option.improvement.name, 58f, !option.canOrder)).size(58f).pad(10f, 10f, 10f, 12f)
            val middle = Table()
            middle.add(wrapped(option.improvement.name.tr(true), Color.WHITE, 18, middleWidth)).width(middleWidth).left().row()
            middle.add(flow(tags(option, middleWidth, inFooter = false), middleWidth)).left().padTop(6f)
            inner.add(middle).width(middleWidth).padTop(10f).padBottom(10f).padRight(8f)
            inner.add(Image(solid(LINE))).width(1f).growY()
            inner.add(detailsColumn()).width(DETAILS_WIDTH).growY()
            add(inner).growX().minHeight(80f)
            onClick { select(option) }
            // Same as landscape: a shortcut orders an improvement that has no problems, without selecting it first
            val shortcutKey = option.shortcutKey
            if (shortcutKey != null && option.report.isEmpty() && !screen.tileMarkedForCreatesOneImprovement)
                keyShortcuts.add(shortcutKey) { screen.accept(option.improvement) }
            setPicked(false)
        }

        /** The whole column is the target; it opens Civilopedia without selecting the card */
        private fun detailsColumn() = Table().apply {
            touchable = Touchable.enabled
            add(Table().apply {
                background = rounded(CHIP_BLUE)
                pad(0f)
                add("Details".toLabel(Color.WHITE, 14)).pad(0f, 10f, 0f, 10f).minHeight(44f)
            })
            onClickSuppressive { screen.openCivilopedia(option.improvement.makeLink()) }
        }

        fun setPicked(picked: Boolean) {
            background = rounded(if (picked) YELLOW else CARD)
            inner.background = if (picked) rounded(CARD_PICKED) else null
        }
    }

    //endregion
    //region Footer

    private fun rebuildFooter() {
        footer.clear()
        val option = selected ?: return
        footer.pad(12f, 12f, 12f + bottomGap, 12f)

        val summary = Table()
        summary.add(iconTile(option.improvement.name, 48f, !option.canOrder)).size(48f).padRight(12f)
        val textWidth = footerWidth - 60f
        val text = Table()
        text.add(wrapped(option.improvement.name.tr(true), Color.WHITE, 19, textWidth)).width(textWidth).left().row()
        val tags = tags(option, textWidth, inFooter = true)
        if (tags.isNotEmpty()) text.add(flow(tags, textWidth)).left().padTop(6f)
        summary.add(text).width(textWidth).left()
        footer.add(summary).width(footerWidth).padBottom(10f).row()

        if (option.replaces != null) {
            val crossed = if (option.removesResource)
                ImageGetter.getCrossedImage(ImageGetter.getResourcePortrait(tile.resource!!, 18f), 18f) else null
            footer.add(note("Replaces [${option.replaces}]", crossed)).width(footerWidth).padBottom(8f).row()
        }
        if (option.outsideWorkRange)
            footer.add(note("Not in city work range", null)).width(footerWidth).padBottom(8f).row()
        // What stands in the way; a queueable option lists the terrain removal that the action orders first
        for ((solution, link) in option.report.proposedSolutions)
            footer.add(solutionRow(solution, link, queued = option.canOrder)).width(footerWidth).padBottom(8f).row()

        val actions = Table()
        val details = bigButton("Details", CHIP_BLUE, Color.WHITE)
        details.onClick { screen.openCivilopedia(option.improvement.makeLink()) }
        val primary = primaryAction(option)
        if (primary == null) actions.add(details).growX()
        else {
            actions.add(details).width(DETAILS_BUTTON_WIDTH).padRight(10f)
            actions.add(primary).growX()
        }
        footer.add(actions).width(footerWidth).padTop(2f)
    }

    /** Yellow order for anything orderable, the tech tree for research; nothing that could build a locked card */
    private fun primaryAction(option: ImprovementOption): Actor? {
        val name = option.improvement.name
        if (option.canOrder) {
            val text = when {
                option.inProgress -> "Keep building [$name]"
                option.replaces != null -> "Replace with [$name]"
                else -> "Build [$name]"
            }
            val enabled = GUI.isMyTurn()
            val button = bigButton(text.tr(hideIcons = true), if (enabled) YELLOW else OFF, if (enabled) YELLOW_INK else INK3)
            if (enabled) button.onClick { order(option) }
            return button
        }
        val tech = option.report.missingTechs.firstOrNull() ?: return null
        val button = bigButton("Show [${tech.name}] in tech tree".tr(hideIcons = true), CHIP_BLUE, Color.WHITE)
        button.onClick { screen.game.pushScreen { TechPickerScreen(screen.currentPlayerCiv, tech) } }
        return button
    }

    private fun order(option: ImprovementOption) {
        if (!option.canOrder || selected !== option) return
        val report = option.report
        val run = {
            if (report.isEmpty()) screen.accept(option.improvement)
            else screen.accept(report.removalImprovement, option.improvement)
        }
        if (option.replaces == null || option.inProgress) run()
        else ConfirmPopup(screen, "Replace [${option.replaces}] with [${option.improvement.name}]?", "Replace",
            isConfirmPositive = true, action = run).open()
    }

    private fun note(text: String, icon: Actor?) = Table().apply {
        background = rounded(WARN_BG)
        pad(10f, 12f, 10f, 12f)
        add(icon ?: ImageGetter.getImage("OtherIcons/ExclamationMark").apply { color = WARN }).size(18f).padRight(8f).top()
        add(wrapped(text, WARN_INK, 15, footerWidth - 50f)).width(footerWidth - 50f).left()
    }

    private fun solutionRow(text: String, link: String?, queued: Boolean) = Table().apply {
        background = rounded(if (queued) WARN_BG else CHIP_BLUE)
        pad(0f, 12f, 0f, 12f)
        val icon = if (queued) ImageGetter.getImage("OtherIcons/ExclamationMark").apply { color = WARN }
            else ImageGetter.getImage("OtherIcons/LockSmall").apply { color = INK2 }
        add(icon).size(16f).padRight(10f)
        add(wrapped(text, if (queued) WARN_INK else Color.WHITE, 15, footerWidth - 90f)).width(footerWidth - 90f).left().minHeight(TOUCH)
        if (!link.isNullOrEmpty()) {
            touchable = Touchable.enabled
            add("›".toLabel(INK2, 22)).padLeft(8f)
            onClick { screen.openCivilopedia(link) }
        }
    }

    //endregion
    //region Pieces

    /** Status, turns, yield change and resource for a card; the footer leaves out status and warnings it shows as rows */
    private fun tags(option: ImprovementOption, width: Float, inFooter: Boolean): List<Actor> {
        val tags = mutableListOf<Actor>()
        if (!inFooter) when {
            option.inProgress -> tags += tag("Current construction", YELLOW, YELLOW_INK, width)
            option.canOrder && option.report.isEmpty() -> tags += tag("Available", FOOD, FOOD_INK, width,
                ImageGetter.getImage("OtherIcons/Checkmark").apply { color = FOOD_INK })
            else -> for ((solution, _) in option.report.proposedSolutions)
                tags += if (option.canOrder) tag(solution, WARN_BG, WARN_INK, width)
                    else tag(solution, LOCK_BG, LOCK_INK, width, ImageGetter.getImage("OtherIcons/LockSmall").apply { color = LOCK_INK })
        }
        if (option.turns > 0) tags += tag("${Fonts.turn} " + "[${option.turns}] turns".tr(), TAG, INK2, width)
        tags += statTags(option.stats, width)
        if (option.providesResource)
            tags += tag("Provides [${tile.resource}]", RES_BG, RES_INK, width, ImageGetter.getResourcePortrait(tile.resource!!, 16f))
        if (!inFooter) {
            if (option.replaces != null) tags += tag("Replaces [${option.replaces}]", WARN_BG, WARN_INK, width)
            if (option.outsideWorkRange) tags += tag("Not in city work range", WARN_BG, WARN_INK, width)
        }
        return tags
    }

    private fun statTags(stats: Stats, width: Float): List<Actor> = stats.mapNotNull { (stat, value) ->
        val rounded = (value * 10).roundToInt() * 0.1f
        if (rounded == 0f) return@mapNotNull null
        val positive = rounded > 0f
        tag((if (positive) "+" else "") + rounded.tr(), if (positive) FOOD_BG else NEG_BG, if (positive) FOOD else NEG, width,
            ImageGetter.getStatIcon(stat.name))
    }

    private fun tag(text: String, fill: Color, ink: Color, maxWidth: Float, icon: Actor? = null) = Table().apply {
        background = rounded(fill, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape)
        pad(4f, 8f, 4f, 8f)
        if (icon != null) add(icon).size(16f).padRight(5f)
        val label = text.tr(hideIcons = true).toLabel(ink, 14)
        val maxText = maxWidth - 16f - (if (icon != null) 21f else 0f)
        if (label.prefWidth > maxText) {
            label.wrap = true
            add(label).width(maxText)
        } else add(label)
    }

    private fun iconTile(name: String, size: Float, locked: Boolean) = Table().apply {
        background = rounded(if (locked) ICON_LOCKED else ICON_BG)
        pad(0f)
        val portrait = ImageGetter.getImprovementPortrait(name, size - 10f)
        if (locked) portrait.color.a = .62f
        add(portrait).size(size - 10f)
    }

    private fun bigButton(text: String, fill: Color, ink: Color) = Table().apply {
        background = rounded(fill, BaseScreen.skinStrings.roundedEdgeRectangleShape)
        touchable = Touchable.enabled
        pad(4f, 18f, 4f, 18f)
        add(text.toLabel(ink, 18, Align.center, hideIcons = true).apply { wrap = true })
            .growX().minHeight(TOUCH - 8f)
    }

    /** A wrapping label already sized to [width], so its height is right before the first layout */
    private fun wrapped(text: String, color: Color, size: Int, width: Float): Label =
        text.toLabel(color, size, hideIcons = true).apply {
            wrap = true
            this.width = width
            height = prefHeight
        }

    /** Lays [items] out left to right, wrapping into rows no wider than [width] */
    private fun flow(items: List<Actor>, width: Float, gap: Float = 6f, rowGap: Float = gap): Table {
        val table = Table().left()
        var row = Table()
        var used = 0f
        for (item in items) {
            val itemWidth = (item as? Layout)?.prefWidth ?: item.width
            if (used > 0f && used + gap + itemWidth > width) {
                table.add(row).left().padBottom(rowGap).row()
                row = Table()
                used = 0f
            }
            val cell = row.add(item)
            if (used > 0f) {
                cell.padLeft(gap)
                used += gap
            }
            used += itemWidth
        }
        table.add(row).left()
        return table
    }

    //endregion

    companion object {
        /** 60 logical is 48pt at the 320/393 portrait scale, DESIGN.md's minimum touch target */
        const val TOUCH = 60f
        const val DETAILS_WIDTH = 78f
        const val DETAILS_BUTTON_WIDTH = 112f

        // The approved mock's palette (DESIGN.md "Interface"): navy page and cards, yellow for picked and the primary action
        val PAGE: Color = Color.valueOf("132435")
        private val FOOTER = Color(16f / 255f, 31f / 255f, 47f / 255f, .96f)
        private val CARD = Color.valueOf("182d43")
        private val CARD_PICKED = Color.valueOf("213a55")
        private val CHIP = Color(1f, 1f, 1f, .08f)
        private val CHIP_BLUE = Color.valueOf("264262")
        private val ICON_BG = Color.valueOf("2d4866")
        private val ICON_LOCKED = Color.valueOf("243a52")
        private val LINE = Color(1f, 1f, 1f, .1f)
        private val TAG = Color(1f, 1f, 1f, .08f)
        private val OFF = Color(1f, 1f, 1f, .08f)
        private val YELLOW = Color.valueOf("ffc93c")
        private val YELLOW_INK = Color.valueOf("3a2a00")
        private val INK2 = Color.valueOf("b7cde0")
        private val INK3 = Color.valueOf("7f9ab2")
        private val FOOD = Color.valueOf("6fd05a")
        private val FOOD_INK = Color.valueOf("1d3a10")
        private val FOOD_BG = Color(111f / 255f, 208f / 255f, 90f / 255f, .14f)
        private val NEG = Color.valueOf("ff7a6e")
        private val NEG_BG = Color(224f / 255f, 82f / 255f, 74f / 255f, .16f)
        private val LOCK_BG = Color.valueOf("314c6b")
        private val LOCK_INK = Color.valueOf("dce8f3")
        private val RES_BG = Color(1f, 201f / 255f, 60f / 255f, .14f)
        private val RES_INK = Color.valueOf("ffe7a8")
        private val WARN = Color.valueOf("ffb14a")
        private val WARN_BG = Color(1f, 177f / 255f, 74f / 255f, .13f)
        private val WARN_INK = Color.valueOf("ffd9a3")
        private val ORDER = Color.valueOf("24402f")
        private val ORDER_INK = Color.valueOf("b8e35a")
        private val DANGER = Color(224f / 255f, 82f / 255f, 74f / 255f, .22f)
        private val DANGER_INK = Color.valueOf("ff7a6e")

        fun solid(color: Color): Drawable = ImageGetter.getWhiteDotDrawable().tint(color)

        fun rounded(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleMidShape): Drawable =
            BaseScreen.skinStrings.getUiBackground("", shape, color)
    }
}
