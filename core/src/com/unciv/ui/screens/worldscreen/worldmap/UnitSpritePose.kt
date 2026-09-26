package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image

/** Keeps the colored layers of one unit together when applying a whole-sprite pose. */
internal class UnitSpritePose(images: Iterable<Image>) {
    private data class Rest(
        val image: Image,
        val x: Float,
        val y: Float,
        val scaleX: Float,
        val scaleY: Float,
        val rotation: Float,
    )

    private val rest = images.map { Rest(it, it.x, it.y, it.scaleX, it.scaleY, it.rotation) }

    fun isAttachedTo(parent: Group) = rest.isNotEmpty() && rest.all { it.image.parent === parent }

    fun apply(scaleX: Float = 1f, scaleY: Float = 1f, rotation: Float = 0f, rise: Float? = null) {
        for (item in rest) {
            item.image.setScale(item.scaleX * scaleX, item.scaleY * scaleY)
            item.image.rotation = item.rotation + rotation
            if (rise != null) item.image.y = item.y + rise
        }
    }

    fun restorePosition() {
        for (item in rest) {
            item.image.x = item.x
            item.image.y = item.y
        }
    }

    fun restorePose() = apply()
}
