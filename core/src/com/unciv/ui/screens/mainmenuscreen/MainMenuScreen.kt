package com.unciv.ui.screens.mainmenuscreen

import com.unciv.ui.screens.basescreen.SafeAreaViewport

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.HolidayDates
import com.unciv.logic.UncivShowableException
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.tr
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.surroundWithThinCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyShortcutDispatcherVeto
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.padTopDescent
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.popups.hasOpenPopups
import com.unciv.ui.popups.options.AboutTab
import com.unciv.ui.popups.popups
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.mainmenuscreen.EasterEggRulesets.modifyForEasterEgg
import com.unciv.ui.screens.mapeditorscreen.EditorMapHolder
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.modmanager.ModManagementScreen
import com.unciv.ui.screens.multiplayerscreens.MultiplayerScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.savescreens.QuickSave
import com.unciv.ui.screens.worldscreen.BackgroundActor
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import com.unciv.utils.Concurrency
import com.unciv.utils.Display
import com.unciv.utils.ONLINE_MULTIPLAYER_UNAVAILABLE
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import yairm210.purity.annotations.Pure
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt


class MainMenuScreen: BaseScreen(), RecreateOnResize {
    private val backgroundStack = Stack()
    private val singleColumn = isCrampedPortrait()
    private val portraitMenu = isPortrait()
    private var portraitBackgroundTexture: Texture? = null
    private var portraitBackground: Image? = null
    private val portraitDecorationTextures = mutableListOf<Texture>()
    private var portraitDisposed = false

    private val backgroundMapRuleset: Ruleset
    private var easterEggRuleset: Ruleset? = null  // Cache it so the next 'egg' can be found in Civilopedia

    private var backgroundMapGenerationJob: Job? = null
    private var backgroundMapExists = false

    companion object {
        const val mapFadeTime = 1.3f
        const val mapFirstFadeTime = 0.3f
        const val mapReplaceDelay = 20f
        /** Inner size of the Civilopedia+Discord+Github buttons (effective size adds 2f for the thin circle) */
        const val buttonsSize = 60f
        /** Distance of the Civilopedia and Discord+Github buttons from the stage edges */
        const val buttonsPosFromEdge = 30f
    }

    /** Create one **Main Menu Button** including onClick/key binding
     *  @param text      The text to display on the button
     *  @param icon      The path of the icon to display on the button
     *  @param binding   keyboard binding
     *  @param function  Action to invoke when the button is activated
     */
    private fun getMenuButton(
        text: String,
        icon: String,
        binding: KeyboardBinding = KeyboardBinding.None,
        function: () -> Unit
    ): Table {
        val table = Table().pad(15f, 30f, 15f, 30f)
        table.background = skinStrings.getUiBackground(
            "MainMenuScreen/MenuButton",
            skinStrings.roundedEdgeRectangleShape,
            skinStrings.skinConfig.baseColor
        )
        table.add(ImageGetter.getImage(icon)).size(50f).padRight(20f)
        table.add(text.toLabel(fontSize = 30, alignment = Align.left)).expand().left().minWidth(200f)
            .padTopDescent()

        table.touchable = Touchable.enabled
        table.onActivation(binding = binding) {
            stopBackgroundMapGeneration()
            function()
        }

        table.pack()
        return table
    }

    init {
        SoundPlayer.initializeForMainMenu()
        if (portraitMenu) updatePortraitViewport()

        val background = skinStrings.getUiBackground("MainMenuScreen/Background", tintColor = clearColor)
        backgroundStack.add(BackgroundActor(background, Align.center))
        stage.addActor(backgroundStack)
        backgroundStack.setFillParent(false)
        updateBackgroundBounds()

        // If we were in a mod, some of the resource images for the background map we're creating
        // will not exist unless we reset the ruleset and images
        val baseRuleset = RulesetCache.getVanillaRuleset()
        ImageGetter.setNewRuleset(baseRuleset)

        if (game.settings.enableEasterEggs) {
            val holiday = HolidayDates.getHolidayByDate()
            if (holiday != null && !portraitMenu)
                EasterEggFloatingArt(stage, holiday.name)
            val easterEggMod = EasterEggRulesets.getTodayEasterEggRuleset()
            if (easterEggMod != null)
                easterEggRuleset = RulesetCache.getComplexRuleset(baseRuleset, listOf(easterEggMod))
        }
        backgroundMapRuleset = easterEggRuleset ?: baseRuleset
        if (portraitMenu) initPortraitMenu() else initLandscapeMenu()
    }

    private fun initLandscapeMenu() {
        // This is an extreme safeguard - should an invalid settings.tileSet ever make it past the
        // guard in UncivGame.create, simply omit the background so the user can at least get to options
        // (let him crash when loading a game but avoid locking him out entirely)
        if (game.settings.tileSet in TileSetCache)
            startBackgroundMapGeneration()

        val column1 = Table().apply { defaults().pad(10f).fillX() }
        val column2 = if (singleColumn) column1 else Table().apply { defaults().pad(10f).fillX() }

        if (game.files.autosaves.autosaveExists()) {
            val resumeTable = getMenuButton("Resume","OtherIcons/Resume", KeyboardBinding.Resume)
                { resumeGame() }
            column1.add(resumeTable).row()
        }

        val quickstartTable = getMenuButton("Quickstart", "OtherIcons/Quickstart", KeyboardBinding.Quickstart)
            { quickstartNewGame() }
        column1.add(quickstartTable).row()

        val newGameButton = getMenuButton("Start new game", "OtherIcons/New", KeyboardBinding.StartNewGame) {
            game.pushScreen{ NewGameScreen() } 
        }
        column1.add(newGameButton).row()

        val loadGameTable = getMenuButton("Load game", "OtherIcons/Load", KeyboardBinding.MainMenuLoad) {
            game.pushScreen{ LoadGameScreen() }
        }
        column1.add(loadGameTable).row()

        val multiplayerTable = getMenuButton("Multiplayer", "OtherIcons/Multiplayer", KeyboardBinding.Multiplayer) {
            if (game.platformCapabilities.onlineMultiplayer) {
                game.pushScreen { MultiplayerScreen() }
            } else {
                Popup(stage).apply {
                    addGoodSizedLabel(ONLINE_MULTIPLAYER_UNAVAILABLE).row()
                    addCloseButton()
                    open()
                }
            }
        }
        column2.add(multiplayerTable).row()

        val mapEditorScreenTable = getMenuButton("Map editor", "OtherIcons/MapEditor", KeyboardBinding.MapEditor) {
            game.pushScreen{ MapEditorScreen() }
        }
        column2.add(mapEditorScreenTable).row()

        if (game.platformCapabilities.onlineModManagement) {
            val modsTable = getMenuButton("Mods", "OtherIcons/Mods", KeyboardBinding.ModManager) {
                game.pushScreen{ ModManagementScreen() }
            }
            column2.add(modsTable).row()
        }

        val optionsTable = getMenuButton("Options", "OtherIcons/Options", KeyboardBinding.MainMenuOptions)
            { openOptionsPopup() }
        optionsTable.onLongPress { openOptionsPopup(withDebug = true) }
        column2.add(optionsTable).row()

        val table = Table().apply { defaults().pad(10f) }
        table.add(column1)
        if (!singleColumn) table.add(column2)
        table.pack()

        val scrollPane = AutoScrollPane(table)
        scrollPane.setFillParent(true)
        stage.addActor(scrollPane)
        table.center(scrollPane)

        globalShortcuts.add(KeyboardBinding.QuitMainMenu) {
            if (hasOpenPopups()) {
                closeAllPopups()
                return@add
            }
            game.popScreen()
        }

        val civilopediaButton = "?".toLabel(fontSize = 48)
            .apply { setAlignment(Align.center) }
            .surroundWithCircle(buttonsSize, color = skinStrings.skinConfig.baseColor)
            .apply { actor.y -= Fonts.getDescenderHeight(48) / 2 } // compensate font baseline
            .surroundWithThinCircle(Color.WHITE)
        civilopediaButton.touchable = Touchable.enabled
        // Passing the binding directly to onActivation gives you a size 26 tooltip...
        civilopediaButton.onActivation { openCivilopedia() }
        civilopediaButton.keyShortcuts.add(KeyboardBinding.Civilopedia)
        civilopediaButton.addTooltip(KeyboardBinding.Civilopedia, 30f)
        civilopediaButton.setPosition(buttonsPosFromEdge, buttonsPosFromEdge)
        stage.addActor(civilopediaButton)

        val rightSideButtons = Table().apply { defaults().space(10f) }
        val discordButton = ImageGetter.getImage("OtherIcons/Discord")
            .surroundWithCircle(buttonsSize, color = skinStrings.skinConfig.baseColor)
            .surroundWithThinCircle(Color.WHITE)
            .onActivation { Gdx.net.openURI("https://discord.gg/GGGAn4SGT") }
        rightSideButtons.add(discordButton)

        val githubButton = ImageGetter.getImage("OtherIcons/Github")
            .surroundWithCircle(buttonsSize, color = skinStrings.skinConfig.baseColor)
            .surroundWithThinCircle(Color.WHITE)
            .onActivation { Gdx.net.openURI(Constants.uncivRepoURL) }
        rightSideButtons.add(githubButton)

        if (game.achievementsAvailable) {
            val gold = Color.valueOf("f3ca73")
            val achievementsButton = ImageGetter.getImage("OtherIcons/AchievementTrophy", gold)
                .surroundWithCircle(buttonsSize, color = Color.valueOf("112a40"))
                .surroundWithThinCircle(gold)
                .onActivation { game.showAchievements() }
            achievementsButton.name = "Achievements"
            achievementsButton.addTooltip("Achievements", 30f)
            rightSideButtons.add(achievementsButton)
        }

        rightSideButtons.pack()
        rightSideButtons.setPosition(stage.width - buttonsPosFromEdge, buttonsPosFromEdge, Align.bottomRight)
        stage.addActor(rightSideButtons)

        val displayVersion = game.displayBuildNumber
            ?.let { "{Version} ${UncivGame.VERSION.text} ($it)" }
            ?: "{Version} ${UncivGame.VERSION.text}"
        val versionLabel = displayVersion.toLabel()
        versionLabel.setAlignment(Align.center)
        val versionTable = Table()
        versionTable.background = skinStrings.getUiBackground("MainMenuScreen/Version",
            skinStrings.roundedEdgeRectangleShape, Color.DARK_GRAY.cpy().apply { a = 0.7f })
        versionTable.add(versionLabel)
        versionTable.pack()
        versionTable.setPosition(stage.width / 2, 10f, Align.bottom)
        versionTable.touchable = Touchable.enabled
        versionTable.onClick {
            val popup = Popup(stage)
            popup.add(AboutTab.asTable()).row()
            popup.addCloseButton()
            popup.open()
        }
        stage.addActor(versionTable)
    }

    private fun initPortraitMenu() {
        val bounds = (stage.viewport as SafeAreaViewport).drawingBounds
        val unit = bounds.width / 393f
        val texture = Texture(Gdx.files.internal("ExtraImages/MainMenuArmy.png"))
        texture.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        portraitBackgroundTexture = texture
        portraitBackground = Image(TextureRegionDrawable(TextureRegion(texture))).apply {
            touchable = Touchable.disabled
            this@MainMenuScreen.stage.addActor(this)
        }
        updatePortraitBackgroundBounds()
        addPortraitTopScrim(bounds.x, bounds.y + bounds.height - 130f * unit,
            bounds.width, 130f * unit)

        val wordmarkSize = (62f * unit).roundToInt()
        val wordmarkHeight = 76f * unit
        val wordmarkY = bounds.y + bounds.height - (120f * unit) - wordmarkHeight
        val shadow = "Unciv".toLabel(Color.valueOf("1c3249"), wordmarkSize, Align.center).apply {
            setBounds(bounds.x, wordmarkY - 4f * unit, bounds.width, wordmarkHeight)
            touchable = Touchable.disabled
        }
        stage.addActor(shadow)
        val wordmark = "Unciv".toLabel(Color.WHITE, wordmarkSize, Align.center).apply {
            setBounds(bounds.x, wordmarkY, bounds.width, wordmarkHeight)
            touchable = Touchable.enabled
            onClick { openPortraitExtras() }
        }
        stage.addActor(wordmark)

        val gap = 10f * unit
        val margin = 18f * unit
        val menuWidth = bounds.width - margin * 2f
        val buttonWidth = (menuWidth - gap) / 2f
        val buttonHeight = 56f * unit
        val firstRowY = bounds.y + 44f * unit + buttonHeight + gap
        val leftX = bounds.x + margin
        val rightX = leftX + buttonWidth + gap
        val secondaryBackground = portraitPanelDrawable(16f, unit,
            Color(16f / 255f, 31f / 255f, 47f / 255f, .9f))

        portraitButton("New game", KeyboardBinding.StartNewGame, secondaryBackground) { game.pushScreen { NewGameScreen() } }
            .apply { setBounds(leftX, firstRowY, buttonWidth, buttonHeight); this@MainMenuScreen.stage.addActor(this) }
        portraitButton("Load game", KeyboardBinding.MainMenuLoad, secondaryBackground) { game.pushScreen { LoadGameScreen() } }
            .apply { setBounds(rightX, firstRowY, buttonWidth, buttonHeight); this@MainMenuScreen.stage.addActor(this) }
        portraitButton("Civilopedia", KeyboardBinding.Civilopedia, secondaryBackground) { openCivilopedia() }
            .apply { setBounds(leftX, firstRowY - buttonHeight - gap, buttonWidth, buttonHeight); this@MainMenuScreen.stage.addActor(this) }
        portraitButton("Settings", KeyboardBinding.MainMenuOptions, secondaryBackground) { openOptionsPopup() }
            .apply {
                setBounds(rightX, firstRowY - buttonHeight - gap, buttonWidth, buttonHeight)
                onLongPress { openOptionsPopup(withDebug = true) }
                this@MainMenuScreen.stage.addActor(this)
            }

        if (game.files.autosaves.autosaveExists()) {
            val continueY = firstRowY + buttonHeight + gap
            val shadow = BackgroundActor(portraitPanelDrawable(20f, unit, Color.valueOf("c9951c")), Align.center)
            shadow.setBounds(leftX, continueY - 5f * unit, menuWidth, 72f * unit)
            stage.addActor(shadow)
            val continueButton = Table().apply {
                background = portraitPanelDrawable(20f, unit, Color.valueOf("ffc93c"))
                touchable = Touchable.enabled
                padLeft(20f * unit)
                padRight(20f * unit)
            }
            val text = Table().apply { left() }
            text.add("Continue".toLabel(Color.valueOf("3a2a00"), (21f * unit).roundToInt())).left().row()
            val summary = "Saved game".toLabel(Color.valueOf("6d541c"), (14f * unit).roundToInt())
            text.add(summary).left()
            continueButton.add(text).growX().left()
            continueButton.add(ImageGetter.getImage("OtherIcons/ForwardArrow", Color.valueOf("3a2a00")))
                .size(26f * unit)
            continueButton.setBounds(leftX, continueY, menuWidth, 72f * unit)
            continueButton.onActivation(binding = KeyboardBinding.Resume) { resumeGame() }
            stage.addActor(continueButton)
            setPortraitSaveSummary(summary)
        }

        globalShortcuts.add(KeyboardBinding.QuitMainMenu) {
            if (hasOpenPopups()) closeAllPopups() else game.popScreen()
        }
    }

    private fun portraitButton(text: String, binding: KeyboardBinding,
                               panel: NinePatchDrawable, action: () -> Unit): Table =
        Table().apply {
            background = panel
            touchable = Touchable.enabled
            add(text.toLabel(Color.WHITE, (16f * this@MainMenuScreen.stage.width / 393f).roundToInt(), Align.center)).grow()
            onActivation(binding = binding, action = action)
        }

    private fun setPortraitSaveSummary(label: com.badlogic.gdx.scenes.scene2d.ui.Label) {
        if (GUI.isWorldLoaded()) {
            val info = GUI.getWorldScreen().gameInfo
            label.setText("${info.getCurrentPlayerCivilization().civName.tr(hideIcons = true)}, turn ${info.turns}")
            return
        }
        Concurrency.run("MainMenuSaveSummary") {
            val files = game.files
            val summary = try {
                val primary = files.getSave("Autosave")
                val candidates = sequenceOf(primary).filter { it.exists() } +
                    files.getSaves().filter { it.name().startsWith("Autosave-") }
                        .sortedByDescending { it.lastModified() }
                candidates.firstNotNullOfOrNull { file ->
                    try {
                        val preview = files.loadGamePreviewFromFile(file)
                        "${preview.getCurrentPlayerCiv().civName.tr(hideIcons = true)}, turn ${preview.turns}"
                    } catch (_: Exception) { null }
                }
            } catch (_: Exception) { null }
            if (summary != null) launchOnGLThread {
                if (!portraitDisposed) label.setText(summary)
            }
        }
    }

    private fun updatePortraitBackgroundBounds() {
        val background = portraitBackground ?: return
        val bounds = (stage.viewport as SafeAreaViewport).drawingBounds
        val texture = portraitBackgroundTexture ?: return
        val scale = max(bounds.width / texture.width, bounds.height / texture.height)
        val width = texture.width * scale
        val height = texture.height * scale
        background.setBounds(bounds.x + (bounds.width - width) / 2f,
            bounds.y + (bounds.height - height) / 2f, width, height)
    }

    private fun portraitPanelDrawable(radiusPx: Float, unit: Float, tint: Color): NinePatchDrawable {
        val radius = (radiusPx * unit).roundToInt().coerceAtLeast(2)
        val side = radius * 2 + 2
        val pixels = Pixmap(side, side, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        for (y in 0 until side) for (x in 0 until side) {
            val px = x + .5f
            val py = y + .5f
            val dx = max(max(radius - px, px - (side - radius)), 0f)
            val dy = max(max(radius - py, py - (side - radius)), 0f)
            val alpha = (radius + .5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            pixels.drawPixel(x, y, 0xffffff00.toInt() or (alpha * 255f).roundToInt())
        }
        val texture = Texture(pixels)
        pixels.dispose()
        texture.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        portraitDecorationTextures.add(texture)
        return NinePatchDrawable(NinePatch(TextureRegion(texture), radius, radius, radius, radius)).tint(tint)
    }

    private fun addPortraitTopScrim(x: Float, y: Float, width: Float, height: Float) {
        val pixels = Pixmap(1, height.roundToInt(), Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        for (row in 0 until pixels.height) {
            val alpha = (158f * (1f - row.toFloat() / pixels.height)).roundToInt()
            pixels.drawPixel(0, row, 0x0a162200 or alpha)
        }
        val texture = Texture(pixels)
        pixels.dispose()
        texture.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        portraitDecorationTextures.add(texture)
        val scrim = Image(TextureRegionDrawable(TextureRegion(texture)))
        scrim.touchable = Touchable.disabled
        scrim.setBounds(x, y, width, height)
        stage.addActor(scrim)
    }

    private fun openPortraitExtras() {
        val popup = Popup(this, Popup.Scrollability.All)
        popup.addGoodSizedLabel("More").row()
        popup.addButton("Quickstart") { popup.close(); quickstartNewGame() }.row()
        popup.addButton("Multiplayer") {
            popup.close()
            if (game.platformCapabilities.onlineMultiplayer) game.pushScreen { MultiplayerScreen() }
            else ToastPopup(ONLINE_MULTIPLAYER_UNAVAILABLE, this)
        }.row()
        popup.addButton("Map editor") { popup.close(); game.pushScreen { MapEditorScreen() } }.row()
        if (game.platformCapabilities.onlineModManagement)
            popup.addButton("Mods") { popup.close(); game.pushScreen { ModManagementScreen() } }.row()
        if (game.achievementsAvailable)
            popup.addButton("Achievements") { popup.close(); game.showAchievements() }.row()
        popup.addButton("Discord") { Gdx.net.openURI("https://discord.gg/GGGAn4SGT") }.row()
        popup.addButton("Github") { Gdx.net.openURI(Constants.uncivRepoURL) }.row()
        popup.addButton("About") {
            popup.close()
            Popup(stage).apply { add(AboutTab.asTable()).row(); addCloseButton(); open() }
        }.row()
        popup.addCloseButton().row()
        popup.open()
    }

    private fun startBackgroundMapGeneration() {
        stopBackgroundMapGeneration()  // shouldn't be necessary as resize re-instantiates this class
        backgroundMapGenerationJob = Concurrency.run("ShowMapBackground") {
            // MapSize.Small has easily enough tiles to fill the entire background - unless the user sized their window to some extreme aspect ratio
            val mapWidth = stage.width / TileGroupMap.groupHorizontalAdvance
            val mapHeight = stage.height / TileGroupMap.groupSize
            @Pure fun Float.scaleCoord(scale: Float) = ceil(this * scale).toInt().coerceAtLeast(6)
            // These scale values are chosen so that a common 4:3 screen minus taskbar gives the same as MapSize.Small
            val backgroundMapSize = MapSize(mapWidth.scaleCoord(.77f), mapHeight.scaleCoord(1f))

            val newMap = MapGenerator(backgroundMapRuleset, this)
                .generateMap(MapParameters().apply {
                    shape = MapShape.rectangular
                    mapSize = backgroundMapSize
                    type = MapType.pangaea
                    temperatureintensity = .7f
                    waterThreshold = -0.1f // mainly land, gets about 30% water
                    modifyForEasterEgg()
                })

            launchOnGLThread { // for GL context
                ImageGetter.setNewRuleset(backgroundMapRuleset, ignoreIfModsAreEqual = true)
                val mapHolder = EditorMapHolder(
                    this@MainMenuScreen,
                    newMap
                ) {}
                mapHolder.color = mapHolder.color.cpy()
                mapHolder.color.a = 0f
                backgroundStack.add(mapHolder)

                if (backgroundMapExists) {
                    mapHolder.addAction(Actions.sequence(
                        Actions.fadeIn(mapFadeTime),
                        Actions.run { backgroundStack.removeActorAt(1, false) }
                    ))
                } else {
                    backgroundMapExists = true
                    mapHolder.addAction(Actions.fadeIn(mapFirstFadeTime))
                }
            }
        }.apply {
            invokeOnCompletion {
                backgroundMapGenerationJob = null
                backgroundStack.addAction(Actions.sequence(
                    Actions.delay(mapReplaceDelay),
                    Actions.run { startBackgroundMapGeneration() }
                ))
            }
        }
    }

    private fun stopBackgroundMapGeneration() {
        backgroundStack.clearActions()
        val currentJob = backgroundMapGenerationJob
            ?: return
        backgroundMapGenerationJob = null
        if (currentJob.isCancelled) return
        currentJob.cancel()
    }

    private fun resumeGame() {
        if (GUI.isWorldLoaded()) {
            val currentTileSet = GUI.getMap().currentTileSetStrings
            val currentGameSetting = GUI.getSettings()
            if (currentTileSet.tileSetName != currentGameSetting.tileSet ||
                    currentTileSet.unitSetName != currentGameSetting.unitSet) {
                game.removeScreensOfType(WorldScreen::class)
                QuickSave.autoLoadGame(this)
            } else {
                GUI.resetToWorldScreen()
                GUI.getWorldScreen().popups.filterIsInstance<WorldScreenMenuPopup>().forEach(Popup::close)
            }
        } else {
            QuickSave.autoLoadGame(this)
        }
    }

    private fun quickstartNewGame() {
        ToastPopup(Constants.working, this)
        val errorText = "Cannot start game with the default new game parameters!"
        Concurrency.run("QuickStart") {
            val newGame: GameInfo
            // Can fail when starting the game...
            try {
                val gameInfo = GameSetupInfo.fromSettings("Chieftain")
                if (gameInfo.gameParameters.victoryTypes.isEmpty()) {
                    val ruleSet = RulesetCache.getComplexRuleset(gameInfo.gameParameters)
                    gameInfo.gameParameters.victoryTypes.addAll(ruleSet.selectableVictories().map { it.name })
                }
                newGame = GameStarter.startNewGame(gameInfo)

            } catch (notAPlayer: UncivShowableException) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(notAPlayer)
                launchOnGLThread { ToastPopup(message, this@MainMenuScreen) }
                return@run
            } catch (_: Exception) {
                launchOnGLThread { ToastPopup(errorText, this@MainMenuScreen) }
                return@run
            }

            // ...or when loading the game
            try {
                game.loadGame(newGame)
            } catch (_: OutOfMemoryError) {
                launchOnGLThread {
                    ToastPopup("Not enough memory on phone to load game!", this@MainMenuScreen)
                }
            } catch (notAPlayer: UncivShowableException) {
                val (message) = LoadGameScreen.getLoadExceptionMessage(notAPlayer)
                launchOnGLThread {
                    ToastPopup(message, this@MainMenuScreen)
                }
            } catch (_: Exception) {
                launchOnGLThread {
                    ToastPopup(errorText, this@MainMenuScreen)
                }
            }
        }
    }

    override fun getCivilopediaRuleset(): Ruleset {
        if (easterEggRuleset != null) return easterEggRuleset!!
        val rulesetParameters = game.settings.lastGameSetup?.gameParameters
        if (rulesetParameters != null) return RulesetCache.getComplexRuleset(rulesetParameters)
        return RulesetCache[BaseRuleset.Civ_V_GnK.fullName]
            ?: throw IllegalStateException("No ruleset found")
    }

    override fun openCivilopedia(link: String) {
        stopBackgroundMapGeneration()
        val ruleset = getCivilopediaRuleset()
        UncivGame.Current.translations.translationActiveMods = ruleset.mods
        ImageGetter.setNewRuleset(ruleset)
        setSkin()
        openCivilopedia(ruleset, link = link)
    }

    private fun updateBackgroundBounds() {
        val bounds = (stage.viewport as SafeAreaViewport).drawingBounds
        backgroundStack.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private fun updatePortraitViewport() {
        // Menu art fills the phone while controls and popups retain safe-area stage coordinates.
        (stage.viewport as SafeAreaViewport).updateDisplay(
            Gdx.graphics.width, Gdx.graphics.height, Display.getSafeInsets(), edgeToEdge = true)
    }

    override fun render(delta: Float) {
        if (portraitMenu) updatePortraitViewport()
        updateBackgroundBounds()
        if (portraitMenu) updatePortraitBackgroundBounds()
        super.render(delta)
    }

    override fun recreate(): BaseScreen {
        stopBackgroundMapGeneration()
        return MainMenuScreen()
    }

    override fun resume() {
        if (!portraitMenu) startBackgroundMapGeneration()
    }

    override fun dispose() {
        portraitDisposed = true
        portraitBackgroundTexture?.dispose()
        portraitDecorationTextures.forEach(Texture::dispose)
        super.dispose()
    }

    // We contain a map...
    override fun getShortcutDispatcherVetoer() = if (portraitMenu) null
        else KeyShortcutDispatcherVeto.createTileGroupMapDispatcherVetoer()
}
