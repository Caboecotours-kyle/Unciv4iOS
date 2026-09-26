package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.ForeignMapUnitView
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.widgets.UnitIconGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** The unit flag is the symbol that appears behind the map unit - circle regularly, shield when defending, etc */
class TileLayerUnitFlag(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    private var civilianUnitIcon: UnitIconGroup? = null
    private var militaryUnitIcon: UnitIconGroup? = null
    private val portraitWrappers = HashMap<UnitIconGroup, Group>()

    private fun clearSlots() {
        civilianUnitIcon?.let { removeOwnedActor(portraitWrappers.remove(it) ?: it) }
        militaryUnitIcon?.let { removeOwnedActor(portraitWrappers.remove(it) ?: it) }
    }

    private val flagsAboveSprites
        get() = TileSetCache.getCurrent().config.unitFlagsAboveSprites && UncivGame.Current.settings.showPixelUnits

    private fun setIconPosition(slot: Int, icon: UnitIconGroup) {
        if (strings.projection.tilted) {
            val shared = tileGroup.tileView.militaryUnit != null && tileGroup.tileView.civilianUnit != null
            val dx = if (shared) { if (slot == 1) -15f else 15f } else if (tileGroup.strategicView) 0f else 20f
            val wrapper = portraitWrappers.getValue(icon)
            wrapper.x = tileX + tileGroup.groundCenterX + dx - icon.width * wrapper.scaleX / 2f
            wrapper.y = tileY + tileGroup.groundCenterY - icon.height * wrapper.scaleY / 2f - if (tileGroup.strategicView) 0f else 7f
            return
        }
        if (flagsAboveSprites) {
            // Side by side over the top of the hex so the unit sprite below stays visible (military left, civilian right)
            icon.x = tileX + (size - icon.width) / 2 + if (slot == 1) -12f else 12f
            icon.y = tileY + size * (0.78f + (1f - tileGroup.mapVerticalScale) * 0.5f)
            return
        }
        // Centre horizontally; offset vertically per slot (slot 0 = bottom, slot 1 = top)
        icon.x = tileX + (size - icon.width) / 2
        icon.y = tileY + (size - icon.height) / 2 + if (slot == 1) 20f else -20f
    }

    private fun newUnitIcon(slot: Int, unit: ForeignMapUnitView?, isViewable: Boolean, viewingCiv: CivView?): UnitIconGroup? {

        var newIcon: UnitIconGroup? = null

        if (unit != null && isViewable) {
            val rawUnit = unit.getUnit()
            newIcon = UnitIconGroup(unit, if (strings.projection.tilted) 14f else if (flagsAboveSprites) 22f else 30f)
            if (strings.projection.tilted) {
                val wrapper = Group().apply {
                    setSize(newIcon.width, newIcon.height)
                    addActor(newIcon)
                    setScale(if (tileGroup.strategicView) 22f / 14f * tileGroup.portraitPointScale else 1f)
                }
                portraitWrappers[newIcon] = wrapper
                addOwnedActor(wrapper)
            } else addOwnedActor(newIcon)
            setIconPosition(slot, newIcon)

            // Display air unit table for carriers/transports
            if (!rawUnit.getTile().isCityCenter() && rawUnit.getTile().airUnits.any { rawUnit.isTransportTypeOf(it) }) {
                val table = getAirUnitTable(rawUnit)
                newIcon.addActor(table)
                table.toBack()
                table.y = newIcon.height/2 - table.height/2
                table.x = newIcon.width - table.width*0.45f
            }

            // Fade out action indicator for own non-idle units
            if (rawUnit.civ === viewingCiv?.getCiv() && !rawUnit.isIdle() && UncivGame.Current.settings.unitIconOpacity == 1f)
                newIcon.actionGroup?.color?.a = 0.5f

            // Fade out flag for own out-of-moves units
            if (rawUnit.civ === viewingCiv?.getCiv() && !rawUnit.hasMovement())
                newIcon.color.a = 0.5f * UncivGame.Current.settings.unitIconOpacity

        }

        return newIcon
    }

    private fun getAirUnitTable(unit: MapUnit): Table {

        val iconColor = unit.civ.nation.getOuterColor()
        val bgColor = unit.civ.nation.getInnerColor()

        val airUnitTable = Table()
        airUnitTable.background = BaseScreen.skinStrings.getUiBackground(
            path="WorldScreen/AirUnitTable",
            "", bgColor
        )
        airUnitTable.pad(0f).defaults().pad(0f)
        airUnitTable.setSize(28f, 12f)

        val table = Table()

        val aircraftImage = ImageGetter.getImage("OtherIcons/Aircraft")
        aircraftImage.color = iconColor
        table.add(aircraftImage).size(8f)
        table.add(unit.getTile().airUnits.size.tr().toLabel(iconColor, 10, alignment = Align.center))

        airUnitTable.add(table).expand().center().right()

        return airUnitTable
    }

    fun updatePortraitScale() {
        if (!strings.projection.tilted) return
        for ((slot, icon) in listOf(0 to civilianUnitIcon, 1 to militaryUnitIcon)) {
            if (icon == null) continue
            portraitWrappers.getValue(icon).setScale(if (tileGroup.strategicView) 22f / 14f * tileGroup.portraitPointScale else 1f)
            setIconPosition(slot, icon)
        }
    }

    fun selectFlag(unitView: ForeignMapUnitView) {
        getIcon(unitView)?.selectUnit()
    }

    fun getIcon(unitView: ForeignMapUnitView) : UnitIconGroup? {
        if (civilianUnitIcon?.unitView == unitView)
            return civilianUnitIcon
        else if (militaryUnitIcon?.unitView == unitView)
            return militaryUnitIcon
        return null
    }

    private fun highlightRed() {
        civilianUnitIcon?.highlightRed()
        militaryUnitIcon?.highlightRed()
    }

    private fun fillSlots(viewingCiv: CivView?) {
        val isViewable = isViewable(viewingCiv)

        val isCivilianShown = isViewable
        val isMilitaryShown = isViewable

        civilianUnitIcon = newUnitIcon(0, tileGroup.tileView.civilianUnit, isCivilianShown, viewingCiv)
        militaryUnitIcon = newUnitIcon(1, tileGroup.tileView.militaryUnit, isMilitaryShown, viewingCiv)
    }

    override fun doUpdate(viewingCiv: CivView?) {
        clearSlots()
        fillSlots(viewingCiv)

        if (viewingCiv != null) {
            val shouldBeHighlighted = tileGroup.tileView.getVisibleUnits().any { it.civ().isAtWarWith(viewingCiv) }
                    && isViewable(viewingCiv)
            if (shouldBeHighlighted)
                highlightRed()
        }

    }

    fun reset() {
        clearSlots()
        civilianUnitIcon = null
        militaryUnitIcon = null
    }

    override fun determineVisibility() {
        isVisible = civilianUnitIcon != null || militaryUnitIcon != null
    }
}
