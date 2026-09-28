package com.unciv.ui.screens.civilopediascreen

import com.unciv.ui.screens.basescreen.portraitCanvasBounds

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.stats.INamed
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.toImageButton
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.TrayArt
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.popups.tinted
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/** Screen displaying the Civilopedia
 * @param ruleset [Ruleset] to display items from
 * @param category [CivilopediaCategories] key to select category
 * @param link alternate selector to select category and/or entry. Can have the form `category/entry`
 *             overriding the [category] parameter, or just `entry` to complement it.
 */
class CivilopediaScreen(
    val ruleset: Ruleset,
    category: CivilopediaCategories = CivilopediaCategories.Tutorial,
    link: String = ""
) : BaseScreen(), RecreateOnResize {

    /** Container collecting data per Civilopedia entry
     * @property name From [Ruleset] object [INamed.name]
     * @property image Icon for button
     * @property flavour Original [ICivilopediaText] reference allowing to render its [ICivilopediaText.civilopediaText]
     * @property y Y coordinate for scrolling to
     * @property height Cell height for scrolling to
     * @property sortBy Optional, enables overriding alphabetical order. Ususally supplied by [ICivilopediaText.getSortGroup]
     * @property subCategory Optional, enablesg grouping with sub-category labels. Ususally supplied by [ICivilopediaText.getSubCategory]
     */
    private class CivilopediaEntry (
        val name: String,
        val image: Actor? = null,
        val flavour: ICivilopediaText? = null,
        val y: Float = 0f,
        val height: Float = 0f,
        val sortBy: Int = 0,
        val subCategory: String? = null
    ) {
        constructor(ruleset: Ruleset, item: ICivilopediaText, category: CivilopediaCategories, imageSize: Float) : this(
            (item as INamed).name,
            category.getImage?.invoke(item.getIconName(), imageSize),
            flavour = item,
            sortBy = item.getSortGroup(ruleset),
            subCategory = item.getSubCategory(ruleset)
        )

        fun withCoordinates(y: Float, height: Float) = CivilopediaEntry(name, image, flavour, y, height, sortBy, subCategory)
    }

    private val categoryToEntries = LinkedHashMap<CivilopediaCategories, Collection<CivilopediaEntry>>()
    private class CategoryButtonInfo(val button: Button, val x: Float, val width: Float)
    private val categoryToButtons = LinkedHashMap<CivilopediaCategories, CategoryButtonInfo>()
    private val entryIndex = LinkedHashMap<String, CivilopediaEntry>()

    private val buttonTableScroll: ScrollPane

    private val entrySelectTable = Table().apply { defaults().pad(6f).left() }
    private val entrySelectScroll: ScrollPane
    private val flavourTable = Table()

    private var currentCategory: CivilopediaCategories = CivilopediaCategories.Tutorial
    private var currentEntry: String = ""
    private val currentEntryPerCategory = HashMap<CivilopediaCategories, String>()

    /** Portrait is a library on a tray sheet: a home view of shelves, a list per category, entries */
    private val portrait = isPortrait()
    private enum class PortraitView { Home, List, Entry }
    private var portraitView = PortraitView.Home
    private val trayHost by lazy { TrayHost(this) }
    private val sheet get() = trayHost.sheet

    private val searchPopup by lazy { CivilopediaSearchPopup(this) {
        selectLink(it)
    } }


    /** Jump to a "link" selecting both category and entry
     *
     * Calls [selectCategory] with the substring before the first '/',
     *
     * and [selectEntry] with the substring after the first '/'
     *
     * @param link Link in the form Category/Entry
     */
    private fun selectLink(link: String) {
        val parts = link.split('/', limit = 2)
        if (parts.isEmpty()) return
        selectCategory(parts[0])
        if (parts.size >= 2) selectEntry(parts[1], noScrollAnimation = true)
    }

    /** Select a specified category
     * @param name Category name or label
     */
    private fun selectCategory(name: String) {
        val category = CivilopediaCategories.fromLink(name)
            ?: return       // silently ignore unknown category names in links
        selectCategory(category)
    }

    /** Select a specified category - unselects entry, rebuilds left side buttons.
     * @param category Category key
     */
    private fun selectCategory(category: CivilopediaCategories) {
        currentCategory = category
        lastCategory = category
        entrySelectTable.clear()
        entryIndex.clear()
        flavourTable.clear()

        for (button in categoryToButtons.values) button.button.color = Color.WHITE
        val buttonInfo = categoryToButtons[category]
            ?: return        // defense against being passed a bad selector
        buttonInfo.button.color = Color.BLUE
        buttonTableScroll.scrollX = buttonInfo.x + (buttonInfo.width - buttonTableScroll.width) / 2

        if (category !in categoryToEntries) return        // defense, allowing buggy panes to remain empty while others work
        val entries = categoryToEntries[category]!!
            // Sort by [CivilopediaEntry.sortBy], then alphabetical order of localized names, using system default locale
            // Categories that should not sort alphabetically, e.g. Difficulties, will override `getSortGroup`.
            .sortedWith(
                compareBy<CivilopediaEntry> { it.sortBy }
                .thenBy (UncivGame.Current.settings.getCollatorFromLocale()) {
                    // In order for the extra icons on Happiness and Faith to not affect sort order
                    it.name.tr(hideIcons = true, hideStats = true)
                }
            )

        if (portrait) {
            // The portrait list is built from the index when shown
            for (entry in entries) entryIndex[entry.name] = entry
            return showPortraitList()
        }

        var currentY = -1f
        var currentSubCategory: String? = null

        for (entry in entries) {
            val entryButton = Table().apply {
                background = skinStrings.getUiBackground("CivilopediaScreen/EntryButton", tintColor = colorFromRGB(50, 75, 125))
                touchable = Touchable.enabled
            }
            val entryImage = entry.image
            if (entryImage != null)
                if (category == CivilopediaCategories.Terrain)
                    entryButton.add(entryImage).padLeft(20f).padRight(10f)
                else
                    entryButton.add(entryImage).padLeft(10f)
            entryButton.left().add(entry.name
                .toLabel(Color.WHITE, 25, hideIcons = true)).pad(10f)
            entryButton.onClick { selectEntry(entry) }
            entryButton.name = entry.name               // make button findable

            if (currentSubCategory != entry.subCategory) {
                if (entry.subCategory != null) {
                    val cell = entrySelectTable.add(SubCategoryTable(entry.subCategory)).fillX().padTop(12f)
                    cell.row()
                    currentY += cell.prefHeight + cell.padTop + cell.padBottom
                }
                currentSubCategory = entry.subCategory
            }

            val cell = entrySelectTable.add(entryButton).height(75f).growX()
            entrySelectTable.row()
            if (currentY < 0f) currentY = cell.padTop
            entryIndex[entry.name] = entry.withCoordinates(currentY, cell.prefHeight)
            currentY += cell.padBottom + cell.prefHeight + cell.padTop
        }

        entrySelectScroll.layout()          // necessary for positioning in selectRow to work

        // Select the first entry if an entry hasn't already been selected
        val entry = currentEntryPerCategory[category]
        if (entry != null) {
            selectEntry(entry)
        } else if (entries.isNotEmpty()) {
            selectEntry(entries.first().name, true)
        }
    }

    /** Select a specified entry within the current category. Unknown strings are ignored!
     * @param name Entry (Ruleset object) name
     * @param noScrollAnimation Disable scroll animation
     */
    private fun selectEntry(name: String, noScrollAnimation: Boolean = false) {
        val entry = entryIndex[name] ?: return
        // fails: entrySelectScroll.scrollTo(0f, entry.y, 0f, entry.h, false, true)
        entrySelectScroll.scrollY = (entry.y + (entry.height - entrySelectScroll.height) / 2)
        if (noScrollAnimation)
            entrySelectScroll.updateVisualScroll()     // snap without animation on fresh pedia open
        selectEntry(entry)
    }
    private fun selectEntry(entry: CivilopediaEntry) {
        currentEntry = entry.name
        lastEntryName = entry.name
        currentEntryPerCategory[currentCategory] = entry.name
        flavourTable.clear()
        if (entry.flavour != null) {
            flavourTable.isVisible = true
            flavourTable.add(if (portrait) portraitArticle(entry)
                else entry.flavour.assembleCivilopediaText(ruleset)
                    .renderCivilopediaText(stage.width * 0.5f) { selectLink(it) })
        } else {
            flavourTable.isVisible = false
        }
        entrySelectTable.children.forEach {
            it.color = if (it.name == entry.name) Color.BLUE else Color.WHITE
        }
        if (portrait) showPortraitEntry(entry)
    }
    private fun selectDefaultEntry() {
        val name = ruleset.mods.asSequence()
                .filter { RulesetCache[it]?.modOptions?.isBaseRuleset == true }
                .plus("Civilopedia")
                .firstOrNull { it in entryIndex.keys }
                ?: return
        selectEntry(name , noScrollAnimation = true)
    }

    init {
        val imageSize = if (portrait) 40f else 50f

        val religionEnabled = showReligionInCivilopedia(ruleset) // To filter the Belief Category only

        // do not confuse with IConstruction.shouldBeDisplayed - that one tests all prerequisites for building
        fun shouldBeDisplayed(obj: ICivilopediaText) =
            obj !is IHasUniques || !obj.isHiddenFromCivilopedia(game.gameInfo, ruleset)

        for (loopCategory in CivilopediaCategories.entries) {
            if (!religionEnabled && loopCategory == CivilopediaCategories.Belief) continue
            categoryToEntries[loopCategory] =
                loopCategory.getCategoryIterator(ruleset, game.gameInfo)
                    .filter(::shouldBeDisplayed)
                    .map { CivilopediaEntry(ruleset, it, loopCategory, imageSize) }
        }

        val buttonTable = Table()
        buttonTable.pad(15f)
        buttonTable.defaults().pad(10f)

        var currentX = 10f  // = padLeft
        for ((categoryKey, entries) in categoryToEntries) {
            if (entries.isEmpty()) continue
            val icon = if (categoryKey.headerIcon.isNotEmpty()) ImageGetter.getImage(categoryKey.headerIcon) else null
            val button = IconTextButton(categoryKey.label, icon)
            button.onActivation(binding = categoryKey.binding) { selectCategory(categoryKey) }
            val cell = buttonTable.add(button)
            categoryToButtons[categoryKey] = CategoryButtonInfo(button, currentX, cell.prefWidth)
            currentX += cell.prefWidth + 20f
        }

        buttonTable.pack()
        buttonTableScroll = ScrollPane(buttonTable)
        buttonTableScroll.setScrollingDisabled(x = false, y = true)

        val searchButton = "OtherIcons/Search".toImageButton(imageSize - 16f, imageSize, skinStrings.skinConfig.baseColor, Color.GOLD)
        searchButton.onActivation(binding = KeyboardBinding.PediaSearch) { searchPopup.open(true) }

        entrySelectScroll = ScrollPane(entrySelectTable)
        entrySelectTable.top()
        entrySelectScroll.setOverscroll(false, false)

        if (portrait) initPortrait(category, link) else initLandscape(imageSize, searchButton, category, link)

        globalShortcuts.add(Input.Keys.LEFT) { navigateCategories(-1) }
        globalShortcuts.add(Input.Keys.RIGHT) { navigateCategories(1) }
        globalShortcuts.add(Input.Keys.UP) { navigateEntries(-1) }
        globalShortcuts.add(Input.Keys.DOWN) { navigateEntries(1) }
        globalShortcuts.add(Input.Keys.PAGE_UP) { navigateEntries(-10) }
        globalShortcuts.add(Input.Keys.PAGE_DOWN) { navigateEntries(10) }
        globalShortcuts.add(Input.Keys.HOME) { navigateEntries(Int.MIN_VALUE) }
        globalShortcuts.add(Input.Keys.END) { navigateEntries(Int.MAX_VALUE) }
    }

    private fun initLandscape(imageSize: Float, searchButton: Actor, category: CivilopediaCategories, link: String) {
        val closeButton = getCloseButton(imageSize) { game.popScreen() }

        val topTable = Table()
        topTable.add(buttonTableScroll).growX()
        topTable.add(searchButton).padLeft(10f)
        topTable.add(closeButton).padLeft(10f).padRight(10f)
        topTable.width = stage.width
        topTable.layout()

        val entryTable = Table()
        val splitPane = SplitPane(topTable, entryTable, true, skin)
        splitPane.splitAmount = topTable.prefHeight / stage.height
        entryTable.height = stage.height - topTable.prefHeight
        splitPane.setFillParent(true)

        stage.addActor(splitPane)

        val descriptionTable = Table()
        descriptionTable.add(flavourTable).padTop(7f).padBottom(5f).row()  // 2f of that 7f is used up by Portrait painting e.g. a Nation's outer border *outside its bounds*
        val entrySplitPane = SplitPane(entrySelectScroll, ScrollPane(descriptionTable), false, skin)
        entrySplitPane.splitAmount = 0.3f
        entryTable.addActor(entrySplitPane)
        entrySplitPane.setFillParent(true)
        entrySplitPane.pack()  // ensure selectEntry has correct entrySelectScroll.height and maxY

        if (link.isEmpty()) {
            // Generic open (e.g. keyboard shortcut, menu button) - resume where we left off
            val resumeCategory = lastCategory ?: category
            val resumeEntry = lastEntryName     // capture before selectCategory overwrites it
            selectCategory(resumeCategory)
            if (resumeEntry != null && resumeEntry in entryIndex) {
                selectEntry(resumeEntry, noScrollAnimation = true)
            } else if (resumeCategory == CivilopediaCategories.Tutorial) {
                selectDefaultEntry()
            }
        } else if ('/' in link) {
            selectLink(link)
        } else {
            selectCategory(category)
            selectEntry(link, noScrollAnimation = true)
        }
    }

    //region Portrait

    private fun initPortrait(category: CivilopediaCategories, link: String) {
        val drawing = portraitCanvasBounds()
        stage.addActor(ImageGetter.getWhiteDot().apply {
            color = Color.valueOf("122536")
            touchable = Touchable.disabled
            setBounds(drawing.x, drawing.y, drawing.width, drawing.height)
        })
        stage.addActor(trayHost)
        sheet.onGone = { game.popScreen() }

        // Category buttons carry these bindings in landscape; here the shelves are not always on screen
        for (categoryKey in categoryToButtons.keys)
            if (categoryKey.binding != KeyboardBinding.None)
                globalShortcuts.add(categoryKey.binding) { selectCategory(categoryKey) }
        globalShortcuts.add(KeyboardBinding.PediaSearch) { searchPopup.open(true) }

        when {
            '/' in link -> selectLink(link)
            link.isNotEmpty() -> {
                selectCategory(category)
                selectEntry(link, noScrollAnimation = true)
            }
            category != CivilopediaCategories.Tutorial -> selectCategory(category)
            else -> showPortraitHome()
        }
        sheet.rise()
    }

    /** Header of a view; a view with [back] steps back on Back and Escape, the home view closes on them */
    private fun setPortraitHeader(title: String, subtitle: String? = null, back: (() -> Unit)?) {
        sheet.header(title, subtitle, onBack = back)
        if (back != null) {
            sheet.findActor<Actor>("portrait-sheet-close")?.keyShortcuts?.clear()
            sheet.findActor<Actor>("portrait-sheet-back")?.keyShortcuts?.add(KeyCharAndCode.BACK)
        }
    }

    private fun showPortraitHome() {
        portraitView = PortraitView.Home
        setPortraitHeader("Civilopedia", back = null)
        sheet.toolbar(null)
        sheet.body.clear()

        val fromYourGame = getFromYourGame()
        if (fromYourGame.isNotEmpty()) {
            sheet.addToBody(sheet.section("From your game"))
            val cards = Table()
            val cardWidth = (sheet.pt(369f) - 2 * sheet.pt(8f)) / 3f
            for ((index, card) in fromYourGame.withIndex())
                cards.add(getGameCard(card.first, card.second, card.third)).size(cardWidth, sheet.pt(118f))
                    .padLeft(if (index > 0) sheet.pt(8f) else 0f)
            val cardScroll = ScrollPane(cards)
            cardScroll.setScrollingDisabled(false, true)
            cardScroll.setOverscroll(false, false)
            sheet.addToBody(cardScroll)
        }

        sheet.addToBody(sheet.section("Browse"))
        val shelves = listOf(CivilopediaCategories.Unit, CivilopediaCategories.Building,
            CivilopediaCategories.Wonder, CivilopediaCategories.Technology, CivilopediaCategories.Resource,
            CivilopediaCategories.Terrain, CivilopediaCategories.Improvement, CivilopediaCategories.Nation,
            CivilopediaCategories.Policy, CivilopediaCategories.Belief)
        val tiles = shelves.mapNotNull { key -> categoryToEntries[key]?.takeIf { it.isNotEmpty() }?.let { getCategoryTile(key, it.size) } }
        sheet.addToBody(sheet.statGrid(tiles, 2))
        val more = Table().apply {
            name = "portrait-pedia-more"
            touchable = Touchable.enabled
            add("More categories".toLabel(TrayColors.INK2, sheet.font(15f))).pad(0f, sheet.pt(16f), 0f, sheet.pt(16f))
        }
        more.onClick {
            val popup = Popup(this)
            categoryToEntries.filter { it.key !in shelves && it.value.isNotEmpty() }.forEach { (key, _) ->
                popup.addButton(key.label) { popup.close(); selectCategory(key) }.row()
            }
            popup.addCloseButton()
            popup.open()
        }
        sheet.body.add(more).height(sheet.touch).padTop(sheet.pt(10f)).padBottom(sheet.pt(8f)).row()

        setPortraitSearchFooter()
        sheet.fit()
    }

    /** The category's entries as tray rows grouped by sub-category, the one you came from ticked */
    private fun showPortraitList() {
        portraitView = PortraitView.List
        setPortraitHeader(currentCategory.label, "[${entryIndex.size}] entries") { showPortraitHome() }
        sheet.body.clear()
        val groups = entryIndex.values.mapNotNull { it.subCategory }.distinct()
        val sections = HashMap<String, Actor>()
        var rows = ArrayList<Actor>()
        fun flush() {
            if (rows.isNotEmpty()) sheet.addToBody(sheet.card(rows))
            rows = ArrayList()
        }
        var subCategory: String? = null
        for (entry in entryIndex.values) {
            if (entry.subCategory != subCategory) {
                flush()
                subCategory = entry.subCategory
                if (subCategory != null) sections[subCategory] = sheet.section(subCategory).also { sheet.addToBody(it) }
            }
            val art = portraitArt(currentCategory, entry.flavour?.getIconName() ?: entry.name, sheet.pt(44f))
            rows += sheet.row(entry.name, lead = art,
                state = if (entry.name == currentEntry) TraySheet.RowState.Selected else TraySheet.RowState.Normal) {
                selectEntry(entry)
            }.apply { name = entry.name }
        }
        flush()
        sheet.addToBody(Table(), 12f)
        sheet.toolbar(if (groups.size < 2) null else AutoScrollPane(sheet.chips(groups, -1) { index ->
            sections[groups[index]]?.let { scrollBodyTo(it, top = true) }
        }).apply { setScrollingDisabled(false, true); setOverscroll(false, false) })
        setPortraitSearchFooter()
        sheet.fit()
    }

    private fun showPortraitEntry(entry: CivilopediaEntry) {
        portraitView = PortraitView.Entry
        setPortraitHeader(currentCategory.label) {
            showPortraitList()
            selectEntryInList(entry.name)
        }
        sheet.toolbar(null)
        sheet.body.clear()
        val hero = Table().apply {
            background = sheet.art.rounded(sheet.pt(18f)).tinted(TrayColors.CARD)
            pad(sheet.pt(14f))
            add(entry.name.toLabel(TrayColors.INK, sheet.font(26f), hideIcons = true).apply { wrap = true }).growX().bottom().left()
            val art = portraitArt(currentCategory, entry.flavour?.getIconName() ?: entry.name, sheet.pt(140f))
            if (art != null) add(art).size(sheet.pt(140f)).padLeft(sheet.pt(8f))
        }
        sheet.addToBody(hero, 4f).height(sheet.pt(168f))
        sheet.addToBody(flavourTable, 14f).padLeft(sheet.pt(4f)).padRight(sheet.pt(4f))

        val footer = sheet.footer()
        val size = sheet.touch.coerceAtLeast(sheet.pt(56f))
        footer.add(sheet.button("‹", TraySheet.ButtonKind.Secondary) { navigateEntries(-1) }.apply { name = "portrait-pedia-previous" }).width(size)
        footer.add(sheet.button(currentCategory.label, TraySheet.ButtonKind.Secondary) {
            showPortraitList()
            selectEntryInList(entry.name)
        }.apply { name = "portrait-pedia-all" }).growX()
        footer.add(sheet.button("›", TraySheet.ButtonKind.Secondary) { navigateEntries(1) }.apply { name = "portrait-pedia-next" }).width(size).padRight(0f)
        sheet.fit()
        scrollBodyTo(null, top = true)
    }

    /** Scroll the category list so [name] is in view (after it was laid out in portrait's list view) */
    private fun selectEntryInList(name: String) {
        sheet.body.findActor<Actor>(name)?.let { scrollBodyTo(it, top = false) }
    }

    /** Scrolls the sheet body to [actor] (to the top when null): its top edge, or centred */
    private fun scrollBodyTo(actor: Actor?, top: Boolean) {
        val pane = sheet.body.parent as? com.badlogic.gdx.scenes.scene2d.ui.ScrollPane ?: return
        pane.layout()
        if (actor == null) pane.scrollY = 0f
        else {
            val y = actor.localToAscendantCoordinates(sheet.body, com.badlogic.gdx.math.Vector2()).y
            val fromTop = sheet.body.height - y - actor.height
            pane.scrollY = if (top) fromTop else fromTop + actor.height / 2 - pane.height / 2
        }
        pane.updateVisualScroll()
    }

    /** Search stays pinned in the footer: results replace the body while you type */
    private fun setPortraitSearchFooter() {
        val footer = sheet.footer()
        val search = Table()
        search.background = sheet.art.rounded(sheet.pt(26f)).tinted(TrayColors.FILL)
        search.touchable = Touchable.enabled
        search.add(ImageGetter.getImage("OtherIcons/Search").apply { color = TrayColors.INK2 }).size(sheet.pt(20f))
            .padLeft(sheet.pt(18f)).padRight(sheet.pt(10f))
        val field = UncivTextField("Search every entry")
        field.name = "portrait-pedia-search"
        field.style = com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(field.style).apply {
            background = null
            fontColor = Color.WHITE
        }
        field.maxLength = 100
        field.onChange {
            val query = field.text.trim()
            if (query.isEmpty()) {
                if (portraitView == PortraitView.Home) showPortraitHome() else showPortraitList()
                return@onChange
            }
            setPortraitHeader("Civilopedia", back = null)
            sheet.toolbar(null)
            sheet.body.clear()
            var count = 0
            for ((category, entries) in categoryToEntries) {
                val matches = entries.asSequence().filter { it.name.tr().contains(query, ignoreCase = true) }
                    .take(61 - count).toList()
                if (matches.isEmpty()) continue
                if (count < 60) sheet.addToBody(sheet.section(category.label))
                val rows = matches.take(60 - count).map { entry ->
                    sheet.row(entry.name, lead = portraitArt(category, entry.flavour?.getIconName() ?: entry.name, sheet.pt(40f))) {
                        stage.keyboardFocus = null
                        selectLink("${category.name}/${entry.name}")
                    }.apply { name = entry.name }
                }
                count += rows.size
                if (rows.isNotEmpty()) sheet.addToBody(sheet.card(rows))
                if (count >= 60) {
                    sheet.addToBody("Refine your search for more results".toLabel(TrayColors.INK2, sheet.font(14f)).apply { wrap = true }, 12f)
                        .padLeft(sheet.pt(16f))
                    break
                }
            }
            if (count == 0) sheet.addToBody(sheet.emptyState("OtherIcons/Search", "No results"))
            sheet.fit()
        }
        search.add(field).growX().height(sheet.touch)
        val clear = "×".toLabel(TrayColors.INK2, sheet.font(24f)).apply {
            setAlignment(Align.center)
            touchable = Touchable.enabled
            name = "portrait-pedia-clear"
            onClick { field.text = "" }
        }
        search.add(clear).size(sheet.touch)
        footer.add(search).growX().height(sheet.touch.coerceAtLeast(sheet.pt(52f))).padRight(0f)
    }

    private fun shelfLabel(category: CivilopediaCategories) = when (category) {
        CivilopediaCategories.Technology -> "Techs"
        CivilopediaCategories.Nation -> "Civilizations"
        CivilopediaCategories.Improvement -> "Improvements"
        CivilopediaCategories.Belief -> "Religions"
        else -> category.label
    }

    private fun getCategoryTile(categoryKey: CivilopediaCategories, count: Int): Table {
        val tile = Table()
        tile.name = "portrait-pedia-${categoryKey.name}"
        tile.background = sheet.art.rounded(sheet.pt(16f)).tinted(TrayColors.CARD)
        tile.touchable = Touchable.enabled
        tile.pad(sheet.pt(6f), sheet.pt(10f), sheet.pt(6f), sheet.pt(10f))
        tile.add().minHeight(sheet.touch.coerceAtLeast(sheet.pt(56f)) - sheet.pt(12f)).width(0f)
        tile.add(sheet.disc(ImageGetter.getImage(categoryKey.headerIcon), size = 40f, iconSize = 22f)).size(sheet.pt(40f)).padRight(sheet.pt(10f))
        val text = Table()
        text.add(shelfLabel(categoryKey).toLabel(TrayColors.INK, sheet.font(15.5f), hideIcons = true).apply { wrap = true }).growX().left().row()
        text.add(count.tr().toLabel(TrayColors.INK2, sheet.font(13f))).left()
        tile.add(text).growX()
        tile.onClick { selectCategory(categoryKey) }
        return tile
    }

    private fun getGameCard(cardCategory: CivilopediaCategories, name: String, why: String): Table {
        val card = Table()
        card.background = sheet.art.rounded(sheet.pt(18f)).tinted(TrayColors.CARD)
        card.touchable = Touchable.enabled
        card.pad(sheet.pt(8f), sheet.pt(6f), sheet.pt(10f), sheet.pt(6f))
        val flavour = categoryToEntries[cardCategory]?.firstOrNull { it.name == name }?.flavour
        val image = portraitArt(cardCategory, flavour?.getIconName() ?: name, sheet.pt(58f))
            ?: ImageGetter.getImage(cardCategory.headerIcon)
        card.add(image).size(sheet.pt(58f)).expandY().top().row()
        card.add(name.toLabel(TrayColors.INK, sheet.font(if (name.length > 12) 13.5f else 15f), Align.center, hideIcons = true)
            .apply { wrap = true }).growX().row()
        card.add(why.toLabel(TrayColors.INK2, sheet.font(12f), Align.center).apply { wrap = true }).growX()
        card.onClick { selectLink("${cardCategory.name}/$name") }
        return card
    }

    private fun portraitArticle(entry: CivilopediaEntry): Table {
        val width = sheet.pt(361f)
        val article = Table().apply { top().left(); defaults().growX().left() }
        val stats = mutableListOf<Triple<String, Int, String>>()
        when (currentCategory) {
            CivilopediaCategories.Technology -> ruleset.technologies[entry.name]?.let {
                stats.add(Triple("Science", it.cost, "Cost"))
            }
            CivilopediaCategories.Unit -> ruleset.units[entry.name]?.let {
                if (it.strength > 0) stats.add(Triple("Strength", it.strength, "Strength"))
                if (it.rangedStrength > 0) stats.add(Triple("RangedStrength", it.rangedStrength, "Ranged strength"))
                stats.add(Triple("Movement", it.movement, "Movement"))
            }
            else -> Unit
        }
        if (stats.isNotEmpty()) {
            val tiles = Table().left()
            for ((icon, value, label) in stats) {
                val tile = Table().apply {
                    background = sheet.art.rounded(sheet.pt(16f)).tinted(TrayColors.CARD)
                    pad(sheet.pt(10f))
                    val path = "StatIcons/$icon"
                    if (ImageGetter.imageExists(path)) add(ImageGetter.getImage(path)).size(sheet.pt(22f)).padRight(sheet.pt(6f))
                    add(value.toString().toLabel(TrayColors.INK, sheet.font(22f))).row()
                    add(label.toLabel(TrayColors.INK2, sheet.font(12f))).colspan(2).padTop(sheet.pt(4f))
                }
                tiles.add(tile).minWidth(sheet.pt(82f)).height(sheet.pt(78f)).padRight(sheet.pt(8f))
            }
            article.add(tiles).left().padBottom(sheet.pt(14f)).row()
        }
        var lines = entry.flavour!!.assembleCivilopediaText(ruleset).civilopediaText
        if (lines.firstOrNull()?.text == entry.name) {
            lines = lines.drop(1)
            if (lines.firstOrNull()?.separator == true) lines = lines.drop(1)
        }
        for (line in lines) {
            if (line.isEmpty()) { article.add().height(sheet.pt(8f)).row(); continue }
            if (line.separator) {
                article.add(ImageGetter.getWhiteDot().apply { color = TrayColors.LINE }).height(1f).padTop(sheet.pt(8f)).padBottom(sheet.pt(8f)).row()
                continue
            }
            if (currentCategory == CivilopediaCategories.Technology && line.text.startsWith("{Cost}:")) continue
            val formatted = FormattedLine(line.text, line.link, line.icon, line.extraImage, line.imageSize,
                size = sheet.font(16f), indent = line.indent, color = if (line.header > 0) "#ffffff" else "#a2b9cd",
                starred = line.starred, centered = line.centered, iconCrossed = line.iconCrossed)
            val linked = line.linkType != FormattedLine.LinkType.None
            val content = formatted.render(if (linked) width - sheet.pt(28f) else width, FormattedLine.IconDisplay.NoLink)
            val row = Table().apply {
                if (linked) { background = sheet.art.rounded(sheet.pt(14f)).tinted(TrayColors.CARD); pad(sheet.pt(8f), sheet.pt(14f), sheet.pt(8f), sheet.pt(14f)) }
                add(content).growX().left()
                if (linked) {
                    touchable = Touchable.enabled
                    onClick {
                        if (line.linkType == FormattedLine.LinkType.Internal) selectLink(line.link)
                        else com.badlogic.gdx.Gdx.net.openURI(line.link)
                    }
                }
            }
            article.add(row).width(width).minHeight(if (linked) sheet.touch else 0f).padBottom(sheet.pt(6f)).row()
        }
        return article
    }

    private fun portraitArt(category: CivilopediaCategories, name: String, size: Float): Actor? {
        val path = when (category) {
            CivilopediaCategories.Unit -> "TileSets/Polytopia/Units/$name"
            CivilopediaCategories.Resource, CivilopediaCategories.Improvement -> "TileSets/Polytopia/Tiles/$name"
            else -> ""
        }
        if (path.isNotEmpty() && ImageGetter.imageExists(path)) return Group().apply {
            setSize(size, size)
            addActor(ImageGetter.getImage(path).apply { setSize(size, size) })
            if (ImageGetter.imageExists("$path-1")) addActor(ImageGetter.getImage("$path-1").apply {
                setSize(size, size)
                color = game.worldScreen?.selectedCiv?.nation?.getOuterColor() ?: Color.valueOf("287fd1")
            })
        }
        return category.getImage?.invoke(name, size)
    }

    /** Entries that matter in the running game, shown first so the pedia starts from what you are doing */
    private fun getFromYourGame(): List<Triple<CivilopediaCategories, String, String>> {
        val civ = game.worldScreen?.selectedCiv ?: return emptyList()
        if (civ.isSpectator()) return emptyList()
        val cards = ArrayList<Triple<CivilopediaCategories, String, String>>()
        fun add(cardCategory: CivilopediaCategories, name: String, why: String) {
            if (categoryToEntries[cardCategory]?.any { it.name == name } == true && cards.none { it.second == name })
                cards += Triple(cardCategory, name, why)
        }
        civ.tech.currentTechnologyName()?.let { add(CivilopediaCategories.Technology, it, "Researching") }
        for (wonder in civ.cities.flatMap { it.cityConstructions.getBuiltBuildings() }.filter { it.isAnyWonder() })
            add(CivilopediaCategories.Wonder, wonder.name, "Your wonder")
        add(CivilopediaCategories.Nation, civ.civName, "Your civilization")
        for (unit in ruleset.units.values.filter { it.uniqueTo == civ.civName })
            add(CivilopediaCategories.Unit, unit.name, "Your unique unit")
        for (building in ruleset.buildings.values.filter { it.uniqueTo == civ.civName })
            add(if (building.isAnyWonder()) CivilopediaCategories.Wonder else CivilopediaCategories.Building, building.name, "Your unique building")
        for (improvement in ruleset.tileImprovements.values.filter { it.uniqueTo == civ.civName })
            add(CivilopediaCategories.Improvement, improvement.name, "Your unique improvement")
        add(CivilopediaCategories.Era, civ.getEra().name, "Your era")
        return cards
    }

    //endregion

    private fun navigateCategories(direction: Int) {
        val categoryKeys = categoryToEntries.keys
        val currentIndex = categoryKeys.indexOf(currentCategory)
        val newIndex = (currentIndex + categoryKeys.size + direction) % categoryKeys.size
        selectCategory(categoryKeys.elementAt(newIndex))
    }

    private fun navigateEntries(direction: Int) {
        //todo this is abusing a Map as Array - there must be a collection allowing both easy positional and associative access
        if (entryIndex.isEmpty()) return  // portrait home has no category open yet
        val index = entryIndex.keys.indexOf(currentEntry)
        if (index < 0) return selectEntry(entryIndex.keys.first(), true)
        val newIndex = when (direction) {
            Int.MIN_VALUE -> 0
            Int.MAX_VALUE -> entryIndex.size - 1
            else -> if (portrait) (index + direction).coerceIn(0, entryIndex.size - 1)
                else (index + entryIndex.size + direction) % entryIndex.size
        }
        selectEntry(entryIndex.keys.drop(newIndex).first())
    }

    override fun recreate(): BaseScreen = when {
        !portrait || portraitView == PortraitView.Entry -> CivilopediaScreen(ruleset, currentCategory, currentEntry)
        portraitView == PortraitView.List -> CivilopediaScreen(ruleset, currentCategory)
        else -> CivilopediaScreen(ruleset)
    }

    companion object {

        /** Remembers the last viewed category/entry so re-opening the Civilopedia without
         *  an explicit link resumes where the user left off. */
        private var lastCategory: CivilopediaCategories? = null
        private var lastEntryName: String? = null

        /** Test whether to show Religion-specific items, does not require a game to be running
         *  - Do not make public - use IHasUniques.isHiddenFromCivilopedia if possible!
         */
        private fun showReligionInCivilopedia(ruleset: Ruleset? = null): Boolean {
            val gameInfo = UncivGame.getGameInfoOrNull()
            return when {
                gameInfo != null -> gameInfo.isReligionEnabled()
                ruleset != null -> ruleset.beliefs.isNotEmpty()
                else -> true
            }
        }
    }
}

/** Stage-wide host of the portrait tray sheet; frees the sheet's textures when the screen goes away */
private class TrayHost(screen: BaseScreen) : Group() {
    val art = TrayArt(screen.stage)
    val sheet = TraySheet(screen, art, minShare = 1f)

    init {
        name = "portrait-pedia-sheet"
        touchable = Touchable.childrenOnly
        setBounds(0f, 0f, screen.stage.width, screen.stage.height)
        addActor(sheet)
    }

    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null) art.release()
    }
}
