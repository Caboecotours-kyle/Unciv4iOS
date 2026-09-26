package com.unciv.ui.screens.worldscreen.bottombar

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.actions.FloatAction
import com.badlogic.gdx.scenes.scene2d.actions.RelativeTemporalAction
import com.badlogic.gdx.scenes.scene2d.actions.SequenceAction
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.map.HexMath
import com.unciv.models.translations.tr
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.components.widgets.ShadowedLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.worldmap.UnitSpritePose
import com.unciv.utils.Concurrency
import com.unciv.view.CombatantView
import com.unciv.view.MapUnitCombatantView


object BattleTableHelpers {
    /** Duration of the red-tint transition, used once per direction */
    private const val flashRedDuration = 0.2f
    /** Duration of the attacker displacement, used once per direction */
    private const val moveActorsDuration = 0.3f
    /** Max distance of the attacker displacement, in world coords */
    private const val moveActorsDisplacement = 10f
    /** If a mod provides attack animations (e.g. swinging a sword), they're played with this duration per frame */
    private const val attackAnimationFrameDuration = 0.1f
    /** Duration a damage number label is visible */
    private const val damageLabelDuration = 1.2f
    /** Size of a damage number label - currently fixed independednt of map zoom */
    private const val damageLabelFontSize = 40
    /** Total distance a damage number label is displaced upwards during that time in world coords */
    private const val damageLabelDisplacement = 90f


    class FlashRedAction(
        start: Float, end: Float,
        private val actorsToOriginalColors: Map<Actor, Color>,
        private val flashColor: Color = Color.RED,
    ) : FloatAction(start, end, flashRedDuration, Interpolation.sine) {
        private fun updateRedPercent(percent: Float) {
            for ((actor, color) in actorsToOriginalColors)
                actor.color = color.cpy().lerp(flashColor, start + percent * (end - start))
        }

        override fun update(percent: Float) = updateRedPercent(percent)
    }

    /** Stage actions render even though the map's sprite layer intentionally does not act. */
    private class RenderedSequenceAction : SequenceAction() {
        var onFinished: (() -> Unit)? = null
        override fun act(delta: Float): Boolean = super.act(delta).also { finished ->
            Gdx.graphics.requestRendering()
            if (finished) {
                val callback = onFinished
                onFinished = null
                callback?.invoke()
            }
        }
    }

    private class PortraitCombatSnapshot(actors: Iterable<Actor>) {
        private data class Rest(val actor: Actor, val parent: Group?, val x: Float, val y: Float,
                                val scaleX: Float, val scaleY: Float, val rotation: Float, val color: Color)
        private val rest = actors.distinct().map {
            Rest(it, it.parent, it.x, it.y, it.scaleX, it.scaleY, it.rotation, it.color.cpy())
        }
        fun restore() {
            for (item in rest) {
                if (item.actor.parent !== item.parent) continue
                item.actor.setPosition(item.x, item.y)
                item.actor.setScale(item.scaleX, item.scaleY)
                item.actor.rotation = item.rotation
                item.actor.color = item.color.cpy()
            }
        }
    }

    private class PortraitAttackPoseAction(actors: List<Image>, private val ranged: Boolean,
                                           private val returning: Boolean) : TemporalAction(moveActorsDuration) {
        private val poses = actors.groupBy { it.parent }.values.map { UnitSpritePose(it) }

        override fun update(percent: Float) {
            val wave = kotlin.math.sin((percent * Math.PI).toFloat())
            val rotation = when {
                ranged && !returning -> -5f * wave
                ranged -> 5f * wave
                !returning -> 13f * (percent - 0.35f)
                else -> 8.5f * (1f - percent)
            }
            val scaleX = if (ranged) 1f - 0.04f * wave else 1f + 0.06f * wave
            val scaleY = if (ranged) 1f + 0.03f * wave else 1f - 0.07f * wave
            for (pose in poses) pose.apply(scaleX, scaleY, rotation)
        }

        override fun end() { for (pose in poses) pose.restorePose() }
    }

    private class PortraitHitPoseAction(actors: List<Image>) : TemporalAction(moveActorsDuration * 2f) {
        private val poses = actors.groupBy { it.parent }.values.map { UnitSpritePose(it) }
        override fun update(percent: Float) {
            val recoil = (1f - percent) * kotlin.math.sin((percent * Math.PI * 2).toFloat())
            for (pose in poses) pose.apply(1f + 0.06f * recoil, 1f - 0.10f * recoil, 6f * recoil)
        }
        override fun end() { for (pose in poses) pose.restorePose() }
    }


    class MoveActorsAction(
        private val actorsToMove: List<Actor>,
        private val movementVector: Vector2
    ) : RelativeTemporalAction() {
        init {
            duration = moveActorsDuration
            interpolation = Interpolation.sine
        }
        override fun updateRelative(percentDelta: Float) {
            for (actor in actorsToMove) {
                actor.moveBy(movementVector.x * percentDelta, movementVector.y * percentDelta)
            }
        }
    }


    class AttackAnimationAction(
        private val attacker: CombatantView,
        defenderActors: List<Actor>,
        private val currentTileSetStrings: TileSetStrings
    ): SequenceAction() {
        private val activeFrames = ArrayList<Image>()
        init {
            if (defenderActors.any()) {
                val attackAnimationLocation = getAttackAnimationLocation()
                if (attackAnimationLocation != null) {
                    var i = 1
                    while (ImageGetter.imageExists(attackAnimationLocation + i)) {
                        val image = ImageGetter.getImage(attackAnimationLocation + i)

                        val defenderParentGroup = defenderActors.first().parent
                        addAction(Actions.run {
                            defenderParentGroup.addActor(image)
                            activeFrames.add(image)
                        })
                        addAction(Actions.delay(attackAnimationFrameDuration))
                        addAction(Actions.run {
                            image.remove()
                            activeFrames.remove(image)
                        })
                        i++
                    }
                }
            }
        }

        fun cancel() {
            for (image in activeFrames) image.remove()
            activeFrames.clear()
        }

        private fun getAttackAnimationLocation(): String? {
            fun TileSetStrings.getLocation(name: String) = getString(unitsLocation, name, "-attack-")

            if (attacker is MapUnitCombatantView) {
                val unitSpecificAttackAnimationLocation = currentTileSetStrings.getLocation(attacker.getCombatantName())
                if (ImageGetter.imageExists(unitSpecificAttackAnimationLocation + "1"))
                    return unitSpecificAttackAnimationLocation
            }

            val unitTypeAttackAnimationLocation = currentTileSetStrings.getLocation(attacker.getUnitType().name)
            if (ImageGetter.imageExists(unitTypeAttackAnimationLocation + "1"))
                return unitTypeAttackAnimationLocation
            return null
        }
    }


    /** The animation for the Damage labels */
    private class DamageLabelAnimation(actor: WidgetGroup, private val portrait: Boolean,
                                       private val onFinished: () -> Unit) :
        TemporalAction(if (portrait) 0.75f else damageLabelDuration) {
        val startX = actor.x
        val startY = actor.y

        /* A tested version with smooth scale-out in addition to the alpha fade
        val width = actor.width
        val height = actor.height
        init {
            actor.isTransform = true
        }
        override fun update(percent: Float) {
            actor.color.a = Interpolation.fade.apply(1f - percent)
            val scale = Interpolation.smooth.apply(1f - percent)
            actor.setScale(scale)
            val x = startX + (1f - scale) * width / 2
            val y = startY + (1f - scale) * height / 2 +
                    Interpolation.smooth.apply(percent) * damageLabelDisplacement
            actor.setPosition(x, y)
        }
        */

        override fun update(percent: Float) {
            actor.color.a = Interpolation.fade.apply(1f - percent)
            actor.setPosition(startX, startY + percent * if (portrait) 32f else damageLabelDisplacement)
            if (portrait) Gdx.graphics.requestRendering()
        }
        override fun end() {
            actor.remove()
            onFinished()
        }
    }

    fun WorldScreen.battleAnimationDeferred(
        attacker: CombatantView, damageToAttacker: Int,
        defender: CombatantView, damageToDefender: Int
    ){
        // This ensures that we schedule the animation to happen AFTER the worldscreen.update(),
        //    where the spriteGroup of the attacker is created on the tile it moves to
        Concurrency.runOnGLThread { battleAnimation(attacker, damageToAttacker, defender, damageToDefender) }
    }

    private fun WorldScreen.battleAnimation(
        attacker: CombatantView, damageToAttacker: Int,
        defender: CombatantView, damageToDefender: Int
    ) {
        val portrait = mapHolder.currentTileSetStrings.projection.tilted
        if (portrait) mapHolder.preparePortraitCombat()
        val defenderStillEnemy = defender.getCivInfo() != attacker.getCivInfo()
        fun getMapActorsForCombatant(combatant: CombatantView): Sequence<Actor> =
            sequence {
                val tileGroup = mapHolder.tileGroups[combatant.getTile()] ?: return@sequence
                if (combatant.isCity()) {
                    val icon = tileGroup.layerImprovement.improvementIcon
                    if (icon != null) yield (icon)
                } else if (combatant is MapUnitCombatantView && !combatant.getUnitView().isAirUnit()) {
                    val slot = tileGroup.layerUnitArt.getSpriteSlot(combatant.getUnitView())
                    if (slot != null) yieldAll(slot.spriteGroup.children)
                }
            }


        val actorsToFlashRed =
                sequence {
                    if (damageToDefender != 0 && (!portrait || defenderStillEnemy && !defender.isDefeated()))
                        yieldAll(getMapActorsForCombatant(defender))
                    if (damageToAttacker != 0) yieldAll(getMapActorsForCombatant(attacker))
                }.associateWith { it.color.cpy() }

        val actorsToMove = getMapActorsForCombatant(attacker).toList()
        val ranged = attacker.isRanged()
        val defenderActors = if (portrait && damageToDefender != 0 && defenderStillEnemy &&
            defender is MapUnitCombatantView && !defender.isDefeated())
            getMapActorsForCombatant(defender).toList() else emptyList()

        val attackVectorHexCoords = defender.getTile().position().minus(attacker.getTile().position())
        val attackVectorWorldCoords = mapHolder.currentTileSetStrings.projection.project(HexMath.hex2WorldCoords(attackVectorHexCoords))
            .nor()  // normalize vector to length of "1"
            .scl(if (!portrait) moveActorsDisplacement else if (attacker.isCity()) 0f
                else if (ranged) 3.5f else 14f)

        val attackerGroup = mapHolder.tileGroups[attacker.getTile()]!!
        val defenderGroup = mapHolder.tileGroups[defender.getTile()]!!
        val hideDefenderDamage = defender.isDefeated() &&
                attacker.getTile().position() == defender.getTile().position()

        val sequence = if (portrait) RenderedSequenceAction() else Actions.sequence()
        val portraitSequence = sequence as? RenderedSequenceAction
        val labels = ArrayList<WidgetGroup>()
        val snapshot = if (portrait) PortraitCombatSnapshot(actorsToMove + defenderActors + actorsToFlashRed.keys) else null
        var sequenceFinished = false
        lateinit var cancelCombat: () -> Unit
        fun completeCombat() {
            if (!portrait || !sequenceFinished || labels.isNotEmpty()) return
            snapshot?.restore()
            mapHolder.finishPortraitCombat(cancelCombat)
        }
        fun addDamageLabel(damage: Int, target: Actor) {
            createDamageLabel(damage, target, portrait) { label ->
                labels.remove(label)
                completeCombat()
            }?.let { labels.add(it) }
        }
        val attackerFrames = AttackAnimationAction(attacker,
            if (damageToDefender != 0 && (!portrait || defenderStillEnemy && !defender.isDefeated()))
                getMapActorsForCombatant(defender).toList() else listOf(),
            mapHolder.currentTileSetStrings)
        val defenderFrames = AttackAnimationAction(defender,
            if (damageToAttacker != 0) getMapActorsForCombatant(attacker).toList() else listOf(),
            mapHolder.currentTileSetStrings)
        sequence.addAction(
            Actions.parallel(
                MoveActorsAction(actorsToMove, attackVectorWorldCoords),
                *if (portrait && attacker is MapUnitCombatantView)
                    arrayOf(PortraitAttackPoseAction(actorsToMove.filterIsInstance<Image>(), ranged, false))
                else emptyArray()
            )
        )
        sequence.addAction(
                Actions.run {
                    addDamageLabel(damageToAttacker, attackerGroup)
                    if (!hideDefenderDamage)
                        addDamageLabel(damageToDefender, defenderGroup)
                }
        )
        sequence.addAction(
                Actions.parallel( // While the unit is moving back to its normal position, we flash the damages on both units
                    MoveActorsAction(actorsToMove, attackVectorWorldCoords.cpy().scl(-1f)),
                    *if (portrait && attacker is MapUnitCombatantView)
                        arrayOf(PortraitAttackPoseAction(actorsToMove.filterIsInstance<Image>(), ranged, true))
                    else emptyArray(),
                    *if (portrait && defenderActors.isNotEmpty())
                        arrayOf(
                            Actions.sequence(
                                MoveActorsAction(defenderActors, attackVectorWorldCoords.cpy().nor().scl(5f)),
                                MoveActorsAction(defenderActors, attackVectorWorldCoords.cpy().nor().scl(-5f)),
                            ),
                            PortraitHitPoseAction(defenderActors.filterIsInstance<Image>()),
                        )
                    else emptyArray(),
                    attackerFrames,
                    defenderFrames,
                    Actions.sequence(
                        FlashRedAction(0f,1f, actorsToFlashRed, if (portrait) Color.WHITE else Color.RED),
                        FlashRedAction(1f,0f, actorsToFlashRed, if (portrait) Color.WHITE else Color.RED)
                    )
                )
        )
        if (portrait) {
            cancelCombat = {
                portraitSequence?.onFinished = null
                stage.root.removeAction(sequence)
                for (label in labels) label.remove()
                labels.clear()
                attackerFrames.cancel()
                defenderFrames.cancel()
                snapshot?.restore()
                mapHolder.finishPortraitCombat(cancelCombat)
            }
            portraitSequence?.onFinished = {
                sequenceFinished = true
                completeCombat()
            }
            mapHolder.beginPortraitCombat(cancelCombat)
        }
        stage.addAction(sequence)
        if (portrait) Gdx.graphics.requestRendering()
    }

    private fun createDamageLabel(damage: Int, target: Actor, portrait: Boolean,
                                  onFinished: (WidgetGroup) -> Unit): WidgetGroup? {
        if (damage == 0) return null

        val container = ShadowedLabel((-damage).tr(), if (portrait) 22 else damageLabelFontSize, Color.RED)
        val targetRight = target.run { localToStageCoordinates(Vector2(width, height * 0.5f)) }
        container.setPosition(targetRight.x, targetRight.y, Align.center)
        target.stage.addActor(container)

        container.addAction(DamageLabelAnimation(container, portrait) { onFinished(container) })
        return container
    }

    fun getHealthBar(maxHealth: Int, currentHealth: Int, maxRemainingHealth: Int, minRemainingHealth: Int, forDefender: Boolean = false): Table {
        val healthBar = Table()
        val totalWidth = 120f
        fun addHealthToBar(image: Image, amount: Int) {
            val width = totalWidth * amount / maxHealth
            healthBar.add(image).size(width.coerceIn(0f, totalWidth),4f)
        }

        fun animateHealth(health: Image, healthDecreaseWidth: Float, move: Float) {
            health.addAction(Actions.sequence(
                Actions.sizeBy(healthDecreaseWidth, 0f),
                Actions.sizeBy(-healthDecreaseWidth, 0f, 0.5f)
            ))
            health.addAction(Actions.sequence(
                Actions.moveBy(-move, 0f),
                Actions.moveBy(move, 0f, 0.5f)
            ))
        }
        
        val damagedHealth = ImageGetter.getDot(Color.FIREBRICK)
        val remainingHealthDot = ImageGetter.getDot(Color.GREEN)
        val maybeDamagedHealth = ImageGetter.getDot(Color.ORANGE)
        val missingHealth = ImageGetter.getDot(ImageGetter.CHARCOAL)
        if (UncivGame.Current.settings.continuousRendering) {
            maybeDamagedHealth.addAction(Actions.forever(Actions.sequence(
                Actions.color(Color.FIREBRICK, 0.7f),
                Actions.color(Color.ORANGE, 0.7f)
            )))
        }
        
        val healthDecreaseWidth = (currentHealth - minRemainingHealth) * totalWidth / 100 // Used for animation only
        if (forDefender) {
            addHealthToBar(missingHealth, maxHealth - currentHealth)
            addHealthToBar(damagedHealth, currentHealth - maxRemainingHealth)
            addHealthToBar(maybeDamagedHealth, maxRemainingHealth - minRemainingHealth)
            addHealthToBar(remainingHealthDot, minRemainingHealth)

            remainingHealthDot.toFront()
            animateHealth(remainingHealthDot, healthDecreaseWidth, healthDecreaseWidth)
        }
        else {
            addHealthToBar(remainingHealthDot, minRemainingHealth)
            addHealthToBar(maybeDamagedHealth, maxRemainingHealth - minRemainingHealth)
            addHealthToBar(damagedHealth, currentHealth - maxRemainingHealth)
            addHealthToBar(missingHealth, maxHealth - currentHealth)

            remainingHealthDot.toFront()
            animateHealth(remainingHealthDot, healthDecreaseWidth, 0f)
        }
        healthBar.pack()
        return healthBar
    }
}
