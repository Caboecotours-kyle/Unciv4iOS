package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.PortraitStatIcons
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.view.TileView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Portrait tile info as the accepted navy bottom sheet (see WorldPortraitHud's More drawer). */
internal object PortraitInfoSheets {
    /** The map's tile details: [PortraitTileCard] in a sheet. */
    fun showTile(worldScreen: WorldScreen, tileView: TileView) {
        val civView = worldScreen.selectedGameView.civView
        // A retained selection may still carry the previous spectator or civilization perspective.
        val tile = civView.gameView.getTile(tileView)
        if (!tile.isExplored()) return
        InfoSheet(worldScreen) { sheet ->
            val card = PortraitTileCard(worldScreen, tile, civView, sheet.icons, p = sheet.p)
            sheet.name = "portrait-tile-sheet"
            sheet.header(card.header(sheet.closeButton()))
            card.yields(sheet.contentWidth)?.let { sheet.body.add(it).left().padTop(sheet.pt(14f)).padBottom(sheet.pt(6f)).row() }
            sheet.body.add(card.rows()).growX().padTop(sheet.pt(4f)).row()
        }.present()
    }

    /** One navy bottom sheet: grab handle, 28pt top radius, swipe or Close to dismiss, click-behind closes,
     *  within the safe area, at most 64% of the screen, scrolling when longer. */
    class InfoSheet(private val world: WorldScreen, build: (InfoSheet) -> Unit) : Popup(world, Scrollability.None, 1f) {
        val p = world.safeAreaBoundsInWorld().width / 393f
        fun pt(value: Float) = value * p
        /** Whole units, so the rounded table layout never pushes the sheet past the safe width */
        val contentWidth = floor(world.safeAreaBoundsInWorld().width - pt(32f))
        internal val icons = PortraitStatIcons()
        val body = Table().top().left()
        private val headerTable = Table()
        private val canvasY = world.portraitCanvasBounds().y
        private val sheetColor = Color.valueOf("122536")
        private val fill = ImageGetter.getWhiteDotDrawable()
        private val panelTexture: Texture

        init {
            background = fill.tint(Color(10f / 255f, 22f / 255f, 34f / 255f, .35f))
            panelTexture = roundedTexture(pt(28f).roundToInt())
            val radius = pt(28f).roundToInt()
            innerTable.background = NinePatchDrawable(NinePatch(panelTexture, radius, radius, radius, radius)).tint(sheetColor)
            innerTable.pad(0f).defaults().pad(0f)
            getCell(innerTable)?.expand()?.bottom()
            clickBehindToClose = true
            closeListeners.add { icons.dispose(); panelTexture.dispose() }

            val top = Table()
            top.touchable = Touchable.enabled
            top.add(Table().apply { background = ImageGetter.getCircleDrawable().tint(Color(1f, 1f, 1f, .34f)) })
                .size(pt(44f), pt(5f)).padTop(pt(9f)).padBottom(pt(12f)).row()
            top.add(headerTable).growX().padLeft(pt(16f)).padRight(pt(16f))
            top.addListener(swipeToClose())
            add(top).growX().row()

            build(this)

            val pane = AutoScrollPane(body).apply {
                setOverscroll(false, false)
                setScrollingDisabled(true, false)
            }
            val bottomGap = pt((30f - (world.safeAreaBoundsInWorld().y - canvasY) / p).coerceAtLeast(12f))
            // Size wrapped labels at their real width, then cap the body so the sheet stays within 64% of the phone.
            for ((table, width) in listOf(body to contentWidth, top to world.safeAreaBoundsInWorld().width)) {
                table.width = width
                table.validate()
                table.invalidate()
            }
            val maxBody = world.portraitCanvasBounds().height * .64f - top.prefHeight - bottomGap
            add(pane).width(contentWidth).height(min(body.prefHeight, maxBody.coerceAtLeast(pt(56f))))
                .padLeft(pt(16f)).padRight(pt(16f)).padBottom(bottomGap).row()
        }

        fun header(actor: Actor) { headerTable.add(actor).growX() }

        /** 48pt round Close, also the back key */
        fun closeButton(): Actor = Table().apply {
            name = "portrait-sheet-close"
            touchable = Touchable.enabled
            background = ImageGetter.getCircleDrawable().tint(Color(1f, 1f, 1f, .1f))
            add(ImageGetter.getImage("OtherIcons/Close").apply { color = Color.WHITE }).size(pt(18f))
            onActivation { close() }
            keyShortcuts.add(KeyCharAndCode.BACK)
        }.let { button -> Table().apply { add(button).size(pt(48f)) } }

        fun present() {
            open(force = true)
            fit()
        }

        /** Full safe width, pinned to the bottom */
        private fun fit() {
            val safe = world.safeAreaBoundsInWorld()
            pad(0f).padLeft(safe.x).padBottom(safe.y).padRight(world.stage.width - safe.x - safe.width)
            // An explicit whole width: a grown cell rounds its fractional stage width up past the safe edge
            getCell(innerTable)?.width(floor(safe.width))
            invalidate()
        }

        override fun onVisibleAreaChanged(visibleArea: Rectangle) = fit()

        override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
            super.drawBackground(batch, parentAlpha, x, y)
            // Carry the sheet under the home indicator and square off its bottom corners.
            val from = min(canvasY - y, innerTable.y)
            val fillHeight = innerTable.y + pt(28f) - from
            if (fillHeight <= 0f) return
            batch.setColor(sheetColor.r, sheetColor.g, sheetColor.b, parentAlpha)
            fill.draw(batch, x + innerTable.x, y + from, innerTable.width, fillHeight)
        }

        /** Dragging the handle or header down follows the finger; past 72pt it closes, otherwise it springs back. */
        private fun swipeToClose() = object : ActorGestureListener() {
            private var start = 0f
            private var drag = 0f
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                start = innerTable.y
                drag = 0f
            }
            override fun pan(event: InputEvent?, x: Float, y: Float, deltaX: Float, deltaY: Float) {
                drag = (drag - deltaY).coerceAtLeast(0f)
                innerTable.y = start - drag
            }
            override fun panStop(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                if (drag > pt(72f)) close()
                else innerTable.addAction(Actions.moveTo(innerTable.x, start, .15f))
            }
        }

        /** White rounded square for the sheet's nine-patch, anti-aliased at the corners */
        private fun roundedTexture(radius: Int): Texture {
            val size = radius * 2 + 2
            val pixels = Pixmap(size, size, Pixmap.Format.RGBA8888)
            pixels.blending = Pixmap.Blending.None
            for (y in 0 until size) for (x in 0 until size) {
                val dx = max(max(radius - x - .5f, x + .5f - (size - radius)), 0f)
                val dy = max(max(radius - y - .5f, y + .5f - (size - radius)), 0f)
                val alpha = (radius + .5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                pixels.drawPixel(x, y, 0xffffff00.toInt() or (alpha * 255f).roundToInt())
            }
            return Texture(pixels).also {
                pixels.dispose()
                it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            }
        }
    }
}
