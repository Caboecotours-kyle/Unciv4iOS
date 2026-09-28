package com.unciv.ui.components.tilegroups.layers

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.unciv.UncivGame
import com.unciv.view.CivView
import com.unciv.view.TileView
import com.unciv.logic.map.NeighborDirection
import com.unciv.models.tilesets.TileProp
import com.unciv.ui.components.tilegroups.TileGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

class TileLayerTerrain(tileGroup: TileGroup, size: Float) : TileLayer(tileGroup, size) {

    val tileBaseImages: ArrayList<Image> = ArrayList()
    /** Portrait tileset props of [tileBaseImages], each drawn right after the image it belongs to */
    val propImages: ArrayList<PropImage> = ArrayList()
    private var tileImageIdentifiers = listOf<String>()
    private var previousStrategicView = false
    private var bottomRightRiverImage: Image? = null
    private var bottomRiverImage: Image? = null
    private var bottomLeftRiverImage: Image? = null

    private fun getTerrainImageLocations(terrainSequence: Sequence<String>): List<String> {
        val allTerrains = terrainSequence.joinToString("+")
        if (strings.tileSetConfig.ruleVariants.containsKey(allTerrains))
            return strings.tileSetConfig.ruleVariants[allTerrains]!!.map { strings.getTile(it) }
        val allTerrainTile = strings.getTile(allTerrains)
        return if (ImageGetter.imageExists(allTerrainTile)) listOf(allTerrainTile)
        else terrainSequence.map { strings.orFallback { getTile(it) } }.toList()
    }

    private fun getImprovementAndResourceImages(resourceAndImprovementSequence: Sequence<String>): List<String> {
        val altogether = resourceAndImprovementSequence.joinToString("+").let { strings.getTile(it) }
        return if (ImageGetter.imageExists(altogether)) listOf(altogether)
        else resourceAndImprovementSequence.map { strings.orFallback { getTile(it) } }.toList()
    }

    private fun usePillagedImprovementImage(tileView: TileView): Boolean {
        if (!tileView.improvementIsPillaged || !UncivGame.Current.settings.showPixelImprovements) return false
        val shownImprovement = tileView.getShownImprovement() ?: return false
        return ImageGetter.imageExists(strings.getTile("$shownImprovement-Pillaged"))
    }

    private fun getTileBaseImageLocations(viewingCiv: CivView?): List<String> {

        val isForceVisible = tileGroup.tileView.isForceVisible()

        if (viewingCiv == null && !isForceVisible)
            return strings.hexagonList

        val tileView = tileGroup.tileView

        val shownImprovement = if (!UncivGame.Current.settings.showPixelImprovements) null
            else tileView.getShownImprovement()

        val viewableResource = if (!UncivGame.Current.settings.showPixelImprovements) null
            else tileView.getViewableResource(if (isForceVisible) null else viewingCiv)

        val resourceAndImprovementSequence = if (viewableResource == null && shownImprovement == null)
            emptySequence()
        else sequence {
            if (viewableResource != null)  yield(viewableResource.name)
            if (shownImprovement != null) {
                if (usePillagedImprovementImage(tileView))
                    yield("$shownImprovement-Pillaged")
                else yield(shownImprovement)
            }
        }

        val terrainImages = when {
            tileView.naturalWonder != null -> sequenceOf(tileView.baseTerrain, tileView.naturalWonder!!)
            // very common case, most oceans have nothing else going on
            tileView.terrainFeatures.isEmpty() -> sequenceOf(tileView.baseTerrain)
            else -> sequenceOf(tileView.baseTerrain) + tileView.terrainFeatures.asSequence()
        }

        val edgeImages = getEdgeTileLocations()

        val allTogether = when {
            resourceAndImprovementSequence.any() -> (terrainImages + resourceAndImprovementSequence).joinToString("+")
            tileView.naturalWonder == null && tileView.terrainFeatures.isEmpty() -> tileView.baseTerrain // single string
            else -> terrainImages.joinToString("+")
        }
        val allTogetherLocation = strings.getTile(allTogether)


        val baseHexagon: ArrayList<String> = if (strings.tileSetConfig.useColorAsBaseTerrain)
            ArrayList<String>().apply { add(strings.hexagon) }
        else ArrayList()
        // If the tilesetconfig *explicitly* lists the terrains+improvements etc, we can't know where in that list to place the edges
        //   So we default to placing them over everything else.
        // If there is no explicit list, then we can know to place them between the terrain and the improvement
        return when {
            strings.tileSetConfig.ruleVariants[allTogether] != null -> baseHexagon.apply {
                addAll(strings.tileSetConfig.ruleVariants[allTogether]!!.map { strings.getTile(it) })
                addAll(edgeImages)
            }
            ImageGetter.imageExists(allTogetherLocation) -> baseHexagon.apply {
                add(allTogetherLocation)
                addAll(edgeImages)
            }
            tileView.naturalWonder != null -> getNaturalWonderBackupImage(baseHexagon) + edgeImages
            else -> baseHexagon.apply {
                addAll(getTerrainImageLocations(terrainImages))
                addAll(edgeImages)
                if (resourceAndImprovementSequence.any())
                    addAll(getImprovementAndResourceImages(resourceAndImprovementSequence))
            }
        }
    }

    /** See https://yairm210.github.io/Unciv/Modders/Creating-a-custom-tileset/#edge-images
     * This caches the filenames of edges to be rendered, given that we have ourTerrains and the neighbor has neighborTerrains
     * Since terrains can change over time, if one of these assumptions is false we recalculate the edge tiles
     * */
    private class NeighborEdgeData(val neighbor: TileView, val direction: NeighborDirection?) {
        var ourTerrains: Set<String> = emptySet()
        var neighborTerrains: Set<String> = emptySet()
        var edgeFileNames: List<String> = emptyList()
    }

    // Only explored neighbors can have edge images computed - unexplored neighbors' terrain is unknown.
    // As more neighbors become explored, entries are added here rather than fixed once at construction.
    private val neighborEdgeDataByNeighbor = HashMap<TileView, NeighborEdgeData>()

    private fun getEdgeTileLocations(): Sequence<String> =
        tileGroup.tileView.getVisibleNeighbors().flatMap { neighbor ->
            val neighborEdgeData = neighborEdgeDataByNeighbor.getOrPut(neighbor) {
                val clockPosition = tileGroup.tileView.getNeighborClockPosition(neighbor)
                val direction = NeighborDirection.byClockPosition[clockPosition]
                NeighborEdgeData(neighbor, direction)
            }
            getMatchingEdges(neighborEdgeData)
        }

    /** See https://yairm210.github.io/Unciv/Modders/Creating-a-custom-tileset/#edge-images */
    private fun getMatchingEdges(neighborEdgeData: NeighborEdgeData): List<String>{
        val tileView = tileGroup.tileView
        // If the terrain data is still up to date, used the cached filenames
        if (neighborEdgeData.ourTerrains == tileView.getCachedTerrainNameSet()
            && neighborEdgeData.neighborTerrains == neighborEdgeData.neighbor.getCachedTerrainNameSet())
                return neighborEdgeData.edgeFileNames

        if (neighborEdgeData.direction == null) return emptyList()
        val possibleEdgeImages = strings.edgeImagesByPosition[neighborEdgeData.direction] ?: return emptyList()

        // Required for performance - full matchesFilter is too expensive for something that needs to run every update()
        @Readonly
        fun matchesFilterMinimal(originTileView: TileView, filter: String): Boolean {
            if (originTileView.getCachedTerrainNameSet().contains(filter)) return true
            if (originTileView.getBaseTerrain().type.name == filter) return true
            return false
        }

        // Chain the filter{} and map{} with sequences to avoid creating intermediate list,
        //  but then resolve it to a proper list.
        val cachedSequence = possibleEdgeImages.asSequence().filter {
            if (!matchesFilterMinimal(tileView, it.originTileFilter)) return@filter false
            if (!matchesFilterMinimal(neighborEdgeData.neighbor, it.destinationTileFilter)) return@filter false
            return@filter true
        }.map { it.fileName }.toList()

        neighborEdgeData.ourTerrains = tileView.getCachedTerrainNameSet()
        neighborEdgeData.neighborTerrains = neighborEdgeData.neighbor.getCachedTerrainNameSet()
        neighborEdgeData.edgeFileNames = cachedSequence

        return cachedSequence
    }

    private fun updateTileImage(viewingCiv: CivView?) {
        val tileBaseImageLocations = getTileBaseImageLocations(viewingCiv)

        if (previousStrategicView == tileGroup.strategicView && tileBaseImageLocations.size == tileImageIdentifiers.size) {
            if (tileBaseImageLocations.withIndex().all { (i, imageLocation) -> tileImageIdentifiers[i] == imageLocation })
                return // All image identifiers are the same as the current ones, no need to change anything
        }
        tileImageIdentifiers = tileBaseImageLocations
        previousStrategicView = tileGroup.strategicView

        // Record where old terrain images sit in TileMapLayer so new ones can be inserted at
        // the same position, preserving the isometric Z-order established at map construction.
        val mapInsertIdx = if (parentMapLayer != null && tileBaseImages.isNotEmpty())
            actorParent!!.children.indexOf(tileBaseImages.first())
        else -1
        // In ownedActors, terrain images precede river images; new terrain replaces from index 0.
        val ownedInsertIdx = if (mapInsertIdx >= 0) 0 else -1

        for (image in tileBaseImages) removeOwnedActor(image)
        tileBaseImages.clear()
        for (image in propImages) removeOwnedActor(image)
        propImages.clear()

        var nextMapIdx = mapInsertIdx
        var nextOwnedIdx = ownedInsertIdx
        // Strategic view draws no props; switching to it rebuilds these images anyway
        val propsByImage = if (tileGroup.strategicView) null else strings.tileSetConfig.props.takeIf { it.isNotEmpty() }
        val newProps = if (propsByImage == null) null else ArrayList<PropImage>()
        // A city or an improvement takes the whole hex: the ground's own upright decor (tufts, flowers, pebbles,
        // shore palms) stays off it, while its ground art and the props of what stands there remain
        val busy = propsByImage != null && UncivGame.Current.settings.showPixelImprovements
            && tileGroup.tileView.getShownImprovement() != null

        for (baseLocation in tileBaseImageLocations) {
            // Here we check what actual tiles exist, and pick one - not at random, but based on the tile location,
            // so it stays consistent throughout the game
            if (!ImageGetter.imageExists(baseLocation)) continue

            val locationToCheck =
                    if (tileGroup.tileView.owningCity() != null)
                        strings.getOwnedTileImageLocation(baseLocation, tileGroup.tileView.getOwner()!!)
                    else baseLocation

            val existingImages = ArrayList<String>()
            existingImages.add(locationToCheck)
            var i = 2
            while (true) {
                val tileVariant = locationToCheck + i
                if (ImageGetter.imageExists(tileVariant)) existingImages.add(tileVariant)
                else break
                i += 1
            }
            val finalLocation = existingImages.random(
                Random(tileGroup.tileView.position().hashCode() + locationToCheck.hashCode()))
            var mapLocation = strings.getMapImageLocation(finalLocation)
            if (tileGroup.strategicView && !baseLocation.contains("/Edges/") && baseLocation != strings.hexagon) {
                val strategicLocation = mapLocation.replace("/Tilted/", "/Strategic/")
                if (!ImageGetter.imageExists(strategicLocation)) continue
                mapLocation = strategicLocation
            }
            val groundOnly = baseLocation == strings.hexagon || baseLocation.endsWith("/" + tileGroup.tileView.baseTerrain)
                || baseLocation.contains("/Edges/")
            val image = if (mapLocation == finalLocation && groundOnly) getGroundImage(finalLocation)
                else ImageGetter.getImage(mapLocation)
            image.name = finalLocation // for debug mode reveal

            tileBaseImages.add(image)
            if (nextMapIdx >= 0) {
                // Insert at the recorded position to keep isometric Z-order intact.
                ownedActors.add(nextOwnedIdx++, image)
                actorParent!!.addActorAt(nextMapIdx++, image)
            } else {
                addOwnedActor(image)
            }

            if (strings.tileSetConfig.tileScales.isNotEmpty()) {
                val scale = strings.tileSetConfig.tileScales[baseLocation.takeLastWhile { it != '/' }]
                image.setHexagonSize(scale)
            } else {
                image.setHexagonSize()
            }

            // Any drawn image may carry props, edges included
            val props = propsByImage?.get(mapLocation) ?: continue
            val bareGround = busy && (baseLocation.contains("/Edges/") || baseLocation.endsWith("/" + tileGroup.tileView.baseTerrain))
            for (prop in props)
                if ((!bareGround || prop.ground) && ImageGetter.imageExists(prop.image)) newProps!!.add(PropImage(prop, image, mapLocation))
        }

        // After all of the tile's images, so edge bands and overlays never paint over them: ground art first, then back to front
        if (newProps.isNullOrEmpty()) return
        newProps.sortWith(propDrawOrder)
        for (propImage in newProps) {
            propImages.add(propImage)
            if (nextMapIdx >= 0) {
                ownedActors.add(nextOwnedIdx++, propImage)
                actorParent!!.addActorAt(nextMapIdx++, propImage)
            } else addOwnedActor(propImage)
            propImage.place()
        }
    }

    /** A tileset prop (see [TileSetConfig.props]): anchored at its bottom centre, hidden below its level of detail,
     *  tinted like the [owner] image it belongs to. It resizes itself for the current zoom when drawn, so a pinch
     *  costs only the props on screen, and follows the tile's ground image if a preview moves that. */
    inner class PropImage(val prop: TileProp, val owner: Image, val ownerLocation: String) : Image(ImageGetter.getDrawable(prop.image)) {
        var shownAtZoom = true
            private set
        private var placedDamping = Float.NaN
        private var placedTier = -1
        private var placedStrategic = false
        private var placedX = Float.NaN
        private var placedY = Float.NaN
        private var groundX = Float.NaN
        private var groundY = Float.NaN

        init { touchable = Touchable.disabled }

        override fun draw(batch: Batch?, parentAlpha: Float) {
            val ground = tileBaseImages.firstOrNull() ?: return
            if (placedDamping != tileGroup.portraitDamping || placedTier != tileGroup.portraitLodTier
                || placedStrategic != tileGroup.strategicView || x != placedX || y != placedY
                || ground.x != groundX || ground.y != groundY) place()
            if (shownAtZoom) super.draw(batch, parentAlpha)
        }

        fun place() {
            val ground = tileBaseImages.firstOrNull() ?: return
            placedDamping = tileGroup.portraitDamping
            placedTier = tileGroup.portraitLodTier
            placedStrategic = tileGroup.strategicView
            groundX = ground.x
            groundY = ground.y
            val hexWidth = tileGroup.hexagonImageWidth
            // Upright props grow slower than the hex; their spread over the hex does not
            val width = prop.w * hexWidth * if (prop.ground) 1f else placedDamping
            val height = width * prefHeight / prefWidth
            // The ground image's origin is the tile's ground centre
            val anchorX = groundX + tileGroup.hexagonImageOriginX + prop.x * hexWidth
            val anchorY = groundY + tileGroup.hexagonImageOriginY + prop.y * hexWidth
            setBounds(anchorX - width / 2f, anchorY, width, height)
            setOrigin(width / 2f, 0f)
            placedX = x
            placedY = y
            shownAtZoom = !placedStrategic && placedTier >= prop.lod
        }
    }

    private fun updateTileColor(viewingCiv: CivView?) {
        val isViewable = isViewable(viewingCiv)
        val tileView = tileGroup.tileView
        val colorPillagedTile = isViewable && tileView.isPillaged() && !usePillagedImprovementImage(tileView)

        val baseTerrainColor = when {
            colorPillagedTile && strings.tileSetConfig.useColorAsBaseTerrain -> tileView.getBaseTerrain()
                .getColor().lerp(Color.BROWN, 0.6f)
            colorPillagedTile -> Color.WHITE.cpy().lerp(Color.BROWN, 0.6f)
            strings.tileSetConfig.useColorAsBaseTerrain && !isViewable -> tileView.getBaseTerrain()
                .getColor().lerp(strings.tileSetConfig.fogOfWarColor, 0.6f)
            strings.tileSetConfig.useColorAsBaseTerrain -> tileView.getBaseTerrain()
                .getColor()
            !isViewable -> Color.WHITE.cpy().lerp(strings.tileSetConfig.fogOfWarColor, 0.6f)
            else -> Color.WHITE.cpy()
        }

        val color = when {
            colorPillagedTile -> Color.WHITE.cpy().lerp(Color.RED.cpy(), 0.5f)
            !isViewable -> Color.WHITE.cpy()
                .lerp(strings.tileSetConfig.fogOfWarColor, 0.6f)
            else -> Color.WHITE.cpy()
        }

        for ((index, image) in tileBaseImages.withIndex())
            image.color = if (index == 0) baseTerrainColor else color
        for (image in propImages) image.color = image.owner.color
        // Rivers keep their colour otherwise, and the portrait move range shades them
        if (strings.projection.tilted) for (river in arrayOf(bottomRightRiverImage, bottomRiverImage, bottomLeftRiverImage))
            river?.color?.set(Color.WHITE)
    }

    /** Portrait move range: tints every image of this tile toward [color] by [amount], like a translucent hexagon laid
     *  over the tile and its props but under its units. The next update's colours undo it. */
    fun shade(color: Color, amount: Float) = forEachOwnedActor { it.color.shadeToward(color, amount) }

    private fun updateRivers(displayBottomRight: Boolean, displayBottom: Boolean, displayBottomLeft: Boolean) {
        bottomRightRiverImage = updateRiver(bottomRightRiverImage,displayBottomRight, strings.bottomRightRiver)
        bottomRiverImage = updateRiver(bottomRiverImage, displayBottom, strings.bottomRiver)
        bottomLeftRiverImage = updateRiver(bottomLeftRiverImage, displayBottomLeft, strings.bottomLeftRiver)
    }

    private fun updateRiver(currentImage: Image?, shouldDisplay: Boolean, imageName: String): Image? {
        if (!shouldDisplay) {
            currentImage?.let { removeOwnedActor(it) }
            return null
        } else {
            if (currentImage != null) return currentImage // already in place, no movement needed
            if (!ImageGetter.imageExists(imageName)) return null // Old "Default" tileset gets no rivers.
            val newImage = getGroundImage(imageName)
            newImage.setHexagonSize()
            addOwnedActor(newImage) // rivers always append after terrain images
            return newImage
        }
    }

    fun dim(brightness: Float) {
        // Dimming with alpha looks weird with overlapping tiles— Can't just set group alpha.
        // Image.draw() doesn't inherit parent colour tints— Can't just set group colour.
        // Covering up with fogImage or adding another tileset image with variable alpha doesn't look good for all terrain shapes— Can't cover up group with image.
        // Directly setting child actor colors breaks Default tileset terrain colours— Need to interpolate between existing and background colour.
        //
        // Reapplying the colour with every update is fine, and doesn't cause the tiles to get darker with every click, because the colours are always reset in either TileGroup.updateTileColor() or the early exit by super.update()/TileGroup.update().
        // Mutating the Color() in-place seems dangerous, but GDX source already mutates even when Kotlin notation suggests it's replacing.
        for (image in tileBaseImages) {
            image.color.lerp(BaseScreen.clearColor, 1-brightness)
        }
        for (image in propImages) image.color.lerp(BaseScreen.clearColor, 1 - brightness)
    }

    override fun doUpdate(viewingCiv: CivView?) {
        updateTileImage(viewingCiv)
        updateRivers(tileGroup.tileView.hasBottomRightRiver, tileGroup.tileView.hasBottomRiver, tileGroup.tileView.hasBottomLeftRiver)
        updateTileColor(viewingCiv)
    }

    fun reset() {
        isVisible = false
        updateRivers(displayBottomRight = false, displayBottom = false, displayBottomLeft = false)
    }

    private companion object {
        val propDrawOrder = compareBy(TileProp.drawOrder) { image: PropImage -> image.prop }
    }

    private fun getNaturalWonderBackupImage(baseHexagon: ArrayList<String>): ArrayList<String> =
            if (strings.tileSetConfig.useSummaryImages) baseHexagon.apply { add(strings.naturalWonder) }
            else baseHexagon.apply { add(strings.orFallback{ getTile(tileGroup.tileView.naturalWonder!!) }) }

}

data class EdgeTileImage(val fileName:String, val originTileFilter: String, val destinationTileFilter: String, val edgeType: NeighborDirection)
