package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.PortraitDialog
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import kotlin.math.floor

/**
 * The Options (Settings) Popup
 *
 * In portrait it becomes a full-screen [OptionsPortraitView]: a category home plus one page at a time.
 * A plain open (no [selectPage]) shows the home, an explicit page (or a reload) opens that page directly.
 * @param screen The caller - note if this is a [WorldScreen] or [MainMenuScreen] they will be rebuilt when major options change.
 */
//region Fields
class OptionsPopup(
    screen: BaseScreen,
    /** `null`: no page requested, portrait shows the home and landscape opens [defaultPage] */
    private val selectPage: OptionsPopupPages? = null,
    withDebug: Boolean = false,
    private val onClose: () -> Unit = {},
    private val subSelect: String? = null
) : Popup(screen.stage, /** [TabbedPager] handles scrolling */ scrollable = Scrollability.None), OptionsPopupHelpers {

    val game = screen.game
    val settings = screen.game.settings
    lateinit var tabs: TabbedPager
        private set
    // Pager indices follow the visible tabs, not the full enum order.
    private val visiblePages = OptionsPopupPages.entries.filter { it.visible(withDebug) }
    private val pageIndex = HashMap<OptionsPopupPages, OptionsPopupTab>()
    override val activePage get() = visiblePages[tabs.activePage]
    override var rightWidgetMinWidth = 0f
        private set
    internal var tabMinWidth = 0f
        private set

    /** Portrait look for the pages, `null` in landscape. Rebuilt when the orientation flips while open */
    internal var portraitStyle: OptionsPortraitStyle? = null
        private set
    private var portraitView: OptionsPortraitView? = null
    private val landscapeBackground = background
    private val landscapeCardBackground = innerTable.background
    private var initialPageShown = false
    /** Unsubmitted text of each page's fields, kept when rotation rebuilds the pages and put back when the page next opens */
    private val drafts = HashMap<OptionsPopupPages, List<String>>()

    //endregion

    companion object {
        val defaultPage = OptionsPopupPages.Gameplay
    }

    init {
        if (settings.addCompletedTutorialTask("Open the options table"))
            (screen as? WorldScreen)?.shouldUpdate = true

        innerTable.pad(0f)
        build()

        pack() // Needed to show the background.
        center(screen.stage)
    }

    /** (Re)creates pager and pages for the current orientation */
    private fun build() {
        innerTable.clear()
        pageIndex.clear()
        val stage = stageToShowOn
        val style = if (PortraitDialog.isPortrait(stage)) OptionsPortraitStyle(stage) else null
        portraitStyle = style
        clickBehindToClose = style == null

        val tabMaxWidth: Float
        val tabMaxHeight: Float
        if (style == null) {
            rightWidgetMinWidth = if (stage.width < 600f) 200f else 240f
            tabMaxWidth = 0.8f * stage.width
            tabMinWidth = 0.6f * stage.width
            tabMaxHeight = 0.8f * stage.height
        } else {
            rightWidgetMinWidth = 150f
            // The sheet spans the visible area, pages scroll vertically only
            tabMaxWidth = floor((stage as? UncivStage)?.lastKnownVisibleArea?.width ?: stage.width)
            tabMinWidth = tabMaxWidth
            tabMaxHeight = stage.height
        }
        // Since all pages now initialize their content late, on activation, we can't measure their preferred size anymore -> use tabMaxHeight for tabMinHeight
        // That's not really bad, the tabs are long enough so some will always need scrolling even on the largest UI size setting.
        tabs = TabbedPager(
            tabMinWidth, tabMaxWidth, if (style == null) tabMaxHeight else 0f, tabMaxHeight,
            headerFontSize = 21, backgroundColor = Color.CLEAR, capacity = 8
        )
        tabs.onSelection { index, _, _ -> restoreDrafts(visiblePages[index]) }

        for (page in visiblePages) {
            val content = page.getContent(this)
            tabs.addPage(page.label, content, page.getIcon(settings.language), 24f)
            pageIndex[page] = content
        }

        if (style == null) {
            portraitView = null
            background = landscapeBackground
            innerTable.background = landscapeCardBackground
            cells.first().expand(false, false).fill(false, false).maxSize(maxPopupWidth, maxPopupHeight)
            add(tabs).pad(0f).grow().row()
            tabs.decorateHeader(getCloseButton { close() })
        } else {
            // The portrait view supplies title, back and close; the pager only shows the page
            tabs.headerScroll.isVisible = false
            tabs.cells[0].height(0f)
            tabs.background = null
            (tabs.cells.last().actor as ScrollPane).setScrollingDisabled(true, false)
            val view = OptionsPortraitView(this, style, tabs, visiblePages)
            portraitView = view
            background = style.screen
            innerTable.background = null
            cells.first().grow().maxSize(0f)
            add(view).grow().pad(0f)
        }
    }

    /** Rotation while open: rebuild for the new orientation, keep the page or the home list */
    private fun syncOrientation() {
        if (PortraitDialog.isPortrait(stageToShowOn) == (portraitStyle != null)) return
        val page = if (tabs.activePage >= 0) visiblePages[tabs.activePage] else null
        tabs.selectPage(-1, false)
        // Pages not opened since the last rotation still hold their earlier drafts
        for ((tabPage, tab) in pageIndex)
            if (tabPage !in drafts) textFields(tab).takeIf { it.isNotEmpty() }?.let { fields -> drafts[tabPage] = fields.map { it.text } }
        build()
        pack()
        if (portraitStyle == null) fitOrCenterContentIntoVisibleArea()
        else fitSheet((stageToShowOn as? UncivStage)?.lastKnownVisibleArea)
        if (initialPageShown) showPage(page)
    }

    private fun textFields(tab: OptionsPopupTab): List<TextField> {
        val fields = ArrayList<TextField>()
        fun collect(actor: Actor?) {
            if (actor is TextField) fields += actor
            if (actor is Group) for (child in actor.children) collect(child)
        }
        collect(tab.getFixedContent())
        collect(tab)
        return fields
    }

    /** Puts a rebuilt page's drafts back. The fields fire their change event as typing does, so validation and
     *  dependent buttons follow and nothing is saved that typing wouldn't have saved */
    private fun restoreDrafts(page: OptionsPopupPages) {
        val texts = drafts.remove(page) ?: return
        val fields = textFields(pageIndex[page] ?: return)
        if (fields.size != texts.size) return // a different set of fields, don't guess which draft goes where
        for ((field, text) in fields.zip(texts)) {
            if (field.text == text) continue
            field.text = text
            if (!field.programmaticChangeEvents) field.fire(ChangeListener.ChangeEvent())
        }
    }

    /** Portrait sheet covers exactly the visible area */
    private fun fitSheet(visibleArea: Rectangle?) {
        val area = visibleArea ?: Rectangle(0f, 0f, stageToShowOn.width, stageToShowOn.height)
        padLeft(area.x)
        padBottom(area.y)
        padRight(stageToShowOn.width - area.x - area.width)
        padTop(stageToShowOn.height - area.y - area.height)
        invalidate()
    }

    /** Shows [page], or in portrait the home list when [page] is `null` */
    private fun showPage(page: OptionsPopupPages?) {
        val view = portraitView
        when {
            view == null -> tabs.selectPage(visiblePages.indexOf(page ?: defaultPage))
            page == null -> view.showHome()
            else -> view.showPage(page)
        }
    }

    override fun act(delta: Float) {
        if (stage != null) syncOrientation()
        super.act(delta)
    }

    override fun onVisibleAreaChanged(visibleArea: Rectangle) {
        syncOrientation()
        if (portraitStyle != null) {
            fitSheet(visibleArea)
            return
        }
        if (com.badlogic.gdx.Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.iOS) {
            tabs.resizePageArea(stageToShowOn.width * 0.8f, visibleArea.height * 0.8f)
        }
        super.onVisibleAreaChanged(visibleArea)
    }

    override fun close() {
        game.musicController.onChange(null)
        center(stageToShowOn)
        tabs.selectPage(-1, false)
        settings.save()
        onClose() // activate the passed 'on close' callback
        super.close()
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (!visible || initialPageShown) return
        initialPageShown = true
        val targetPage = if (selectPage != null && selectPage in pageIndex) selectPage else defaultPage
        pageIndex[targetPage]?.subSelect(subSelect)
        // A plain open lands on the portrait home; explicit pages and reloads keep their page
        val openHome = portraitView != null && selectPage == null
        showPage(if (openHome) null else targetPage)
    }
}
