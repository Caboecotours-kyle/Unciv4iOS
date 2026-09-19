package com.unciv.utils

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.GdxNativesLoader
import com.unciv.json.json
import com.unciv.models.metadata.GameSettings
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class SafeAreaViewportTest {
    private val oldApp = Gdx.app
    private val oldGraphics = Gdx.graphics
    private val oldGl = Gdx.gl
    private val oldGl20 = Gdx.gl20

    @Before fun setup() {
        GdxNativesLoader.load()
        Gdx.app = mock(com.badlogic.gdx.Application::class.java)
        Gdx.graphics = mock(Graphics::class.java)
        Gdx.gl = mock(GL20::class.java)
        Gdx.gl20 = Gdx.gl
    }

    @After fun cleanup() {
        Gdx.app = oldApp
        Gdx.graphics = oldGraphics
        Gdx.gl = oldGl
        Gdx.gl20 = oldGl20
    }

    private fun screen(width: Int, height: Int) {
        `when`(Gdx.graphics.width).thenReturn(width)
        `when`(Gdx.graphics.height).thenReturn(height)
        `when`(Gdx.graphics.backBufferWidth).thenReturn(width * 3)
        `when`(Gdx.graphics.backBufferHeight).thenReturn(height * 3)
    }

    @Test fun fullscreenModeUsesTheEntireScreenWhileSafeModeUsesInsets() {
        for ((width, height, insets) in listOf(
            Triple(852, 393, SafeInsets(59, 0, 0, 21)),
            Triple(852, 393, SafeInsets(0, 0, 59, 21)),
            Triple(393, 852, SafeInsets(0, 59, 0, 34)),
            Triple(800, 600, SafeInsets())
        )) {
            screen(width, height)
            val viewport = SafeAreaViewport(500f)
            viewport.updateDisplay(width, height, insets, false)
            val safe = insets.applyTo(width, height)
            assertEquals(safe.x, viewport.screenX)
            assertEquals(safe.y, viewport.screenY)
            assertEquals(safe.width, viewport.screenWidth)
            assertEquals(safe.height, viewport.screenHeight)
            viewport.updateDisplay(width, height, insets, true)
            assertEquals(0, viewport.screenX)
            assertEquals(0, viewport.screenY)
            assertEquals(width, viewport.screenWidth)
            assertEquals(height, viewport.screenHeight)
            assertEquals(Rectangle(0f, 0f, viewport.worldWidth, viewport.worldHeight), viewport.drawingBounds)
            val safeBounds = viewport.safeAreaBoundsInWorld
            assertEquals(safe.x * viewport.worldWidth / width, safeBounds.x, 0.001f)
            assertEquals(safe.y * viewport.worldHeight / height, safeBounds.y, 0.001f)
            assertEquals(safe.width * viewport.worldWidth / width, safeBounds.width, 0.001f)
            assertEquals(safe.height * viewport.worldHeight / height, safeBounds.height, 0.001f)
            assertTrue(Vector2(width.toFloat(), height.toFloat()).epsilonEquals(
                viewport.project(Vector2(viewport.worldWidth, viewport.worldHeight)), 0.001f))
        }
    }

    @Test fun insetOnlyChangesMoveTheCameraAndEdgeInputHitsTheMap() {
        screen(852, 393)
        val viewport = SafeAreaViewport(500f)
        val stage = Stage(viewport, mock(Batch::class.java))
        viewport.updateDisplay(852, 393, SafeInsets(59, 0, 0, 21), true)
        val oldBounds = Rectangle(viewport.drawingBounds)
        val map = Actor().apply { setBounds(oldBounds.x, oldBounds.y, oldBounds.width, oldBounds.height) }
        stage.addActor(map)
        val button = Actor().apply { setBounds(0f, 0f, 50f, 50f) }
        stage.addActor(button)
        assertSame(map, stage.hit(oldBounds.x + 1, oldBounds.y + oldBounds.height / 2f, true))
        assertSame(button, stage.hit(20f, 20f, true))
        viewport.updateDisplay(852, 393, SafeInsets(0, 0, 59, 21), true)
        assertEquals(oldBounds, viewport.drawingBounds)
        viewport.updateDisplay(852, 393, SafeInsets(0, 0, 59, 21), false)
        assertEquals(Rectangle(0f, 0f, viewport.worldWidth, viewport.worldHeight), viewport.drawingBounds)
        stage.dispose()
    }

    @Test fun resizingPreservesMapWorldCenterAndZoom() {
        val pane = ZoomableScrollPane()
        pane.actor = Actor().apply { setSize(3000f, 2000f) }
        pane.setSize(800f, 400f)
        pane.validate()
        pane.scrollX = 1200f
        pane.scrollY = 900f
        pane.zoom(1.25f)
        val before = Rectangle().also(pane::getViewport).getCenter(Vector2())
        repeat(10) {
            for (bounds in listOf(Rectangle(-60f, -21f, 920f, 421f), Rectangle(0f, -34f, 400f, 860f))) {
                pane.resizeViewport(bounds)
                val center = Rectangle().also(pane::getViewport).getCenter(Vector2())
                assertTrue(before.epsilonEquals(center, 0.001f))
                assertEquals(1.25f, pane.scaleX, 0f)
            }
        }
    }

    @Test fun settingsKeepAndroidPreferenceIndependentAndRoundTripIosChoices() {
        val old = json().fromJson(GameSettings::class.java, "{androidCutout:true}")
        assertTrue(old.androidCutout)
        assertFalse(old.iosUseDisplayCutout)
        assertEquals(ScreenOrientation.Landscape, old.displayOrientation)
        for (orientation in ScreenOrientation.entries) {
            old.displayOrientation = orientation
            old.iosUseDisplayCutout = true
            val restored = json().fromJson(GameSettings::class.java, json().toJson(old))
            assertEquals(orientation, restored.displayOrientation)
            assertTrue(restored.iosUseDisplayCutout)
            assertTrue(restored.androidCutout)
        }
    }
}
