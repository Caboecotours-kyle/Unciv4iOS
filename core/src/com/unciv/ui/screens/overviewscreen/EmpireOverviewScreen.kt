package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Notification
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
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

    /** Portrait wraps the tab buttons into rows so every tab is in view */
    private val portrait = isPortrait()

    private val tabbedPager: TabbedPager
    private val pageObjects = HashMap<EmpireOverviewCategories, EmpireOverviewTab>()

    internal val persistState by game.settings::overview

    override fun dispose() {
        tabbedPager.selectPage(-1)
        super.dispose()
    }

    override fun getCivilopediaRuleset() = viewingPlayer.ruleset

    init {
        val selectCategory = defaultCategory ?: persistState.last
        val iconSize = Constants.defaultFontSize.toFloat()

        tabbedPager = TabbedPager(
            stage.width, stage.width,
            centerAreaHeight, centerAreaHeight,
            separatorColor = Color.WHITE,
            capacity = EmpireOverviewCategories.entries.size,
            wrapHeaderWidth = if (portrait) stage.width else 0f)

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
                // wide tables start at their first column rather than centered past both edges
                scrollAlign = if (portrait) Align.topLeft else category.scrollAlign
            )
            if (category == selectCategory) {
                tabbedPager.selectPage(index)
                select(pageObject, selection)
            }
        }
        persistState.update(pageObjects)

        val closeButton = getCloseButton { game.popScreen() }
        tabbedPager.decorateHeader(closeButton)
        if (portrait) {
            // header rows plus separator and padding, where the landscape estimate above assumes one row
            centerAreaHeight = stage.height - tabbedPager.minHeight - 12f
            // the remembered tab can be disabled (no cities yet): open the first one that works
            if (tabbedPager.activePage < 0)
                (0 until tabbedPager.pageCount()).firstOrNull { tabbedPager.selectPage(it) }
        }

        tabbedPager.setFillParent(true)
        stage.addActor(tabbedPager)

        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
   }

    override fun recreate(): BaseScreen {
        tabbedPager.selectPage(-1)  // trigger deselect on _old_ instance so the tabs can persist their stuff
        return EmpireOverviewScreen(viewingPlayer, persistState.last)
    }

    fun resizePage(tab: EmpireOverviewTab) {
        val category = (pageObjects.entries.find { it.value == tab } ?: return).key
        tabbedPager.replacePage(category.name, tab)
    }

    fun select(category: EmpireOverviewCategories, selection: String) {
        tabbedPager.selectPage(category.name)
        select(pageObjects[category], selection)
    }
    private fun select(tab: EmpireOverviewTab?, selection: String) {
        if (tab == null) return
        val scrollY = tab.select(selection) ?: return
        tabbedPager.setPageScrollY(tabbedPager.activePage, scrollY)
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
        val index = tabbedPager.activePage
        val category = EmpireOverviewCategories.entries.getOrNull(index) ?: return
        pageObjects[category]?.activated(index, "", tabbedPager) // Fake caption marks this as popScreen-triggered
    }
}
