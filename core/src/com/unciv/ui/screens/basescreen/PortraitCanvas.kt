package com.unciv.ui.screens.basescreen

import com.badlogic.gdx.math.Rectangle
import com.unciv.utils.Display

/** Full phone canvas in stage coordinates, including areas excluded by the safe viewport. */
internal fun BaseScreen.portraitCanvasBounds(): Rectangle {
    val viewport = stage.viewport as SafeAreaViewport
    if (Display.isEdgeToEdgeEnabled()) return Rectangle(viewport.drawingBounds)
    val insets = Display.getSafeInsets()
    val xUnit = viewport.worldWidth / viewport.screenWidth.coerceAtLeast(1)
    val yUnit = viewport.worldHeight / viewport.screenHeight.coerceAtLeast(1)
    return Rectangle(-insets.left * xUnit, -insets.bottom * yUnit,
        viewport.worldWidth + (insets.left + insets.right) * xUnit,
        viewport.worldHeight + (insets.top + insets.bottom) * yUnit)
}
