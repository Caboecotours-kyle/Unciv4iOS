package com.unciv.app.desktop

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.utils.BufferUtils
import com.unciv.logic.GameStarter
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
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.DebugUtils
import java.io.File

/**
 * Screenshot harness for checking visuals without a visible window (`--capture=out.png`).
 * Quickstarts a game, waits for the world map to settle, renders one frame into an offscreen buffer,
 * writes it as a PNG, and exits. Works while the desktop session is locked or has no display.
 */
/** An iPhone 15 screen in points, doubled: the UI lays out as it would on the phone. */
private const val PHONE_W = 786
private const val PHONE_H = 1704

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
                if (setup.gameParameters.victoryTypes.isEmpty())
                    setup.gameParameters.victoryTypes.addAll(
                        RulesetCache.getComplexRuleset(setup.gameParameters).selectableVictories().map { it.name })
                val newGame = GameStarter.startNewGame(setup)
                loadGame(newGame)
            }
        }
        // count frames from the first world frame on, including frames spent on a screen opened over it
        if (current is WorldScreen && worldFrames == 0 && open != null) openScreen(current)
        if (current is WorldScreen || worldFrames > 0) worldFrames++
        if (worldFrames >= 120 && (open == null || current !is WorldScreen)) {
            if (current is WorldScreen) current.closeAllPopups()
            if (++settledFrames < 30) return // let the opened screen lay out and animate in
            capture()
            Gdx.app.exit()
        }
    }

    private var settledFrames = 0

    private fun openScreen(world: WorldScreen) {
        val civ = world.gameInfo.getCurrentPlayerCivilization()
        when (open) {
            "tech" -> pushScreen { TechPickerScreen(civ) }
            "policies" -> pushScreen { PolicyPickerScreen(civ, canChangeState = true) }
            "diplomacy" -> pushScreen { DiplomacyScreen(world.selectedGameView.civView) }
            "overview" -> pushScreen { EmpireOverviewScreen(world.selectedGameView.civView) }
            "civilopedia" -> pushScreen { CivilopediaScreen(world.gameInfo.ruleset) }
            "victory" -> pushScreen { VictoryScreen(world) }
            "newgame" -> pushScreen { NewGameScreen() }
            "menu" -> replaceCurrentScreen { MainMenuScreen() }
            "city" -> {
                // found the capital where the first settler stands, then open it
                val settler = civ.units.getCivUnits().first { it.isCivilian() }
                val city = civ.addCity(settler.getTile().position, settler)
                pushScreen { CityScreen(world.selectedGameView.getCityView(city)) }
            }
            else -> error("unknown --open=$open")
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
