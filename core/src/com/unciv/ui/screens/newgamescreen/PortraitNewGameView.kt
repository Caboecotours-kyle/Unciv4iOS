package com.unciv.ui.screens.newgamescreen

import com.unciv.ui.screens.basescreen.portraitCanvasBounds

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.unciv.Constants
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.worldscreen.BackgroundActor
import com.unciv.ui.components.input.onClick
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The approved leader-card layout. Its controls read and change the same setup used by the legacy pickers. */
internal class PortraitNewGameView(
    private val screen: NewGameScreen,
    private val gameOptions: GameOptionsTable,
    private val mapOptions: MapOptionsTable,
    private val players: PlayerPickerTable,
    private val startGame: () -> Unit,
    private val closeScreen: () -> Unit,
) : Group(), Disposable {
    private val drawingBounds = screen.portraitCanvasBounds()
    private val unit = min(drawingBounds.width / 393f, drawingBounds.height / 852f)
    private val inset = (drawingBounds.width - 393f * unit) / 2f
    private val heightInPixels = drawingBounds.height / unit
    private val panelTextures = mutableListOf<Texture>()
    private val panels = mutableMapOf<String, NinePatchDrawable>()
    private val thumbTextures = mutableMapOf<String, Texture>()
    private var heroTexture: Texture? = null
    private var heroKey: String? = null
    private var glowTexture: Texture? = null
    private var lastState = ""
    private var startButton: Table? = null
    private var stripScroll: AutoScrollPane? = null

    init {
        setBounds(drawingBounds.x, drawingBounds.y, drawingBounds.width, drawingBounds.height)
        refresh(force = true)
    }

    fun refresh(force: Boolean = false) {
        val setup = screen.gameSetupInfo
        val game = setup.gameParameters
        val map = setup.mapParameters
        val current = listOf(
            game.players.joinToString { "${it.playerType}:${it.chosenCiv}" },
            map.mapSize.name, map.type, map.shape, map.mapResources,
            game.difficulty, game.speed, game.startingEra, game.numberOfCityStates.toString(),
            game.randomNumberOfPlayers.toString(), game.minNumberOfPlayers.toString(), game.maxNumberOfPlayers.toString(),
            game.victoryTypes.joinToString(), game.mods.joinToString(), game.baseRuleset,
        ).joinToString("|")
        if (!force && current == lastState) {
            startButton?.touchable = if (screen.rightSideButton.isDisabled) Touchable.disabled else Touchable.enabled
            return
        }
        lastState = current
        val previousScroll = stripScroll?.scrollX ?: 0f
        clearChildren()

        val human = game.players.firstOrNull { it.playerType == PlayerType.Human }
        val nation = human?.chosenCiv?.let { screen.ruleset.nations[it] }
        addBackground(nation)
        addChrome()
        addLeader(nation, human?.chosenCiv ?: Constants.random)
        addStrip(human?.chosenCiv ?: Constants.random, previousScroll)
        addRecipe()
        addFooter()
    }

    private fun addBackground(nation: Nation?) {
        addAt(ImageGetter.getWhiteDot().apply { color = Color.valueOf("122536"); touchable = Touchable.disabled },
            0f, 0f, width / unit, heightInPixels)
        val glowColor = nation?.getOuterColor() ?: Color.valueOf("2a4866")
        val pixels = Pixmap(64, 72, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val dx = (x - 31.5f) / 38f
            val dy = (y - 20f) / 48f
            val strength = (1f - dx * dx - dy * dy).coerceAtLeast(0f) * .55f
            val fade = ((pixels.height - y).toFloat() / pixels.height).coerceIn(0f, 1f)
            val color = Color(glowColor.r, glowColor.g, glowColor.b, strength * fade)
            pixels.drawPixel(x, y, Color.rgba8888(color))
        }
        glowTexture?.dispose()
        val glow = Texture(pixels).apply { setFilter(TextureFilter.Linear, TextureFilter.Linear) }
        glowTexture = glow
        pixels.dispose()
        addAt(Image(TextureRegionDrawable(TextureRegion(glow))).apply { touchable = Touchable.disabled },
            0f, 0f, 393f, 470f)
    }

    private fun addChrome() {
        val close = iconButton("OtherIcons/Close", 48f, Color(1f, 1f, 1f, .09f)) { closeScreen() }
        addAt(close, 14f, 52f, 48f, 48f)
        val previous = iconButton("OtherIcons/BackArrow", 52f, Color(16f / 255f, 31f / 255f, 47f / 255f, .9f)) {
            stepLeader(-1)
        }
        addAt(previous, 14f, 156f, 52f, 52f)
        val next = iconButton("OtherIcons/ForwardArrow", 52f, Color(16f / 255f, 31f / 255f, 47f / 255f, .9f)) {
            stepLeader(1)
        }
        addAt(next, 327f, 156f, 52f, 52f)
    }

    private fun addLeader(nation: Nation?, selected: String) {
        addAt(BackgroundActor(panel(40f, Color(0f, 0f, 0f, .28f)), Align.center),
            74f, 70f, 244f, 244f)
        addAt(BackgroundActor(panel(40f, Color(1f, 1f, 1f, .9f)), Align.center),
            74f, 62f, 244f, 244f)
        val hero = if (nation == null || selected == Constants.random) {
            randomPortrait(false)
        } else {
            portraitImage(nation, false) ?: ImageGetter.getNationPortrait(nation, 236f * unit)
        }
        hero.touchable = Touchable.enabled
        hero.addListener(object : InputListener() {
            private var startX = 0f
            private var startY = 0f
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                startX = x; startY = y
                return true
            }
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                val dx = x - startX
                if (kotlin.math.abs(dx) < 40f * unit || kotlin.math.abs(dx) < kotlin.math.abs(y - startY)) return
                stepLeader(if (dx < 0f) 1 else -1)
            }
        })
        addAt(hero, 78f, 66f, 236f, 236f)

        val name = if (nation == null || selected == Constants.random) "Random leader"
            else nation.leaderName.ifEmpty { nation.name }
        addAt(name.toLabel(Color.WHITE, px(27f), Align.center, hideIcons = true), 18f, 318f, 357f, 34f)
        if (nation != null && selected != Constants.random) {
            val civ = badge(nation.name, "NationIcons/${nation.name}", Color.WHITE)
            val unique = badge(nation.uniqueName, null, Color.valueOf("ffc93c"))
            val gap = 8f * unit
            val total = civ.prefWidth + unique.prefWidth + gap
            civ.setBounds((width - total) / 2f, y(355f, 34f), civ.prefWidth, 34f * unit)
            unique.setBounds(civ.x + civ.width + gap, civ.y, unique.prefWidth, civ.height)
            addActor(civ)
            addActor(unique)
            val ability = nation.uniqueText.ifBlank {
                nation.uniques.take(2).joinToString(". ") { it.tr(hideIcons = true).trimEnd('.') }
                    .let { if (it.isBlank()) nation.uniqueName else "$it." }
            }
            addAt(ability.toLabel(Color.valueOf("e7f1fa"), px(14.5f), Align.center, hideIcons = true)
                .apply { wrap = true }, 24f, 400f, 345f, 62f)
        } else {
            addAt("A random civilization from the pool.".toLabel(Color.valueOf("e7f1fa"), px(14.5f), Align.center),
                24f, 401f, 345f, 46f)
        }
    }

    private fun addStrip(selected: String, previousScroll: Float) {
        val civs = availableCivs(selected)
        val strip = Table()
        strip.padLeft(14f * unit).padRight(14f * unit)
        for (name in civs) {
            val nation = screen.ruleset.nations[name]
            val slot = Group().apply { touchable = Touchable.enabled }
            slot.setSize(60f * unit, 60f * unit)
            if (name == selected) {
                val ring = BackgroundActor(panel(30f, Color.valueOf("ffc93c")), Align.center)
                ring.setBounds(0f, 0f, slot.width, slot.height)
                slot.addActor(ring)
            }
            val portrait = when {
                nation == null || name == Constants.random -> randomPortrait(true)
                name in listOf("China", "Egypt", "England") -> nationCrest(name)
                else -> portraitImage(nation, true) ?: ImageGetter.getNationPortrait(nation, 54f * unit)
            }
            portrait.touchable = Touchable.disabled
            portrait.setBounds(3f * unit, 3f * unit, 54f * unit, 54f * unit)
            slot.addActor(portrait)
            slot.onClick { selectLeader(name) }
            strip.add(slot).size(60f * unit).padRight(4f * unit)
        }
        strip.pack()
        val scroll = AutoScrollPane(strip).apply {
            setScrollingDisabled(false, true)
            setOverscroll(false, false)
            setFadeScrollBars(true)
        }
        stripScroll = scroll
        addAt(scroll, 0f, 484f, 393f, 64f)
        scroll.scrollX = previousScroll
    }

    private fun addRecipe() {
        val game = screen.gameSetupInfo.gameParameters
        val map = screen.gameSetupInfo.mapParameters
        val rivals = if (game.randomNumberOfPlayers)
            "${game.minNumberOfPlayers}-${game.maxNumberOfPlayers} players"
        else "${(game.players.count { it.chosenCiv != Constants.spectator } - 1).coerceAtLeast(0)} rivals"
        val chips = listOf(
            Triple("OtherIcons/Hexagon", "${map.mapSize.name} ${map.type}", "map"),
            Triple("OtherIcons/Star", game.difficulty, "rules"),
            Triple("OtherIcons/Timer", game.speed, "rules"),
            Triple("OtherIcons/Scenarios", game.startingEra.removeSuffix(" era"), "rules"),
            Triple("OtherIcons/Nations", "$rivals · ${game.numberOfCityStates} CS", "players"),
            Triple("OtherIcons/AchievementTrophy", if (game.victoryTypes.size == screen.ruleset.selectableVictories().size)
                "All victories" else "${game.victoryTypes.size} victories", "rules"),
        )
        for ((index, chip) in chips.withIndex()) {
            val card = Table().apply {
                background = panel(16f, Color(1f, 1f, 1f, .07f))
                touchable = Touchable.enabled
                padLeft(12f * unit)
                padRight(7f * unit)
            }
            card.add(ImageGetter.getImage(chip.first, Color.valueOf("b7cde0"))).size(22f * unit).padRight(9f * unit)
            card.add(chip.second.toLabel(Color.WHITE, px(15f)).apply { setEllipsis(true) }).growX().left()
            card.onClick { showSettings(chip.third) }
            val column = index % 2
            val row = index / 2
            addAt(card, 14f + column * 187f, 558f + row * 64f, 179f, 56f)
        }
    }

    private fun addFooter() {
        val top = heightInPixels - 94f
        val more = iconButton("OtherIcons/Options", 64f, Color(1f, 1f, 1f, .08f), 16f) { showSettings("all") }
        addAt(more, 14f, top, 64f, 60f)
        addAt(BackgroundActor(panel(18f, Color.valueOf("c9951c")), Align.center),
            86f, top + 4f, 293f, 60f)
        val start = Table().apply {
            background = panel(18f, Color.valueOf("ffc93c"))
            touchable = Touchable.enabled
        }
        start.add("Start game".toLabel(Color.valueOf("3a2a00"), px(18f))).padRight(8f * unit)
        start.add(ImageGetter.getImage("OtherIcons/ForwardArrow", Color.valueOf("3a2a00"))).size(24f * unit)
        start.onClick { if (!screen.rightSideButton.isDisabled) startGame() }
        startButton = addAt(start, 86f, top, 293f, 60f)
    }

    private fun iconButton(icon: String, size: Float, color: Color, radius: Float = size / 2f, action: () -> Unit): Table =
        Table().apply {
            background = panel(radius, color)
            touchable = Touchable.enabled
            add(ImageGetter.getImage(icon, Color.WHITE)).size(24f * unit)
            onClick { action() }
        }

    private fun badge(text: String, icon: String?, color: Color): Table {
        val badge = Table().apply {
            background = panel(12f, Color(1f, 1f, 1f, .08f))
            padLeft(8f * unit)
            padRight(8f * unit)
        }
        if (icon != null) badge.add(ImageGetter.getImage(icon)).size(17f * unit).padRight(6f * unit)
        badge.add(text.toLabel(color, px(13f), hideIcons = true))
        badge.pack()
        return badge
    }

    private fun randomPortrait(thumb: Boolean): Actor = Table().apply {
        background = panel(if (thumb) 27f else 36f, Color.valueOf("2a4866"))
        add("?".toLabel(Color.WHITE, px(if (thumb) 38f else 120f), Align.center)).grow()
    }

    private fun nationCrest(name: String): Actor {
        val (background, icon) = when (name) {
            "China" -> Color.valueOf("008f74") to Color.WHITE
            "Egypt" -> Color.valueOf("f5f500") to Color.valueOf("7531c1")
            else -> Color.valueOf("8c0909") to Color.WHITE
        }
        return Table().apply {
            this.background = panel(27f, background)
            add(ImageGetter.getImage("NationIcons/$name", icon)).size(40f * unit)
        }
    }

    private fun portraitImage(nation: Nation, thumb: Boolean): Image? {
        val name = nation.leaderName.substringBefore(' ')
        val file = "ExtraImages/Leaders/p_${name}${if (thumb) "_thumb" else ""}.png"
        val handle = Gdx.files.internal(file)
        if (!handle.exists()) return null
        val texture = if (thumb) thumbTextures.getOrPut(file) {
            Texture(handle).apply { setFilter(TextureFilter.Linear, TextureFilter.Linear) }
        } else {
            if (heroKey != file) {
                heroTexture?.dispose()
                heroTexture = Texture(handle).apply { setFilter(TextureFilter.Linear, TextureFilter.Linear) }
                heroKey = file
            }
            heroTexture!!
        }
        return Image(TextureRegionDrawable(TextureRegion(texture)))
    }

    private fun availableCivs(selected: String): List<String> {
        val preferred = listOf("Babylon", "Greece", "China", "Egypt", "England")
        val available = players.getAvailablePlayerCivs(selected).map { it.name }.toList()
        return listOf(Constants.random) + preferred.filter { it in available } + available.filterNot { it in preferred }
    }

    private fun stepLeader(step: Int) {
        val selected = screen.gameSetupInfo.gameParameters.players.firstOrNull { it.playerType == PlayerType.Human }
            ?.chosenCiv ?: Constants.random
        val civs = availableCivs(selected)
        if (civs.size < 2) return
        val index = civs.indexOf(selected).coerceAtLeast(0)
        selectLeader(civs[(index + step + civs.size) % civs.size])
    }

    private fun selectLeader(name: String) {
        val human = screen.gameSetupInfo.gameParameters.players.firstOrNull { it.playerType == PlayerType.Human } ?: return
        if (human.chosenCiv == name) return
        human.chosenCiv = name
        if (name in screen.ruleset.nations) human.setNationTransient(screen.ruleset)
        players.update()
        refresh(force = true)
    }

    private fun showSettings(section: String) {
        val popup = Popup(screen, Popup.Scrollability.All, 1f)
        popup.addGoodSizedLabel(when (section) {
            "map" -> "Map"
            "players" -> "Players"
            "rules" -> "Game rules"
            else -> "More options"
        }, px(23f)).row()
        val width = screen.stage.width - 24f * unit
        if (section == "map" || section == "all") popup.add(mapOptions).width(width).row()
        if (section == "rules" || section == "players" || section == "all")
            popup.add(gameOptions).width(width).row()
        if (section == "players" || section == "all") popup.add(players).width(width).row()
        if (section == "all") popup.add(gameOptions.modCheckboxes).width(width).row()
        popup.addCloseButton("Done") { refresh(force = true) }.row()
        popup.open()
    }

    private fun panel(radiusPx: Float, tint: Color): NinePatchDrawable {
        val key = "$radiusPx:${Color.rgba8888(tint)}"
        return panels.getOrPut(key) {
            val radius = (radiusPx * unit).roundToInt().coerceAtLeast(2)
            val side = radius * 2 + 2
            val pixels = Pixmap(side, side, Pixmap.Format.RGBA8888)
            pixels.blending = Pixmap.Blending.None
            for (y in 0 until side) for (x in 0 until side) {
                val px = x + .5f
                val py = y + .5f
                val dx = max(max(radius - px, px - (side - radius)), 0f)
                val dy = max(max(radius - py, py - (side - radius)), 0f)
                val alpha = (radius + .5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                pixels.drawPixel(x, y, 0xffffff00.toInt() or (alpha * 255f).roundToInt())
            }
            val texture = Texture(pixels)
            pixels.dispose()
            texture.setFilter(TextureFilter.Linear, TextureFilter.Linear)
            panelTextures.add(texture)
            NinePatchDrawable(NinePatch(TextureRegion(texture), radius, radius, radius, radius)).tint(tint)
        }
    }

    private fun px(value: Float) = (value * unit).roundToInt()
    private fun y(top: Float, height: Float) = this.height - (top + height) * unit

    private fun <T : Actor> addAt(actor: T, left: Float, top: Float, width: Float, height: Float): T {
        actor.setBounds(inset + left * unit, y(top, height), width * unit, height * unit)
        addActor(actor)
        return actor
    }

    override fun dispose() {
        panelTextures.forEach(Texture::dispose)
        thumbTextures.values.forEach(Texture::dispose)
        heroTexture?.dispose()
        glowTexture?.dispose()
    }
}
