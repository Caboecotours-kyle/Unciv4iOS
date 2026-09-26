package com.unciv.ui.components.tilegroups.citybutton

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.toGroup
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.BorderedTable
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.padTopDescent
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.DebugUtils
import com.unciv.view.CityView

/**
 *  This is the main "button" inside [CityButton], the one with a rounded edge look,
 *  excluding the air units / defence badges above and influence / status indicators below.
 *
 *  It's the only touchable component of `CityButton`.
 *
 *  Components (* only if you're allowed to see - you're owner, spectator, debug flag):
 *  - population number
 *  - pop growth bar (vertical)*
 *  - capital indicator icon
 *  - name
 *  - religion icon (unless [forPopup] is `true`)
 *  - construction turns to completion* ('∞' for perpetual constructions, "-" for idle)
 *  - construction progress bar* (showing 0% for perpetual constructions)
 *  - construction icon* (perpetual ones show empty space instead)
 *  - nation or city-state icon (unless yours)
 */
internal class CityTable(
    city: CityView,
    forPopup: Boolean = false,
    private val compact: Boolean = false
) : BorderedTable(
    path = "WorldScreen/CityButton/IconTable",
    defaultBgShape = BaseScreen.skinStrings.roundedEdgeRectangleMidShape,
    defaultBgBorder = BaseScreen.skinStrings.roundedEdgeRectangleMidBorderShape
) {
    private val compactShadow = Color(0f, 0f, 0f, .25f)

    init {
        isTransform = false
        touchable = Touchable.enabled
        pad(0f, 4f, 0f, 4f) // outer pad left and right

        if (compact) buildCompact(city) else {
            val selectedCiv = GUI.getSelectedPlayer()
            val viewingCiv = city.getViewingCiv()
            when {
                city.belongsTo(selectedCiv) -> {
                    borderOnTop = true
                    borderSize = 4f
                    bgBorderColor = Color.valueOf("#E9E9AC")
                }
                city.isAtWarWith(selectedCiv) -> {
                    borderSize = 4f
                    bgBorderColor = Color.valueOf("#E63200")
                }
                else -> {
                    borderSize = 2f
                    bgBorderColor = ImageGetter.CHARCOAL
                }
            }
            bgColor = city.getNationOuterColor().cpy().apply { a = 0.9f }

            val isShowDetailedInfo = DebugUtils.VISIBLE_MAP
                    || city.belongsTo(selectedCiv)
                    || viewingCiv.isSpectator()

            addCityPopNumber(city)

            if (isShowDetailedInfo)
                addCityGrowthBar(city)

            addCityText(city, forPopup)

            if (isShowDetailedInfo)
                addCityConstruction(city)

            if (!city.belongsTo(viewingCiv))
                addCivIcon(city)
        }
    }

    private fun buildCompact(city: CityView) {
        borderSize = 0f
        bgColor = city.getNationOuterColor().cpy()
        val foreground = if (bgColor.r * .299f + bgColor.g * .587f + bgColor.b * .114f > .68f)
            Color.valueOf("102338") else Color.WHITE
        val population = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(bgColor.cpy().darken(.4f))
            add(city.getPopulationCount().tr().toLabel(Color.WHITE, 12))
        }
        pad(2f, 2f, 2f, 6f)
        add(population).size(18f).padRight(4f)
        if (city.isCapital()) add(ImageGetter.getImage("OtherIcons/Capital")).size(12f).padRight(4f)
        add(city.name.toLabel(foreground, 13, hideIcons = true)).padRight(5f)
        val canSeeDetails = city.belongsTo(city.getViewingCiv()) || city.getViewingCiv().isSpectator() || DebugUtils.VISIBLE_MAP
        if (canSeeDetails && city.getBuiltBuildings().any { it.isWonder })
            add(ImageGetter.getImage("OtherIcons/Wonders")).size(16f).padRight(4f)
        if (canSeeDetails && city.constructions.currentConstructionName().isNotEmpty()) {
            val construction = city.constructions.getCurrentConstruction()
            val turns = if (construction is PerpetualConstruction) Fonts.infinity.toString()
                else city.constructions.turnsToConstruction(construction.name).toString()
            val build = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("WorldScreen/Portrait/CityTurns",
                    BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, bgColor.cpy().darken(.25f))
                add(turns.toLabel(Color.WHITE, 11)).pad(0f, 5f, 0f, 5f)
            }
            add(build).height(16f)
        }
    }

    override fun drawBackground(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float, x: Float, y: Float) {
        if (!compact) return super.drawBackground(batch, parentAlpha, x, y)
        val circle = ImageGetter.getCircleDrawable()
        val fill = ImageGetter.getDrawable(ImageGetter.whiteDotLocation)
        fun pill(tint: Color, baseline: Float) {
            batch.setColor(tint.r * color.r, tint.g * color.g, tint.b * color.b, tint.a * color.a * parentAlpha)
            circle.draw(batch, x, baseline, height, height)
            fill.draw(batch, x + height / 2f, baseline, (width - height).coerceAtLeast(0f), height)
            circle.draw(batch, x + width - height, baseline, height, height)
        }
        pill(compactShadow, y - 2f)
        pill(bgColor, y)
    }

    override fun hit(x: Float, y: Float, touchable: Boolean): com.badlogic.gdx.scenes.scene2d.Actor? {
        if (compact && isVisible && (!touchable || this.touchable == Touchable.enabled)
            && x >= 0f && x < width && kotlin.math.abs(y - height / 2f) <= 24f) return this
        return super.hit(x, y, touchable)
    }

    private fun addCityPopNumber(city: CityView) {
        val textColor = city.getCivInnerColor()
        val popLabel = city.getPopulationCount().tr()
            .toLabel(fontColor = textColor, fontSize = 18, alignment = Align.center)
        add(popLabel).minWidth(26f)
    }

    private fun addCityGrowthBar(city: CityView) {
        val table = Table()
        fun calcGrowthPercentage(food: Int) = (food.toFloat() / city.getFoodToNextPopulation()).coerceIn(0f, 1f)
        val isGrowing = city.isGrowing()
        val isStarving = city.isStarving()

        val growthPercentage = calcGrowthPercentage(city.getFoodStored())
        val growthBar = ImageGetter.getProgressBarVertical(
            4f, 30f,
            if (isStarving) 1.0f else growthPercentage,
            if (isStarving) Color.RED else CityButton.ColorGrowth,
            ImageGetter.CHARCOAL, 1f
        )
        growthBar.color.a = 0.8f
        if (isGrowing) {
            val nextTurnPercentage = calcGrowthPercentage(city.foodForNextTurn() + city.getFoodStored())
            growthBar.setSemiProgress(CityButton.ColorGrowth.cpy().darken(0.4f), nextTurnPercentage, 1f)
        }

        val turnLabelText = when {
            isGrowing -> {
                val turnsToGrowth = city.getNumTurnsToNewPopulation()
                if (turnsToGrowth != null && turnsToGrowth < 100) turnsToGrowth.tr() else Fonts.infinity.toString()
            }
            isStarving -> {
                val turnsToStarvation = city.getNumTurnsToStarvation()
                if (turnsToStarvation != null && turnsToStarvation < 100) turnsToStarvation.tr() else Fonts.infinity.toString()
            }
            else -> "-"
        }
        val textColor = city.getCivInnerColor()
        val turnLabel = turnLabelText.toLabel(fontColor = textColor, fontSize = 13)

        table.add(growthBar).padRight(2f)
        table.add(turnLabel).expandY().bottom()
        add(table).minWidth(6f).padLeft(2f)
    }

    private fun addCityText(city: CityView, forPopup: Boolean) {
        val textColor = city.getCivInnerColor()
        val table = Table().apply { isTransform = false }

        if (city.isCapital()) {
            val capitalIcon = when {
                city.isCityState() -> ImageGetter.getNationIcon("CityState")
                    .apply { color = textColor }
                else -> ImageGetter.getImage("OtherIcons/Capital")
            }
            table.add(capitalIcon).size(20f).padRight(5f)
        }

        val cityName = city.name.toLabel(fontColor = textColor, alignment = Align.center, hideIcons = true)
        table.add(cityName).growY().center().padTopDescent()

        if (!forPopup) {
            val cityReligion = city.getMajorityReligion()
            if (cityReligion != null) {
                val religionImage = ImageGetter.getReligionIcon(cityReligion.getIconName()).apply {
                    color = textColor }.toGroup(20f)
                table.add(religionImage).size(20f).padLeft(5f)
            }
        }

        table.pack()
        add(table)
            .minHeight(34f)
            .padLeft(10f)
            .padRight(10f)
            .expandY().center()
    }

    private fun addCityConstruction(city: CityView) {
        val textColor = city.getCivInnerColor()

        val cityConstructions = city.constructions
        val cityCurrentConstruction = cityConstructions.getCurrentConstruction()

        val progressTable = Table()

        // There's two different "idle" states: No entry in the queue and PerpetualConstruction.idle queued.
        // getCurrentConstruction does not distinuish these, only currentConstructionName does. And we want the icon to only show in the second case.
        var nextTurnPercentage = 0f
        var percentage = 0f
        val icon = if (cityConstructions.currentConstructionName().isEmpty()) null
            else ImageGetter.getConstructionPortrait(cityCurrentConstruction.name, 24f)
        val turns = when (cityCurrentConstruction) {
            PerpetualConstruction.Idle -> {
                "-"
            }
            is PerpetualConstruction -> {
                Fonts.infinity.toString()
            }
            else -> {
                cityCurrentConstruction as INonPerpetualConstruction
                val turnsToConstruction = cityConstructions.turnsToConstruction(cityCurrentConstruction.name)
                val workDone = cityConstructions.getWorkDone(cityCurrentConstruction.name).toFloat()
                val cost = city.getConstructionProductionCost(cityCurrentConstruction)
                fun getPercentage(done: Float) = (done / cost).coerceIn(0f, 1f)
                nextTurnPercentage = getPercentage(workDone + city.getCurrentCityStats().production)
                percentage = getPercentage(workDone)
                if (turnsToConstruction < 100) turnsToConstruction.tr() else "-"
            }
        }

        val productionBar = ImageGetter.getProgressBarVertical(4f, 30f, percentage,
            CityButton.ColorConstruction, ImageGetter.CHARCOAL, 1f)
        productionBar.setSemiProgress(CityButton.ColorConstruction.cpy().darken(0.4f), nextTurnPercentage, 1f)
        productionBar.color.a = 0.8f

        progressTable.add(turns.toLabel(textColor, 13)).expandY().bottom()
        progressTable.add(productionBar).padLeft(2f)

        add(progressTable).minWidth(6f).padRight(2f)
        add(icon).minWidth(26f)
    }

    private fun addCivIcon(city: CityView) {
        val icon = when {
            city.isMajorCiv() -> ImageGetter.getNationIcon(city.getNationName())
            else -> ImageGetter.getImage("CityStateIcons/" + city.getCityStateTypeName())
        }
        icon.color = city.getCivInnerColor()

        add(icon.toGroup(20f)).minWidth(26f)
    }
}
