package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable

/** Project after rotation, so diagonal borders and roads follow the same plane as the hex.
 *  A reusable atlas quad avoids transform changes, batch flushes and draw-time allocations. */
internal class GroundImage(drawable: TextureRegionDrawable, private val verticalScale: Float) : Image(drawable) {
    private val sprite = Sprite(drawable.region)
    var projectionOriginY = 0f

    override fun draw(batch: Batch, parentAlpha: Float) {
        validate()
        sprite.setBounds(x + imageX, y + imageY, imageWidth, imageHeight)
        sprite.setOrigin(originX - imageX, originY - imageY)
        sprite.setScale(scaleX, scaleY)
        sprite.rotation = rotation
        sprite.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        val center = y + projectionOriginY
        val vertices = sprite.vertices
        var index = 1
        while (index < vertices.size) {
            vertices[index] = center + (vertices[index] - center) * verticalScale
            index += 5
        }
        batch.draw(sprite.texture, vertices, 0, vertices.size)
    }
}
