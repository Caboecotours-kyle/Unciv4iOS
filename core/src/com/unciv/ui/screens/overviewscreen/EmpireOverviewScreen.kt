package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Notification
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.PortraitStatIcons
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.pickerscreens.PortraitMapBackdrop
import com.unciv.ui.screens.pickerscreens.portraitChromeGaps
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories.EmpireOverviewTabState
import com.unciv.view.CivView

class EmpireOverviewScreen(
    private var viewingPlayer: CivView,
    defaultCategory: EmpireOverviewCategories? = null,
    selection: String = ""
) : BaseScreen(), RecreateOnResize {
    // 50 normal button height + 2*10 topTable padding + 2 Separator + 2*5 centerTable padding
    // Since a resize recreates this screen this should be fine as a val
    internal var centerAreaHeight = stage.height - 82f
        private set

    /** Portrait shows a map-backed sheet with phone pages instead of the desktop tables */
    private val portrait = isPortrait()

    /** Landscape only */
    private val tabbedPager: TabbedPager?
    internal val pageObjects = HashMap<EmpireOverviewCategories, EmpireOverviewTab>()

    internal val persistState by game.settings::overview

    /** Portrait only */
    internal var portraitSheet: EmpireOverviewPortraitSheet? = null
        private set
    private var portraitBackdrop: PortraitMapBackdrop? = null
    internal val portraitStatIcons = PortraitStatIcons()
    private val portraitStates = HashMap<EmpireOverviewCategories, EmpireOverviewTabState>()

    override fun dispose() {
        tabbedPager?.selectPage(-1)
        portraitBackdrop?.dispose()
        portraitStatIcons.dispose()
        super.dispose()
    }

    override fun getCivilopediaRuleset() = viewingPlayer.ruleset

    init {
        val selectCategory = defaultCategory ?: persistState.last
        tabbedPager = if (portrait) null else initLandscape(selectCategory, selection)
        if (portrait) initPortrait(selectCategory, selection)

        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
    }

    private fun initLandscape(selectCategory: EmpireOverviewCategories, selection: String): TabbedPager {
        val iconSize = Constants.defaultFontSize.toFloat()

        val tabbedPager = TabbedPager(
            stage.width, stage.width,
            centerAreaHeight, centerAreaHeight,
            separatorColor = Color.WHITE,
            capacity = EmpireOverviewCategories.entries.size)

        for (category in EmpireOverviewCategories.entries) {
            val tabState = category.testState(viewingPlayer)
            if (tabState == EmpireOverviewTabState.Hidden) continue
            val icon = if (category.iconName.isEmpty()) null else ImageGetter.getImage(category.iconName)
            val pageObject = category.createTab(viewingPlayer, this, persistState[category])
            pageObject.pad(10f, 0f, 10f, 0f)
            pageObjects[category] = pageObject
            val index = tabbedPager.addPage(
                caption = category.name,
                content = pageObject,
                icon, iconSize,
                disabled = tabState != EmpireOverviewTabState.Normal,
                shortcutKey = category.shortcutKey,
                scrollAlign = category.scrollAlign
            )
            if (category == selectCategory) {
                tabbedPager.selectPage(index)
                select(tabbedPager, pageObject, selection)
            }
        }
        persistState.update(pageObjects)

        val closeButton = getCloseButton { game.popScreen() }
        tabbedPager.decorateHeader(closeButton)

        tabbedPager.setFillParent(true)
        stage.addActor(tabbedPager)
        return tabbedPager
    }

    /** Same categories, states and persisted data as landscape; pages are built when first shown. */
    private fun initPortrait(selectCategory: EmpireOverviewCategories, selection: String) {
        for (category in EmpireOverviewCategories.entries)
            portraitStates[category] = category.testState(viewingPlayer)

        val canvas = portraitCanvasBounds()
        portraitBackdrop = PortraitMapBackdrop(viewingPlayer.getCiv()).apply {
            setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
            this@EmpireOverviewScreen.stage.addActor(this)
        }
        val safe = safeAreaBoundsInWorld()
        val width = OverviewPortraitStyle.WIDTH
        val scale = safe.width / width
        val (topGap, bottomGap) = portraitChromeGaps(width)
        // The sheet runs under the home indicator; its rail keeps the targets above it
        val bottomInset = (safe.y - canvas.y).coerceAtLeast(0f) / scale
        val top = safe.y + safe.height - (topGap + 64f) * scale
        val sheet = EmpireOverviewPortraitSheet(this, portraitStates, bottomInset + bottomGap + 4f)
        sheet.isTransform = true
        sheet.setBounds(safe.x, canvas.y, width, (top - canvas.y) / scale)
        sheet.setScale(scale)
        portraitSheet = sheet
        stage.addActor(sheet)

        // The remembered tab can be disabled (no cities yet): open the first one that works
        val category = selectCategory.takeIf { sheet.isEnabled(it) }
            ?: EmpireOverviewCategories.entries.firstOrNull { sheet.isEnabled(it) }
        if (category == null) {
            sheet.showNothing()
            return
        }
        if (category == selectCategory && selection.isNotEmpty()) select(category, selection)
        else sheet.show(category)
    }

    internal fun getPortraitPage(category: EmpireOverviewCategories): PortraitOverviewPage {
        (pageObjects[category] as? PortraitOverviewPage)?.let { return it }
        val page = createPortraitPage(category, viewingPlayer, this, persistState[category])
        pageObjects[category] = page
        persistState.update(pageObjects)
        return page
    }

    override fun recreate(): BaseScreen {
        tabbedPager?.selectPage(-1)  // trigger deselect on _old_ instance so the tabs can persist their stuff
        portraitSheet?.saveScroll()
        return EmpireOverviewScreen(viewingPlayer, persistState.last)
    }

    fun resizePage(tab: EmpireOverviewTab) {
        if (portrait) {
            (tab as? PortraitOverviewPage)?.refresh()
            return
        }
        val category = (pageObjects.entries.find { it.value == tab } ?: return).key
        tabbedPager?.replacePage(category.name, tab)
    }

    fun select(category: EmpireOverviewCategories, selection: String) {
        val sheet = portraitSheet
        if (sheet != null) {
            if (!sheet.isEnabled(category)) return
            sheet.show(category)
            val page = getPortraitPage(category)
            page.focus = null
            page.select(selection)
            sheet.refreshPage(page)
            page.focus?.let { sheet.scrollTo(it) }
            return
        }
        val pager = tabbedPager ?: return
        pager.selectPage(category.name)
        select(pager, pageObjects[category], selection)
    }
    private fun select(pager: TabbedPager, tab: EmpireOverviewTab?, selection: String) {
        if (tab == null) return
        val scrollY = tab.select(selection) ?: return
        pager.setPageScrollY(pager.activePage, scrollY)
    }

    /** Helper to show the world screen with a temporary "one-time" notification */
    // Here because it's common to notification history, resource finder, and city WLTK demanded resource
    internal fun showOneTimeNotification(notification: Notification?) {
        if (notification == null) return  // Convenience - easier than a return@lambda for a caller
        val worldScreen = GUI.getWorldScreen()
        worldScreen.notificationsScroll.oneTimeNotification = notification
        GUI.resetToWorldScreen()
        notification.resetExecuteRoundRobin()
        notification.execute(worldScreen)
    }

    override fun resume() {
        // This is called by UncivGame.popScreen - e.g. after City Tab opened a City and the user closes that CityScreen...
        // Notify the current tab via its IPageExtensions.activated entry point so it can refresh if needed
        portraitSheet?.let { sheet ->
            // Portrait pages rebuild from the model, e.g. after a rename, promotion or a visit to a city
            val category = sheet.active ?: return
            (pageObjects[category] as? PortraitOverviewPage)?.refresh()
            return
        }
        val tabbedPager = tabbedPager ?: return
        val index = tabbedPager.activePage
        val category = EmpireOverviewCategories.entries.getOrNull(index) ?: return
        pageObjects[category]?.activated(index, "", tabbedPager) // Fake caption marks this as popScreen-triggered
    }
}
