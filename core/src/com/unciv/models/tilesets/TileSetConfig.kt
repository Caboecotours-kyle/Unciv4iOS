package com.unciv.models.tilesets

import com.badlogic.gdx.graphics.Color
import com.unciv.Constants
import com.unciv.ui.images.ImageGetter

class TileSetConfig {
    var useColorAsBaseTerrain = false
    var useSummaryImages = false
    var unexploredTileColor: Color = Color.DARK_GRAY
    var fogOfWarColor: Color = ImageGetter.CHARCOAL
    /** What the world map shows beyond the drawn tiles. Null keeps the skin's screen background. */
    var mapBackgroundColor: Color? = null
    /** Name of the tileset to use when this one is missing images. Null to disable. */
    var fallbackTileSet: String? = Constants.defaultFallbackTileset
    /** Scale factor for hex images, with hex center as origin. */
    var tileScale: Float = 1f
    var tileScales: HashMap<String, Float> = HashMap()
    var ruleVariants: HashMap<String, Array<String>> = HashMap()

    fun clone(): TileSetConfig {
        val toReturn = TileSetConfig()
        toReturn.useColorAsBaseTerrain = useColorAsBaseTerrain
        toReturn.useSummaryImages = useSummaryImages
        toReturn.unexploredTileColor = unexploredTileColor
        toReturn.fogOfWarColor = fogOfWarColor
        toReturn.mapBackgroundColor = mapBackgroundColor
        toReturn.fallbackTileSet = fallbackTileSet
        toReturn.tileScale = tileScale
        toReturn.tileScales = tileScales
        toReturn.ruleVariants.putAll(ruleVariants.map { Pair(it.key, it.value.clone()) })
        return toReturn
    }

    fun updateConfig(other: TileSetConfig) {
        useColorAsBaseTerrain = other.useColorAsBaseTerrain
        useSummaryImages = other.useSummaryImages
        unexploredTileColor = other.unexploredTileColor
        fogOfWarColor = other.fogOfWarColor
        if (other.mapBackgroundColor != null) mapBackgroundColor = other.mapBackgroundColor
        fallbackTileSet = other.fallbackTileSet
        tileScale = other.tileScale
        for ((tileString, scale) in other.tileScales) {
            tileScales[tileString] = scale
        }
        for ((tileSetString, renderOrder) in other.ruleVariants) {
            ruleVariants[tileSetString] = renderOrder
        }
    }
}
