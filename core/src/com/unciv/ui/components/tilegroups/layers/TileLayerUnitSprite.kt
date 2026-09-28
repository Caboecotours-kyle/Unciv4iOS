package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.UncivGame
import com.unciv.models.tilesets.TileSetCache
import com.unciv.view.CivView
import com.unciv.view.ForeignMapUnitView
import com.unciv.ui.components.NonTransformGroup
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter

class UnitSpriteSlot {
    val spriteGroup = NonTransformGroup()
    var currentImageLocation = ""
    var unitId = -1
    /** Portrait zoom damping the sprite was last sized with (see TileGroup.portraitDamping) */
    var damping = 1f
}

class TileLayerUnitSprite(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    // Slots are only filled if units exist, and images for those units exist
    private var civilianSlot: UnitSpriteSlot? = null
    private var militarySlot: UnitSpriteSlot? = null


    fun getSpriteSlot(unitView: ForeignMapUnitView) = if (unitView.isCivilian()) civilianSlot else militarySlot

    /** The slot still drawing [unitId], even after the unit itself has left this tile */
    fun getSpriteSlot(unitId: Int) = civilianSlot?.takeIf { it.unitId == unitId } ?: militarySlot?.takeIf { it.unitId == unitId }

    private fun updateSlot(currentSlot: UnitSpriteSlot?, unitView: ForeignMapUnitView?, isShown: Boolean): UnitSpriteSlot? {

        var location = ""
        var nationName = ""

        if (unitView != null && isShown && UncivGame.Current.settings.showPixelUnits) {
            location = strings.getMapImageLocation(strings.getUnitImageLocation(unitView))
            nationName = "${unitView.civName}-"
        }

        if (currentSlot == null && location == "") return null // No-op - had none, has none
        if (currentSlot?.currentImageLocation == "$nationName$location" && currentSlot.unitId == unitView?.id)
            return currentSlot // No-op - same unit and art

        if (location == "" || !ImageGetter.imageExists(location)){
            currentSlot?.spriteGroup?.let { removeOwnedActor(it) }
            return null
        }

        val slot = currentSlot ?: UnitSpriteSlot().apply {
            // Position the container at the tile origin so children use tile-local coords.
            // This keeps spriteGroup visible to viewport culling and preserves the
            // tile-local child positions that WorldMapHolder's animation code relies on.
            spriteGroup.setPosition(tileX, tileY)
            spriteGroup.setSize(size, size)
            addOwnedActor(spriteGroup)
        }
        slot.currentImageLocation = "$nationName$location"
        slot.unitId = unitView!!.id
        slot.spriteGroup.clear()

        val civView = unitView.civ()
        val inner = civView.getInnerColor()
        val outer = civView.getOuterColor()
        val teamColor = if (TileSetCache.getCurrent().config.vividUnitTeamColor && saturation(outer) > saturation(inner)) outer else inner
        val pixelUnitImages = ImageGetter.getLayeredImageColored(
            location,
            null,
            teamColor,
            outer
        )
        for (pixelUnitImage in pixelUnitImages) {
            slot.spriteGroup.addActor(pixelUnitImage)
            // tileLocal=true: spriteGroup is already at (tileX,tileY) so children only need
            // the hex-local offset (hexagonImagePosition), not the full absolute position.
            pixelUnitImage.setHexagonSize(tileLocal = true)
        }
        return slot
    }

    fun dim() {
        forEachOwnedActor { it.color.a = 0.5f }
    }

    override fun doUpdate(viewingCiv: CivView?) {

        val isPixelUnitsEnabled = UncivGame.Current.settings.showPixelUnits
        val isViewable = isViewable(viewingCiv)

        val isCivilianSlotShown = isPixelUnitsEnabled && isViewable
        val isMilitarySlotShown = isPixelUnitsEnabled && isViewable

        civilianSlot = updateSlot(civilianSlot, tileGroup.tileView.civilianUnit, isShown = isCivilianSlotShown)
        militarySlot = updateSlot(militarySlot, tileGroup.tileView.militaryUnit, isShown = isMilitarySlotShown)
        spreadSharedTile()
        showSleep(civilianSlot, tileGroup.tileView.civilianUnit)
        showSleep(militarySlot, tileGroup.tileView.militaryUnit)
    }

    /** Portrait: the player's sleeping units rest at .8 alpha */
    private fun showSleep(slot: UnitSpriteSlot?, unitView: ForeignMapUnitView?) {
        val group = slot?.spriteGroup ?: return
        group.color.a = if (strings.projection.tilted && unitView?.tryGetMapUnitView()?.isSleeping() == true) .8f else 1f
    }

    private fun saturation(c: Color): Float {
        val max = maxOf(c.r, c.g, c.b)
        return if (max == 0f) 0f else (max - minOf(c.r, c.g, c.b)) / max
    }

    /** Tall unit sprites hide each other on a shared tile, so a military and a civilian unit stand side by side. */
    private fun spreadSharedTile() {
        if (strings.projection.tilted) return updateDamping()
        if (!TileSetCache.getCurrent().config.unitFlagsAboveSprites) return
        val shared = civilianSlot != null && militarySlot != null
        val baseX = tileGroup.hexagonImagePosition.first
        militarySlot?.spriteGroup?.children?.forEach { it.x = baseX - if (shared) size * 0.2f else 0f }
        civilianSlot?.spriteGroup?.children?.forEach { it.x = baseX + if (shared) size * 0.2f else 0f }
    }

    /** Portrait: sprites grow slower than the hex when zoomed in, around their ground anchor (their feet stand on the
     *  ground centre), so poses and motion that scale or rotate around the image origin stay aligned. */
    fun updateDamping() {
        if (!strings.projection.tilted) return
        val spread = if (TileSetCache.getCurrent().config.unitFlagsAboveSprites && civilianSlot != null && militarySlot != null)
            size * 0.2f else 0f
        placeDamped(militarySlot, -spread)
        placeDamped(civilianSlot, spread)
    }

    private fun placeDamped(slot: UnitSpriteSlot?, spread: Float) {
        val children = slot?.spriteGroup?.children ?: return
        slot.damping = tileGroup.portraitDamping
        val damping = slot.damping
        val hexWidth = tileGroup.hexagonImageWidth
        for (i in 0 until children.size) {
            val image = children[i] as? Image ?: continue
            image.setSize(hexWidth * damping, image.prefHeight * hexWidth / image.prefWidth * damping)
            image.setOrigin(tileGroup.hexagonImageOriginX * damping, tileGroup.hexagonImageOriginY * damping)
            image.x = tileGroup.groundCenterX + spread * damping - image.originX
            image.y = tileGroup.groundCenterY - image.originY
        }
    }

    override fun determineVisibility() {
        isVisible = !tileGroup.strategicView && (civilianSlot != null || militarySlot != null)
    }

    fun reset() {
        civilianSlot?.spriteGroup?.let { removeOwnedActor(it) }
        militarySlot?.spriteGroup?.let { removeOwnedActor(it) }
        civilianSlot = null
        militarySlot = null
    }
}
