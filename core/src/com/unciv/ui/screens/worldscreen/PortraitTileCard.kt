package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.ui.components.extensions.center
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.map.tile.Tile
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onClickSuppressive
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.components.tilegroups.TileSetStrings
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.PortraitStatIcons
import com.unciv.ui.objectdescriptions.TileDescription
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.FormattedLine.IconDisplay
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer
import com.unciv.view.CityView
import com.unciv.view.CivView
import com.unciv.view.ForeignMapUnitView
import com.unciv.view.TileView
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** The portrait tile card shared by the map's tile sheet and the city's tile details, laid out on the 393pt canvas
 *  times [p]. Every fact is a [TileDescription.toMarkup] line, so visibility, spy and exploration rules stay identical. */
internal class PortraitTileCard(
    private val screen: BaseScreen,
    private val tileView: TileView,
    private val civView: CivView?,
    private val icons: PortraitStatIcons,
    private val cityView: CityView? = null,
    hideUnits: Boolean = false,
    spyCity: CityView? = null,
    private val p: Float = 1f
) {
    companion object {
        val NAVY2: Color = Color.valueOf("1c3249")
        val INK2: Color = Color.valueOf("b7cde0")
        val LINE = Color(1f, 1f, 1f, .12f)
        private val LOCK = Color.valueOf("ffe28b")
        private val LOCK_FILL = Color(1f, 201f / 255f, 60f / 255f, .16f)
        private val PLAIN = Color(1f, 1f, 1f, .1f)
        private val CHEVRON = Color.valueOf("6f89a0")
        private val HP_TRACK = Color.valueOf("0c1824")
        private val HP = Color.valueOf("6fd05a")

        fun bg(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleShape): Drawable =
            BaseScreen.skinStrings.getUiBackground("", shape, color)

        /** A unit in its map flag colors: outer color disc, inner color icon */
        fun unitDisc(unit: ForeignMapUnitView, iconSize: Float) = Table().apply {
            background = ImageGetter.getCircleDrawable().tint(unit.civ().getOuterColor())
            add(ImageGetter.getUnitIcon(unit.getBaseUnit(), unit.civ().getInnerColor())).size(iconSize)
        }
    }

    private fun pt(value: Float) = value * p
    private fun font(size: Int) = (size * p).roundToInt()

    private val lines = TileDescription.toMarkup(tileView, civView, hideUnits, spyCity)
    private var baseLine: FormattedLine? = null
    private var resourceLine: FormattedLine? = null
    private var techLine: FormattedLine? = null
    private var wonderLine: FormattedLine? = null
    private val improvementLines = ArrayList<FormattedLine>()
    private val featureLines = ArrayList<FormattedLine>()
    private val unitLines = ArrayList<FormattedLine>()
    private val otherLines = ArrayList<FormattedLine>()
    private var impassable = false
    private var freshWater = false
    /** [TileDescription] emits the defence line only for a nonzero bonus */
    private var defence = false

    init {
        for (line in lines) {
            val target = line.link.substringAfter('/')
            when {
                line.text == Constants.freshWater -> freshWater = true
                line.text == Constants.impassable -> impassable = true
                line.text.endsWith("to unit defence") -> defence = true
                line.text.startsWith("Requires [") -> techLine = line
                line.link.startsWith("Terrain/") && target == tileView.baseTerrain && baseLine == null -> baseLine = line
                line.link.startsWith("Terrain/") && target == tileView.naturalWonder -> wonderLine = line
                line.link.startsWith("Terrain/") -> featureLines += line
                line.link.startsWith("Resource/") -> resourceLine = line
                line.link.startsWith("Improvement/") -> improvementLines += line
                line.link.startsWith("Unit/") -> unitLines += line
                else -> otherLines += line
            }
        }
    }

    /** "Grassland, Forest" */
    val title: String = (listOfNotNull(baseLine) + featureLines).joinToString(", ") { it.text.tr(hideIcons = true) }

    private val defencePercent: String
        get() {
            val percent = (tileView.getDefensiveBonus() * 100).toInt()
            return (if (percent > 0) "+" else "") + "$percent%"
        }

    private fun openLink(link: String) = screen.openCivilopedia(link)

    /** Hex thumbnail drawn by the map's own tile renderer, with only the terrain and resource the markup shows */
    fun thumbnail(width: Float, height: Float): Actor = tileImage(width, height, featureLines.map { it.link.substringAfter('/') },
        wonderLine != null, resourceLine != null)

    private fun tileImage(width: Float, height: Float, features: List<String>, wonder: Boolean = false, resource: Boolean = false): Actor {
        val ruleset = tileView.getRuleset()
        val tile = Tile()
        tile.ruleset = ruleset
        tile.baseTerrain = tileView.baseTerrain
        if (wonder) tile.naturalWonder = tileView.naturalWonder
        tile.setTerrainTransients()
        tile.setTerrainFeatures(features)
        if (resource) tile.tileResource = tileView.getViewableResource(civView)
        // Sized like the Civilopedia's terrain icons: TileGroup draws past its own bounds, so its layers are recentred
        val group = TileGroup(TileView.forSingleTile(tile), TileSetStrings(ruleset, UncivGame.Current.settings), width * 36f / 54f)
        group.isForMapEditorIcon = true
        group.update()
        group.touchable = Touchable.disabled
        group.width *= 1.5f
        group.height *= 1.5f
        for (layer in group.children) layer.center(group)
        return object : Group() {
            init {
                addActor(group)
                setSize(width, height)
                touchable = Touchable.disabled
            }
            override fun sizeChanged() {
                group.setPosition((width - group.width) / 2f, (height - group.height) / 2f)
            }
        }
    }

    /** Thumbnail, title and "Owner · Fresh water"; [trailing] sits at the end (the sheet's close button) */
    fun header(trailing: Actor? = null): Table {
        val header = Table()
        val text = Table().left()
        text.add(title.toLabel(Color.WHITE, font(22), hideIcons = true).apply { wrap = true }).growX().left().row()
        val owner = tileView.getOwner()?.civName?.tr(hideIcons = true) ?: "Unowned".tr()
        val subtitle = if (freshWater) "$owner · ${Constants.freshWater.tr()}" else owner
        text.add(subtitle.toLabel(INK2, font(14), hideIcons = true).apply { wrap = true }).growX().left().padTop(pt(3f))
        val baseLink = baseLine?.link
        val block = Table()
        block.add(thumbnail(pt(64f), pt(56f))).size(pt(64f), pt(56f)).padRight(pt(13f))
        block.add(text).growX().minWidth(0f)
        if (baseLink != null) {
            block.touchable = Touchable.enabled
            block.onClick { openLink(baseLink) }
        }
        header.add(block).growX().minHeight(pt(56f))
        if (trailing != null) header.add(trailing).padLeft(pt(8f))
        return header
    }

    private fun amount(value: Float) = if (value == value.toInt().toFloat()) value.toInt().tr() else "%.1f".format(value)

    /** 44pt yield chips; any of them opens the same breakdown popup the landscape tile table offers */
    fun yields(maxWidth: Float): Table? {
        val stats = tileView.getTileStats(civView, cityView)
        if (stats.isEmpty()) return null
        val table = Table().left()
        var line = Table().left()
        var used = 0f
        for ((stat, value) in stats) {
            val chip = Table()
            chip.background = bg(NAVY2)
            chip.add(icons.image(stat.name)).size(pt(22f)).padRight(pt(8f))
            chip.add(amount(value).toLabel(Color.WHITE, font(20)))
            chip.pad(0f, pt(14f), 0f, pt(14f))
            // The chip draws 44pt tall; its touch target keeps the 48pt minimum
            val target = Table()
            target.touchable = Touchable.enabled
            target.name = "portrait-yield-${stat.name}"
            target.add(chip).minWidth(pt(70f)).height(pt(44f)).pad(pt(2f), 0f, pt(2f), 0f)
            target.onClick { openBreakdown() }
            val width = max(chip.prefWidth, pt(70f))
            if (used > 0f && used + pt(8f) + width > maxWidth) {
                table.add(line).left().padBottom(pt(4f)).row()
                line = Table().left()
                used = 0f
            }
            line.add(target).padRight(pt(8f))
            used += width + pt(8f)
        }
        table.add(line).left()
        return table
    }

    private fun openBreakdown() {
        Popup(screen).apply {
            for ((name, stats) in tileView.getTileStatsBreakdown(civView, cityView))
                add("${name.tr()}: {${stats.clone()}}".toLabel()).row()
            addCloseButton()
        }.open(force = true)
    }

    fun pill(text: String, fill: Color, ink: Color, icon: String? = null): Table = Table().apply {
        background = bg(fill, BaseScreen.skinStrings.roundedEdgeRectangleMidShape)
        if (icon != null) add(ImageGetter.getImage(icon).apply { color = ink }).size(pt(14f)).padRight(pt(5f))
        add(text.toLabel(ink, font(13), hideIcons = true)).height(pt(30f))
        pad(0f, pt(10f), 0f, pt(10f))
    }

    /** "+25%" with a shield, or null when the tile gives no defence bonus */
    fun defencePill(suffix: String = ""): Table? =
        if (!defence) null else pill(defencePercent + suffix, PLAIN, Color.WHITE, "OtherIcons/Shield")

    private fun iconBox(actor: Actor?, round: Boolean = false): Actor = Table().apply {
        background = if (round) null else bg(NAVY2)
        if (actor != null) add(actor).size(pt(if (round) 40f else 34f))
    }

    /** Plain bars: the skin's rounded shapes cannot draw as thin as 8pt */
    fun healthBar(health: Int, width: Float = pt(84f), height: Float = pt(8f)): Table = Table().apply {
        background = ImageGetter.getWhiteDotDrawable().tint(HP_TRACK)
        // Whole units, so the rounded layout keeps the bar at exactly [width]
        val total = floor(width)
        val filled = floor(total * health.coerceIn(0, 100) / 100f)
        if (filled > 0f) add(Image(ImageGetter.getWhiteDotDrawable().tint(HP))).size(filled, height)
        if (filled < total) add().size(total - filled, height)
    }

    /** A 56pt row: icon, wrapping title and meta, trailing pills and a chevron when it opens [link] */
    fun row(icon: Actor?, title: String, meta: String? = null, end: List<Actor> = emptyList(), link: String? = null,
            onTap: (() -> Unit)? = if (link == null) null else ({ openLink(link) })): Table {
        val row = Table()
        if (icon != null) row.add(icon).size(pt(40f)).padRight(pt(12f))
        val text = Table().left()
        text.add(title.toLabel(Color.WHITE, font(17), hideIcons = true).apply { wrap = true }).growX().left().row()
        if (meta != null) text.add(meta.toLabel(INK2, font(13), hideIcons = true).apply { wrap = true }).growX().left().padTop(pt(2f))
        row.add(text).growX().minWidth(0f).padTop(pt(6f)).padBottom(pt(6f))
        for (actor in end) row.add(actor).padLeft(pt(8f))
        if (onTap != null) {
            row.add(ImageGetter.getImage("OtherIcons/ArrowRight").apply { color = CHEVRON }).size(pt(14f)).padLeft(pt(8f))
            row.touchable = Touchable.enabled
            row.onClick { onTap() }
        }
        return row
    }

    /** The lock pill names the tech [TileDescription] says the resource needs, and opens that tech */
    private fun lockPill(line: FormattedLine): Table {
        val tech = line.text.removePrefix("Requires [").removeSuffix("]")
        val pill = pill(tech.tr(hideIcons = true), LOCK_FILL, LOCK, "OtherIcons/LockSmall")
        return Table().apply {
            name = "portrait-lock-pill"
            touchable = Touchable.enabled
            add(pill).padTop(pt(9f)).padBottom(pt(9f))
            onClickSuppressive { openLink(line.link) }
        }
    }

    private fun unitRow(line: FormattedLine, used: MutableSet<ForeignMapUnitView>): Table {
        val name = line.link.substringAfter('/')
        val unit = listOfNotNull(tileView.militaryUnit, tileView.civilianUnit)
            .firstOrNull { it.name == name && it !in used }
            ?: return row(null, line.text, link = line.link)
        used += unit
        val strength = unit.getBaseUnit().strength
        val meta = unit.civName.tr(hideIcons = true) + if (strength > 0) " · ${"Strength".tr()} $strength" else ""
        return row(iconBox(unitDisc(unit, pt(26f)), round = true), unit.displayName().tr(hideIcons = true), meta,
            listOf(healthBar(unit.unitHealth)), line.link)
    }

    /** Rows in the order the brief sets: resource, wonder, improvements, terrain, impassable, units, then anything else */
    fun rows(): Table {
        val table = Table()
        fun add(row: Actor) {
            if (table.cells.size > 0) table.add(Image(ImageGetter.getWhiteDotDrawable().tint(LINE))).height(max(1f, pt(1f))).growX().row()
            table.add(row).growX().minHeight(pt(56f)).row()
        }
        resourceLine?.let { line ->
            val resource = tileView.getViewableResource(civView)
            add(row(iconBox(ImageGetter.getResourcePortrait(line.link.substringAfter('/'), pt(34f))), line.text, resource?.let { "${it.resourceType.name} resource" },
                listOfNotNull(techLine?.let { lockPill(it) }), line.link))
        }
        wonderLine?.let { line ->
            add(row(iconBox(tileImage(pt(34f), pt(34f), emptyList(), wonder = true)), line.text, link = line.link))
        }
        for (line in improvementLines) {
            val name = line.link.substringAfter('/')
            add(row(iconBox(ImageGetter.getImprovementPortrait(name, pt(34f))), line.text, link = line.link))
        }
        // Without a feature the base terrain carries the row, so a bare tile still opens its entry and shows its defence
        val features = featureLines.ifEmpty { listOfNotNull(baseLine) }
        for ((index, line) in features.withIndex()) {
            val name = line.link.substringAfter('/')
            val icon = tileImage(pt(34f), pt(34f), if (name == tileView.baseTerrain) emptyList() else listOf(name))
            add(row(iconBox(icon), line.text, end = listOfNotNull(if (index == features.lastIndex) defencePill() else null), link = line.link))
        }
        if (impassable) add(row(iconBox(ImageGetter.getImage("OtherIcons/Stop").apply { color = INK2 }), Constants.impassable))
        val used = mutableSetOf<ForeignMapUnitView>()
        for (line in unitLines) add(unitRow(line, used))
        if (otherLines.isNotEmpty()) {
            val rendered = MarkupRenderer.render(otherLines, labelWidth = pt(320f), padding = pt(4f), iconDisplay = IconDisplay.None) { openLink(it) }
            add(rendered)
        }
        return table
    }
}
