package com.unciv.ui.components.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import kotlin.math.abs

/**
 * Portrait sheets: dragging the top of [sheet] down (its grab bar and [header], a direct child) moves the sheet with
 * the finger; past 80pt it slides away and runs [close], the same callback as the sheet's ×. Anything shorter springs back.
 * Drags that start below [header], sideways drags and a second finger never close, so body scrolling keeps working.
 * Add it to [sheet]. Same feel as the city drawer.
 */
class SwipeDownToClose(private val sheet: Actor, private val header: Actor, private val close: () -> Unit) : InputListener() {
    private val slop = 10f
    private val threshold = 80f
    private val start = Vector2()
    private val point = Vector2()
    private var restY = 0f
    private var tracking = false
    private var dragging = false

    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (tracking) { restore(); return false }
        if (pointer != 0 || button != 0 || y < header.y || sheet.hasActions()) return false
        inParent(event, start)
        restY = sheet.y
        tracking = true
        dragging = false
        return true
    }

    override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
        if (!tracking || pointer != 0) return
        if (Gdx.input.isTouched(1)) { restore(); return }
        inParent(event, point)
        val dx = point.x - start.x
        val down = start.y - point.y
        if (!dragging) {
            val slopLocal = slop * sheet.scaleY
            if (abs(dx) > slopLocal && abs(dx) >= down) { tracking = false; return }
            if (down <= slopLocal || down < abs(dx)) return
            dragging = true
            // The drag owns this touch now: no header button activates on release
            event.stage.cancelTouchFocusExcept(this, sheet)
        }
        sheet.y = restY - down.coerceAtLeast(0f)
    }

    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        if (!tracking || pointer != 0) return
        inParent(event, point)
        val closes = dragging && !event.isTouchFocusCancel && !Gdx.input.isTouched(1) && start.y - point.y >= threshold * sheet.scaleY
        if (!closes) { restore(); return }
        tracking = false
        dragging = false
        sheet.touchable = Touchable.disabled
        sheet.addAction(Actions.sequence(
            Actions.moveTo(sheet.x, restY - sheet.height * sheet.scaleY, .15f, Interpolation.fastSlow),
            Actions.run { close() }))
    }

    /** The sheet moves while dragged, so the finger is measured in its parent's coordinates */
    private fun inParent(event: InputEvent, out: Vector2) {
        out.set(event.stageX, event.stageY)
        sheet.parent?.stageToLocalCoordinates(out)
    }

    private fun restore() {
        if (dragging && sheet.y != restY)
            sheet.addAction(Actions.moveTo(sheet.x, restY, .12f, Interpolation.fastSlow))
        tracking = false
        dragging = false
    }
}
