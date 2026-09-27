package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.MapUnitAction
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.ui.components.input.activate
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.TrayArt
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.screens.worldscreen.status.NextTurnAction

/** The turn digest is independent of the Next decision queue and leaves the map visible: a tray sheet without a scrim,
 *  so the map above it and the HUD stay live. */
internal class PortraitNotificationDigest(private val world: WorldScreen) : Group() {
    private var earlier = false
    private var category: NotificationCategory? = null
    private var selected: Notification? = null
    private var limit = 50
    private val openedTurn = world.gameInfo.turns
    private val openedCiv = world.selectedCiv
    private val art = TrayArt(world.stage)
    private val sheet = TraySheet(world, art)

    init {
        name = "portrait-notification-digest"
        touchable = Touchable.childrenOnly
        world.stage.actors.filterIsInstance<PortraitNotificationDigest>().forEach { it.remove() }
        world.bottomUnitTable.selectUnit()
        world.shouldUpdate = true
        setBounds(0f, 0f, world.stage.width, world.stage.height)
        world.stage.addActor(this)
        addActor(sheet)
        sheet.onGone = { remove() }
        rebuild()
        sheet.rise()
    }

    override fun remove(): Boolean {
        world.shouldUpdate = true
        return super.remove()
    }

    /** Leaving the stage (close, Next, a replaced screen) frees the sheet's textures once */
    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null) art.release()
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

    /** The notification's own icon on a white disc, in its own colours */
    private fun row(notification: Notification, actionable: Boolean = true): Table {
        val icon = notification.icons.lastOrNull()?.let { NotificationIcon.getImage(it, world.gameInfo.ruleset, sheet.pt(30f)) }
        val disc = if (icon != null) sheet.disc(icon, recolor = false, iconSize = 30f)
            else sheet.disc(ImageGetter.getImage("OtherIcons/Notifications"), iconSize = 26f)
        return sheet.row(notification.text, lead = disc, chevron = actionable && notification.actions.isNotEmpty(),
            onTap = if (actionable) ({ select(notification) }) else null)
    }

    private fun select(notification: Notification) {
        selected = notification
        // A row flies to its location. Its explicit action opens any separate detail screen.
        notification.actions.firstOrNull { it is LocationAction || it is MapUnitAction }?.execute(world)
        rebuild()
    }

    private fun pending() = world.selectedGameView.civView.dueUnitsCount() + NextTurnAction.entries.count {
        it.ordinal in NextTurnAction.PickGreatPerson.ordinal until NextTurnAction.NextUnit.ordinal && it.isChoice(world)
    }

    private fun nextButton() = sheet.button("Next", badge = pending().toString()) { remove(); world.nextTurnButton.activate() }
        .apply { name = "portrait-digest-next" }

    private fun rebuild() {
        sheet.body.clear()
        val notification = selected
        if (notification != null) peek(notification) else list()
        sheet.fit()
    }

    /** One notification after flying to it: short, so the place it shows stays in view */
    private fun peek(notification: Notification) {
        sheet.minShare = 0f
        val entries = filtered().map { it.second }.toList()
        val index = entries.indexOf(notification)
        sheet.header(if (earlier) "Earlier" else "This turn", "${index + 1} / ${entries.size}")
        sheet.toolbar(null)
        sheet.addToBody(sheet.card(listOf(row(notification, false))), 4f)
        val action = notification.actions.firstOrNull { it !is LocationAction && it !is MapUnitAction }
        if (action != null) sheet.addToBody(sheet.button("Open", TraySheet.ButtonKind.Secondary) { action.execute(world) }
            .apply { name = "portrait-digest-open" }, 8f)
        val footer = sheet.footer()
        val prev = sheet.button("‹", TraySheet.ButtonKind.Secondary) { if (index > 0) select(entries[index - 1]) }
        val next = sheet.button("›", TraySheet.ButtonKind.Secondary) { if (index in 0 until entries.lastIndex) select(entries[index + 1]) }
        if (index <= 0) prev.color.a = .35f
        if (index >= entries.lastIndex) next.color.a = .35f
        footer.add(prev).width(sheet.touch.coerceAtLeast(sheet.pt(56f)))
        footer.add(next).width(sheet.touch.coerceAtLeast(sheet.pt(56f)))
        footer.add(sheet.button("List", TraySheet.ButtonKind.Secondary) { selected = null; rebuild() }.apply { name = "portrait-digest-list" }).growX()
        footer.add(nextButton()).padRight(0f)
    }

    /** This turn's (or earlier) notifications with category chips, Now / Earlier and Next under the thumb */
    private fun list() {
        sheet.minShare = .54f
        val all = events().toList()
        val visible = all.filter { category == null || it.second.category == category }
        sheet.header(if (earlier) "Earlier" else "This turn", "${visible.size} events")
        val categories = all.map { it.second.category }.distinct()
        val chips = sheet.chips(listOf("All") + categories.map { it.name }, category?.let { categories.indexOf(it) + 1 } ?: 0) { index ->
            category = if (index == 0) null else categories[index - 1]
            limit = 50
            rebuild()
        }
        sheet.toolbar(AutoScrollPane(chips).apply { setScrollingDisabled(false, true); setOverscroll(false, false) })

        var turn: Int? = null
        var rows = ArrayList<Table>()
        fun flush() {
            if (rows.isNotEmpty()) sheet.addToBody(sheet.card(rows), 4f)
            rows = ArrayList()
        }
        for ((eventTurn, event) in visible.take(limit)) {
            if (earlier && turn != eventTurn) {
                flush()
                turn = eventTurn
                sheet.addToBody(sheet.section("Turn [$eventTurn]"))
            }
            rows += row(event)
        }
        flush()
        if (visible.isEmpty()) sheet.addToBody(sheet.emptyState("OtherIcons/Notifications",
            if (earlier) "No earlier notifications" else "No events this turn"))
        if (visible.size > limit) sheet.addToBody(sheet.button("Show more", TraySheet.ButtonKind.Secondary) { limit += 50; rebuild() }, 8f)

        val footer = sheet.footer()
        footer.add(sheet.segmented(listOf("Now", "Earlier"), if (earlier) 1 else 0) { index ->
            earlier = index == 1
            category = null
            limit = 50
            rebuild()
        }).width(sheet.pt(176f))
        footer.add(nextButton()).growX().padRight(0f)
    }
}
