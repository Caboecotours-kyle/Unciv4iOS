package com.unciv.ui.screens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.scrollTo
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.LanguageTable
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageKeyShortcuts
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageTables
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Display
import kotlin.math.max
import kotlin.math.roundToInt

/** A [PickerScreen] to select a language, used once on the initial run after a fresh install.
 *  After that, [OptionsPopup] provides the functionality.
 *  Reusable code is in [LanguageTable] and [addLanguageTables].
 *  Portrait matches the main menu: the army lineup and wordmark, with the languages on a sheet below.
 */
class LanguagePickerScreen : PickerScreen() {
    private var chosenLanguage: String

    private val languageTables: ArrayList<LanguageTable>

    private val portrait = isPortrait()
    private val portraitTextures = ArrayList<Texture>()
    private val portraitArt = Image()
    private val portraitScrim = Image()
    private var wordmarkShadow: Label? = null
    private var wordmark: Label? = null
    private val portraitSheet = Table()

    fun update() {
        languageTables.forEach { it.update(chosenLanguage) }
        if (portrait) languageTables.forEach {
            it.background = rounded(if (it.language == chosenLanguage) selectedRow else row)
        }
    }

    init {
        chosenLanguage = LocaleCode.getSystemLanguage()

        closeButton.isVisible = false

        languageTables = topTable.addLanguageTables(if (portrait) portraitWidth - 60f else stage.width - 60f)

        for (languageTable in languageTables) {
            languageTable.onClick {
                onChoice(languageTable.language)
            }
        }

        topTable.addLanguageKeyShortcuts(languageTables, { chosenLanguage }) { language ->
            onChoice(language)
            val selectedTable = languageTables.firstOrNull { it.language == language }
                ?: return@addLanguageKeyShortcuts
            scrollPane.scrollTo(selectedTable, true)
        }

        rightSideButton.setText("Pick language".tr())
        rightSideButton.onActivation {
            pickLanguage()
        }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        if (portrait) initPortrait()
        if (chosenLanguage.isNotEmpty()) onChoice()
    }

    private fun initPortrait() {
        pickerPane.remove()
        updatePortraitViewport()
        val unit = (stage.viewport as SafeAreaViewport).drawingBounds.width / portraitWidth

        val army = Texture(Gdx.files.internal("ExtraImages/MainMenuArmy.png"))
        army.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        portraitTextures += army
        portraitArt.drawable = TextureRegionDrawable(army)
        portraitArt.touchable = Touchable.disabled
        stage.addActor(portraitArt)

        // Same top scrim as the main menu, so the wordmark reads against the sky
        val pixels = Pixmap(1, 130, Pixmap.Format.RGBA8888)
        pixels.blending = Pixmap.Blending.None
        for (y in 0 until pixels.height)
            pixels.drawPixel(0, y, 0x0a162200 or (158f * (1f - y.toFloat() / pixels.height)).roundToInt())
        val scrim = Texture(pixels)
        pixels.dispose()
        scrim.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        portraitTextures += scrim
        portraitScrim.drawable = TextureRegionDrawable(scrim)
        portraitScrim.touchable = Touchable.disabled
        stage.addActor(portraitScrim)

        val wordmarkSize = (62f * unit).roundToInt()
        wordmarkShadow = "Unciv".toLabel(Color.valueOf("1c3249"), wordmarkSize, Align.center)
            .apply { touchable = Touchable.disabled; this@LanguagePickerScreen.stage.addActor(this) }
        wordmark = "Unciv".toLabel(Color.WHITE, wordmarkSize, Align.center)
            .apply { touchable = Touchable.disabled; this@LanguagePickerScreen.stage.addActor(this) }

        rightSideButton.style = TextButton.TextButtonStyle(rightSideButton.style).apply {
            up = rounded(yellow)
            down = rounded(Color.valueOf("eab527"))
            over = null
            disabled = rounded(Color.valueOf("64592e"))
            fontColor = yellowInk
            disabledFontColor = Color.valueOf("dfd7ac")
        }
        rightSideButton.color = Color.WHITE

        portraitSheet.apply {
            isTransform = true
            background = rounded(sheet, skinStrings.roundedTopEdgeRectangleSmallShape)
            add(ImageGetter.getWhiteDot().apply { color = Color(1f, 1f, 1f, .3f) }).size(44f, 5f).padTop(8f).row()
            add("Language".toLabel(fontSize = 23)).left().pad(10f, 16f, 6f, 16f).row()
            add(scrollPane).grow().row()
            add(rightSideButton).growX().height(56f).pad(12f, 16f, 44f, 16f)
        }
        stage.addActor(portraitSheet)
        layoutPortrait()
        update()
    }

    private fun updatePortraitViewport() {
        // Art fills the phone like the main menu; the sheet keeps its buttons clear of the home indicator
        (stage.viewport as SafeAreaViewport).updateDisplay(
            Gdx.graphics.width, Gdx.graphics.height, Display.getSafeInsets(), edgeToEdge = true)
    }

    /** Mirrors MainMenuScreen's portrait positions: art covers the phone, wordmark 120pt below the top */
    private fun layoutPortrait() {
        val bounds = (stage.viewport as SafeAreaViewport).drawingBounds
        val unit = bounds.width / portraitWidth
        val top = bounds.y + bounds.height
        val art = portraitTextures.first()
        val scale = max(bounds.width / art.width, bounds.height / art.height)
        portraitArt.setBounds(bounds.x + (bounds.width - art.width * scale) / 2f,
            bounds.y + (bounds.height - art.height * scale) / 2f, art.width * scale, art.height * scale)
        portraitScrim.setBounds(bounds.x, top - 130f * unit, bounds.width, 130f * unit)
        val wordmarkHeight = 76f * unit
        val wordmarkY = top - 120f * unit - wordmarkHeight
        wordmarkShadow?.setBounds(bounds.x, wordmarkY - 4f * unit, bounds.width, wordmarkHeight)
        wordmark?.setBounds(bounds.x, wordmarkY, bounds.width, wordmarkHeight)
        portraitSheet.setScale(unit)
        portraitSheet.setBounds(bounds.x, bounds.y, portraitWidth, (wordmarkY - 16f * unit - bounds.y) / unit)
    }

    override fun render(delta: Float) {
        if (portrait) {
            updatePortraitViewport()
            layoutPortrait()
        }
        super.render(delta)
    }

    override fun dispose() {
        portraitTextures.forEach(Texture::dispose)
        super.dispose()
    }

    private fun onChoice(choice: String) {
        chosenLanguage = choice
        onChoice()
    }
    private fun onChoice() {
        rightSideButton.enable()
        update()
    }

    private fun pickLanguage() {
        game.settings.language = chosenLanguage
        game.settings.updateLocaleFromLanguage()
        game.settings.isFreshlyCreated = false     // mark so the picker isn't called next launch
        game.settings.save()

        game.translations.tryReadTranslationForCurrentLanguage()
        game.replaceCurrentScreen{ MainMenuScreen() }
    }

    private companion object {
        const val portraitWidth = 393f
        val sheet: Color = Color.valueOf("101f2fe6")
        val row = Color(1f, 1f, 1f, .09f)
        val selectedRow: Color = Color.valueOf("ffc93c47")
        val yellow: Color = Color.valueOf("ffc93c")
        val yellowInk: Color = Color.valueOf("3a2a00")

        fun rounded(color: Color, shape: String = BaseScreen.skinStrings.roundedEdgeRectangleMidShape) =
            BaseScreen.skinStrings.getUiBackground("", shape, color)
    }
}
