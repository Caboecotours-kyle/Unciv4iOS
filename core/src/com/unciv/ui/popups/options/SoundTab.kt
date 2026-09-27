package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.UncivSound
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.MusicControls
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.utils.Concurrency
import com.unciv.utils.DEFAULT_MUSIC_DOWNLOAD_UNAVAILABLE
import com.unciv.utils.launchOnGLThread

internal class SoundTab(
    optionsPopup: OptionsPopup
) : OptionsPopupTab(optionsPopup), MusicControls {
    override fun lateInitialize() {
        val music = game.musicController

        addSoundEffectsVolumeSlider(settings)
        addCitySoundsVolumeSlider(settings)

        if (music.isVoicesAvailable())
            addVoicesVolumeSlider(settings, music)

        if (music.isMusicAvailable())
            addMusicControls(settings, music)

        if (!music.isDefaultFileAvailable()) {
            if (game.platformCapabilities.defaultMusicDownload)
                addDownloadMusic()
            else
                add(DEFAULT_MUSIC_DOWNLOAD_UNAVAILABLE.toLabel(Color.GRAY))
                    .colspan(2).padTop(20f).row()
        }

        super.lateInitialize()
    }

    /** Portrait: the volume label stacks over its slider instead of squeezing beside it */
    override fun Table.addVolumeSlider(text: String, initial: Float, silent: Boolean, onChange: (Float) -> Unit) {
        val style = portrait
            // Kotlin can't call super on a member extension, so landscape goes through the plain default
            ?: return with(object : MusicControls {}) { this@addVolumeSlider.addVolumeSlider(text, initial, silent, onChange) }
        val slider = UncivSlider(
            0f, 1.0f, 0.05f,
            initial = initial,
            sound = if (silent) UncivSound.Silent else UncivSound.Slider,
            tipType = UncivSlider.TipType.None,
            getTipText = UncivSlider::formatPercent,
            onChange = onChange
        )
        add(SliderRow(style, text, slider, 0.05f, UncivSlider::formatPercent)).colspan(2).growX().pad(0f).row()
    }

    private fun addDownloadMusic() {
        val downloadMusicButton = "Download music".toTextButton()
        add(downloadMusicButton).colspan(2).padTop(20f).row()
        val errorTable = Table()
        add(errorTable).colspan(2).row()

        downloadMusicButton.onClick {
            downloadMusicButton.disable()
            errorTable.clear()
            errorTable.add("Downloading...".toLabel())

            // So the whole game doesn't get stuck while downloading the file
            Concurrency.run("MusicDownload") {
                try {
                    game.musicController.downloadDefaultFile()
                    launchOnGLThread {
                        replacePage { optionsPopup -> SoundTab(optionsPopup) }
                        game.musicController.chooseTrack(flags = MusicTrackChooserFlags.setPlayDefault)
                    }
                } catch (_: Exception) {
                    launchOnGLThread {
                        errorTable.clear()
                        errorTable.add("Could not download music!".toLabel(Color.RED))
                    }
                }
            }
        }
    }
}
