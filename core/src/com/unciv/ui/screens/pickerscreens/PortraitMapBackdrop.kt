package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.ui.images.ImageGetter
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

/** A small, static piece of the actual explored map behind the translucent portrait sheets. */
internal class PortraitMapBackdrop(private val civ: Civilization) : Actor() {
    private data class Hex(val x: Float, val y: Float, val color: Color)
    private val region = ImageGetter.getDrawable("OtherIcons/Hexagon").region
    private val hexes: List<Hex>

    init {
        touchable = Touchable.disabled
        val centerTile = civ.getCapital()?.getCenterTile() ?: civ.cities.firstOrNull()?.getCenterTile()
            ?: civ.units.getCivUnits().firstOrNull()?.getTile()
            ?: civ.gameInfo.tileMap.values.firstOrNull { civ.hasExplored(it) }
            ?: civ.gameInfo.tileMap.values.firstOrNull()
        val center = centerTile?.let { HexMath.hex2WorldCoords(it.position) }
        hexes = if (center == null) emptyList() else civ.gameInfo.tileMap.values.mapNotNull { tile ->
            val p = HexMath.hex2WorldCoords(tile.position)
            val x = (p.x - center.x) * 34f
            val y = (p.y - center.y) * 34f
            if (x < -400f || x > 400f || y < -600f || y > 600f) null
            else Hex(x, y, if (civ.hasExplored(tile)) tile.getBaseTerrain().getColor().cpy() else Color.valueOf("aebcca"))
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val old = batch.color.cpy()
        for (hex in hexes) {
            val tint = hex.color.cpy().lerp(Color.valueOf("132435"), .32f)
            batch.setColor(tint.r, tint.g, tint.b, parentAlpha)
            // HexMath's adjacent centers are 51px apart horizontally and 29.4px vertically
            // at this scale. A flat-top 68 × 58.9 hex meets its neighbors without gaps.
            batch.draw(region, x + width / 2f + hex.x - 34f, y + height / 2f + hex.y - 29.45f, 68f, 58.9f)
        }
        batch.color = old
    }
}
