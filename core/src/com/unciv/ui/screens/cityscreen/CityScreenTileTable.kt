package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
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
import com.unciv.ui.components.tilegroups.CityTileState
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.popups.AnimatedMenuPopup.Companion.addContextMenu
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.ui.screens.worldscreen.PortraitTileCard
import com.unciv.view.CityView
import com.unciv.view.TileView
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt
import kotlin.math.max

/** @param tray The portrait city sheet's kit: set, the tile shows as a pushed tray page and its actions go to [trayActions] */
class CityScreenTileTable(private val cityScreen: CityScreen, private val tray: TraySheet? = null) : Table() {
    private val innerTable = Table()
    val cityView: CityView = cityScreen.cityView
    /** The tray page's actions, for the sheet's footer; filled by [update] when [tray] is set */
    val trayActions = ArrayList<Actor>()
    /** 48 real screen points in stage units, for the buy menu that sits on the stage itself */
    private val stageTouch = max(48f, PortraitTileCard.touchPoints(cityScreen))
    /** The same in the portrait sheet's 393pt units, which the city sheet scales to the safe width */
    private val touch = max(48f, stageTouch * 393f / cityScreen.safeAreaBoundsInWorld().width)

    init {
        innerTable.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CityScreenTileTable/InnerTable",
            tintColor = if (cityScreen.isPortrait()) Color.valueOf("20394f")
                else BaseScreen.skinStrings.skinConfig.baseColor.darken(0.5f)
        )
        if (tray != null) {
            innerTable.background = null
            add(innerTable).growX()
        } else if (cityScreen.isPortrait()) add(innerTable).width(369f)
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
        if (tray != null) return updateTray(tileView, tray)

        val stats = tileView.getTileStats(cityView.viewingCiv(), cityView)
        innerTable.pad(if (cityScreen.isPortrait()) 12f else 5f)

        val description = TileDescription.toMarkup(
            tileView,
            cityView.viewingCiv(),
            hideUnits = cityScreen.isSpying,
            spyCity = if (cityScreen.isSpying) cityView else null
        )
        if (cityScreen.isPortrait()) {
            // The map's tile sheet card, from the same markup
            val card = PortraitTileCard(cityScreen, tileView, cityView.viewingCiv(), cityScreen.portraitStatIcons, cityView,
                hideUnits = cityScreen.isSpying, spyCity = if (cityScreen.isSpying) cityView else null, touch = touch)
            innerTable.add(card.header()).width(345f).left().row()
            card.yields(345f)?.let { innerTable.add(it).width(345f).left().padTop(14f).padBottom(6f).row() }
            innerTable.add(card.rows()).width(345f).padTop(4f).row()
            addWorkAction(tileView)
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

    /** The city sheet's tile page: yield chips, the tile card's rows with owner and worker, actions for the footer */
    private fun updateTray(tileView: TileView, tray: TraySheet) {
        trayActions.clear()
        val card = PortraitTileCard(cityScreen, tileView, cityView.viewingCiv(), cityScreen.portraitStatIcons, cityView,
            hideUnits = cityScreen.isSpying, spyCity = if (cityScreen.isSpying) cityView else null,
            p = tray.p, touch = tray.touch, tray = tray)
        card.yields(0f)?.let { innerTable.add(it).growX().padTop(tray.pt(4f)).row() }
        val info = ArrayList<Actor>()
        tileView.owningCity()?.let {
            info += tray.row("Owned by [${it.name}]", lead = tray.disc(ImageGetter.getImage("OtherIcons/Cities"), TraySheet.Tone.Fill))
        }
        tileView.getWorkingCity()?.let {
            info += tray.row("Worked by [${it.name}]", lead = tray.disc(ImageGetter.getImage("StatIcons/Specialist"), TraySheet.Tone.Fill))
        }
        innerTable.add(tray.section("On this tile")).growX().row()
        innerTable.add(card.rows(info, baseRow = true)).growX().row()

        // Same actions, gates and commands as the tile table below; the sheet lays them out in its footer
        if (cityScreen.tileStates()[tileView] == CityTileState.WORKABLE && cityScreen.canChangeState && !cityView.isPuppet()) {
            val assign = !tileView.providesYield()
            trayActions += trayButton(tray, if (assign) "Assign citizen" else "Unassign",
                if (assign) TraySheet.ButtonKind.Primary else TraySheet.ButtonKind.Secondary,
                !assign || cityView.getFreePopulation() > 0) { cityScreen.toggleTileWorked(tileView) }
        }
        if (cityView.isWorked(tileView)) {
            val locked = tileView.isLocked()
            trayActions += trayButton(tray, if (locked) "Unlock" else "Lock", TraySheet.ButtonKind.Secondary, cityScreen.canChangeState) {
                if (locked) cityView.tryUnlockTile(tileView) else cityView.tryLockTile(tileView)
                update(tileView)
                cityScreen.updateAsync()
            }
        }
        if (cityView.canBuyTile(tileView)) {
            val goldCostOfTile = cityView.getGoldCostOfTile(tileView)
            val enabled = cityScreen.canChangeState && cityView.viewingCiv().hasStatToBuy(Stat.Gold, goldCostOfTile)
            lateinit var buyTileButton: Table
            buyTileButton = trayButton(tray, "Buy for [$goldCostOfTile] gold", TraySheet.ButtonKind.Primary, enabled) {
                buyTileButton.touchable = Touchable.disabled
                cityScreen.askToBuyTile(tileView)
            }
            buyTileButton.name = "portrait-buy-tile-button"
            buyTileButton.addContextMenu { TileBuyMenu(buyTileButton) }
            trayActions += buyTileButton
        }
        if (tileView.isCityCenter()) {
            val otherCityView = tileView.owningCity()?.tryGetCityView()
            if (otherCityView != null && otherCityView != cityView)
                trayActions += trayButton(tray, "Move to city", TraySheet.ButtonKind.Secondary) {
                    cityScreen.game.replaceCurrentScreen { CityScreen(otherCityView) }
                }
        }
        pack()
    }

    /** The kit's button, greyed out and inert when not [enabled] */
    private fun trayButton(tray: TraySheet, text: String, kind: TraySheet.ButtonKind, enabled: Boolean = true, action: () -> Unit): Table =
        tray.button(text, kind) { action() }.apply {
            if (!enabled) {
                touchable = Touchable.disabled
                color.a = .45f
            }
        }

    /** The Tiles tab card's worker toggle: Work this tile for an unworked workable tile, Unassign for a worked one */
    private fun addWorkAction(tileView: TileView) {
        if (cityScreen.tileStates()[tileView] != CityTileState.WORKABLE || !cityScreen.canChangeState || cityView.isPuppet()) return
        val assign = !tileView.providesYield()
        val button = (if (assign) "Work this tile" else "Unassign").toTextButton()
        button.onClick { cityScreen.toggleTileWorked(tileView) }
        if (assign && cityView.getFreePopulation() <= 0) button.disable()
        stylePortraitButton(button, assign)
        innerTable.add(button).width(345f).height(max(if (assign) 56f else 48f, touch)).padTop(12f).row()
    }

    private fun addInfo(text: String) {
        val label = text.toLabel().apply { wrap = cityScreen.isPortrait() }
        if (cityScreen.isPortrait()) innerTable.add(label).width(345f).left().padTop(8f).row()
        else innerTable.add(label).row()
    }

    private fun addAction(button: TextButton, primary: Boolean = false) {
        if (cityScreen.isPortrait()) {
            stylePortraitButton(button, primary)
            innerTable.add(button).width(345f).height(touch).padTop(8f).row()
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

    private inner class TileBuyMenu(buyTileButton: Actor) : AnimatedMenuPopup(stage, buyTileButton) {
        override fun createContentTable(): Table? {
            val maxRing = cityView.getWorkRange()
            val counts = IntArray(maxRing + 1) { countBuyableInRing(it) }
            if (counts.sum() < 2) return null
            return super.createContentTable()!!.apply {
                val balance = "Currently you have [${cityView.viewingCiv().gold}] [Gold].".toLabel(alignment = Align.center)
                if (cityScreen.isPortrait()) {
                    balance.wrap = true
                    add(balance).width(333f).minHeight(stageTouch).row()
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
                        add(button).width(333f).height(stageTouch).row()
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
