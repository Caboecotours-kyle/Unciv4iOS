package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.unciv.logic.civilization.Civilization
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.utils.Display

/** Desktop mocks include status and home areas; native safe stages already exclude those pixels. */
internal fun BaseScreen.portraitChromeGaps(): Pair<Float, Float> {
    val viewport = stage.viewport as SafeAreaViewport
    viewport.apply()
    val safe = safeAreaBoundsInWorld()
    val drawing = viewport.drawingBounds
    val insets = Display.getSafeInsets()
    val safePixelHeight = (Gdx.graphics.height - insets.top - insets.bottom).coerceAtLeast(1)
    val unitsPerPixel = safe.height / safePixelHeight
    val topInset = maxOf((drawing.y + drawing.height - safe.y - safe.height).coerceAtLeast(0f), insets.top * unitsPerPixel)
    val bottomInset = maxOf((safe.y - drawing.y).coerceAtLeast(0f), insets.bottom * unitsPerPixel)
    return (56f - topInset).coerceAtLeast(0f) to (30f - bottomInset).coerceAtLeast(0f)
}

/** Captures the player's rendered map once, without HUD actors or extra map copies. */
internal class PortraitMapBackdrop(civ: Civilization) : Actor(), com.badlogic.gdx.utils.Disposable {
    private var buffer: com.badlogic.gdx.graphics.glutils.FrameBuffer? = null
    private var region: com.badlogic.gdx.graphics.g2d.TextureRegion? = null

    init {
        touchable = Touchable.disabled
        val world = com.unciv.UncivGame.Current.worldScreen
        if (world != null && world.gameInfo === civ.gameInfo) {
            val viewport = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(4)
            Gdx.gl.glGetIntegerv(com.badlogic.gdx.graphics.GL20.GL_VIEWPORT, viewport)
            val actors = world.stage.actors.toList()
            val visibility = actors.map { it.isVisible }
            val snapshot = com.badlogic.gdx.graphics.glutils.FrameBuffer(com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888,
                Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight, false)
            try {
                snapshot.begin()
                Gdx.gl.glClearColor(.07f, .14f, .21f, 1f)
                Gdx.gl.glClear(com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT)
                actors.forEach { it.isVisible = it === world.mapHolder }
                world.stage.viewport.apply()
                world.stage.draw()
                buffer = snapshot
                region = com.badlogic.gdx.graphics.g2d.TextureRegion(snapshot.colorBufferTexture).apply { flip(false, true) }
            } finally {
                actors.forEachIndexed { index, actor -> actor.isVisible = visibility[index] }
                snapshot.end()
                Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3))
                if (buffer == null) snapshot.dispose()
            }
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val image = region ?: return
        val old = batch.packedColor
        batch.setColor(.68f, .68f, .68f, parentAlpha)
        batch.draw(image, x, y, width, height)
        batch.packedColor = old
    }

    override fun dispose() {
        buffer?.dispose()
        buffer = null
        region = null
    }
}
