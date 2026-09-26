package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.ui.objectdescriptions.TileDescription
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.ui.audio.SoundPlayer
import com.unciv.ui.components.extensions.darken
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.civilopediascreen.FormattedLine.LinkType
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.view.CityView
import com.unciv.view.TileView
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

class CityScreenTileTable(private val cityScreen: CityScreen) : Table() {
    private val innerTable = Table()
    val cityView: CityView = cityScreen.cityView

    init {
        innerTable.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CityScreenTileTable/InnerTable",
            tintColor = if (cityScreen.isPortrait()) Color.valueOf("20394f")
                else BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        if (cityScreen.isPortrait()) add(innerTable).width(369f)
        else {
            add(innerTable).pad(2f).fill()
            background = BaseScreen.skinStrings.getUiBackground("CityScreen/CityScreenTileTable/Background", tintColor = Color.WHITE)
        }
    }

    fun update(tileView: TileView?) {
        innerTable.clear()
        if (tileView == null) {
            isVisible = false
            return
        }
        isVisible = true
        innerTable.clearChildren()

        val stats = tileView.getTileStats(cityView.viewingCiv(), cityView)
        innerTable.pad(if (cityScreen.isPortrait()) 12f else 5f)

        val description = TileDescription.toMarkup(
            tileView,
            cityView.viewingCiv(),
            hideUnits = cityScreen.isSpying,
            spyCity = if (cityScreen.isSpying) cityView else null
        )
        if (cityScreen.isPortrait()) {
            for (line in description) {
                val row = Table()
                row.add(MarkupRenderer.render(listOf(line), labelWidth = 333f, iconDisplay = IconDisplay.None))
                    .width(338f).left()
                if (line.linkType == LinkType.Internal) {
                    row.touchable = Touchable.enabled
                    row.onClick { cityScreen.openCivilopedia(line.link) }
                }
                innerTable.add(row).width(345f)
                    .minHeight(if (line.linkType == LinkType.Internal) 48f else 0f).fillY().left().row()
            }
            innerTable.add(getTileStatsTable(stats)).width(345f).left().padTop(8f).row()
        } else {
            innerTable.add(MarkupRenderer.render(description, iconDisplay = IconDisplay.None) {
                cityScreen.openCivilopedia(it)
            }).row()
            innerTable.add(getTileStatsTable(stats)).row()
        }

        if (cityView.canBuyTile(tileView)) {
            val goldCostOfTile = cityView.getGoldCostOfTile(tileView)
            val buyTileButton = "Buy for [$goldCostOfTile] gold".toTextButton()
            buyTileButton.onActivation(binding = KeyboardBinding.BuyTile) {
                buyTileButton.disable()
                cityScreen.askToBuyTile(tileView)
            }
            buyTileButton.addContextMenu { TileBuyMenu(buyTileButton) }
            buyTileButton.isEnabled = cityScreen.canChangeState && cityView.viewingCiv().hasStatToBuy(Stat.Gold, goldCostOfTile)
            addAction(buyTileButton, true)
        }

        val owningCity = tileView.owningCity()
        if (owningCity != null) addInfo("Owned by [${owningCity.name}]")

        val workingCity = tileView.getWorkingCity()
        if (workingCity != null) addInfo("Worked by [${workingCity.name}]")

        if (cityView.isWorked(tileView)) {
            if (tileView.isLocked()) {
                val unlockButton = "Unlock".toTextButton()
                unlockButton.onClick {
                    cityView.tryUnlockTile(tileView)
                    update(tileView)
                    cityScreen.updateAsync()
                }
                if (!cityScreen.canChangeState) unlockButton.disable()
                addAction(unlockButton)
            } else {
                val lockButton = "Lock".toTextButton()
                lockButton.onClick {
                    cityView.tryLockTile(tileView)
                    update(tileView)
                    cityScreen.updateAsync()
                }
                if (!cityScreen.canChangeState) lockButton.disable()
                addAction(lockButton)
            }
        }

        if (tileView.isCityCenter()) {
            val otherCityView = tileView.owningCity()?.tryGetCityView()
            if (otherCityView != null && otherCityView != cityView)
                addAction("Move to city".toTextButton().apply { onClick {
                    cityScreen.game.replaceCurrentScreen { CityScreen(otherCityView) }
                } })
        }

        innerTable.pack()
        pack()
    }

    private fun addInfo(text: String) {
        val label = text.toLabel().apply { wrap = cityScreen.isPortrait() }
        if (cityScreen.isPortrait()) innerTable.add(label).width(345f).left().padTop(8f).row()
        else innerTable.add(label).row()
    }

    private fun addAction(button: TextButton, primary: Boolean = false) {
        if (cityScreen.isPortrait()) {
            stylePortraitButton(button, primary)
            innerTable.add(button).width(345f).height(48f).padTop(8f).row()
        } else innerTable.add(button).padTop(5f).row()
    }

    private fun stylePortraitButton(button: TextButton, primary: Boolean = false) {
        val shape = BaseScreen.skinStrings.roundedEdgeRectangleShape
        val normal = if (primary) Color.valueOf("ffc93c") else Color(1f, 1f, 1f, .08f)
        val disabled = Color(1f, 1f, 1f, .08f)
        button.style = TextButton.TextButtonStyle(button.style).apply {
            up = BaseScreen.skinStrings.getUiBackground("", shape, normal)
            down = BaseScreen.skinStrings.getUiBackground("", shape, normal)
            this.disabled = BaseScreen.skinStrings.getUiBackground("", shape, disabled)
            fontColor = if (primary) Color.valueOf("3a2a00") else Color.WHITE
            disabledFontColor = Color.valueOf("8eacc2")
        }
    }

    private fun getTileStatsTable(stats: Stats): Table {
        val statsTable = Table()
        statsTable.defaults().pad(2f)
        for ((index, entry) in stats.withIndex()) {
            val (key, value) = entry
            statsTable.add(ImageGetter.getStatIcon(key.name)).size(20f)
            statsTable.add(value.roundToInt().toLabel()).padRight(5f)
            if (cityScreen.isPortrait() && index % 3 == 2) statsTable.row()
        }
        return statsTable
    }

    private inner class TileBuyMenu(buyTileButton: TextButton) : AnimatedMenuPopup(stage, buyTileButton) {
        override fun createContentTable(): Table? {
            val maxRing = cityView.getWorkRange()
            val counts = IntArray(maxRing + 1) { countBuyableInRing(it) }
            if (counts.sum() < 2) return null
            return super.createContentTable()!!.apply {
                val balance = "Currently you have [${cityView.viewingCiv().gold}] [Gold].".toLabel(alignment = Align.center)
                if (cityScreen.isPortrait()) {
                    balance.wrap = true
                    add(balance).width(333f).minHeight(48f).row()
                } else add(balance).growX().row()
                for (ring in 0..maxRing) {
                    val count = counts[ring]
                    if (count == 0 || ring > 0 && count == counts[ring - 1]) continue
                    val cost = getRingCost(ring)
                    val text = "Buy [$count] tiles in ring [$ring] for [$cost][${Stat.Gold.character}]"
                    val button = getButton(text, KeyboardBinding.None) { buyRing(ring) }
                    button.isDisabled = cost > cityView.viewingCiv().gold
                    if (cityScreen.isPortrait()) {
                        button.label.wrap = true
                        add(button).width(333f).height(48f).row()
                    } else add(button).row()
                }
            }
        }

        @Readonly private fun getRing(ring: Int) = cityView.centerTile().getVisibleTilesInDistance(ring).filter { it.owningCity() == null }
        @Readonly private fun countBuyableInRing(ring: Int) = getRing(ring).count()
        @Readonly private fun getRingCost(ring: Int) = getRing(ring).withIndex()
            .sumOf { cityView.getGoldCostOfTile(it.value, it.index) }
        private fun buyRing(ring: Int) {
            for (tileView in getRing(ring)) {
                if (!cityView.tryBuyTile(tileView))
                    break
            }
            SoundPlayer.play(Stat.Gold.purchaseSound)
            cityScreen.game.replaceCurrentScreen { CityScreen(cityView) } // update doesn't redo the tiles
        }
    }
}
