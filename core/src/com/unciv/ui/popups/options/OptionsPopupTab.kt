package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.screens.basescreen.BaseScreen.Companion.skin

/**
 *  Common base for the page content Tables.
 *
 *  Provides the [OptionsPopupHelpers] functions and adds some shortcut accessors.
 *
 *  Shortcuts: [settings], [game], [activePage], [selectPage], [replacePage]
 *
 *  Sets table padding to 10 and cell default padding to 5.
 */
internal abstract class OptionsPopupTab(
    internal val optionsPopup: OptionsPopup,
) : Table(skin), TabbedPager.IPageExtensions, OptionsPopupHelpers {
    override val rightWidgetMinWidth by optionsPopup::rightWidgetMinWidth
    override val activePage by optionsPopup::activePage
    override val portrait: OptionsPortraitStyle? get() = optionsPopup.portraitStyle

    val settings by optionsPopup::settings
    val game by optionsPopup::game

    fun selectPage(name: String) = optionsPopup.tabs.selectPage(name)
    fun replacePage(factory: (OptionsPopup) -> OptionsPopupTab) =
        optionsPopup.tabs.replacePage(optionsPopup.tabs.activePage, factory(optionsPopup))

    private var isInitialized = false

    init {
        val style = portrait
        if (style == null) pad(10f)
        else pad(0f, style.px(12f), style.px(24f), style.px(12f)).top() // short pages start under the header
        defaults().pad(5f)
    }

    open fun lateInitialize() {
        isInitialized = true
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (isInitialized) return
        lateInitialize()
        portrait?.let { fitPortrait(it) }
    }

    /** Portrait pages are one phone wide: plain labels the page added directly wrap instead of clipping,
     *  buttons too wide for the page wrap their text, and buttons and select boxes get 48pt targets */
    private fun fitPortrait(style: OptionsPortraitStyle) {
        raiseButtons(this, style)
        val cellsPerRow = cells.groupingBy { it.row }.eachCount()
        val pageWidth = optionsPopup.tabMinWidth - padLeft - padRight
        for (cell in cells) {
            val perRow = cellsPerRow[cell.row] ?: 1
            val label = when (val actor = cell.actor) {
                is Label -> actor
                // Only a button wider than its share of the page wraps, the rest keep their natural width
                is TextButton -> if (actor.prefWidth > pageWidth / perRow) actor.label else continue
                else -> continue
            }
            if (label.wrap) continue
            label.wrap = true
            cell.fillX()
            // A wrapped label beside another widget has no width of its own and would collapse to one letter per line
            if (perRow != 1) cell.minWidth(style.px(110f))
        }
    }

    private fun raiseButtons(table: Table, style: OptionsPortraitStyle) {
        for (cell in table.cells) when (val actor = cell.actor) {
            is Button -> cell.minHeight(style.px(48f))
            is SelectBox<*> -> {
                cell.minHeight(style.px(48f))
                style.fitSelect(actor)
            }
            is PortraitRow -> {}
            is Table -> raiseButtons(actor, style)
        }
    }

    open fun subSelect(select: String?) {}
}
