package com.unciv.ui.popups

import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.screens.basescreen.BaseScreen

/** Variant of [Popup] pre-populated with one label, plus confirm and cancel buttons
 * @param stageToShowOn Parent [Stage], see [Popup.stageToShowOn]
 * @param question The text for the label
 * @param confirmText The text for the "Confirm" button
 * @param isConfirmPositive If the action to be performed is positive or not (i.e. buy = positive, delete = negative), default false
 * @param action A lambda to execute when "Yes" is chosen
 * @param restoreDefault A lambda to execute when "No" is chosen
 */
open class ConfirmPopup(
    stageToShowOn: Stage,
    question: String,
    confirmText: String,
    isConfirmPositive: Boolean = false,
    restoreDefault: () -> Unit = {},
    action: () -> Unit
) : Popup(stageToShowOn, maxSizePercentage = PortraitDialog.sizePercentage(stageToShowOn)) {

    constructor(
        screen: BaseScreen,
        question: String,
        confirmText: String,
        isConfirmPositive: Boolean = false,
        restoreDefault: () -> Unit = {},
        action: () -> Unit
    ) : this(screen.stage, question, confirmText, isConfirmPositive, restoreDefault, action)

    /** The [Label][com.badlogic.gdx.scenes.scene2d.ui.Label] created for parameter `question` for optional layout tweaking */
    private val promptLabel = question.toLabel()

    init {
        if (PortraitDialog.isPortrait(stageToShowOn)) {
            // Portrait card: question first, then the choice and Cancel stacked full width in thumb reach
            val scale = PortraitDialog.scale(stageToShowOn)
            PortraitDialog.anchorCard(this, innerTable)
            promptLabel.setFontSize(20)
            promptLabel.wrap = true
            add(promptLabel).width(maxPopupWidth - 50f).colspan(2).row()
            val confirmKind = if (isConfirmPositive) PortraitDialog.Kind.Primary else PortraitDialog.Kind.Danger
            addOKButton(confirmText, KeyboardBinding.Confirm, action = action).apply {
                PortraitDialog.styleButton(actor, confirmKind)
            }.growX().height(56f * scale).row()
            addCloseButton(Constants.cancel, KeyboardBinding.Cancel, action = restoreDefault).apply {
                PortraitDialog.styleButton(actor, PortraitDialog.Kind.Ghost)
            }.growX().height(52f * scale)
        } else {
            promptLabel.setAlignment(Align.center)
            add(promptLabel).colspan(2).row()
            addCloseButton(Constants.cancel, KeyboardBinding.Cancel, action = restoreDefault)
            val confirmStyleName = if (isConfirmPositive) "positive" else "negative"
            val confirmStyle = BaseScreen.skin.get(confirmStyleName, TextButtonStyle::class.java)
            addOKButton(confirmText, KeyboardBinding.Confirm, confirmStyle, action = action)
            equalizeLastTwoButtonWidths()
        }
    }

}
