package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image
import kotlin.math.sin

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

/** One hop of the approved motion mock: crouch, an airborne arc leaning into the travel, then a squash landing. */
internal class HopPose {
    var travel = 0f
    var lift = 0f
    var scaleX = 1f
    var scaleY = 1f
    var lean = 0f

    /** [q] is the hop's progress 0..1; [rightward] leans the sprite toward its travel. */
    fun at(q: Float, rightward: Boolean): HopPose {
        lift = 0f
        lean = 0f
        when {
            q < 0.14f -> {
                val k = q / 0.14f
                travel = 0f; scaleX = 1f + 0.08f * k; scaleY = 1f - 0.14f * k
            }
            q < 0.86f -> {
                val k = (q - 0.14f) / 0.72f
                val air = sin(k * Math.PI).toFloat()
                travel = 1f - (1f - k) * (1f - k) * (1f - k)
                lift = air; scaleX = 0.95f; scaleY = 1.07f
                lean = (if (rightward) -5f else 5f) * air
            }
            else -> {
                val squash = sin((q - 0.86f) / 0.14f * Math.PI).toFloat()
                travel = 1f; scaleX = 1f + 0.10f * squash; scaleY = 1f - 0.16f * squash
            }
        }
        return this
    }
}
