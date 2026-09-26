package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** Simple class for showing a prompt for a positive integer to the user
 * @param screen The previous screen the user was on
 * @param label A line of text shown to the user
 * @param icon Icon at the top, should have size 80f
 * @param defaultValue The number that should be in the prompt at the start
 * @param amountButtons Buttons that when clicked will add/subtract these amounts to the number
 * @param bounds The bounds in which the number must lie. Defaults to [Int.MIN_VALUE, Int.MAX_VALUE]
 * @param errorText Text that will be shown when an error is detected
 * @param validate Function that should return `true` when a valid input is detected
 * @param actionOnOk Lambda that will be executed after pressing 'OK'.
 */

class AskNumberPopup(
    screen: BaseScreen,
    label: String = "Please enter a number",
    icon: IconCircleGroup = ImageGetter.getImage("OtherIcons/Pencil").apply { this.color = ImageGetter.CHARCOAL }.surroundWithCircle(80f),
    defaultValue: Int? = null,
    amountButtons: List<Int> = listOf(),
    bounds: IntRange = IntRange(Int.MIN_VALUE, Int.MAX_VALUE),
    errorText: String = "Invalid input! Please enter a valid number.",
    validate: (input: Int) -> Boolean = { true },
    actionOnOk: (input: Int) -> Unit = { },
): Popup(screen, maxSizePercentage = PortraitDialog.sizePercentage(screen.stage)) {
    private val portrait = screen.isPortrait()

    init {
        val pointScale = screen.safeAreaBoundsInWorld().width / 393f
        // Portrait uses the card width of the other dialogs, less the popup's 25pt inner padding per side
        val contentWidth = if (portrait) maxPopupWidth - 50f else goodTextWidth
        if (portrait) PortraitDialog.anchorCard(this, innerTable)
        val wrapper = Table()
        wrapper.add(icon).padRight(10f)
        val prompt = label.toLabel()
        if (portrait) {
            prompt.wrap = true
            wrapper.add(prompt).width(contentWidth - icon.width - 10f)
        } else wrapper.add(prompt)
        add(wrapper).colspan(2).row()

        val nameField = UncivTextField.Integer(label, defaultValue)

        fun clampInBounds(input: Int?): Int? {
            if (input == null) return null

            if (bounds.first > input) {
                return bounds.first
            }
            if (bounds.last < input)
                return bounds.last

            return input
        }

        nameField.onChange {
            nameField.intValue = clampInBounds(nameField.intValue)
        }

        val centerTable = Table(skin)

        fun addValueButton(delta: Int) {
            val button = Button(delta.toStringSigned().toLabel(), skin).apply {
                onClick {
                    val value = nameField.intValue ?: return@onClick
                    nameField.intValue = clampInBounds(value + delta)
                }
            }
            if (portrait) button.style = PortraitDialog.buttonStyle(PortraitDialog.Kind.Ghost)
            val cell = centerTable.add(button).pad(5f)
            if (portrait) cell.width(contentWidth / (amountButtons.size * 2).coerceAtLeast(1) - 10f)
                .height(48f * pointScale)
        }

        val errorLabel = errorText.toLabel()
        errorLabel.color = Color.RED
        errorLabel.wrap = portrait
        // Portrait shows the error once, right under the field; landscape keeps appending it under the steppers
        var portraitErrorCell: Cell<Actor?>? = null

        if (portrait) {
            val columns = (amountButtons.size * 2).coerceAtLeast(1)
            nameField.style = TextFieldStyle(nameField.style).apply {
                background = PortraitDialog.panel(Color.valueOf("0b1826")).apply {
                    leftWidth = 16f * pointScale
                    rightWidth = 16f * pointScale
                }
                focusedBackground = null
            }
            centerTable.add(nameField).width(contentWidth - 20f).height(56f * pointScale)
                .colspan(columns).pad(10f).row()
            portraitErrorCell = centerTable.add().colspan(columns)
            centerTable.row()
            errorLabel.color = PortraitDialog.ERROR
            for (value in amountButtons.reversed()) addValueButton(-value)
            for (value in amountButtons) addValueButton(value)
        } else {
            for (value in amountButtons.reversed()) addValueButton(-value)
            centerTable.add(nameField).growX().pad(10f)
            for (value in amountButtons) addValueButton(value)
        }
        add(centerTable).colspan(2).row()

        val closeCell = addCloseButton()
        val okCell = addOKButton(
            validate = {
                val errorFound = nameField.intValue?.let { validate(it) } != true
                val errorSlot = portraitErrorCell
                if (errorFound) {
                    if (errorSlot == null) add(errorLabel).colspan(2).center()
                    else if (errorSlot.actor == null) {
                        errorSlot.setActor(errorLabel).width(contentWidth - 20f).padBottom(4f)
                        centerTable.invalidateHierarchy()
                    }
                }
                !errorFound
            }
        ) {
            actionOnOk(nameField.intValue!!)
        }
        if (portrait) {
            // Cancel and OK side by side, the one row that must fit above the keyboard
            PortraitDialog.styleButton(closeCell.actor, PortraitDialog.Kind.Ghost)
            PortraitDialog.styleButton(okCell.actor, PortraitDialog.Kind.Primary)
            closeCell.growX().uniformX().height(56f * pointScale)
            okCell.growX().uniformX().height(56f * pointScale)
        } else equalizeLastTwoButtonWidths()

        keyboardFocus = nameField
    }

    /** Portrait keeps the card anchored to the top of the keyboard instead of the base class's push-from-center */
    override fun onVisibleAreaChanged(visibleArea: Rectangle) {
        super.onVisibleAreaChanged(visibleArea)
        if (!portrait) return
        padLeft(visibleArea.x)
        padBottom(visibleArea.y)
        padRight(stageToShowOn.width - visibleArea.x - visibleArea.width)
        padTop(stageToShowOn.height - visibleArea.y - visibleArea.height)
        invalidate()
    }
}
