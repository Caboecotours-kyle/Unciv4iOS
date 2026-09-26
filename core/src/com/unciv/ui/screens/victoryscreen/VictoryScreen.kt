package com.unciv.ui.screens.victoryscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.VictoryData
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.Victory
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.extensions.areSecretKeysPressed
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.PortraitStatIcons
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.ui.screens.pickerscreens.PortraitMapBackdrop
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.worldscreen.WorldScreen
import kotlin.math.sqrt
import kotlin.math.roundToInt
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane
import yairm210.purity.annotations.Readonly
import java.util.EnumSet

class VictoryScreen(
    private val worldScreen: WorldScreen,
    pageNumber: Int = 0
) : PickerScreen(), RecreateOnResize {
    private val music get() = UncivGame.Current.musicController
    private val gameInfo = worldScreen.gameInfo
    private val playerCiv = worldScreen.selectedGameView.civView.getCiv()
    private val portraitStats = PortraitStatIcons()
    private var endPortraitTexture: Texture? = null
    private var portraitBackdrop: PortraitMapBackdrop? = null
    private var endBackdrop: PortraitMapBackdrop? = null
    /** Portrait uses compact victory tracks with the other result pages in a secondary chooser. */
    private val portrait = isPortrait()
    private val tabs = TabbedPager(separatorColor = Color.WHITE, shortcutScreen = this,
        wrapHeaderWidth = if (portrait) 393f else 0f)

    internal class CivWithStat(val civ: Civilization, val value: Int) {
        constructor(civ: Civilization, category: RankingType) : this(civ, civ.getStatForRanking(category))
    }

    private enum class VictoryTabs(
        val key: Char,
        val caption: String? = null,
        val allowAsSecret: Boolean = false
    ) {
        OurStatus('O', caption = "Our status") {
            override fun getContent(parent: VictoryScreen) =
                if (parent.portrait) VictoryScreenTracks(parent.worldScreen, 393f) else VictoryScreenOurVictory(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) = playerCiv.isSpectator()
        },
        Global('G', caption = "Global status") {
            override fun getContent(parent: VictoryScreen) = VictoryScreenGlobalVictory(parent.worldScreen, parent.portrait)
        },
        Illustration('I') {
            override fun getContent(parent: VictoryScreen) = VictoryScreenIllustrations(parent, parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) = !VictoryScreenIllustrations.enablePage(playerCiv)
        },
        Demographics('D', allowAsSecret = true) {
            override fun getContent(parent: VictoryScreen) = VictoryScreenDemographics(parent.worldScreen, parent.portrait)
            override fun isHidden(playerCiv: Civilization) =
                !playerCiv.isSpectator()
                    && !(playerCiv.gameInfo.gameParameters.showCivilizationStats == true && playerCiv.gameInfo.gameParameters.showDemographics)
                    && playerCiv.gameInfo.victoryData == null
        },
        Rankings('R', allowAsSecret = true) {
            override fun getContent(parent: VictoryScreen) = VictoryScreenCivRankings(parent.worldScreen, parent.portrait)
            override fun isHidden(playerCiv: Civilization) =
                !playerCiv.isSpectator()
                    && !(playerCiv.gameInfo.gameParameters.showCivilizationStats == true && playerCiv.gameInfo.gameParameters.showRankings)
                    && playerCiv.gameInfo.victoryData == null
        },
        Charts('C') {
            override fun getContent(parent: VictoryScreen) = VictoryScreenCharts(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) =
                if (playerCiv.isSpectator())
                    playerCiv.gameInfo.civilizations.all { it.statsHistory.size < 2 }
                else
                    (playerCiv.statsHistory.size < 2
                        || !(playerCiv.gameInfo.gameParameters.showCivilizationStats == true && playerCiv.gameInfo.gameParameters.showCharts))
                    && playerCiv.gameInfo.victoryData == null
        },
        Replay('P', allowAsSecret = true) {
            override fun getContent(parent: VictoryScreen) = VictoryScreenReplay(parent.worldScreen)
            override fun isHidden(playerCiv: Civilization) =
                !playerCiv.isSpectator()
                    && playerCiv.gameInfo.victoryData == null
                    && playerCiv.isAlive()
                    // We show the replay after 5 turns. This is to ensure that the replay
                    // slider doesn't look weird.
                    && playerCiv.gameInfo.turns < 5
        };
        abstract fun getContent(parent: VictoryScreen): Table
        open fun isHidden(playerCiv: Civilization) = false
    }

    init {
        worldScreen.autoPlay.stopAutoPlay()
        //**************** Set up the tabs ****************
        splitPane.setFirstWidget(tabs)
        val iconSize = Constants.headingFontSize.toFloat()

        for (tab in VictoryTabs.entries) {
            val tabHidden = tab.isHidden(playerCiv)
            if (tabHidden && !(tab.allowAsSecret && Gdx.input.areSecretKeysPressed()))
                continue
            val icon = ImageGetter.getImage("VictoryScreenIcons/${tab.name}")
            tabs.addPage(
                tab.caption ?: tab.name,
                tab.getContent(this),
                icon, iconSize,
                scrollAlign = Align.topLeft,
                shortcutKey = KeyCharAndCode(tab.key),
                secret = tabHidden
            )
        }
        tabs.selectPage(pageNumber)

        //**************** Set up bottom area - buttons and description label ****************
        when {
            gameInfo.victoryData != null ->
                displayWinner(gameInfo.victoryData!!)
            playerCiv.isDefeated() -> {
                displayWonOrLost(Victory().defeatString)
                music.chooseTrack(playerCiv.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
            }
            else -> {
                rightSideButton.isVisible = false
                setDefaultCloseAction()
            }
        }

        //**************** Set up floating info panels ****************
        // When horizontal screen space is scarce so they would overlap, insert
        // them into the scrolling portion of the TabbedPager header instead
        tabs.pack()
        val topRightPanel = VerticalGroup().apply {
            space(5f)
            align(Align.right)
            addActor("{Game Speed}: {${gameInfo.gameParameters.speed}}".toLabel())
            if ("Time" in gameInfo.gameParameters.victoryTypes)
                addActor("{Max Turns}: ${gameInfo.gameParameters.maxTurns.tr()}".toLabel())
            pack()
        }
        val difficultyLabel = "{Difficulty}: {${gameInfo.difficulty}}".toLabel()
        val neededSpace = topRightPanel.width.coerceAtLeast(difficultyLabel.width) * 2 + tabs.getHeaderPrefWidth()
        if (portrait) {
            val info = listOf(difficultyLabel, *topRightPanel.children.toArray()).joinToString("\n") { (it as Label).text }
            descriptionLabel.setText(listOf(descriptionLabel.text.toString(), info).filter { it.isNotEmpty() }.joinToString("\n\n"))
        } else if (neededSpace > stage.width) {
            // Let additions take part in TabbedPager's header scrolling
            tabs.decorateHeader(difficultyLabel, leftSide = true, fixed = false)
            tabs.decorateHeader(topRightPanel, leftSide = false, fixed = false)
            tabs.headerScroll.fadeScrollBars = false
        } else {
            // Let additions float in the corners
            val panelY = stage.height - tabs.getRowHeight(0) * 0.5f
            stage.addActor(topRightPanel)
            topRightPanel.setPosition(stage.width - 10f, panelY, Align.right)
            stage.addActor(difficultyLabel)
            difficultyLabel.setPosition(10f, panelY, Align.left)
        }
        if (portrait) showPortraitVictoryPage()
        if (portrait && (gameInfo.victoryData != null || playerCiv.isDefeated()))
            showPortraitEndMoment()
    }

    private fun showPortraitVictoryPage() {
        val canvas = portraitCanvasBounds()
        val safe = safeAreaBoundsInWorld()
        val scale = 1f
        val width = 393f
        val logicalScale = safe.width / width
        portraitBackdrop = PortraitMapBackdrop(playerCiv).apply {
            setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
            this@VictoryScreen.stage.addActor(this)
        }
        splitPane.setFirstWidget(Table())
        val shell = Table().apply {
            setTransform(true)
            setBounds(safe.x, safe.y, width, safe.height / logicalScale)
            setScale(logicalScale)
            top()
        }
        val chrome = Table().apply { bottom() }
        val stats = Table().apply {
            background = skinStrings.getUiBackground("VictoryScreen/PortraitStats",
                tintColor = Color(0.06f, .13f, .2f, .88f))
            pad(4f)
        }
        fun stat(name: String, value: String, secondary: String? = null) {
            val item = Table()
            item.add(portraitStats.image(name)).size(18f).padRight(2f)
            item.add(value.toLabel(Color.WHITE, 14))
            if (secondary != null) item.add(secondary.toLabel(Color.valueOf("b7cde0"), 11))
                .padLeft(2f)
            stats.add(item).expandX().fillX()
        }
        val nextTurn = playerCiv.stats.statsForNextTurn
        fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()
        stat("Gold", playerCiv.gold.toString(), signed(nextTurn.gold.roundToInt()))
        stat("Science", signed(nextTurn.science.roundToInt()))
        stat("Culture", playerCiv.policies.storedCulture.toString(),
            "/${playerCiv.policies.getCultureNeededForNextPolicy()}")
        stat("Happiness", playerCiv.getHappiness().toString())
        stats.add("T${gameInfo.turns}".toLabel(Color.WHITE, 14)).padRight(5f)
        chrome.add(stats).width(width - 12f * scale).height(48f * scale).padBottom(8f * scale)
        shell.add(chrome).width(width).height(120f * scale).row()
        val sheet = Table().apply {
            background = skinStrings.getUiBackground("VictoryScreen/PortraitSheet",
                tintColor = Color.valueOf("122536"))
            top()
        }
        val header = Table()
        val title = "Victory".toLabel(Color.WHITE, (25f * scale).toInt())
        val titles = Table().apply { left() }
        titles.add(title).left().row()
        titles.add("Turn ${gameInfo.turns} of ${gameInfo.gameParameters.maxTurns} · ${gameInfo.difficulty} · ${gameInfo.gameParameters.speed}"
            .toLabel(Color.valueOf("b7cde0"), 12).apply { wrap = true }).width(205f).left().row()
        header.add(titles).width(205f).growX().left().padLeft(16f * scale)
        val viewsButton = "Views".toTextButton().apply {
            style = TextButton.TextButtonStyle(style).apply {
                up = skinStrings.getUiBackground("VictoryScreen/PortraitViews",
                    skinStrings.roundedEdgeRectangleMidShape, Color.valueOf("20394f"))
                down = up
                fontColor = Color.valueOf("b5c9d9")
            }
        }
        viewsButton.onClick {
            val popup = Popup(this@VictoryScreen, Popup.Scrollability.All, .82f)
            for (index in 0 until tabs.pageCount()) {
                val caption = tabs.getPageButton(index).name
                val option = caption.toTextButton()
                option.onClick { tabs.selectPage(index); popup.close() }
                popup.add(option).width(260f).height(48f).padBottom(4f).row()
            }
            popup.open()
        }
        header.add(viewsButton).size(88f, 48f).padRight(8f)
        val exit = ImageGetter.getImage("OtherIcons/Close").apply {
            color = Color.valueOf("142536")
            setSize(24f * scale, 24f * scale)
        }.surroundWithCircle(48f * scale, resizeActor = false, color = Color.WHITE)
        exit.onClick {
            if (gameInfo.victoryData != null || playerCiv.isDefeated()) gameInfo.oneMoreTurnMode = true
            game.popScreen()
        }
        header.add(exit).size(48f * scale).padRight(14f * scale)
        sheet.add(header).width(width).height(72f * scale).row()
        tabs.headerScroll.isVisible = false
        tabs.getCell(tabs.headerScroll).height(0f).minHeight(0f).maxHeight(0f).pad(0f)
        tabs.invalidateHierarchy()
        tabs.onSelection { _, caption, _ -> title.setText(if (caption == "Our status") "Victory" else caption) }
        if (tabs.activePage >= 0) title.setText(tabs.getPageButton(tabs.activePage).name
            .let { if (it == "Our status") "Victory" else it })
        sheet.add(tabs).grow()
        shell.add(sheet).grow()
        stage.addActor(shell)
    }

    private fun showPortraitEndMoment() {
        val victoryData = gameInfo.victoryData
        val winner = victoryData?.winningCivObject ?: playerCiv
        val won = winner == playerCiv && victoryData != null
        val victory = victoryData?.let { gameInfo.ruleset.victories[it.victoryType] } ?: Victory()
        val title = when {
            won -> "[${victoryData.victoryType}] Victory"
            victoryData != null -> "${winner.civName} has won"
            else -> "You have been defeated"
        }
        val body = if (won) victory.victoryString else victory.defeatString
        val safe = safeAreaBoundsInWorld()
        val scale = 1f
        val width = 393f
        val height = safe.height / (safe.width / width)
        val moment = Group().apply {
            setSize(width, height)
            setPosition(safe.x, safe.y)
            setScale(safe.width / width)
        }
        endBackdrop = PortraitMapBackdrop(playerCiv).apply {
            setSize(width, height)
            moment.addActor(this)
        }
        moment.addActor(ImageGetter.getWhiteDot().apply {
            color = if (won) Color(0.16f, 0.23f, 0.27f, .62f) else Color(0.12f, 0.13f, 0.23f, .76f)
            setSize(width, height)
        })
        val centerX = width / 2f
        val centerY = height - 238f * scale
        for (angle in 0 until 360 step 20) {
            moment.addActor(ImageGetter.getWhiteDot().apply {
                color = if (won) Color(1f, .83f, .48f, .09f) else Color(.96f, .37f, .36f, .08f)
                setBounds(centerX, centerY - 10f * scale, width * .9f, 20f * scale)
                setOrigin(0f, 10f * scale)
                rotation = angle.toFloat()
            })
        }
        val portraitSize = 250f * scale
        val portrait = Group().apply { setSize(portraitSize, portraitSize) }
        portrait.addActor(ImageGetter.getCircle(if (won) Color.GOLD else Color.WHITE, portraitSize))
        portrait.addActor(ImageGetter.getCircle(winner.nation.getOuterColor(), portraitSize - 12f * scale).apply {
            setPosition(6f * scale, 6f * scale)
        })
        val leaderFile = ImageGetter.findExternalImage("Leaders/p_${winner.nation.leaderName.substringBefore(' ')}")
        val leader: Actor = if (leaderFile != null) {
            val source = Pixmap(leaderFile)
            val circle = Pixmap(source.width, source.height, Pixmap.Format.RGBA8888)
            val radius = minOf(source.width, source.height) / 2f
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val distance = sqrt((x + .5f - source.width / 2f) * (x + .5f - source.width / 2f) +
                    (y + .5f - source.height / 2f) * (y + .5f - source.height / 2f))
                val coverage = (radius - distance).coerceIn(0f, 1f)
                val pixel = source.getPixel(x, y)
                circle.drawPixel(x, y, (pixel and -256) or ((pixel and 255) * coverage).toInt())
            }
            source.dispose()
            val texture = Texture(circle)
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            endPortraitTexture = texture
            circle.dispose()
            Image(TextureRegionDrawable(TextureRegion(texture)))
        } else ImageGetter.getNationPortrait(winner.nation, portraitSize - 30f * scale)
        leader.setBounds(15f * scale, 15f * scale, portraitSize - 30f * scale, portraitSize - 30f * scale)
        portrait.addActor(leader)
        portrait.setPosition(centerX - portraitSize / 2f, height - 366f * scale)
        moment.addActor(portrait)
        if (victoryData != null) {
            val badge = Group().apply { setSize(70f * scale, 70f * scale) }
            badge.addActor(ImageGetter.getCircle(Color.WHITE, 70f * scale))
            badge.addActor(ImageGetter.getCircle(if (won) Color.GOLD else Color.FIREBRICK, 60f * scale).apply {
                setPosition(5f * scale, 5f * scale)
            })
            badge.addActor(ImageGetter.getVictoryTypeIcon(victoryData.victoryType, 38f * scale,
                if (won) Color.valueOf("192a3d") else Color.WHITE).apply {
                setPosition(16f * scale, 16f * scale)
            })
            badge.setPosition(centerX - 35f * scale, height - 398f * scale)
            moment.addActor(badge)
        }

        val card = Table().apply {
            background = skinStrings.getUiBackground("VictoryScreen/EndCard", tintColor = Color(0.06f, .12f, .18f, .96f))
            pad(16f * scale)
            top()
        }
        card.add(title.toLabel(fontColor = if (won) Color.GOLD else Color.WHITE,
            fontSize = (30f * scale).toInt()).apply { wrap = true; setAlignment(Align.left) })
            .width(width - 64f * scale).left().row()
        val subtitle = if (won) winner.getLeaderDisplayName() else
            if (victoryData != null) "${victoryData.victoryType} Victory · ${winner.getLeaderDisplayName()}" else
                winner.getLeaderDisplayName()
        card.add(subtitle.toLabel(Color.LIGHT_GRAY, (14f * scale).toInt()).apply { wrap = true })
            .width(width - 64f * scale).left().padTop(4f * scale).row()
        val resultLine = if (won) victory.victoryScreenHeader else
            if (victoryData != null) "${winner.civName} has won" else "Your civilization has fallen"
        card.add(resultLine.toLabel(Color.GOLD, (13f * scale).toInt()).apply { wrap = true })
            .width(width - 64f * scale).left().padTop(10f * scale).row()
        val bodyLabel = body.toLabel(Color.WHITE, (14f * scale).toInt()).apply { wrap = true }
        card.add(ScrollPane(bodyLabel)).width(width - 64f * scale).height(110f * scale)
            .padTop(12f * scale).row()
        val newGame = "Start new game".toTextButton().apply {
            style = TextButton.TextButtonStyle(style).apply {
                up = skinStrings.getUiBackground("VictoryScreen/EndPrimary",
                    skinStrings.roundedEdgeRectangleMidShape, Color.valueOf("ffc93c"))
                down = skinStrings.getUiBackground("VictoryScreen/EndPrimaryDown",
                    skinStrings.roundedEdgeRectangleMidShape, Color.valueOf("eab527"))
                fontColor = Color.valueOf("142536")
            }
            color = Color.WHITE
            label.color = Color.valueOf("142536")
        }
        newGame.onClick {
            val setup = GameSetupInfo(gameInfo)
            setup.mapParameters.reseed()
            game.pushScreen { NewGameScreen(setup) }
        }
        card.add(newGame).width(width - 64f * scale).height(56f * scale)
            .padTop(16f * scale).row()
        val oneMoreTurn = "One more turn...!".toTextButton()
        oneMoreTurn.onClick {
            gameInfo.oneMoreTurnMode = true
            game.popScreen()
        }
        card.add(oneMoreTurn).width(width - 64f * scale).height(48f * scale)
            .padTop(8f * scale).row()
        card.setSize(width - 24f * scale, 350f * scale)
        card.setPosition(12f * scale, 30f * scale)
        moment.addActor(card)
        val viewResults = "View results".toTextButton()
        viewResults.onClick { moment.remove() }
        viewResults.setSize(124f * scale, 48f * scale)
        viewResults.setPosition(width - 138f * scale, height - 62f * scale)
        moment.addActor(viewResults)
        stage.addActor(moment)
    }

    private fun displayWinner(victoryData: VictoryData) {
        // Undecided how to display victoryTurn
        val victoryType = victoryData.victoryType
        val winningCiv = victoryData.winningCivObject
        val victory = gameInfo.ruleset.victories[victoryType]
            ?: Victory()  // This contains our default victory/defeat texts
        if (winningCiv.civID == playerCiv.civID) {
            displayWonOrLost("You have won a [$victoryType] Victory!", victory.victoryString)
            if (!music.chooseTrack(victory.name, MusicMood.Victory, EnumSet.of(MusicTrackChooserFlags.PrefixMustMatch, MusicTrackChooserFlags.SuffixMustMatch))) {
                music.chooseTrack(playerCiv.civName, listOf(MusicMood.Victory, MusicMood.Theme), EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
            }
        } else {
            displayWonOrLost("[${winningCiv.civName}] has won a [$victoryType] Victory!", victory.defeatString)
            if (!music.chooseTrack(victory.name, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.PrefixMustMatch, MusicTrackChooserFlags.SuffixMustMatch))) {
                music.chooseTrack(playerCiv.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
            }
        }
        worldScreen.autoPlay.stopAutoPlay()
    }

    private fun displayWonOrLost(vararg descriptions: String) {
        descriptionLabel.setText(descriptions.joinToString("\n") { it.tr() })

        rightSideButton.setText("Start new game".tr())
        rightSideButton.enable()
        rightSideButton.onClick {
            val newGameSetupInfo = GameSetupInfo(gameInfo)
            newGameSetupInfo.mapParameters.reseed()
            game.pushScreen{ NewGameScreen(newGameSetupInfo) }
        }

        closeButton.setText("One more turn...!".tr())
        closeButton.onClick {
            gameInfo.oneMoreTurnMode = true
            game.popScreen()
        }
    }

    override fun show() {
        super.show()
        tabs.askForPassword(secretHashCode = 2747985)
    }

    override fun dispose() {
        tabs.selectPage(-1)  // Tells Replay page to stop its timer
        portraitStats.dispose()
        endPortraitTexture?.dispose()
        endBackdrop?.dispose()
        portraitBackdrop?.dispose()
        super.dispose()
    }

    override fun recreate(): BaseScreen = VictoryScreen(worldScreen, tabs.activePage)

    companion object {
        @Readonly
        fun canViewCivStats(
            gameInfo: GameInfo,
            viewingCiv: Civilization,
            viewedCiv: Civilization
        ): Boolean = gameInfo.victoryData != null
            || viewingCiv.isSpectator()
            || !gameInfo.gameParameters.hideOtherCivilizationStats
            || viewedCiv == viewingCiv
    }
}
