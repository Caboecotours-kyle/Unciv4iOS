package com.unciv.ui.popups

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport

/** Portrait look shared by the dialog families: a navy card in thumb reach over a dimmed screen (see DESIGN.md). */
internal object PortraitDialog {
    val NAVY = Color(16f / 255f, 31f / 255f, 47f / 255f, .9f)
    private val SCRIM = Color(10f / 255f, 22f / 255f, 34f / 255f, .5f)
    val YELLOW: Color = Color.valueOf("ffc93c")
    private val YELLOW_INK = Color.valueOf("3a2a00")
    private val RED = Color.valueOf("e0524a")
    private val GHOST = Color(1f, 1f, 1f, .09f)
    val ERROR: Color = Color.valueOf("ffb1aa")

    enum class Kind { Primary, Danger, Ghost }

    fun isPortrait(stage: Stage) = stage.height > stage.width

    private fun safeBounds(stage: Stage) = (stage.viewport as? SafeAreaViewport)?.safeAreaBoundsInWorld
        ?: Rectangle(0f, 0f, stage.width, stage.height)

    /** Same convention as the portrait screens: one unit of a 393pt-wide phone */
    fun scale(stage: Stage) = safeBounds(stage).width / 393f

    /** Popup size limit that leaves the 10pt side margins of the mock in portrait, the usual 90% elsewhere */
    fun sizePercentage(stage: Stage) =
        if (isPortrait(stage)) (stage.width - 20f * scale(stage)) / stage.width else 0.9f

    fun panel(color: Color) = BaseScreen.skinStrings.getUiBackground("",
        BaseScreen.skinStrings.roundedEdgeRectangleShape, color)

    fun buttonStyle(kind: Kind) = TextButtonStyle(BaseScreen.skin[TextButtonStyle::class.java]).apply {
        val fill = when (kind) {
            Kind.Primary -> YELLOW
            Kind.Danger -> RED
            Kind.Ghost -> GHOST
        }
        up = panel(fill)
        down = panel(if (kind == Kind.Ghost) Color(1f, 1f, 1f, .18f) else fill.darken(.15f))
        over = up
        fontColor = if (kind == Kind.Primary) YELLOW_INK else Color.WHITE
        downFontColor = fontColor
        overFontColor = fontColor
    }

    /** Restyles a button the base [Popup] created, keeping its actions and key bindings */
    fun styleButton(button: TextButton, kind: Kind) {
        button.style = buttonStyle(kind)
        button.label.wrap = true
        button.label.setAlignment(Align.center)
    }

    /** Dims the screen like the mock scrim and anchors [card] (the popup's inner table) to the bottom at full card width */
    fun anchorCard(popup: Popup, card: Table) {
        val scale = scale(popup.stageToShowOn)
        popup.background = BaseScreen.skinStrings.getUiBackground("General/Popup/Background", tintColor = SCRIM)
        card.background = panel(NAVY)
        popup.align(Align.bottom)
        popup.cells.first().width(popup.stageToShowOn.width * sizePercentage(popup.stageToShowOn))
            .padBottom(12f * scale)
    }
}
