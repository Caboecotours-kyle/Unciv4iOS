package com.unciv.ui.screens.worldscreen

import com.unciv.ui.screens.basescreen.portraitCanvasBounds

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UncivSound
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitTray
import com.unciv.ui.popups.TraySheet
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
import com.unciv.ui.screens.worldscreen.worldmap.MotionTextures
import com.unciv.ui.screens.worldscreen.worldmap.turnOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
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
    /** Turn HUD pieces outlive [refresh], which rebuilds everything else */
    private var turnStrip: TurnStrip? = null
    private var turnToast: Actor? = null
    private var turnPulse: Actor? = null
    private var hudTop = 0f
    private var anchorBottom = 0f
    /** The unit card and its action arc, kept across [refresh] while they fade out for the turn strip */
    private var unitLayer: Group? = null
    /** 1 while the unit controls show, easing to 0 while other civs move; negative until the first [refresh] */
    private var unitShown = -1f
    /** The unit card on its own also steps aside while the "Your turn" toast holds its slot */
    private var unitCard: Actor? = null
    private var cardShown = 1f

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

    /** Round button tones of style C "soft sphere": top-left lit radial gradient stops from the approved mock */
    private enum class Tone(val light: String, val mid: String, val edge: String, val stop: Float) {
        White("ffffff", "eef5fb", "cfdce8", .42f),
        Yellow("ffe58f", "ffc93c", "e8a916", .45f),
        Navy("2d4a66", "1c3249", "132435", .5f),
    }

    /** Gradient and soft-shadow textures, drawn once per tone and pixel size and disposed with the HUD */
    private val discTextures = HashMap<String, Texture>()

    private fun pixels(points: Float): Int {
        val viewport = world.stage.viewport
        val perUnit = viewport.screenWidth / viewport.worldWidth * Gdx.graphics.backBufferWidth / max(1, Gdx.graphics.width)
        return (points * scaleX * perUnit).roundToInt().coerceIn(8, 640)
    }

    private fun discTexture(key: String, size: Int, pixel: (x: Float, y: Float, color: Color) -> Unit): Texture =
        discTextures.getOrPut("$key/$size") {
            val pixels = Pixmap(size, size, Pixmap.Format.RGBA8888)
            pixels.blending = Pixmap.Blending.None
            val color = Color()
            for (y in 0 until size) for (x in 0 until size) {
                pixel(x + .5f, y + .5f, color)
                pixels.drawPixel(x, y, Color.rgba8888(color))
            }
            Texture(pixels).also {
                pixels.dispose()
                it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            }
        }

    /** CSS radial-gradient(circle at 36% 30%, ...) reaching the farthest corner, cut to an antialiased circle */
    private fun sphere(tone: Tone, points: Float): Texture {
        val size = pixels(points)
        val light = Color.valueOf(tone.light)
        val mid = Color.valueOf(tone.mid)
        val edge = Color.valueOf(tone.edge)
        val reach = size * hypot(.64f, .7f)
        return discTexture("sphere/${tone.name}", size) { x, y, color ->
            val d = hypot(x - .36f * size, y - .3f * size) / reach
            if (d < tone.stop) color.set(light).lerp(mid, d / tone.stop) else color.set(mid).lerp(edge, (d - tone.stop) / (1f - tone.stop))
            color.a = (size / 2f + .5f - hypot(x - size / 2f, y - size / 2f)).coerceIn(0f, 1f)
        }
    }

    /** CSS box-shadow blur [blur] (a Gaussian of half that deviation) around a [points] circle, in rgba(7,17,28,[alpha]) */
    private fun softShadow(points: Float, blur: Float, alpha: Float): Texture {
        val span = points + blur * 2.8f
        val size = pixels(span)
        val perPoint = size / span
        val radius = points / 2f * perPoint
        val sigma = blur / 2f * perPoint
        return discTexture("shadow/$points/$blur/$alpha", size) { x, y, color ->
            val d = hypot(x - size / 2f, y - size / 2f)
            color.set(7f / 255f, 17f / 255f, 28f / 255f, alpha / (1f + exp(1.702f * (d - radius) / sigma)))
        }
    }

    /** A soft-sphere disc: the shadow sits centred and slightly low, dims with the disc, and a press scales it to .94 */
    private inner class Disc(tone: Tone, size: Float) : Table() {
        private val big = size >= 90f
        private val drop = if (big) 4f else 3f
        private val blur = if (big) 12f else 9f
        private val shade = softShadow(size, blur, if (big) .3f else .26f)
        var pressable = true

        init {
            background = TextureRegionDrawable(TextureRegion(sphere(tone, size)))
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (!pressable || pointer != 0) return false
                    press(true)
                    return true
                }
                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) = press(false)
            })
        }

        private fun press(down: Boolean) {
            isTransform = down
            setOrigin(width / 2f, height / 2f)
            setScale(if (down) .94f else 1f)
        }

        override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
            val margin = blur * 1.4f
            batch.setColor(1f, 1f, 1f, color.a * parentAlpha)
            batch.draw(shade, x - margin, y - margin - drop, width + 2f * margin, height + 2f * margin)
            super.drawBackground(batch, parentAlpha, x, y)
        }
    }
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
                     iconSize: Float = if (size >= 90f) 34f else 30f): Disc {
        val table = Disc(if (color == yellow) Tone.Yellow else if (color == white) Tone.White else Tone.Navy, size)
        image.color = if (color == white || color == yellow) ink else Color.WHITE
        table.add(image).size(iconSize)
        if (caption != null) {
            table.row()
            table.add(caption.tr().toLabel(if (color == yellow) ink else Color.WHITE, 15)).padTop(2f)
        }
        table.setSize(size, size)
        return table
    }

    private fun place(actor: Actor, x: Float, y: Float, width: Float, height: Float, into: Group = this) {
        actor.setBounds(x, y, width, height)
        into.addActor(actor)
    }

    private fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()

    /** Four 54pt discs fit on one 110pt arc beside Skip without crossing the 393pt canvas edge. */
    private fun actionArcPosition(index: Int, count: Int, bottom: Float): Pair<Float, Float> {
        val step = when (count) { 4 -> 34f; 3 -> 41f; else -> 50f }
        val angle = Math.toRadians((180f - index * step).toDouble())
        return Pair(325f + 110f * cos(angle).toFloat() - 27f,
            bottom + 48f + 110f * sin(angle).toFloat() - 27f)
    }

    fun dispose() {
        scrimTexture.dispose()
        statTexture.dispose()
        panelTextures.forEach { it.dispose() }
        discTextures.values.forEach { it.dispose() }
        discTextures.clear()
    }

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
        // While other civs move, Next waits: navy, with a ring turning in the moving civ's color
        val waiting = world.turnWaitCiv() != null
        val skip = if (waiting) null else actions.firstOrNull { it.type == UnitActionType.Skip && it.action != null }
        val anchor = if (waiting) disc(icon("OtherIcons/ForwardArrow"), 96f, Color.valueOf("1c3249"), "Wait")
            else disc(icon(if (skip != null) "UnitActionIcons/Skip" else "OtherIcons/ForwardArrow"), 96f, yellow,
                if (skip != null) "Skip" else "Next")
        anchor.name = "portrait-next"
        anchor.onClick {
            if (waiting || world.mapHolder.turnReplay != null) world.mapHolder.skipTurnReplay()
            else if (skip != null && unit != null) unitActions.activateAction(skip, unit)
            else world.nextTurnButton.activate()
        }
        if (skip != null) holdToRead(anchor, skip.title.tr(), nextUnitNote(skip.type))
        else if (!waiting) holdToRead(anchor, world.nextTurnButton.label.text.toString(), null, !world.nextTurnButton.isDisabled)
        place(anchor, 277f, bottom, 96f, 96f)
        if (waiting) place(WaitRing(), 271f, bottom - 6f, 108f, 108f)
        hudTop = top
        anchorBottom = bottom
        val dueUnits = world.selectedGameView.civView.dueUnitsCount()
        val pendingChoices = NextTurnAction.entries.count {
            it.ordinal in NextTurnAction.PickGreatPerson.ordinal until NextTurnAction.NextUnit.ordinal && it.isChoice(world)
        }
        val decisionCount = dueUnits + pendingChoices
        if (decisionCount > 0 && world.isPlayersTurn && !waiting) {
            val badge = Table().apply {
                background = ImageGetter.getCircleDrawable().tint(ink)
                add(decisionCount.toString().toLabel(Color.WHITE, 14))
                touchable = Touchable.disabled
            }
            place(badge, 351f, bottom + 76f, 28f, 28f)
        }
        tutorialTop = (top - 58f) * scale + safe.y

        // While other civs move, the unit card and its arc fade aside and the turn strip takes the card slot
        if (unitShown < 0f) unitShown = if (waiting) 0f else 1f
        val units = if (waiting) unitLayer?.takeIf { unitShown > 0f } else if (unit != null) Group().apply { isTransform = false } else null
        unitLayer = units
        if (!waiting) unitCard = null
        if (units != null) {
            units.setSize(393f, height)
            units.touchable = if (waiting) Touchable.disabled else Touchable.childrenOnly
            units.color.a = unitShown
            addActor(units)
        }
        if (unit != null && unitView != null) {
            tutorialTop = (top - 134f) * scale + safe.y
            if (units != null && !waiting) {
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
                    onClickSuppressive { world.bottomUnitTable.selectUnit(); world.shouldUpdate = true }
                }
                tag.add(close).size(48f)
                tag.touchable = Touchable.enabled
                tag.onClick { PortraitInfoSheets.showUnit(world, unitView) }
                place(tag, 10f, top - 126f, 373f, 68f, units)
                unitCard = tag
                showCard()

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
                        activate(action, unit)
                    })
                }
                // One close arc from Skip. Names still appear only while a disc is held.
                val actionCount = min(3, arcActions.size) + if (actions.isNotEmpty()) 1 else 0
                for ((index, action) in arcActions.take(3).withIndex()) {
                    val (x, y) = actionArcPosition(index, actionCount, bottom)
                    val button = disc(action.image, 54f,
                        if (index == 0 && action.enabled) yellow else white, iconSize = 28f)
                    button.name = action.tag
                    if (!action.enabled) {
                        button.color.a = .45f
                        button.pressable = false
                    } else button.onClick { action.activate() }
                    holdToRead(button, action.title, action.note, action.enabled)
                    place(button, x, y, 54f, 54f, units)
                    if (action.chip != null) {
                        val chip = Table().apply {
                            background = panel(10).tint(Color.valueOf("e0524a"))
                            touchable = Touchable.disabled
                            add(action.chip.toLabel(Color.WHITE, 13)).pad(3f, 8f, 3f, 8f)
                            pack()
                        }
                        place(chip, x + 27f - chip.width / 2f, y - chip.height - 4f, chip.width, chip.height, units)
                    }
                }
                if (actions.isNotEmpty()) {
                    val (x, y) = actionArcPosition(min(3, arcActions.size), actionCount, bottom)
                    val more = disc(moreGlyph(), 54f, iconSize = 28f)
                    more.name = "portrait-more"
                    val primary = arcActions.firstOrNull()?.takeIf { it.enabled }?.title
                    more.onClick { MoreSheet(unit, actions, primary).present() }
                    holdToRead(more, "Show more".tr(), null)
                    place(more, x, y, 54f, 54f, units)
                }
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
        // A replay picks up the strip the previous screen showed while the turn processed, so it does not slide in twice
        if (waiting && turnStrip == null) turnStrip = TurnStrip(slideIn = world.mapHolder.turnReplay == null)
        for (actor in listOfNotNull(turnStrip, turnToast, turnPulse)) addActor(actor)
    }

    private val reduceMotion get() = world.game.settings.reduceMotion

    override fun act(delta: Float) {
        super.act(delta)
        if (unitShown < 0f) return
        // A long frame (a screen update) slows the fades instead of skipping them
        val step = min(delta, 1f / 30f) / .18f
        fun approach(value: Float, target: Float) =
            if (reduceMotion) target else if (target < value) max(target, value - step) else min(target, value + step)
        val cardTarget = if (turnToast != null) 0f else 1f
        if (cardShown != cardTarget) {
            cardShown = approach(cardShown, cardTarget)
            showCard()
            Gdx.graphics.requestRendering()
        }
        val waiting = world.mapHolder.turnReplay?.ownMovesOnly == false || world.isNextTurnUpdateRunning()
        val target = if (waiting) 0f else 1f
        if (unitShown == target) return
        unitShown = approach(unitShown, target)
        val layer = unitLayer ?: return
        layer.color.a = unitShown
        if (unitShown <= 0f) {
            layer.remove()
            unitLayer = null
            unitCard = null
        } else Gdx.graphics.requestRendering()
    }

    private fun showCard() {
        val card = unitCard ?: return
        card.color.a = cardShown
        card.touchable = if (cardShown > .5f) Touchable.enabled else Touchable.disabled
    }

    /**
     * Runs a unit order like [UnitActionsTable.activateAction], and lets the unit's reaction play first: an order that
     * moves on to the next unit waits for it, as a hop does. Orders without a reaction go through unchanged.
     */
    private fun activate(action: UnitAction, unit: MapUnit) {
        val map = world.mapHolder
        if (!map.reactions.order(unit, action.type) || !world.game.settings.autoUnitCycle) {
            unitActions.activateAction(action, unit)
            map.reactions.resolve()
            return
        }
        action.action!!.invoke()
        world.shouldUpdate = true
        map.removeUnitActionOverlay()
        val reacting = map.reactions.resolve()
        if (!unit.isDestroyed && !(action.type.isSkippingToNextUnit && (!unit.isMoving() || !unit.hasMovement()))) {
            world.bottomUnitTable.shouldUpdate = true
            return
        }
        if (reacting == null) return world.switchToNextUnit()
        val table = world.bottomUnitTable
        val selection = Triple(table.selectedUnit, table.selectedCity, table.selectedSpy)
        map.motion.afterUnit(reacting) {
            if (Triple(table.selectedUnit, table.selectedCity, table.selectedSpy) == selection) world.switchToNextUnit()
        }
    }
    private fun ease(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)
    private fun knows(civ: Civilization) = civ == world.viewingCivilization || civ.isBarbarian || world.viewingCivilization.knows(civ)
    /** Civs the player has not met stay neutral */
    private fun civColor(civ: Civilization): Color {
        if (!knows(civ)) return Color.valueOf("496077")
        val outer = civ.nation.getOuterColor()
        return if (outer.r + outer.g + outer.b > 2.6f) civ.nation.getInnerColor() else outer
    }

    private inner class WaitRing : Image(MotionTextures.ring(6f, .22f)) {
        init { touchable = Touchable.disabled }
        override fun act(delta: Float) {
            super.act(delta)
            setOrigin(width / 2f, height / 2f)
            world.turnWaitCiv()?.let { color = civColor(it) }
            if (reduceMotion) return
            rotation -= 360f * delta
            Gdx.graphics.requestRendering()
        }
    }

    /** One disc per major civ in turn order: done ones dimmed, the moving one ringed, then its name. */
    private inner class TurnStrip(slideIn: Boolean) : Table() {
        private var shown: Civilization? = null
        private var age = if (slideIn) 0f else 1f
        private var leaving = false

        init {
            background = panel(26)
            touchable = Touchable.disabled
            pad(0f, 8f, 0f, 16f)
        }

        private fun show(civ: Civilization) {
            shown = civ
            clearChildren()
            val majors = turnOrder(world.gameInfo, world.viewingCivilization).filter { it.isMajorCiv() && it.isAlive() }
            val current = majors.indexOf(civ)
            // Fit 373pt (the canvas less 10pt margins): the name is capped, and with many civs only a window
            // of discs around the moving one is shown
            val name = (if (knows(civ)) civ.civName.tr() else "Unknown".tr()).toLabel(Color.WHITE, 16).apply { setEllipsis(true) }
            val nameWidth = min(name.prefWidth, 140f)
            val slots = ((373f - 24f - 4f - nameWidth) / 42f).toInt().coerceIn(1, max(1, majors.size))
            val first = (current - slots / 2).coerceIn(0, max(0, majors.size - slots))
            for ((index, major) in majors.withIndex()) {
                if (index < first || index >= first + slots) continue
                val disc = Table().apply { background = ImageGetter.getCircleDrawable().tint(civColor(major)) }
                if (knows(major)) disc.add(ImageGetter.getNationIcon(major.civName).apply { color = major.nation.getInnerColor() }).size(20f)
                else disc.add(ImageGetter.getCircle(Color(1f, 1f, 1f, .5f), 10f)).size(10f)
                val stack = Stack(disc)
                if (index == current) stack.add(Image(MotionTextures.ring(12f)))
                stack.isTransform = true
                stack.setOrigin(18f, 18f)
                if (index == current) stack.setScale(1.12f)
                if (index < current) stack.color.a = .4f
                add(stack).size(36f).padRight(6f)
            }
            add(name).width(nameWidth).padLeft(4f)
        }

        override fun act(delta: Float) {
            super.act(delta)
            val civ = world.turnWaitCiv()
            if ((civ == null) != leaving) {
                // Waiting ended, or a new turn started while the strip was still leaving
                leaving = civ == null
                age = 0f
            }
            if (civ != null && civ != shown) show(civ)
            age += delta
            val p = if (reduceMotion) 1f else (age / if (leaving) .26f else .3f).coerceIn(0f, 1f)
            setSize(prefWidth, 52f)
            setPosition((393f - width) / 2f, hudTop - 118f + 30f * if (leaving) ease(p) else 1f - ease(p))
            color.a = if (leaving) 1f - p else min(1f, p * 2f)
            if (leaving && p >= 1f) {
                remove()
                if (turnStrip === this) turnStrip = null
                return
            }
            Gdx.graphics.requestRendering()
        }
    }

    /** After the replay: a yellow toast drops in for about 1.5 s and Next pulses once. */
    fun showYourTurn(turn: Int) {
        turnToast?.remove()
        turnPulse?.remove()
        val toast = object : Table() {
            private var age = 0f
            override fun act(delta: Float) {
                super.act(delta)
                age += delta
                val enter = if (reduceMotion) 1f else (age / .3f).coerceIn(0f, 1f)
                val leave = if (reduceMotion) (if (age >= 2.1f) 1f else 0f) else ((age - 1.8f) / .3f).coerceIn(0f, 1f)
                setPosition((393f - width) / 2f, hudTop - 122f + 30f * (1f - ease(enter)) + 30f * leave)
                color.a = min(1f, enter * 2f) * (1f - leave)
                if (leave >= 1f) {
                    remove()
                    if (turnToast === this) turnToast = null
                } else Gdx.graphics.requestRendering()
            }
        }
        toast.background = panel(28).tint(yellow)
        toast.touchable = Touchable.disabled
        toast.add("Your turn".tr().toLabel(ink, 19)).padLeft(24f)
        toast.add("T$turn".toLabel(ink.cpy().apply { a = .7f }, 14)).padLeft(10f).padRight(24f)
        toast.setSize(toast.prefWidth, 56f)
        toast.color.a = 0f
        turnToast = toast
        addActor(toast)
        if (reduceMotion) return
        val pulse = object : Image(MotionTextures.ring(8f)) {
            private var age = 0f
            override fun act(delta: Float) {
                super.act(delta)
                age += delta
                val p = ((age - .05f) / .7f).coerceIn(0f, 1f)
                setOrigin(width / 2f, height / 2f)
                setScale(1f + .45f * p)
                color.a = if (p <= 0f) 0f else 1f - p
                if (p >= 1f) {
                    remove()
                    if (turnPulse === this) turnPulse = null
                } else Gdx.graphics.requestRendering()
            }
        }
        pulse.color = yellow.cpy().apply { a = 0f }
        pulse.touchable = Touchable.disabled
        pulse.setBounds(277f, anchorBottom, 96f, 96f)
        turnPulse = pulse
        addActor(pulse)
    }

    /** Same colors as the unit's map flag: outer color disc, inner color icon (Greece reads blue on white). */
    private fun unitPortrait(unit: MapUnit, iconSize: Float) = Table().apply {
        val nation = unit.civ.nation
        background = ImageGetter.getCircleDrawable().tint(nation.getOuterColor())
        add(ImageGetter.getUnitIcon(unit.baseUnit, nation.getInnerColor())).size(iconSize)
    }

    /** The More drawer: every unit action, Skip and combat details included, as a tray sheet (direction B).
     *  Actions run after the sheet closes so their own confirmations (e.g. Disband) can open. */
    private inner class MoreSheet(unit: MapUnit, actions: List<UnitAction>, primary: String?) :
        PortraitTray(world, maxShare = .64f) {

        /** Waits behind any open popup, as the drawer always has */
        fun present() = present(force = false)

        init {
            name = "portrait-more-sheet"
            val rows = ArrayList<Actor>()
            fun addRow(image: Actor, title: String, enabled: Boolean, meta: String?, tone: TraySheet.Tone, current: Boolean,
                    sound: UncivSound, run: () -> Unit): Table {
                val state = when {
                    !enabled -> TraySheet.RowState.Disabled
                    tone == TraySheet.Tone.Danger -> TraySheet.RowState.Danger
                    current -> TraySheet.RowState.Selected
                    else -> TraySheet.RowState.Normal
                }
                // Action icons are black and cannot take the red ink, so Disband keeps the white disc beside its red title
                val disc = sheet.disc(image, if (tone == TraySheet.Tone.Danger) TraySheet.Tone.White else tone, iconSize = 26f)
                val row = sheet.row(title, meta = if (enabled) meta else null, lead = disc,
                    state = state, reason = if (enabled) null else meta, chevron = false, sound = sound,
                    onTap = if (enabled) ({ close(); run() }) else null)
                rows += row
                return row
            }

            battle.portraitAttackButton?.let { attack ->
                val title = attack.text.toString()
                val enabled = !attack.isDisabled
                addRow(icon("OtherIcons/CrosshairB"), title, enabled,
                    if (enabled) battle.portraitDamagePreview else "Unavailable".tr(),
                    if (title == primary) TraySheet.Tone.Yellow else TraySheet.Tone.White, false, UncivSound.Silent) { attack.activate() }
                addRow(icon("OtherIcons/Search"), "Combat details".tr(), true, null, TraySheet.Tone.White, false, UncivSound.Click) {
                    battle.isVisible = true
                    battle.toFront()
                }
            }
            for (action in actions) {
                val enabled = action.action != null
                val title = action.title.tr()
                val tone = if (action.type == UnitActionType.DisbandUnit) TraySheet.Tone.Danger
                    else if (title == primary) TraySheet.Tone.Yellow else TraySheet.Tone.White
                val image = "UnitActionIcons/${action.type.name}"
                val row = addRow(if (ImageGetter.imageExists(image)) icon(image) else action.getIcon(sheet.pt(26f)), title, enabled,
                    if (enabled) null else "Unavailable".tr(), tone, action.isCurrentAction, action.uncivSound) {
                    activate(action, unit)
                }
                // Same upgrade details menu the landscape action button offers, reachable even when disabled.
                if (action is UpgradeUnitAction) {
                    val details = { UnitUpgradeMenu(world.stage, row, unit, action, enabled, true) { close(); world.shouldUpdate = true } }
                    row.onRightClick { details() }
                    if (!enabled) {
                        row.touchable = Touchable.enabled
                        row.onClick { details() }
                    }
                }
            }
            sheet.header(world.bottomUnitTable.nameLabelText, lead = unitPortrait(unit, sheet.pt(32f)))
            sheet.addToBody(sheet.card(rows), 4f)
        }
    }
}
