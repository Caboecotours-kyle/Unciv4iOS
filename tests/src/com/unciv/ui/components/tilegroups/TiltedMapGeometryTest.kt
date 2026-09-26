package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.dev.FontDesktop
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.toHexCoord
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.screens.cityscreen.CityMapHolder
import com.unciv.utils.SafeInsets
import com.unciv.view.TileMapView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class TiltedMapGeometryTest {
    private lateinit var game: TestGame

    @Before fun setUp() {
        game = TestGame()
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        TileSetCache.loadTileSetConfigs()
        game.makeHexagonalMap(2)
    }

    @Test fun projectedGroundSelectsItsTileWithoutSelectingTallArt() {
        val view = TileMapView(game.tileMap, null)
        val strings = TileSetStrings("Polytopia", "Polytopia", isPortrait = true)
        val groups = game.tileMap.values.map { TileGroup(view.getTile(it), strings) }
        val map = TileGroupMap(ZoomableScrollPane(), groups)
        for (group in groups) {
            val center = Vector2(group.x + group.groundCenterX, group.y + group.groundCenterY)
            assertSame(group, map.hit(center.x, center.y, true))
            val hex = HexMath.roundHexCoords(HexMath.world2HexCoords(map.getPositionalVector(center)))
            assertEquals(group.tileView.position(), hex.toHexCoord())
        }
        val group = groups.first()
        group.addActor(Actor().apply { setBounds(0f, 0f, 54f, 200f) })
        assertNull(group.hit(group.groundCenterX, group.groundCenterY + 30f, true))
    }

    @Test fun portraitLatticeMatchesPointUpMock() {
        val view = TileMapView(game.tileMap, null)
        val strings = TileSetStrings("Polytopia", "Polytopia", isPortrait = true)
        val groups = game.tileMap.values.map { TileGroup(view.getTile(it), strings) }
        val map = TileGroupMap(ZoomableScrollPane(), groups)
        val origin = groups.first { it.tileView.position() == HexCoord.Zero }
        val right = groups.first { it.tileView.position() == HexCoord(-1, 0) }
        val row = groups.first { it.tileView.position() == HexCoord(0, 1) }
        assertEquals(69.282f, right.x - origin.x, 0.001f)
        assertEquals(0f, right.y - origin.y, 0.001f)
        assertEquals(34.641f, row.x - origin.x, 0.001f)
        assertEquals(36f, row.y - origin.y, 0.001f)
        assertSame(origin, map.hit(origin.x + origin.groundCenterX,
            origin.y + origin.groundCenterY + 23f, true))
        assertNull(origin.hit(origin.groundCenterX + 36f, origin.groundCenterY, true))
    }

    @Test fun periodicCopyKeepsEveryLayerAndInverseHitTogether() {
        val view = TileMapView(game.tileMap, null)
        val strings = TileSetStrings("Polytopia", "Polytopia", isPortrait = true)
        val groups = game.tileMap.values.map { TileGroup(view.getTile(it), strings) }
        val holder = ZoomableScrollPane()
        val map = TileGroupMap(holder, groups, worldWrap = true)
        holder.actor = map
        holder.setSize(120f, 240f)
        holder.layout()
        val wrap = map.wrapVector
        assertTrue(wrap.x > 0f && wrap.y > 0f)
        val original = groups.associateWith { Vector2(it.x, it.y) }
        val camera = map.fromFlat(Vector2(map.flatWidth - 1f, map.flatHeight / 2f))
        holder.setScrollPosition(camera.x, holder.maxY - camera.y)
        map.updateWrappedPositions()
        var moved = 0
        for (group in groups) {
            val delta = Vector2(group.x, group.y).sub(original.getValue(group))
            if (delta.len2() < 0.001f) continue
            moved++
            assertEquals(wrap.x, delta.x, 0.001f)
            assertEquals(wrap.y, delta.y, 0.001f)
            val terrain = group.layerTerrain.tileBaseImages.first()
            assertEquals(group.x + group.hexagonImagePosition.first, terrain.x, 0.001f)
            assertEquals(group.y + group.hexagonImagePosition.second, terrain.y, 0.001f)
            val center = Vector2(group.x + group.groundCenterX, group.y + group.groundCenterY)
            assertSame(group, map.hit(center.x, center.y, true))
            val flat = map.toFlat(center)
            flat.x -= map.flatWidth
            val originalCenter = map.fromFlat(flat)
            assertEquals(original.getValue(group).x + group.groundCenterX, originalCenter.x, 0.001f)
            assertEquals(original.getValue(group).y + group.groundCenterY, originalCenter.y, 0.001f)
        }
        assertTrue("The probe must actually cross the seam", moved > 0)
    }

    @Test fun miniatureKeepsItsOwnHitBounds() {
        val view = TileMapView(game.tileMap, null)
        val strings = TileSetStrings("Polytopia", "Polytopia", isPortrait = true)
        val miniature = TileGroup(view.getTile(game.getTile(HexCoord.Zero)), strings, 18f)
        miniature.isForMapEditorIcon = true
        // Neither the normal bounds nor its 27-wide hex extends this far right.
        assertNull(miniature.hit(30f, 9f, true))
    }

    @Test fun absentProjectionAndLandscapeKeepFlatPlacement() {
        val view = TileMapView(game.tileMap, null)
        val flat = TileSetStrings("Polytopia", "Polytopia", isPortrait = false)
        val other = TileSetStrings("FantasyHex", "FantasyHex", isPortrait = true)
        assertEquals(1f, flat.mapVerticalScale, 0f)
        assertEquals(1f, other.mapVerticalScale, 0f)
        val flatGroups = game.tileMap.values.map { TileGroup(view.getTile(it), flat) }
        val otherGroups = game.tileMap.values.map { TileGroup(view.getTile(it), other) }
        TileGroupMap(ZoomableScrollPane(), flatGroups)
        TileGroupMap(ZoomableScrollPane(), otherGroups)
        for ((a, b) in flatGroups.zip(otherGroups)) {
            assertEquals(a.x, b.x, 0f)
            assertEquals(a.y, b.y, 0f)
        }
    }

    @Test fun cameraCoordinatesSurviveProjectionChanges() {
        val view = TileMapView(game.tileMap, null)
        val flatGroups = game.tileMap.values.map {
            TileGroup(view.getTile(it), TileSetStrings("Polytopia", "Polytopia", isPortrait = false))
        }
        val tiltedStrings = TileSetStrings("Polytopia", "Polytopia", isPortrait = true)
        val tiltedGroups = game.tileMap.values.map { TileGroup(view.getTile(it), tiltedStrings) }
        val flat = TileGroupMap(ZoomableScrollPane(), flatGroups)
        val tilted = TileGroupMap(ZoomableScrollPane(), tiltedGroups)
        assertEquals(flat.height, tilted.flatHeight, 0.001f)
        val viewport = Rectangle(10f, 20f, 100f, 200f)
        val originalViewport = Rectangle(viewport)
        val flatViewport = tilted.flatViewport(viewport)
        assertEquals(originalViewport, viewport)
        assertTrue(flatViewport.width > viewport.width)
        assertTrue(flatViewport.height > viewport.height)
        for ((a, b) in flatGroups.zip(tiltedGroups)) {
            val mapped = tilted.toFlat(Vector2(b.x + b.groundCenterX, b.y + b.groundCenterY))
            assertEquals(a.x + a.groundCenterX, mapped.x, 0.001f)
            assertEquals(a.y + a.groundCenterY, mapped.y, 0.001f)
        }
        for (point in listOf(Vector2(-35f, 17.5f), Vector2.Zero, Vector2(flat.width + 20f, flat.height + 35f))) {
            val mapped = tilted.toFlat(tilted.fromFlat(Vector2(point)))
            assertEquals(point.x, mapped.x, 0.001f)
            assertEquals(point.y, mapped.y, 0.001f)
        }
    }

    @Test
    @RedirectOutput(RedirectPolicy.Show)
    fun defaultRowsMeet44PointsWithoutBlockingZoomOut() {
        val view = TileMapView(game.tileMap, null)
        val strings = TileSetStrings("Polytopia", "Polytopia", isPortrait = true)
        val groups = game.tileMap.values.map { TileGroup(view.getTile(it), strings) }
        val holder = CityMapHolder()
        val map = TileGroupMap(holder, groups)
        holder.actor = map
        holder.setSize(432f, 800f)
        holder.layout()
        val a = groups.first { it.tileView.position() == HexCoord.Zero }
        val b = groups.first { it.tileView.position() == HexCoord(0, 1) }
        for (fullLayout in listOf(false, true)) {
            for (edgeToEdge in listOf(false, true)) {
                val viewport = SafeAreaViewport(600f, fullLayout)
                viewport.updateDisplay(393, 852, SafeInsets(0, 59, 0, 34), edgeToEdge)
                holder.setDefaultZoom(viewport)
                val from = viewport.project(Vector2(0f, 0f))
                val to = viewport.project(Vector2(0f, (b.y - a.y) * holder.scaleY))
                assertEquals(44.64f, to.y - from.y, 0.01f)
                assertTrue("Row spacing ${to.y - from.y}, full=$fullLayout, edge=$edgeToEdge", to.y - from.y >= 43.999f)
                println("Tilt 393x852, full=$fullLayout, edge=$edgeToEdge: row=${to.y - from.y}pt, zoom=${holder.scaleY}")
                holder.zoom(0.5f)
                assertEquals(0.5f, holder.scaleX, 0f)
                holder.zoom(0.8f)
                assertEquals(0.8f, holder.scaleX, 0f)
            }
        }
    }
}
