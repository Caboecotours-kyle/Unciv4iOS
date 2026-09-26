package com.unciv.app.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.utils.BufferUtils
import com.unciv.logic.GameStarter
import com.unciv.logic.VictoryData
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.screens.LanguagePickerScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.civilopediascreen.CivilopediaScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.newgamescreen.NewGameScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.models.Counter
import com.unciv.models.ruleset.BeliefType
import com.unciv.ui.screens.pickerscreens.PantheonPickerScreen
import com.unciv.ui.screens.pickerscreens.DiplomaticVotePickerScreen
import com.unciv.ui.screens.pickerscreens.GreatPersonPickerScreen
import com.unciv.ui.screens.pickerscreens.ImprovementPickerScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.PromotionPickerScreen
import com.unciv.ui.screens.pickerscreens.ReligiousBeliefsPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.savescreens.SaveGameScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.modmanager.ModManagementScreen
import com.unciv.ui.screens.multiplayerscreens.MultiplayerScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.ui.screens.worldscreen.AlertPopup
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import com.unciv.utils.Concurrency
import com.unciv.utils.DebugUtils
import java.io.File

/**
 * Screenshot harness for checking visuals without a visible window (`--capture=out.png`).
 * Quickstarts a game, waits for the world map to settle, renders one frame into an offscreen buffer,
 * writes it as a PNG, and exits. Works while the desktop session is locked or has no display.
 */
/** An iPhone 15 screen in points, doubled: the UI lays out as it would on the phone. */
private val captureSize = System.getenv("UNCIV_CAPTURE_SIZE")?.split("x")?.map { it.toInt() }
/** Window size: an iPhone in portrait by default; UNCIV_CAPTURE_SIZE=WxH overrides it (e.g. 1704x786 for landscape) */
internal val PHONE_W = captureSize?.get(0) ?: 786
internal val PHONE_H = captureSize?.get(1) ?: 1704

class CaptureGame(
    config: Lwjgl3ApplicationConfiguration,
    customDataDirectory: String?,
    private val outFile: File,
    private val revealMap: Boolean,
    /** Screen to open over the world map before capturing (tech, city, policies, ...); null captures the map. */
    private val open: String? = null,
) : DesktopGame(config, customDataDirectory) {

    private var started = false
    private var worldFrames = 0

    override fun render() {
        // A hidden window gets no input events, and Unciv only redraws on events unless told otherwise
        Gdx.graphics.isContinuousRendering = true
        super.render()
        val current = screen
        if (current is LanguagePickerScreen) {
            settings.isFreshlyCreated = false
            settings.showTutorials = false
            settings.screenSize = GameSettings.ScreenSize.Small // what a fresh iPhone install uses
            settings.windowState = GameSettings.WindowState(PHONE_W, PHONE_H)
            Gdx.graphics.setWindowedMode(PHONE_W, PHONE_H)
            replaceCurrentScreen { MainMenuScreen() }
            return
        }
        if (current is MainMenuScreen && !started) {
            started = true
            println("Capture: quickstarting")
            DebugUtils.VISIBLE_MAP = revealMap
            Concurrency.run("CaptureQuickstart") {
                val setup = GameSetupInfo.fromSettings("Chieftain")
                // UNCIV_CAPTURE_SEED pins the map and your civ, so two builds can be compared pixel for pixel
                System.getenv("UNCIV_CAPTURE_SEED")?.toLong()?.let { seed ->
                    setup.mapParameters.seed = seed
                    setup.gameParameters.players.first { it.playerType == PlayerType.Human }.chosenCiv = "Babylon"
                }
                if (setup.gameParameters.victoryTypes.isEmpty())
                    setup.gameParameters.victoryTypes.addAll(
                        RulesetCache.getComplexRuleset(setup.gameParameters).selectableVictories().map { it.name })
                // UNCIV_CAPTURE_LOAD replays one saved game, so two builds render the identical map;
                // UNCIV_CAPTURE_SAVE_TO writes the freshly started game out to make such a save
                val load = System.getenv("UNCIV_CAPTURE_LOAD")
                val newGame = if (load != null) UncivFiles.gameInfoFromString(File(load).readText())
                    else GameStarter.startNewGame(setup)
                System.getenv("UNCIV_CAPTURE_SAVE_TO")?.let { File(it).writeText(UncivFiles.gameInfoToString(newGame)) }
                loadGame(newGame)
            }
        }
        // count frames from the first world frame on, including frames spent on a screen opened over it
        if (current is WorldScreen && worldFrames == 0 && open == "found") {
            foundCapital(current); current.shouldUpdate = true
        } else if (current is WorldScreen && worldFrames == 0 && open != null) openScreen(current)
        if (current is WorldScreen || worldFrames > 0) worldFrames++
        val popupOpened = open == "gamemenu" || open == "options" || open?.startsWith("alert-") == true
        if (worldFrames >= 120 && (open == null || open == "found" || popupOpened || current !is WorldScreen)) {
            if (current is WorldScreen && !popupOpened) current.closeAllPopups()
            if (++settledFrames < 30) return // let the opened screen lay out and animate in
            capture()
            Gdx.app.exit()
        }
    }

    private var settledFrames = 0

    private fun showAlert(world: WorldScreen, type: AlertType) {
        val civ = world.gameInfo.getCurrentPlayerCivilization()
        val other = civ.diplomacyFunctions.getKnownCivsSorted().firstOrNull { it.isMajorCiv() }?.civName ?: civ.civName
        val value = when (type) {
            AlertType.TechResearched -> "Writing"
            AlertType.WonderBuilt -> "The Great Library"
            AlertType.StartIntro -> ""
            else -> other
        }
        AlertPopup(world, PopupAlert(type, value)).open(force = true)
    }

    /** Founds the capital where the first settler stands, as a player would on turn one. */
    private fun foundCapital(world: WorldScreen) = world.gameInfo.getCurrentPlayerCivilization().let { civ ->
        val settler = civ.units.getCivUnits().first { it.isCivilian() }
        civ.addCity(settler.getTile().position, settler)
    }

    private fun openScreen(world: WorldScreen) {
        val civ = world.gameInfo.getCurrentPlayerCivilization()
        when (open) {
            "tech" -> pushScreen { TechPickerScreen(civ) }
            "policies" -> pushScreen { PolicyPickerScreen(civ, canChangeState = true) }
            "diplomacy" -> pushScreen { DiplomacyScreen(world.selectedGameView.civView) }
            "diplomacy-civ", "trade" -> {
                // first major civ met (needs --reveal at turn one); "trade" opens the trade table with it
                val other = civ.diplomacyFunctions.getKnownCivsSorted().first { it.isMajorCiv() }
                pushScreen { DiplomacyScreen(world.selectedGameView.civView, world.selectedGameView.getForeignCivView(other),
                    showTrade = open == "trade") }
            }
            "citystate" -> {
                val other = world.gameInfo.civilizations.first { it.isCityState }
                if (!civ.knows(other)) civ.diplomacyFunctions.makeCivilizationsMeet(other)
                pushScreen { DiplomacyScreen(world.selectedGameView.civView,
                    world.selectedGameView.getForeignCivView(other)) }
            }
            "overview" -> pushScreen { EmpireOverviewScreen(world.selectedGameView.civView) }
            "notifications" -> pushScreen { EmpireOverviewScreen(world.selectedGameView.civView,
                EmpireOverviewCategories.Notifications) }
            "civilopedia" -> pushScreen { CivilopediaScreen(world.gameInfo.ruleset) }
            "victory" -> pushScreen { VictoryScreen(world) }
            "victory-won", "victory-lost" -> {
                val winner = if (open == "victory-won") civ
                    else world.gameInfo.civilizations.first { it.isMajorCiv() && it != civ }
                val type = world.gameInfo.ruleset.selectableVictories().first().name
                world.gameInfo.victoryData = VictoryData(winner, type, world.gameInfo.turns)
                pushScreen { VictoryScreen(world) }
            }
            "newgame" -> pushScreen { NewGameScreen() }
            "pantheon" -> pushScreen { PantheonPickerScreen(civ) }
            "religion" -> pushScreen {
                // founding a religion: one founder and one follower belief, plus picking the icon and name
                ReligiousBeliefsPickerScreen(civ, Counter<BeliefType>().apply { add(BeliefType.Founder, 1); add(BeliefType.Follower, 1) }, true)
            }
            "menu" -> replaceCurrentScreen { MainMenuScreen() }
            "gamemenu" -> WorldScreenMenuPopup(world).open(force = true)
            "options" -> world.openOptionsPopup()
            "promotion" -> pushScreen { PromotionPickerScreen(civ.units.getCivUnits().first { it.isMilitary() }) }
            "greatperson" -> pushScreen { GreatPersonPickerScreen(world, civ) }
            "improvement" -> {
                // a worker on the capital's neighbour, choosing what to build there
                val city = foundCapital(world)
                val worker = civ.units.addUnit("Worker", city)!!
                val tile = city.getCenterTile().neighbors.first { it.isLand && !it.isImpassible() }
                pushScreen { ImprovementPickerScreen(tile, worker) {} }
            }
            "vote" -> pushScreen { DiplomaticVotePickerScreen(civ) }
            "save" -> pushScreen { SaveGameScreen(world.gameInfo) }
            "load" -> pushScreen { LoadGameScreen() }
            "mods" -> pushScreen { ModManagementScreen() }
            "multiplayer" -> pushScreen { MultiplayerScreen() }
            "mapeditor" -> pushScreen { MapEditorScreen() }
            "city" -> pushScreen { CityScreen(world.selectedGameView.getCityView(foundCapital(world))) }
            "city-build" -> pushScreen {
                CityScreen(world.selectedGameView.getCityView(foundCapital(world)), world.gameInfo.ruleset.buildings["Monument"])
            }
            // alert-<Type>: an alert popup as the game would raise it (with --reveal so other civs are met)
            else -> if (open!!.startsWith("alert-")) showAlert(world, AlertType.valueOf(open.removePrefix("alert-")))
                else error("unknown --open=$open")
        }
    }

    private fun capture() {
        val w = Gdx.graphics.backBufferWidth
        val h = Gdx.graphics.backBufferHeight
        val fb = FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
        fb.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        super.render()
        val pixels = BufferUtils.newByteBuffer(w * h * 4)
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1)
        Gdx.gl.glReadPixels(0, 0, w, h, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels)
        fb.end()
        fb.dispose()
        // GL rows run bottom-up; PNG rows run top-down
        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        val row = w * 4
        val bytes = ByteArray(w * h * 4)
        pixels.get(bytes)
        val flipped = ByteArray(bytes.size)
        for (y in 0 until h) System.arraycopy(bytes, y * row, flipped, (h - 1 - y) * row, row)
        BufferUtils.copy(flipped, 0, pixmap.pixels, flipped.size)
        PixmapIO.writePNG(Gdx.files.absolute(outFile.absolutePath), pixmap)
        pixmap.dispose()
        println("Captured ${w}x$h to ${outFile.absolutePath}")
    }
}
