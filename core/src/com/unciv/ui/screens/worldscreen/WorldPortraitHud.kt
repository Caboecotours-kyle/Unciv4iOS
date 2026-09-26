package com.unciv.ui.screens.worldscreen

import com.unciv.ui.screens.basescreen.portraitCanvasBounds

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UncivSound
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.UnitUpgradeMenu
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.pickerscreens.ReligionPathScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.worldscreen.bottombar.BattleTable
import com.unciv.ui.screens.worldscreen.mainmenu.WorldScreenMenuPopup
import com.unciv.ui.screens.worldscreen.status.NextTurnAction
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsTable
import kotlin.math.cos
import kotlin.math.min
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

    private fun moreGlyph(): Actor = Table().apply {
        repeat(3) { index -> add(ImageGetter.getCircle(ink)).size(5f).padRight(if (index < 2) 4f else 0f) }
    }

    private class Disc(val shadowColor: Color) : Table()
    /** [note] is the side effect shown while held; [chip] stays visible (attack damage); [tag] names the disc for tutorial rings. */
    private class ArcAction(val image: Actor, val title: String, val enabled: Boolean,
        val note: String? = null, val chip: String? = null, val tag: String? = null, val activate: () -> Unit)

    private var callout: Actor? = null
    private var calloutOwner: Actor? = null

    private fun hideCallout(owner: Actor? = calloutOwner) {
        if (owner !== calloutOwner) return
        callout?.remove()
        callout = null
        calloutOwner = null
    }

    /** Holding shows the translated name; releasing only hides it, so reading never acts.
     *  Disabled controls explain themselves as soon as they are touched. */
    private fun holdToRead(target: Actor, title: String, note: String?, enabled: Boolean = true) {
        val show = { showCallout(target, title, if (enabled) note else "Unavailable".tr()) }
        if (enabled) target.onLongPress(UncivSound.Silent) { show() }
        target.addListener(object : InputListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (!enabled) show()
                return true
            }
            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) = hideCallout(target)
        })
    }

    private fun showCallout(target: Actor, title: String, note: String?) {
        hideCallout()
        val box = Table().apply {
            background = panel(12)
            touchable = Touchable.disabled
            for ((text, color, size) in listOfNotNull(Triple(title, Color.WHITE, 16), note?.let { Triple(it, Color.valueOf("b7cde0"), 13) })) {
                val label = text.toLabel(color, size)
                val labelWidth = min(label.prefWidth + 1f, 220f)
                label.wrap = true
                add(label).width(labelWidth).left().row()
            }
            pad(8f, 12f, 8f, 12f)
            pack()
        }
        box.setPosition((target.x + target.width / 2f - box.width / 2f).coerceIn(8f, 385f - box.width),
            (target.y + target.height + 12f).coerceAtMost(height - box.height - 8f))
        addActor(box)
        callout = box
        calloutOwner = target
    }

    private fun nextUnitNote(type: UnitActionType) =
        if (type.isSkippingToNextUnit && world.game.settings.autoUnitCycle) "Next unit".tr() else null

    private fun disc(image: Actor, size: Float, color: Color = white, caption: String? = null,
                     iconSize: Float = if (size >= 90f) 34f else 30f): Table {
        val table = Disc(if (color == yellow) Color.valueOf("c9951c") else if (color == white) Color.valueOf("b9cadb") else Color.valueOf("0b1826"))
        table.touchable = Touchable.enabled
        table.background = ImageGetter.getCircleDrawable().tint(color)
        image.color = if (color == white || color == yellow) ink else Color.WHITE
        table.add(image).size(iconSize)
        if (caption != null) {
            table.row()
            table.add(caption.tr().toLabel(if (color == yellow) ink else Color.WHITE, 15)).padTop(2f)
        }
        table.setSize(size, size)
        return table
    }

    private fun place(actor: Actor, x: Float, y: Float, width: Float, height: Float, shadowOffset: Float = 5f) {
        if (actor is Disc) {
            val shadow = ImageGetter.getCircle(actor.shadowColor)
            shadow.touchable = Touchable.disabled
            shadow.setBounds(x, y - shadowOffset, width, height)
            addActor(shadow)
        }
        actor.setBounds(x, y, width, height)
        addActor(actor)
    }

    private fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()

    /** Four 54pt discs fit on one 110pt arc beside Skip without crossing the 393pt canvas edge. */
    private fun actionArcPosition(index: Int, count: Int, bottom: Float): Pair<Float, Float> {
        val step = when (count) { 4 -> 34f; 3 -> 41f; else -> 50f }
        val angle = Math.toRadians((180f - index * step).toDouble())
        return Pair(325f + 110f * cos(angle).toFloat() - 27f,
            bottom + 48f + 110f * sin(angle).toFloat() - 27f)
    }

    fun dispose() { scrimTexture.dispose(); statTexture.dispose(); panelTextures.forEach { it.dispose() } }

    fun refresh() {
        hideCallout()
        clearChildren()
        val safe = world.safeAreaBoundsInWorld()
        val canvas = world.portraitCanvasBounds()
        val scale = safe.width / 393f
        setScale(scale)
        setPosition(safe.x, safe.y)
        setSize(393f, safe.height / scale)
        val topInset = (canvas.y + canvas.height - safe.y - safe.height) / scale
        val top = height - (54f - topInset).coerceAtLeast(4f)
        val bottom = (40f - (safe.y - canvas.y) / scale).coerceAtLeast(6f)
        val unitView = world.bottomUnitTable.selectedUnit
        val unit = unitView?.getUnit()
        place(Image(scrimTexture).apply { touchable = Touchable.disabled },
            0f, (canvas.y - safe.y) / scale, 393f, if (unit == null) 250f else 420f)
        place(Image(TextureRegion(scrimTexture).apply { flip(false, true) }).apply { touchable = Touchable.disabled },
            0f, height + topInset - 130f, 393f, 130f)
        val civ = world.selectedCiv
        val stats = civ.stats.statsForNextTurn
        val strip = Table().apply { background = panel(16); pad(5f) }
        fun stat(name: String, value: String, secondary: String? = null, action: () -> Unit) {
            val cell = Table().apply { touchable = Touchable.enabled; this.name = "portrait-stat-$name" }
            cell.add(statIcon(name)).size(18f).padRight(3f)
            cell.add(value.toLabel(Color.WHITE, 16))
            if (secondary != null) cell.add(secondary.toLabel(Color.valueOf("b7cde0"), 12)).padLeft(2f)
            cell.onClick(action)
            strip.add(cell).minWidth(48f).height(48f).expandX().fillX()
        }
        stat("Gold", civ.gold.toString(), signed(stats.gold.roundToInt())) { world.openEmpireOverview(EmpireOverviewCategories.Stats) }
        stat("Science", signed(stats.science.roundToInt())) { world.game.pushScreen { TechPickerScreen(civ) } }
        stat("Culture", civ.policies.storedCulture.toString(), "/${civ.policies.getCultureNeededForNextPolicy()}") {
            if (world.gameInfo.ruleset.policyBranches.isNotEmpty())
                world.game.pushScreen { PolicyPickerScreen(civ, world.canChangeState) }
        }
        stat("Happiness", civ.getHappiness().toString()) { world.openEmpireOverview(EmpireOverviewCategories.Resources) }
        if (world.gameInfo.isReligionEnabled())
            stat("Faith", civ.religionManager.storedFaith.toString()) { world.game.pushScreen { ReligionPathScreen(civ, world) } }
        val menu = Table().apply {
            touchable = Touchable.enabled
            name = "portrait-menu"
            add("T${world.gameInfo.turns}".toLabel(Color.WHITE, 15))
            add(icon("OtherIcons/MenuIcon")).size(16f).padLeft(4f)
            onClick { WorldScreenMenuPopup(world) }
        }
        strip.add(menu).minWidth(54f).height(48f)
        place(strip, 10f, top - 58f, 373f, 58f)

        val actions = if (unit != null && world.canChangeState)
            UnitActions.getUnitActions(unit).sortedWith(compareBy<com.unciv.models.UnitAction> { UnitActions.getActionDefaultPage(unit, it.type) }.thenByDescending { it.useFrequency }).toList() else emptyList()
        val skip = actions.firstOrNull { it.type == UnitActionType.Skip && it.action != null }
        val anchor = disc(icon(if (skip != null) "UnitActionIcons/Skip" else "OtherIcons/ForwardArrow"), 96f, yellow,
            if (skip != null) "Skip" else "Next")
        anchor.name = "portrait-next"
        anchor.onClick {
            if (skip != null && unit != null) unitActions.activateAction(skip, unit)
            else world.nextTurnButton.activate()
        }
        if (skip != null) holdToRead(anchor, skip.title.tr(), nextUnitNote(skip.type))
        else holdToRead(anchor, world.nextTurnButton.label.text.toString(), null, !world.nextTurnButton.isDisabled)
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
            tag.add(unitPortrait(unit, 30f)).size(46f).padRight(12f)
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
            tag.add(close).size(48f)
            place(tag, 10f, top - 126f, 373f, 68f)
            tutorialTop = (top - 134f) * scale + safe.y

            val arcActions = mutableListOf<ArcAction>()
            battle.portraitAttackButton?.let { attack ->
                val damage = battle.portraitDamagePreview
                arcActions.add(ArcAction(icon("OtherIcons/CrosshairB"), attack.text.toString(),
                    !attack.isDisabled, damage, damage) { attack.activate() })
            }
            for (action in actions.filter { it.type != UnitActionType.Skip }.take(3 - arcActions.size)) {
                val image = "UnitActionIcons/${action.type.name}"
                arcActions.add(ArcAction(if (ImageGetter.imageExists(image)) icon(image) else action.getIcon(28f),
                    action.title.tr(), action.action != null, nextUnitNote(action.type), tag = "portrait-action-${action.type.name}") {
                    unitActions.activateAction(action, unit)
                })
            }
            // One close arc from Skip. Names still appear only while a disc is held.
            val actionCount = min(3, arcActions.size) + if (actions.isNotEmpty()) 1 else 0
            for ((index, action) in arcActions.take(3).withIndex()) {
                val (x, y) = actionArcPosition(index, actionCount, bottom)
                val button = disc(action.image, 54f,
                    if (index == 0 && action.enabled) yellow else white, iconSize = 28f)
                button.name = action.tag
                if (!action.enabled) button.color.a = .45f
                else button.onClick { action.activate() }
                holdToRead(button, action.title, action.note, action.enabled)
                place(button, x, y, 54f, 54f, shadowOffset = 3f)
                if (action.chip != null) {
                    val chip = Table().apply {
                        background = panel(10).tint(Color.valueOf("e0524a"))
                        touchable = Touchable.disabled
                        add(action.chip.toLabel(Color.WHITE, 13)).pad(3f, 8f, 3f, 8f)
                        pack()
                    }
                    place(chip, x + 27f - chip.width / 2f, y - chip.height - 4f, chip.width, chip.height)
                }
            }
            if (actions.isNotEmpty()) {
                val (x, y) = actionArcPosition(min(3, arcActions.size), actionCount, bottom)
                val more = disc(moreGlyph(), 54f, iconSize = 28f)
                more.name = "portrait-more"
                val primary = arcActions.firstOrNull()?.takeIf { it.enabled }?.title
                more.onClick { MoreSheet(unit, actions, primary).present() }
                holdToRead(more, "Show more".tr(), null)
                place(more, x, y, 54f, 54f, shadowOffset = 3f)
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
                techButton.name = "portrait-tech"
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

    /** Same colors as the unit's map flag: outer color disc, inner color icon (Greece reads blue on white). */
    private fun unitPortrait(unit: MapUnit, iconSize: Float) = Table().apply {
        val nation = unit.civ.nation
        background = ImageGetter.getCircleDrawable().tint(nation.getOuterColor())
        add(ImageGetter.getUnitIcon(unit.baseUnit, nation.getInnerColor())).size(iconSize)
    }

    /** The More drawer: every unit action, Skip and combat details included, as the accepted navy bottom sheet.
     *  Actions run after the sheet closes so their own confirmations (e.g. Disband) can open. */
    private inner class MoreSheet(unit: MapUnit, actions: List<UnitAction>, primary: String?) :
        Popup(world, Scrollability.None, 1f) {
        private val p = world.safeAreaBoundsInWorld().width / 393f
        private val canvasY = world.portraitCanvasBounds().y
        private val sheet = Color.valueOf("122536")
        private val muted = Color.valueOf("b7cde0")
        private val danger = Color.valueOf("ffb1aa")
        private val dangerDisc = Color(224f / 255f, 82f / 255f, 74f / 255f, .2f)
        private val line = Color(1f, 1f, 1f, .12f)
        private val fill = ImageGetter.getWhiteDotDrawable()
        private fun pt(value: Float) = value * p
        private fun font(size: Int) = (size * p).roundToInt()

        init {
            background = fill.tint(Color(10f / 255f, 22f / 255f, 34f / 255f, .35f))
            innerTable.background = panel(pt(28f).roundToInt()).tint(sheet)
            innerTable.pad(0f).defaults().pad(0f)
            getCell(innerTable)?.expand()?.bottom()?.growX()
            clickBehindToClose = true

            val header = Table()
            header.touchable = Touchable.enabled
            header.add(Table().apply { background = panel(max(1, pt(2.5f).roundToInt())).tint(Color(1f, 1f, 1f, .3f)) })
                .size(pt(44f), pt(5f)).colspan(3).padTop(pt(8f)).row()
            header.add(unitPortrait(unit, pt(30f))).size(pt(48f)).pad(pt(8f), pt(16f), pt(8f), pt(12f))
            header.add(world.bottomUnitTable.nameLabelText.toLabel(Color.WHITE, font(21))).expandX().left()
            header.add(Table().apply {
                touchable = Touchable.enabled
                add(ImageGetter.getImage("OtherIcons/Close").apply { color = muted }).size(pt(18f))
                onClick { close() }
            }).size(pt(48f)).padRight(pt(8f))
            header.addListener(swipeToClose())
            add(header).growX().row()

            val list = Table()
            fun addRow(image: Actor, title: String, enabled: Boolean, chip: String?, tone: Color, current: Boolean,
                    sound: UncivSound, run: () -> Unit): Table {
                val isDanger = tone === dangerDisc
                if (list.cells.size > 0) list.add(Image(fill).apply { color = line }).height(max(1f, pt(1f))).growX().row()
                val row = Table()
                row.touchable = Touchable.enabled
                image.color = if (isDanger) danger else ink
                row.add(Table().apply { background = ImageGetter.getCircleDrawable().tint(tone); add(image).size(pt(26f)) })
                    .size(pt(48f)).padRight(pt(14f))
                val text = Table()
                val nameColor = if (isDanger) danger else if (current) yellow else Color.WHITE
                text.add(title.toLabel(nameColor, font(17)).apply { wrap = true }).growX().row()
                if (chip != null) text.add(Table().apply {
                    background = panel(pt(10f).roundToInt()).tint(Color(1f, 1f, 1f, .08f))
                    add(chip.toLabel(muted, font(13))).pad(pt(4f), pt(9f), pt(4f), pt(9f))
                }).left().padTop(pt(5f))
                row.add(text).growX()
                row.pad(pt(8f), pt(6f), pt(8f), pt(6f))
                if (!enabled) row.color.a = .45f
                else row.onClick(sound) { close(); run() }
                list.add(row).growX().minHeight(pt(64f)).row()
                return row
            }

            battle.portraitAttackButton?.let { attack ->
                val title = attack.text.toString()
                val enabled = !attack.isDisabled
                addRow(icon("OtherIcons/CrosshairB"), title, enabled,
                    if (enabled) battle.portraitDamagePreview else "Unavailable".tr(),
                    if (title == primary) yellow else white, false, UncivSound.Silent) { attack.activate() }
                addRow(icon("OtherIcons/Search"), "Combat details".tr(), true, null, white, false, UncivSound.Click) {
                    battle.isVisible = true
                    battle.toFront()
                }
            }
            for (action in actions) {
                val image = "UnitActionIcons/${action.type.name}"
                val enabled = action.action != null
                val title = action.title.tr()
                val tone = if (action.type == UnitActionType.DisbandUnit) dangerDisc else if (title == primary) yellow else white
                val row = addRow(if (ImageGetter.imageExists(image)) icon(image) else action.getIcon(pt(26f)), title, enabled,
                    if (enabled) null else "Unavailable".tr(), tone, action.isCurrentAction, action.uncivSound) {
                    unitActions.activateAction(action, unit)
                }
                // Same upgrade details menu the landscape action button offers, reachable even when disabled.
                if (action is UpgradeUnitAction) {
                    val details = { UnitUpgradeMenu(world.stage, row, unit, action, enabled, true) { close(); world.shouldUpdate = true } }
                    row.onRightClick { details() }
                    if (!enabled) row.onClick { details() }
                }
            }
            val pane = AutoScrollPane(list).apply {
                setOverscroll(false, false)
                setScrollingDisabled(true, false)
            }

            val bottomGap = pt((34f - (world.safeAreaBoundsInWorld().y - canvasY) / p).coerceAtLeast(12f))
            val footer = Table()
            footer.add(Table().apply {
                touchable = Touchable.enabled
                background = panel(pt(18f).roundToInt()).tint(Color(1f, 1f, 1f, .09f))
                add("Close".tr().toLabel(Color.WHITE, font(17)))
                onActivation { close() }
                keyShortcuts.add(KeyCharAndCode.BACK)
            }).growX().height(pt(52f)).pad(pt(10f), pt(14f), bottomGap, pt(14f))

            // Size wrapped rows at their real width, then cap the list so the sheet stays within 64% of the phone.
            list.width = world.safeAreaBoundsInWorld().width - pt(24f)
            list.validate()
            list.invalidate()
            val maxList = world.portraitCanvasBounds().height * .64f - header.prefHeight - footer.prefHeight
            add(pane).growX().height(min(list.prefHeight, maxList.coerceAtLeast(pt(64f)))).padLeft(pt(12f)).padRight(pt(12f)).row()
            add(Image(fill).apply { color = line }).height(max(1f, pt(1f))).growX().row()
            add(footer).growX()
        }

        fun present() {
            open()
            fit()
        }

        /** Full safe width, pinned to the bottom; the scroll list keeps its own drags. */
        private fun fit() {
            val safe = world.safeAreaBoundsInWorld()
            pad(0f).padLeft(safe.x).padBottom(safe.y).padRight(world.stage.width - safe.x - safe.width)
            invalidate()
        }

        override fun onVisibleAreaChanged(visibleArea: Rectangle) = fit()

        override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
            super.drawBackground(batch, parentAlpha, x, y)
            // Carry the sheet under the home indicator and square off its bottom corners.
            val from = min(canvasY - y, innerTable.y)
            val fillHeight = innerTable.y + pt(28f) - from
            if (fillHeight <= 0f) return
            batch.setColor(sheet.r, sheet.g, sheet.b, parentAlpha)
            fill.draw(batch, x + innerTable.x, y + from, innerTable.width, fillHeight)
        }

        /** Dragging the header down follows the finger; past 72pt it closes, otherwise it springs back. */
        private fun swipeToClose() = object : ActorGestureListener() {
            private var start = 0f
            private var drag = 0f
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                start = innerTable.y
                drag = 0f
            }
            override fun pan(event: InputEvent?, x: Float, y: Float, deltaX: Float, deltaY: Float) {
                drag = (drag - deltaY).coerceAtLeast(0f)
                innerTable.y = start - drag
            }
            override fun panStop(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                if (drag > pt(72f)) close()
                else innerTable.addAction(Actions.moveTo(innerTable.x, start, .15f))
            }
        }
    }
}
