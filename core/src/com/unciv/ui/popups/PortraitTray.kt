package com.unciv.ui.popups

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Value
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.models.UncivSound
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Portrait bottom sheets in the approved direction B "Tray" of mocks/drawers-proposal.html: a deep navy tray whose
 * sections are inset grouped cards, a raised footer layer, 28pt top radius, 40x5 handle, 72pt header and a square
 * bottom edge flush with the screen, the sheet colour filling behind the home indicator.
 *
 * [PortraitTray] is the modal host (a Popup with the mock's scrim); [TraySheet] is the sheet itself with every
 * builder, so a non-modal host (the notifications digest) can hold one too. Sizes are the mock's points on the
 * 393pt canvas times [TraySheet.p]; touch targets never drop under 48 real screen points ([TraySheet.touch]).
 */

/** Direction B colours, straight from the mock's `.dir-b` variables */
object TrayColors {
    val SHEET: Color = Color.valueOf("0c1925")
    val CARD: Color = Color.valueOf("1a2e43")
    val FILL: Color = Color.valueOf("253f58")
    val FOOTER: Color = Color.valueOf("16293d")
    val INK: Color = Color.WHITE
    val INK2: Color = Color.valueOf("a2b9cd")
    val SECTION: Color = Color.valueOf("7f98ae")
    val LINE = Color(1f, 1f, 1f, .08f)
    val HANDLE = Color(1f, 1f, 1f, .28f)
    val DISC: Color = Color.valueOf("eef4f9")
    val DISC_INK: Color = Color.valueOf("132435")
    val CLOSE_INK: Color = Color.valueOf("dce9f4")
    val CHEVRON: Color = Color.valueOf("6a8399")
    val SELECTED = Color(1f, 201f / 255f, 60f / 255f, .1f)
    val SEGMENT_ON: Color = Color.valueOf("34506b")
    val YELLOW: Color = Color.valueOf("ffc93c")
    val YELLOW_INK: Color = Color.valueOf("3a2a00")
    val DANGER_BG = Color(224f / 255f, 72f / 255f, 62f / 255f, .14f)
    val DANGER_INK: Color = Color.valueOf("ff8b80")
    val REASON: Color = Color.valueOf("ff9d8f")
    val LOCK_BG = Color(1f, 201f / 255f, 60f / 255f, .14f)
    val LOCK_INK: Color = Color.valueOf("ffe28b")
    val GOOD_BG = Color(111f / 255f, 208f / 255f, 90f / 255f, .15f)
    val GOOD_INK: Color = Color.valueOf("aff2a5")
    val SWITCH_OFF: Color = Color.valueOf("324d67")
    val TRACK: Color = Color.valueOf("050d15")
    val NAVY: Color = Color.valueOf("132435")
    val SCRIM = Color(5f / 255f, 12f / 255f, 20f / 255f, .35f)
}

/** Textures one sheet draws with, made at device pixels on first use and freed once by [release] */
class TrayArt(private val stage: Stage) {
    private val textures = ArrayList<Texture>()
    private val shapes = HashMap<String, NinePatchDrawable>()
    private val buttons = HashMap<String, Texture>()
    var released = false
        private set

    /** Device pixels per stage unit, so corners and gradients stay sharp on a retina screen */
    private val pixelsPerUnit: Float get() {
        val viewport = stage.viewport
        return viewport.screenWidth / viewport.worldWidth.coerceAtLeast(1f) * Gdx.graphics.backBufferWidth / max(1, Gdx.graphics.width)
    }

    private fun texture(width: Int, height: Int, pixel: (x: Float, y: Float, color: Color) -> Unit): Texture {
        val pixels = Pixmap(width, height, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        val color = Color()
        for (y in 0 until height) for (x in 0 until width) {
            pixel(x + .5f, y + .5f, color)
            pixels.drawPixel(x, y, Color.rgba8888(color))
        }
        return Texture(pixels).also {
            pixels.dispose()
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            textures += it
        }
    }

    /** Coverage of a pixel at ([x],[y]) by a rounded rectangle [width] x [height] with corner [radius]; [topOnly] squares the bottom */
    private fun coverage(x: Float, y: Float, width: Float, height: Float, radius: Float, topOnly: Boolean): Float {
        val bottomRadius = if (topOnly) 0f else radius
        val r = if (y < height / 2f) radius else bottomRadius
        if (r <= 0f) return 1f
        val dx = max(max(r - x, x - (width - r)), 0f)
        val dy = max(max(r - y, y - (height - r)), 0f)
        return (r + .5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
    }

    /** A white rounded shape of [radius] stage units; tint it. It adds no padding to the Table it backs. */
    fun rounded(radius: Float, topOnly: Boolean = false): NinePatchDrawable = shapes.getOrPut("$radius/$topOnly") {
        check(!released) { "TrayArt used after release" }
        val scale = pixelsPerUnit
        val r = (radius * scale).roundToInt().coerceAtLeast(1)
        val size = r * 2 + 2
        val texture = texture(size, size) { x, y, color ->
            color.set(1f, 1f, 1f, coverage(x, y, size.toFloat(), size.toFloat(), r.toFloat(), topOnly))
        }
        val patch = NinePatch(texture, r, r, r, r)
        patch.scale(1f / scale, 1f / scale)
        NinePatchDrawable(patch).apply { zeroPadding() }
    }

    /** The primary button's soft sphere: CSS radial-gradient(130% 190% at 28% 0%, #ffe58f 0, #ffc93c 46%, #eaa916 100%) */
    fun sphere(width: Float, height: Float, radius: Float): Texture {
        val scale = pixelsPerUnit
        val w = (width * scale).roundToInt().coerceIn(2, 2048)
        val h = (height * scale).roundToInt().coerceIn(2, 512)
        return buttons.getOrPut("sphere/$w/$h") {
            val light = Color.valueOf("ffe58f")
            val mid = TrayColors.YELLOW
            val edge = Color.valueOf("eaa916")
            val r = radius * scale
            texture(w, h) { x, y, color ->
                val d = hypot((x - .28f * w) / (1.3f * w), y / (1.9f * h))
                if (d < .46f) color.set(light).lerp(mid, d / .46f) else color.set(mid).lerp(edge, ((d - .46f) / .54f).coerceAtMost(1f))
                color.a = coverage(x, y, w.toFloat(), h.toFloat(), r, false)
            }
        }
    }

    /** CSS box-shadow 0 [blur] rgba(7,17,28,[alpha]) around a [width] x [height] rounded rectangle, with [margin] room each side */
    fun shadow(width: Float, height: Float, radius: Float, blur: Float, alpha: Float, margin: Float): Texture {
        val scale = pixelsPerUnit
        val w = ((width + 2 * margin) * scale).roundToInt().coerceIn(2, 2048)
        val h = ((height + 2 * margin) * scale).roundToInt().coerceIn(2, 512)
        return buttons.getOrPut("shadow/$w/$h/$blur/$alpha") {
            val m = margin * scale
            val r = radius * scale
            val sigma = blur / 2f * scale
            val innerW = w - 2 * m
            val innerH = h - 2 * m
            texture(w, h) { x, y, color ->
                // Signed distance to the rounded rectangle
                val qx = abs(x - w / 2f) - (innerW / 2f - r)
                val qy = abs(y - h / 2f) - (innerH / 2f - r)
                val outside = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
                color.set(7f / 255f, 17f / 255f, 28f / 255f, alpha / (1f + exp(1.702f * outside / sigma)))
            }
        }
    }

    /** A vertical fade, opaque at the bottom row, for the footer's raised edge */
    val fade: Texture by lazy {
        check(!released) { "TrayArt used after release" }
        texture(1, 32) { _, y, color -> color.set(0f, 0f, 0f, (y / 32f).let { it * it }) }
    }

    /** Frees every texture once; safe to call again, and from both close and stage removal */
    fun release() {
        if (released) return
        released = true
        textures.forEach { it.dispose() }
        textures.clear()
        shapes.clear()
        buttons.clear()
    }

    /** GL handles of the live textures, for leak checks */
    fun handles(): List<Int> = textures.map { it.textureObjectHandle }
}

private fun Drawable.zeroPadding() {
    leftWidth = 0f; rightWidth = 0f; topHeight = 0f; bottomHeight = 0f
    minWidth = 0f; minHeight = 0f
}

/** Tint that keeps [TrayArt.rounded]'s zero padding */
fun NinePatchDrawable.tinted(color: Color): NinePatchDrawable = tint(color).apply { zeroPadding() }

/**
 * The sheet: handle, header, optional toolbar, scrolling [body], optional raised footer. It sits flush on the
 * canvas bottom (its background runs behind the home indicator) while its content stays above the safe area.
 * Host it in [PortraitTray], or in any stage-aligned group and call [fit] then [rise].
 *
 * @param maxShare Largest share of the canvas height the sheet may take (it also stops under the status bar)
 * @param minShare Smallest share, so a list does not jump in height when it is filtered
 */
class TraySheet(
    val screen: BaseScreen,
    val art: TrayArt,
    var maxShare: Float = 1f,
    var minShare: Float = 0f,
) : Table() {
    enum class Tone { White, Yellow, Fill, Danger }
    enum class RowState { Normal, Selected, Disabled, Danger }
    enum class ButtonKind { Primary, Secondary, Destructive }

    /** Stage units per mock point: the 393pt canvas across the safe width */
    val p = screen.safeAreaBoundsInWorld().width / 393f
    fun pt(value: Float) = value * p
    fun font(size: Float) = (size * p).roundToInt()
    /** Smallest touch target: 48pt of art, and never under 48 real screen points on a narrow phone */
    val touch = max(pt(48f), touchPoints(screen))
    /** Row height of the mock (64pt), never under [touch] */
    val rowHeight get() = max(pt(64f), touch)

    /** Called when a dismiss has finished moving the sheet away; the host removes itself here */
    var onGone: () -> Unit = {}

    val body = Table().top().left()
    private val top = Table()
    private val headerTable = Table()
    private val toolbarTable = Table()
    private val pane = AutoScrollPane(body).apply {
        setOverscroll(false, false)
        setScrollingDisabled(true, false)
    }
    private var footerLayer: Table? = null
    private val footerRow = Table()
    private val paneCell: Cell<AutoScrollPane>
    private val endCell: Cell<Actor>

    init {
        touchable = Touchable.enabled
        background = art.rounded(pt(28f), topOnly = true).tinted(TrayColors.SHEET)
        top.touchable = Touchable.enabled
        top.add(Image(art.rounded(pt(2.5f)).tinted(TrayColors.HANDLE))).size(pt(40f), pt(5f)).padTop(pt(8f)).row()
        top.add(headerTable).growX().minHeight(pt(72f))
        top.addListener(DragToClose())
        add(top).growX().row()
        add(toolbarTable).growX().row()
        paneCell = add(pane).growX().padLeft(pt(12f)).padRight(pt(12f))
        row()
        @Suppress("UNCHECKED_CAST")
        endCell = add().growX() as Cell<Actor>
    }

    // ---------------- header, toolbar, footer ----------------

    /** Title row of the mock: optional back, optional 52pt lead, title and subtitle, 44pt close */
    fun header(title: String, subtitle: String? = null, lead: Actor? = null, onBack: (() -> Unit)? = null) {
        val text = Table().left()
        text.add(title.toLabel(TrayColors.INK, font(23f), hideIcons = true).apply { wrap = true }).growX().left().row()
        if (subtitle != null)
            text.add(subtitle.toLabel(TrayColors.INK2, font(14.5f), hideIcons = true).apply { wrap = true }).growX().left().padTop(pt(3f))
        val content = Table()
        if (lead != null) content.add(lead).size(pt(52f)).padRight(pt(12f))
        content.add(text).growX().minWidth(0f)
        header(content, onBack)
    }

    /** Any content in the header slot; the close button (and back) stay the kit's */
    fun header(content: Actor, onBack: (() -> Unit)? = null) {
        headerTable.clearChildren()
        val target = max(pt(48f), touch)
        if (onBack != null) headerTable.add(roundButton("OtherIcons/BackArrow", "portrait-sheet-back", onBack)).size(target).padRight(pt(4f))
        headerTable.add(content).growX().minWidth(0f)
        headerTable.add(roundButton("OtherIcons/Close", "portrait-sheet-close") { dismiss() }
            .apply { keyShortcuts.add(KeyCharAndCode.BACK) }).size(target).padLeft(pt(8f))
        headerTable.pad(pt(6f), pt(if (onBack != null) 12f else 26f), pt(8f), pt(16f))
    }

    /** 44pt round button in the header; the header cell makes its target [touch] square */
    private fun roundButton(icon: String, name: String, action: () -> Unit): Table = Table().apply {
        this.name = name
        touchable = Touchable.enabled
        add(Table().apply {
            background = ImageGetter.getCircleDrawable().tint(TrayColors.CARD)
            add(ImageGetter.getImage(icon).apply { color = TrayColors.CLOSE_INK }).size(pt(18f))
        }).size(pt(44f))
        onActivation { action() }
    }

    /** A row under the header that does not scroll with the body (filter chips, a segmented control) */
    fun toolbar(content: Actor?) {
        toolbarTable.clearChildren()
        if (content != null) toolbarTable.add(content).growX().pad(0f, pt(12f), pt(4f), pt(12f))
    }

    /** The raised footer layer; add actions to the returned row. The layer runs under the home indicator. */
    fun footer(): Table {
        if (footerLayer == null) footerLayer = object : Table() {
            override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
                // The mock's 0 -6px 18px shadow, cast up onto the tray
                batch.setColor(1f, 1f, 1f, .35f * color.a * parentAlpha)
                batch.draw(art.fade, x, y + height, width, pt(14f))
                super.drawBackground(batch, parentAlpha, x, y)
            }
        }.apply {
            touchable = Touchable.enabled
            background = art.rounded(pt(22f), topOnly = true).tinted(TrayColors.FOOTER)
            add(footerRow).growX()
        }
        footerRow.clearChildren()
        footerRow.defaults().padRight(pt(10f))
        return footerRow
    }

    fun removeFooter() { footerLayer = null }

    // ---------------- geometry and motion ----------------

    private var restY = 0f
    private var offset = 0f
    private enum class Phase { Rest, Rise, Drag, Settle, Leave, FadeIn, FadeOut }
    private var phase = Phase.Rest
    private var time = 0f
    private var from = 0f
    /** The frame that opens the sheet may carry the time spent building it; motion starts on the next one */
    private var skipDelta = false
    /** Scrim strength 0..1 for the host to draw: follows the rise, the drag and the dismiss */
    var scrim = 1f
        private set
    val isLeaving get() = phase == Phase.Leave || phase == Phase.FadeOut
    private val reduceMotion get() = GUI.getSettings().reduceMotion

    /** Sizes the sheet to its content within the share limits and pins it to the canvas bottom */
    fun fit() {
        val safe = screen.safeAreaBoundsInWorld()
        val canvas = screen.portraitCanvasBounds()
        val inset = (safe.y - canvas.y).coerceAtLeast(0f)
        val left = (safe.x - canvas.x).coerceAtLeast(0f)
        val right = (canvas.x + canvas.width - safe.x - safe.width).coerceAtLeast(0f)
        padLeft(left).padRight(right)
        val footer = footerLayer
        if (footer != null) {
            footerRow.pad(pt(12f), pt(16f), max(pt(12f), inset), pt(16f))
            endCell.setActor<Actor>(footer).growX().padTop(pt(4f)).height(Value.prefHeight)
            paneCell.padBottom(pt(12f))
        } else {
            endCell.setActor<Actor>(null).padTop(0f).height(max(pt(24f), inset + pt(8f)))
            paneCell.padBottom(0f)
        }
        // Wrapped labels report their height at the width they get, so measure at the real widths first
        val inner = canvas.width - left - right
        for ((table, width) in listOfNotNull(top to inner, toolbarTable to inner, body to inner - pt(24f), footer?.to(inner))) {
            table.width = width
            table.validate()
            table.invalidate()
        }
        val chrome = top.prefHeight + toolbarTable.prefHeight + (footer?.let { it.prefHeight + pt(4f) + pt(12f) } ?: max(pt(24f), inset + pt(8f)))
        val maxTop = min(safe.y + safe.height, canvas.y + canvas.height - pt(58f))
        val maxHeight = min(maxTop - canvas.y, canvas.height * maxShare)
        var paneHeight = min(body.prefHeight, maxHeight - chrome).coerceAtLeast(pt(48f))
        if (minShare > 0f) paneHeight = max(paneHeight, min(canvas.height * minShare, maxHeight) - chrome)
        paneCell.height(paneHeight)
        val origin = parent?.localToStageCoordinates(Vector2(0f, 0f)) ?: Vector2()
        restY = canvas.y - origin.y
        setBounds(canvas.x - origin.x, restY - offset, canvas.width, chrome + paneHeight)
        invalidate()
        validate()
    }

    /** Open motion: a spring up from the bottom edge with the scrim fading in, or a short crossfade with reduced motion */
    fun rise() {
        time = 0f
        skipDelta = true
        if (reduceMotion) {
            phase = Phase.FadeIn
            offset = 0f
            color.a = 0f
            scrim = 0f
        } else {
            phase = Phase.Rise
            offset = travel()
            scrim = 0f
        }
        y = restY - offset
    }

    /** Close motion (X, scrim, swipe, back): eases down past the edge, then [onGone] */
    fun dismiss() {
        if (isLeaving) return
        touchable = Touchable.disabled
        time = 0f
        from = offset
        phase = if (reduceMotion) Phase.FadeOut else Phase.Leave
    }

    private fun travel() = height + pt(12f)

    override fun act(delta: Float) {
        super.act(delta)
        if (phase == Phase.Rest || phase == Phase.Drag) return
        if (skipDelta) skipDelta = false else time += delta
        when (phase) {
            Phase.Rise -> {
                offset = travel() * (1f - spring(time))
                scrim = min(1f, time / .24f)
                if (time >= .6f) settle()
            }
            Phase.Settle -> {
                offset = from * (1f - spring(time))
                scrim = 1f - offset / travel()
                if (time >= .6f) settle()
            }
            Phase.Leave -> {
                val k = min(1f, time / .2f)
                offset = from + (travel() - from) * k * k * k
                scrim = (1f - offset / travel()).coerceAtLeast(0f)
                if (k >= 1f) gone()
            }
            Phase.FadeIn -> {
                color.a = min(1f, time / .15f)
                scrim = color.a
                if (time >= .15f) settle()
            }
            Phase.FadeOut -> {
                color.a = (1f - time / .15f).coerceAtLeast(0f)
                scrim = color.a
                if (time >= .15f) gone()
            }
            else -> {}
        }
        y = restY - offset
    }

    private fun settle() {
        phase = Phase.Rest
        offset = 0f
        scrim = 1f
        color.a = 1f
    }

    private fun gone() {
        phase = Phase.Rest
        onGone()
    }

    /** iOS-style spring of the mock: response 0.42s, damping 0.82, settled by 0.6s */
    private fun spring(t: Float): Float {
        if (t >= .6f) return 1f
        val w = 2f * Math.PI.toFloat() / .42f
        val z = .82f
        val wd = w * sqrt(1f - z * z)
        return 1f - exp(-z * w * t) * (cos(wd * t) + z * w / wd * sin(wd * t))
    }

    /** Handle and header follow the finger down; past 72pt or faster than 800pt/s it closes, otherwise it springs back */
    private inner class DragToClose : InputListener() {
        private var startY = 0f
        private var startX = 0f
        private var tracking = false
        private var dragging = false
        private var lastY = 0f
        private var lastTime = 0L
        private var velocity = 0f

        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
            if (pointer != 0 || button != 0 || isLeaving || phase == Phase.Rise || phase == Phase.FadeIn) return false
            startX = event.stageX
            startY = event.stageY
            from = offset
            lastY = startY
            lastTime = System.nanoTime()
            velocity = 0f
            tracking = true
            dragging = false
            return true
        }

        override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
            if (!tracking || pointer != 0) return
            val down = startY - event.stageY
            if (!dragging) {
                val slop = max(pt(10f), touch / 5f)
                val side = abs(event.stageX - startX)
                if (side > slop && side >= down) { tracking = false; return }
                if (down <= slop) return
                dragging = true
                phase = Phase.Drag
                // The drag owns this touch now: no header button activates on release
                event.stage.cancelTouchFocusExcept(this, top)
            }
            val now = System.nanoTime()
            val dt = (now - lastTime) / 1e9f
            if (dt > 0f) velocity = velocity * .4f + (lastY - event.stageY) / dt * .6f
            lastY = event.stageY
            lastTime = now
            offset = (from + down).coerceAtLeast(0f)
            scrim = 1f - offset / travel()
            this@TraySheet.y = restY - offset
        }

        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
            if (pointer != 0 || !tracking) return
            tracking = false
            if (!dragging) return
            dragging = false
            time = 0f
            from = offset
            if (!event.isTouchFocusCancel && (offset > pt(72f) || velocity > pt(800f))) dismiss()
            else phase = Phase.Settle
        }
    }

    // ---------------- builders ----------------

    /** 44pt disc with a dark glyph (white, yellow), a light glyph (fill) or a red one (danger).
     *  Tinting multiplies, so a black image (unit action icons) stays black: pass a white one for fill and danger. */
    fun disc(image: Actor?, tone: Tone = Tone.White, size: Float = 44f, iconSize: Float = 24f, recolor: Boolean = true): Table = Table().apply {
        val (fill, ink) = when (tone) {
            Tone.White -> TrayColors.DISC to TrayColors.DISC_INK
            Tone.Yellow -> TrayColors.YELLOW to TrayColors.DISC_INK
            Tone.Fill -> TrayColors.FILL to TrayColors.CLOSE_INK
            Tone.Danger -> TrayColors.DANGER_BG to TrayColors.DANGER_INK
        }
        background = ImageGetter.getCircleDrawable().tint(fill)
        if (image != null) {
            if (recolor) image.color = ink
            add(image).size(pt(iconSize))
        }
        setSize(pt(size), pt(size))
    }

    /** Section label above a card: "Units", optionally with a quieter count */
    fun section(text: String, extra: String? = null): Table = Table().apply {
        left()
        add(text.toLabel(TrayColors.SECTION, font(13.5f), hideIcons = true)).left()
        if (extra != null) add(extra.toLabel(TrayColors.INK2, font(13.5f), hideIcons = true)).left().padLeft(pt(8f))
        add().growX()
        pad(pt(18f), pt(16f), pt(8f), pt(16f))
    }

    /** One list row: lead disc, title and meta (or the reason it is unavailable), trailing value, actor and chevron.
     *  The whole row is the target when [onTap] is set. */
    fun row(
        title: String,
        meta: String? = null,
        lead: Actor? = null,
        value: String? = null,
        trailing: Actor? = null,
        state: RowState = RowState.Normal,
        reason: String? = null,
        /** null: a chevron exactly when the row is tappable and not selected */
        chevron: Boolean? = null,
        sound: UncivSound = UncivSound.Click,
        /** Keyboard shortcut that activates the row like a tap */
        binding: KeyboardBinding? = null,
        onTap: (() -> Unit)? = null,
    ): Table {
        val row = Table()
        row.add().minHeight(rowHeight).width(0f)
        if (lead != null) {
            if (state == RowState.Disabled) lead.color.a = .5f
            row.add(lead).size(pt(44f)).padRight(pt(14f))
        }
        val titleColor = when (state) {
            RowState.Disabled -> TrayColors.INK2
            RowState.Danger -> TrayColors.DANGER_INK
            else -> TrayColors.INK
        }
        val text = Table().left()
        text.add(title.toLabel(titleColor, font(17f), hideIcons = true).apply { wrap = true }).growX().left().row()
        val second = reason ?: meta
        if (second != null)
            text.add(second.toLabel(if (reason != null) TrayColors.REASON else TrayColors.INK2, font(13.5f), hideIcons = true)
                .apply { wrap = true }).growX().left().padTop(pt(3f))
        row.add(text).growX().minWidth(0f).padTop(pt(9f)).padBottom(pt(9f))
        if (value != null) row.add(value.toLabel(TrayColors.INK2, font(14.5f), hideIcons = true)).padLeft(pt(8f))
        if (trailing != null) row.add(trailing).padLeft(pt(8f))
        if (state == RowState.Selected) {
            row.background = art.rounded(pt(18f)).tinted(TrayColors.SELECTED)
            row.add(Table().apply {
                background = ImageGetter.getCircleDrawable().tint(TrayColors.YELLOW)
                add(ImageGetter.getImage("OtherIcons/Checkmark").apply { color = TrayColors.YELLOW_INK }).size(pt(14f))
            }).size(pt(26f)).padLeft(pt(8f))
        }
        val showChevron = chevron ?: (onTap != null && state != RowState.Selected)
        if (showChevron) row.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = TrayColors.CHEVRON }).size(pt(14f)).padLeft(pt(8f))
        row.pad(0f, pt(14f), 0f, pt(14f))
        if (onTap != null) {
            row.touchable = Touchable.enabled
            if (binding != null) row.onActivation(sound, binding) { onTap() }
            else row.onActivation(sound) { onTap() }
        }
        return row
    }

    /** A row with a switch at its end; tapping anywhere on it flips the switch */
    fun toggleRow(title: String, checked: Boolean, meta: String? = null, lead: Actor? = null, onToggle: (Boolean) -> Unit): Table {
        var on = checked
        val switch = Table()
        fun paint() {
            switch.clearChildren()
            switch.background = art.rounded(pt(15.5f)).tinted(if (on) TrayColors.YELLOW else TrayColors.SWITCH_OFF)
            val knob = Image(ImageGetter.getCircleDrawable())
            if (on) switch.add().growX()
            switch.add(knob).size(pt(27f)).pad(pt(2f))
            if (!on) switch.add().growX()
        }
        paint()
        val row = row(title, meta, lead, trailing = Table().apply { add(switch).size(pt(51f), pt(31f)) }, chevron = false) {
            on = !on
            paint()
            onToggle(on)
        }
        row.name = "portrait-toggle"
        return row
    }

    /** Inset grouped card holding [rows], separated by hairlines that start after the lead disc */
    fun card(rows: List<Actor>, inset: Boolean = true): Table = Table().apply {
        background = art.rounded(pt(18f)).tinted(TrayColors.CARD)
        for ((index, row) in rows.withIndex()) {
            if (index > 0) add(Image(ImageGetter.getWhiteDotDrawable().tint(TrayColors.LINE))).height(max(1f, pt(1f))).growX()
                .padLeft(pt(if (inset) 72f else 14f)).padRight(pt(14f)).row()
            add(row).growX().row()
        }
    }

    /** Stat chip: icon and a bold value on the card colour, 48pt tall */
    fun statChip(icon: Actor?, value: String, onTap: (() -> Unit)? = null): Table = Table().apply {
        background = art.rounded(pt(16f)).tinted(TrayColors.CARD)
        add().minHeight(max(pt(48f), if (onTap != null) touch else 0f)).width(0f)
        if (icon != null) add(icon).size(pt(21f)).padRight(pt(6f))
        add(value.toLabel(TrayColors.INK, font(18f), hideIcons = true))
        pad(0f, pt(12f), 0f, pt(12f))
        if (onTap != null) {
            touchable = Touchable.enabled
            onClick { onTap() }
        }
    }

    /** Stat box: a big value over a caption (unit strength, movement, XP) */
    fun statBox(value: String, caption: String): Table = Table().apply {
        top().left()
        background = art.rounded(pt(16f)).tinted(TrayColors.CARD)
        add(value.toLabel(TrayColors.INK, font(22f), hideIcons = true)).left().row()
        add(caption.toLabel(TrayColors.INK2, font(13f), hideIcons = true).apply { wrap = true }).growX().left()
        pad(pt(8f), pt(14f), pt(8f), pt(14f))
    }

    /** Equal columns of chips or boxes with the mock's 8pt gap */
    fun statGrid(items: List<Actor>, columns: Int = items.size): Table = Table().apply {
        for ((index, item) in items.withIndex()) {
            if (index > 0 && index % columns == 0) row()
            add(item).uniformX().growX().fillY().padLeft(if (index % columns > 0) pt(8f) else 0f).padTop(if (index >= columns) pt(8f) else 0f)
        }
        for (i in items.size % columns until columns) if (items.size % columns != 0) add().uniformX().growX().padLeft(pt(8f))
    }

    /** Segmented control: 42pt segments on a track; each segment's target spans the full [touch] height */
    fun segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Table = Table().apply {
        name = "portrait-segmented"
        background = art.rounded(pt(14f)).tinted(TrayColors.CARD)
        for ((index, text) in items.withIndex()) {
            val on = index == selected
            val pill = Table().apply {
                if (on) background = art.rounded(pt(11f)).tinted(TrayColors.SEGMENT_ON)
                add(text.toLabel(if (on) TrayColors.INK else TrayColors.INK2, font(15f), hideIcons = true))
            }
            val target = Table().apply {
                touchable = Touchable.enabled
                add(pill).grow().pad(pt(3f))
                onClick { if (index != selected) onSelect(index) }
            }
            add(target).uniformX().growX().height(max(pt(48f), touch)).padLeft(if (index > 0) pt(2f) else 0f)
        }
    }

    /** Filter chips: 44pt pills inside [touch]-tall targets, the selected one lit */
    fun chips(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Table = Table().apply {
        left()
        for ((index, text) in items.withIndex()) {
            val on = index == selected
            val pill = Table().apply {
                background = art.rounded(pt(22f)).tinted(if (on) TrayColors.SEGMENT_ON else TrayColors.FILL)
                add(text.toLabel(if (on) TrayColors.INK else TrayColors.INK2, font(15f), hideIcons = true)).pad(0f, pt(18f), 0f, pt(18f))
            }
            val target = Table().apply {
                touchable = Touchable.enabled
                name = "portrait-chip-$index"
                add(pill).height(pt(44f)).minWidth(pt(44f))
                onClick { onSelect(index) }
            }
            add(target).height(max(pt(48f), touch)).minWidth(touch).padRight(pt(8f))
        }
    }

    /** Status pill (locked tech, worked tile): 30pt tall, quiet */
    fun pill(text: String, fill: Color, ink: Color, icon: String? = null): Table = Table().apply {
        background = art.rounded(pt(15f)).tinted(fill)
        if (icon != null) add(ImageGetter.getImage(icon).apply { color = ink }).size(pt(14f)).padRight(pt(5f))
        add(text.toLabel(ink, font(13.5f), hideIcons = true)).height(pt(30f))
        pad(0f, pt(11f), 0f, pt(11f))
    }

    /** 56pt button: yellow soft sphere (primary), quiet card (secondary) or red (destructive) */
    fun button(
        text: String,
        kind: ButtonKind = ButtonKind.Primary,
        icon: String? = null,
        badge: String? = null,
        sound: UncivSound = UncivSound.Click,
        action: () -> Unit,
    ): Table {
        val ink = when (kind) {
            ButtonKind.Primary -> TrayColors.YELLOW_INK
            ButtonKind.Secondary -> TrayColors.INK
            ButtonKind.Destructive -> TrayColors.DANGER_INK
        }
        val button = if (kind == ButtonKind.Primary) SphereButton() else Table().apply {
            background = art.rounded(pt(18f)).tinted(if (kind == ButtonKind.Secondary) TrayColors.CARD else TrayColors.DANGER_BG)
        }
        button.add().minHeight(max(pt(56f), touch)).width(0f)
        if (icon != null) button.add(ImageGetter.getImage(icon).apply { color = ink }).size(pt(20f)).padRight(pt(8f))
        button.add(text.toLabel(ink, font(17f), hideIcons = true))
        if (badge != null) button.add(Table().apply {
            background = art.rounded(pt(13f)).tinted(TrayColors.NAVY)
            add(badge.toLabel(TrayColors.INK, font(14f))).pad(0f, pt(8f), 0f, pt(8f))
        }).minWidth(pt(26f)).height(pt(26f)).padLeft(pt(8f))
        button.pad(0f, pt(20f), 0f, pt(20f))
        button.touchable = Touchable.enabled
        button.onActivation(sound) { action() }
        return button
    }

    /** The primary button: radial light from the top left and a soft centred shadow, drawn at its own size */
    private inner class SphereButton : Table() {
        override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
            if (art.released || width <= 0f || height <= 0f) return
            val margin = pt(12f) * 1.4f
            batch.setColor(1f, 1f, 1f, color.a * parentAlpha)
            batch.draw(art.shadow(width, height, pt(18f), pt(12f), .28f, margin), x - margin, y - margin - pt(4f), width + 2 * margin, height + 2 * margin)
            batch.draw(art.sphere(width, height, pt(18f)), x, y, width, height)
        }
    }

    /** Empty state: a 72pt disc with the icon and one line of text */
    fun emptyState(icon: String, text: String): Table = Table().apply {
        add(Table().apply {
            background = ImageGetter.getCircleDrawable().tint(TrayColors.FILL)
            add(ImageGetter.getImage(icon).apply { color = TrayColors.INK2 }).size(pt(32f))
        }).size(pt(72f)).row()
        add(text.toLabel(TrayColors.INK2, font(16f), Align.center, hideIcons = true).apply { wrap = true }).growX().padTop(pt(12f))
        pad(pt(44f), pt(20f), pt(30f), pt(20f))
    }

    /** Adds [actor] to the body across its full width, [gap] points below the previous item */
    fun addToBody(actor: Actor, gap: Float = 0f): Cell<Actor> = body.add(actor).growX().padTop(pt(gap)).also { body.row() }

    companion object {
        /** 48 real screen points in stage units: the art scales with the 393pt canvas, a fingertip does not */
        fun touchPoints(screen: BaseScreen): Float = 48f * screen.stage.width / Gdx.graphics.width.coerceAtLeast(1)
    }
}

/**
 * Modal host for a [TraySheet]: the mock's scrim over the whole screen, tap behind or back to close, the sheet's
 * textures freed when it closes or its stage goes away. With [tray] false it is a plain [Popup] (landscape callers
 * can share one class) and [sheet] is unused.
 */
open class PortraitTray(
    val screen: BaseScreen,
    val tray: Boolean = true,
    maxShare: Float = 1f,
    minShare: Float = 0f,
    scrollable: Scrollability = Scrollability.WithoutButtons,
) : Popup(screen, if (tray) Scrollability.None else scrollable, if (tray) 1f else 0.9f) {
    val art = TrayArt(screen.stage)
    /** Built on first use, so the landscape Popup never makes sheet textures */
    val sheet by lazy { TraySheet(screen, art, maxShare, minShare) }
    private val white = ImageGetter.getWhiteDotDrawable()

    init {
        closeListeners.add { art.release() }
        if (tray) {
            innerTable.isVisible = false
            innerTable.touchable = Touchable.disabled
            addActor(sheet)
            sheet.onGone = { close() }
            showListeners.add { sheet.fit(); sheet.rise() }
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (event?.target != this@PortraitTray || GUI.getSettings().forbidPopupClickBehindToClose) return
                    dismiss()
                }
            })
        }
    }

    /** Opens the sheet; it rises once it is shown (right away when [force]d or nothing else is open) */
    fun present(force: Boolean = true) = open(force)

    /** Animated close, like X, the scrim or a swipe. [close] stays the immediate one actions use. */
    fun dismiss() = if (tray) sheet.dismiss() else close()

    override fun onVisibleAreaChanged(visibleArea: Rectangle) {
        if (tray) sheet.fit() else super.onVisibleAreaChanged(visibleArea)
    }

    override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
        if (!tray) return super.drawBackground(batch, parentAlpha, x, y)
        val bounds = (stageToShowOn.viewport as? SafeAreaViewport)?.drawingBounds?.takeIf { it.width > 0f }
            ?: Rectangle(0f, 0f, stageToShowOn.width, stageToShowOn.height)
        batch.setColor(TrayColors.SCRIM.r, TrayColors.SCRIM.g, TrayColors.SCRIM.b, TrayColors.SCRIM.a * sheet.scrim * color.a * parentAlpha)
        white.draw(batch, x + bounds.x, y + bounds.y, bounds.width, bounds.height)
    }

    /** Screen disposal clears the stage without [close]; leaving the stage is the one exit every path shares */
    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null) art.release()
    }
}
