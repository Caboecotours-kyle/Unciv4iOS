package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.trade.Trade
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.citybutton.InfluenceTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.TrayArt
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.popups.tinted
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.pickerscreens.PortraitMapBackdrop
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView
import kotlin.math.floor
import kotlin.math.hypot
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/**
 * Creates the diplomacy screen for [viewingCivView].
 *
 * When [selectCivView] is given and [selectTrade] is not, that Civilization is selected as if clicked on the left side.
 * When [selectCivView] is given and [selectTrade] is not but [showTrade] is set, the [TradeTable] for that Civilization is shown.
 * When [selectCivView] and [selectTrade] are supplied, that Trade for that Civilization is selected, used for the counter-offer option from `TradePopup`.
 * Note calling this with [selectCivView] a City State and [selectTrade] supplied is **not allowed**.
 */
class DiplomacyScreen(
    internal val viewingCivView: CivView,
    private val selectCivView: ForeignCivView? = null,
    private val selectTrade: Trade? = null,
    private val showTrade: Boolean = selectTrade != null
): BaseScreen(), RecreateOnResize {
    companion object {
        private const val nationIconSize = 100f
        private const val nationIconPad = 10f
        private const val closeButtonSize = 50f
        /** distance of the floating close button from the top and right */
        private const val closeButtonPad = 10f
    }

    internal val viewingCiv: Civilization = viewingCivView.getCiv()
    private val selectCiv: Civilization? = selectCivView?.getCiv()
    internal val portraitWidth = 393f
    internal val portraitScale get() = safeAreaBoundsInWorld().width / portraitWidth
    internal val portraitHeight get() = safeAreaBoundsInWorld().height / portraitScale

    private val highlightColor: Color = clearColor.cpy().lerp(skin.getColor("color"), 0.333f)

    private val leftSideTable = Table().apply {
        // Portrait: the list sits on the sheet, so it must not paint its own navy over it
        if (isPortrait()) top()
        else background = skinStrings.getUiBackground("DiplomacyScreen/LeftSide", tintColor = clearColor)
    }
    private val leftSideScroll = ScrollPaneWithMinSize()

    private var highlightedCivButton: Table? = null
    private val highlightBackground = skinStrings.getUiBackground("DiplomacyScreen/SelectedCiv", tintColor = highlightColor)

    internal val rightSideTable = Table().apply {
        background = skinStrings.getUiBackground("DiplomacyScreen/RightSide",
            tintColor = if (isPortrait()) Color.valueOf("122536") else highlightColor)
    }

    private val splitPane = SplitPaneCenteringLeftSide()

    /** Portrait: the civ list and each civ's pages are one tray sheet over the map (see [showPortraitList]) */
    private var tray: TrayHost? = null
    /** The portrait tray sheet; pages rebuild its header, body and footer */
    internal val traySheet: TraySheet get() = tray!!.sheet
    /** Portrait Trade keeps its full-width page with a way back to the list */
    private val portraitHolder = Table()

    private val closeButton = if (isPortrait()) ImageGetter.getImage("OtherIcons/Close").apply {
        color = Color.valueOf("142536")
        setSize(24f, 24f)
    }.surroundWithCircle(closeButtonSize, resizeActor = false, color = Color.WHITE).apply {
        onActivation { game.popScreen() }
        keyShortcuts.add(KeyCharAndCode.BACK)
    } else getCloseButton(closeButtonSize) { game.popScreen() }
    private var leaderPortraitTexture: Texture? = null
    private var roundLeaderTexture: Texture? = null
    private var portraitBackdrop: PortraitMapBackdrop? = null

    internal fun stylePortraitPrimary(button: TextButton) {
        val style = TextButton.TextButtonStyle(button.style).apply {
            up = skinStrings.getUiBackground("DiplomacyScreen/PortraitPrimary",
                skinStrings.roundedEdgeRectangleMidShape, Color.valueOf("ffc93c"))
            down = skinStrings.getUiBackground("DiplomacyScreen/PortraitPrimaryDown",
                skinStrings.roundedEdgeRectangleMidShape, Color.valueOf("eab527"))
            disabled = skinStrings.getUiBackground("DiplomacyScreen/PortraitPrimaryDisabled",
                skinStrings.roundedEdgeRectangleMidShape, Color.valueOf("64592e"))
            fontColor = Color.valueOf("142536")
            disabledFontColor = Color.valueOf("dfd7ac")
        }
        button.style = style
        button.color = Color.WHITE
    }

    internal fun getPortraitLeaderArt(civ: Civilization): Actor? {
        val path = "Leaders/p_${civ.nation.leaderName.substringBefore(' ')}_thumb"
        val file = ImageGetter.findExternalImage(path) ?: return null
        leaderPortraitTexture?.dispose()
        val image = ImageGetter.getExternalImage(file)
        leaderPortraitTexture = (image.drawable as TextureRegionDrawable).region.texture
        return image
    }

    /** The leader's picture cut to a circle for the tray header, else the nation's emblem; tapping it opens the Civilopedia */
    internal fun leaderLead(civ: Civilization): Actor {
        val sheet = traySheet
        val file = if (civ.isCityState) null
            else ImageGetter.findExternalImage("Leaders/p_${civ.nation.leaderName.substringBefore(' ')}_thumb")
        val lead: Actor = if (file == null) ImageGetter.getNationPortrait(civ.nation, sheet.pt(52f))
        else {
            val square = Pixmap(file)
            val size = minOf(square.width, square.height)
            val round = Pixmap(size, size, Pixmap.Format.RGBA8888)
            round.blending = Pixmap.Blending.None
            val radius = size / 2f
            for (y in 0 until size) for (x in 0 until size) {
                val coverage = (radius + .5f - hypot(x + .5f - radius, y + .5f - radius)).coerceIn(0f, 1f)
                val pixel = square.getPixel(x + (square.width - size) / 2, y + (square.height - size) / 2)
                round.drawPixel(x, y, (pixel and 0xffffff00.toInt()) or ((pixel and 0xff) * coverage).toInt())
            }
            square.dispose()
            roundLeaderTexture?.dispose()
            val texture = Texture(round).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
            round.dispose()
            roundLeaderTexture = texture
            Image(texture)
        }
        lead.touchable = Touchable.enabled
        lead.onClick { openCivilopedia(civ.nation.makeLink()) }
        return lead
    }

    internal fun isNotPlayersTurn() = !GUI.isAllowedChangeState()

    init {
        // In cramped conditions, start the left side with enough width for nation icon and padding, but allow it to get squeezed until just the icon fits.
        // (and SplitPane will squeeze even beyond the minWidth our left side supplies - when the right side has a conflicting minWidth, then both get squeezed).
        splitPane.splitAmount = 0.2f.coerceAtLeast(leftSideScroll.prefWidth / stage.width)

        updateLeftSideTable(selectCiv)

        if (isPortrait()) {
            val canvas = portraitCanvasBounds()
            portraitBackdrop = PortraitMapBackdrop(viewingCiv).apply {
                setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
                this@DiplomacyScreen.stage.addActor(this)
            }
            val safe = safeAreaBoundsInWorld()
            portraitHolder.setTransform(true)
            portraitHolder.setBounds(safe.x, safe.y, portraitWidth, portraitHeight)
            portraitHolder.setScale(portraitScale)
            stage.addActor(portraitHolder)
            tray = TrayHost(this).also { stage.addActor(it) }
            traySheet.onGone = { game.popScreen() }
            showPortraitList()
        } else {
            splitPane.setFillParent(true)
            stage.addActor(splitPane)
        }

        positionCloseButton()
        stage.addActor(closeButton) // This must come after the split pane so it will be above, that the button will be clickable

        if (selectCiv != null) {
            if (showTrade) {
                val tradeTable = setTrade(selectCiv)
                if (selectTrade != null)
                    tradeTable.tradeView.setStagedTrade(selectTrade)
                tradeTable.offerColumnsTable.update()
            } else
                updateRightSide(selectCiv)
        }

        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia() }
        if (isPortrait()) traySheet.rise()
    }

    override fun getCivilopediaRuleset() = viewingCivView.ruleset

    private inner class ScrollPaneWithMinSize : ScrollPane(leftSideTable) {
        // On cramped screens 20% default splitAmount can make the left side smaller than a nation icon.
        // Also, content changes of the right side may claim too much space, pushing the split further to the left.
        // This reduces some ugliness - but remember Portrait lies, its size parameter means the *inner* circle.
        override fun getMinWidth() = nationIconSize * 1.1f // See PortraitNation's borderSize = size*0.1f parameter and how it's used
        override fun getPrefWidth() = minWidth + 2 * nationIconPad
    }

    private inner class SplitPaneCenteringLeftSide : SplitPane(leftSideScroll, rightSideTable, false, skin) {
        // A lot of effort for little effect, but noticeable on really cramped width.
        // SplitPane supports no events at all, but this centers the nation icons whenever splitAmount changes,
        // whether from touchDragged or clampSplitAmount (yes both actual methods in SplitPane).
        var lastSplitAmount = splitAmount
        override fun validate() {
            super.validate()
            if (splitAmount == lastSplitAmount) return
            lastSplitAmount = splitAmount
            leftSideScroll.scrollPercentX = 0.5f
        }
    }

    /** Portrait: the tray sheet (list and civ pages) is on screen, the Trade page is not */
    private fun showTray() {
        portraitBackdrop?.isVisible = true
        closeButton.isVisible = false
        portraitHolder.clear()
        portraitHolder.isVisible = false
        val host = tray!!
        if (traySheet.parent == null) host.addActor(traySheet)
    }

    /** Portrait: the civ list, a tray sheet over the map with its own header and close */
    internal fun showPortraitList() {
        showTray()
        val knownCivs = viewingCiv.diplomacyFunctions.getKnownCivsSorted().toList()
        val sheet = portraitPage("Diplomacy", getMetSubtitle(knownCivs), null, null)
        sheet.minShare = 0f
        val rows = ArrayList<Actor>()
        if (!viewingCiv.isSpectator())
            rows += sheet.row(viewingCiv.nation.leaderName,
                "${viewingCiv.civName}, you  ·  Score ${viewingCiv.calculateTotalScore().toInt()}",
                lead = ImageGetter.getNationPortrait(viewingCiv.nation, sheet.pt(44f)), chevron = false)
        for (civ in knownCivs) rows += getPortraitCivRow(civ)
        if (rows.isNotEmpty()) {
            sheet.addToBody(sheet.section("Everyone you have met"))
            sheet.addToBody(sheet.card(rows))
        }
        if (knownCivs.isEmpty()) {
            sheet.addToBody(sheet.emptyState("OtherIcons/Diplomacy", "Meet another civilization!"))
            sheet.addToBody("Explore the map until you encounter another civilization!".toLabel(TrayColors.INK2, sheet.font(14.5f), Align.center, hideIcons = true)
                .apply { wrap = true }).padLeft(sheet.pt(20f)).padRight(sheet.pt(20f)).padTop(-sheet.pt(18f)).padBottom(sheet.pt(12f))
        }
        sheet.fit()
    }

    private fun getMetSubtitle(knownCivs: List<Civilization>): String {
        val majors = knownCivs.count { !it.isCityState }
        val cityStates = knownCivs.count { it.isCityState }
        val met = listOfNotNull(
            if (majors > 0) "$majors " + (if (majors == 1) "civilization" else "civilizations") else null,
            if (cityStates > 0) "$cityStates " + (if (cityStates == 1) "city-state" else "city-states") else null
        )
        return if (met.isEmpty()) "Nobody met yet" else met.joinToString(" and ") + " met"
    }

    /** One met civ: emblem, leader (or city-state) name, and where you stand; a city-state with quests for you says so */
    private fun getPortraitCivRow(civ: Civilization): Actor {
        val sheet = traySheet
        val diplomacy = civ.getDiplomacyManager(viewingCiv)!!
        val level = diplomacy.relationshipLevel()
        val standing = when {
            civ.isAtWarWith(viewingCiv) -> DiplomaticStatus.War.name.tr()
            civ.isHuman() && viewingCiv.isHuman() -> getHumanRelationshipText(diplomacy).tr()
            diplomacy.diplomaticStatus == DiplomaticStatus.DefensivePact -> Constants.defensivePact.tr()
            civ.isCityState -> "${level.name.tr()} (${diplomacy.getInfluence().toInt()})"
            else -> level.name.tr()
        }
        val who = if (civ.isCityState) civ.cityStateType.name.tr() else civ.civName.tr(hideIcons = true)
        val title = if (civ.isCityState || civ.nation.leaderName.isEmpty()) civ.civName else civ.nation.leaderName
        val quest = if (civ.isCityState && civ.questManager.haveQuestsFor(viewingCiv))
            sheet.pill("Quest", TrayColors.LOCK_BG, TrayColors.LOCK_INK) else null
        return sheet.row(title, "$who  ·  $standing", lead = ImageGetter.getNationPortrait(civ.nation, sheet.pt(44f)), trailing = quest) {
            updateRightSide(civ)
        }.apply { name = "portrait-civ-${civ.civName}" }
    }

    /** Starts a portrait page on the tray sheet: header (with back when [onBack] is set), empty toolbar, body and footer.
     *  On a pushed page Back and Escape step back like its back button; on the list they close. */
    internal fun portraitPage(title: String, subtitle: String?, lead: Actor?, onBack: (() -> Unit)?): TraySheet {
        val sheet = traySheet
        sheet.minShare = .6f
        sheet.header(title, subtitle, lead, onBack)
        if (onBack != null) {
            sheet.findActor<Actor>("portrait-sheet-close")?.keyShortcuts?.clear()
            sheet.findActor<Actor>("portrait-sheet-back")?.keyShortcuts?.add(KeyCharAndCode.BACK)
        }
        sheet.toolbar(null)
        sheet.removeFooter()
        sheet.body.clear()
        return sheet
    }

    /** The leader's words in the mock's speech bubble */
    internal fun portraitQuote(text: String): Actor {
        val sheet = traySheet
        return Table().apply {
            left()
            add(Table().apply {
                background = sheet.art.rounded(sheet.pt(16f)).tinted(TrayColors.FILL)
                add(text.toLabel(TrayColors.INK, sheet.font(15f), hideIcons = true).apply { wrap = true })
                    .growX().pad(sheet.pt(10f), sheet.pt(14f), sheet.pt(10f), sheet.pt(14f))
            }).left().growX()
            add().width(sheet.pt(40f))
        }
    }

    /** Footer button that can be off: an unavailable action stays visible, dimmed and inert */
    internal fun portraitButton(text: String, kind: TraySheet.ButtonKind, icon: String?, enabled: Boolean, action: () -> Unit): Actor =
        traySheet.button(text, kind, icon) { action() }.apply {
            if (!enabled) {
                touchable = Touchable.disabled
                color.a = .45f
            }
        }

    /** Portrait Trade: its own full-width page over the map, with a way back to the list */
    private fun showPortraitDetail() {
        if (!isPortrait()) return
        tray?.let { traySheet.remove() }
        portraitHolder.isVisible = true
        closeButton.isVisible = true
        portraitHolder.clear()
        val back = "‹  Back".toTextButton()
        back.style = TextButton.TextButtonStyle(back.style).apply {
            up = null
            down = null
            over = null
        }
        back.onActivation { showPortraitList() }
        back.keyShortcuts.add(KeyCharAndCode.BACK)
        portraitHolder.add(back).left().pad(closeButtonPad).height(closeButtonSize).row()
        portraitHolder.add(rightSideTable).grow()
    }

    private fun positionCloseButton() {
        if (isPortrait()) {
            val safe = safeAreaBoundsInWorld()
            closeButton.setScale(portraitScale)
            closeButton.setPosition(safe.x + safe.width - (closeButtonPad + closeButtonSize) * portraitScale,
                safe.y + safe.height - (closeButtonPad + closeButtonSize) * portraitScale)
        } else closeButton.setPosition(stage.width - closeButtonPad,
            stage.height - closeButtonPad, Align.topRight)
    }

    internal fun updateLeftSideTable(selectCiv: Civilization?) {
        if (isPortrait()) return // the portrait list is rebuilt whenever it is shown
        leftSideTable.clear()
        leftSideTable.add().padBottom(closeButtonPad).row()  // no default pad, and make distance of first civ to top same as for the close button

        var selectCivY = 0f

        for (civ in viewingCiv.diplomacyFunctions.getKnownCivsSorted()) {
            if (civ == selectCiv) {
                selectCivY = leftSideTable.prefHeight
            }

            val civIndicator = ImageGetter.getNationPortrait(civ.nation, nationIconSize)

            val diplomacy = civ.getDiplomacyManager(viewingCiv)!!
            val relationLevel = diplomacy.relationshipLevel()
            val relationshipIcon = if (civ.isCityState && relationLevel == RelationshipLevel.Ally)
                ImageGetter.getImage("OtherIcons/Star")
                    .surroundWithCircle(size = 30f, color = relationLevel.color).apply {
                        actor.color = Color.GOLD
                    }
            else
                ImageGetter.getCircle(
                    color = if (civ.isHuman() && viewingCiv.isHuman()) getHumanRelationshipColor(diplomacy)
                    else if (diplomacy.diplomaticStatus == DiplomaticStatus.DefensivePact) Color.PURPLE
                    else if (civ.isAtWarWith(viewingCiv)) Color.RED
                    else relationLevel.color,
                    size = 30f
                )
            civIndicator.addActor(relationshipIcon)

            if (civ.isCityState) {
                val innerColor = civ.gameInfo.ruleset.nations[civ.civName]!!.getInnerColor()
                val typeIcon = ImageGetter.getImage("CityStateIcons/"+civ.cityStateType.name)
                    .surroundWithCircle(size = 35f, color = innerColor).apply {
                        actor.color = ImageGetter.CHARCOAL
                    }
                civIndicator.addActor(typeIcon)
                typeIcon.y = floor(civIndicator.height - typeIcon.height)
                typeIcon.x = floor(civIndicator.width - typeIcon.width)
            }

            if (civ.isCityState && civ.questManager.haveQuestsFor(viewingCiv)) {
                val questIcon = ImageGetter.getImage(NotificationIcon.Quest)
                    .surroundWithCircle(size = 30f, color = Color.GOLDENROD)
                civIndicator.addActor(questIcon)
                questIcon.x = floor(civIndicator.width - questIcon.width)
            }

            val civNameLabel = civ.civName.toLabel(hideIcons = true)

            // The wrapper serves only to highlight the selected civ better
            val civButton = Table().apply {
                defaults().pad(nationIconPad)
                add(civIndicator).row()
                add(civNameLabel).row()
                onClick {
                    updateRightSide(civ)
                    highlightCiv(this)
                }
                if (civ == selectCiv) highlightCiv(this)
            }

            leftSideTable.add(civButton).padBottom(20f - nationIconPad).growX().row()
        }

        if (selectCivY != 0f) {
            leftSideScroll.layout()
            leftSideScroll.scrollY = selectCivY + (nationIconSize + 2 * nationIconPad - stage.height) / 2
            leftSideScroll.updateVisualScroll()
        }
    }

    private fun highlightCiv(civButton: Table) {
        highlightedCivButton?.background = null
        civButton.background = highlightBackground
        highlightedCivButton = civButton
    }

    internal fun updateRightSide(otherCiv: Civilization) {
        UncivGame.Current.musicController.chooseTrack(otherCiv.civName,
            MusicMood.peaceOrWar(viewingCiv.isAtWarWith(otherCiv)),MusicTrackChooserFlags.setSelectNation)
        if (isPortrait()) {
            // Portrait: the civ's page is pushed onto the tray sheet, back returns to the list
            showTray()
            if (otherCiv.isCityState) CityStateDiplomacyTable(this).showPortraitPage(otherCiv)
            else MajorCivDiplomacyTable(this).showPortraitPage(otherCiv)
            return
        }
        rightSideTable.clear()
        val content = if (otherCiv.isCityState) CityStateDiplomacyTable(this).getCityStateDiplomacyTable(otherCiv)
            else MajorCivDiplomacyTable(this).getMajorCivDiplomacyTable(otherCiv)
        rightSideTable.add(ScrollPane(content)).height(stage.height)
    }

    //region Major Civ Diplomacy

    internal fun setTrade(otherCiv: Civilization): TradeTable {
        portraitBackdrop?.isVisible = true
        rightSideTable.clear()
        val tradeTable = TradeTable(viewingCivView, viewingCivView.gameView.getForeignCivView(otherCiv), this)
        if (isPortrait()) rightSideTable.add(tradeTable).width(portraitWidth)
            .height(portraitHeight - closeButtonSize - 2 * closeButtonPad)
        else rightSideTable.add(tradeTable)
        showPortraitDetail()
        return tradeTable
    }

    /**
     * Helper function for updateLeftSideTable() and getHumanRelationshipTable() (human-human relationships only)
     * @param otherCivDiplomacyManager Other human player [DiplomacyManager]
     * @return Relationship color between two human players
     */
    private fun getHumanRelationshipColor(otherCivDiplomacyManager: DiplomacyManager): Color {
        // should ensure colors align with equivalent human-AI relationship colors (RelationshipLevel ?)
        // as of writing, RelationshipLevel colors are not appropriate IMO, so using hardcoded values for now
        return if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.DefensivePact)
            Color.CYAN
        else if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            Color.GREEN
        else if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.War)
            Color.RED
        else
            RelationshipLevel.Neutral.color
    }

    /**
     * Human-human relationships only. See also: getHumanRelationshipColor()
     * @param otherCivDiplomacyManager Other human player [DiplomacyManager]
     * @return Relationship text (e.g. "Friend")
     */
    private fun getHumanRelationshipText(otherCivDiplomacyManager: DiplomacyManager): String {
        return if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.DefensivePact)
            Constants.defensivePact
        else if (otherCivDiplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            RelationshipLevel.Friend.name
        else if (otherCivDiplomacyManager.diplomaticStatus == DiplomaticStatus.War)
            RelationshipLevel.Enemy.name
        else
            RelationshipLevel.Neutral.name
    }

    /**
     * @param otherCivDiplomacyManager Other human player [DiplomacyManager]
     * @return Relationship [Table] for human vs human player only
     */
    internal fun getHumanRelationshipTable(otherCivDiplomacyManager: DiplomacyManager): Table {
        val relationshipTable = Table()
        val relationshipColor: Color = getHumanRelationshipColor(otherCivDiplomacyManager)
        val relationshipText: String = getHumanRelationshipText(otherCivDiplomacyManager)

        relationshipTable.add("{Our relationship}: ".toLabel())
        relationshipTable.add(relationshipText.toLabel(relationshipColor)).row()
        return relationshipTable
    }

    internal fun getRelationshipTable(otherCivDiplomacyManager: DiplomacyManager): Table {
        val relationshipTable = Table()

        val opinionOfUs =
            if (otherCivDiplomacyManager.civInfo.isCityState) otherCivDiplomacyManager.getInfluence().toInt()
            else otherCivDiplomacyManager.opinionOfOtherCiv().toInt()

        relationshipTable.add("{Our relationship}: ".toLabel())
        val relationshipLevel = otherCivDiplomacyManager.relationshipLevel()
        val relationshipText = relationshipLevel.name.tr() + " ($opinionOfUs)"
        val relationshipColor = when (relationshipLevel) {
            RelationshipLevel.Neutral -> Color.WHITE
            RelationshipLevel.Favorable, RelationshipLevel.Friend,
            RelationshipLevel.Ally -> Color.GREEN
            RelationshipLevel.Afraid -> Color.YELLOW
            else -> Color.RED
        }

        relationshipTable.add(relationshipText.toLabel(relationshipColor)).row()
        if (otherCivDiplomacyManager.civInfo.isCityState)
            relationshipTable.add(
                InfluenceTable(
                    otherCivDiplomacyManager.getInfluence(),
                    relationshipLevel,
                    200f, 10f
                )
            ).colspan(2).pad(5f)
        return relationshipTable
    }

    internal fun getDeclareWarButton(
        diplomacyManager: DiplomacyManager,
        otherCiv: Civilization
    ): TextButton {
        val declareWarButton = "Declare war".toTextButton(skin.get("negative", TextButton.TextButtonStyle::class.java))
        val turnsToPeaceTreaty = diplomacyManager.turnsToPeaceTreaty()
        if (turnsToPeaceTreaty > 0) {
            declareWarButton.disable()
            declareWarButton.setText(declareWarButton.text.toString() + " (${turnsToPeaceTreaty.tr()}${Fonts.turn})")
        }
        declareWarButton.onClick { confirmDeclareWar(diplomacyManager, otherCiv) }
        if (isNotPlayersTurn()) declareWarButton.disable()
        return declareWarButton
    }

    /** Portrait row for Declare war: last, in danger red, still confirming; waits out a peace treaty like the button */
    internal fun getDeclareWarRow(diplomacyManager: DiplomacyManager, otherCiv: Civilization): Actor {
        val sheet = traySheet
        val turnsToPeaceTreaty = diplomacyManager.turnsToPeaceTreaty()
        val enabled = turnsToPeaceTreaty <= 0 && !isNotPlayersTurn()
        return sheet.row("Declare war",
            meta = if (turnsToPeaceTreaty > 0) "${turnsToPeaceTreaty.tr()}${Fonts.turn}" else null,
            lead = sheet.disc(ImageGetter.getImage("OtherIcons/Fire"), TraySheet.Tone.Danger, recolor = false),
            state = if (enabled) TraySheet.RowState.Danger else TraySheet.RowState.Disabled,
            onTap = if (enabled) ({ confirmDeclareWar(diplomacyManager, otherCiv) }) else null)
            .apply { name = "portrait-declare-war" }
    }

    private fun confirmDeclareWar(diplomacyManager: DiplomacyManager, otherCiv: Civilization) {
        ConfirmPopup(this, getDeclareWarButtonText(otherCiv), "Declare war") {
            diplomacyManager.declareWar()
            setRightSideFlavorText(otherCiv, otherCiv.nation.attacked, "Very well.")
            updateLeftSideTable(otherCiv)
            val music = UncivGame.Current.musicController
            music.chooseTrack(otherCiv.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
            music.playVoice("${otherCiv.civName}.attacked")
        }.open()
    }

    private fun getDeclareWarButtonText(otherCiv: Civilization): String {
        val messageLines = arrayListOf<String>()
        messageLines += "Declare war on [${otherCiv.civName}]?"
        
        if (otherCiv.getDiplomacyManager(viewingCiv)!!.hasFlag(DiplomacyFlags.AgreedToNotAttackUs))
            messageLines += "This will break your promise to not attack them. Other leaders will view this unfavorably."
        
        // Tell the player who all will join the other side from defensive pacts
        val otherCivDefensivePactList = otherCiv.diplomacy.values.filter {
            otherCivDiploManager -> otherCivDiploManager.otherCiv != viewingCiv
            && otherCivDiploManager.diplomaticStatus == DiplomaticStatus.DefensivePact
            && !otherCivDiploManager.otherCiv.isAtWarWith(viewingCiv) }
            .map { it.otherCiv }

        // Defensive pact chains are not allowed now
        for (civ in otherCivDefensivePactList) {
            messageLines += if (viewingCiv.knows(civ)) {
                "[${civ.civName}] will also join them in the war"
            } else {
                "[An unknown civilization] will also join them in the war"
            }
        }

        // Tell the player that their defensive pacts will be canceled.
        for (civDiploManager in viewingCiv.diplomacy.values) {
            if (civDiploManager.otherCiv != otherCiv
                && civDiploManager.diplomaticStatus == DiplomaticStatus.DefensivePact
                && !otherCivDefensivePactList.contains(civDiploManager.otherCiv)) {
                messageLines += "This will cancel your defensive pact with [${civDiploManager.otherCiv.civName}]"
            }
        }
        return messageLines.joinToString("\n") { "{$it}" }
    }

    //endregion

    // response currently always gets "Very Well.", but that may expand in the future.
    @Suppress("SameParameterValue")
    internal fun setRightSideFlavorText(
        otherCiv: Civilization,
        flavorText: String,
        response: String
    ) {
        if (isPortrait()) {
            // The leader's answer on the tray sheet; the response button returns to their page
            showTray()
            val sheet = portraitPage(otherCiv.nation.leaderName.ifEmpty { otherCiv.civName }, otherCiv.civName,
                leaderLead(otherCiv)) { updateRightSide(otherCiv) }
            sheet.addToBody(portraitQuote(flavorText), 4f)
            sheet.footer().add(portraitButton(response, TraySheet.ButtonKind.Primary, null, true) { updateRightSide(otherCiv) }
                .apply { keyShortcuts.add(KeyCharAndCode.SPACE) }).growX().padRight(0f)
            sheet.fit()
            return
        }
        val diplomacyTable = Table()
        diplomacyTable.defaults().pad(10f)
        diplomacyTable.add(LeaderIntroTable(otherCiv))
        diplomacyTable.addSeparator()
        diplomacyTable.add(flavorText.toLabel()).row()

        val responseButton = response.toTextButton()
        responseButton.onActivation { updateRightSide(otherCiv) }
        responseButton.keyShortcuts.add(KeyCharAndCode.SPACE)
        diplomacyTable.add(responseButton)

        rightSideTable.clear()
        rightSideTable.add(diplomacyTable)
    }

    internal fun getGoToOnMapButton(civilization: Civilization): TextButton {
        val goToOnMapButton = "Go to on map".toTextButton()
        goToOnMapButton.onClick { goToOnMap(civilization) }
        return goToOnMapButton
    }

    internal fun getGoToOnMapRow(civilization: Civilization): Actor = traySheet.row("Go to on map",
        lead = traySheet.disc(ImageGetter.getImage("OtherIcons/CrosshairB"))) { goToOnMap(civilization) }

    private fun goToOnMap(civilization: Civilization) {
        val worldScreen = UncivGame.Current.resetToWorldScreen()
        worldScreen.mapHolder.setCenterPosition(civilization.getCapital()!!.location.toHexCoord(), selectUnit = false)
    }

    /** Calculate a width for [TradeTable] two-column layout, called from [OfferColumnsTable]
     *
     *  _Caller is responsible to not exceed this **including its own padding**_
     */
    // Note breaking the rule above will squeeze the leftSideScroll to the left - cumulatively.
    internal fun getTradeColumnsWidth() = rightSideWidth() / 2

    /** Recommended Cell width for wrappable Labels spanning the right side, e.g. city-state protectors */
    internal fun rightSideLabelWidth() = rightSideWidth() - 40f

    private fun rightSideWidth(): Float {
        if (isPortrait()) return stage.width - 2 * closeButtonPad // the detail page spans the screen
        splitPane.validate() // Ensure rightSideTable is sized
        return rightSideTable.width
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        positionCloseButton()
    }

    override fun recreate(): BaseScreen = DiplomacyScreen(viewingCivView, selectCivView, selectTrade, showTrade)

    override fun dispose() {
        leaderPortraitTexture?.dispose()
        roundLeaderTexture?.dispose()
        portraitBackdrop?.dispose()
        super.dispose()
    }
}

/** Stage-wide host of the portrait tray sheet: draws the mock's scrim over the map as the sheet rises, is dragged and
 *  leaves, and frees the sheet's textures when the screen goes away */
private class TrayHost(private val screen: BaseScreen) : Group() {
    val art = TrayArt(screen.stage)
    val sheet = TraySheet(screen, art)
    private val white = ImageGetter.getWhiteDotDrawable()

    init {
        name = "portrait-diplomacy-sheet"
        touchable = Touchable.childrenOnly
        setBounds(0f, 0f, screen.stage.width, screen.stage.height)
        addActor(sheet)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (sheet.parent == this) {
            val canvas = screen.portraitCanvasBounds()
            batch.setColor(TrayColors.SCRIM.r, TrayColors.SCRIM.g, TrayColors.SCRIM.b, TrayColors.SCRIM.a * sheet.scrim * parentAlpha)
            white.draw(batch, canvas.x, canvas.y, canvas.width, canvas.height)
        }
        super.draw(batch, parentAlpha)
    }

    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null) art.release()
    }
}
