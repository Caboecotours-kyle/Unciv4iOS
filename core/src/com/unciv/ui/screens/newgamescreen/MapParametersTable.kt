package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.map.*
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.metadata.GameParameters
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.*
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.PortraitDialog
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.victoryscreen.LoadMapPreview
import com.unciv.utils.Concurrency
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

/** Table for editing [mapParameters]
 *
 *  This is a separate class, because it should be in use both in the New Game screen and the Map Editor screen
 *
 *  @param forMapEditor whether the [MapType.empty] option should be present. Is used by the Map Editor, but should **never** be used with the New Game
 * */
class MapParametersTable(
    private val previousScreen: IPreviousScreen? = null,
    private val mapParameters: MapParameters,
    private val mapGeneratedMainType: String,
    private val forMapEditor: Boolean = false,
    private val sizeChangedCallback: (()->Unit)? = null
) : Table() {
    // These are accessed from outside the class to read _and_ write values,
    // namely from MapOptionsTable, NewMapScreen and NewGameScreen
    lateinit var mapTypeSelectBox: TranslatedSelectBox
    lateinit var customMapSizeRadius: UncivTextField.Integer
    lateinit var customMapWidth: UncivTextField.Integer
    lateinit var customMapHeight: UncivTextField.Integer

    private lateinit var worldSizeSelectBox: TranslatedSelectBox
    private var customWorldSizeTable = Table()
    private var hexagonalSizeTable = Table()
    private var rectangularSizeTable = Table()
    lateinit var resourceSelectBox: TranslatedSelectBox
    lateinit var mirrorSelectBox: TranslatedSelectBox
    private lateinit var noRuinsCheckbox: CheckBox
    private lateinit var noNaturalWondersCheckbox: CheckBox
    private lateinit var worldWrapCheckbox: CheckBox
    private lateinit var legendaryStartCheckbox: CheckBox
    private lateinit var strategicBalanceCheckbox: CheckBox
    private lateinit var seedTextField: UncivTextField.Numeric

    private lateinit var mapShapesOptionsValues: HashSet<String>
    private lateinit var mapTypesOptionsValues: HashSet<String>
    private lateinit var mapSizesOptionsValues: HashSet<String>
    private lateinit var mapResourcesOptionsValues: HashSet<String>

    /** New game on a portrait phone: same widgets and setters, laid out for the More options Map tab */
    private val portrait = !forMapEditor && (previousScreen as? NewGameScreen)?.isPortrait() == true
    private val controls by lazy { PortraitOptionControls.of(previousScreen as BaseScreen) }

    // Preview is shrunk to 65% of column width so Width/Height fields and warnings below stay visible without scrolling
    private val maxMapSize = (((previousScreen as? NewGameScreen)?.getColumnWidth() ?: 200f) - 10f) * 0.65f
    private val previewWidth get() = if (portrait) controls.contentWidth - controls.s(24f) else maxMapSize
    private val previewHeight get() = if (portrait) controls.s(190f) else maxMapSize
    private val mapTypeExample = Table()
    private var exampleMapJob: Job? = null
    @Volatile
    private var exampleMapGeneration = 0

    // Keep references (in the key) and settings value getters (in the value) of the 'advanced' sliders
    // in a HashMap for reuse later - in the reset to defaults button. Better here as field than as closure.
    // A HashMap indexed on a Widget is problematic, as it does not define its own hashCode and equals
    // overrides nor is a Widget a data class. Seems to work anyway.
    private val advancedSliders = HashMap<UncivSlider, ()->Float>()

    private lateinit var hexWarningLabel: Label
    private lateinit var rectWarningLabel: Label

    companion object {
        private const val LARGE_MAP_WARNING = "This is a large map - may cause latency issues"
        private const val VERY_LARGE_MAP_WARNING = "This is a very large map - may cause Out Of Memory crashes"
        private const val LARGE_MAP_TILES = 4000
        private const val VERY_LARGE_MAP_TILES = 16000
    }

    // used only in map editor (forMapEditor == true)
    var randomizeSeed = true

    init {
        update()
    }

    fun update() {
        clear()

        skin = BaseScreen.skin
        defaults().pad(if (portrait) 0f else 5f, if (portrait) 0f else 5f)
        if (portrait && mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            val prompt = "Which options should be available to the random selection?"
            controls.section(this, null, controls.label(prompt, 15f, PortraitOptionControls.MUTED).apply { wrap = true })
        } else if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            val prompt = "Which options should be available to the random selection?"
            val width = (previousScreen as? NewGameScreen)?.getColumnWidth() ?: 200f
            val label = WrappableLabel(prompt, width - 10f)  // 20 is the defaults() padding
            label.setAlignment(Align.center)
            label.wrap = true
            add(label).colspan(2).grow().row()
        }
        addMapShapeSelectBox()
        addMapTypeSelectBox()
        addWorldSizeTable()
        addResourceSelectBox()
        addWrappedCheckBoxes()
        addMirrorSelectBox()
        addAdvancedSettings()
        generateExampleMap()
    }

    fun reseed() {
        mapParameters.reseed()
        seedTextField.value = mapParameters.seed
    }

    /** Chips in portrait, else a [MultiCheckboxTable]; either edits [values] and reports each change to [onUpdate] */
    private fun addRandomOptions(title: String, persistenceID: String, options: Collection<String>,
                                 values: HashSet<String>, onUpdate: (String) -> Unit) {
        if (portrait) controls.section(this, title, controls.toggleChips(options, values, onUpdate))
        else add(MultiCheckboxTable(title, persistenceID, values, onUpdate)).colspan(2).grow().row()
    }

    private fun addMapShapeSelectBox() {
        val mapShapes = MapShape.allValues
        val rng = GameContext().stateBasedRandom("MapParametersTable.addMapShapeSelectBox", System.currentTimeMillis().toInt())

        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            mapShapesOptionsValues = mapShapes.toHashSet()
            addRandomOptions("{Enabled Map Shapes}", "NewGameMapShapes", mapShapes, mapShapesOptionsValues) {
                if (mapShapesOptionsValues.isEmpty()) {
                    mapParameters.shape = mapShapes.random(rng)
                } else {
                    mapParameters.shape = mapShapesOptionsValues.random(rng)
                }
            }
        } else {
            val mapShapeSelectBox =
                    TranslatedSelectBox(mapShapes, mapParameters.shape)
            mapShapeSelectBox.onChange {
                mapParameters.shape = mapShapeSelectBox.selected.value
                updateWorldSizeTable()
                generateExampleMap()
            }

            if (portrait) {
                controls.section(this, "Map Shape", controls.choices(mapShapeSelectBox), gap = 12f)
                return
            }
            add ("{Map Shape}:".toLabel()).left()
            add(mapShapeSelectBox).fillX().row()
        }
    }

    internal fun generateExampleMap() {
        cancelBackgroundJobs()
        val generation = ++exampleMapGeneration
        val ruleset = if (previousScreen is NewGameScreen) previousScreen.ruleset.clone() else RulesetCache.getVanillaRuleset()
        val mapParametersForExample =
            if (forMapEditor) mapParameters
            else mapParameters.clone().apply {
                seed = 0
                mirroring = MirroringType.none
            }
        exampleMapJob = Concurrency.run("Generate example map") {
            val exampleMap = MapGenerator(ruleset).generateMap(mapParametersForExample, GameParameters())
            if (!isActive) return@run
            Concurrency.runOnGLThread {
                if (generation != exampleMapGeneration) return@runOnGLThread
                mapTypeExample.clear()
                val mapPreview = LoadMapPreview(exampleMap, previewWidth, previewHeight)
                if (!forMapEditor) {
                    val label = "Example map".toLabel()
                    label.centerX(mapPreview)
                    label.y = mapPreview.height - label.height - 10f
                    mapPreview.addActor(label)
                }
                mapTypeExample.add(mapPreview)
                if (portrait) invalidateHierarchy() else pack()
            }
        }.apply {
            invokeOnCompletion {
                if (generation == exampleMapGeneration)
                    exampleMapJob = null
            }
        }
    }

    internal fun cancelBackgroundJobs() {
        exampleMapGeneration++
        exampleMapJob?.cancel()
        exampleMapJob = null
    }

    private fun addMapTypeSelectBox() {
        // MapType is not an enum so we can't simply enumerate. //todo: make it so!
        val rng = GameContext().stateBasedRandom("MapParametersTable.addMapTypeSelectBox", System.currentTimeMillis().toInt())
        var mapTypes = MapType.allValues
        if (forMapEditor && mapGeneratedMainType != MapGeneratedMainType.randomGenerated) mapTypes = mapTypes + MapType.empty

        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            mapTypesOptionsValues = mapTypes.toHashSet()
            addRandomOptions("{Enabled Map Generation Types}", "NewGameMapGenerationTypes", mapTypes, mapTypesOptionsValues) {
                if (mapTypesOptionsValues.isEmpty()) {
                    mapParameters.type = mapTypes.random(rng)
                } else {
                    mapParameters.type = mapTypesOptionsValues.random(rng)
                }
            }
        } else {
            mapTypeSelectBox = TranslatedSelectBox(mapTypes, mapParameters.type)


            mapTypeSelectBox.onChange {
                mapParameters.type = mapTypeSelectBox.selected.value

                // If the map won't be generated, these options are irrelevant and are hidden
                noRuinsCheckbox.isVisible = mapParameters.type != MapType.empty
                noNaturalWondersCheckbox.isVisible = mapParameters.type != MapType.empty

                generateExampleMap()
            }

            if (portrait) {
                controls.section(this, null,
                    controls.pickerRow(previousScreen as BaseScreen, "Map Generation Type", mapTypeSelectBox), gap = 40f)
                val preview = Table().apply {
                    background = controls.panel(PortraitOptionControls.TRACK)
                    pad(0f)
                    add(mapTypeExample).size(previewWidth, previewHeight).minWidth(0f).pad(controls.s(12f))
                }
                controls.section(this, null, preview, gap = 24f)
                return
            }
            add("{Map Generation Type}:".toLabel()).left()
            add(mapTypeSelectBox).fillX().row()
            add(mapTypeExample).colspan(2).grow().row()
        }
    }

    private fun addWorldSizeTable() {
        val rng = GameContext().stateBasedRandom("MapParametersTable.addWorldSizeTable", System.currentTimeMillis().toInt())
        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            val mapSizes = MapSize.names()
            mapSizesOptionsValues = mapSizes.toHashSet()
            addRandomOptions("{Enabled World Sizes}", "NewGameWorldSizes", mapSizes, mapSizesOptionsValues) {
                if (mapSizesOptionsValues.isEmpty()) {
                    mapParameters.mapSize = MapSize(mapSizes.random(rng))
                } else {
                    mapParameters.mapSize = MapSize(mapSizesOptionsValues.random(rng))
                }
            }
        } else {
            val mapSizes = MapSize.names() + listOf(MapSize.custom)
            worldSizeSelectBox = TranslatedSelectBox(mapSizes, mapParameters.mapSize.name)
            worldSizeSelectBox.onChange { updateWorldSizeTable(); generateExampleMap() }

            addHexagonalSizeTable()
            addRectangularSizeTable()

            if (portrait) {
                controls.section(this, "World Size", controls.choices(worldSizeSelectBox, chips = true))
                add(customWorldSizeTable).colspan(2).growX().row()
            } else {
                add("{World Size}:".toLabel()).left()
                add(worldSizeSelectBox).fillX().row()
                add(customWorldSizeTable).colspan(2).grow().row()
            }

            updateWorldSizeTable()
        }
    }

    private fun addHexagonalSizeTable() {
        val defaultRadius = mapParameters.mapSize.radius
        customMapSizeRadius = UncivTextField.Integer("Radius", defaultRadius)
        customMapSizeRadius.onChange {
            mapParameters.mapSize = MapSize(customMapSizeRadius.intValue ?: 0)
            updateHexagonalWarnings()
        }
        hexWarningLabel = "".toLabel(Color.RED).apply { wrap = true }
        hexagonalSizeTable.add(sizeLabel("{Radius}:")).grow().left()
        hexagonalSizeTable.add(customMapSizeRadius).right().portraitField().row()
        hexagonalSizeTable.add(hexWarningLabel).fillX().colspan(hexagonalSizeTable.columns).row()
        updateHexagonalWarnings()
    }

    private fun updateHexagonalWarnings() {
        val tiles = HexMath.getNumberOfTilesInHexagon(customMapSizeRadius.intValue ?: 0)
        hexWarningLabel.isVisible = tiles >= LARGE_MAP_TILES
        if (tiles >= VERY_LARGE_MAP_TILES) hexWarningLabel.setText(VERY_LARGE_MAP_WARNING)
        else hexWarningLabel.setText(LARGE_MAP_WARNING)
    }

    private fun addRectangularSizeTable() {
        val defaultWidth = mapParameters.mapSize.width
        customMapWidth = UncivTextField.Integer("Width", defaultWidth)
        val defaultHeight = mapParameters.mapSize.height
        customMapHeight = UncivTextField.Integer("Height", defaultHeight)

        customMapWidth.onChange {
            mapParameters.mapSize = MapSize(customMapWidth.intValue ?: 0, customMapHeight.intValue ?: 0)
            updateRectangularWarnings()
        }
        customMapHeight.onChange {
            mapParameters.mapSize = MapSize(customMapWidth.intValue ?: 0, customMapHeight.intValue ?: 0)
            updateRectangularWarnings()
        }

        rectWarningLabel = "".toLabel(Color.RED).apply { wrap = true }

        rectangularSizeTable.defaults().pad(5f)
        rectangularSizeTable.add(sizeLabel("{Width}:")).grow().left()
        rectangularSizeTable.add(customMapWidth).right().portraitField().row()
        rectangularSizeTable.add(sizeLabel("{Height}:")).grow().left()
        rectangularSizeTable.add(customMapHeight).right().portraitField().row()
        rectangularSizeTable.add(rectWarningLabel).fillX().colspan(hexagonalSizeTable.columns).row()
        updateRectangularWarnings()
    }

    private fun sizeLabel(text: String) = if (portrait) controls.label(text) else text.toLabel()

    /** Custom size fields get a full touch target in portrait */
    private fun <T : Actor> Cell<T>.portraitField(): Cell<T> =
        if (portrait) height(controls.target).width(controls.s(140f)).padTop(controls.s(10f)) else this

    private fun updateRectangularWarnings() {
        val tiles = (customMapWidth.intValue ?: 0) * (customMapHeight.intValue ?: 0)
        rectWarningLabel.isVisible = tiles >= LARGE_MAP_TILES
        if (tiles >= VERY_LARGE_MAP_TILES) rectWarningLabel.setText(VERY_LARGE_MAP_WARNING)
        else rectWarningLabel.setText(LARGE_MAP_WARNING)
    }

    private fun updateWorldSizeTable() {
        customWorldSizeTable.clear()

        if ((mapParameters.shape == MapShape.hexagonal || mapParameters.shape == MapShape.flatEarth) && worldSizeSelectBox.selected.value == MapSize.custom)
            customWorldSizeTable.add(hexagonalSizeTable).grow().row()
        else if (mapParameters.shape == MapShape.rectangular && worldSizeSelectBox.selected.value == MapSize.custom)
            customWorldSizeTable.add(rectangularSizeTable).grow().row()
        else
            mapParameters.mapSize = MapSize(worldSizeSelectBox.selected.value)

        sizeChangedCallback?.invoke()
    }

    private fun addResourceSelectBox() {
        val rng = GameContext().stateBasedRandom("MapParametersTable.addResourceSelectBox", System.currentTimeMillis().toInt())
        val mapResources = MapResourceSetting.activeLabels()

        if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            mapResourcesOptionsValues = mapResources.toHashSet()
            addRandomOptions("{Enabled Resource Settings}", "NewGameResourceSettings", mapResources, mapResourcesOptionsValues) {
                if (mapResourcesOptionsValues.isEmpty()) {
                    mapParameters.mapResources = mapResources.random(rng)
                } else {
                    mapParameters.mapResources = mapResourcesOptionsValues.random(rng)
                }
            }
        } else {
            resourceSelectBox = TranslatedSelectBox(mapResources, mapParameters.mapResources)

            resourceSelectBox.onChange {
                mapParameters.mapResources = resourceSelectBox.selected.value
            }

            if (forMapEditor) {
                val comment = "This is used for painting resources, not in map generator steps:"
                val expectedWidth = (UncivGame.Current.screen?.stage?.width ?: 1200f) * 0.4f
                val label = WrappableLabel(comment, expectedWidth, Color.GOLD, 14)
                label.setAlignment(Align.center)
                label.wrap = true
                add(label).colspan(2).row()
            }
            if (portrait) {
                controls.section(this, "Resource Setting", controls.choices(resourceSelectBox))
                return
            }
            add("{Resource Setting}:".toLabel()).left()
            add(resourceSelectBox).fillX().row()
        }
    }

    private fun addMirrorSelectBox() {
        if (! forMapEditor)
            return
        
        // only support these, as the rest seem buggy
        val options = listOf(
            MirroringType.none,
            MirroringType.leftright
        )
        
        mirrorSelectBox = TranslatedSelectBox(options, mapParameters.mirroring)

        mirrorSelectBox.onChange {
            mapParameters.mirroring = mirrorSelectBox.selected.value
        }

        add("{Mirroring Type}:".toLabel()).left()
        add(mirrorSelectBox).fillX().row()
    }

    private fun Table.addNoRuinsCheckbox() {
        noRuinsCheckbox = "No Ancient Ruins".toCheckBox(mapParameters.noRuins) {
            mapParameters.noRuins = it
        }
        add(noRuinsCheckbox).row()
    }

    private fun Table.addNoNaturalWondersCheckbox() {
        noNaturalWondersCheckbox = "No Natural Wonders".toCheckBox(mapParameters.noNaturalWonders) {
            mapParameters.noNaturalWonders = it
        }
        add(noNaturalWondersCheckbox).row()
    }

    private fun Table.addWorldWrapCheckbox() {
        worldWrapCheckbox = "World Wrap".toCheckBox(mapParameters.worldWrap) {
            mapParameters.worldWrap = it
        }
        add(worldWrapCheckbox).row()
    }

    private fun Table.addStrategicBalanceCheckbox() {
        strategicBalanceCheckbox = "Strategic Balance".toCheckBox(mapParameters.strategicBalance) {
            mapParameters.strategicBalance = it
        }
        add(strategicBalanceCheckbox).row()
    }

    private fun Table.addLegendaryStartCheckbox() {
        legendaryStartCheckbox = "Legendary Start".toCheckBox(mapParameters.legendaryStart) {
            mapParameters.legendaryStart = it
        }
        add(legendaryStartCheckbox).row()
    }

    private fun addWrappedCheckBoxes() {
        val worldWrapWarning = "World wrap maps are very memory intensive - creating large world wrap maps on Android can lead to crashes!"
        if (portrait) {
            Table().apply {
                addStrategicBalanceCheckbox()
                addLegendaryStartCheckbox()
                addNoRuinsCheckbox()
                addNoNaturalWondersCheckbox()
                addWorldWrapCheckbox()
            }
            controls.section(this, "Other Settings", controls.switches(listOf(strategicBalanceCheckbox,
                legendaryStartCheckbox, noRuinsCheckbox, noNaturalWondersCheckbox, worldWrapCheckbox)))
            // The memory warning only matters while wrapping is on
            val note = controls.label(worldWrapWarning, 13f, PortraitOptionControls.MUTED).apply { wrap = true }
            val noteCell = add().colspan(2).growX()
            row()
            fun showNote() {
                val show = worldWrapCheckbox.isChecked
                noteCell.setActor(if (show) note else null).padTop(if (show) controls.s(8f) else 0f)
            }
            worldWrapCheckbox.onChange { showNote(); invalidateHierarchy() }
            showNote()
        } else if (mapGeneratedMainType == MapGeneratedMainType.randomGenerated) {
            add(ExpanderTab("{Other Settings}",
                icon = ImageGetter.getImage("OtherIcons/Settings").apply { setSize(20f, 20f) },
                persistenceID = "NewGameOtherSettings", startsOutOpened = false) {
                it.defaults().pad(5f,0f)
                it.addStrategicBalanceCheckbox()
                it.addLegendaryStartCheckbox()
                it.addNoRuinsCheckbox()
                it.addNoNaturalWondersCheckbox()
                it.addWorldWrapCheckbox()
                it.add(worldWrapWarning.toLabel(fontSize = 14).apply { wrap=true }).colspan(2).fillX().row()
            }).pad(10f).padTop(10f).colspan(2).growX().row()
        } else {
            add(Table(skin).apply {
                defaults().left().pad(2.5f)
                addStrategicBalanceCheckbox()
                addLegendaryStartCheckbox()
                addNoRuinsCheckbox()
                addNoNaturalWondersCheckbox()
                addWorldWrapCheckbox()
            }).colspan(2).center().row()
            add(worldWrapWarning.toLabel(fontSize = 14).apply { wrap=true }).colspan(2).fillX().row()
        }
    }

    private fun addAdvancedSettings() {
        if (portrait) {
            val content = Table().apply {
                background = controls.panel(PortraitOptionControls.TRACK)
                pad(controls.s(16f))
            }
            addAdvancedControls(content)
            controls.section(this, null, controls.disclosure("mapAdvanced", "Advanced Settings", content))
            return
        }
        val expander = ExpanderTab("Advanced Settings",
            icon = ImageGetter.getImage("OtherIcons/Settings").apply { setSize(20f, 20f) },
            startsOutOpened = false, defaultPad = 0f) {
            addAdvancedControls(it)
        }
        add(expander).padTop(10f).colspan(2).growX().row()
    }

    private fun addAdvancedControls(table: Table) {
        if (portrait) table.defaults().pad(0f)
        else table.defaults().pad(2f).padTop(10f)

        seedTextField = UncivTextField.Numeric("RNG Seed", mapParameters.seed, integerOnly = true)

        seedTextField.onChange {
            mapParameters.seed = seedTextField.value?.toLong() ?: 0L
        }

        if (portrait) {
            val c = controls
            table.add(c.label("RNG Seed", 13f, PortraitOptionControls.MUTED)).colspan(2).left().padBottom(c.s(8f)).row()
            val seedRow = Table()
            seedRow.add(seedTextField).growX().height(c.target)
            seedRow.add(c.iconButton("OtherIcons/Turn right", PortraitOptionControls.TARGET) { reseed() }).size(c.target).padLeft(c.s(8f))
            table.add(seedRow).colspan(2).growX().padBottom(c.s(4f)).row()
        } else {
            table.add("RNG Seed".toLabel()).left()
            table.add(seedTextField).fillX().padBottom(10f).row()
        }

        fun addSlider(text: String, getValue:()->Float, min: Float, max: Float, step: Float, onChange: (value: Float)->Unit): UncivSlider {
            val tipType = if (portrait) UncivSlider.TipType.None else UncivSlider.TipType.Permanent
            val slider = UncivSlider(min, max, step, onChange = {onChange(it); generateExampleMap()}, initial = getValue(), tipType = tipType)
            if (portrait) {
                table.add(controls.sliderTitle(text, slider)).colspan(2).growX().padTop(controls.s(16f)).row()
                table.add(controls.slider(slider)).colspan(2).growX().row()
            } else {
                table.add(text.toLabel()).left()
                table.add(slider).fillX().row()
            }
            advancedSliders[slider] = getValue
            return slider
        }

        fun addSlider(text: String, getValue:()->Float, min: Float, max: Float, onChange: (value: Float)->Unit): UncivSlider {
            val step = (max - min) / 20
            return addSlider(text, getValue, min, max, step, onChange)
        }

        fun addTextButton(text: String, shouldAddToTable: Boolean = false, action: ((Boolean) -> Unit)) {
            if (portrait) {
                val button = controls.button(text, PortraitDialog.Kind.Ghost) { action.invoke(true) }
                if (shouldAddToTable)
                    table.add(button).colspan(2).growX().height(controls.target).padTop(controls.s(20f)).row()
                return
            }
            val button = text.toTextButton()
            button.onClick { action.invoke(true) }
            if (shouldAddToTable)
                table.add(button).colspan(2).padTop(10f).row()
        }

        fun addCheckBox(text: String, initialState: Boolean, action: ((Boolean) -> Unit)) {
            val checkbox = text.toCheckBox(initialState){
                action(it)
            }
            table.add(checkbox).colspan(2).row()
        }
        if (forMapEditor) {
            addCheckBox("Randomize seed", randomizeSeed) {
                randomizeSeed = it
            }
        }
        addSlider("Map Elevation", {mapParameters.elevationExponent}, 0.6f, 0.8f)
        { mapParameters.elevationExponent = it }

        addSlider("Temperature intensity", {mapParameters.temperatureintensity}, 0.4f, 0.8f)
        { mapParameters.temperatureintensity = it }

        addSlider("Temperature shift", {mapParameters.temperatureShift}, -0.4f, 0.4f, 0.1f)
        { mapParameters.temperatureShift = it }

        if (forMapEditor) {
            addSlider("Resource richness", { mapParameters.resourceRichness }, 0f, 0.5f)
            { mapParameters.resourceRichness = it }
        }

        addSlider("Vegetation richness", {mapParameters.vegetationRichness}, 0f, 1f)
        { mapParameters.vegetationRichness = it }

        addSlider("Rare features richness", {mapParameters.rareFeaturesRichness}, 0f, 0.5f)
        { mapParameters.rareFeaturesRichness = it }

        addSlider("Max Coast extension", {mapParameters.maxCoastExtension.toFloat()}, 1f, 5f)
        { mapParameters.maxCoastExtension = it.toInt() }.apply { stepSize = 1f }

        addSlider("Biome size", {mapParameters.tilesPerBiomeArea.toFloat()}, 1f, 15f)
        { mapParameters.tilesPerBiomeArea = it.toInt() }.apply { stepSize = 1f }

        addSlider("Water level", {mapParameters.waterThreshold}, -0.1f, 0.1f)
        { mapParameters.waterThreshold = it }

        addTextButton("Reset to defaults", true) {
            mapParameters.resetAdvancedSettings()
            seedTextField.value = mapParameters.seed
            for (entry in advancedSliders)
                entry.key.value = entry.value()
        }
    }
}
