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
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.PortraitStatIcons
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.pickerscreens.PromotionPickerScreen
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Portrait tile and unit info as the accepted navy bottom sheet (see WorldPortraitHud's More drawer). */
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

    /** Health, strength, moves, experience, promotions and the tile a unit stands on. */
    fun showUnit(worldScreen: WorldScreen, unitView: MapUnitView) {
        InfoSheet(worldScreen) { sheet -> sheet.name = "portrait-unit-sheet"; unitContent(worldScreen, unitView, sheet) }.present()
    }

    private fun unitContent(worldScreen: WorldScreen, unit: MapUnitView, sheet: InfoSheet) {
        val p = sheet.p
        fun pt(value: Float) = value * p
        fun font(size: Int) = (size * p).roundToInt()
        val civView = worldScreen.selectedGameView.civView
        val tile = unit.getTile()
        val card = PortraitTileCard(worldScreen, tile, civView, sheet.icons, hideUnits = true, p = p)

        val header = Table()
        header.add(PortraitTileCard.unitDisc(unit, pt(40f))).size(pt(64f)).padRight(pt(13f))
        val text = Table().left()
        text.add(unit.displayName().toLabel(Color.WHITE, font(22), hideIcons = true).apply { wrap = true }).growX().left().row()
        val base = unit.getBaseUnit()
        text.add("${unit.civName.tr(hideIcons = true)} · ${base.unitType.tr(hideIcons = true)}"
            .toLabel(PortraitTileCard.INK2, font(14), hideIcons = true).apply { wrap = true }).growX().left().padTop(pt(3f))
        header.add(text).growX().minWidth(0f)
        header.add(sheet.closeButton()).padLeft(pt(8f))
        sheet.header(header)

        val health = unit.unitHealth.coerceIn(0, 100)
        val bar = card.healthBar(health, sheet.contentWidth, pt(12f))
        sheet.body.add(bar).growX().height(pt(12f)).padTop(pt(12f)).row()
        val caption = Table()
        caption.add("Health".toLabel(PortraitTileCard.INK2, font(13))).expandX().left()
        caption.add("$health / 100".toLabel(PortraitTileCard.INK2, font(13))).padRight(pt(2f))
        sheet.body.add(caption).growX().padTop(pt(6f)).row()

        val promotions = unit.getPromotions()
        val strength = if (base.rangedStrength > 0) base.rangedStrength to "Ranged strength" else base.strength to "Strength"
        val grid = Table()
        for ((index, stat) in listOf(strength.first.tr() to strength.second, unit.getMovementString() to "Movement",
                "${promotions.XP}/${promotions.xpForNextPromotion()}" to "XP").withIndex()) {
            val box = Table().top().left()
            box.background = PortraitTileCard.bg(PortraitTileCard.NAVY2)
            box.add(stat.first.toLabel(Color.WHITE, font(22))).left().row()
            box.add(stat.second.toLabel(PortraitTileCard.INK2, font(13)).apply { wrap = true }).growX().left()
            box.pad(pt(10f), pt(12f), pt(10f), pt(12f))
            grid.add(box).uniformX().growX().minHeight(pt(68f)).fillY().padLeft(if (index > 0) pt(8f) else 0f)
        }
        sheet.body.add(grid).growX().padTop(pt(14f)).padBottom(pt(8f)).row()

        val rows = Table()
        val own = civView.isOwnerOf(unit) && !civView.isSpectator()
        val count = promotions.promotions.size
        val promotionIcon = Table().apply {
            background = PortraitTileCard.bg(PortraitTileCard.NAVY2)
            add(ImageGetter.getImage("OtherIcons/Shield").apply { color = Color.valueOf("dce9f4") }).size(pt(22f))
        }
        val meta = if (count > 0) null else "Available at [${promotions.xpForNextPromotion()}] XP"
        val end = if (count > 0) listOf(card.pill(count.tr(), Color(1f, 1f, 1f, .1f), Color.WHITE)) else emptyList()
        rows.add(card.row(promotionIcon, "Promotions", meta, end,
            onTap = if (own) ({ sheet.close(); worldScreen.game.pushScreen { PromotionPickerScreen(unit.getUnit()) } }) else null)
            .apply { name = "portrait-unit-promotions" }).growX().minHeight(pt(56f)).row()
        rows.add(Image(ImageGetter.getWhiteDotDrawable().tint(PortraitTileCard.LINE))).height(max(1f, pt(1f))).growX().row()
        val tileIcon = Table().apply { add(card.thumbnail(pt(40f), pt(36f))).size(pt(40f), pt(36f)) }
        rows.add(card.row(tileIcon, "On [${card.title}]".tr(hideIcons = true), end = listOfNotNull(card.defencePill(" " + "defense".tr())),
            onTap = { sheet.close(); showTile(worldScreen, tile) }).apply { name = "portrait-unit-tile" })
            .growX().minHeight(pt(56f)).row()
        sheet.body.add(rows).growX().padTop(pt(4f)).row()
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
