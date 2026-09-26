package com.unciv.ui.screens.cityscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.city.*
import com.unciv.models.Counter
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.view.CityView
import kotlin.math.ceil
import kotlin.math.roundToInt

class CityStatsTable(private val cityScreen: CityScreen,
                     private val onPortraitBuildingSelected: ((Building) -> Unit)? = null) : Table() {
    private val cityView: CityView = cityScreen.cityView
    private val expander: ExpanderTab
    // table within this Table. Slightly smaller creates border
    private val miniStatsTable = MiniStatsTable(ExpanderTab.wasOpen("CityStatsTable"))
    private val lowerTable = Table() // table that will be in the ScrollPane
    private val lowerPane = AutoScrollPane(lowerTable)
    private var lowerCell: Cell<AutoScrollPane>? = null

    private val detailedStatsButton = "Stats".toTextButton().apply {
        labelCell.pad(10f)
        onActivation(binding = KeyboardBinding.ShowStats) {
            DetailedStatsPopup(cityScreen).open()
        }
    }

    init {
        if (!cityScreen.isPortrait()) {
            pad(2f)
            background = BaseScreen.skinStrings.getUiBackground(
                "CityScreen/CityStatsTable/Background",
                tintColor = colorFromRGB(194, 180, 131)
            )
        }

        expander = ExpanderTab("",
            startsOutOpened = true, // portrait shows city stats alone on their own tab
            persistenceID = "CityStatsTable",
            defaultPad = 7f,
            headerPad = if (cityScreen.isCrampedPortrait()) 7f else 6f,
            topPad = 0f, // remove space between miniStatsTable and detailedStatsButton
            expanderWidth = miniStatsTable.width,
            expanderHeight = miniStatsTable.height,
            onChange = {
                cityScreen.updateWithoutConstructionAndMap()
            }
        ) {
            lowerCell = it.add(lowerPane).grow()
        }
        expander.headerContent.add(miniStatsTable).growX()
        expander.background = BaseScreen.skinStrings.getUiBackground(
            "CityScreen/CityStatsTable/InnerTable",
            tintColor = ImageGetter.CHARCOAL.cpy().apply { a = 0.8f }
        )
        expander.header.background = null // Make header transparent
        /** Without this, the expander will keep initial header height, even when the
         *  row count of miniStatsTable changes when opening/closing in portrait mode */
        expander.setDynamicHeaderSize()
        // Don't toggle expander when clicking the stats icons
        expander.toggleOnIconOnly()

        lowerPane.setOverscroll(false, false)
        lowerPane.setScrollingDisabled(x = true, y = false)
        lowerTable.defaults().space(4f)

        if (cityScreen.isPortrait()) {
            lowerTable.background = portraitBackground(Color.valueOf("20394f"))
            add(lowerTable).width(369f)
        } else add(expander).growX()
    }

    fun update(height: Float) {
        if (cityScreen.isPortrait()) {
            updatePortrait()
            return
        }
        miniStatsTable.update()

        lowerTable.clear()

        lowerTable.add(detailedStatsButton).row()
        addText()

        // begin lowerTable
        addCitizenManagement()
        addGreatPersonPointInfo()
        if (!cityView.getMaxSpecialists().isEmpty()) {
            addSpecialistInfo()
        }
        if (cityView.getNumberOfFollowers().isNotEmpty() && cityView.viewingCiv().isReligionEnabled())
            addReligionInfo()

        addBuildingsInfo()

        lowerTable.pack()
        lowerPane.layout()
        lowerPane.updateVisualScroll()
        expander.header.pack() //  set header height correctly for next line
        lowerCell?.maxHeight(height - expander.header.height - 8f) // 2 on each side of each cell in expander
        expander.pack() // update expander so the scrollpane is scollable immediately without need for click
        pack()  // update self last
    }

    private fun portraitBackground(color: Color) = BaseScreen.skinStrings.getUiBackground(
        "", BaseScreen.skinStrings.roundedEdgeRectangleShape, color)

    private fun portraitTitle(title: String) {
        lowerTable.add(title.toLabel(Color.valueOf("8eacc2"), 14)).width(345f).left()
            .padTop(12f).padBottom(6f).row()
    }

    private fun portraitRow(actor: Actor, height: Float = 48f) {
        lowerTable.add(actor).width(345f).minHeight(height).fillY().left().padBottom(8f).row()
    }

    private fun portraitButton(text: String, enabled: Boolean = true, primary: Boolean = false,
                               binding: KeyboardBinding = KeyboardBinding.None, action: () -> Unit): TextButton {
        val button = text.toTextButton()
        val color = if (primary) Color.valueOf("ffc93c") else Color(1f, 1f, 1f, .08f)
        button.style = TextButton.TextButtonStyle(button.style).apply {
            up = portraitBackground(color)
            down = portraitBackground(color)
            disabled = portraitBackground(Color(1f, 1f, 1f, .08f))
            fontColor = if (primary) Color.valueOf("3a2a00") else Color.WHITE
            disabledFontColor = Color.valueOf("8eacc2")
        }
        if (enabled) button.onActivation(binding = binding) { action() } else button.disable()
        return button
    }

    private fun portraitTextRow(text: String, onTap: (() -> Unit)? = null) {
        val row = Table().apply { background = portraitBackground(Color(1f, 1f, 1f, .06f)); pad(8f, 12f, 8f, 12f) }
        row.add(text.toLabel().apply { wrap = true }).width(321f).left()
        if (onTap != null) {
            row.touchable = Touchable.enabled
            row.onClick { onTap() }
        }
        portraitRow(row)
    }

    private fun updatePortrait() {
        lowerTable.clear()
        lowerTable.pad(12f)
        portraitTitle("City yields")
        val statGrid = Table()
        var column = 0
        for (stat in Stat.entries) {
            if (stat == Stat.Faith && !cityView.viewingCiv().isReligionEnabled()) continue
            val focus = CityFocus.safeValueOf(stat)
            val selected = focus == cityView.getCityFocus()
            val nextFocus = if (selected) CityFocus.NoFocus else focus
            val chip = Table().apply {
                background = portraitBackground(if (selected) Color(1f, 1f, 1f, .18f) else Color(1f, 1f, 1f, .06f))
            }
            chip.add(ImageGetter.getStatIcon(stat.name)).size(24f).padRight(6f)
            val value = if (stat == Stat.Happiness) cityView.getHappinessList().values.sum()
                else cityView.getCurrentCityStats()[stat]
            chip.add(value.roundToInt().toLabel()).left()
            if (cityScreen.canCityBeChanged()) {
                chip.touchable = Touchable.enabled
                chip.onActivation(binding = nextFocus.binding) {
                    cityView.trySetCityFocus(nextFocus)
                    cityScreen.updateAsync()
                }
            }
            statGrid.add(chip).width(109f).height(48f).padRight(if (column == 2) 0f else 6f).padBottom(6f)
            column++
            if (column == 3) { statGrid.row(); column = 0 }
        }
        portraitRow(statGrid)
        portraitRow(portraitButton("Stats", binding = KeyboardBinding.ShowStats) {
            DetailedStatsPopup(cityScreen).open()
        })
        addPortraitStatus()
        addPortraitCitizenManagement()
        addPortraitGreatPeople()
        if (!cityView.getMaxSpecialists().isEmpty()) addPortraitSpecialists()
        if (cityView.getNumberOfFollowers().isNotEmpty() && cityView.viewingCiv().isReligionEnabled())
            addPortraitReligion()
        addPortraitBuildings()
        lowerTable.pack()
        pack()
    }

    private fun addPortraitStatus() {
        portraitTitle("Population and expansion")
        val unassigned = "{Unassigned population}: ".tr() +
            cityView.getFreePopulation().tr() + "/" + cityView.getPopulationCount().tr()
        portraitTextRow(unassigned, if (cityScreen.canChangeState) ({
            cityView.tryReassignPopulation()
            cityScreen.updateAsync()
        }) else null)

        val expansion = if (cityView.getCurrentCityStats().culture > 0 && cityView.hasChoosableTiles()) {
            val remaining = cityView.getCultureToNextTile() - cityView.getCultureStored()
            val turns = ceil(remaining / cityView.getCurrentCityStats().culture).toInt().coerceAtLeast(1)
            "[$turns] turns to expansion".tr()
        } else "Stopped expansion".tr()
        val expansionProgress = if (cityView.hasChoosableTiles())
            " (${cityView.getCultureStored()}${Fonts.culture}/${cityView.getCultureToNextTile()}${Fonts.culture})"
        else ""
        portraitTextRow(expansion + expansionProgress)

        val growth = when {
            cityView.isStarving() -> "[${cityView.getNumTurnsToStarvation()}] turns to lose population"
            cityView.getRuleset().units[cityView.currentConstructionName()]
                .let { it != null && it.hasUnique(UniqueType.ConvertFoodToProductionWhenConstructed) } ->
                "Food converts to production"
            cityView.isGrowing() -> "[${cityView.getNumTurnsToNewPopulation()}] turns to new population"
            else -> "Stopped population growth"
        }.tr()
        portraitTextRow(growth + " (${cityView.getFoodStored()}${Fonts.food}/${cityView.getFoodToNextPopulation()}${Fonts.food})")
        if (cityView.isInResistance())
            portraitTextRow("In resistance for another [${cityView.getFlag(CityFlags.Resistance)}] turns")

        val resources = Counter<TileResource>()
        for (supply in cityView.getCityResourcesAvailableToCity())
            if (supply.resource.getMatchingUniques(UniqueType.NotShownOnWorldScreen, cityView.getState()).none())
                resources.add(supply.resource, supply.amount)
        for ((name, amount) in cityView.getResourceStockpiles()) {
            val resource = cityView.getRuleset().tileResources[name] ?: continue
            if (resource.getMatchingUniques(UniqueType.NotShownOnWorldScreen, cityView.getState()).none())
                resources.add(resource, amount)
        }
        if (resources.any { it.key.isCityWide }) portraitTitle("Resources")
        for ((resource, amount) in resources) {
            if (!resource.isCityWide) continue
            val row = Table().apply { background = portraitBackground(Color(1f, 1f, 1f, .06f)); pad(0f, 12f, 0f, 12f) }
            row.add(ImageGetter.getResourcePortrait(resource.name, 28f)).size(28f).padRight(10f)
            row.add(resource.name.toLabel().apply { wrap = true }).width(233f).left()
            row.add(amount.toLabel()).width(40f).right()
            row.touchable = Touchable.enabled
            row.onClick { cityScreen.openCivilopedia(resource.makeLink()) }
            portraitRow(row)
        }
        val wltk = when {
            cityView.isWeLoveTheKingDayActive() ->
                "We Love The King Day for another [${cityView.getFlag(CityFlags.WeLoveTheKing)}] turns"
            cityView.demandedResource.isNotEmpty() -> "Demanding [${cityView.demandedResource}]"
            else -> null
        }
        if (wltk != null) portraitTextRow(wltk) {
            cityScreen.openCivilopedia("Tutorial/We Love The King Day")
        }
    }

    private fun addPortraitCitizenManagement() {
        portraitTitle("Citizen management")
        portraitRow(portraitButton("Reset Citizens", cityScreen.canCityBeChanged(), binding = KeyboardBinding.ResetCitizens) {
            cityView.tryReassignPopulation(resetLocked = true)
            cityScreen.updateAsync()
        })
        portraitRow(portraitButton("Avoid Growth", cityScreen.canCityBeChanged(), cityView.avoidGrowth,
            KeyboardBinding.AvoidGrowth) {
            cityView.tryToggleAvoidGrowth()
            cityScreen.updateAsync()
        })
        portraitTitle("Citizen focus")
        val grid = Table()
        var column = 0
        for (focus in CityFocus.entries) {
            if (!focus.tableEnabled || focus == CityFocus.FaithFocus && !cityView.viewingCiv().isReligionEnabled()) continue
            val button = portraitButton(focus.label, cityScreen.canCityBeChanged(), cityView.getCityFocus() == focus,
                if (cityView.getCityFocus() == focus) focus.binding else KeyboardBinding.None) {
                cityView.trySetCityFocus(focus)
                cityScreen.updateAsync()
            }
            button.label.wrap = true
            grid.add(button).width(168f).minHeight(48f).padRight(if (column == 0) 9f else 0f).padBottom(8f)
            column++
            if (column == 2) { grid.row(); column = 0 }
        }
        portraitRow(grid)
    }

    private fun addPortraitGreatPeople() {
        val breakdown = cityView.getGreatPersonPointsBreakdown()
        if (breakdown.allNames.isEmpty()) return
        portraitTitle("Great People")
        val points = breakdown.sum()
        for (name in breakdown.allNames) {
            val person = cityView.getRuleset().units[name] ?: continue
            val current = cityView.viewingCiv().getGreatPersonPoints(name)
            val needed = cityView.viewingCiv().getPointsRequiredForGreatPerson(name)
            val card = Table().apply { background = portraitBackground(Color(1f, 1f, 1f, .06f)); pad(10f) }
            card.add(ImageGetter.getUnitIcon(person, Color.GOLD).toGroup(36f)).size(36f).padRight(10f)
            card.add("{$name} (+${points[name]})".toLabel(hideIcons = true).apply { wrap = true })
                .width(279f).left().row()
            val bar = ImageGetter.ProgressBar(300f, 25f, false)
            bar.setBackground(ImageGetter.CHARCOAL.cpy().apply { a = .8f })
            bar.setProgress(Color.ORANGE, current / needed.toFloat())
            bar.setLabel(Color.WHITE, "$current/$needed", fontSize = 14)
            card.add(bar).colspan(2).width(300f).padTop(8f).left().row()
            portraitRow(card, 85f)
            val actions = Table()
            actions.add(portraitButton("Breakdown") {
                GreatPersonPointsBreakdownPopup(cityScreen, breakdown, name)
            }).width(168f).height(48f).padRight(9f)
            actions.add(portraitButton("All great people") {
                GreatPersonPointsBreakdownPopup(cityScreen, breakdown, null)
            }).width(168f).height(48f)
            portraitRow(actions)
        }
    }

    private fun addPortraitSpecialists() {
        portraitTitle("Specialists")
        if (cityScreen.canCityBeChanged()) {
            val toggle = if (cityView.manualSpecialists) "Manual Specialists" else "Auto Specialists"
            portraitRow(portraitButton(toggle) {
                if (cityView.manualSpecialists) {
                    cityView.tryDisableManualSpecialists()
                    cityScreen.updateAsync()
                } else {
                    cityView.tryEnableManualSpecialists()
                    cityScreen.updateAsync()
                }
            })
        }
        for ((name, maximum) in cityView.getMaxSpecialists().asSequence().sortedBy { it.key }) {
            val specialist = cityView.getRuleset().specialists[name] ?: continue
            val assigned = cityView.getNewSpecialists()[name]
            val stats = cityView.getStatsOfSpecialist(name).joinToString("  ") { "${it.value.toInt()}${it.key.character}" }
            val greatPeople = specialist.greatPersonPoints.asSequence().sortedBy { it.key }
                .joinToString("  ") { "${it.value} ${it.key.tr(hideIcons = true)}" }
            val card = Table().apply { background = portraitBackground(Color(1f, 1f, 1f, .06f)); pad(10f) }
            card.add(ImageGetter.getSpecialistIcon(specialist.colorObject)).size(36f).padRight(10f)
            card.add("{${name}}  $assigned/$maximum".toLabel().apply { wrap = true }).width(279f).left().row()
            card.add(listOf(greatPeople, stats).filter { it.isNotEmpty() }.joinToString("  ").toLabel().apply { wrap = true })
                .colspan(2).width(325f).left().padTop(6f)
            portraitRow(card, 70f)
            if (cityScreen.canChangeState) {
                val actions = Table()
                actions.add(portraitButton("−", assigned > 0 && !cityView.isPuppet()) {
                    cityView.tryUnassignSpecialist(name)
                    cityScreen.updateAsync()
                }).width(168f).height(48f).padRight(9f)
                actions.add(portraitButton("+", assigned < maximum && !cityView.isPuppet() &&
                    cityView.getFreePopulation() > 0) {
                    cityView.tryAssignSpecialist(name)
                    cityScreen.updateAsync()
                }).width(168f).height(48f)
                portraitRow(actions)
            }
        }
    }

    private fun addPortraitReligion() {
        portraitTitle("Religion")
        val majority = cityView.getMajorityReligion()
        portraitTextRow("Majority Religion: [${majority?.getReligionDisplayName() ?: "None"}]")
        val holy = cityView.getReligionThisIsTheHolyCityOf()
        if (holy != null) {
            val label = cityView.getReligion(holy)?.getReligionDisplayName() ?: holy
            portraitTextRow("${if (cityView.isBlockedHolyCity()) "Former Holy City of" else "Holy City of"}: [$label]") {
                openPortraitReligion(holy)
            }
        }
        val pressures = cityView.getPressuresFromSurroundingCities()
        for ((religionName, count) in cityView.getNumberOfFollowers().asSequence().sortedByDescending { it.value }) {
            val religion = cityView.getReligion(religionName) ?: continue
            val iconName = religion.getIconName()
            val pressure = if (pressures.containsKey(religionName))
                " · +${pressures[religionName]} pressure" else ""
            val row = Table().apply { background = portraitBackground(Color(1f, 1f, 1f, .06f)); pad(0f, 12f, 0f, 12f) }
            row.add(ImageGetter.getReligionPortrait(iconName, 30f)).size(30f).padRight(10f)
            row.add("${religion.getReligionDisplayName()}: $count followers$pressure".toLabel().apply { wrap = true })
                .width(281f).left()
            row.touchable = Touchable.enabled
            row.onClick { openPortraitReligion(religionName) }
            portraitRow(row)
        }
    }

    private fun openPortraitReligion(name: String) {
        val iconName = cityView.getReligion(name)?.getIconName() ?: return
        if (name == iconName)
            GUI.getWorldScreen().openEmpireOverview(EmpireOverviewCategories.Religion, name)
        else GUI.openCivilopedia("Belief/$name")
    }

    private fun addPortraitBuildings() {
        val buildings = cityView.getBuiltBuildings().sortedBy { it.name }
        val groups = listOf(
            "Specialist Buildings" to buildings.filter { !it.newSpecialists().isEmpty() && !it.isAnyWonder() },
            "Wonders" to buildings.filter { it.isAnyWonder() },
            "Other" to buildings.filter { !it.isAnyWonder() && it.newSpecialists().isEmpty() }
        )
        portraitTitle("Buildings")
        for ((heading, entries) in groups) {
            if (entries.none()) continue
            portraitTitle(heading)
            for (building in entries) {
                val free = cityScreen.hasFreeBuilding(building)
                val title = if (free) "{${building.name}} ({Free})" else building.name
                val yields = cityView.getBuildingStats(building).joinToString("  ") {
                    "${it.value.toInt()}${it.key.character}"
                }
                val specialists = building.newSpecialists().asSequence().joinToString("  ") {
                    "${it.value} ${it.key.tr(hideIcons = true)} ${if (it.value == 1) "slot" else "slots"}"
                }
                val row = Table().apply { background = portraitBackground(Color(1f, 1f, 1f, .06f)); pad(8f, 10f, 8f, 10f) }
                row.add(ImageGetter.getConstructionPortrait(building.name, 50f)).size(50f).padRight(10f)
                val info = Table()
                info.add(title.toLabel(hideIcons = true).apply { wrap = true }).width(265f).left().row()
                if (yields.isNotEmpty()) info.add(yields.toLabel().apply { wrap = true }).width(265f).left().row()
                if (specialists.isNotEmpty()) info.add(specialists.toLabel().apply { wrap = true }).width(265f).left().row()
                row.add(info).width(265f).left()
                row.touchable = Touchable.enabled
                row.onClick {
                    if (onPortraitBuildingSelected != null) onPortraitBuildingSelected.invoke(building)
                    else {
                        cityScreen.selectConstruction(building)
                        cityScreen.updateAsync()
                    }
                }
                portraitRow(row, 66f)
            }
        }
    }

    private fun onContentResize() {
        val previousTop = top
        val previousCenterX = x + width / 2f
        pack()
        if (cityScreen.isPortrait()) {
            setPosition(previousCenterX, previousTop, Align.top)
            return
        }
        setPosition(
            stage.width - CityScreen.posFromEdge,
            stage.height - CityScreen.posFromEdge,
            Align.topRight
        )
    }

    private fun addText() {
        val unassignedPopString = "{Unassigned population}: ".tr() +
                cityView.getFreePopulation().tr() + "/" + cityView.getPopulationCount().tr()
        val unassignedPopLabel = unassignedPopString.toLabel()
        if (cityScreen.canChangeState)
            unassignedPopLabel.onClick { 
                cityView.tryReassignPopulation()
                cityScreen.updateAsync()
            }

        var turnsToExpansionString =
                if (cityView.getCurrentCityStats().culture > 0 && cityView.hasChoosableTiles()) {
                    val remainingCulture = cityView.getCultureToNextTile() - cityView.getCultureStored()
                    var turnsToExpansion = ceil(remainingCulture / cityView.getCurrentCityStats().culture).toInt()
                    if (turnsToExpansion < 1) turnsToExpansion = 1
                    "[$turnsToExpansion] turns to expansion".tr()
                } else "Stopped expansion".tr()
        if (cityView.hasChoosableTiles())
            turnsToExpansionString +=
                    " (${cityView.getCultureStored()}${Fonts.culture}/${cityView.getCultureToNextTile()}${Fonts.culture})"

        var turnsToPopString =
                when {
                    cityView.isStarving() -> "[${cityView.getNumTurnsToStarvation()}] turns to lose population"
                    cityView.getRuleset().units[cityView.currentConstructionName()]
                        .let { it != null && it.hasUnique(UniqueType.ConvertFoodToProductionWhenConstructed) }
                    -> "Food converts to production"
                    cityView.isGrowing() -> "[${cityView.getNumTurnsToNewPopulation()}] turns to new population"
                    else -> "Stopped population growth"
                }.tr()
        turnsToPopString += " (${cityView.getFoodStored()}${Fonts.food}/${cityView.getFoodToNextPopulation()}${Fonts.food})"

        lowerTable.add(unassignedPopLabel).row()
        lowerTable.add(turnsToExpansionString.toLabel()).row()
        lowerTable.add(turnsToPopString.toLabel()).row()

        val tableWithIcons = Table() // Each row has a SINGLE actor
        tableWithIcons.defaults().pad(2f)
        if (cityView.isInResistance()) {
            tableWithIcons.add(Table().apply {
                add(ImageGetter.getImage("StatIcons/Resistance")).size(20f).padRight(2f)
                add("In resistance for another [${cityView.getFlag(CityFlags.Resistance)}] turns".toLabel())
            }).row()
        }

        val resourceTable = Table()
        val resourceCounter = Counter<TileResource>()

        // Supply
        for (resourceSupply in cityView.getCityResourcesAvailableToCity())
            if (resourceSupply.resource.getMatchingUniques(UniqueType.NotShownOnWorldScreen, cityView.getState()).none())
                resourceCounter.add(resourceSupply.resource, resourceSupply.amount)

        // Stockpiles
        for ((resourceName, amount) in cityView.getResourceStockpiles()) {
            val resourceObj = cityView.getRuleset().tileResources[resourceName] ?: continue
            if (resourceObj.getMatchingUniques(UniqueType.NotShownOnWorldScreen, cityView.getState()).none())
                resourceCounter.add(resourceObj, amount)
        }

        for ((resource, amount) in resourceCounter) {
            if (resource.isCityWide) {
                val resourceIcon = Table()
                resourceIcon.addTooltip(resource.name, targetAlign = Align.bottom)
                resourceIcon.onClick { cityScreen.openCivilopedia(resource.makeLink()) }
                resourceIcon.add(ImageGetter.getResourcePortrait(resource.name, 20f)).padRight(5f)
                resourceIcon.add(amount.toLabel())
                resourceTable.add(resourceIcon).apply {
                    // Only add right padding if it's not the last one in the list
                    if (resourceCounter.keys.last() != resource) padRight(10f)
                }
            }
        }

        if (resourceTable.cells.notEmpty())
            tableWithIcons.add(resourceTable)

        val (wltkIcon: Actor?, wltkLabel: Label?) = when {
            cityView.isWeLoveTheKingDayActive() ->
                ImageGetter.getStatIcon("Food") to
                "We Love The King Day for another [${cityView.getFlag(CityFlags.WeLoveTheKing)}] turns".toLabel(Color.LIME)
            cityView.demandedResource.isNotEmpty() ->
                ImageGetter.getResourcePortrait(cityView.demandedResource, 20f) to
                "Demanding [${cityView.demandedResource}]".toLabel(Color.CORAL, hideIcons = true)
            else -> null to null
        }
        if (wltkLabel != null) {
            tableWithIcons.add(Table().apply {
                add(wltkIcon!!).size(20f).padRight(5f)
                add(wltkLabel).row()
            })
            wltkLabel.onClick {
                cityScreen.openCivilopedia("Tutorial/We Love The King Day")
            }
        }

        lowerTable.add(tableWithIcons).row()
    }

    private fun addCitizenManagement() {
        val expanderTab = CitizenManagementTable(cityScreen).asExpander { onContentResize() }
        lowerTable.add(expanderTab).growX().row()
    }

    private fun addSpecialistInfo() {
        val expanderTab = SpecialistAllocationTable(cityScreen).asExpander { onContentResize() }
        lowerTable.add(expanderTab).growX().row()
    }

    private fun addReligionInfo() {
        val expanderTab = CityReligionInfoTable(cityView).asExpander { onContentResize() }
        lowerTable.add(expanderTab).growX().row()
    }

    private fun addBuildingsInfo() {
        val wonders = mutableListOf<Building>()
        val specialistBuildings = mutableListOf<Building>()
        val otherBuildings = mutableListOf<Building>()

        for (building in cityView.getBuiltBuildings()) {
            when {
                building.isAnyWonder() -> wonders.add(building)
                !building.newSpecialists().isEmpty() -> specialistBuildings.add(building)
                else -> otherBuildings.add(building)
            }
        }

        // Buildings sorted alphabetically
        wonders.sortBy { it.name }
        specialistBuildings.sortBy { it.name }
        otherBuildings.sortBy { it.name }

        val totalTable = Table()
        lowerTable.addCategory("Buildings", totalTable, KeyboardBinding.BuildingsDetail, false)

        if (specialistBuildings.isNotEmpty()) {
            val specialistBuildingsTable = Table()
            totalTable.add().row()
            totalTable.addSeparator(color = Color.LIGHT_GRAY)
            totalTable.add("Specialist Buildings".toLabel().apply { setAlignment(Align.center) }).growX()
            totalTable.addSeparator(color = Color.LIGHT_GRAY)
            for (building in specialistBuildings) addBuildingButton(building, specialistBuildingsTable)
            totalTable.add(specialistBuildingsTable).growX().right().row()
        }

        if (wonders.isNotEmpty()) {
            val wondersTable = Table()
            totalTable.addSeparator(color = Color.LIGHT_GRAY)
            totalTable.add("Wonders".toLabel().apply { setAlignment(Align.center) }).growX()
            totalTable.addSeparator(color = Color.LIGHT_GRAY)
            for (building in wonders) addBuildingButton(building, wondersTable)
            totalTable.add(wondersTable).growX().right().row()
        }

        if (otherBuildings.isNotEmpty()) {
            val regularBuildingsTable = Table()
            totalTable.addSeparator(color = Color.LIGHT_GRAY)
            totalTable.add("Other".toLabel().apply { setAlignment(Align.center) }).growX()
            totalTable.addSeparator(color = Color.LIGHT_GRAY)
            for (building in otherBuildings) addBuildingButton(building, regularBuildingsTable)
            totalTable.add(regularBuildingsTable).growX().right().row()
        }
    }

    private fun addBuildingButton(building: Building, destinationTable: Table) {

        val button = Table()

        val info = Table()
        val statsAndSpecialists = Table()

        val icon = ImageGetter.getConstructionPortrait(building.name, 50f)
        val isFree = cityScreen.hasFreeBuilding(building)
        val displayName = if (isFree) "{${building.name}} ({Free})" else building.name

        info.add(displayName.toLabel(fontSize = Constants.defaultFontSize, hideIcons = true)).padBottom(5f).right().row()

        val stats = cityView.getBuildingStats(building).joinToString(separator = " ") {
            "" + it.value.toInt() + it.key.character
        }
        statsAndSpecialists.add(stats.toLabel(fontSize = Constants.defaultFontSize)).right()

        if (building.newSpecialists().any()) {
            val assignedSpec = cityView.getNewSpecialists().clone()
            val specialistIcons = Table()
            for ((specialistName, amount) in building.newSpecialists()) {
                val specialist = cityView.getRuleset().specialists[specialistName]
                    ?: continue // probably a mod that doesn't have the specialist defined yet
                repeat(amount) {
                    if (assignedSpec[specialistName] > 0) {
                        specialistIcons.add(ImageGetter.getSpecialistIcon(specialist.colorObject))
                            .size(20f)
                        assignedSpec.add(specialistName, -1)
                    } else {
                        specialistIcons.add(ImageGetter.getSpecialistIcon(Color.GRAY)).size(20f)
                    }
                }
            }
            statsAndSpecialists.add(specialistIcons).right()
        }

        info.add(statsAndSpecialists).right()

        button.add(info).right().top().padRight(10f).padTop(5f)
        button.add(icon).right()

        button.onClick {
            cityScreen.selectConstruction(building)
            cityScreen.updateAsync()
        }

        destinationTable.add(button).pad(1f).padBottom(2f).padTop(2f).expandX().right().row()
    }

    private fun Table.addCategory(
        category: String,
        showHideTable: Table,
        toggleKey: KeyboardBinding,
        startsOpened: Boolean = true
    ) : ExpanderTab {
        val expanderTab = ExpanderTab(
            title = category,
            fontSize = Constants.defaultFontSize,
            persistenceID = "CityInfo.$category",
            startsOutOpened = startsOpened,
            toggleKey = toggleKey,
            onChange = { onContentResize() }
        ) {
            it.add(showHideTable).fillX().right()
        }
        add(expanderTab).growX().row()
        return expanderTab
    }

    private fun addGreatPersonPointInfo() {

        val greatPeopleTable = Table()

        val gppBreakdown = cityView.getGreatPersonPointsBreakdown()
        if (gppBreakdown.allNames.isEmpty())
            return
        val greatPersonPoints = gppBreakdown.sum()

        // Iterating over allNames instead of greatPersonPoints will include those where the aggregation had points but ended up zero
        for (greatPersonName in gppBreakdown.allNames) {
            val gppPerTurn = greatPersonPoints[greatPersonName]

            val info = Table()

            val greatPerson = cityView.getRuleset().units[greatPersonName] ?: continue
            info.add(ImageGetter.getUnitIcon(greatPerson, Color.GOLD).toGroup(20f))
                .left().padBottom(4f).padRight(5f)
            info.add("{$greatPersonName} (+$gppPerTurn)".toLabel(hideIcons = true)).left().padBottom(4f).expandX().row()

            val gppCurrent = cityView.viewingCiv().getGreatPersonPoints(greatPersonName)
            val gppNeeded = cityView.viewingCiv().getPointsRequiredForGreatPerson(greatPersonName)

            val percent = gppCurrent / gppNeeded.toFloat()

            val progressBar = ImageGetter.ProgressBar(300f, 25f, false)
            progressBar.setBackground(ImageGetter.CHARCOAL.cpy().apply { a = 0.8f })
            progressBar.setProgress(Color.ORANGE, percent)
            progressBar.apply {
                val bar = ImageGetter.getWhiteDot()
                bar.color = Color.GRAY
                bar.setSize(width+5f, height+5f)
                bar.center(this)
                addActor(bar)
                bar.toBack()
            }
            progressBar.setLabel(Color.WHITE, "$gppCurrent/$gppNeeded", fontSize = 14)

            info.add(progressBar).colspan(2).left().expandX().row()
            info.onClick {
                GreatPersonPointsBreakdownPopup(cityScreen, gppBreakdown, greatPersonName)
            }
            greatPeopleTable.add(info).growX().top().padBottom(10f)
            val icon = ImageGetter.getConstructionPortrait(greatPersonName, 50f)
            icon.onClick {
                GreatPersonPointsBreakdownPopup(cityScreen, gppBreakdown, null)
            }
            greatPeopleTable.add(icon).row()
        }

        lowerTable.addCategory("Great People", greatPeopleTable, KeyboardBinding.GreatPeopleDetail)
    }

    private inner class MiniStatsTable(wasOpen: Boolean?) : Table() {
        // Challenge: we want this measured before instantating the ExpanderTab.
        // Ergo: update() must not access expander until _after_ init
        init {
            update(wasOpen)
            pack()
        }

        fun update() = update(expander.isOpen)
        private fun update(expanderIsOpen: Boolean?) {
            clear()
            val selected = BaseScreen.skin.getColor("selection")
            for (stat in Stat.entries) {
                if (stat == Stat.Faith && !cityView.viewingCiv().isReligionEnabled()) continue
                val amount = cityView.getCurrentCityStats()[stat]
                val icon = Table()
                val focus = CityFocus.safeValueOf(stat)
                val toggledFocus = if (focus == cityView.getCityFocus()) {
                    icon.add(ImageGetter.getStatIcon(stat.name).surroundWithCircle(27f, false, color = selected))
                    CityFocus.NoFocus
                } else {
                    icon.add(ImageGetter.getStatIcon(stat.name).surroundWithCircle(27f, false, color = Color.CLEAR))
                    focus
                }
                if (cityScreen.canCityBeChanged()) {
                    icon.onActivation(binding = toggledFocus.binding) {
                        cityView.trySetCityFocus(toggledFocus)
                        cityScreen.updateAsync()
                    }
                }
                add(icon).size(27f).padRight(3f)
                val valueToDisplay = if (stat == Stat.Happiness) cityView.getHappinessList().values.sum() else amount
                add((valueToDisplay.roundToInt()).toLabel()).padRight(5f)
                if (cityScreen.isCrampedPortrait() && (expanderIsOpen == null || !expanderIsOpen) && stat == Stat.Gold) {
                    row()
                }
            }
        }
    }
}
