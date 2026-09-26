package com.unciv.ui.screens.civilopediascreen

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
                background = skinStrings.getUiBackground(
                    "CivilopediaScreen/EntryButton",
                    tintColor = colorFromRGB(50, 75, 125)
                )
                touchable = Touchable.enabled
            }
            if (entry.image != null)
                if (category == CivilopediaCategories.Terrain)
                    entryButton.add(entry.image).padLeft(20f).padRight(10f)
                else
                    entryButton.add(entry.image).padLeft(10f)
            entryButton.left().add(entry.name
                .toLabel(Color.WHITE, if (portrait) 20 else 25, hideIcons = true)).pad(10f)
            entryButton.onClick { selectEntry(entry) }
            entryButton.name = entry.name               // make button findable

            if (currentSubCategory != entry.subCategory) {
                if (entry.subCategory != null)
                    entrySelectTable.add(SubCategoryTable(entry.subCategory)).fillX().row()
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
            flavourTable.add(
                entry.flavour.assembleCivilopediaText(ruleset)
                    .renderCivilopediaText(if (portrait) stage.width - 2 * portraitPad else stage.width * 0.5f) { selectLink(it) })
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
        portraitRoot.setFillParent(true)
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
        for ((categoryKey, entries) in categoryToEntries) {
            if (entries.isEmpty()) continue
            grid.add(getCategoryTile(categoryKey, entries.size)).fill()
            if (++column % 2 == 0) grid.row()
        }
        body.add(grid).row()

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
        body.add(flavourTable).top().row()
        setPortraitBody(body)

        portraitFooter.clear()
        portraitFooter.background = portraitFooterBackground()
        portraitFooter.pad(10f, portraitPad, 14f, portraitPad)
        portraitFooter.defaults().height(52f)
        portraitFooter.add(getPortraitCircleButton("OtherIcons/BackArrow") { navigateEntries(-1) }).size(52f)
        val all = currentCategory.label.toTextButton()
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
        portraitHeader.pad(10f, portraitPad, 10f, portraitPad)
        if (back != null)
            portraitHeader.add(getPortraitCircleButton("OtherIcons/BackArrow", KeyCharAndCode.BACK, action = back)).size(48f).padRight(10f)
        val titles = Table().left()
        titles.add(title.toLabel(Color.WHITE, 26, hideIcons = true)).left().row()
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
        portraitFooter.pad(10f, portraitPad, 14f, portraitPad)
        val search = Table()
        search.background = skinStrings.getUiBackground("CivilopediaScreen/PortraitSearch",
            skinStrings.roundedEdgeRectangleMidShape, Color(1f, 1f, 1f, 0.12f))
        search.touchable = Touchable.enabled
        search.add(ImageGetter.getImage("OtherIcons/Search").apply { color = Color.LIGHT_GRAY }).size(20f).padLeft(14f).padRight(10f)
        search.add("Search every entry".toLabel(Color.LIGHT_GRAY, 18)).growX().left()
        search.onClick { searchPopup.open(true) }
        portraitFooter.add(search).growX().height(52f)
    }

    private fun portraitFooterBackground() =
        skinStrings.getUiBackground("CivilopediaScreen/PortraitFooter", tintColor = colorFromRGB(15, 32, 48))

    private fun portraitSectionLabel(text: String) = text.toLabel(Color.LIGHT_GRAY, 18)

    private fun getPortraitCircleButton(icon: String, key: KeyCharAndCode? = null, flip: Boolean = false, action: () -> Unit): Group {
        val image = ImageGetter.getImage(icon)
        image.setSize(22f, 22f)
        if (flip) image.rotation = 180f
        val button = image.surroundWithCircle(48f, resizeActor = false, color = skinStrings.skinConfig.baseColor)
        button.touchable = Touchable.enabled
        button.onActivation(action)
        if (key != null) button.keyShortcuts.add(key)
        return button
    }

    private fun getCategoryTile(categoryKey: CivilopediaCategories, count: Int): Table {
        val tile = Table()
        tile.background = skinStrings.getUiBackground("CivilopediaScreen/PortraitTile",
            skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(35, 58, 88))
        tile.touchable = Touchable.enabled
        tile.pad(8f, 10f, 8f, 10f)
        val icon = ImageGetter.getImage(categoryKey.headerIcon).apply { color = colorFromRGB(19, 36, 53) }
        tile.add(icon.surroundWithCircle(44f, color = colorFromRGB(238, 245, 251))).size(44f).padRight(10f)
        val text = Table()
        text.add(categoryKey.label.toLabel(Color.WHITE, 17, hideIcons = true).apply { wrap = true }).growX().left().row()
        text.add(count.tr().toLabel(Color.LIGHT_GRAY, 14)).left()
        tile.add(text).growX()
        tile.onClick { selectCategory(categoryKey) }
        return tile
    }

    private fun getGameCard(cardCategory: CivilopediaCategories, name: String, why: String): Table {
        val card = Table()
        card.background = skinStrings.getUiBackground("CivilopediaScreen/PortraitTile",
            skinStrings.roundedEdgeRectangleMidShape, colorFromRGB(35, 58, 88))
        card.touchable = Touchable.enabled
        card.pad(10f, 8f, 10f, 8f)
        card.top()
        val flavour = categoryToEntries[cardCategory]?.firstOrNull { it.name == name }?.flavour
        val image = cardCategory.getImage?.invoke(flavour?.getIconName() ?: name, 56f)
            ?: ImageGetter.getImage(cardCategory.headerIcon)
        card.add(image).size(56f).padBottom(6f).row()
        card.add(name.toLabel(Color.WHITE, 15, Align.center, hideIcons = true).apply { wrap = true }).width(96f).row()
        card.add(why.toLabel(Color.LIGHT_GRAY, 13, Align.center).apply { wrap = true }).width(96f)
        card.onClick { selectLink("${cardCategory.name}/$name") }
        return card
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
            else -> (index + entryIndex.size + direction) % entryIndex.size
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
