package com.unciv.ui.screens.worldscreen.worldmap

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Action
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.UncivGame
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.layers.TileLayerUnitSprite
import com.unciv.ui.components.tilegroups.layers.UnitSpriteSlot
import com.unciv.ui.images.ImageGetter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One colored layer of a unit sprite, copied so motion can draw a unit whose slot is gone or belonged to the previous screen. */
internal class SpriteCopy(image: Image) {
    private val drawable: Drawable = image.drawable
    private val tint = image.color.cpy()
    private val x = image.x
    private val y = image.y
    private val width = image.width
    private val height = image.height
    private val originX = image.originX
    private val originY = image.originY
    private val scaleX = image.scaleX
    private val scaleY = image.scaleY

    fun toImage() = Image(drawable).apply {
        setBounds(this@SpriteCopy.x, this@SpriteCopy.y, this@SpriteCopy.width, this@SpriteCopy.height)
        setOrigin(this@SpriteCopy.originX, this@SpriteCopy.originY)
        setScale(this@SpriteCopy.scaleX, this@SpriteCopy.scaleY)
        color = tint.cpy()
        touchable = Touchable.disabled
    }

    companion object {
        fun of(slot: UnitSpriteSlot) = slot.spriteGroup.children.filterIsInstance<Image>().map { SpriteCopy(it) }
    }
}

/** Ring and arc textures drawn once; map effects and the portrait turn HUD share them. */
internal object MotionTextures {
    private val cache = HashMap<String, Texture>()

    /** A 128px ring of [stroke] px covering [sweep] of the circle clockwise from the top. */
    fun ring(stroke: Float, sweep: Float = 1f): Texture = cache.getOrPut("$stroke/$sweep") {
        val size = 128
        val pixels = Pixmap(size, size, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        val radius = size / 2f - stroke / 2f - 1f
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x + .5f - size / 2f
            val dy = y + .5f - size / 2f
            var alpha = (stroke / 2f + .5f - abs(sqrt(dx * dx + dy * dy) - radius)).coerceIn(0f, 1f)
            if (sweep < 1f) {
                val turn = ((atan2(dx, -dy) / (2 * PI)).toFloat() + 1f) % 1f
                alpha *= ((sweep - turn) * 60f).coerceIn(0f, 1f) * (turn * 60f).coerceIn(0f, 1f)
            }
            pixels.drawPixel(x, y, 0xffffff00.toInt() or (alpha * 255f).toInt())
        }
        Texture(pixels).also {
            pixels.dispose()
            it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
    }
}

private fun ease(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)

/**
 * Drives every portrait map motion from one stage action, so a skip, a projection change or leaving the
 * world screen lands every clip on its exact end state and removes every stand-in actor.
 */
internal class PortraitMotion(private val holder: WorldMapHolder) {
    /** [pose] runs every frame from [play] on; its time is negative until [delay] has passed and ends at [length]. */
    abstract inner class Clip(private val delay: Float, val length: Float, private val speed: Float = 1f) {
        private var elapsed = 0f
        val time get() = (elapsed - delay) * speed
        open val unitId get() = -1
        abstract fun pose(t: Float)
        open fun end() {}
        internal fun advance(delta: Float) { elapsed += delta }
    }

    private val clips = ArrayList<Clip>()
    private var driver: Action? = null
    private var stopping = false

    fun isMoving(unitId: Int) = clips.any { it.unitId == unitId }

    fun play(clip: Clip) {
        if (stopping || UncivGame.Current.settings.reduceMotion) return finish(clip)
        clips.add(clip)
        clip.pose(clip.time)
        if (driver == null) {
            val action = object : Action() {
                override fun act(delta: Float) = tick(delta)
            }
            driver = action
            holder.worldScreen.stage.root.addAction(action)
        }
        Gdx.graphics.requestRendering()
    }

    private fun tick(delta: Float): Boolean {
        // A long frame (a screen update) slows the motion down instead of skipping whole hops
        val step = min(delta, 1f / 20f)
        for (clip in clips.toList()) {
            if (clip !in clips) continue
            clip.advance(step)
            if (clip.time >= clip.length) {
                clips.remove(clip)
                finish(clip)
            } else clip.pose(clip.time)
        }
        if (clips.isNotEmpty()) {
            Gdx.graphics.requestRendering()
            return false
        }
        driver = null
        holder.worldScreen.shouldUpdate = true
        return true
    }

    private fun finish(clip: Clip) {
        clip.pose(clip.length)
        clip.end()
    }

    /** Lands every clip on its end state; clips started by those endings finish at once. */
    fun finishAll() {
        if (stopping) return
        stopping = true
        driver?.let { holder.worldScreen.stage.root.removeAction(it) }
        driver = null
        while (clips.isNotEmpty()) {
            val clip = clips.removeAt(0)
            finish(clip)
        }
        stopping = false
    }

    fun finishUnit(unitId: Int) {
        for (clip in clips.filter { it.unitId == unitId }) {
            clips.remove(clip)
            finish(clip)
        }
    }

    private fun unitScale(group: TileGroup) = group.hexagonImageWidth / 86.6f
    private fun groundX(group: TileGroup) = group.x + group.groundCenterX
    private fun groundY(group: TileGroup) = group.y + group.groundCenterY

    /** A stand-in for one unit drawn above the map, while its real sprite on [realTile] stays hidden. */
    abstract inner class GhostClip(delay: Float, length: Float, speed: Float, final override val unitId: Int,
                                   val home: TileGroup, sprite: List<SpriteCopy>, private val realTile: TileGroup?) :
        Clip(delay, length, speed) {
        val ghost = Group().apply {
            isTransform = true
            touchable = Touchable.disabled
            setPosition(home.x, home.y)
            setOrigin(home.groundCenterX, home.groundCenterY)
        }
        private var hidden: Pair<TileLayerUnitSprite, UnitSpriteSlot>? = null

        init {
            setSprite(sprite)
            holder.addActorToTileGroupMap(ghost)
        }

        fun setSprite(sprite: List<SpriteCopy>) {
            ghost.clearChildren()
            for (layer in sprite) ghost.addActor(layer.toImage())
        }

        /** Tile updates may show the real sprite again, so this runs every frame. */
        fun hideReal() {
            ghost.isVisible = !home.strategicView
            val group = realTile ?: return
            val slot = holder.slotOf(group, unitId) ?: return
            slot.spriteGroup.isVisible = false
            hidden = group.layerUnitArt to slot
        }

        fun rest(group: TileGroup) {
            ghost.setPosition(group.x, group.y)
            ghost.setScale(1f)
            ghost.rotation = 0f
        }

        override fun end() {
            ghost.remove()
            hidden?.let { (layer, slot) -> slot.spriteGroup.isVisible = layer.isVisible }
            hidden = null
        }
    }

    /** Hops along [path] ([path] starts on the unit's old tile). A land unit swaps to [endSprite] when it
     *  first lands in the end tile's domain, with a splash on water and dust on land. */
    inner class HopClip(delay: Float, speed: Float, unitId: Int, private val path: List<TileGroup>,
                        sprite: List<SpriteCopy>, private val landUnit: Boolean,
                        private val endSprite: () -> List<SpriteCopy>?, private val perHop: Float) :
        GhostClip(delay, perHop * (path.size - 1), speed, unitId, path.first(), sprite, path.last()) {
        private val pose = HopPose()
        private val swapAt = if (!landUnit || embarked(path.first()) == embarked(path.last())) -1
            else path.indexOfFirst { embarked(it) == embarked(path.last()) }
        private var landed = 0
        private var swapped = false

        private fun embarked(group: TileGroup) = landUnit && !group.tileView.isLand

        override fun pose(t: Float) {
            hideReal()
            val hops = path.size - 1
            if (t < 0f) return rest(home)
            val hop = min(hops - 1, (t / perHop).toInt())
            val q = ((t - hop * perHop) / perHop).coerceIn(0f, 1f)
            val from = path[hop]
            val to = path[hop + 1]
            pose.at(if (t >= length) 1f else q, groundX(to) >= groundX(from))
            ghost.setPosition(from.x + (to.x - from.x) * pose.travel,
                from.y + (to.y - from.y) * pose.travel + pose.lift * 20f * unitScale(from))
            ghost.setScale(pose.scaleX, pose.scaleY)
            ghost.rotation = pose.lean
            if (swapAt > 0 && !swapped && (hop + 1 > swapAt || hop + 1 == swapAt && q >= .5f || t >= length)) {
                swapped = true
                endSprite()?.let { setSprite(it) }
            }
            val nowLanded = if (t >= length) hops else hop
            while (landed < nowLanded) {
                landed++
                val tile = path[landed]
                if (landed == hops || landed == swapAt)
                    play(if (embarked(tile)) Splash(0f, tile) else Dust(0f, tile))
            }
        }
    }

    /** Short-lived particles in map space, added only once their start time comes. */
    abstract inner class Effect(delay: Float, length: Float, private val count: Int) : Clip(delay, length) {
        private val images = ArrayList<Actor>()
        abstract fun make(index: Int): Actor
        abstract fun step(index: Int, actor: Actor, p: Float)
        override fun pose(t: Float) {
            if (t < 0f) return
            if (images.isEmpty()) repeat(count) { images.add(make(it).also { actor ->
                actor.touchable = Touchable.disabled
                holder.addActorToTileGroupMap(actor)
            }) }
            val p = (t / length).coerceIn(0f, 1f)
            for ((index, actor) in images.withIndex()) step(index, actor, p)
        }
        override fun end() {
            for (actor in images) actor.remove()
            images.clear()
        }
        fun circle(color: String, diameter: Float) = ImageGetter.getCircle(Color.valueOf(color), diameter).apply {
            setOrigin(diameter / 2f, diameter / 2f)
        }
        fun place(actor: Actor, x: Float, y: Float) = actor.setPosition(x - actor.width / 2f, y - actor.height / 2f)
    }

    inner class Dust(delay: Float, private val tile: TileGroup) : Effect(delay, .42f, 4) {
        private val u = unitScale(tile)
        override fun make(index: Int) = circle(if (index < 2) "efe6cf" else "e2d6b8", (if (index < 2) 8f else 6f) * u)
        override fun step(index: Int, actor: Actor, p: Float) {
            val side = if (index % 2 == 0) -1f else 1f
            val offset = if (index < 2) 0f else 5f * side
            place(actor, groundX(tile) + side * (7f + 15f * ease(p)) * u + offset * u, groundY(tile) + (3f * p + if (index < 2) 0f else 2f) * u)
            actor.setScale(.6f + .8f * p)
            actor.color.a = 1f - p
        }
    }

    inner class Ring(delay: Float, private val tile: TileGroup, private val tint: Color, length: Float) : Effect(delay, length, 1) {
        private val u = unitScale(tile)
        override fun make(index: Int) = Image(MotionTextures.ring(10f)).apply {
            setSize(40f * u, 16f * u)
            setOrigin(width / 2f, height / 2f)
            color = tint.cpy()
        }
        override fun step(index: Int, actor: Actor, p: Float) {
            place(actor, groundX(tile), groundY(tile))
            actor.setScale(1f + 1.6f * ease(p))
            actor.color.a = 1f - p
        }
    }

    inner class Splash(delay: Float, private val tile: TileGroup) : Effect(delay, .48f, 5) {
        private val u = unitScale(tile)
        init {
            play(Ring(delay, tile, Color.valueOf("e8f7ff"), .7f))
            play(Ring(delay + .16f, tile, Color.valueOf("bfeaff"), .7f))
        }
        override fun make(index: Int) = circle("e8f7ff", 6.4f * u)
        override fun step(index: Int, actor: Actor, p: Float) {
            val angle = PI * (.15 + .7 * index / 4.0)
            place(actor, groundX(tile) - cos(angle).toFloat() * 22f * ease(p) * u,
                groundY(tile) + 4f * u + (sin(angle).toFloat() * 26f * p - 30f * p * p) * u)
            actor.color.a = 1f - p
        }
    }
}
