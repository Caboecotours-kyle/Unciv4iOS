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
import com.unciv.ui.components.tilegroups.layers.GroundImage
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
        /** Runs once after [end], whether the clip played out, was skipped or was stopped */
        var onEnd: (() -> Unit)? = null
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
        clip.onEnd?.let { clip.onEnd = null; it() }
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

    /** Runs [action] when [unitId]'s last playing clip ends, or at once when it has none. */
    fun afterUnit(unitId: Int, action: () -> Unit) {
        val clip = clips.lastOrNull { it.unitId == unitId } ?: return action()
        val previous = clip.onEnd
        clip.onEnd = { previous?.invoke(); action() }
    }

    fun finishUnit(unitId: Int) {
        for (clip in clips.filter { it.unitId == unitId }) {
            clips.remove(clip)
            finish(clip)
        }
    }

    /** Runs [action] once when its time comes, or at once when motion is skipped. */
    inner class Cue(delay: Float, private val action: () -> Unit) : Clip(delay, 0f) {
        private var done = false
        override fun pose(t: Float) {
            if (t < 0f || done) return
            done = true
            action()
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

    /** Rises out of the ground; with [ring] a yellow ring marks a city that produced it, else it simply fades in. */
    inner class SpawnClip(delay: Float, speed: Float, unitId: Int, tile: TileGroup, sprite: List<SpriteCopy>,
                          private val ring: Boolean) :
        GhostClip(delay, if (ring) .42f else .3f, speed, unitId, tile, sprite, tile) {
        private var ringed = false
        override fun pose(t: Float) {
            hideReal()
            rest(home)
            val p = (t / length).coerceIn(0f, 1f)
            if (t < 0f) { ghost.color.a = 0f; return }
            if (!ring) { ghost.color.a = p; return }
            if (!ringed) { ringed = true; play(Ring(0f, home, Color.valueOf("ffc93c"), .7f)) }
            val rise = ease(p)
            ghost.color.a = (p * 2f).coerceAtMost(1f)
            ghost.y = home.y - 14f * unitScale(home) * (1f - rise)
            ghost.setScale(.5f + .5f * rise + if (p > .8f) .08f * sin((p - .8f) / .2f * PI).toFloat() else 0f,
                .3f + .7f * rise)
        }
    }

    /** Flashes as it is hit, tips over about 78 degrees, fades and leaves a white puff. */
    inner class DefeatClip(delay: Float, speed: Float, tile: TileGroup, sprite: List<SpriteCopy>, unitId: Int = -1) :
        GhostClip(delay, speed = speed, length = .94f, unitId = unitId, home = tile, sprite = sprite, realTile = tile) {
        private val base = ghost.children.map { it.color.cpy() }
        private var puffed = false
        override fun pose(t: Float) {
            hideReal()
            rest(home)
            if (t < 0f) return
            val hit = t / .46f
            val flash = if (hit < 1f) (1f - hit * 1.6f).coerceAtLeast(0f) else 0f
            for ((image, color) in ghost.children.zip(base)) image.color = color.cpy().lerp(Color.WHITE, flash)
            val u = unitScale(home)
            if (hit < 1f) ghost.x += (-8f * (1f - hit) * (1f - hit) + sin(hit * 40f) * 2.5f * (1f - hit)) * u
            val fall = ((t - .42f) / .52f).coerceIn(0f, 1f)
            ghost.rotation = 78f * ease(fall)
            ghost.x -= 10f * u * ease(fall)
            ghost.color.a = 1f - ((fall - .55f) / .45f).coerceIn(0f, 1f)
            if (fall >= .6f && !puffed) {
                puffed = true
                play(Puff(0f, home))
            }
        }
    }

    /** A unit that left the view, or the first half of a jump too long to hop: it fades where it stood. */
    inner class FadeOutClip(delay: Float, speed: Float, tile: TileGroup, sprite: List<SpriteCopy>) :
        GhostClip(delay, .25f, speed, -1, tile, sprite, null) {
        override fun pose(t: Float) {
            hideReal()
            ghost.color.a = 1f - (t / length).coerceIn(0f, 1f)
        }
    }

    /**
     * A founded city: the settler hops in place and sinks, the city rises with dust, the border draws around
     * clockwise and the banner drops in. [territory] are the city's tiles; [settler] is optional.
     */
    inner class SettleClip(delay: Float, speed: Float, private val center: TileGroup, private val territory: List<TileGroup>,
                           settler: List<SpriteCopy>?) : Clip(delay, 2.05f, speed) {
        private val settlerGhost = settler?.let { SettlerGhost(it) }
        private val restAlpha = HashMap<Actor, Float>()
        private val restScale = HashMap<Actor, Pair<Float, Float>>()
        private val restY = HashMap<Actor, Float>()
        private var dusted = false

        private inner class SettlerGhost(sprite: List<SpriteCopy>) :
            GhostClip(0f, 0f, 1f, -1, center, sprite, null) {
            private val hop = HopPose()
            override fun pose(t: Float) {
                hideReal()
                rest(home)
                if (t < .25f) return
                if (t < .67f) {
                    hop.at((t - .25f) / .42f, true)
                    ghost.y += hop.lift * 16f * unitScale(home)
                    ghost.setScale(hop.scaleX, hop.scaleY)
                    ghost.rotation = hop.lean
                    return
                }
                val sink = ((t - .67f) / .26f).coerceIn(0f, 1f)
                ghost.setScale(1f + .3f * sink, 1f - ease(sink))
                ghost.color.a = 1f - sink
            }
        }

        private fun cityImages() = center.layerTerrain.tileBaseImages.filter { it !is GroundImage && it.name?.contains("City center") == true }
        private fun borderActors() = territory.flatMap { it.layerBorders.ownedActors }
        private fun bannerActors() = center.layerCityButton.ownedActors.toList()

        private fun alpha(actor: Actor, value: Float) {
            actor.color.a = restAlpha.getOrPut(actor) { actor.color.a } * value
        }

        override fun pose(t: Float) {
            settlerGhost?.pose(t)
            val u = unitScale(center)
            if (t >= .76f && !dusted) {
                dusted = true
                play(Dust(0f, center))
                play(Ring(0f, center, Color.valueOf("efe6cf"), .7f))
            }
            val rise = ((t - .76f) / .48f).coerceIn(0f, 1f)
            val over = if (rise < 1f) ease(rise) + .12f * sin(rise * PI).toFloat() else 1f
            for (image in cityImages()) {
                alpha(image, (rise * 3f).coerceAtMost(1f))
                val (sx, sy) = restScale.getOrPut(image) { image.scaleX to image.scaleY }
                image.setScale(sx * (.7f + .3f * over), sy * over)
            }
            val sweep = ease(((t - 1.25f) / .7f).coerceIn(0f, 1f))
            val cx = groundX(center)
            val cy = groundY(center)
            for (actor in borderActors()) {
                val turn = ((atan2(actor.x + actor.width / 2f - cx, actor.y + actor.height / 2f - cy) / (2 * PI)).toFloat() + 1f) % 1f
                alpha(actor, ((sweep * 1.1f - turn) * 10f).coerceIn(0f, 1f))
            }
            val drop = ((t - 1.7f) / .32f).coerceIn(0f, 1f)
            for (actor in bannerActors()) {
                alpha(actor, drop)
                actor.y = restY.getOrPut(actor) { actor.y } + 14f * u * (1f - ease(drop))
            }
            if (!Gdx.graphics.isContinuousRendering) Gdx.graphics.requestRendering()
        }

        override fun end() {
            settlerGhost?.end()
            for ((actor, a) in restAlpha) actor.color.a = a
            for ((actor, s) in restScale) actor.setScale(s.first, s.second)
            for ((actor, y) in restY) actor.y = y
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

    inner class Puff(delay: Float, private val tile: TileGroup) : Effect(delay, .56f, 7) {
        private val u = unitScale(tile)
        override fun make(index: Int) = circle("f2f2ee", (10f + 2f * (index % 3)) * u)
        override fun step(index: Int, actor: Actor, p: Float) {
            val angle = 2 * PI * index / 7
            place(actor, groundX(tile) - 8f * u + cos(angle).toFloat() * 20f * ease(p) * u,
                groundY(tile) + (18f - sin(angle).toFloat() * 12f * ease(p) + 10f * p) * u)
            actor.setScale(1.2f - .6f * p)
            actor.color.a = 1f - p
        }
    }

    /** A ranged shot: an arrow on an arc from [from] to [to], turned along its flight. */
    inner class Projectile(delay: Float, private val from: TileGroup, private val to: TileGroup) : Effect(delay, .42f, 1) {
        private val u = unitScale(from)
        override fun make(index: Int) = ImageGetter.getImage("OtherIcons/ArrowRight").apply {
            setSize(16f * u, 10f * u)
            setOrigin(width / 2f, height / 2f)
            color = Color.valueOf("3a2a14")
        }
        override fun step(index: Int, actor: Actor, p: Float) {
            val ax = groundX(from) - 10f * u
            val ay = groundY(from) + 30f * u
            val bx = groundX(to) + 6f * u
            val by = groundY(to) + 26f * u
            val arc = 46f * u
            place(actor, ax + (bx - ax) * p, ay + (by - ay) * p + sin(PI * p).toFloat() * arc)
            actor.rotation = (atan2((by - ay) + arc * PI.toFloat() * cos(PI * p).toFloat(), bx - ax) * 180 / PI).toFloat()
            actor.isVisible = p < .95f
        }
    }
}
