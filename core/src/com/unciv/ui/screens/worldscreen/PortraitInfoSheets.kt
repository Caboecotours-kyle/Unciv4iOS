package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.PortraitStatIcons
import com.unciv.ui.popups.PortraitTray
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.screens.pickerscreens.PromotionPickerScreen
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import kotlin.math.floor

/** Portrait tile and unit info as tray sheets (direction B of mocks/drawers-proposal.html). */
internal object PortraitInfoSheets {
    /** The map's tile details: [PortraitTileCard] in a sheet. */
    fun showTile(worldScreen: WorldScreen, tileView: TileView) {
        val civView = worldScreen.selectedGameView.civView
        // A retained selection may still carry the previous spectator or civilization perspective.
        val tile = civView.gameView.getTile(tileView)
        if (!tile.isExplored()) return
        InfoSheet(worldScreen) { info ->
            val card = PortraitTileCard(worldScreen, tile, civView, info.icons, p = info.p, touch = info.touch, tray = info.sheet)
            info.name = "portrait-tile-sheet"
            info.sheet.header(card.header())
            card.yields(info.contentWidth)?.let { info.sheet.addToBody(it, 4f) }
            info.sheet.addToBody(card.rows(), 8f)
        }.present()
    }

    /** Health, strength, moves, experience, promotions and the tile a unit stands on. */
    fun showUnit(worldScreen: WorldScreen, unitView: MapUnitView) {
        InfoSheet(worldScreen) { info -> info.name = "portrait-unit-sheet"; unitContent(worldScreen, unitView, info) }.present()
    }

    private fun unitContent(worldScreen: WorldScreen, unit: MapUnitView, info: InfoSheet) {
        val sheet = info.sheet
        fun pt(value: Float) = sheet.pt(value)
        val civView = worldScreen.selectedGameView.civView
        val tile = unit.getTile()
        val card = PortraitTileCard(worldScreen, tile, civView, info.icons, hideUnits = true, p = info.p, touch = info.touch, tray = sheet)

        val base = unit.getBaseUnit()
        sheet.header(unit.displayName(), "${unit.civName.tr(hideIcons = true)} · ${base.unitType.tr(hideIcons = true)}",
            PortraitTileCard.unitDisc(unit, pt(32f)))

        val health = unit.unitHealth.coerceIn(0, 100)
        val barWidth = info.contentWidth - pt(32f)
        sheet.addToBody(Table().apply { add(card.healthBar(health, barWidth, pt(10f), tray = true)).width(floor(barWidth)).height(pt(10f)) }, 6f)
        val caption = Table()
        caption.add("Health".toLabel(TrayColors.INK2, sheet.font(13.5f))).expandX().left()
        caption.add("$health / 100".toLabel(TrayColors.INK2, sheet.font(13.5f)))
        sheet.addToBody(caption, 6f).padLeft(pt(16f)).padRight(pt(16f))

        val promotions = unit.getPromotions()
        val strength = if (base.rangedStrength > 0) base.rangedStrength to "Ranged strength" else base.strength to "Strength"
        val boxes = listOf(strength.first.tr() to strength.second, unit.getMovementString() to "Movement",
            "${promotions.XP}/${promotions.xpForNextPromotion()}" to "XP").map { (value, label) -> sheet.statBox(value, label) }
        sheet.addToBody(sheet.statGrid(boxes, 3), 10f)

        val own = civView.isOwnerOf(unit) && !civView.isSpectator()
        val count = promotions.promotions.size
        val meta = if (count > 0) null else "Available at [${promotions.xpForNextPromotion()}] XP"
        val end = if (count > 0) sheet.pill(count.tr(), TrayColors.FILL, Color.WHITE) else null
        val promotionRow = sheet.row("Promotions", meta, sheet.disc(ImageGetter.getImage("OtherIcons/Shield")), trailing = end,
            onTap = if (own) ({ info.close(); worldScreen.game.pushScreen { PromotionPickerScreen(unit.getUnit()) } }) else null)
            .apply { name = "portrait-unit-promotions" }
        val tileIcon = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(TrayColors.FILL)
            add(card.thumbnail(pt(40f), pt(36f))).size(pt(40f), pt(36f))
        }
        val tileRow = sheet.row("On [${card.title}]".tr(hideIcons = true), lead = tileIcon, trailing = card.defencePill(" " + "defense".tr()),
            onTap = { info.close(); showTile(worldScreen, tile) }).apply { name = "portrait-unit-tile" }
        sheet.addToBody(sheet.card(listOf(promotionRow, tileRow)), 8f)
    }

    /** A tray sheet at most 64% of the phone tall, scrolling when longer; the stat icons go with its textures. */
    class InfoSheet(world: WorldScreen, build: (InfoSheet) -> Unit) : PortraitTray(world, maxShare = .64f) {
        val p get() = sheet.p
        val touch get() = sheet.touch
        /** Whole units, so the rounded table layout never pushes the body past the safe width */
        val contentWidth = floor(world.safeAreaBoundsInWorld().width - sheet.pt(24f))
        internal val icons = PortraitStatIcons()

        init {
            closeListeners.add { icons.dispose() }
            build(this)
        }

        /** Screen disposal clears the stage without [close]; the icons leave with the sheet's own textures */
        override fun setStage(stage: Stage?) {
            super.setStage(stage)
            if (stage == null) icons.dispose()
        }
    }
}
