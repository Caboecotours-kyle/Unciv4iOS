package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.models.UnitActionType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.worldscreen.bottombar.BattleTable
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import com.unciv.ui.screens.worldscreen.status.NextTurnAction
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTable
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max

/** Portrait controls use the approved mock's 393-point canvas, independent of game UI scale. */
internal class WorldPortraitHud(
    private val world: WorldScreen,
    private val unitActions: UnitActionsTable,
    private val battle: BattleTable
) : Group() {
    private val navy = Color.valueOf("101f2fe6")
    private val ink = Color.valueOf("102338")
    private val white = Color.valueOf("eef5fb")
    private val yellow = Color.valueOf("ffc93c")
    private val scrimTexture by lazy {
        val pixels = Pixmap(1, 256, Pixmap.Format.RGBA8888)
        for (y in 0 until 256) {
            pixels.setColor(10f / 255f, 22f / 255f, 34f / 255f, y / 255f * .66f)
            pixels.drawPixel(0, y)
        }
        Texture(pixels).also { pixels.dispose() }
    }
    private val statTexture by lazy {
        Texture(Gdx.files.internal("ExtraImages/PortraitStats.png")).apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
    }
    private fun statIcon(name: String): Image {
        val index = listOf("Food", "Production", "Gold", "Science", "Culture", "Faith", "Happiness", "Strength", "Ranged", "Movement").indexOf(name)
        return Image(TextureRegion(statTexture, index * 64, 0, 64, 64))
    }
    private var zoomExpanded = false
    var tutorialTop = 0f
        private set

    init { touchable = Touchable.childrenOnly }

    private val panelTextures = mutableListOf<Texture>()
    private val panels = mutableMapOf<Int, NinePatchDrawable>()
    private fun panel(radius: Int = 18): NinePatchDrawable = panels.getOrPut(radius) {
        val size = radius * 2 + 2
        val pixels = Pixmap(size, size, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        for (y in 0 until size) for (x in 0 until size) {
            val dx = max(max(radius - x - .5f, x + .5f - (size - radius)), 0f)
            val dy = max(max(radius - y - .5f, y + .5f - (size - radius)), 0f)
            val alpha = (radius + .5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            pixels.drawPixel(x, y, 0xffffff00.toInt() or (alpha * 255f).roundToInt())
        }
        val texture = Texture(pixels).also { pixels.dispose() }
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        panelTextures.add(texture)
        NinePatchDrawable(NinePatch(texture, radius, radius, radius, radius)).tint(navy)
    }

    private fun icon(path: String): Actor = ImageGetter.getImage(
        if (ImageGetter.imageExists(path)) path else "OtherIcons/Star")

    private class Disc(val shadowColor: Color) : Table()
    private class ArcAction(val image: Actor, val title: String, val enabled: Boolean, val activate: () -> Unit)

    private fun disc(image: Actor, size: Float, color: Color = white, caption: String? = null): Table {
        val table = Disc(if (color == yellow) Color.valueOf("c9951c") else if (color == white) Color.valueOf("b9cadb") else Color.valueOf("0b1826"))
        table.touchable = Touchable.enabled
        table.background = ImageGetter.getCircleDrawable().tint(color)
        image.color = if (color == white || color == yellow) ink else Color.WHITE
        table.add(image).size(if (size >= 90f) 34f else 30f)
        if (caption != null) {
            table.row()
            table.add(caption.tr().toLabel(if (color == yellow) ink else Color.WHITE, 15)).padTop(2f)
        }
        table.setSize(size, size)
        return table
    }

    private fun place(actor: Actor, x: Float, y: Float, width: Float, height: Float) {
        if (actor is Disc) {
            val shadow = ImageGetter.getCircle(actor.shadowColor)
            shadow.touchable = Touchable.disabled
            shadow.setBounds(x, y - 5f, width, height)
            addActor(shadow)
        }
        actor.setBounds(x, y, width, height)
        addActor(actor)
    }

    private fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()

    fun dispose() { scrimTexture.dispose(); statTexture.dispose(); panelTextures.forEach { it.dispose() } }

    fun refresh() {
        clearChildren()
        val safe = world.safeAreaBoundsInWorld()
        val scale = safe.width / 393f
        setScale(scale)
        setPosition(safe.x, safe.y)
        setSize(393f, safe.height / scale)
        val topInset = (world.stage.height - safe.y - safe.height) / scale
        val top = height - (54f - topInset).coerceAtLeast(4f)
        val bottom = (40f - safe.y / scale).coerceAtLeast(6f)
        val unitView = world.bottomUnitTable.selectedUnit
        val unit = unitView?.getUnit()
        place(Image(scrimTexture).apply { touchable = Touchable.disabled },
            0f, -safe.y / scale, 393f, if (unit == null) 250f else 420f)
        place(Image(TextureRegion(scrimTexture).apply { flip(false, true) }).apply { touchable = Touchable.disabled },
            0f, height + topInset - 130f, 393f, 130f)
        val civ = world.selectedCiv
        val stats = civ.stats.statsForNextTurn
        val strip = Table().apply { background = panel(16); pad(5f) }
        fun stat(name: String, value: String, secondary: String? = null, action: () -> Unit) {
            val cell = Table().apply { touchable = Touchable.enabled }
            cell.add(statIcon(name)).size(18f).padRight(3f)
            cell.add(value.toLabel(Color.WHITE, 16))
            if (secondary != null) cell.add(secondary.toLabel(Color.valueOf("b7cde0"), 12)).padLeft(2f)
            cell.onClick(action)
            strip.add(cell).height(44f).expandX().fillX()
        }
        stat("Gold", civ.gold.toString(), signed(stats.gold.roundToInt())) { world.openEmpireOverview(EmpireOverviewCategories.Stats) }
        stat("Science", signed(stats.science.roundToInt())) { world.game.pushScreen { TechPickerScreen(civ) } }
        stat("Culture", civ.policies.storedCulture.toString(), "/${civ.policies.getCultureNeededForNextPolicy()}") {
            if (world.gameInfo.ruleset.policyBranches.isNotEmpty())
                world.game.pushScreen { PolicyPickerScreen(civ, world.canChangeState) }
        }
        stat("Happiness", civ.getHappiness().toString()) { world.openEmpireOverview(EmpireOverviewCategories.Resources) }
        if (world.gameInfo.isReligionEnabled())
            stat("Faith", civ.religionManager.storedFaith.toString()) { world.openEmpireOverview(EmpireOverviewCategories.Religion) }
        val menu = Table().apply {
            touchable = Touchable.enabled
            add("T${world.gameInfo.turns}".toLabel(Color.WHITE, 15))
            add(icon("OtherIcons/MenuIcon")).size(16f).padLeft(4f)
            onClick { WorldScreenMenuPopup(world) }
        }
        strip.add(menu).minWidth(54f).height(44f)
        place(strip, 10f, top - 50f, 373f, 50f)

        val actions = if (unit != null && world.canChangeState)
            UnitActions.getUnitActions(unit).sortedWith(compareBy<com.unciv.models.UnitAction> { UnitActions.getActionDefaultPage(unit, it.type) }.thenByDescending { it.useFrequency }).toList() else emptyList()
        val skip = actions.firstOrNull { it.type == UnitActionType.Skip && it.action != null }
        val anchor = disc(icon(if (skip != null) "UnitActionIcons/Skip" else "OtherIcons/ForwardArrow"), 96f, yellow,
            if (skip != null) "Skip" else "Next")
        anchor.onClick {
            if (skip != null && unit != null) unitActions.activateAction(skip, unit)
            else world.nextTurnButton.activate()
        }
        place(anchor, 277f, bottom, 96f, 96f)
        val dueUnits = world.selectedGameView.civView.dueUnitsCount()
        val pendingChoices = NextTurnAction.entries.count {
            it.ordinal in NextTurnAction.PickGreatPerson.ordinal until NextTurnAction.NextUnit.ordinal && it.isChoice(world)
        }
        val decisionCount = dueUnits + pendingChoices
        if (decisionCount > 0 && world.isPlayersTurn) {
            val badge = Table().apply {
                background = ImageGetter.getCircleDrawable().tint(ink)
                add(decisionCount.toString().toLabel(Color.WHITE, 14))
                touchable = Touchable.disabled
            }
            place(badge, 351f, bottom + 76f, 28f, 28f)
        }
        tutorialTop = (top - 58f) * scale + safe.y

        if (unit != null && unitView != null) {
            val tag = Table().apply { background = panel(); pad(10f) }
            val portrait = Table().apply {
                background = ImageGetter.getCircleDrawable().tint(civ.nation.getOuterColor())
                add(ImageGetter.getUnitIcon(unit.baseUnit, Color.WHITE)).size(30f)
            }
            tag.add(portrait).size(46f).padRight(12f)
            val text = Table()
            text.add(world.bottomUnitTable.nameLabelText.toLabel(Color.WHITE, 18)).left().row()
            val base = unit.baseUnit
            val values = Table()
            fun unitStat(name: String, value: String) {
                values.add(statIcon(name)).size(16f).padRight(3f)
                values.add(value.toLabel(Color.WHITE, 14)).padRight(8f)
            }
            if (base.strength > 0) unitStat("Strength", base.strength.toString())
            if (base.rangedStrength > 0) unitStat("Ranged", base.rangedStrength.toString())
            unitStat("Movement", unitView.getMovementString())
            values.add("${unit.health} HP".toLabel(Color.WHITE, 14))
            text.add(values).left()
            tag.add(text).expandX().left()
            if (dueUnits > 0) {
                val position = civ.units.getDueUnits().toList().indexOf(unit) + 1
                if (position > 0) tag.add("$position / $dueUnits".toLabel(Color.valueOf("b7cde0"), 13)).padLeft(4f)
            }
            val close = "×".toLabel(Color.WHITE, 25).apply {
                setAlignment(Align.center)
                touchable = Touchable.enabled
                onClick { world.bottomUnitTable.selectUnit(); world.shouldUpdate = true }
            }
            tag.add(close).size(44f)
            place(tag, 10f, top - 126f, 373f, 68f)
            tutorialTop = (top - 134f) * scale + safe.y

            val arcActions = mutableListOf<ArcAction>()
            battle.portraitAttackButton?.let { attack ->
                arcActions.add(ArcAction(icon("OtherIcons/Crosshair"),
                    attack.text.toString() + (battle.portraitDamagePreview?.let { "\n$it" } ?: ""),
                    !attack.isDisabled) { attack.activate() })
            }
            for (action in actions.filter { it.type != UnitActionType.Skip }.take(3 - arcActions.size)) {
                val image = "UnitActionIcons/${action.type.name}"
                arcActions.add(ArcAction(if (ImageGetter.imageExists(image)) icon(image) else action.getIcon(30f),
                    action.title.tr(), action.action != null) { unitActions.activateAction(action, unit) })
            }
            for ((index, action) in arcActions.take(3).withIndex()) {
                val angle = Math.toRadians((200 + index * 26).toDouble())
                val x = 325f + 182f * cos(angle).toFloat() - 33f
                val y = bottom + 48f - 182f * sin(angle).toFloat() - 33f
                val button = disc(action.image, 66f,
                    if (index == 0 && action.enabled) yellow else white)
                if (!action.enabled) button.color.a = .45f
                else button.onClick { action.activate() }
                place(button, x, y, 66f, 66f)
                val label = Table().apply {
                    background = panel(11)
                    val text = action.title.tr().toLabel(Color.WHITE, 14)
                    val available = if (index < 2) (x - 18f).coerceAtMost(120f) else 104f
                    val textWidth = (text.prefWidth + 1f).coerceAtMost(available - 20f)
                    text.wrap = true
                    add(text).width(textWidth).pad(6f, 10f, 6f, 10f)
                    pack()
                }
                if (index < 2) place(label, (x - label.width - 10f).coerceAtLeast(8f), y + 17f, label.width, label.height)
                else place(label, x + 33f - label.width / 2, y - label.height - 9f, label.width, label.height)
            }
            if (actions.isNotEmpty()) {
                val angle = Math.toRadians(278.0)
                val x = 325f + 182f * cos(angle).toFloat() - 33f
                val y = bottom + 48f - 182f * sin(angle).toFloat() - 33f
                val more = disc(icon("UnitActionIcons/ShowMore"), 66f)
                more.onClick {
                    val popup = Popup(world)
                    if (battle.portraitAttackButton != null) {
                        popup.addButton("Combat details") { popup.close(); battle.isVisible = true; battle.toFront() }.row()
                    }
                    actions.forEach { popup.add(unitActions.getUnitActionButton(unit, it) { popup.close() }).growX().minHeight(48f).row() }
                    popup.addCloseButton()
                    popup.open()
                }
                place(more, x, y, 66f, 66f)
                val label = Table().apply {
                    background = panel(11)
                    add("More".tr().toLabel(Color.WHITE, 14)).pad(6f, 11f, 6f, 11f)
                    pack()
                }
                place(label, x + 33f - label.width / 2f, y - label.height - 9f, label.width, label.height)
            }
        } else {
            val leader = "LeaderIcons/${civ.nation.leaderName}"
            val diplomacy = disc(icon(if (ImageGetter.imageExists(leader)) leader else "OtherIcons/DiplomacyW"), 70f, navy)
            diplomacy.onClick { world.game.pushScreen { DiplomacyScreen(world.selectedGameView.civView) } }
            place(diplomacy, 18f, bottom + 18f, 70f, 70f)
            place("Civ".tr().toLabel(Color.WHITE, 14).apply { setAlignment(Align.center) }, 18f, bottom - 8f, 70f, 24f)
            if (world.gameInfo.ruleset.technologies.isNotEmpty()) {
                val tech = world.selectedGameView.civView.currentTechnologyName()
                val techButton = disc(if (tech == null) ImageGetter.getStatIcon("Science") else ImageGetter.getTechIconPortrait(tech, 30f), 58f, navy)
                techButton.onClick { world.game.pushScreen { TechPickerScreen(civ) } }
                place(techButton, 104f, bottom + 24f, 58f, 58f)
                val title = if (tech == null) "Research" else "$tech ${world.selectedGameView.civView.turnsToTech(tech)}"
                place(title.tr().toLabel(Color.WHITE, 13).apply { setAlignment(Align.center) }, 84f, bottom - 8f, 105f, 24f)
            }
        }
        if (unit == null) {
            val notes = disc(icon("OtherIcons/Notifications"), 54f, navy)
            notes.onClick { PortraitNotificationDigest(world) }
            place(notes, 325f, bottom + 140f, 54f, 54f)
        }
        val zoom = disc(icon("OtherIcons/Search"), 56f, navy)
        zoom.onClick { zoomExpanded = !zoomExpanded; refresh() }
        val zoomY = if (unit == null) bottom + 210f else top - 198f
        place(zoom, 10f, zoomY, 56f, 56f)
        if (zoomExpanded) {
            for ((index, text) in listOf("+", "−").withIndex()) {
                val control = Table().apply {
                    touchable = Touchable.enabled
                    background = ImageGetter.getCircleDrawable().tint(navy)
                    add(text.toLabel(Color.WHITE, 28))
                    onClick { if (index == 0) world.mapHolder.zoomIn() else world.mapHolder.zoomOut() }
                }
                place(control, 10f, zoomY - (index + 1) * 56f, 56f, 52f)
            }
        }
    }
}
