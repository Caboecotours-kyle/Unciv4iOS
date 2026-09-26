package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.Layout
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
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The portrait, one-handed [TechPickerScreen] (DESIGN.md "Tech", mock `mocks/portrait-hud.html`):
 * - **Next up**: current research, every tech researchable now, and goals (wonders, units, buildings) by turns to reach.
 * - **Full tree**: the tree in four lanes that only scrolls vertically, a detail card for the selected tech, era tabs.
 *
 * Selection, queueing and committing stay in [TechPickerScreen]; this view only shows its state and calls back.
 */
internal class TechPickerPortrait(
    private val screen: TechPickerScreen,
    private val contentWidth: Float,
    startOnTree: Boolean,
) : Table() {
    private val civInfo = screen.civInfo
    private val civTech = civInfo.tech
    private val ruleset = civInfo.gameInfo.ruleset
    private val science = civInfo.stats.statsForNextTurn.science

    private enum class GoalKind(val label: String) { Wonders("Wonders"), Units("Units"), Buildings("Buildings") }
    private class Goal(val name: String, val kind: GoalKind, val tech: Technology, val remainingScience: Int)

    private var showTree = startOnTree
    private var goalKind = GoalKind.Wonders
    private var pickedGoal: Goal? = null

    private val tabs = Table()
    private val body = Container<Actor>().fill()

    private val nextUpContent = Table()
    private val nextUpScroll = verticalScroll(nextUpContent)
    private val nextUpBar = Table()
    private val nextUpView = Table()
    private val pickCards = HashMap<String, PickCard>()
    private val goalTabs = Table()
    private val goalRows = Table()
    private var nextUpBuilt = false

    private var tree: TreeView? = null

    init {
        background = solid(SHEET)
        add(header()).growX().row()
        add(tabs).growX().row()
        add(body).grow().prefHeight(0f)
        nextUpView.add(nextUpScroll).grow().prefHeight(0f).row()
        nextUpView.add(nextUpBar).growX()
    }

    /** Re-reads selection and queue from [screen]; called after every change there */
    fun refresh() {
        rebuildTabs()
        if (showTree) {
            val treeView = tree ?: TreeView().also { tree = it }
            treeView.refresh()
            body.actor = treeView
        } else {
            if (!nextUpBuilt) buildNextUp()
            for ((name, card) in pickCards) card.setPicked(pickedGoal == null && screen.selectedTech?.name == name)
            rebuildGoalRows()
            rebuildNextUpBar()
            body.actor = nextUpView
        }
    }

    fun centerOn(tech: Technology) {
        tree?.centerOn(tech.name)
    }

    private fun showTab(treeTab: Boolean) {
        if (showTree == treeTab) return
        showTree = treeTab
        refresh()
    }

    private fun header() = Table().apply {
        pad(10f, 18f, 2f, 12f)
        val titles = Table().left()
        titles.add("Technologies".toLabel(fontSize = 24)).left().row()
        titles.add("+${science.roundToInt()}${Fonts.science}".toLabel(INK2, 15)).left()
        add(titles).growX().left()
        val close = closeIcon()
        close.onClick { screen.game.popScreen() }
        add(close).size(48f)
    }

    private fun rebuildTabs() {
        tabs.clear()
        for ((label, treeTab) in listOf("Next up" to false, "Full tree" to true)) {
            val on = treeTab == showTree
            val tab = Table()
            tab.touchable = Touchable.enabled
            tab.add(label.toLabel(if (on) Color.WHITE else INK3, 17)).expand().center().row()
            tab.add(Image(solid(if (on) YELLOW else Color.CLEAR))).size(96f, 3f)
            tab.onClick { showTab(treeTab) }
            tabs.add(tab).growX().uniformX().height(48f)
        }
        tabs.row()
        tabs.add(Image(solid(LINE))).colspan(2).growX().height(1f)
    }

    //region Next up

    private fun buildNextUp() {
        nextUpBuilt = true
        val width = contentWidth - 28f
        nextUpContent.pad(10f, 14f, 16f, 14f).defaults().growX()
        nextUpContent.add(nowCard(width)).row()

        nextUpContent.add(sectionLabel("Pick next")).padTop(18f).padBottom(8f).row()
        val current = if (screen.freeTechPick) null else civTech.currentTechnologyName()
        val picks = ruleset.technologies.values
            .filter { it.name in screen.researchableTechs && it.name != current }
            .sortedWith(compareBy({ civTech.remainingScienceToTech(it.name) }, { it.name.tr() }))
        for (tech in picks) {
            val card = PickCard(techCardContent(tech, width - 5f))
            card.onClick { pickTech(tech) }
            card.onRightClick {
                pickedGoal = null
                screen.selectTechnology(tech, queue = true)
            }
            card.onDoubleClick(UncivSound.Paper) { screen.tryExit() }
            pickCards[tech.name] = card
            nextUpContent.add(card).padBottom(8f).row()
        }

        if (screen.freeTechPick) return // a free tech is always one of the picks above
        nextUpContent.add(sectionLabel("Goals")).padTop(18f).padBottom(8f).row()
        nextUpContent.add(goalTabs).padBottom(10f).row()
        nextUpContent.add(goalRows).row()
    }

    private fun nowCard(width: Float) = Table().apply {
        background = rounded(PANEL)
        pad(14f)
        val current = if (screen.freeTechPick) null else civTech.currentTechnology()
        if (current == null) {
            add((if (screen.freeTechPick) "Pick a free tech" else "Pick a tech").toLabel(fontSize = 20)).left().height(40f)
            return@apply
        }
        val disc = ImageGetter.getTechIconPortrait(current.name, 56f).surroundWithCircle(68f, false, YELLOW)
        add(disc).size(68f).padRight(12f)
        val middle = Table().left()
        middle.add("Researching".toLabel(INK3, 14)).left().row()
        middle.add(current.name.toLabel(fontSize = 20, hideIcons = true)).left().padTop(2f).row()
        val progress = civTech.researchOfTech(current.name) / civTech.costOfTech(current.name).toFloat()
        val barWidth = width - 28f - 80f - 70f
        middle.add(
            ImageGetter.ProgressBar(barWidth, 8f, false)
                .setBackground(Color.WHITE.cpy().apply { a = 0.15f })
                .setProgress(SCIENCE, progress.coerceIn(0f, 1f))
        ).left().padTop(8f)
        add(middle).growX().left()
        add(turnsLabel(screen.turnsToTech[current.name] ?: "")).padLeft(8f).row()
        val chips = unlocks(current).map { (name, kind) -> unlockChip(name, kind) }
        if (chips.isNotEmpty()) add(flow(chips, width - 28f)).colspan(3).left().padTop(12f)
    }

    private fun techCardContent(tech: Technology, width: Float) = Table().apply {
        pad(8f, 10f, 8f, 12f)
        add(ImageGetter.getTechIconPortrait(tech.name, 48f)).size(50f).padRight(12f)
        val middle = Table().left()
        middle.add(tech.name.toLabel(fontSize = 17, hideIcons = true)).left().row()
        middle.add(unlockMinis(tech, width - 22f - 62f - 70f)).left().padTop(5f)
        add(middle).growX().left()
        add(turnsLabel(screen.turnsToTech[tech.name] ?: "")).padLeft(8f)
    }

    /** One line of what a tech unlocks, as many as fit */
    private fun unlockMinis(tech: Technology, width: Float): Table {
        val row = Table()
        var used = 0f
        for ((name, kind) in unlocks(tech)) {
            val mini = Table()
            mini.add(ImageGetter.getConstructionPortrait(name, 18f)).size(20f).padRight(4f)
            mini.add(name.toLabel(if (kind == GoalKind.Wonders) YELLOW else INK2, 13, hideIcons = true))
            mini.pack()
            if (used + mini.width > width) break
            row.add(mini).padRight(10f)
            used += mini.width + 10f
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
            val tab = Table()
            tab.background = rounded(if (on) Color.WHITE else CARD)
            tab.touchable = Touchable.enabled
            tab.add(kind.label.toLabel(if (on) SHEET else INK3, 15))
            tab.onClick {
                if (goalKind == kind) return@onClick
                goalKind = kind
                if (pickedGoal != null) screen.clearSelection() else refresh()
            }
            goalTabs.add(tab).growX().uniformX().height(48f).pad(0f, 3f, 0f, 3f)
        }
        goalRows.clear()
        for (goal in goals(goalKind)) {
            val content = Table().pad(8f, 10f, 8f, 12f)
            content.add(ImageGetter.getConstructionPortrait(goal.name, 44f)).size(46f).padRight(12f)
            val middle = Table().left()
            middle.add(goal.name.toLabel(if (goal.kind == GoalKind.Wonders) YELLOW else Color.WHITE, 17, hideIcons = true)).left().row()
            val via = Table()
            via.add(ImageGetter.getTechIconPortrait(goal.tech.name, 18f)).size(20f).padRight(5f)
            via.add(goal.tech.name.toLabel(INK2, 14, hideIcons = true))
            middle.add(via).left().padTop(4f)
            content.add(middle).growX().left()
            content.add(turnsLabel(turnsFor(goal.remainingScience))).padLeft(8f)
            val card = PickCard(content)
            card.setPicked(pickedGoal?.name == goal.name)
            card.onClick { pickGoal(goal) }
            goalRows.add(card).growX().padBottom(8f).row()
        }
    }

    private fun rebuildNextUpBar() {
        nextUpBar.clear()
        nextUpBar.background = solid(BAR)
        nextUpBar.pad(12f, 14f, 14f, 14f)
        val tech = screen.selectedTech
        if (tech == null) {
            val button = bigButton("See the full tree".tr(), primary = false)
            button.onClick { showTab(true) }
            nextUpBar.add(button).growX()
            return
        }
        val goal = pickedGoal
        if (goal != null) {
            val head = Table()
            head.add(ImageGetter.getConstructionPortrait(goal.name, 24f)).size(26f).padRight(8f)
            head.add(goal.name.toLabel(fontSize = 17, hideIcons = true)).left()
            head.add().growX()
            head.add(turnsLabel(turnsFor(screen.tempTechsToResearch.sumOf { civTech.remainingScienceToTech(it) })))
            nextUpBar.add(head).growX().padBottom(8f).row()
            nextUpBar.add(pathLine()).growX().padBottom(10f).row()
        }
        nextUpBar.add(actionButton()).growX()
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
                eraRow.add(eraButton).height(48f).padRight(6f)
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
            add(detail).growX().row()
            val eraBar = Table()
            eraBar.background = solid(BAR)
            eraBar.add(eraScroll).growX().pad(8f, 0f, 8f, 0f)
            add(Image(solid(LINE))).growX().height(1f).row()
            add(eraBar).growX()

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
                button.background = rounded(if (on) Color.WHITE else CARD)
                (button.children.first() as Label).color = if (on) SHEET else INK3
            }
            val button = eraButtons[era]
            eraScroll.scrollTo(button.x - 10f, 0f, button.width + 20f, button.height)
        }

        private fun select(tech: Technology, queue: Boolean = false) {
            pendingScroll = tech.name to false
            screen.selectTechnology(tech, queue = queue)
        }

        private fun rebuildDetail() {
            detail.clear()
            val tech = screen.selectedTech
            if (tech == null) {
                detail.background = null
                return
            }
            val width = contentWidth - 32f
            detail.background = rounded(PANEL, BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape)
            detail.pad(12f, 16f, 12f, 16f)

            val head = Table()
            val pedia = Table().left()
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
            head.add(pedia).growX().left()
            val close = closeIcon()
            close.onClick { screen.clearSelection() }
            head.add(close).size(44f)
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

            if (screen.tempTechsToResearch.size > 1 && !screen.freeTechPick)
                detail.add(pathLine()).growX().padTop(4f).row()
            detail.add(actionButton()).growX().padTop(10f)
        }

        private fun infoGroup(title: String, content: Actor) = Table().apply {
            left()
            add(title.toLabel(INK3, 13)).left().padBottom(6f).row()
            add(content).left()
        }

        private fun techChip(techName: String): Table {
            val chip = Table()
            chip.background = rounded(CHIP, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape)
            chip.pad(6f, 7f, 6f, 11f)
            chip.touchable = Touchable.enabled
            chip.add(ImageGetter.getTechIconPortrait(techName, 24f)).size(26f).padRight(6f)
            chip.add(techName.toLabel(if (civTech.isResearched(techName)) INK2 else Color.WHITE, 14, hideIcons = true))
            chip.onClick { ruleset.technologies[techName]?.let { select(it) } }
            return chip
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
                onDoubleClick(UncivSound.Paper) { screen.tryExit() }
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
    private fun actionButton(): Table {
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
        val button = bigButton(if (turns.isEmpty()) text else "$text, $turns${Fonts.turn}", enabled)
        if (enabled) button.onClick(UncivSound.Paper) { screen.tryExit() }
        return button
    }

    private fun bigButton(text: String, primary: Boolean) = Table().apply {
        background = rounded(if (primary) YELLOW else CHIP, BaseScreen.skinStrings.roundedEdgeRectangleShape)
        touchable = Touchable.enabled
        add(text.toLabel(if (primary) YELLOW_INK else INK2, 18, Align.center, hideIcons = true).apply { wrap = true })
            .growX().minHeight(52f).pad(0f, 16f, 0f, 16f)
    }

    /** The queued techs in order: 1 Writing -> 2 Philosophy -> ... */
    private fun pathLine(): Actor {
        val row = Table().left()
        for ((index, techName) in screen.tempTechsToResearch.withIndex()) {
            if (index > 0) row.add("→".toLabel(INK3, 14)).padLeft(6f).padRight(6f)
            row.add((index + 1).tr().toLabel(YELLOW_INK, 12, Align.center).surroundWithCircle(22f, false, YELLOW)).padRight(5f)
            row.add(techName.toLabel(Color.WHITE, 14, hideIcons = true))
        }
        return AutoScrollPane(row).apply {
            setScrollingDisabled(false, true)
            setOverscroll(false, false)
        }
    }

    private fun unlockChip(name: String, kind: GoalKind) = Table().apply {
        background = rounded(if (kind == GoalKind.Wonders) WONDER_CHIP else CHIP, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape)
        pad(6f, 7f, 6f, 11f)
        add(ImageGetter.getConstructionPortrait(name, 22f)).size(24f).padRight(6f)
        add(name.toLabel(if (kind == GoalKind.Wonders) YELLOW else Color.WHITE, 14, hideIcons = true))
    }

    private fun improvementChip(name: String) = Table().apply {
        background = rounded(CHIP, BaseScreen.skinStrings.roundedEdgeRectangleSmallShape)
        pad(6f, 7f, 6f, 11f)
        add(ImageGetter.getImprovementPortrait(name, 22f)).size(24f).padRight(6f)
        add(name.toLabel(Color.WHITE, 14, hideIcons = true))
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

    private fun turnsLabel(turns: String) = Table().apply {
        add(turns.toLabel(fontSize = 18)).padRight(4f)
        add("turns".toLabel(INK3, 13)).padTop(3f)
    }

    private fun closeIcon() = ImageGetter.getImage("OtherIcons/Close").apply { setSize(18f, 18f) }
        .surroundWithCircle(46f, false, CHIP)

    private fun sectionLabel(text: String) = text.toLabel(INK3, 15)

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

    /** A tappable card with the mock's yellow outline when picked */
    private class PickCard(content: Table) : Table() {
        private val inner = Table()
        init {
            touchable = Touchable.enabled
            pad(2.5f)
            inner.pad(0f)
            inner.add(content).growX().minHeight(64f)
            add(inner).growX()
            setPicked(false)
        }
        fun setPicked(picked: Boolean) {
            background = rounded(if (picked) YELLOW else CARD)
            inner.background = if (picked) rounded(CARD_PICKED) else null
        }
    }

    private companion object {
        const val NODE_WIDTH = 104f
        const val DISC = 58f

        // The mock's palette (DESIGN.md "Interface"): navy sheet and panels, yellow for the primary action and the path
        val SHEET: Color = Color.valueOf("132435")
        val PANEL: Color = Color.valueOf("1c3249")
        val BAR: Color = Color.valueOf("0f2030")
        val CARD: Color = Color.valueOf("1e2f40")
        val CARD_PICKED: Color = Color.valueOf("2b3536")
        val CHIP: Color = Color.valueOf("2a3d51")
        val WONDER_CHIP: Color = Color.valueOf("3d3a2e")
        val BAND_ODD: Color = Color.valueOf("182939")
        val LINE: Color = Color(1f, 1f, 1f, 0.1f)
        val YELLOW: Color = Color.valueOf("ffc93c")
        val YELLOW_INK: Color = Color.valueOf("3a2a00")
        val SELECT_GLOW: Color = Color(1f, 0.79f, 0.24f, 0.55f)
        val INK2: Color = Color.valueOf("b7cde0")
        val INK3: Color = Color.valueOf("7f9ab2")
        val SCIENCE: Color = Color.valueOf("5cc8ff")
        val DONE: Color = Color.valueOf("2f7fe0")
        val LOCKED_DISC: Color = Color.valueOf("1f3850")
        val LOCKED_ICON: Color = Color.valueOf("6f8ba4")
        val LINE_DONE: Color = Color.valueOf("3f8ff0")
        val LINE_OPEN: Color = Color.valueOf("9cc0e2")
        val LINE_LOCKED: Color = Color.valueOf("56779a")
        val CASING: Color = Color.valueOf("122536")

        fun solid(color: Color): Drawable = ImageGetter.getWhiteDotDrawable().tint(color)

        fun rounded(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleMidShape): Drawable =
            BaseScreen.skinStrings.getUiBackground("", shape, color)

        fun verticalScroll(content: Actor) = AutoScrollPane(content).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
    }
}
