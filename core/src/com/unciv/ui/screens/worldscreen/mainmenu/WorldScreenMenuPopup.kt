package com.unciv.ui.screens.worldscreen.mainmenu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitTray
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The in-game menu called from the "Hamburger" button top-left
 *
 *  Popup automatically opens as soon as it's initialized.
 *  In portrait it is a tray sheet (grouped rows, Exit last in red); in landscape the boxed button stack.
 */
class WorldScreenMenuPopup(
    val worldScreen: WorldScreen,
    private val expertMode: Boolean = false
) : PortraitTray(worldScreen, tray = worldScreen.isPortrait(), scrollable = Scrollability.All) {
    private var singleColumn = true
    private fun <T: Actor?> Cell<T>.nextColumn() {
        if (!singleColumn && column == 0) return
        row()
    }

    /** One menu entry: the same action and key binding in both layouts */
    private class Entry(val title: String, val icon: String, val binding: KeyboardBinding?, val action: () -> Unit)

    init {
        worldScreen.autoPlay.stopAutoPlay()

        val showSave = !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        val showMusic = worldScreen.game.musicController.isMusicAvailable()
        val showConsole = showSave && expertMode

        val mainMenu = Entry("Main menu", "OtherIcons/Resume", null) {
            worldScreen.game.goToMainMenu()
        }
        val civilopedia = Entry("Civilopedia", "OtherIcons/Question", KeyboardBinding.Civilopedia) {
            close()
            worldScreen.openCivilopedia()
        }
        val save = Entry("Save game", "OtherIcons/ModUpdate", KeyboardBinding.SaveGame) {
            close()
            worldScreen.openSaveGameScreen()
        }
        val load = Entry("Load game", "OtherIcons/Load", KeyboardBinding.LoadGame) {
            close()
            worldScreen.game.pushScreen{ LoadGameScreen() }
        }
        val newGame = Entry("Start new game", "OtherIcons/New", KeyboardBinding.NewGame) {
            close()
            worldScreen.openNewGameScreen()
        }
        val victory = Entry("Victory status", "OtherIcons/Star", KeyboardBinding.VictoryScreen) {
            close()
            worldScreen.game.pushScreen{ VictoryScreen(worldScreen) }
        }
        val achievements = Entry("Achievements", "OtherIcons/AchievementTrophy", null) {
            close()
            worldScreen.game.showAchievements()
        }
        val guide = Entry("Achievement guide", "OtherIcons/Quest", null) {
            close()
            worldScreen.game.showAchievementGuide()
        }
        val options = Entry("Options", "OtherIcons/Options", KeyboardBinding.Options) {
            close()
            worldScreen.openOptionsPopup()
        }
        val optionsWithDebug = {
            close()
            worldScreen.openOptionsPopup(withDebug = true)
        }
        val music = Entry("Music", "OtherIcons/Speaker", KeyboardBinding.MusicPlayer) {
            close()
            WorldScreenMusicPopup(worldScreen).open(force = true)
        }
        val console = Entry("Developer Console", "OtherIcons/Keyboard", KeyboardBinding.DeveloperConsole) {
            close()
            worldScreen.openDeveloperConsole()
        }
        val exit = Entry("Exit", "OtherIcons/Close", null) {
            close()
            Gdx.app.exit()
        }
        val achievementEntries = if (worldScreen.game.achievementsAvailable) listOf(achievements, guide) else emptyList()

        if (tray) {
            // Mock grouping: files, reference and settings, leaving the game; Exit last and red, never the thumb default
            val files = listOfNotNull(save.takeIf { showSave }, load)
            val reference = listOf(civilopedia, victory) + achievementEntries +
                listOfNotNull(options, music.takeIf { showMusic }, console.takeIf { showConsole })
            val leave = listOf(newGame, mainMenu)
            with(sheet) {
                header("Menu")
                for ((index, group) in listOf(files, reference, leave).withIndex()) {
                    val rows = group.map { entry ->
                        row(entry.title, lead = disc(ImageGetter.getImage(entry.icon)), binding = entry.binding) { entry.action() }
                            .also { row -> if (entry === options) row.onLongPress { optionsWithDebug() } }
                    }.toMutableList()
                    if (index == 2) rows += row(exit.title, lead = disc(ImageGetter.getImage(exit.icon), TraySheet.Tone.Danger),
                        state = TraySheet.RowState.Danger, chevron = false) { exit.action() }
                    addToBody(card(rows), if (index == 0) 4f else 26f)
                }
            }
        } else legacy(showSave, showMusic, showConsole, mainMenu, civilopedia, save, load, newGame, victory, achievementEntries,
            options, optionsWithDebug, music, console, exit)

        open(force = true)
    }

    /** Rotated or resized while open: reopen, so landscape gets the boxed stack and portrait a sheet at the new scale */
    override fun onMetricsChanged() {
        close()
        WorldScreenMenuPopup(worldScreen, expertMode)
    }

    /** The boxed button stack, unchanged */
    private fun legacy(showSave: Boolean, showMusic: Boolean, showConsole: Boolean, mainMenu: Entry, civilopedia: Entry, save: Entry,
                       load: Entry, newGame: Entry, victory: Entry, achievementEntries: List<Entry>, options: Entry,
                       optionsWithDebug: () -> Unit, music: Entry, console: Entry, exit: Entry) {
        defaults().fillX()
        val buttonCount = 8 + (if (showSave) 1 else 0) + (if (showMusic) 1 else 0) + (if (showConsole) 1 else 0) +
            achievementEntries.size

        val emptyPrefHeight = this.prefHeight
        val firstCell = addButton(mainMenu.title) { mainMenu.action() }
        singleColumn = worldScreen.isCrampedPortrait() ||
            2 * prefWidth > maxPopupWidth ||  // Very coarse: Assume width of translated "Main menu" is representative
            buttonCount * (prefHeight - emptyPrefHeight) + emptyPrefHeight < maxPopupHeight
        firstCell.nextColumn()

        fun add(entry: Entry) = (if (entry.binding == null) addButton(entry.title) { entry.action() }
            else addButton(entry.title, entry.binding) { entry.action() })
        add(civilopedia).nextColumn()
        if (showSave) add(save).nextColumn()
        add(load).nextColumn()
        add(newGame).nextColumn()
        add(victory).nextColumn()
        for (entry in achievementEntries) add(entry).nextColumn()
        val optionsCell = add(options)
        optionsCell.actor.onLongPress { optionsWithDebug() }
        optionsCell.nextColumn()
        if (showMusic) add(music).nextColumn()
        if (showConsole) add(console).nextColumn()

        add(exit).apply { actor.style = BaseScreen.skin.get("negative", TextButtonStyle::class.java) }
            .nextColumn()

        addCloseButton().run { colspan(if (singleColumn || column == 1) 1 else 2) }
        pack()
    }
}
