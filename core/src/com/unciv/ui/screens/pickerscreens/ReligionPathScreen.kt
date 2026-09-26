package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Religion
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsReligion
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapTileUpdater.updateTiles
import kotlin.math.ceil
import kotlin.math.max

/** The approved faith path and its live city-follower map lens. */
class ReligionPathScreen(
    private val viewingCiv: Civilization,
    private val worldScreen: WorldScreen,
    startOnMap: Boolean = false,
) : BaseScreen(), RecreateOnResize {
    private val manager = viewingCiv.religionManager
    private var worldTab = false
    private var lens = startOnMap
    private var selectedCity: City? = null
    private var showingBeliefs: BeliefType? = null
    private var shown = false
    private var map: WorldMapHolder? = null

    init {
        globalShortcuts.add(KeyCharAndCode.BACK) { if (lens) { lens = false; rebuild() } else game.popScreen() }
        rebuild()
    }

    override fun show() {
        super.show()
        if (shown) rebuild()
        shown = true
    }

    override fun dispose() {
        stage.actors.filterIsInstance<PortraitMapBackdrop>().forEach { it.dispose() }
        super.dispose()
    }

    override fun recreate(): BaseScreen = ReligionPathScreen(viewingCiv, worldScreen, lens)

    private fun rebuild() {
        stage.actors.filterIsInstance<PortraitMapBackdrop>().forEach { it.dispose() }
        stage.root.clearChildren()
        map = null
        if (lens) buildLens() else buildPath()
    }

    private fun buildPath() {
        val safe = safeAreaBoundsInWorld()
        val scale = safe.width / 393f
        val bounds = com.badlogic.gdx.math.Rectangle(safe.x, safe.y, 393f, safe.height / scale)
        val (topGap, bottomGap) = portraitChromeGaps(393f)
        stage.addActor(PortraitMapBackdrop(viewingCiv).apply {
            val canvas = portraitCanvasBounds()
            setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
        })
        val root = Table().apply {
            background = rounded(SHEET.cpy().apply { a = .9f }, BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape)
            isTransform = true
            setScale(scale)
            setBounds(bounds.x, bounds.y, bounds.width, bounds.height - topGap)
        }
        root.add(Table().apply { add(Image(solid(Color(1f, 1f, 1f, .3f)))).size(44f, 5f) }).growX().height(16f).row()
        root.add(header(bounds.width)).growX().row()
        val tabs = Table()
        for ((title, isWorld) in listOf("Your faith" to false, "World" to true)) {
            val tab = Table().apply { touchable = Touchable.enabled }
            tab.add(title.toLabel(if (worldTab == isWorld) Color.WHITE else INK3, 16, Align.center)).grow().row()
            tab.add(Image(solid(if (worldTab == isWorld) YELLOW else Color.CLEAR))).size(90f, 3f)
            tab.onClick { worldTab = isWorld; rebuild() }
            tabs.add(tab).growX().uniformX().height(52f)
        }
        root.add(tabs).growX().row()
        root.add(Image(solid(LINE))).growX().height(1f).row()
        val content = if (worldTab) worldContent(bounds.width) else faithContent(bounds.width)
        root.add(scroll(content)).grow().prefHeight(0f).row()
        root.add(pathFooter(bounds.width, bottomGap)).growX()
        stage.addActor(root)
        if (showingBeliefs != null) beliefSheet(bounds.width)
    }

    private fun header(width: Float) = Table().apply {
        pad(9f, 16f, 4f, 16f)
        add("Religion".toLabel(fontSize = 24)).left()
        val faith = Table()
        faith.add(ImageGetter.getImage("StatIcons/Faith")).size(20f).padRight(4f)
        faith.add(manager.storedFaith.toString().toLabel(fontSize = 17))
        val perTurn = viewingCiv.stats.statsForNextTurn.faith
        faith.add(" +${perTurn.toInt()}".toLabel(INK2, 13))
        add(faith).expandX().right().padRight(12f)
        val close = button("×", CHIP, Color.WHITE, 48f)
        close.onClick { game.popScreen() }
        add(close).size(48f)
    }

    private fun faithContent(width: Float): Table {
        val content = Table().top().left()
        content.pad(16f, 14f, 24f, 14f)
        content.defaults().growX().left()
        if (!viewingCiv.gameInfo.isReligionEnabled()) {
            content.add("Religion is off in this game".toLabel(INK2, 17)).padTop(24f)
            return content
        }
        val religion = manager.religion
        val pantheon = religion?.getBeliefs(BeliefType.Pantheon)?.firstOrNull()
        val pantheonDone = pantheon != null
        val state = manager.religionState
        val prophet = viewingCiv.units.getCivUnits().firstOrNull {
            it.baseUnit.hasUnique(UniqueType.MayFoundReligion) || it.baseUnit.hasUnique(UniqueType.MayEnhanceReligion)
        }
        val prophetCost = if (pantheonDone && manager.getGreatProphetEquivalent() != null) manager.faithForNextGreatProphet() else 0
        val perTurn = viewingCiv.stats.statsForNextTurn.faith

        val pantheonText = when {
            pantheon != null -> pantheon.name.tr()
            manager.canFoundOrExpandPantheon() -> "Ready to choose"
            else -> "${manager.storedFaith} / ${manager.faithForPantheon()} faith"
        }
        content.add(step("Pantheon", pantheonText, "ReligionIcons/Pantheon", pantheonDone, !pantheonDone,
            if (pantheonDone) null else manager.storedFaith to manager.faithForPantheon(), width) {
            if (manager.canFoundOrExpandPantheon()) game.pushScreen { PantheonPickerScreen(viewingCiv) }
            else showBeliefs(BeliefType.Pantheon)
        }).padBottom(4f).row()

        val prophetText = when {
            prophet != null -> "Ready in ${prophet.getTile().getCity()?.name ?: prophet.getTile().position}"
            !pantheonDone -> "First choose a pantheon"
            !manager.canGenerateProphet(ignoreFaithAmount = true) -> "No prophet available"
            manager.storedFaith >= prophetCost -> "Can be born each turn"
            perTurn > 0f -> "${manager.storedFaith} / $prophetCost faith  ·  +${perTurn.toInt()} per turn"
            else -> "${manager.storedFaith} / $prophetCost faith"
        }
        content.add(step("Great Prophet", prophetText, "UnitIcons/${manager.getGreatProphetEquivalent()?.name ?: "Great Prophet"}", prophet != null,
            pantheonDone, if (prophet == null && prophetCost > 0) manager.storedFaith to prophetCost else null, width) {
            if (prophet != null) { lens = true; rebuild(); focusOn(prophet.getTile().position) }
        }).padBottom(4f).row()

        val founded = state >= ReligionState.Religion && religion?.isMajorReligion() == true
        val founding = state == ReligionState.FoundingReligion
        val foundNote = when {
            founded -> "Holy city ${manager.getHolyCity()?.name ?: ""}"
            founding -> "Choose a symbol and beliefs"
            prophetForFounding() != null -> "Great Prophet ready to found"
            manager.remainingFoundableReligions() == 0 -> "No religion slots remain"
            else -> "Choose when a Great Prophet is ready"
        }
        val found = step("Found a religion", foundNote, "ReligionIcons/Religion", founded, pantheonDone, null, width) { foundOrEnhance(false) }
        val slots = Table().left()
        for ((label, type) in listOf("Religion" to null, "Founder" to BeliefType.Founder, "Follower" to BeliefType.Follower)) {
            val chosen = if (type == null) religion?.takeIf { it.isMajorReligion() }?.getReligionDisplayName()
            else religion?.getBeliefs(type)?.firstOrNull()?.name
            val icon = if (type == null) religion?.takeIf { it.isMajorReligion() }?.getIconName() ?: "Religion" else type.name
            val slot = beliefSlot(chosen ?: label, icon, chosen != null, 92f)
            slot.onClick {
                if (founding) openBeliefPicker(true)
                else if (type != null) showBeliefs(type)
                else showReligionSymbols()
            }
            slots.add(slot).width(92f).height(92f).padRight(6f)
        }
        found.add(slots).colspan(2).growX().pad(10f, 64f, 0f, 0f).row()
        content.add(found).padBottom(4f).row()

        val enhanced = state == ReligionState.EnhancedReligion || religion?.isEnhancedReligion() == true
        val enhancing = state == ReligionState.EnhancingReligion
        val enhanceNote = when {
            enhanced -> "Your religion is enhanced"
            enhancing -> "Choose two more beliefs"
            !founded -> "After founding a religion"
            prophetForEnhancing() != null -> "Great Prophet ready to enhance"
            else -> "Second Great Prophet  ·  ${manager.storedFaith} / $prophetCost faith"
        }
        val enhance = step("Enhance", enhanceNote, "ReligionIcons/Enhancer", enhanced, founded, null, width) { foundOrEnhance(true) }
        val enhanceSlots = Table().left()
        for (type in listOf(BeliefType.Follower, BeliefType.Enhancer)) {
            val chosen = if (type == BeliefType.Follower) religion?.getBeliefs(type)?.drop(1)?.firstOrNull()?.name
                else religion?.getBeliefs(type)?.firstOrNull()?.name
            val slot = beliefSlot(chosen ?: type.name, type.name, chosen != null, 110f)
            slot.onClick { if (enhancing) openBeliefPicker(false) else showBeliefs(type) }
            enhanceSlots.add(slot).width(110f).height(92f).padRight(8f)
        }
        enhance.add(enhanceSlots).colspan(2).growX().pad(10f, 64f, 0f, 0f)
        content.add(enhance).row()
        return content
    }

    private fun step(title: String, note: String, icon: String, done: Boolean, available: Boolean,
                     progress: Pair<Int, Int>?, width: Float, action: () -> Unit): Table {
        val row = object : Table() {
            override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
                super.drawBackground(batch, parentAlpha, x, y)
                if (title != "Enhance") solid(if (done) DONE else LINE).draw(batch, x + 24f, y - 4f, 4f, (height - 48f).coerceAtLeast(0f))
            }
        }.top().left().apply { touchable = Touchable.enabled; padBottom(18f) }
        val disc = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(if (done) DONE else if (available) Color.WHITE else LOCKED)
            touchable = Touchable.disabled
        }
        val picture = ImageGetter.getImage(icon).apply { color = if (done) Color.WHITE else if (available) SHEET else INK3 }
        disc.add(picture).size(30f)
        row.add(disc).size(52f).top().padRight(12f)
        val right = Table().left()
        right.add(title.toLabel(if (available || done) Color.WHITE else INK2, 18)).left().row()
        right.add(note.toLabel(INK2, 14)).left().padTop(4f).row()
        if (progress != null && progress.second > 0) right.add(progressBar(progress.first, progress.second, width - 96f, FAITH)).width(width - 96f).height(9f).padTop(9f).left()
        row.add(right).growX().top().row()
        row.onClick(action)
        return row
    }

    private fun pathFooter(width: Float, bottomGap: Float) = Table().apply {
        background = solid(BAR)
        pad(12f, 14f, bottomGap, 14f)
        val mapButton = button("Map", Color.WHITE, SHEET, 76f, 60f, 14)
        mapButton.onClick { lens = true; rebuild() }
        add(mapButton).size(76f, 60f).padRight(10f)
        val state = manager.religionState
        val actionText = when {
            !viewingCiv.gameInfo.isReligionEnabled() -> "Religion is off"
            state == ReligionState.FoundingReligion -> "Found a religion"
            state == ReligionState.EnhancingReligion -> "Enhance religion"
            manager.canFoundOrExpandPantheon() -> "Choose pantheon"
            prophetForFounding() != null -> "Found a religion"
            prophetForEnhancing() != null -> "Enhance religion"
            else -> "Great Prophet progress"
        }
        val actionable = state == ReligionState.FoundingReligion || state == ReligionState.EnhancingReligion ||
            manager.canFoundOrExpandPantheon() || prophetForFounding() != null || prophetForEnhancing() != null
        val action = button(actionText, if (actionable) YELLOW else CHIP, if (actionable) YELLOW_INK else INK2, width - 114f, 60f, 17)
        if (actionable) action.onClick {
            when {
                state == ReligionState.FoundingReligion -> openBeliefPicker(true)
                state == ReligionState.EnhancingReligion -> openBeliefPicker(false)
                manager.canFoundOrExpandPantheon() -> game.pushScreen { PantheonPickerScreen(viewingCiv) }
                prophetForFounding() != null -> foundOrEnhance(false)
                prophetForEnhancing() != null -> foundOrEnhance(true)
            }
        }
        add(action).growX().height(60f)
    }

    private fun worldContent(width: Float): Table {
        val content = Table().top().left()
        content.pad(12f, 14f, 24f, 14f).defaults().growX().left()
        val religions = viewingCiv.gameInfo.religions.values.distinctBy { it.name }
            .filter { it.foundingCiv == viewingCiv || viewingCiv.knows(it.foundingCiv) }
        if (religions.isEmpty()) content.add("No known religions yet".toLabel(INK2, 16)).pad(12f).row()
        for (religion in religions) {
            val card = Table().apply { background = rounded(PANEL); pad(14f) }
            card.add(ImageGetter.getReligionPortrait(religion.getIconName(), 48f)).size(48f).padRight(12f)
            val names = Table().left()
            names.add(religion.getReligionDisplayName().toLabel(fontSize = 18)).left().row()
            val holy = viewingCiv.gameInfo.getCities().firstOrNull { it.religion.religionThisIsTheHolyCityOf == religion.name && viewingCiv.hasExplored(it.getCenterTile()) }
            val sub = if (holy != null) "${religion.foundingCiv.civName}  ·  Holy city ${holy.name}" else religion.foundingCiv.civName
            names.add(sub.toLabel(INK2, 13)).left().padTop(3f)
            card.add(names).growX().left()
            content.add(card).padBottom(10f).row()
        }
        content.add("Cities".toLabel(INK3, 14)).pad(8f, 2f, 6f, 2f).row()
        val visibleCities = visibleCities()
        if (visibleCities.isEmpty()) content.add("No discovered cities".toLabel(INK2, 15)).pad(10f).row()
        for (city in visibleCities) {
            val row = cityDetails(city, width - 28f, compact = true)
            row.onClick { selectedCity = city; lens = true; rebuild(); focusOn(city.location.toHexCoord()) }
            content.add(row).padBottom(8f).row()
        }
        return content
    }

    private fun buildLens() {
        val safe = safeAreaBoundsInWorld()
        val scale = safe.width / 393f
        val bounds = com.badlogic.gdx.math.Rectangle(safe.x, safe.y, 393f, safe.height / scale)
        val (topGap, bottomGap) = portraitChromeGaps(393f)
        val drawing = (stage.viewport as SafeAreaViewport).drawingBounds
        val mapHolder = WorldMapHolder(worldScreen, viewingCiv.gameInfo.tileMap, gameplayInput = false)
        map = mapHolder
        stage.addActor(Image(solid(Color.valueOf("eef5fb"))).apply {
            setBounds(drawing.x, drawing.y, drawing.width, drawing.height)
            touchable = Touchable.disabled
        })
        stage.addActor(mapHolder)
        mapHolder.addTiles()
        mapHolder.setBounds(drawing.x, drawing.y, drawing.width, drawing.height)
        mapHolder.layout()
        mapHolder.reloadMaxZoom()
        mapHolder.zoom(.85f)
        mapHolder.updateTiles(worldScreen.selectedGameView.civView)
        for (tile in mapHolder.tileGroups.values) {
            tile.layerFeatures.isVisible = false
            tile.layerResource.isVisible = false
            tile.layerImprovement.isVisible = false
            tile.layerMisc.isVisible = false
            tile.layerYield.isVisible = false
            tile.layerUnitArt.isVisible = false
            tile.layerUnitFlag.isVisible = false
            tile.layerCityButton.isVisible = false
        }
        val mapGroup = mapHolder.actor as Group
        // The lens is read-only. Its own city markers get touch handling after map layers are disabled.
        for (child in mapGroup.children) child.touchable = Touchable.disabled
        stage.addActor(Image(solid(Color(0.06f, 0.13f, 0.19f, .3f))).apply {
            setBounds(drawing.x, drawing.y, drawing.width, drawing.height)
            touchable = Touchable.disabled
        })
        val cities = visibleCities()
        val byCity = mapHolder.tileGroups.values.associateBy { it.tileView.position() }
        val incomingPressure = cities.associateWith { it.religion.getPressuresFromSurroundingCities() }
        val lines = TechTreeLines().apply {
            casingColor = SHEET
            setBounds(0f, 0f, mapGroup.width, mapGroup.height)
        }
        for (city in cities) {
            val religion = city.religion.getMajorityReligion() ?: continue
            if (!religion.isMajorReligion()) continue
            val destination = cities.filter { it != city && it.religion.getFollowersOf(religion.name) > 0 &&
                incomingPressure[it]?.get(religion.name)?.let { pressure -> pressure > 0 } == true }
                .minByOrNull { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) } ?: continue
            if (city.getCenterTile().aerialDistanceTo(destination.getCenterTile()) > 10) continue
            val from = byCity[city.location.toHexCoord()] ?: continue
            val to = byCity[destination.location.toHexCoord()] ?: continue
            lines.addLink(city.name, destination.name, from.x + from.groundCenterX, from.y + from.groundCenterY,
                to.x + to.groundCenterX, to.y + to.groundCenterY, emptyList(), emptyList())
            lines.links.last().apply { color = religionColor(religion); dashed = true; width = 3f }
        }
        mapHolder.addActorToTileGroupMap(lines)
        for (city in cities) {
            val group = byCity[city.location.toHexCoord()] ?: continue
            val marker = cityMarker(city)
            marker.setPosition(group.x + group.groundCenterX - marker.width / 2f, group.y + group.groundCenterY - 39f)
            mapHolder.addActorToTileGroupMap(marker)
        }
        val overlay = WidgetGroup().apply {
            isTransform = true
            setScale(scale)
            setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
            touchable = Touchable.childrenOnly
        }
        val stats = Table().apply { background = rounded(PANEL); pad(4f, 7f, 4f, 7f) }
        val next = viewingCiv.stats.statsForNextTurn
        val values = listOf(
            "Gold" to viewingCiv.gold.toString(),
            "Science" to "+${next.science.toInt()}",
            "Culture" to "${viewingCiv.policies.storedCulture}/${viewingCiv.policies.getCultureNeededForNextPolicy()}",
            "Happiness" to viewingCiv.getHappiness().toString(),
            "Faith" to manager.storedFaith.toString(),
        )
        for ((icon, value) in values) {
            stats.add(ImageGetter.getImage("StatIcons/$icon")).size(17f).padRight(3f)
            stats.add(value.toLabel(fontSize = 13)).padRight(7f)
        }
        stats.pack()
        stats.setBounds(10f, bounds.height - topGap - 48f, bounds.width - 20f, 48f)
        overlay.addActor(stats)
        val legend = Table().top().left()
        legend.pad(8f)
        val names = cities.flatMap { city -> city.religion.getNumberOfFollowers().keys }.distinct()
        for (name in names) {
            val faith = viewingCiv.gameInfo.religions[name] ?: continue
            val chip = Table().apply { background = rounded(PANEL); pad(7f, 10f, 7f, 10f) }
            chip.add(Image(solid(religionColor(faith)))).size(10f).padRight(7f)
            chip.add(faith.getReligionDisplayName().toLabel(fontSize = 13))
            legend.add(chip).left().padBottom(6f).row()
        }
        legend.pack()
        legend.setPosition(10f, bounds.height - topGap - legend.height - 58f)
        overlay.addActor(legend)
        val panel = lensPanel(bounds.width, bottomGap)
        panel.pack()
        panel.setBounds(0f, 0f, bounds.width, panel.prefHeight)
        overlay.addActor(panel)
        stage.addActor(overlay)
        val focus = selectedCity?.location?.toHexCoord() ?: viewingCiv.getCapital()?.location?.toHexCoord()
            ?: viewingCiv.units.getCivUnits().firstOrNull()?.getTile()?.position
            ?: viewingCiv.gameInfo.tileMap.values.firstOrNull { viewingCiv.hasExplored(it) }?.position
        if (focus != null) focusOn(focus)
    }

    private fun cityMarker(city: City): Group {
        val marker = Group().apply { setSize(96f, 110f); touchable = Touchable.enabled }
        val followers = city.religion.getNumberOfFollowers()
        val segments = followers.entries.mapNotNull { (name, count) ->
            if (count <= 0) null else (viewingCiv.gameInfo.religions[name]?.let { religionColor(it) } ?: NO_RELIGION) to count
        }
        marker.addActor(FollowerRing(city.population.population, segments).apply { setBounds(16f, 40f, 64f, 64f) })
        val pop = Table().apply { background = rounded(city.civ.nation.getOuterColor()); touchable = Touchable.disabled }
        pop.add(city.population.population.toString().toLabel(fontSize = 15)).center()
        pop.setBounds(32f, 56f, 32f, 32f)
        marker.addActor(pop)
        val name = Table().apply { background = rounded(PANEL); touchable = Touchable.disabled }
        name.add(city.name.toLabel(fontSize = 13, alignment = Align.center)).growX()
        name.setBounds(0f, 7f, 96f, 27f)
        marker.addActor(name)
        marker.onClick { selectedCity = city; rebuild() }
        return marker
    }

    private fun lensPanel(width: Float, bottomGap: Float): Table {
        val panel = Table().apply { background = rounded(PANEL, BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape); pad(12f, 16f, bottomGap, 16f) }
        val city = selectedCity?.takeIf { viewingCiv.hasExplored(it.getCenterTile()) }
        if (city == null) {
            val status = Table().left()
            status.add(ImageGetter.getImage("StatIcons/Faith")).size(30f).padRight(10f)
            val label = when {
                manager.religionState == ReligionState.FoundingReligion -> "Choose a religion"
                prophetForFounding() != null -> "Great Prophet ready"
                manager.religion?.isMajorReligion() == true -> "${manager.religion!!.getReligionDisplayName()} founded"
                else -> "Great Prophet  ·  ${manager.storedFaith} faith"
            }
            status.add(label.toLabel(fontSize = 16)).growX().left()
            panel.add(status).growX().padRight(10f)
        } else {
            panel.add(cityDetails(city, width - 32f, compact = false)).growX().row()
        }
        val actions = Table()
        val path = button("Path", CHIP, Color.WHITE, 80f, 56f, 15)
        path.onClick { lens = false; selectedCity = null; rebuild() }
        actions.add(path).width(80f).height(56f).padRight(8f)
        val all = button(if (city == null) "All cities" else "World cities", CHIP, Color.WHITE, width - 120f, 56f, 15)
        all.onClick { worldTab = true; lens = false; selectedCity = null; rebuild() }
        actions.add(all).growX().height(56f)
        panel.add(actions).growX().padTop(12f)
        return panel
    }

    private fun cityDetails(city: City, width: Float, compact: Boolean): Table {
        val card = Table().apply { background = rounded(if (compact) PANEL else SHEET); pad(10f); touchable = Touchable.enabled }
        val head = Table().left()
        head.add(city.population.population.toString().toLabel(fontSize = 19, alignment = Align.center)).size(44f).padRight(10f)
        val title = Table().left()
        title.add(city.name.toLabel(fontSize = if (compact) 17 else 20)).left().row()
        val holy = city.religion.religionThisIsTheHolyCityOf?.let { viewingCiv.gameInfo.religions[it]?.getReligionDisplayName() }
        title.add((holy?.let { "Holy city of $it" } ?: city.civ.civName).toLabel(INK2, 13)).left()
        head.add(title).growX().left()
        card.add(head).growX().row()
        val followers = city.religion.getNumberOfFollowers()
        val total = city.population.population.coerceAtLeast(1)
        val bar = Table().apply { background = solid(NO_RELIGION) }
        for ((name, count) in followers) if (count > 0) bar.add(Image(solid(viewingCiv.gameInfo.religions[name]?.let { religionColor(it) } ?: NO_RELIGION)))
            .width((width - 20f) * count / total).height(10f)
        card.add(bar).growX().height(10f).padTop(10f).row()
        val names = followers.entries.filter { it.value > 0 }.joinToString("   ") { (name, count) ->
            "$count ${viewingCiv.gameInfo.religions[name]?.getReligionDisplayName() ?: name}"
        }
        val noReligion = (city.population.population - followers.values.sum()).coerceAtLeast(0)
        val summary = listOf(names.takeIf { it.isNotEmpty() }, "$noReligion no religion".takeIf { noReligion > 0 }).filterNotNull().joinToString("   ")
        card.add(summary.toLabel(INK2, 13)).left().padTop(7f)
        return card
    }

    private fun beliefSheet(width: Float) {
        val type = showingBeliefs ?: return
        val safe = safeAreaBoundsInWorld()
        val scale = safe.width / 393f
        val bounds = com.badlogic.gdx.math.Rectangle(safe.x, safe.y, 393f, safe.height / scale)
        val sheet = Table().apply {
            background = solid(PANEL)
            isTransform = true
            setScale(scale)
            setBounds(bounds.x, bounds.y, bounds.width, bounds.height * .75f)
        }
        val header = Table()
        header.add("${type.name} beliefs".toLabel(fontSize = 21)).growX().left().pad(12f)
        val close = button("×", CHIP, Color.WHITE, 48f)
        close.onClick { showingBeliefs = null; rebuild() }
        header.add(close).size(48f).pad(8f)
        sheet.add(header).growX().row()
        val list = Table().top().left().apply { pad(8f, 14f, 20f, 14f) }
        for (belief in viewingCiv.gameInfo.ruleset.beliefs.values.filter { it.type == type }) {
            val holder = manager.getReligionWithBelief(belief)
            val card = Table().apply { background = rounded(CARD); pad(12f) }
            card.add(ImageGetter.getReligionIcon(type.name)).size(32f).padRight(10f)
            val text = Table().left()
            text.add(belief.name.toLabel(fontSize = 16)).left().row()
            val effects = belief.uniqueObjects.filterNot { it.isHiddenToUsers() }.joinToString(" · ") { it.getDisplayText().tr() }
            val label = effects.toLabel(INK2, 13).apply { wrap = true }
            text.add(label).width(width - 110f).left().padTop(4f)
            card.add(text).growX().left()
            list.add(card).growX().padBottom(8f).row()
            if (holder != null) list.add(("Chosen by " + holder.foundingCiv.civName).toLabel(INK3, 12)).left().padBottom(6f).row()
        }
        sheet.add(scroll(list)).grow().prefHeight(0f).row()
        sheet.add("Choose when your Great Prophet is ready".toLabel(INK2, 14, Align.center)).growX().height(54f)
        stage.addActor(sheet)
    }

    private fun showReligionSymbols() {
        if (manager.religionState == ReligionState.FoundingReligion) openBeliefPicker(true)
        else showBeliefs(BeliefType.Founder)
    }

    private fun showBeliefs(type: BeliefType) { showingBeliefs = type; rebuild() }

    private fun openBeliefPicker(founding: Boolean) {
        val choices = if (founding) manager.getBeliefsToChooseAtFounding() else manager.getBeliefsToChooseAtEnhancing()
        game.pushScreen { ReligiousBeliefsPickerScreen(viewingCiv, choices, pickIconAndName = founding) }
    }

    private fun foundOrEnhance(enhance: Boolean) {
        val state = manager.religionState
        if (state == ReligionState.FoundingReligion && !enhance) { openBeliefPicker(true); return }
        if (state == ReligionState.EnhancingReligion && enhance) { openBeliefPicker(false); return }
        if (!viewingCiv.isCurrentPlayer() || !worldScreen.canChangeState) return
        val prophet = (if (enhance) prophetForEnhancing() else prophetForFounding()) ?: return
        val action = if (enhance) UnitActionsReligion.getEnhanceReligionActions(prophet, prophet.getTile()).firstOrNull()?.action
        else UnitActionsReligion.getFoundReligionActions(prophet, prophet.getTile()).firstOrNull()?.action
        if (action != null) {
            action()
            openBeliefPicker(!enhance)
        } else {
            lens = true
            rebuild()
            focusOn(prophet.getTile().position)
        }
    }

    private fun prophetForFounding(): MapUnit? = viewingCiv.units.getCivUnits().firstOrNull {
        it.baseUnit.hasUnique(UniqueType.MayFoundReligion) && manager.mayFoundReligionHere(it.getTile())
    }

    private fun prophetForEnhancing(): MapUnit? = viewingCiv.units.getCivUnits().firstOrNull {
        it.baseUnit.hasUnique(UniqueType.MayEnhanceReligion) && manager.mayEnhanceReligionHere(it.getTile())
    }

    private fun visibleCities(): List<City> = viewingCiv.gameInfo.getCities()
        .filter { viewingCiv.hasExplored(it.getCenterTile()) }.toList()

    private fun religionColor(religion: Religion) = if (religion.isPantheon()) DONE else religion.foundingCiv.nation.getOuterColor()

    private fun focusOn(tile: com.unciv.logic.map.HexCoord) { map?.setCenterPosition(tile, immediately = true, selectUnit = false) }

    private fun progressBar(value: Int, goal: Int, width: Float, color: Color): Table {
        val bar = Table().left().apply { background = solid(CHIP) }
        bar.add(Image(solid(color))).width(width * (value.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)).height(9f).left()
        return bar
    }

    private fun scroll(content: Actor) = AutoScrollPane(content).apply { setScrollingDisabled(true, false); setOverscroll(false, false) }

    private fun beliefSlot(text: String, icon: String, chosen: Boolean, width: Float) = Table().apply {
        background = rounded(if (chosen) COMPLETE else CHIP)
        touchable = Touchable.enabled
        add(ImageGetter.getReligionIcon(icon)).size(28f).padTop(9f).row()
        add(text.toLabel(if (chosen) YELLOW else INK2, 13, Align.center).apply { wrap = true })
            .width(width - 10f).height(39f).padBottom(7f)
        setSize(width, 92f)
    }

    private fun button(text: String, backgroundColor: Color, textColor: Color, width: Float, height: Float = 48f, font: Int = 18) = Table().apply {
        background = rounded(backgroundColor)
        touchable = Touchable.enabled
        add(text.toLabel(textColor, font, Align.center)).grow()
        setSize(width, height)
    }

    /** A ring drawn in follower-count shares around each discovered city, including residents with no religion. */
    private class FollowerRing(private val population: Int, private val followers: List<Pair<Color, Int>>) : Actor() {
        private val dot = ImageGetter.getWhiteDotDrawable().region
        override fun draw(batch: Batch, parentAlpha: Float) {
            val old = batch.color.cpy()
            var start = 0f
            val shares = followers + (NO_RELIGION to (population - followers.sumOf { it.second }).coerceAtLeast(0))
            for ((color, count) in shares) {
                val arc = 360f * count / population.coerceAtLeast(1)
                val pieces = max(1, ceil(arc / 6f).toInt())
                batch.setColor(color.r, color.g, color.b, parentAlpha)
                for (i in 0 until pieces) {
                    val angle = (start + i * arc / pieces + 90f) * MathUtils.degreesToRadians
                    val px = x + width / 2f + MathUtils.cos(angle) * 25f
                    val py = y + height / 2f + MathUtils.sin(angle) * 25f
                    batch.draw(dot, px - 4.5f, py - 4.5f, 4.5f, 4.5f, 9f, 9f, 1f, 1f, start + i * arc / pieces)
                }
                start += arc
            }
            batch.color = old
        }
    }

    private companion object {
        val SHEET = Color.valueOf("132435")
        val PANEL = Color.valueOf("1c3249")
        val BAR = Color.valueOf("0f2030")
        val CARD = Color.valueOf("1e2f40")
        val CHIP = Color.valueOf("2a3d51")
        val COMPLETE = Color.valueOf("3d3a2e")
        val YELLOW = Color.valueOf("ffc93c")
        val YELLOW_INK = Color.valueOf("3a2a00")
        val INK2 = Color.valueOf("b7cde0")
        val INK3 = Color.valueOf("7f9ab2")
        val FAITH = Color.valueOf("f3ecd2")
        val DONE = Color.valueOf("2f7fe0")
        val LOCKED = Color.valueOf("1f3850")
        val NO_RELIGION = Color.valueOf("5d7690")
        val LINE = Color(1f, 1f, 1f, .12f)
        fun solid(color: Color): Drawable = ImageGetter.getWhiteDotDrawable().tint(color)
        fun rounded(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleMidShape): Drawable =
            BaseScreen.skinStrings.getUiBackground("", shape, color)
    }
}
