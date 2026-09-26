package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
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
import com.unciv.ui.components.input.SwipeDownToClose
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.tilegroups.citybutton.InfluenceTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.pickerscreens.PortraitMapBackdrop
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView
import kotlin.math.floor
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
        /** Portrait list rows put a smaller icon beside the name */
        private const val portraitIconSize = 60f
        private const val nationIconPad = 10f
        private const val closeButtonSize = 50f
        /** distance of the floating close button from the top and right */
        private const val closeButtonPad = 10f
        /** Portrait list sheet leaves this much map visible above it (approved mock: sheet top at 120px) */
        private const val sheetTopGap = 64f
        private val sheetColor = Color.valueOf("122536")
        private val ink2 = Color.valueOf("b7cde0")
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

    /** Portrait replaces the side-by-side split with one full-width page: the civ list, or one civ's details */
    private val portraitHolder = Table()

    private val closeButton = if (isPortrait()) ImageGetter.getImage("OtherIcons/Close").apply {
        color = Color.valueOf("142536")
        setSize(24f, 24f)
    }.surroundWithCircle(closeButtonSize, resizeActor = false, color = Color.WHITE).apply {
        onActivation { game.popScreen() }
        keyShortcuts.add(KeyCharAndCode.BACK)
    } else getCloseButton(closeButtonSize) { game.popScreen() }
    private var leaderPortraitTexture: Texture? = null
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
                isVisible = false
                this@DiplomacyScreen.stage.addActor(this)
            }
            val safe = safeAreaBoundsInWorld()
            portraitHolder.setTransform(true)
            portraitHolder.setBounds(safe.x, safe.y, portraitWidth, portraitHeight)
            portraitHolder.setScale(portraitScale)
            stage.addActor(portraitHolder)
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

    /** Portrait: the civ list is a sheet over the map, with its own header and close */
    private fun showPortraitList() {
        portraitBackdrop?.isVisible = true
        closeButton.isVisible = false
        portraitHolder.clear()
        val knownCivs = viewingCiv.diplomacyFunctions.getKnownCivsSorted().toList()
        val sheet = Table().apply {
            background = skinStrings.getUiBackground("DiplomacyScreen/PortraitSheet",
                skinStrings.roundedTopEdgeRectangleSmallShape, sheetColor)
        }
        sheet.add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .3f) })
            .size(44f, 5f).padTop(8f).row()
        val header = getPortraitSheetHeader(knownCivs)
        sheet.add(header).growX().row()
        // Grab bar and header are the drag zone; swiping them down closes like ×
        sheet.touchable = Touchable.enabled
        header.touchable = Touchable.enabled
        sheet.addListener(SwipeDownToClose(sheet, header) { game.popScreen() })
        if (knownCivs.isEmpty()) {
            val guidance = Table().apply {
                background = skinStrings.getUiBackground("DiplomacyScreen/PortraitEmpty",
                    skinStrings.roundedEdgeRectangleShape, Color.valueOf("1c3249"))
                pad(16f)
                val icon = ImageGetter.getImage("OtherIcons/Diplomacy")
                add(icon).size(48f).padRight(14f)
                val copy = Table()
                copy.add("Meet another civilization!".toLabel(Color.WHITE, 16, hideIcons = true)).left().row()
                copy.add("Explore the map until you encounter another civilization!".toLabel(ink2, 14, hideIcons = true)
                    .apply { wrap = true }).width(portraitWidth - 124f).left().padTop(4f)
                add(copy).growX()
            }
            sheet.add(guidance).width(portraitWidth - 28f).padTop(10f).row()
        }
        sheet.add(leftSideScroll).grow()
        portraitHolder.add(sheet).grow().padTop(sheetTopGap)
    }

    private fun getPortraitSheetHeader(knownCivs: List<Civilization>) = Table().apply {
        pad(10f, 16f, 6f, 16f)
        val majors = knownCivs.count { !it.isCityState }
        val cityStates = knownCivs.count { it.isCityState }
        val met = listOfNotNull(
            if (majors > 0) "$majors " + (if (majors == 1) "civilization" else "civilizations") else null,
            if (cityStates > 0) "$cityStates " + (if (cityStates == 1) "city-state" else "city-states") else null
        )
        val subtitle = if (met.isEmpty()) "Nobody met yet" else met.joinToString(" and ") + " met"
        val titles = Table()
        titles.add("Diplomacy".toLabel(fontSize = 23)).left().row()
        titles.add(subtitle.toLabel(ink2, 14)).left().padTop(1f)
        add(titles).expandX().left()
        val close = ImageGetter.getImage("OtherIcons/Close").apply {
            color = ink2
            setSize(18f, 18f)
        }.surroundWithCircle(48f, resizeActor = false, color = Color(1f, 1f, 1f, .09f))
        close.onActivation { game.popScreen() }
        close.keyShortcuts.add(KeyCharAndCode.BACK)
        add(close).size(48f)
    }

    /** Portrait: after any right-side content change, show it full width with a way back to the list */
    private fun showPortraitDetail() {
        if (!isPortrait()) return
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
        leftSideTable.clear()
        leftSideTable.add().padBottom(closeButtonPad).row()  // no default pad, and make distance of first civ to top same as for the close button

        if (isPortrait() && !viewingCiv.isSpectator()) {
            leftSideTable.add("Everyone you have met".toLabel(ink2, 14, hideIcons = true))
                .left().pad(8f, 16f, 6f, 16f).row()
            val ownRow = Table().apply {
                background = skinStrings.getUiBackground("DiplomacyScreen/PortraitOwnRow",
                    skinStrings.roundedEdgeRectangleShape, Color.valueOf("1c3249"))
                pad(10f)
                add(ImageGetter.getNationPortrait(viewingCiv.nation, 46f)).size(46f).padRight(12f)
                val text = Table()
                text.add(viewingCiv.nation.leaderName.toLabel(Color.WHITE, 17, hideIcons = true)
                    .apply { wrap = true }).width(portraitWidth - 116f).left().row()
                text.add("${viewingCiv.civName}, you  ·  Score ${viewingCiv.calculateTotalScore().toInt()}"
                    .toLabel(ink2, 14, hideIcons = true).apply { wrap = true })
                    .width(portraitWidth - 116f).left().padTop(2f)
                add(text).growX()
            }
            leftSideTable.add(ownRow).growX().pad(0f, 14f, 8f, 14f).row()
        }

        var selectCivY = 0f

        for (civ in viewingCiv.diplomacyFunctions.getKnownCivsSorted()) {
            if (civ == selectCiv) {
                selectCivY = leftSideTable.prefHeight
            }

            val civIndicator = ImageGetter.getNationPortrait(civ.nation, if (isPortrait()) portraitIconSize else nationIconSize)

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
                if (isPortrait()) {
                    // one full-width row per civ: icon beside the name
                    add(civIndicator)
                    add(civNameLabel).expandX().left()
                } else {
                    add(civIndicator).row()
                    add(civNameLabel).row()
                }
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
            leftSideScroll.scrollY = selectCivY + (nationIconSize + 2 * nationIconPad -
                if (isPortrait()) portraitHeight else stage.height) / 2
            leftSideScroll.updateVisualScroll()
        }
    }

    private fun highlightCiv(civButton: Table) {
        highlightedCivButton?.background = null
        civButton.background = highlightBackground
        highlightedCivButton = civButton
    }

    internal fun updateRightSide(otherCiv: Civilization) {
        portraitBackdrop?.isVisible = false
        rightSideTable.clear()
        UncivGame.Current.musicController.chooseTrack(otherCiv.civName,
            MusicMood.peaceOrWar(viewingCiv.isAtWarWith(otherCiv)),MusicTrackChooserFlags.setSelectNation)
        val content = if (otherCiv.isCityState) CityStateDiplomacyTable(this).getCityStateDiplomacyTable(otherCiv)
            else MajorCivDiplomacyTable(this).getMajorCivDiplomacyTable(otherCiv)
        val detailHeight = if (isPortrait()) portraitHeight - closeButtonSize - 2 * closeButtonPad else stage.height
        if (isPortrait() && otherCiv.isCityState)
            rightSideTable.add(content).width(portraitWidth).height(detailHeight)
        else rightSideTable.add(ScrollPane(content)).height(detailHeight)
        showPortraitDetail()
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
        declareWarButton.onClick {
            ConfirmPopup(this, getDeclareWarButtonText(otherCiv), "Declare war") {
                diplomacyManager.declareWar()
                setRightSideFlavorText(otherCiv, otherCiv.nation.attacked, "Very well.")
                updateLeftSideTable(otherCiv)
                val music = UncivGame.Current.musicController
                music.chooseTrack(otherCiv.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
                music.playVoice("${otherCiv.civName}.attacked")
            }.open()
        }
        if (isNotPlayersTurn()) declareWarButton.disable()
        return declareWarButton
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
        showPortraitDetail()
    }

    internal fun getGoToOnMapButton(civilization: Civilization): TextButton {
        val goToOnMapButton = "Go to on map".toTextButton()
        goToOnMapButton.onClick {
            val worldScreen = UncivGame.Current.resetToWorldScreen()
            worldScreen.mapHolder.setCenterPosition(civilization.getCapital()!!.location.toHexCoord(), selectUnit = false)
        }
        return goToOnMapButton
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
        portraitBackdrop?.dispose()
        super.dispose()
    }
}
