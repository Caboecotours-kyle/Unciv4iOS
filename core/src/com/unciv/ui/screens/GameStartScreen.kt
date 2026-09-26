package com.unciv.ui.screens

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.unciv.ui.components.extensions.center
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.utils.Display
import kotlin.math.max

/** First screen after launch, before any atlas, font or skin exists.
 *  Portrait shows the main menu's army lineup edge to edge, so the menu or language picker
 *  appears over the same art. iOS keeps this identity during its initial orientation change;
 *  desktop landscape keeps the original banner. */
class GameStartScreen : BaseScreen() {
    private var army: Image? = null
    private var banner: Image? = null

    private fun load(fileName: String) = ImageGetter.getExternalImage(fileName).also { stage.addActor(it) }

    override fun render(delta: Float) {
        if (isPortrait() || Gdx.app.type == ApplicationType.iOS) {
            val viewport = stage.viewport as SafeAreaViewport
            viewport.updateDisplay(Gdx.graphics.width, Gdx.graphics.height, Display.getSafeInsets(), edgeToEdge = true)
            banner?.isVisible = false
            val art = army ?: load("MainMenuArmy.png").also { army = it }
            art.isVisible = true
            val texture = texture(art)
            val bounds = viewport.drawingBounds
            val scale = max(bounds.width / texture.width, bounds.height / texture.height)
            art.setSize(texture.width * scale, texture.height * scale)
            art.setPosition(bounds.x + (bounds.width - art.width) / 2f, bounds.y + (bounds.height - art.height) / 2f)
        } else {
            army?.isVisible = false
            val logo = banner ?: load("banner.png").also { banner = it }
            logo.isVisible = true
            logo.center(stage)
        }
        super.render(delta)
    }

    private fun texture(image: Image): Texture = (image.drawable as TextureRegionDrawable).region.texture

    override fun dispose() {
        listOfNotNull(army, banner).forEach { texture(it).dispose() }
        super.dispose()
    }
}
