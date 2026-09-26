package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.IRulesetObject
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction.StatConversion
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.getTurnsToConstructionString
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.closeAllPopups
import com.unciv.ui.screens.basescreen.BaseScreen

/** This is the bottom-right table in the city screen that shows the currently selected construction */
class ConstructionInfoTable(val cityScreen: CityScreen) : Table() {
    private val selectedConstructionTable = Table()
    private val buyButtonFactory = BuyButtonFactory(cityScreen)
    private val cityView get() = cityScreen.cityView

    init {
        selectedConstructionTable.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/ConstructionInfoTable/SelectedConstructionTable",
            tintColor = if (cityScreen.isPortrait()) Color.valueOf("20394f")
                else BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        if (cityScreen.isPortrait()) add(selectedConstructionTable).width(369f)
        else {
            add(selectedConstructionTable).pad(2f).fill()
            background = BaseScreen.skinStrings.getUiBackground(
                "CityScreen/ConstructionInfoTable/Background",
                tintColor = Color.WHITE
            )
        }
    }

    fun update(selectedConstruction: IConstruction?) {
        selectedConstructionTable.clear()  // clears content and listeners

        if (selectedConstruction == null) {
            isVisible = false
            return
        }
        isVisible = true

        updateSelectedConstructionTable(selectedConstruction)

        pack()
    }

    private fun updateSelectedConstructionTable(construction: IConstruction) {
        if (cityScreen.isPortrait()) {
            updatePortrait(construction)
            return
        }
        val cityConstructions = cityView.constructions

        //val selectedConstructionTable = Table()
        selectedConstructionTable.run {
            pad(10f)

            add(ImageGetter.getConstructionPortrait(construction.name, 50f).apply {
                val link = (construction as? IRulesetObject)?.makeLink() ?: return@apply
                if (link.isEmpty()) return@apply
                touchable = Touchable.enabled
                this.onClick {
                    cityScreen.openCivilopedia(link)
                }
            }).pad(5f)

            var buildingText = construction.name.tr(hideIcons = true)
            val specialConstruction = PerpetualConstruction.perpetualConstructionsMap[construction.name]

            buildingText += specialConstruction?.let { cityView.getProductionTooltip(it) }
                    ?: cityConstructions.getTurnsToConstructionString(construction)

            add(Label(buildingText, BaseScreen.skin)).expandX().row()  // already translated

            val description = when (construction) {
                is BaseUnit -> cityView.getUnitDescription(construction)
                is Building -> cityView.getBuildingDescription(construction)
                is StatConversion -> construction.description.replace("[rate]", "[${cityView.getConversionRate(construction)}]").tr()
                is PerpetualConstruction -> construction.description.tr()
                else -> ""  // Should never happen
            }

            val descriptionLabel = Label(description, BaseScreen.skin)  // already translated
            descriptionLabel.wrap = true
            // portrait floats this panel alone above the tab bar, so it can take most of the phone's width
            val descriptionWidth = if (cityScreen.isPortrait()) cityScreen.stage.width * 0.7f
                else cityScreen.stage.width / if (cityScreen.isCrampedPortrait()) 3 else 4
            add(descriptionLabel).colspan(2).width(descriptionWidth)

            if (cityConstructions.isBuilt(construction.name)) {
                showSellButton(construction)
            } else if (buyButtonFactory.hasBuyButtons(construction)) {
                row()
                for (button in buyButtonFactory.getBuyButtons(construction)) {
                    selectedConstructionTable.add(button).padTop(5f).colspan(2).center().row()
                }
            }
            if (construction is BaseUnit) {
                val baseUnit = construction.name
                val buildUnitWithPromotions = cityView.getUnitShouldUseSavedPromotion(baseUnit)

                if (buildUnitWithPromotions != null) {
                    row()
                    add("Use default promotions".toCheckBox(buildUnitWithPromotions) {
                        cityView.trySetUnitShouldUseSavedPromotion(baseUnit, it)
                    }).colspan(2).center()
                }
            }
        }
    }

    private fun updatePortrait(construction: IConstruction) {
        val cityConstructions = cityView.constructions
        selectedConstructionTable.pad(12f)

        val header = Table()
        header.add(ImageGetter.getConstructionPortrait(construction.name, 50f).apply {
            val link = (construction as? IRulesetObject)?.makeLink() ?: return@apply
            if (link.isNotEmpty()) {
                touchable = Touchable.enabled
                onClick { cityScreen.openCivilopedia(link) }
            }
        }).size(50f).padRight(12f)
        val specialConstruction = PerpetualConstruction.perpetualConstructionsMap[construction.name]
        val title = construction.name.tr(hideIcons = true) +
            (specialConstruction?.let { cityView.getProductionTooltip(it) }
                ?: cityConstructions.getTurnsToConstructionString(construction))
        header.add(Label(title, BaseScreen.skin).apply { wrap = true }).width(271f).left()
        selectedConstructionTable.add(header).width(345f).left().row()

        val description = when (construction) {
            is BaseUnit -> cityView.getUnitDescription(construction)
            is Building -> cityView.getBuildingDescription(construction)
            is StatConversion -> construction.description.replace("[rate]", "[${cityView.getConversionRate(construction)}]").tr()
            is PerpetualConstruction -> construction.description.tr()
            else -> ""
        }
        selectedConstructionTable.add(Label(description, BaseScreen.skin).apply { wrap = true })
            .width(345f).left().padTop(12f).row()

        if (cityConstructions.isBuilt(construction.name)) showSellButton(construction)
        else for (button in buyButtonFactory.getBuyButtons(construction)) {
            selectedConstructionTable.add(stylePortraitButton(button, true))
                .width(345f).height(48f).padTop(8f).row()
        }
        if (construction is BaseUnit) {
            val baseUnit = construction.name
            val usePromotions = cityView.getUnitShouldUseSavedPromotion(baseUnit)
            if (usePromotions != null) {
                selectedConstructionTable.add("Use default promotions".toCheckBox(usePromotions) {
                    cityView.trySetUnitShouldUseSavedPromotion(baseUnit, it)
                }).width(345f).height(48f).left().padTop(8f).row()
            }
        }
    }

    private fun stylePortraitButton(button: TextButton, primary: Boolean): TextButton {
        val normal = if (primary) Color.valueOf("ffc93c") else Color(1f, 1f, 1f, .08f)
        val disabled = Color(1f, 1f, 1f, .08f)
        val shape = BaseScreen.skinStrings.roundedEdgeRectangleShape
        button.style = TextButton.TextButtonStyle(button.style).apply {
            up = BaseScreen.skinStrings.getUiBackground("", shape, normal)
            down = BaseScreen.skinStrings.getUiBackground("", shape, normal)
            this.disabled = BaseScreen.skinStrings.getUiBackground("", shape, disabled)
            fontColor = if (primary) Color.valueOf("3a2a00") else Color.WHITE
            disabledFontColor = Color.valueOf("8eacc2")
        }
        return button
    }

    // Show sell button if construction is a currently sellable building
    private fun showSellButton(
        construction: IConstruction
    ) {
        if (construction is Building && construction.isSellable()) {
            selectedConstructionTable.run {
                val sellAmount = cityView.getGoldForSellingBuilding(construction.name)
                val sellText = "{Sell} $sellAmount " + Fonts.gold
                val sellBuildingButton = sellText.toTextButton()
                row()
                if (cityScreen.isPortrait())
                    add(stylePortraitButton(sellBuildingButton, false)).width(345f).height(48f).padTop(8f).row()
                else add(sellBuildingButton).padTop(5f).colspan(2).center()

                val isFree = cityScreen.hasFreeBuilding(construction)
                val enableSell = !isFree &&
                    !cityView.isPuppet() &&
                    cityScreen.canChangeState &&
                    (!cityView.hasSoldBuildingThisTurn() || cityView.isGodModeEnabled())
                sellBuildingButton.isEnabled = enableSell
                if (enableSell)
                    sellBuildingButton.onClick(UncivSound.Coin) {
                        sellBuildingButton.disable()
                        sellBuildingClicked(construction, sellText)
                    }

                if (cityView.hasSoldBuildingThisTurn() && !cityView.isGodModeEnabled()
                        || cityView.isPuppet()
                        || !cityScreen.canChangeState)
                    sellBuildingButton.disable()
            }
        }
    }

    private fun sellBuildingClicked(construction: Building, sellText: String) {
        cityScreen.closeAllPopups()

        ConfirmPopup(
            cityScreen,
            "Are you sure you want to sell this [${construction.name}]?",
            sellText,
            restoreDefault = {
                cityScreen.updateAsync()
            }
        ) {
            sellBuildingConfirmed(construction)
        }.open()
    }

    private fun sellBuildingConfirmed(construction: Building) {
        cityView.trySellBuilding(construction)
        cityScreen.clearSelection()
        cityScreen.updateAsync()
    }

}
