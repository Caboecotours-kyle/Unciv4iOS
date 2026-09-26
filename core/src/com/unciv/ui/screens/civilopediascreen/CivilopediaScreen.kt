package com.unciv.ui.screens.civilopediascreen

import com.unciv.ui.screens.basescreen.portraitCanvasBounds

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
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
import com.unciv.ui.components.extensions.surroundWithCircle
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

    /** Portrait is a library: a home view of shelves, a full-screen list per category, full-screen entries */
    private val portrait = isPortrait()
    private enum class PortraitView { Home, List, Entry }
    private var portraitView = PortraitView.Home
    private val portraitHeader = Table()
    private val portraitFooter = Table()
    private val portraitRoot = Table()
    private val portraitBodyCell = portraitRoot.run {
        add(portraitHeader).growX().row()
        add().grow().apply { row() }
    }

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

        var currentY = -1f
        var currentSubCategory: String? = null

        for (entry in entries) {
            val entryButton = Table().apply {
                background = if (portrait) portraitPanel(Color(1f, 1f, 1f, .04f), 14f)
                else skinStrings.getUiBackground("CivilopediaScreen/EntryButton", tintColor = colorFromRGB(50, 75, 125))
                touchable = Touchable.enabled
            }
            val entryImage = if (portrait) portraitArt(category, entry.flavour?.getIconName() ?: entry.name, 44f) else entry.image
            if (entryImage != null)
                if (category == CivilopediaCategories.Terrain)
                    entryButton.add(entryImage).padLeft(20f).padRight(10f)
                else
                    entryButton.add(entryImage).padLeft(10f)
            entryButton.left().add(entry.name
                .toLabel(Color.WHITE, if (portrait) 16 else 25, hideIcons = true)).pad(10f)
            entryButton.onClick { selectEntry(entry) }
            entryButton.name = entry.name               // make button findable

            if (currentSubCategory != entry.subCategory) {
                if (entry.subCategory != null) {
                    val header = if (portrait) entry.subCategory.toLabel(mutedPedia(), 15) else SubCategoryTable(entry.subCategory)
                    val cell = entrySelectTable.add(header).fillX().padTop(12f)
                    if (portrait) cell.height(28f)
                    cell.row()
                    currentY += if (portrait) 40f else cell.prefHeight + cell.padTop + cell.padBottom
                }
                currentSubCategory = entry.subCategory
            }

            val cell = entrySelectTable.add(entryButton).height(if (portrait) 60f else 75f).growX()
            entrySelectTable.row()
            if (currentY < 0f) currentY = cell.padTop
            entryIndex[entry.name] = entry.withCoordinates(currentY, cell.prefHeight)
            currentY += cell.padBottom + cell.prefHeight + cell.padTop
        }

        entrySelectScroll.layout()          // necessary for positioning in selectRow to work

        if (portrait) return showPortraitList()

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
        portraitRoot.add(portraitFooter).growX()
        val safe = safeAreaBoundsInWorld()
        val unit = safe.width / 393f
        val drawing = portraitCanvasBounds()
        val topInset = (drawing.y + drawing.height - safe.y - safe.height) / unit
        val top = (56f - topInset).coerceAtLeast(0f)
        portraitRoot.isTransform = true
        portraitRoot.setScale(unit)
        portraitRoot.setBounds(safe.x, safe.y, 393f, safe.height / unit - top)
        stage.addActor(ImageGetter.getWhiteDot().apply {
            color = Color.valueOf("122536")
            touchable = Touchable.disabled
            setBounds(drawing.x, drawing.y, drawing.width, drawing.height)
        })
        portraitRoot.background = portraitPanel(Color.valueOf("132435"), 26f)
        stage.addActor(portraitRoot)

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
    }

    private fun showPortraitHome() {
        portraitView = PortraitView.Home
        setPortraitHeader("Civilopedia", back = null)

        val body = Table()
        body.defaults().growX().left()
        body.pad(0f, portraitPad, portraitPad, portraitPad)

        val fromYourGame = getFromYourGame()
        if (fromYourGame.isNotEmpty()) {
            body.add(portraitSectionLabel("From your game")).padBottom(8f).row()
            val cards = Table()
            cards.defaults().space(10f).top()
            for ((cardCategory, name, why) in fromYourGame)
                cards.add(getGameCard(cardCategory, name, why)).width(112f).fillY()
            val cardScroll = ScrollPane(cards)
            cardScroll.setScrollingDisabled(false, true)
            cardScroll.setOverscroll(false, false)
            body.add(cardScroll).padBottom(16f).row()
        }

        body.add(portraitSectionLabel("Browse")).padBottom(8f).row()
        val grid = Table()
        grid.defaults().space(8f).uniformX().growX().minHeight(64f)
        var column = 0
        val shelves = listOf(CivilopediaCategories.Unit, CivilopediaCategories.Building,
            CivilopediaCategories.Wonder, CivilopediaCategories.Technology, CivilopediaCategories.Resource,
            CivilopediaCategories.Terrain, CivilopediaCategories.Improvement, CivilopediaCategories.Nation,
            CivilopediaCategories.Policy, CivilopediaCategories.Belief)
        for (categoryKey in shelves) {
            val entries = categoryToEntries[categoryKey] ?: continue
            if (entries.isEmpty()) continue
            grid.add(getCategoryTile(categoryKey, entries.size)).fill()
            if (++column % 2 == 0) grid.row()
        }
        body.add(grid).row()
        val more = Table().apply {
            touchable = Touchable.enabled
            add("More categories".toLabel(mutedPedia(), 14))
        }
        more.onClick {
            val popup = Popup(this)
            categoryToEntries.filter { it.key !in shelves && it.value.isNotEmpty() }.forEach { (key, _) ->
                popup.addButton(key.label) { popup.close(); selectCategory(key) }.row()
            }
            popup.addCloseButton()
            popup.open()
        }
        body.add(more).height(48f).padTop(12f).row()

        setPortraitBody(body)
        setPortraitSearchFooter()
    }

    private fun showPortraitList() {
        portraitView = PortraitView.List
        val count = categoryToEntries[currentCategory]?.size ?: 0
        setPortraitHeader(currentCategory.label, "[$count] entries") { showPortraitHome() }
        entrySelectTable.pad(0f, portraitPad - 6f, 0f, portraitPad - 6f)
        portraitBodyCell.setActor(entrySelectScroll)
        setPortraitSearchFooter()
        portraitRoot.layout()
    }

    private fun showPortraitEntry(entry: CivilopediaEntry) {
        portraitView = PortraitView.Entry
        setPortraitHeader(currentCategory.label) {
            showPortraitList()
            selectEntryInList(entry.name)
        }

        val body = Table()
        body.pad(0f, portraitPad, portraitPad, portraitPad)
        val hero = Table().apply {
            background = portraitPanel(Color.valueOf("213c54"), 22f)
            pad(14f)
            val text = entry.name.toLabel(Color.WHITE, 26, hideIcons = true).apply { wrap = true }
            add(text).width(169f).bottom().left()
            val art = portraitArt(currentCategory, entry.flavour?.getIconName() ?: entry.name, 140f)
            if (art != null) add(art).size(140f)
        }
        body.add(hero).width(365f).height(184f).padBottom(14f).row()
        body.add(flavourTable).width(365f).top().row()
        setPortraitBody(body)

        portraitFooter.clear()
        portraitFooter.background = portraitFooterBackground()
        portraitFooter.pad(10f, portraitPad, portraitBottomPad(), portraitPad)
        portraitFooter.defaults().height(52f)
        portraitFooter.add(getPortraitCircleButton("OtherIcons/BackArrow") { navigateEntries(-1) }).size(52f)
        val all = Table().apply {
            background = portraitPanel(Color(1f, 1f, 1f, .07f), 16f)
            touchable = Touchable.enabled
            add(currentCategory.label.toLabel(Color.WHITE, 15))
        }
        all.onClick { showPortraitList(); selectEntryInList(entry.name) }
        portraitFooter.add(all).growX().padLeft(10f).padRight(10f)
        portraitFooter.add(getPortraitCircleButton("OtherIcons/BackArrow", flip = true) { navigateEntries(1) }).size(52f)
    }

    /** Scroll the category list so [name] is in view (after it was laid out in portrait's list view) */
    private fun selectEntryInList(name: String) {
        val entry = entryIndex[name] ?: return
        entrySelectScroll.layout()
        entrySelectScroll.scrollY = (entry.y + (entry.height - entrySelectScroll.height) / 2)
        entrySelectScroll.updateVisualScroll()
    }

    private fun setPortraitHeader(title: String, subtitle: String? = null, back: (() -> Unit)?) {
        portraitHeader.clear()
        portraitHeader.pad(10f, portraitPad, 8f, portraitPad)
        portraitHeader.add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .22f) })
            .size(40f, 4f).colspan(if (back == null) 2 else 3).padBottom(8f).row()
        if (back != null)
            portraitHeader.add(getPortraitCircleButton("OtherIcons/BackArrow", KeyCharAndCode.BACK, action = back)).size(48f).padRight(10f)
        val titles = Table().left()
        titles.add(title.toLabel(Color.WHITE, 22, hideIcons = true)).left().row()
        if (subtitle != null) titles.add(subtitle.toLabel(Color.LIGHT_GRAY, 16)).left()
        portraitHeader.add(titles).growX().left().minHeight(48f)
        // Back steps out of an entry or list first; only the home view closes on Back
        val close = getPortraitCircleButton("OtherIcons/Close", if (back == null) KeyCharAndCode.BACK else null) { game.popScreen() }
        portraitHeader.add(close).size(48f).padLeft(10f)
    }

    private fun setPortraitBody(body: Table) {
        val scroll = ScrollPane(body)
        scroll.setScrollingDisabled(true, false)
        scroll.setOverscroll(false, false)
        body.top()
        portraitBodyCell.setActor(scroll)
    }

    private fun setPortraitSearchFooter() {
        portraitFooter.clear()
        portraitFooter.background = portraitFooterBackground()
        portraitFooter.pad(10f, portraitPad, portraitBottomPad(), portraitPad)
        val search = Table()
        search.background = portraitPanel(Color(1f, 1f, 1f, .1f), 16f)
        search.touchable = Touchable.enabled
        search.add(ImageGetter.getImage("OtherIcons/Search").apply { color = Color.LIGHT_GRAY }).size(20f).padLeft(14f).padRight(10f)
        val field = UncivTextField("Search every entry")
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
            val results = Table().apply { top(); pad(0f, 14f, 14f, 14f); defaults().growX() }
            var count = 0
            for ((category, entries) in categoryToEntries) {
                val matches = entries.asSequence().filter { it.name.tr().contains(query, ignoreCase = true) }
                    .take(61 - count).toList()
                if (matches.isEmpty()) continue
                if (count < 60) results.add(category.label.toLabel(mutedPedia(), 15)).left().padTop(12f).row()
                for (entry in matches.take(60 - count)) {
                    val result = Table().apply {
                        touchable = Touchable.enabled
                        val art = portraitArt(category, entry.flavour?.getIconName() ?: entry.name, 40f)
                        if (art != null) add(art).size(40f).padRight(12f)
                        add(entry.name.toLabel(Color.WHITE, 16, hideIcons = true).apply { wrap = true }).growX().left()
                        onClick { stage.keyboardFocus = null; selectLink("${category.name}/${entry.name}") }
                    }
                    results.add(result).minHeight(60f).row()
                    count++
                }
                if (count >= 60) {
                    results.add("Refine your search for more results".toLabel(mutedPedia(), 14).apply { wrap = true })
                        .width(365f).padTop(12f).row()
                    break
                }
            }
            if (count == 0) results.add("No results".toLabel(mutedPedia(), 16)).padTop(20f)
            setPortraitBody(results)
        }
        search.add(field).growX().height(48f)
        val clear = "×".toLabel(mutedPedia(), 24).apply {
            setAlignment(Align.center)
            touchable = Touchable.enabled
            onClick { field.text = "" }
        }
        search.add(clear).size(48f)
        if (portraitView == PortraitView.List) {
            val rail = Table()
            val groups = categoryToEntries[currentCategory].orEmpty().mapNotNull { it.subCategory }.distinct()
            for (group in groups) {
                val chip = Table().apply {
                    background = portraitPanel(Color(1f, 1f, 1f, .06f), 13f)
                    touchable = Touchable.enabled
                    add(group.toLabel(mutedPedia(), 14)).pad(0f, 13f, 0f, 13f)
                }
                chip.onClick {
                    val entry = entryIndex.values.firstOrNull { it.subCategory == group } ?: return@onClick
                    entrySelectScroll.scrollY = entry.y
                    entrySelectScroll.updateVisualScroll()
                }
                rail.add(chip).height(48f).padRight(6f)
            }
            if (groups.size > 1) portraitFooter.add(ScrollPane(rail).apply { setScrollingDisabled(false, true) }).height(52f).growX().row()
        }
        portraitFooter.add(search).growX().height(52f)
    }

    private fun portraitBottomPad(): Float {
        val safe = safeAreaBoundsInWorld()
        val drawing = portraitCanvasBounds()
        return (34f - (safe.y - drawing.y) / (safe.width / 393f)).coerceAtLeast(10f)
    }

    private fun mutedPedia() = Color.valueOf("b7cde0")

    private fun shelfLabel(category: CivilopediaCategories) = when (category) {
        CivilopediaCategories.Technology -> "Techs"
        CivilopediaCategories.Nation -> "Civilizations"
        CivilopediaCategories.Improvement -> "Improvements"
        CivilopediaCategories.Belief -> "Religions"
        else -> category.label
    }

    private fun portraitFooterBackground() = ImageGetter.getDrawable(ImageGetter.whiteDotLocation).tint(Color.valueOf("0f2030"))

    private fun portraitPanel(color: Color, radius: Float): com.badlogic.gdx.scenes.scene2d.utils.Drawable {
        val region = ImageGetter.getCircleDrawable().region
        val split = (minOf(region.regionWidth, region.regionHeight) / 2 - 1).coerceAtLeast(1)
        val patch = com.badlogic.gdx.graphics.g2d.NinePatch(region, split, split, split, split)
        patch.scale(radius / split, radius / split)
        return com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable(patch).tint(color).apply {
            minWidth = 0f; minHeight = 0f
            leftWidth = 0f; rightWidth = 0f; topHeight = 0f; bottomHeight = 0f
        }
    }

    private fun portraitSectionLabel(text: String) = text.toLabel(mutedPedia(), 16)

    private fun getPortraitCircleButton(icon: String, key: KeyCharAndCode? = null, flip: Boolean = false, action: () -> Unit): Group {
        val image = ImageGetter.getImage(icon)
        image.setSize(22f, 22f)
        if (flip) image.rotation = 180f
        val button = image.surroundWithCircle(48f, resizeActor = false, color = Color.valueOf("263b4b"))
        button.touchable = Touchable.enabled
        button.onActivation(action)
        if (key != null) button.keyShortcuts.add(key)
        return button
    }

    private fun getCategoryTile(categoryKey: CivilopediaCategories, count: Int): Table {
        val tile = Table()
        tile.background = portraitPanel(Color(1f, 1f, 1f, .06f), 16f)
        tile.touchable = Touchable.enabled
        tile.pad(8f, 10f, 8f, 10f)
        val icon = ImageGetter.getImage(categoryKey.headerIcon).apply { color = colorFromRGB(19, 36, 53) }
        tile.add(icon.surroundWithCircle(44f, color = colorFromRGB(238, 245, 251))).size(44f).padRight(10f)
        val text = Table()
        text.add(shelfLabel(categoryKey).toLabel(Color.WHITE, 15, hideIcons = true).apply { wrap = true }).growX().left().row()
        text.add(count.tr().toLabel(mutedPedia(), 12)).left()
        tile.add(text).growX()
        tile.onClick { selectCategory(categoryKey) }
        return tile
    }

    private fun getGameCard(cardCategory: CivilopediaCategories, name: String, why: String): Table {
        val card = Table()
        card.background = portraitPanel(Color.valueOf("1c3249"), 18f)
        card.touchable = Touchable.enabled
        card.pad(10f, 8f, 10f, 8f)
        card.top()
        val flavour = categoryToEntries[cardCategory]?.firstOrNull { it.name == name }?.flavour
        val image = portraitArt(cardCategory, flavour?.getIconName() ?: name, 64f)
            ?: ImageGetter.getImage(cardCategory.headerIcon)
        card.add(image).size(64f).padBottom(6f).row()
        card.add(name.toLabel(Color.WHITE, 14, Align.center, hideIcons = true).apply { wrap = true }).width(96f).row()
        card.add(why.toLabel(mutedPedia(), 12, Align.center).apply { wrap = true }).width(96f)
        card.onClick { selectLink("${cardCategory.name}/$name") }
        return card
    }

    private fun portraitArticle(entry: CivilopediaEntry): Table {
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
                    background = portraitPanel(Color(1f, 1f, 1f, .06f), 16f)
                    pad(10f)
                    val path = "StatIcons/$icon"
                    if (ImageGetter.imageExists(path)) add(ImageGetter.getImage(path)).size(22f).padRight(6f)
                    add(value.toString().toLabel(Color.WHITE, 22)).row()
                    add(label.toLabel(mutedPedia(), 12)).colspan(2).padTop(4f)
                }
                tiles.add(tile).minWidth(82f).height(78f).padRight(8f)
            }
            article.add(tiles).left().padBottom(14f).row()
        }
        var lines = entry.flavour!!.assembleCivilopediaText(ruleset).civilopediaText
        if (lines.firstOrNull()?.text == entry.name) {
            lines = lines.drop(1)
            if (lines.firstOrNull()?.separator == true) lines = lines.drop(1)
        }
        for (line in lines) {
            if (line.isEmpty()) { article.add().height(8f).row(); continue }
            if (line.separator) {
                article.add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .1f) }).height(1f).padTop(8f).padBottom(8f).row()
                continue
            }
            if (currentCategory == CivilopediaCategories.Technology && line.text.startsWith("{Cost}:")) continue
            val formatted = FormattedLine(line.text, line.link, line.icon, line.extraImage, line.imageSize,
                size = 16, indent = line.indent, color = if (line.header > 0) "#ffffff" else "#b7cde0",
                starred = line.starred, centered = line.centered, iconCrossed = line.iconCrossed)
            val linked = line.linkType != FormattedLine.LinkType.None
            val content = formatted.render(if (linked) 337f else 365f, FormattedLine.IconDisplay.NoLink)
            val row = Table().apply {
                if (linked) { background = portraitPanel(Color(1f, 1f, 1f, .07f), 14f); pad(8f, 14f, 8f, 14f) }
                add(content).growX().left()
                if (linked) {
                    touchable = Touchable.enabled
                    onClick {
                        if (line.linkType == FormattedLine.LinkType.Internal) selectLink(line.link)
                        else com.badlogic.gdx.Gdx.net.openURI(line.link)
                    }
                }
            }
            article.add(row).width(365f).minHeight(if (linked) 48f else 0f).padBottom(6f).row()
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
        private const val portraitPad = 14f

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
