package com.unciv.ui.screens.worldscreen

import com.unciv.ui.screens.basescreen.portraitCanvasBounds

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.SwipeDownToClose
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.status.NextTurnAction

/** The turn digest is independent of the Next decision queue and leaves the map visible. */
internal class PortraitNotificationDigest(private val world: WorldScreen) : Group() {
    private val ink = Color.valueOf("132435")
    private val muted = Color.valueOf("b7cde0")
    private val panel = Color.valueOf("101f2ff2")
    private var earlier = false
    private var category: NotificationCategory? = null
    private var selected: Notification? = null
    private var limit = 50
    private val openedTurn = world.gameInfo.turns
    private val openedCiv = world.selectedCiv

    init {
        name = "portrait-notification-digest"
        touchable = Touchable.childrenOnly
        world.stage.actors.filterIsInstance<PortraitNotificationDigest>().forEach { it.remove() }
        world.bottomUnitTable.selectUnit()
        world.shouldUpdate = true
        world.stage.addActor(this)
        rebuild()
    }

    override fun remove(): Boolean {
        world.shouldUpdate = true
        return super.remove()
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (!world.isPortrait() || world.selectedCiv !== openedCiv || world.gameInfo.turns != openedTurn) remove()
    }

    private fun events(): Sequence<Pair<Int, Notification>> = if (!earlier)
        openedCiv.notifications.asSequence().map { openedTurn to it }
    else openedCiv.notificationsLog.asReversed().asSequence().flatMap { log ->
        log.notifications.asSequence().map { log.turn to it }
    }

    private fun filtered() = events().filter { category == null || it.second.category == category }

    private fun rounded(tint: Color, radius: Float = 18f): com.badlogic.gdx.scenes.scene2d.utils.Drawable {
        if (radius == 0f) return ImageGetter.getDrawable(ImageGetter.whiteDotLocation).tint(tint)
        val region = ImageGetter.getCircleDrawable().region
        val split = (minOf(region.regionWidth, region.regionHeight) / 2 - 1).coerceAtLeast(1)
        val patch = NinePatch(region, split, split, split, split)
        patch.scale(radius / split, radius / split)
        return NinePatchDrawable(patch).tint(tint).apply {
            minWidth = 0f; minHeight = 0f
            leftWidth = 0f; rightWidth = 0f; topHeight = 0f; bottomHeight = 0f
        }
    }

    private fun button(text: String, active: Boolean = false, action: () -> Unit) = Table().apply {
        touchable = Touchable.enabled
        background = rounded(if (active) Color.WHITE else Color(1f, 1f, 1f, .07f), 13f)
        add(text.tr().toLabel(if (active) ink else muted, 14)).pad(0f, 13f, 0f, 13f)
        onClick(action)
    }

    private fun row(notification: Notification, actionable: Boolean = true) = Table().apply {
        val disc = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(Color.valueOf("eef5fb"))
            val icon = notification.icons.lastOrNull()?.let {
                NotificationIcon.getImage(it, world.gameInfo.ruleset, 30f)
            } ?: ImageGetter.getImage("OtherIcons/Notifications").apply { color = ink }
            add(icon).size(30f)
        }
        add(disc).size(48f).padRight(12f)
        add(notification.text.toLabel(muted, 15, hideIcons = true).apply { wrap = true }).growX().left()
        if (notification.actions.isNotEmpty()) {
            add(ImageGetter.getImage("OtherIcons/ForwardArrow").apply { color = muted }).size(18f).pad(13f)
        }
        pad(8f, 4f, 8f, 4f)
        touchable = Touchable.enabled
        if (actionable) onClick { select(notification) }
    }

    private fun select(notification: Notification) {
        selected = notification
        // A row flies to its location. Its explicit action opens any separate detail screen.
        notification.actions.firstOrNull { it is LocationAction || it is MapUnitAction }?.execute(world)
        rebuild()
    }

    private fun place(actor: Actor, x: Float, y: Float, width: Float, height: Float) {
        actor.setBounds(x, y, width, height)
        addActor(actor)
    }

    private fun rebuild() {
        clearChildren()
        val safe = world.safeAreaBoundsInWorld()
        val canvas = world.portraitCanvasBounds()
        val unit = safe.width / 393f
        setScale(unit)
        setPosition(safe.x, safe.y)
        setSize(393f, safe.height / unit)
        val bottom = (34f - (safe.y - canvas.y) / unit).coerceAtLeast(8f)
        val notification = selected
        if (notification != null) {
            val peek = Table().apply {
                background = rounded(panel, 24f)
                pad(12f)
                add(row(notification, false)).colspan(5).growX().row()
            }
            val entries = filtered().map { it.second }.toList()
            val index = entries.indexOf(notification)
            val prev = button("‹") { if (index > 0) select(entries[index - 1]) }
            val next = button("›") { if (index in 0 until entries.lastIndex) select(entries[index + 1]) }
            if (index <= 0) prev.color.a = .35f
            if (index >= entries.lastIndex) next.color.a = .35f
            peek.add(prev).size(48f).padTop(10f)
            peek.add("${index + 1} / ${entries.size}".toLabel(muted, 14)).minWidth(56f).padTop(10f)
            peek.add(next).size(48f).padTop(10f)
            peek.add(button("List", true) { selected = null; rebuild() }).height(48f).padLeft(8f).padTop(10f)
            val action = notification.actions.firstOrNull { it !is LocationAction && it !is MapUnitAction }
            if (action != null) peek.add(button("Open") { action.execute(world) }).height(48f).padLeft(8f).padTop(10f)
            peek.width = 373f
            peek.pack()
            peek.width = 373f
            place(peek, 10f, bottom + 126f, 373f, peek.prefHeight)
            return
        }

        val sheet = Table().apply {
            touchable = Touchable.enabled
            background = rounded(panel, 26f)
            top()
        }
        sheet.add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .22f) }).size(40f, 4f).padTop(10f).padBottom(6f).row()
        val all = events().toList()
        val visible = all.filter { category == null || it.second.category == category }
        val header = Table().apply {
            val title = Table().left()
            title.add((if (earlier) "Earlier" else "This turn").toLabel(Color.WHITE, 24)).left().row()
            title.add("${visible.size} events".toLabel(muted, 14)).left()
            add(title).growX().left()
            add(button("×") { remove() }).size(48f)
        }
        sheet.add(header).width(365f).pad(8f, 14f, 12f, 14f).row()
        // Grab bar and header are the drag zone; swiping them down closes like ×
        sheet.addListener(SwipeDownToClose(sheet, header) { remove() })
        val filters = Table().left()
        filters.add(button("All", category == null) { category = null; limit = 50; rebuild() }).height(48f).padRight(6f)
        for (cat in all.map { it.second.category }.distinct())
            filters.add(button(cat.name, cat == category) { category = cat; limit = 50; rebuild() }).height(48f).padRight(6f)
        sheet.add(AutoScrollPane(filters).apply { setScrollingDisabled(false, true) }).width(365f).height(56f).padLeft(14f).padRight(14f).row()
        val rows = Table().apply { top(); defaults().growX() }
        var turn: Int? = null
        for ((eventTurn, event) in visible.take(limit)) {
            if (earlier && turn != eventTurn) {
                turn = eventTurn
                rows.add("Turn [$eventTurn]".tr().toLabel(muted, 14)).left().padTop(10f).row()
            }
            rows.add(row(event)).minHeight(66f).row()
            rows.add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .1f) }).height(1f).row()
        }
        if (visible.isEmpty()) rows.add((if (earlier) "No earlier notifications" else "No events this turn").toLabel(muted, 15)).padTop(20f)
        if (visible.size > limit) rows.add(button("Show more") { limit += 50; rebuild() }).height(48f).row()
        sheet.add(AutoScrollPane(rows).apply { setScrollingDisabled(true, false) }).grow().padLeft(14f).padRight(14f).row()
        val footer = Table().apply {
            background = rounded(Color.valueOf("0f2030"), 0f)
            pad(8f, 14f, bottom, 14f)
        }
        footer.add(button("Now", !earlier) { earlier = false; category = null; limit = 50; rebuild() }).height(48f).padRight(6f)
        footer.add(button("Earlier", earlier) { earlier = true; category = null; limit = 50; rebuild() }).height(48f).expandX().left()
        val pending = world.selectedGameView.civView.dueUnitsCount() + NextTurnAction.entries.count {
            it.ordinal in NextTurnAction.PickGreatPerson.ordinal until NextTurnAction.NextUnit.ordinal && it.isChoice(world)
        }
        val next = button("Next  $pending", true) { remove(); world.nextTurnButton.activate() }
        next.background = rounded(Color.valueOf("ffc93c"), 30f)
        footer.add(next).width(120f).height(60f)
        sheet.add(footer).growX().row()
        val topInset = (canvas.y + canvas.height - safe.y - safe.height) / unit
        val sheetHeight = (height - (330f - topInset)).coerceAtLeast(330f)
        place(sheet, 0f, -safe.y / unit, 393f, sheetHeight + safe.y / unit)
    }
}
