package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitDialog
import kotlin.math.roundToInt

/** Portrait tutorial task (popup-proposal "Tutorial"): the built-in tasks speak in phone taps and ring the real HUD control.
 *  Completion stays with the game's own triggers; choices run through [com.unciv.models.ruleset.EventChoice.triggerChoice].
 *  Events without phone steps (mods) keep their [RenderEvent] content inside the same card. */
internal class PortraitTutorialTask(private val world: WorldScreen) : Group() {
    /** [targets] are [WorldPortraitHud] actor names; the first one present gets the ring */
    private class Step(val text: String, vararg val targets: String)
    private class PhoneTask(val steps: List<Step>, val text: String? = null, val current: (WorldScreen, MapUnit?) -> Int = { _, _ -> 0 })

    private var card = Table()
    private val ring = Image()
    private var ringTexture: Texture? = null
    private var targets: Array<out String> = emptyArray()
    private val scale get() = PortraitDialog.scale(world.stage)
    private fun pt(value: Float) = value * scale
    private fun font(size: Int) = (size * scale).roundToInt()

    init {
        isTransform = false
        touchable = Touchable.childrenOnly
        ring.touchable = Touchable.disabled
        ring.color = PortraitDialog.YELLOW
        ring.addAction(Actions.forever(Actions.sequence(Actions.alpha(.35f, .7f), Actions.alpha(1f, .7f))))
        addActor(ring)
    }

    /** Rebuilds the card for [event]; `false` when the event has no valid choice, like [RenderEvent.isValid] */
    fun show(event: Event): Boolean {
        val choices = event.getMatchingChoices(GameContext(world.gameInfo.currentPlayerCiv)) ?: return false
        // A fresh actor each time: click handlers accumulate on a reused one
        card.remove()
        card = Table()
        addActorAt(0, card)
        targets = emptyArray()
        if (UncivGame.Current.isTutorialTaskCollapsed) {
            card.background = ImageGetter.getCircleDrawable().tint(PortraitDialog.NAVY)
            card.add(ImageGetter.getImage("OtherIcons/HiddenTutorialTask").apply { color = PortraitDialog.YELLOW }).size(pt(26f))
            card.onClick { setCollapsed(false) }
            return true
        }
        card.background = PortraitDialog.panel(PortraitDialog.NAVY)
        card.pad(pt(12f), pt(14f), pt(12f), pt(6f))
        val width = world.safeAreaBoundsInWorld().width - pt(20f) - pt(14f + 6f + 48f + 6f)
        val content = Table()
        val task = TASKS[event.name]
        if (task == null) content.add(RenderEvent(event, world) { world.shouldUpdate = true }).width(width)
        else {
            val unit = world.bottomUnitTable.selectedUnit?.getUnit()
            content.add(text(event.civilopediaText.firstOrNull()?.text ?: event.name, 17, Color.WHITE, width)).width(width).left().row()
            if (task.text != null)
                content.add(text(task.text, 15, INK2, width)).width(width).left().padTop(pt(6f)).row()
            val current = task.current(world, unit)
            for ((index, step) in task.steps.withIndex())
                content.add(stepRow(step.text, index, current, width)).width(width).left().padTop(pt(if (index == 0) 8f else 6f)).row()
            targets = task.steps.getOrNull(current)?.targets ?: emptyArray()
            for (choice in choices) {
                val button = TextButton(choice.text.tr(), PortraitDialog.buttonStyle(PortraitDialog.Kind.Primary))
                button.onActivation {
                    world.shouldUpdate = true
                    choice.triggerChoice(world.gameInfo.currentPlayerCiv)
                }
                val key = KeyCharAndCode.parse(choice.keyShortcut)
                if (key != KeyCharAndCode.UNKNOWN) button.keyShortcuts.add(key)
                content.add(button).width(width).height(pt(48f)).padTop(pt(10f)).row()
            }
        }
        card.add(content).width(width).top().left()
        val hide = Table().apply {
            touchable = Touchable.enabled
            // Size before rotating, so the chevron turns around its own center
            add(ImageGetter.getImage("OtherIcons/ArrowRight").apply {
                color = INK2
                setSize(pt(18f), pt(18f))
                setOrigin(Align.center)
                rotation = 90f
            }).size(pt(18f))
            onClick { setCollapsed(true) }
        }
        card.add(hide).size(pt(48f)).top().padLeft(pt(6f))
        return true
    }

    private fun setCollapsed(collapsed: Boolean) {
        UncivGame.Current.isTutorialTaskCollapsed = collapsed
        world.shouldUpdate = true
    }

    private fun text(value: String, size: Int, color: Color, width: Float): Label =
        value.tr().toLabel(color, font(size), hideIcons = true).apply {
            wrap = true
            setWidth(width)  // wrapped labels measure their height at this width
        }

    private fun stepRow(value: String, index: Int, current: Int, width: Float) = Table().apply {
        val done = index < current
        val badge = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(when {
                done -> FOOD
                index == current -> PortraitDialog.YELLOW
                else -> Color(1f, 1f, 1f, .12f)
            })
            add((if (done) "✓" else "${index + 1}").toLabel(if (done || index == current) YELLOW_INK else INK2, font(13)))
        }
        add(badge).size(pt(24f)).padRight(pt(9f))
        add(text(value, 15, if (done) INK3 else Color.WHITE, width - pt(33f))).width(width - pt(33f)).left()
    }

    /** Lays the card out under [top] (stage y) and rings the current step's control when [hud] shows it */
    fun place(top: Float, hud: Group) {
        val safe = world.safeAreaBoundsInWorld()
        setBounds(0f, 0f, world.stage.width, world.stage.height)
        if (UncivGame.Current.isTutorialTaskCollapsed) {
            // Right side: the zoom disc sits under the unit card on the left
            card.setBounds(safe.x + safe.width - pt(58f), top - pt(56f), pt(48f), pt(48f))
        } else {
            card.width = safe.width - pt(20f)
            card.height = card.prefHeight
            // Keep the selected unit's zoom disc reachable above the expanded task.
            val zoomSpace = if (world.bottomUnitTable.selectedUnit != null) pt(64f) else 0f
            card.setPosition(safe.x + pt(10f), top - zoomSpace - pt(8f) - card.height)
        }
        val target = targets.firstNotNullOfOrNull { hud.findActor<Actor>(it) }
        ring.isVisible = target != null && target.isVisible
        if (target == null) return
        if (ringTexture == null) ringTexture = ringTexture().also { ring.drawable = TextureRegionDrawable(it) }
        val corner = target.localToStageCoordinates(Vector2(0f, 0f))
        val far = target.localToStageCoordinates(Vector2(target.width, target.height))
        val size = maxOf(far.x - corner.x, far.y - corner.y) + pt(10f)
        ring.setBounds((corner.x + far.x - size) / 2f, (corner.y + far.y - size) / 2f, size, size)
    }

    private fun ringTexture(): Texture {
        val pixels = Pixmap(128, 128, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        pixels.setColor(Color.WHITE)
        pixels.fillCircle(64, 64, 63)
        pixels.setColor(0f, 0f, 0f, 0f)
        pixels.fillCircle(64, 64, 56)
        return Texture(pixels).also {
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pixels.dispose()
        }
    }

    fun dispose() { ringTexture?.dispose() }

    companion object {
        private val INK2 = Color.valueOf("b7cde0")
        private val INK3 = Color.valueOf("7f9ab2")
        private val FOOD = Color.valueOf("6fd05a")
        private val YELLOW_INK = Color.valueOf("3a2a00")

        private const val CITY = "Tap your city's banner on the map"
        private fun selected(unit: MapUnit?, test: (MapUnit) -> Boolean) = if (unit != null && test(unit)) 1 else 0

        /** Phone steps for the built-in Events.json tasks, keyed by event name. Text reads the portrait HUD:
         *  names appear when a button is held, the city banner opens the city sheet, Science opens technologies. */
        private val TASKS = mapOf(
            "Tutorial Task: [Move unit]" to PhoneTask(listOf(
                Step("Tap one of your units"),
                Step("Tap where it should go, then tap the arrow. Or drag the unit there."),
            )) { _, unit -> selected(unit) { true } },
            "Tutorial Task: [Found city]" to PhoneTask(listOf(
                Step("Tap your Settler"),
                Step("Tap Found city. Hold a button to read its name.", "portrait-action-FoundCity", "portrait-more"),
            )) { _, unit -> selected(unit) { it.hasUnique(UniqueType.FoundCity) } },
            "Tutorial Task: [Enter city screen]" to PhoneTask(listOf(Step(CITY))),
            "Tutorial Task: [Pick technology]" to PhoneTask(listOf(
                Step("Tap the tech button or Science at the top", "portrait-tech", "portrait-stat-Science"),
                Step("Pick a technology"),
                Step("Tap the yellow Research button"),
            )),
            "Tutorial Task: [Pick construction]" to PhoneTask(listOf(
                Step(CITY),
                Step("In Build, tap a unit or building, then Add to queue"),
            )),
            "Tutorial Task: [Pass a turn]" to PhoneTask(listOf(
                Step("Give each unit an order, or tap Skip", "portrait-next"),
                Step("Tap Next to end the turn", "portrait-next"),
            )) { world, _ -> if (world.selectedGameView.civView.dueUnitsCount() > 0) 0 else 1 },
            "Tutorial Task: [Reassign worked tiles]" to PhoneTask(listOf(
                Step(CITY),
                Step("Tap a worked tile's citizen to free it"),
                Step("Tap an empty tile's citizen to work it"),
            )),
            "Tutorial Task: [Meet another civilization]" to PhoneTask(emptyList(),
                "Explore the map until you encounter another civilization!"),
            "Tutorial Task: [Open the options table]" to PhoneTask(listOf(
                Step("Tap the turn button at the top right", "portrait-menu"),
                Step("Tap Options"),
            )),
            "Tutorial Task: [Construct an improvement]" to PhoneTask(listOf(
                Step("Build a Worker in your city"),
                Step("Move it to Plains or Grassland"),
                Step("Tap Construct improvement, then the Farm", "portrait-action-ConstructImprovement", "portrait-more"),
                Step("Leave the Worker there until the Farm is done"),
            )) { _, unit ->
                when {
                    unit == null || !unit.isCivilian() || !unit.cache.hasUniqueToBuildImprovements -> 0
                    unit.getTile().baseTerrain in listOf("Plains", "Grassland") -> 2
                    else -> 1
                }
            },
            "Tutorial Task: [Create a trade route]" to PhoneTask(listOf(
                Step("Build roads from your capital to another city"),
                Step("Or tap Automate on a Worker and let it build them", "portrait-action-Automate"),
            )) { _, unit -> selected(unit) { it.isCivilian() && it.cache.hasUniqueToBuildImprovements } },
            "Tutorial Task: [Conquer a city]" to PhoneTask(listOf(
                Step("Attack an enemy city until its health is low"),
                Step("Move a melee unit into it"),
            )),
            "Tutorial Task: [Move an air unit]" to PhoneTask(listOf(
                Step("Tap an air unit"),
                Step("Tap another city in range, then tap the arrow"),
            )) { _, unit -> selected(unit) { it.baseUnit.isAirUnit() } },
            "Tutorial Task: [See your stats breakdown]" to PhoneTask(listOf(
                Step("Tap your Gold at the top", "portrait-stat-Gold"),
            )),
        )
    }
}
