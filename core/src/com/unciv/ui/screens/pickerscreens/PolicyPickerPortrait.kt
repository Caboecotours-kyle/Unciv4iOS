package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.Align
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.ceil
import kotlin.math.max

/** The approved single-branch policy tree. Policy adoption remains in [PolicyPickerScreen]. */
internal class PolicyPickerPortrait(
    private val screen: PolicyPickerScreen,
    private val widthOnScreen: Float,
    private val branches: Map<String, PolicyBranch>,
    initialBranch: String?,
    initialPolicy: String?,
    private val bottomGap: Float,
) : Table() {
    private val civ = screen.viewingCiv
    private val policies = civ.policies
    private var branch = branches[initialBranch] ?: branches.values.firstOrNull()
    private var selected = initialPolicy?.let { name ->
        (branch?.takeIf { it.name == name } ?: branch?.policies?.firstOrNull { it.name == name })
    } ?: branch?.policies?.firstOrNull { it.policyBranchType == Policy.PolicyBranchType.Member && policies.isAdoptable(it) }
        ?: branch?.policies?.firstOrNull { it.policyBranchType == Policy.PolicyBranchType.Member }
    private val culture = civ.stats.statsForNextTurn.culture
    private val body = Table().top()
    private val rail = Table()
    private val railScroll = AutoScrollPane(rail).apply { setScrollingDisabled(false, true); setOverscroll(false, false) }

    init {
        background = rounded(SHEET.cpy().apply { a = .9f }, BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape)
        add(Table().apply { add(Image(solid(Color(1f, 1f, 1f, .3f)))).size(44f, 5f) }).growX().height(16f).row()
        add(header()).growX().row()
        add(cultureCard()).growX().pad(0f, 14f, 8f, 14f).row()
        add(body).grow().prefHeight(0f).row()
        add(railScroll).growX().height(106f)
        refresh()
    }

    private fun header() = Table().apply {
        pad(9f, 16f, 4f, 16f)
        add("Social Policies".toLabel(fontSize = 24)).growX().left()
        val close = button("×", Color.WHITE, CHIP, 48f, 48f)
        close.onClick { screen.game.popScreen() }
        add(close).size(48f)
    }

    private fun cultureCard(): Table {
        val needed = policies.getCultureNeededForNextPolicy()
        val stored = policies.storedCulture
        val ready = policies.canAdoptPolicy()
        val card = Table().apply {
            background = rounded(PANEL)
            pad(10f, 14f, 10f, 14f)
        }
        card.add(ImageGetter.getImage("StatIcons/Culture")).size(30f).padRight(12f)
        val middle = Table().left()
        middle.add("$stored / $needed".toLabel(fontSize = 18)).left().row()
        val bar = Table().apply { background = rounded(CHIP); add(Image(solid(if (ready) YELLOW else CULTURE))).width((widthOnScreen - 170f) * (stored.toFloat() / needed.coerceAtLeast(1)).coerceIn(0f, 1f)).height(8f).left() }
        middle.add(bar).width(widthOnScreen - 170f).height(8f).padTop(8f)
        card.add(middle).growX().left()
        val turns = if (ready) "Ready" else if (culture > 0f) "${ceil((needed - stored) / culture).toInt()} turns" else "∞ turns"
        card.add(turns.toLabel(if (ready) YELLOW else Color.WHITE, 16)).right()
        return card
    }

    private fun refresh() {
        body.clear()
        val current = branch
        if (current == null) {
            body.add("No social policies in this ruleset".toLabel(INK2, 17)).grow().center()
        } else {
            val tree = tree(current)
            body.add(tree).grow().prefHeight(0f)
        }
        rail.clear()
        rail.pad(8f, 10f, bottomGap, 10f)
        for (candidate in branches.values) {
            val chosen = current == candidate
            val adopted = policies.isAdopted(candidate.name)
            val done = candidate.policies.count { it.policyBranchType == Policy.PolicyBranchType.Member && policies.isAdopted(it.name) }
            val tile = Table().apply {
                touchable = Touchable.enabled
                background = rounded(if (chosen) Color.WHITE else CARD)
                pad(4f)
            }
            val icon = ImageGetter.getImage("PolicyBranchIcons/${candidate.name}").apply { color = if (chosen) SHEET else if (adopted) Color.WHITE else INK3 }
            tile.add(icon).size(26f).row()
            tile.add(candidate.name.toLabel(if (chosen) SHEET else if (adopted) INK2 else INK3, 13, Align.center, hideIcons = true)).width(68f).padTop(4f)
            if (done > 0) {
                val badge = "$done".toLabel(Color.WHITE, 11, Align.center)
                badge.setBounds(53f, 48f, 20f, 20f)
                tile.addActor(badge)
            }
            tile.onClick {
                branch = candidate
                screen.rememberPortraitBranch(candidate.name)
                selected = candidate.policies.firstOrNull { it.policyBranchType == Policy.PolicyBranchType.Member && policies.isAdoptable(it) }
                    ?: candidate.policies.firstOrNull { it.policyBranchType == Policy.PolicyBranchType.Member }
                refresh()
            }
            rail.add(tile).width(76f).height(70f).padRight(6f)
        }
    }

    private fun tree(current: PolicyBranch): Actor {
        val members = current.policies.filter { it.policyBranchType == Policy.PolicyBranchType.Member }
        val maxRow = members.maxOfOrNull { it.row } ?: 1
        val maxCol = max(5, members.maxOfOrNull { it.column } ?: 5)
        val height = 72f + maxRow * 124f + 190f
        val canvas = object : WidgetGroup() {
            override fun getPrefWidth() = widthOnScreen
            override fun getPrefHeight() = height
        }.apply { setSize(widthOnScreen, height) }
        val lines = TechTreeLines().apply { casingColor = SHEET; setBounds(0f, 0f, widthOnScreen, height) }
        val centers = HashMap<String, Pair<Float, Float>>()
        fun lane(col: Int) = 46f + (col - 1) * ((widthOnScreen - 92f) / (maxCol - 1))
        fun row(row: Int) = height - (if (row == 0) 72f else 72f + row * 124f)
        centers[current.name] = widthOnScreen / 2f to row(0)
        for (policy in members) centers[policy.name] = lane(policy.column) to row(policy.row)
        for (policy in members) for (prereq in policy.requires.orEmpty()) {
            val from = centers[prereq] ?: continue
            val to = centers[policy.name] ?: continue
            lines.addLink(prereq, policy.name, from.first, from.second - 34f, to.first, to.second + 34f, emptyList<Rectangle>(), emptyList())
        }
        for (link in lines.links) {
            link.color = when {
                policies.isAdopted(link.tech) -> DONE
                policies.isAdopted(link.prerequisite) -> OPEN
                else -> LOCKED_LINE
            }
        }
        canvas.addActor(lines)
        for (policy in listOf<Policy>(current) + members) {
            val center = centers[policy.name] ?: continue
            val node = node(policy)
            node.setPosition(center.first - node.width / 2f, center.second - 47f)
            canvas.addActor(node)
        }
        val adoptedMembers = members.count { policies.isAdopted(it.name) }
        val state = when {
            policies.isAdopted(current.name) -> "$adoptedMembers of ${members.size}"
            civ.gameInfo.ruleset.eras[current.era]?.eraNumber?.let { it > civ.getEraNumber() } == true -> "${current.era} era"
            else -> "Not opened"
        }
        val status = state.toLabel(INK3, 13, Align.center)
        status.setBounds(0f, row(0) - 75f, widthOnScreen, 24f)
        canvas.addActor(status)
        val complete = current.policies.firstOrNull { it.policyBranchType == Policy.PolicyBranchType.BranchComplete }
        if (complete != null) {
            val card = Table().apply {
                background = rounded(if (policies.isAdopted(complete.name)) COMPLETE else CARD)
                pad(12f, 14f, 12f, 14f)
            }
            card.add("★  Complete all ${members.size}".toLabel(if (policies.isAdopted(complete.name)) YELLOW else INK2, 16)).left().row()
            val effects = complete.uniqueObjects.filterNot { it.isHiddenToUsers() }.joinToString(" · ") { it.getDisplayText().tr() }
            val label = effects.toLabel(INK2, 13).apply { wrap = true }
            card.add(label).width(widthOnScreen - 76f).left().padTop(4f)
            card.setBounds(24f, 18f, widthOnScreen - 48f, 100f)
            canvas.addActor(card)
        }
        val column = Table().top()
        val scroll = AutoScrollPane(canvas).apply { setScrollingDisabled(true, false); setOverscroll(false, false) }
        column.add(scroll).grow().prefHeight(0f).row()
        selected?.let { column.add(detail(it)).growX().row() }
        return column
    }

    private fun node(policy: Policy): Group {
        val adopted = policies.isAdopted(policy.name)
        val pickable = policies.isAdoptable(policy)
        val chosen = selected?.name == policy.name
        val group = Group().apply { setSize(96f, 96f); touchable = Touchable.enabled }
        if (chosen) ImageGetter.getCircle(SELECT, 86f).apply { setPosition(5f, 23f); group.addActor(this) }
        val discColor = when { adopted -> DONE; pickable -> Color.WHITE; else -> LOCKED_DISC }
        ImageGetter.getCircle(discColor, 66f).apply { setPosition(15f, 32f); group.addActor(this) }
        val path = if (policy is PolicyBranch) "PolicyBranchIcons/${policy.name}" else "PolicyIcons/${policy.name}"
        ImageGetter.getImage(path).apply {
            color = when { adopted -> Color.WHITE; pickable -> SHEET; else -> INK3 }
            setBounds(31f, 48f, 34f, 34f)
            group.addActor(this)
        }
        val label = policy.name.toLabel(if (adopted) INK2 else if (pickable) Color.WHITE else INK3, if (policy is PolicyBranch) 15 else 13, Align.center, hideIcons = true)
        label.wrap = true
        label.setBounds(0f, 0f, 96f, 32f)
        group.addActor(label)
        for (child in group.children) child.touchable = Touchable.disabled
        group.onClick { selected = policy; refresh() }
        return group
    }

    private fun detail(policy: Policy): Table {
        val adopted = policies.isAdopted(policy.name)
        val canAdopt = !adopted && screen.canChangeState && civ.isCurrentPlayer() && policies.canAdoptPolicy() && policies.isAdoptable(policy)
        val tray = Table().apply { background = rounded(PANEL, BaseScreen.skinStrings.roundedTopEdgeRectangleSmallShape); pad(12f, 16f, 12f, 16f) }
        val head = Table()
        head.add(ImageGetter.getImage(if (policy is PolicyBranch) "PolicyBranchIcons/${policy.name}" else "PolicyIcons/${policy.name}")).size(42f).padRight(10f)
        val titles = Table().left()
        titles.add(policy.name.toLabel(fontSize = 18, hideIcons = true)).left().row()
        titles.add((if (policy is PolicyBranch) "${policy.era} era branch" else policy.branch.name).toLabel(INK2, 13)).left()
        head.add(titles).growX().left()
        val close = button("×", Color.WHITE, CHIP, 44f, 44f)
        close.onClick { selected = null; refresh() }
        head.add(close).size(44f)
        tray.add(head).growX().row()
        val effects = policy.uniqueObjects.filterNot { it.isHiddenToUsers() }.joinToString("\n") { "★  ${it.getDisplayText().tr()}" }
        if (effects.isNotEmpty()) {
            val label = effects.toLabel(Color.WHITE, 14).apply { wrap = true }
            tray.add(label).width(widthOnScreen - 32f).left().padTop(8f).row()
        }
        val requirement = policy.requires.orEmpty().filterNot { policies.isAdopted(it) }
        if (requirement.isNotEmpty()) tray.add(("Requires " + requirement.joinToString(", ")).toLabel(INK2, 13)).left().padTop(5f).row()
        val actionText = when { adopted -> "Adopted"; canAdopt -> "Adopt ${policy.name}"; else -> "Unavailable" }
        val action = button(actionText, if (canAdopt) YELLOW_INK else INK2, if (canAdopt) YELLOW else CHIP, widthOnScreen - 32f, 56f)
        if (canAdopt) action.onClick { screen.adoptPortrait(policy) }
        tray.add(action).growX().height(56f).padTop(10f)
        return tray
    }

    private fun button(text: String, ink: Color, color: Color, width: Float, height: Float) = Table().apply {
        background = rounded(color)
        touchable = Touchable.enabled
        add(text.toLabel(ink, if (height >= 56f) 17 else 20, Align.center)).grow()
        setSize(width, height)
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
        val CULTURE = Color.valueOf("d38bff")
        val DONE = Color.valueOf("2f7fe0")
        val OPEN = Color.valueOf("9cc0e2")
        val LOCKED_LINE = Color.valueOf("56779a")
        val LOCKED_DISC = Color.valueOf("1f3850")
        val SELECT = Color.valueOf("ffc93c88")

        fun solid(color: Color): Drawable = ImageGetter.getWhiteDotDrawable().tint(color)
        fun rounded(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleMidShape): Drawable =
            BaseScreen.skinStrings.getUiBackground("", shape, color)
    }
}
