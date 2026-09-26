package com.unciv.ui.images

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Disposable

/** The approved HUD artwork, owned and released by the screen using it. */
internal class PortraitStatIcons : Disposable {
    private var texture: Texture? = null

    fun image(name: String): Image {
        val index = names.indexOf(name)
        if (index < 0) return ImageGetter.getStatIcon(name)
        val atlas = texture ?: Texture(Gdx.files.internal("ExtraImages/PortraitStats.png")).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            texture = it
        }
        return Image(TextureRegion(atlas, index * 64, 0, 64, 64))
    }

    override fun dispose() { texture?.dispose(); texture = null }

    private companion object {
        val names = listOf("Food", "Production", "Gold", "Science", "Culture", "Faith", "Happiness", "Strength", "Ranged", "Movement")
    }
}
