package com.unciv.ui.popups

import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.addRoundCloseButton
import com.unciv.ui.components.input.ActivationTypes
import com.unciv.ui.components.input.clearActivationActions
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * This is an unobtrusive popup which will close itself after a given amount of time.
 * - Will show on top of other Popups, but not on top of other ToastPopups
 * - Several calls in a short time will be shown sequentially, each "waiting their turn"
 * - The user can close a Toast by clicking it
 * - Supports color markup via [ColorMarkupLabel], using «» instead of Gdx's [].
 * @param time Duration in milliseconds, defaults to 2 seconds
 */
class ToastPopup (message: String, stageToShowOn: Stage, val time: Long = 2000) : Popup(
    stageToShowOn,
    // Portrait needs no scroll pane or button area, so the navy pill hugs its text
    if (PortraitDialog.isPortrait(stageToShowOn)) Popup.Scrollability.None else Popup.Scrollability.WithoutButtons,
    PortraitDialog.sizePercentage(stageToShowOn)
) {

    constructor(message: String, screen: BaseScreen, time: Long = 2000) : this(message, screen.stage, time)

    private var timerJob: Job? = null
    private val portrait = PortraitDialog.isPortrait(stageToShowOn)

    init {
        //Make this popup unobtrusive
        setFillParent(false)
        onClick(::stayVisible) // or `touchable = Touchable.disabled` so you can operate what's behind

        if (portrait) {
            val scale = PortraitDialog.scale(stageToShowOn)
            background = null
            innerTable.background = PortraitDialog.panel(PortraitDialog.NAVY)
            innerTable.pad(12f * scale, 14f * scale, 12f * scale, 14f * scale)
            add(ColorMarkupLabel(message, 16).apply {
                wrap = true
                setAlignment(Align.center)
            }).width(maxPopupWidth - 28f * scale).pad(0f)
        } else add(ColorMarkupLabel(message).apply {
            wrap = true
            setAlignment(Align.center)
        }).width(goodTextWidth)

        open(force = stageToShowOn.actors.none { it is ToastPopup })

        // move it to the top so its not in the middle of the screen
        // has to be done after open() because open() centers the popup
        // portrait drops it just under the HUD's stats strip, like the mock
        y = if (portrait) stageToShowOn.height - (height + 70f * PortraitDialog.scale(stageToShowOn))
            else stageToShowOn.height - (height + 20f)
    }

    private fun startTimer() {
        timerJob = Concurrency.run("ResponsePopup") {
            delay(time)
            timerJob = null
            launchOnGLThread { this@ToastPopup.close() }
        }
    }
    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun close() {
        stopTimer()
        super.close()
    }

    override fun setVisible(visible: Boolean) {
        if (visible)
            startTimer()
        // setVisible(false) runs from the base constructor, before `portrait` is assigned
        if (visible && portrait) {
            color.a = 0f
            addAction(Actions.fadeIn(.35f, Interpolation.pow3Out))
        }
        super.setVisible(visible)
    }

    private fun stayVisible() {
        stopTimer()
        clearActivationActions(ActivationTypes.Tap, false)
        onClick(::close)
        addRoundCloseButton(this, ::close)
    }
}
