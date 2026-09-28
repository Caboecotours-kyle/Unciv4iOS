package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.Portrait
import com.unciv.ui.objectdescriptions.TechnologyDescriptions
import com.unciv.ui.popups.TrayArt
import com.unciv.ui.popups.TrayColors
import com.unciv.ui.popups.TraySheet
import com.unciv.ui.popups.tinted
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The portrait, one-handed [TechPickerScreen] as a tray sheet (approved direction B of `mocks/drawers-proposal.html`):
 * - **Next up**: current research (or an empty research slot), every tech researchable now, and goals (wonders, units,
 *   buildings) by turns to reach.
 * - **Full tree**: the tree in four lanes that only scrolls vertically, a detail card for the selected tech, era tabs.
 *
 * The kit's [TraySheet] owns the handle, header, close, segmented tabs, raised footer (the research action) and the
 * motion; the tab content is laid out in mock points and scaled into the sheet body.
 * Selection, queueing and committing stay in [TechPickerScreen]; this view only shows its state and calls back.
 */
internal class TechPickerPortrait(
    private val screen: TechPickerScreen,
    startOnTree: Boolean,
) : Group() {
    private val civInfo = screen.civInfo
    private val civTech = civInfo.tech
    private val ruleset = civInfo.gameInfo.ruleset
    private val science = civInfo.stats.statsForNextTurn.science

    private val art = TrayArt(screen.stage)
    private val sheet = TraySheet(screen, art, minShare = 1f)
    private val white = ImageGetter.getWhiteDotDrawable()
    /** The content below is laid out in mock points on the 393pt canvas and scaled by the sheet's stage units per point */
    private val contentWidth = 393f
    /** Smallest touch target in content points: 48pt of art, and never under 48 real screen points */
    private val touch = sheet.touch / sheet.p

    private enum class GoalKind(val label: String) { Wonders("Wonders"), Units("Units"), Buildings("Buildings") }
    private class Goal(val name: String, val kind: GoalKind, val tech: Technology, val remainingScience: Int)

    private var showTree = startOnTree
    private var goalKind = GoalKind.Wonders
    private var pickedGoal: Goal? = null

    private val body = Container<Actor>().fill()

    private val nextUpContent = Table()
    private val nextUpScroll = verticalScroll(nextUpContent)
    private val pickRows = HashMap<String, PickRow>()
    private val goalTabs = Table()
    private val goalRows = Table()
    private var nextUpBuilt = false

    private var tree: TreeView? = null

    init {
        name = "portrait-tech-sheet"
        touchable = Touchable.childrenOnly
        setBounds(0f, 0f, screen.stage.width, screen.stage.height)
        addActor(sheet)
        sheet.onGone = { screen.game.popScreen() }
        sheet.header("Technology", "+${science.roundToInt()} science per turn")
        sheet.fullWidthBody()
        sheet.body.add(Scaled(body, sheet.p)).grow()
    }

    /** Opens the sheet: it rises from the bottom edge (or fades in with reduced motion) */
    fun rise() = sheet.rise()

    /** The scrim of the mock over the map backdrop, following the sheet as it rises, is dragged and leaves */
    override fun draw(batch: Batch, parentAlpha: Float) {
        val canvas = screen.portraitCanvasBounds()
        batch.setColor(TrayColors.SCRIM.r, TrayColors.SCRIM.g, TrayColors.SCRIM.b, TrayColors.SCRIM.a * sheet.scrim * parentAlpha)
        white.draw(batch, canvas.x, canvas.y, canvas.width, canvas.height)
        super.draw(batch, parentAlpha)
    }

    /** Leaving the stage (close, research, a replaced screen) frees the sheet's textures once */
    override fun setStage(stage: Stage?) {
        super.setStage(stage)
        if (stage == null) art.release()
    }

    /** Re-reads selection and queue from [screen]; called after every change there */
    fun refresh() {
        if (pickedGoal?.tech != screen.selectedTech) pickedGoal = null
        sheet.toolbar(sheet.segmented(listOf("Next up", "Full tree"), if (showTree) 1 else 0) { showTab(it == 1) })
        if (showTree) {
            val treeView = tree ?: TreeView().also { tree = it }
            treeView.refresh()
            body.actor = treeView
        } else {
            if (!nextUpBuilt) buildNextUp()
            for ((name, row) in pickRows) row.setPicked(pickedGoal == null && screen.selectedTech?.name == name)
            rebuildGoalRows()
            body.actor = nextUpScroll
        }
        rebuildFooter()
        sheet.fit()
    }

    fun centerOn(tech: Technology) {
        tree?.centerOn(tech.name)
    }

    fun isShowingTree() = showTree

    private fun showTab(treeTab: Boolean) {
        if (showTree == treeTab) return
        showTree = treeTab
        refresh()
    }

    //region Next up

    private fun buildNextUp() {
        nextUpBuilt = true
        val width = contentWidth - 24f
        nextUpContent.pad(4f, 12f, 16f, 12f).defaults().width(width)
        nextUpContent.add(nowCard(width)).row()
        if (!screen.freeTechPick) {
            val current = civTech.currentTechnology()
            if (current != null) {
                val chips = unlocks(current).map { (name, kind) -> unlockChip(name, kind) }
                if (chips.isNotEmpty()) nextUpContent.add(flow(chips, width - 8f)).left().padLeft(4f).padTop(10f).row()
            }
        }

        nextUpContent.add(sectionLabel("Pick next")).row()
        val current = if (screen.freeTechPick) null else civTech.currentTechnologyName()
        val picks = ruleset.technologies.values
            .filter { it.name in screen.researchableTechs && it.name != current }
            .sortedWith(compareBy({ civTech.remainingScienceToTech(it.name) }, { it.name.tr() }))
        val rows = picks.map { tech ->
            val row = PickRow(ImageGetter.getTechIconPortrait(tech.name, 42f), tech.name, INK,
                unlockMinis(tech, width - 72f - 110f), screen.turnsToTech[tech.name] ?: "")
            row.onClick { pickTech(tech) }
            row.onRightClick {
                pickedGoal = null
                screen.selectTechnology(tech, queue = true)
            }
            row.onDoubleClick(UncivSound.Paper) {
                pickedGoal = null
                screen.selectTechnology(tech)
                screen.tryExit()
            }
            pickRows[tech.name] = row
            row
        }
        if (rows.isNotEmpty()) nextUpContent.add(card(rows)).row()

        if (screen.freeTechPick) return // a free tech is always one of the picks above
        nextUpContent.add(sectionLabel("Goals")).row()
        nextUpContent.add(goalTabs).left().padBottom(8f).row()
        nextUpContent.add(goalRows).row()
    }

    /** What is being researched, or the empty research slot of the mock when nothing is */
    private fun nowCard(width: Float): Table {
        val current = if (screen.freeTechPick) null else civTech.currentTechnology()
        if (current == null) return DashedTable(18f).apply {
            name = "portrait-tech-slot"
            pad(8f, 14f, 8f, 14f)
            add().minHeight(48f).width(0f)
            val ring = DashedTable(20f).apply { add("+".toLabel(INK2, 22)) }
            add(ring).size(40f).padRight(14f)
            val text = Table().left()
            text.add((if (screen.freeTechPick) "Pick a free tech" else "Pick a tech").toLabel(INK2, 16, hideIcons = true)).left().row()
            if (!screen.freeTechPick) text.add("Nothing researching".toLabel(INK2, 13)).left().padTop(2f)
            add(text).growX().left()
        }
        return Table().apply {
            background = tray(18f, TrayColors.CARD)
            pad(12f, 14f, 12f, 14f)
            val disc = ImageGetter.getTechIconPortrait(current.name, 44f).surroundWithCircle(52f, false, YELLOW)
            add(disc).size(52f).padRight(14f)
            val middle = Table().left()
            middle.add("Researching".toLabel(TrayColors.SECTION, 13)).left().row()
            middle.add(current.name.toLabel(INK, 17, hideIcons = true)).left().padTop(2f).row()
            val progress = civTech.researchOfTech(current.name) / civTech.costOfTech(current.name).toFloat()
            val barWidth = width - 28f - 66f - 80f
            middle.add(
                ImageGetter.ProgressBar(barWidth, 8f, false)
                    .setBackground(TrayColors.TRACK)
                    .setProgress(SCIENCE, progress.coerceIn(0f, 1f))
            ).left().padTop(8f)
            add(middle).growX().left()
            add(turnsLabel(screen.turnsToTech[current.name] ?: "")).padLeft(8f).row()
        }
    }

    /** One line of what a tech unlocks, as many as fit */
    private fun unlockMinis(tech: Technology, width: Float): Table {
        val row = Table()
        var used = 0f
        for ((name, kind) in unlocks(tech)) {
            val mini = Table()
            mini.add(ImageGetter.getConstructionPortrait(name, 18f)).size(20f).padRight(4f)
            mini.add(name.toLabel(if (kind == GoalKind.Wonders) WONDER else INK2, 13, hideIcons = true))
            mini.pack()
            if (used + mini.width > width) break
            row.add(mini).padRight(8f)
            used += mini.width + 8f
        }
        if (row.hasChildren()) return row
        // Nothing to build: show whatever else the tech brings (improvements, abilities) as icons
        for (icon in TechnologyDescriptions.getTechEnabledIcons(tech, civInfo, 20f).take(6))
            row.add(icon).size(22f).padRight(6f)
        return row
    }

    private fun rebuildGoalRows() {
        if (screen.freeTechPick) return
        goalTabs.clear()
        for (kind in GoalKind.entries) {
            val on = kind == goalKind
            val pill = Table().apply {
                background = tray(22f, if (on) TrayColors.SEGMENT_ON else TrayColors.FILL)
                add(kind.label.toLabel(if (on) INK else INK2, 15)).pad(0f, 18f, 0f, 18f)
            }
            val tab = Table()
            tab.touchable = Touchable.enabled
            tab.name = "portrait-goal-${kind.name}"
            tab.add(pill).height(44f).minWidth(44f)
            tab.onClick {
                if (goalKind == kind) return@onClick
                goalKind = kind
                if (pickedGoal != null) screen.clearSelection() else refresh()
            }
            goalTabs.add(tab).height(max(48f, touch)).padRight(8f)
        }
        goalRows.clear()
        val rows = goals(goalKind).map { goal ->
            val via = Table()
            via.add(ImageGetter.getTechIconPortrait(goal.tech.name, 16f)).size(18f).padRight(5f)
            via.add(goal.tech.name.toLabel(INK2, 13, hideIcons = true))
            val row = PickRow(ImageGetter.getConstructionPortrait(goal.name, 42f), goal.name,
                if (goal.kind == GoalKind.Wonders) WONDER else INK, via, turnsFor(goal.remainingScience))
            row.setPicked(pickedGoal?.name == goal.name)
            row.onClick { pickGoal(goal) }
            row
        }
        if (rows.isNotEmpty()) goalRows.add(card(rows)).width(contentWidth - 24f)
    }

    /** The raised footer: the research action (and the path it queues) whenever a tech is picked, nothing otherwise.
     *  The Full tree tab stays one tap away in the segmented control, so no footer repeats it. */
    private fun rebuildFooter() {
        val tech = screen.selectedTech
        if (tech == null) {
            sheet.removeFooter()
            return
        }
        val column = Table()
        val goal = if (showTree) null else pickedGoal
        if (goal != null) {
            val head = Table()
            head.add(ImageGetter.getConstructionPortrait(goal.name, sheet.pt(24f))).size(sheet.pt(26f)).padRight(sheet.pt(8f))
            head.add(goal.name.toLabel(INK, sheet.font(17f), hideIcons = true)).left()
            head.add().growX()
            head.add(footerTurns(turnsFor(screen.tempTechsToResearch.sumOf { civTech.remainingScienceToTech(it) })))
            column.add(head).growX().padBottom(sheet.pt(8f)).row()
        }
        if (goal != null || (screen.tempTechsToResearch.size > 1 && !screen.freeTechPick))
            column.add(pathLine()).growX().padBottom(sheet.pt(10f)).row()
        column.add(actionButton()).growX()
        sheet.footer().add(column).growX().padRight(0f)
    }

    private fun pickTech(tech: Technology) {
        if (pickedGoal == null && screen.selectedTech == tech) return screen.clearSelection()
        pickedGoal = null
        screen.selectTechnology(tech)
    }

    private fun pickGoal(goal: Goal) {
        if (pickedGoal?.name == goal.name) {
            pickedGoal = null
            return screen.clearSelection()
        }
        pickedGoal = goal
        screen.selectTechnology(goal.tech)
    }

    private val requiredTechs = HashMap<String, List<String>>()
    private val goalCache = HashMap<GoalKind, List<Goal>>()

    /** Things to aim for, closest first: each needs its tech and every missing prerequisite */
    private fun goals(kind: GoalKind): List<Goal> = goalCache.getOrPut(kind) {
        val builtWonders = civInfo.gameInfo.getCities().flatMap { it.cityConstructions.getBuiltBuildings() }
            .filter { it.isWonder }.map { it.name }.toSet()
        val goals = ArrayList<Goal>()
        for (tech in ruleset.technologies.values) {
            if (civTech.isResearched(tech.name)) continue
            val path = requiredTechs.getOrPut(tech.name) { civTech.getRequiredTechsToDestination(tech).map { it.name } }
            if (path.isEmpty()) continue
            val remaining = path.sumOf { civTech.remainingScienceToTech(it) }
            for ((name, unlockKind) in unlocks(tech))
                if (unlockKind == kind && name !in builtWonders) goals += Goal(name, kind, tech, remaining)
        }
        goals.sortedWith(compareBy({ it.remainingScience }, { it.name.tr() })).take(8)
    }

    //endregion
    //region Full tree

    private inner class TreeView : Table() {
        private val lanes = TechTreeLanes(ruleset, contentWidth)
        private val nodes = HashMap<String, TechNode>()
        private val lines = TechTreeLines()
        private val canvas = object : WidgetGroup() {
            override fun getPrefWidth() = contentWidth
            override fun getPrefHeight() = lanes.height
        }
        private val scroll = verticalScroll(canvas)
        private val detail = Table()
        private val eraButtons = ArrayList<Table>()
        private val eraRow = Table()
        private val eraScroll = AutoScrollPane(eraRow).apply {
            setScrollingDisabled(false, true)
            setOverscroll(false, false)
        }
        private var shownEra = -1
        /** A tech to bring into view, and whether to center it (else just make it visible).
         *  Applied on every layout until the next [act], so it survives the size changes of the first frame. */
        private var pendingScroll: Pair<String, Boolean>? = null
        private var laidOutSincePending = false

        init {
            val height = lanes.height
            canvas.setSize(contentWidth, height)
            for ((index, band) in lanes.bands.withIndex()) {
                val fill = Image(solid(if (index % 2 == 1) BAND_ODD else SHEET))
                fill.setBounds(0f, height - band.top - band.height, contentWidth, band.height)
                canvas.addActor(fill)
                val edge = Image(solid(LINE))
                edge.setBounds(0f, height - band.top - 1f, contentWidth, 1f)
                canvas.addActor(edge)
            }
            canvas.addActor(lines)
            lines.casingColor = CASING
            for ((index, band) in lanes.bands.withIndex()) {
                val inEra = ruleset.technologies.values.filter { it.column != null && it.era() == band.era }
                val pill = Table()
                pill.background = rounded(BAR, BaseScreen.skinStrings.roundedEdgeRectangleShape)
                pill.pad(0f, 14f, 0f, 14f)
                pill.add(band.era.toLabel(INK2, 16)).padRight(8f)
                pill.add("${inEra.count { civTech.isResearched(it.name) }}/${inEra.size}".toLabel(INK3, 14))
                pill.pack()
                pill.setSize(pill.prefWidth, 34f)
                pill.setPosition(10f, height - band.top - 8f - pill.height)
                pill.touchable = Touchable.disabled
                canvas.addActor(pill)

                val eraButton = Table()
                eraButton.touchable = Touchable.enabled
                eraButton.pad(0f, 16f, 0f, 16f)
                eraButton.add(band.era.toLabel(fontSize = 15))
                eraButton.onClick { scroll.scrollY = band.top }
                eraButtons += eraButton
                eraRow.add(eraButton).height(max(44f, touch)).padRight(6f)
                if (index == 0) eraRow.padLeft(10f)
            }
            for (tech in ruleset.technologies.values) {
                val center = lanes.centers[tech.name] ?: continue
                val node = TechNode(tech)
                val top = center.y - TechTreeLanes.DISC_CENTER + node.discTopInset
                node.setPosition(center.x - node.width / 2, height - top - node.height)
                nodes[tech.name] = node
            }
            // a link may not pass behind another tech's disc, name or icons
            val boxes = nodes.mapValues { (_, node) -> Rectangle(node.x + node.width / 2 - 36f, node.y, 72f, node.discTop() - node.y) }
            val gutters = lanes.laneX.zipWithNext { a, b -> (a + b) / 2 }
            for (tech in ruleset.technologies.values) {
                val node = nodes[tech.name] ?: continue
                for (prerequisite in tech.prerequisites) {
                    val parent = nodes[prerequisite] ?: continue
                    val obstacles = boxes.filterKeys { it != tech.name && it != prerequisite }.values.toList()
                    lines.addLink(prerequisite, tech.name,
                        parent.x + parent.width / 2, parent.y - 2f,
                        node.x + node.width / 2, node.discTop() - 4f, obstacles, gutters)
                }
            }
            lines.setBounds(0f, 0f, contentWidth, height)
            for (node in nodes.values) canvas.addActor(node)

            add(scroll).grow().prefHeight(0f).row()
            add(detail).growX().pad(8f, 12f, 8f, 12f).row()
            add(Image(solid(LINE))).growX().height(1f).row()
            add(eraScroll).growX().pad(6f, 0f, 6f, 0f)

            val start = (screen.selectedTech ?: civTech.currentTechnology())?.name ?: screen.researchableTechs.firstOrNull()
            if (start != null) pendingScroll = start to true
        }

        fun refresh() {
            val path = screen.tempTechsToResearch
            val selected = screen.selectedTech?.name
            for ((name, node) in nodes) node.update(path.indexOf(name), name == selected)
            for (link in lines.links) {
                val onPath = link.tech in path && (link.prerequisite in path || civTech.isResearched(link.prerequisite))
                val leadsOut = selected != null && link.prerequisite == selected && !onPath
                link.color = when {
                    onPath -> YELLOW
                    leadsOut -> Color.WHITE
                    civTech.isResearched(link.tech) -> LINE_DONE
                    civTech.isResearched(link.prerequisite) -> LINE_OPEN
                    else -> LINE_LOCKED
                }
                link.hot = onPath || leadsOut
                link.dashed = leadsOut
                link.width = if (link.hot) 4.5f else 3f
            }
            rebuildDetail()
            invalidateHierarchy()
        }

        fun centerOn(techName: String) {
            pendingScroll = techName to true
            invalidate()
        }

        override fun layout() {
            super.layout()
            val (name, center) = pendingScroll ?: return
            val node = nodes[name] ?: return
            laidOutSincePending = true
            scroll.layout()
            // scrollY counts from the top; ScrollPane.scrollTo's vertical centering lands above center, so do it here
            val top = canvas.height - node.y - node.height
            scroll.scrollY = when {
                center -> top + node.height / 2 - scroll.height / 2
                top < scroll.scrollY -> top - 8f
                top + node.height > scroll.scrollY + scroll.height -> top + node.height - scroll.height + 8f
                else -> scroll.scrollY
            }
            if (center) scroll.updateVisualScroll()
        }

        override fun act(delta: Float) {
            super.act(delta)
            if (laidOutSincePending) {
                pendingScroll = null
                laidOutSincePending = false
            }
            val y = scroll.visualScrollY + 60f
            val era = lanes.bands.indexOfLast { it.top <= y }.coerceAtLeast(0)
            if (era == shownEra || eraButtons.isEmpty()) return
            shownEra = era
            for ((index, button) in eraButtons.withIndex()) {
                val on = index == era
                button.background = tray(22f, if (on) TrayColors.SEGMENT_ON else TrayColors.FILL)
                (button.children.first() as Label).color = if (on) INK else INK2
            }
            val button = eraButtons[era]
            eraScroll.scrollTo(button.x - 10f, 0f, button.width + 20f, button.height)
        }

        private fun select(tech: Technology, queue: Boolean = false) {
            pendingScroll = tech.name to false
            pickedGoal = null
            screen.selectTechnology(tech, queue = queue)
        }

        private fun rebuildDetail() {
            detail.clear()
            val tech = screen.selectedTech
            if (tech == null) {
                detail.background = null
                return
            }
            val width = contentWidth - 52f
            detail.background = tray(18f, TrayColors.CARD)
            detail.pad(12f, 14f, 12f, 14f)

            val head = Table()
            val pedia = Table().left()
            pedia.name = "portrait-tech-pedia"
            pedia.touchable = Touchable.enabled
            pedia.add(ImageGetter.getTechIconPortrait(tech.name, 50f)).size(52f).padRight(12f)
            val titles = Table().left()
            titles.add(tech.name.toLabel(fontSize = 20, hideIcons = true)).left().row()
            val era = ruleset.eras[tech.era()]
            val facts = listOfNotNull(
                "${civTech.costOfTech(tech.name)}${Fonts.science}",
                if (civTech.isResearched(tech.name) && !tech.isContinuallyResearchable()) null
                    else "${screen.turnsToTech[tech.name]}${Fonts.turn}",
                era?.name?.tr()
            )
            titles.add(facts.joinToString("   ").toLabel(INK2, 14)).left().padTop(2f)
            pedia.add(titles).left()
            pedia.onClick { screen.openCivilopedia(tech.makeLink()) }
            head.add(pedia).growX().left().minHeight(touch)
            val close = closeIcon()
            close.onClick { screen.clearSelection() }
            head.add(close).size(touch)
            detail.add(head).growX().row()

            val info = Table().left()
            info.defaults().growX().left().padBottom(12f)
            val unlocked = unlocks(tech)
            for ((title, kind) in listOf("Wonders enabled" to GoalKind.Wonders, "Buildings enabled" to GoalKind.Buildings, "Units enabled" to GoalKind.Units)) {
                val chips = unlocked.filter { it.second == kind }.map { (name, k) -> unlockChip(name, k) }
                if (chips.isNotEmpty()) info.add(infoGroup(title, flow(chips, width))).row()
            }
            val improvements = ruleset.tileImprovements.values.filter { it.techRequired == tech.name }
                .map { improvementChip(it.name) }
            if (improvements.isNotEmpty()) info.add(infoGroup("Tile improvements enabled", flow(improvements, width))).row()
            val abilities = tech.uniqueObjects.filter { !it.isHiddenToUsers() }
            if (abilities.isNotEmpty()) {
                val list = Table().left()
                for (unique in abilities)
                    list.add(wrapped(unique.getDisplayText(), Color.WHITE, width)).width(width).padBottom(4f).row()
                info.add(list).row()
            }
            if (tech.prerequisites.isNotEmpty())
                info.add(infoGroup("Requires", flow(tech.prerequisites.map { techChip(it) }, width))).row()
            val leadsTo = ruleset.technologies.values.filter { tech.name in it.prerequisites }
            if (leadsTo.isNotEmpty())
                info.add(infoGroup("Leads to:", flow(leadsTo.map { techChip(it.name) }, width))).row()
            if (tech.quote.isNotEmpty())
                info.add(wrapped(tech.quote, INK3, width)).width(width).row()
            val infoScroll = verticalScroll(info)
            detail.add(infoScroll).growX().height(info.prefHeight.coerceAtMost(170f)).padTop(12f).row()
        }

        private fun infoGroup(title: String, content: Actor) = Table().apply {
            left()
            add(title.toLabel(INK3, 13)).left().padBottom(6f).row()
            add(content).left()
        }

        private fun techChip(techName: String): Actor {
            val chip = Table()
            chip.background = rounded(CHIP, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape)
            chip.pad(6f, 7f, 6f, 11f)
            chip.touchable = Touchable.disabled
            chip.add(ImageGetter.getTechIconPortrait(techName, 24f)).size(26f).padRight(6f)
            chip.add(techName.toLabel(if (civTech.isResearched(techName)) INK2 else Color.WHITE, 14, hideIcons = true))
            return Container(chip).prefHeight(touch).minHeight(touch).apply {
                touchable = Touchable.enabled
                onClick { ruleset.technologies[techName]?.let { select(it) } }
            }
        }

        /** One tech on the tree: its disc with state rings and badges, the name, and a row of what it unlocks */
        private inner class TechNode(val tech: Technology) : Group() {
            private val portrait = ImageGetter.getTechIconPortrait(tech.name, DISC) as Portrait
            private val outerRing = ImageGetter.getCircle()
            private val innerRing = ImageGetter.getCircle()
            private val queueBadge: Group
            private val queueLabel = "".toLabel(YELLOW_INK, 14, Align.center)
            private val nameLabel = tech.name.toLabel(Color.WHITE, 14, Align.center, hideIcons = true)
            private val defaultIconColor = portrait.image.color.cpy()
            /** Space above the disc, kept for the selection ring */
            val discTopInset = 12f

            init {
                touchable = Touchable.enabled
                width = NODE_WIDTH
                nameLabel.wrap = true
                nameLabel.width = NODE_WIDTH
                nameLabel.height = nameLabel.prefHeight
                val icons = Table()
                for (icon in TechnologyDescriptions.getTechEnabledIcons(tech, civInfo, 18f).take(4))
                    icons.add(icon).size(20f).pad(0f, 1.5f, 0f, 1.5f)
                icons.pack()
                height = discTopInset + DISC + 8f + nameLabel.height + 4f + icons.height

                val discCenterY = height - discTopInset - DISC / 2
                for (ring in listOf(outerRing, innerRing)) {
                    ring.isVisible = false
                    addActor(ring)
                }
                portrait.setPosition(width / 2 - portrait.width / 2, discCenterY - portrait.height / 2)
                addActor(portrait)
                nameLabel.setPosition(0f, discCenterY - DISC / 2 - 8f - nameLabel.height)
                addActor(nameLabel)
                icons.setPosition(width / 2 - icons.width / 2, 0f)
                addActor(icons)

                if (!civTech.isResearched(tech.name) || tech.isContinuallyResearchable()) {
                    val turns = Table()
                    turns.background = rounded(SHEET, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape)
                    turns.pad(1f, 7f, 1f, 7f)
                    turns.add((screen.turnsToTech[tech.name] ?: "").toLabel(Color.WHITE, 13))
                    turns.pack()
                    turns.setPosition(width / 2 + DISC / 2 - turns.width + 12f, discCenterY - DISC / 2 - 4f)
                    addActor(turns)
                }
                queueBadge = queueLabel.surroundWithCircle(26f, false, YELLOW).surroundWithCircle(30f, false, CASING)
                queueBadge.setPosition(width / 2 - DISC / 2 - 8f, discCenterY + DISC / 2 - 22f)
                addActor(queueBadge)
                for (child in children) child.touchable = Touchable.disabled

                onClick { select(tech) }
                onRightClick { select(tech, queue = true) }
                onDoubleClick(UncivSound.Paper) {
                    select(tech)
                    screen.tryExit()
                }
            }

            /** y of the disc's top edge in the canvas */
            fun discTop() = y + height - discTopInset

            fun update(queueIndex: Int, selected: Boolean) {
                val researched = civTech.isResearched(tech.name) && !tech.isContinuallyResearchable()
                val current = !screen.freeTechPick && civTech.currentTechnologyName() == tech.name
                val open = tech.name in screen.researchableTechs
                val onPath = queueIndex >= 0
                if (!portrait.isPortrait) {
                    val disc = portrait.background.children.last()
                    disc.color = when {
                        researched -> DONE
                        open || current || onPath -> Color.WHITE
                        else -> LOCKED_DISC
                    }
                    portrait.image.color = when {
                        researched -> Color.WHITE
                        open || current || onPath -> defaultIconColor
                        else -> LOCKED_ICON
                    }
                } else portrait.color.a = if (researched || open || onPath) 1f else 0.6f
                nameLabel.color = when {
                    onPath || selected || current -> Color.WHITE
                    researched -> INK2
                    open -> Color.WHITE
                    else -> INK3
                }
                ring(outerRing, if (selected) SELECT_GLOW else null, DISC + 24f)
                ring(innerRing, when {
                    selected -> Color.WHITE
                    current || onPath -> YELLOW
                    else -> null
                }, if (selected || current) DISC + 12f else DISC + 9f)
                queueBadge.isVisible = onPath && screen.tempTechsToResearch.size > 1
                queueLabel.setText((queueIndex + 1).tr())
            }

            private fun ring(ring: Image, color: Color?, size: Float) {
                ring.isVisible = color != null
                if (color == null) return
                ring.color = color
                ring.setSize(size, size)
                ring.setPosition(width / 2 - size / 2, height - discTopInset - DISC / 2 - size / 2)
            }
        }
    }

    //endregion
    //region shared pieces

    /** The yellow action from the mock: research, queue a path, or pick a free tech; commits and closes */
    private fun actionButton(): Actor {
        val enabled = screen.rightSideButton.isEnabled
        val path = screen.tempTechsToResearch
        val tech = screen.selectedTech
        val text = when {
            tech != null && civTech.isResearched(tech.name) && !tech.isContinuallyResearchable() -> "Already researched".tr()
            !enabled || tech == null -> screen.rightSideButton.text.toString().replace('\n', ' ')
            screen.freeTechPick -> "Pick [${tech.name}] as free tech".tr(hideIcons = true)
            path.size > 1 -> "Queue [${path.size}] techs".tr(hideIcons = true)
            else -> "Research [${path.first()}]".tr(hideIcons = true)
        }
        val turns = when {
            !enabled || tech == null || screen.freeTechPick -> ""
            else -> turnsFor(path.sumOf { civTech.remainingScienceToTech(it) })
        }
        val label = if (turns.isEmpty()) text else "$text, $turns${Fonts.turn}"
        if (!enabled) return sheet.button(label, TraySheet.ButtonKind.Secondary) {}.apply {
            name = "portrait-tech-action"
            touchable = Touchable.disabled
            color.a = .5f
        }
        return sheet.button(label, sound = UncivSound.Paper) { screen.tryExit() }.apply { name = "portrait-tech-action" }
    }

    /** The queued techs in order: 1 Writing -> 2 Philosophy -> ... (footer, stage units) */
    private fun pathLine(): Actor {
        val row = Table().left()
        for ((index, techName) in screen.tempTechsToResearch.withIndex()) {
            if (index > 0) row.add("→".toLabel(TrayColors.SECTION, sheet.font(14f))).padLeft(sheet.pt(6f)).padRight(sheet.pt(6f))
            row.add((index + 1).tr().toLabel(YELLOW_INK, sheet.font(12f), Align.center).surroundWithCircle(sheet.pt(22f), false, YELLOW))
                .padRight(sheet.pt(5f))
            row.add(techName.toLabel(INK, sheet.font(14f), hideIcons = true))
        }
        return AutoScrollPane(row).apply {
            setScrollingDisabled(false, true)
            setOverscroll(false, false)
        }
    }

    private fun footerTurns(turns: String) = Table().apply {
        add(turns.toLabel(INK, sheet.font(17f))).padRight(sheet.pt(4f))
        add("turns".toLabel(INK2, sheet.font(14.5f))).padTop(sheet.pt(2f))
    }

    private fun unlockChip(name: String, kind: GoalKind) = Table().apply {
        background = tray(15f, if (kind == GoalKind.Wonders) TrayColors.LOCK_BG else TrayColors.FILL)
        pad(5f, 7f, 5f, 11f)
        add(ImageGetter.getConstructionPortrait(name, 22f)).size(24f).padRight(6f)
        add(name.toLabel(if (kind == GoalKind.Wonders) WONDER else INK, 14, hideIcons = true))
    }

    private fun improvementChip(name: String) = Table().apply {
        background = tray(15f, TrayColors.FILL)
        pad(5f, 7f, 5f, 11f)
        add(ImageGetter.getImprovementPortrait(name, 22f)).size(24f).padRight(6f)
        add(name.toLabel(INK, 14, hideIcons = true))
    }

    /** Buildings, wonders and units a tech makes available to this civ */
    private fun unlocks(tech: Technology): List<Pair<String, GoalKind>> {
        val buildings = TechnologyDescriptions.getEnabledBuildings(tech.name, ruleset, civInfo)
        val units = TechnologyDescriptions.getEnabledUnits(tech.name, ruleset, civInfo)
        return buildings.filter { it.isWonder }.map { it.name to GoalKind.Wonders }.toList() +
            buildings.filter { !it.isWonder }.map { it.name to GoalKind.Buildings } +
            units.map { it.name to GoalKind.Units }
    }

    private fun turnsFor(remainingScience: Int) = when {
        remainingScience <= 0 -> 0.tr()
        science <= 0f -> Fonts.infinity.toString()
        else -> max(1, ceil(remainingScience / science).toInt()).tr()
    }

    /** The row's trailing value: "∞ turns", as the mock writes it */
    private fun turnsLabel(turns: String) = Table().apply {
        add(turns.toLabel(INK2, 17)).padRight(4f)
        add("turns".toLabel(INK2, 14)).padTop(2f)
    }

    private fun closeIcon() = ImageGetter.getImage("OtherIcons/Close").apply {
        setSize(18f, 18f)
        color = TrayColors.CLOSE_INK
    }.surroundWithCircle(44f, false, TrayColors.FILL)

    /** Section label above a card, as the kit's: 28pt from the sheet edge */
    private fun sectionLabel(text: String) = Table().apply {
        add(text.toLabel(TrayColors.SECTION, 13)).left().growX()
        pad(18f, 16f, 8f, 16f)
    }

    /** A wrapping label already sized to [width], so its height is right before the first layout */
    private fun wrapped(text: String, color: Color, width: Float) = text.toLabel(color, 14).apply {
        wrap = true
        this.width = width
        height = prefHeight
    }

    /** Lays [items] out left to right, wrapping into rows no wider than [width] */
    private fun flow(items: List<Actor>, width: Float, gap: Float = 6f): Table {
        val table = Table().left()
        var row = Table()
        var used = 0f
        for (item in items) {
            val itemWidth = (item as? Layout)?.prefWidth ?: item.width
            if (used > 0f && used + itemWidth > width) {
                table.add(row).left().padBottom(gap).row()
                row = Table()
                used = 0f
            }
            row.add(item).padRight(gap)
            used += itemWidth + gap
        }
        table.add(row).left()
        return table
    }

    /** The kit's rounded shape at [radius] content points, drawn sharp although the content is scaled up */
    private fun tray(radius: Float, color: Color): Drawable {
        val patch = NinePatch(art.rounded(radius * sheet.p).patch)
        patch.scale(1f / sheet.p, 1f / sheet.p)
        return NinePatchDrawable(patch).tinted(color)
    }

    /** The kit's inset grouped card in content points: hairlines start after the lead disc */
    private fun card(rows: List<Actor>) = Table().apply {
        background = tray(18f, TrayColors.CARD)
        for ((index, row) in rows.withIndex()) {
            if (index > 0) add(Image(solid(TrayColors.LINE))).height(1f).growX().padLeft(72f).padRight(14f).row()
            add(row).growX().row()
        }
    }

    /** A tray row in content points: lead, title over [meta], the turns, a chevron, or the yellow check when picked */
    private inner class PickRow(lead: Actor, title: String, titleColor: Color, meta: Actor?, turns: String) : Table() {
        private val end = Container<Actor>()
        private val chevron = ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = TrayColors.CHEVRON }
        private val check = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(YELLOW)
            add(ImageGetter.getImage("OtherIcons/Checkmark").apply { color = YELLOW_INK }).size(14f)
        }

        init {
            touchable = Touchable.enabled
            pad(0f, 14f, 0f, 14f)
            add().minHeight(max(64f, touch)).width(0f)
            add(lead).size(44f).padRight(14f)
            val text = Table().left()
            text.add(title.toLabel(titleColor, 17, hideIcons = true).apply { wrap = true }).growX().left().row()
            if (meta != null) text.add(meta).left().padTop(4f)
            add(text).growX().minWidth(0f).padTop(9f).padBottom(9f)
            add(turnsLabel(turns)).padLeft(8f)
            add(end).size(26f).padLeft(8f)
            setPicked(false)
        }

        fun setPicked(picked: Boolean) {
            background = if (picked) tray(18f, TrayColors.SELECTED) else null
            end.actor = if (picked) check else chevron
            end.size(if (picked) 26f else 14f)
        }
    }

    /** The empty research slot: a dashed outline around the table, as the mock draws it */
    private inner class DashedTable(private val radius: Float) : Table() {
        private val dot = ImageGetter.getWhiteDotDrawable().region

        override fun drawBackground(batch: Batch, parentAlpha: Float, x: Float, y: Float) {
            val thick = 2f
            val w = width - thick
            val h = height - thick
            val r = min(radius, min(w, h) / 2f)
            val arc = (Math.PI / 2 * r).toFloat()
            val lengths = floatArrayOf(w - 2 * r, arc, h - 2 * r, arc, w - 2 * r, arc, h - 2 * r, arc)
            val total = lengths.sum()
            val period = total / max(1, (total / 11f).roundToInt())
            batch.setColor(TrayColors.SEGMENT_ON.r, TrayColors.SEGMENT_ON.g, TrayColors.SEGMENT_ON.b, color.a * parentAlpha)
            val point = Vector2()
            var s = 0f
            while (s < total) {
                if (s % period < period * .55f) {
                    val angle = pointAt(s, lengths, w, h, r, point)
                    batch.draw(dot, x + thick / 2 + point.x - .75f, y + thick / 2 + point.y - thick / 2, .75f, thick / 2, 1.5f, thick, 1f, 1f, angle)
                }
                s += 1f
            }
        }

        /** Walks the rounded rectangle counter-clockwise from the bottom left; returns the tangent angle in degrees */
        private fun pointAt(distance: Float, lengths: FloatArray, w: Float, h: Float, r: Float, out: Vector2): Float {
            var s = distance
            for ((index, length) in lengths.withIndex()) {
                if (s > length && index < lengths.lastIndex) { s -= length; continue }
                val k = if (length > 0f) s / length else 0f
                val quarter = Math.PI.toFloat() / 2 * k
                when (index) {
                    0 -> out.set(r + s, 0f)
                    1 -> out.set(w - r + r * sin(quarter), r - r * cos(quarter))
                    2 -> out.set(w, r + s)
                    3 -> out.set(w - r + r * cos(quarter), h - r + r * sin(quarter))
                    4 -> out.set(w - r - s, h)
                    5 -> out.set(r - r * sin(quarter), h - r + r * cos(quarter))
                    6 -> out.set(0f, h - r - s)
                    else -> out.set(r - r * cos(quarter), r - r * sin(quarter))
                }
                return index * 45f + (if (index % 2 == 1) 90f * k - 45f else 0f)
            }
            return 0f
        }
    }

    /** Holds [content] laid out in mock points and scales it to stage units, filling the sheet body */
    private class Scaled(private val content: Group, private val scale: Float) : WidgetGroup() {
        init {
            content.isTransform = true
            content.setScale(scale)
            addActor(content)
        }

        override fun layout() = content.setBounds(0f, 0f, width / scale, height / scale)
        override fun getPrefWidth() = 0f
        override fun getPrefHeight() = 0f
    }

    /** The tree runs edge to edge: the kit insets its body 12pt for cards, so the tab content takes that inset itself */
    private fun TraySheet.fullWidthBody() {
        for (cell in cells) if (cell.actor is ScrollPane) cell.padLeft(0f).padRight(0f)
    }

    private companion object {
        const val NODE_WIDTH = 104f
        const val DISC = 58f

        // Direction B of the drawers mock (the kit's TrayColors), plus the tree's own state colours
        val SHEET: Color = TrayColors.SHEET
        val BAR: Color = TrayColors.FOOTER
        val CHIP: Color = TrayColors.FILL
        val BAND_ODD: Color = Color.valueOf("10202f")
        val LINE: Color = TrayColors.LINE
        val YELLOW: Color = TrayColors.YELLOW
        val YELLOW_INK: Color = TrayColors.YELLOW_INK
        val WONDER: Color = Color.valueOf("ffd35c")
        val SELECT_GLOW: Color = Color(1f, 0.79f, 0.24f, 0.55f)
        val INK: Color = TrayColors.INK
        val INK2: Color = TrayColors.INK2
        val INK3: Color = TrayColors.SECTION
        val SCIENCE: Color = Color.valueOf("5cc8ff")
        val DONE: Color = Color.valueOf("2f7fe0")
        val LOCKED_DISC: Color = Color.valueOf("1f3850")
        val LOCKED_ICON: Color = Color.valueOf("6f8ba4")
        val LINE_DONE: Color = Color.valueOf("3f8ff0")
        val LINE_OPEN: Color = Color.valueOf("9cc0e2")
        val LINE_LOCKED: Color = Color.valueOf("56779a")
        val CASING: Color = TrayColors.SHEET

        fun solid(color: Color): Drawable = ImageGetter.getWhiteDotDrawable().tint(color)

        fun rounded(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleMidShape): Drawable =
            BaseScreen.skinStrings.getUiBackground("", shape, color)

        fun verticalScroll(content: Actor) = AutoScrollPane(content).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
    }
}
