package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.math.Interpolation
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.PortraitDialog
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.portraitCanvasBounds
import com.unciv.ui.screens.cityscreen.CityScreen
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import yairm210.purity.annotations.Readonly
import java.util.EnumSet
import kotlin.text.ifEmpty

/**
 * [Popup] communicating events other than trade offers to the player.
 * (e.g. First Contact, Wonder built, Tech researched,...)
 *
 * **Opens itself at the end of instantiation!**
 *
 * (In rare cases, it chooses not to: Mods making a RecapturedCivilian not find the unit as it was illegal and removed after the actual capture)
 *
 * Called in [WorldScreen].update, which pulls them from viewingCiv.popupAlerts.
 *
 * @param worldScreen The parent screen
 * @param popupAlert The [PopupAlert] entry to present
 *
 * @see AlertType
 *
 * Attention developers: This is a Popup with `Scrollability.WithoutButtons`, and that means the
 * content area has two parts - one scrolls and the bottom not. Use Popup's normal `add` for stuff that
 * should go to the upper scrolling part and all typical closing buttons should *only* use Popup's
 * add*Button methods - for a good exception see `addCityConquered`.
 * That also means colspan is independent for top and bottom, and you need no row() between them.
 */
class AlertPopup(
    private val worldScreen: WorldScreen,
    private val popupAlert: PopupAlert
): Popup(worldScreen) {

    private var wonderSceneTexture: Texture? = null
    private var leaderSceneTexture: Texture? = null
    
    companion object {
        private const val SEPARATOR_LINE_TO_TEXT_PADDING = 25f
        private val LIGHTER_RED_COLOR = Color(1f, 1/3f, 1/3f, 1f)
        private val LIGHTER_GREEN_COLOR = Color(1/3f, 1f, 1/3f, 1f)
        private val LIGHTER_ORANGE_COLOR = Color(1f, 2/5f, 0f, 1f)
    }

    //region convenience getters
    private val music get() = UncivGame.Current.musicController
    private val gameInfo get() = worldScreen.gameInfo
    private val viewingCiv get() = worldScreen.selectedGameView.civView.getCiv()
    private val stageWidth get() = worldScreen.stage.width
    private val stageHeight get() = worldScreen.stage.height
    @Readonly private fun getCiv(civName: String) = gameInfo.getCivilization(civName)
    @Readonly private fun getCity(cityId: String) = gameInfo.getCities().first { it.id == cityId }
    //endregion

    // This redirects all addCloseButton uses with only text and no action to accept the space key
    private fun addCloseButton(text: String = Constants.close) =
        addCloseButton(text, KeyboardBinding.NextTurnAlternate, null)

    init {
        var shouldOpen = true

        // This makes the buttons fill up available width. See comments in #9559.
        // To implement a middle ground, I would either simply replace growX() with minWidth(240f) or so,
        // or replace the Popup.equalizeLastTwoButtonWidths() function with something intelligent not
        // limited to two buttons.
        bottomTable.defaults().growX()

        when (popupAlert.type) {
            // Cities
            AlertType.CityConquered -> addCityConquered()
            AlertType.CityTraded -> addCityTraded()
            AlertType.DiplomaticMarriage -> addDiplomaticMarriage()
            // Demands and diplomacy
            AlertType.FirstContact -> addFirstContact()
            AlertType.WarDeclaration -> shouldOpen = addWarDeclaration()
            AlertType.BorderConflict -> shouldOpen = addBorderConflict()
            AlertType.TilesStolen -> shouldOpen = addTilesStolen()
            AlertType.Denounced -> shouldOpen = addDenouncement()
            
            // demands
            AlertType.DemandToStopSettlingCitiesNear -> shouldOpen = addDemand(Demand.DoNotSettleNearUs)
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSettleNearUs)
            AlertType.DemandToStopSpreadingReligion -> shouldOpen = addDemand(Demand.DoNotSpreadReligion)
            AlertType.ReligionSpreadDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSpreadReligion)
            AlertType.DemandToStopSpyingOnUs -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.SpyingOnUsDespiteOurPromise -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.DemandToNotAttackUs -> shouldOpen = addDemand(Demand.DoNotAttackUs)
            AlertType.AttackedUsDespitePromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotAttackUs)
            AlertType.AcceptingDemand -> shouldOpen = addAcceptingDemand()
            AlertType.RejectingDemand -> shouldOpen = addRejectingDemand()
            
            AlertType.DeclarationOfFriendship -> shouldOpen = addDeclarationOfFriendship()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> 
                shouldOpen = addBulliedOrAttackedProtectedOrAlliedMinor()
            AlertType.Defeated -> addDefeated()
            // We did stuff
            AlertType.WonderBuilt -> addWonderBuilt()
            AlertType.TechResearched -> addTechResearched()
            AlertType.GoldenAge -> addGoldenAge()
            AlertType.StartIntro -> addStartIntro()
            AlertType.RecapturedCivilian -> shouldOpen = addRecapturedCivilian()
            AlertType.GameHasBeenWon -> addGameHasBeenWon()
            AlertType.Event -> shouldOpen = addEvent()
        }
        if (shouldOpen) open()
        else viewingCiv.popupAlerts.remove(popupAlert)
    }

    //region AlertType handlers

    private fun addBorderConflict(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addGoodSizedLabel("Remove your troops in our border immediately!")
        addCloseButton("Sorry.", KeyboardBinding.Confirm)
        addCloseButton("Never!", KeyboardBinding.Cancel)
        return true
    }
    
    private fun addTilesStolen(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addGoodSizedLabel("Those lands were not yours to take. This has not gone unnoticed.")
        addCloseButton()
        return true
    }

    private fun addBulliedOrAttackedProtectedOrAlliedMinor(): Boolean {
        val involvedCivs = popupAlert.value.split('@')
        val bullyOrAttacker = getCiv(involvedCivs[0])
        if (bullyOrAttacker.isDefeated()) return false
        val cityState = getCiv(involvedCivs[1])
        val player = viewingCiv
        addLeaderName(bullyOrAttacker)

        val isAtLeastNeutral = bullyOrAttacker.getDiplomacyManager(player)!!.isRelationshipLevelGE(RelationshipLevel.Neutral)
        val text = when {
            popupAlert.type == AlertType.BulliedProtectedMinor && isAtLeastNeutral ->  // Nice message
                "I've been informed that my armies have taken tribute from [${cityState.civName}], a city-state under your protection.\nI assure you, this was quite unintentional, and I hope that this does not serve to drive us apart."
            popupAlert.type == AlertType.BulliedProtectedMinor ->  // Nasty message
                "We asked [${cityState.civName}] for a tribute recently and they gave in.\nYou promised to protect them from such things, but we both know you cannot back that up."
            isAtLeastNeutral ->  // Nice message
                "It's come to my attention that I may have attacked [${cityState.civName}].\nWhile it was not my goal to be at odds with your empire, this was deemed a necessary course of action."
            else ->  // Nasty message
                "I thought you might like to know that I've launched an invasion of one of your little pet states.\nThe lands of [${cityState.civName}] will make a fine addition to my own."
        }
        addGoodSizedLabel(text).row()
        
        if (!player.isAtWarWith(bullyOrAttacker)) {
            addCloseButton("THIS MEANS WAR!", KeyboardBinding.Confirm) {
            player.getDiplomacyManager(bullyOrAttacker)!!.sideWithCityState()
            val warReason = if (popupAlert.type == AlertType.AttackedAllyMinor) WarType.AlliedCityStateWar else WarType.ProtectedCityStateWar
            player.getDiplomacyManager(bullyOrAttacker)!!.declareWar(DeclareWarReason(warReason, cityState))
            cityState.getDiplomacyManager(player)!!.influence += 20f // You went to war for us!!
        }.row()}

        addCloseButton("You'll pay for this!", KeyboardBinding.Confirm) {
            player.getDiplomacyManager(bullyOrAttacker)!!.sideWithCityState()
        }.row()

        addCloseButton("Very well.", KeyboardBinding.Cancel) {
            player.addNotification("You have broken your Pledge to Protect [${cityState.civName}]!",
                cityState.cityStateFunctions.getNotificationActions(), NotificationCategory.Diplomacy, cityState.civName)
            cityState.cityStateFunctions.removeProtectorCiv(player, forced = true)
        }.row()
        
        return true
    }

    private fun addCityConquered() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        val conqueringCiv = gameInfo.getCurrentPlayerCivilization()

        if (city.foundingCivObject != null
                && city.civ != city.foundingCivObject // can't liberate if the city actually belongs to those guys
                && conqueringCiv != city.foundingCivObject) { // or belongs originally to us
            addLiberateOption(city, conqueringCiv)
            addSeparator()
        }

        if (conqueringCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.puppetCity(conqueringCiv)
                city.destroyCity()
            }
        } else {
            val mayAnnex = !conqueringCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
            }
            addSeparator()

            addPuppetOption(mayAnnex = mayAnnex) {
                city.puppetCity(conqueringCiv)
            }
            addSeparator()

            addRazeOption(city, mayAnnex = mayAnnex, conqueringCiv)
        }
    }

    private fun addDemandViolationNoticed(demand: Demand): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated()) return false
        addLeaderName(otherciv)
        addGoodSizedLabel(demand.violationNoticedText).row()
        addCloseButton("Very well.")
        return true
    }

    private fun addCityTraded() {
        val city = getCity(popupAlert.value)
        addQuestionAboutTheCity(city.name)
        val conqueringCiv = gameInfo.getCurrentPlayerCivilization()

        if (!conqueringCiv.isAtWarWith(city.foundingCivObject!!)) {
            addLiberateOption(city, conqueringCiv)
            addSeparator()
        }
        addCloseButton("Keep it").row()
    }

    private fun addDeclarationOfFriendship(): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated() || otherciv.getDiplomacyManager(viewingCiv)!!.diplomaticStatus == DiplomaticStatus.War) return false
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)!!
        addLeaderName(otherciv)
        addTopicHeader("DECLARATION OF FRIENDSHIP", LIGHTER_GREEN_COLOR)
        addGoodSizedLabel(
                if (otherciv.nation.declaringFriendship.isNotEmpty()) otherciv.nation.declaringFriendship else "My friend, shall we declare our friendship to the world?"
        ).row()
        addCloseButton("Declare Friendship ([30] turns)", KeyboardBinding.Confirm) {
            playerDiploManager.signDeclarationOfFriendship()
        }.row()
        addCloseButton("We are not interested.", KeyboardBinding.Cancel) {
            playerDiploManager.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
        }.row()
        val music = UncivGame.Current.musicController
        music.playVoice("${otherciv.nation.name}.declaringFriendship")
        return true
    }

    private fun addDenouncement(): Boolean {
        val denouncer = getCiv(popupAlert.value)
        if (denouncer.isDefeated())
            return false
        addLeaderName(denouncer)
        addTopicHeader("DENOUNCEMENT", LIGHTER_ORANGE_COLOR)
        // normal message unless we are enemies
        val leaderMessage = if (denouncer.getDiplomacyManager(viewingCiv)!!.isRelationshipLevelGE(RelationshipLevel.Competitor)) {
            music.playVoice("${denouncer.nation.name}.neutralDenouncing")
            denouncer.nation.neutralDenouncing.ifEmpty { "You have violated our bond of trust. This is intolerable!" }
        } else {
            music.playVoice("${denouncer.nation.name}.hateDenouncing")
            denouncer.nation.hateDenouncing.ifEmpty { "You are a scourge upon this earth. I denounce you!" }
        }
        addGoodSizedLabel(leaderMessage).row()
        val diplomacy = viewingCiv.getDiplomacyManager(denouncer)!!
        if (diplomacy.canDeclareWar()) {
            addCloseButton("THIS MEANS WAR! (Declare war)") {
                diplomacy.declareWar()
            }.row()
        }
        addCloseButton("Very well.", KeyboardBinding.Cancel).row()
        return true
    }
    
    private fun addDefeated() {
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addGoodSizedLabel(civInfo.nation.defeated).row()
        addCloseButton("Farewell.")
        music.chooseTrack(civInfo.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
        music.playVoice("${civInfo.civName}.defeated")
    }

    private fun addDemand(demand: Demand): Boolean {
        val otherciv = getCiv(popupAlert.value)
        if (otherciv.isDefeated()) return false
        
        val playerDiploManager = viewingCiv.getDiplomacyManager(otherciv)!!
        addLeaderName(otherciv)
        addGoodSizedLabel(demand.demandText).row()
        addCloseButton(demand.acceptDemandText, KeyboardBinding.Confirm) {
            playerDiploManager.agreeToDemand(demand)
        }.row()
        addCloseButton(demand.refuseDemandText, KeyboardBinding.Cancel) {
            playerDiploManager.refuseDemand(demand)
            if (demand == Demand.DoNotAttackUs)
                viewingCiv.getDiplomacyManager(otherciv)!!.declareWar()
        }
        return true
    }

    private fun addAcceptingDemand(): Boolean {
        val otherCiv = getCiv(popupAlert.value)
        if (otherCiv.isDefeated())
            return false
        addLeaderName(otherCiv)
        addTopicHeader("ACCEPTING DEMAND", Color.YELLOW)
        val leaderMessage = otherCiv.nation.acceptingDemand.ifEmpty {
            "We will comply, but our consent is given grudgingly."
        }
        addGoodSizedLabel(leaderMessage).row()
        music.playVoice("${otherCiv.civName}.acceptingDemand")
        addCloseButton("Very well.", KeyboardBinding.Cancel)
        return true
    }
    
    private fun addRejectingDemand(): Boolean {
        val otherCiv = getCiv(popupAlert.value)
        if (otherCiv.isDefeated())
            return false
        addLeaderName(otherCiv)
        addTopicHeader("REJECTING DEMAND", LIGHTER_ORANGE_COLOR)
        val theirDiplomacy = otherCiv.getDiplomacyManager(viewingCiv)!!
        val leaderMessage = if (theirDiplomacy.isRelationshipLevelGE(RelationshipLevel.Competitor)) {
            music.playVoice("${otherCiv.nation.name}.neutralRejectingDemand")
            otherCiv.nation.neutralRejectingDemand.ifEmpty {
                "Your demands are in poor taste. We shall decide this matter on our own."
            }
        } else {
            music.playVoice("${otherCiv.nation.name}.hateRejectingDemand")
            otherCiv.nation.hateRejectingDemand.ifEmpty {
                "Did you really expect us to bend to such brazen demands?"
            }
        }
        addGoodSizedLabel(leaderMessage).row()
        addCloseButton("You'll pay for this!")
        addCloseButton("Very well.", KeyboardBinding.Cancel)
        equalizeLastTwoButtonWidths()
        return true
    }

    private fun addDiplomaticMarriage() {
        val city = getCity(popupAlert.value)
        addGoodSizedLabel(city.name.tr() + ": " + "What would you like to do with the city?".tr(), Constants.headingFontSize) // Add name because there might be several cities
            .padBottom(20f).row()
        val marryingCiv = gameInfo.getCurrentPlayerCivilization()

        if (marryingCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.destroyCity(overrideSafeties = true)
            }
        } else {
            val mayAnnex = !marryingCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex) {}
            addSeparator()

            addPuppetOption(mayAnnex) {
                city.isPuppet = true
                city.cityStats.update()
            }
        }
    }

    private fun addFirstContact() {
        val civInfo = getCiv(popupAlert.value)
        val nation = civInfo.nation
        addLeaderName(civInfo)
        music.chooseTrack(civInfo.civName, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.introduction")
        if (civInfo.isCityState) {
            addGoodSizedLabel("We have encountered the City-State of [${nation.name}]!").row()
            addCloseButton("Excellent!")
        } else {
            addGoodSizedLabel(nation.introduction).row()
            addCloseButton("A pleasure to meet you.")
        }
    }

    private fun addGameHasBeenWon() {
        val victoryData = gameInfo.victoryData!!
        addGoodSizedLabel("[${victoryData.winningCivObject.civName}] has won a [${victoryData.victoryType}] Victory!").row()
        addButton("Victory status") { close(); worldScreen.game.pushScreen{ VictoryScreen(worldScreen) } }.row()
        addCloseButton()
    }

    private fun addGoldenAge() {
        addGoodSizedLabel("GOLDEN AGE")
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        addGoodSizedLabel("Your citizens have been happy with your rule for so long that the empire enters a Golden Age!").row()
        addCloseButton()
        music.chooseTrack(viewingCiv.civName, MusicMood.Golden, MusicTrackChooserFlags.setSpecific)
    }

    /** @return false to skip opening this Popup, as we're running in the initialization phase before the Popup is open */
    private fun addRecapturedCivilian(): Boolean {
        val position = HexCoord.fromString(popupAlert.value)
        val tile = gameInfo.tileMap[position]
        val capturedUnit = tile.civilianUnit  // This has got to be it
            ?: return false // the unit disappeared somehow? maybe a modded action?
        val originalOwner = capturedUnit.originalOwningCiv!!
        if (originalOwner.isDefeated()) return false
        val captor = viewingCiv

        addGoodSizedLabel("Return [${capturedUnit.name}] to [${originalOwner.civName}]?")
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        addGoodSizedLabel("The [${capturedUnit.name}] we liberated originally belonged to [${originalOwner.civName}]. They will be grateful if we return it to them.").row()

        bottomTable.defaults().pad(0f, 30f) // Small buttons, plenty of pad so we don't fat-finger it

        addCloseButton(Constants.yes, KeyboardBinding.Confirm) {
            // Return it to original owner
            val unitName = capturedUnit.baseUnit.name
            capturedUnit.destroy()
            val closestCity = originalOwner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }

            if (closestCity != null) {
                // Attempt to place the unit near their nearest city
                originalOwner.units.placeUnitNearTile(closestCity.location.toHexCoord(), unitName)
            }

            if (originalOwner.isCityState) {
                originalOwner.getDiplomacyManagerOrMeet(captor).addInfluence(45f)
            } else if (originalOwner.isMajorCiv()) {
                // No extra bonus from doing it several times
                originalOwner.getDiplomacyManagerOrMeet(captor)
                    .setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
            }
            val notificationSequence = sequence {
                yield(LocationAction(tile.position))
                if (closestCity != null)
                    yield(LocationAction(closestCity.location))
                yield(DiplomacyAction(captor))
                yield(CivilopediaAction("Tutorial/Barbarians"))
            }
            originalOwner.addNotification("Your captured [${unitName}] has been returned by [${captor.civName}]", notificationSequence, NotificationCategory.Diplomacy, NotificationIcon.Trade, unitName, captor.civName)
        }
        addCloseButton(Constants.no, KeyboardBinding.Cancel) {
            // Take it for ourselves
            BattleUnitCapture.captureOrConvertToWorker(capturedUnit, captor)
        }
        return true
    }

    private fun addStartIntro() {
        val civInfo = viewingCiv
        if (stageHeight > stageWidth) addPortraitStartIntro(civInfo)
        else {
            addLeaderName(civInfo)
            addGoodSizedLabel(civInfo.nation.startIntroPart1).row()
            addGoodSizedLabel(civInfo.nation.startIntroPart2).row()
            addCloseButton("Let's begin!")
        }

        // Since there's introduction text, play the startIntroPart1 voice hook with the nation's theme.
        val music = UncivGame.Current.musicController
        music.chooseTrack(civInfo.nation.name, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.nation.name}.startIntroPart1")
    }

    /** Leader moment like the wonder scene: portrait fills the top, the intro scrolls in a card, "Let's begin!" in thumb reach */
    private fun addPortraitStartIntro(civInfo: Civilization) {
        val nation = civInfo.nation
        background = null
        innerTable.background = null
        val canvas = worldScreen.portraitCanvasBounds()
        val logicalWidth = 393f
        val scale = stageWidth / logicalWidth
        addActorAt(0, Image(ImageGetter.getWhiteDotDrawable()).apply {
            color = nation.getOuterColor()
            touchable = Touchable.disabled
            setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
        })

        val artTop = canvas.y + canvas.height
        val artHeight = canvas.height * .62f
        val art = Gdx.files.internal("ExtraImages/Leaders/p_${nation.leaderName.substringBefore(' ')}.png")
        val leaderIcon = "LeaderIcons/${nation.leaderName}"
        val picture = when {
            nation.leaderName.isNotEmpty() && art.exists() -> {
                val texture = Texture(art)
                leaderSceneTexture = texture
                texture.setFilter(TextureFilter.Linear, TextureFilter.Linear)
                Image(TextureRegionDrawable(TextureRegion(texture))).apply {
                    setScaling(Scaling.fill)
                    setAlign(Align.top)
                    setBounds(canvas.x, artTop - artHeight, canvas.width, artHeight)
                }
            }
            nation.leaderName.isNotEmpty() && ImageGetter.imageExists(leaderIcon) ->
                ImageGetter.getImage(leaderIcon).apply { setSize(200f * scale, 200f * scale) }
            else -> ImageGetter.getNationPortrait(nation, 160f * scale)
        }
        if (picture.width < canvas.width)  // mod leaders without generated art sit centered in the art area
            picture.setPosition(canvas.x + (canvas.width - picture.width) / 2f,
                artTop - artHeight / 2f - picture.height / 2f)
        picture.touchable = Touchable.disabled
        picture.setOrigin(picture.width / 2f, picture.height / 2f)
        picture.setScale(1.12f)
        picture.addAction(Actions.scaleTo(1f, 1f, 1.8f, Interpolation.pow3Out))
        addActorAt(1, picture)

        val bottom = maxOf(canvas.y + 30f * scale, worldScreen.safeAreaBoundsInWorld().y)
        val cardWidth = logicalWidth - 20f
        val textWidth = cardWidth - 36f
        val story = Table()
        for (part in listOf(nation.startIntroPart1, nation.startIntroPart2).filter { it.isNotEmpty() })
            story.add(part.toLabel(Color.valueOf("e8f0f7"), 16).apply { wrap = true })
                .width(textWidth).left().padBottom(12f).row()
        val begin = "Let's begin!".toTextButton(PortraitDialog.buttonStyle(PortraitDialog.Kind.Primary))
        // Same keys as the landscape close button
        begin.onActivation(binding = KeyboardBinding.NextTurnAlternate) { close() }
        begin.keyShortcuts.add(KeyCharAndCode.BACK)
        val card = Table().apply {
            background = PortraitDialog.panel(Color(16f / 255f, 31f / 255f, 47f / 255f, .92f))
            pad(20f, 18f, 16f, 18f)
            // Tapping the leader opens their Civilopedia entry, as LeaderIntroTable does
            add(civInfo.getLeaderDisplayName().toLabel(Color.valueOf("ffd97a"), 30, hideIcons = true).apply {
                wrap = true
                onClick { worldScreen.openCivilopedia(nation.makeLink()) }
            }).width(textWidth).left().row()
            add(ScrollPane(story).apply {
                setOverscroll(false, false)
                setScrollingDisabled(true, false)
            }).width(textWidth).expandY().fill().top().padTop(14f).row()
            add(begin).growX().height(56f).padTop(8f)
        }
        card.isTransform = true
        card.setSize(cardWidth, (canvas.y + canvas.height * .54f - bottom) / scale)
        card.setScale(scale)
        card.setPosition((stageWidth - cardWidth * scale) / 2f, bottom)
        card.touchable = Touchable.enabled
        card.color.a = 0f
        card.moveBy(0f, -40f * scale)
        card.addAction(Actions.sequence(Actions.delay(.4f), Actions.parallel(
            Actions.fadeIn(.7f, Interpolation.pow3Out),
            Actions.moveBy(0f, 40f * scale, .7f, Interpolation.pow3Out)
        )))
        addActor(card)
    }

    private fun addTechResearched() {
        val tech = gameInfo.ruleset.technologies[popupAlert.value]!!
        addGoodSizedLabel(tech.name)
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        val centerTable = Table()
        val portrait = stageHeight > stageWidth
        // portrait stacks icon, quote and description in one column; landscape keeps three columns
        val columnWidth = if (portrait) goodTextWidth else stageWidth / 3
        if (portrait) centerTable.add(ImageGetter.getTechIconPortrait(tech.name, 100f)).pad(10f).row()
        centerTable.add(tech.quote.toLabel().apply { wrap = true }).width(columnWidth)
        if (portrait) centerTable.row()
        else centerTable.add(ImageGetter.getTechIconPortrait(tech.name, 100f)).pad(20f)
        val descriptionScroll = ScrollPane(tech.getDescription(viewingCiv).toLabel().apply { wrap = true })
        centerTable.add(descriptionScroll).width(columnWidth).maxHeight(stageHeight / if (portrait) 3 else 2).padTop(if (portrait) 10f else 0f)
        add(centerTable).row()
        addCloseButton()
        music.chooseTrack(tech.name, MusicMood.Researched, MusicTrackChooserFlags.setSpecific)
    }

    private fun addWarDeclaration(): Boolean {
        val civInfo = getCiv(popupAlert.value)
        // technically they already declared war, but if they're dead it'll be strange that they talk to us
        if (civInfo.isDefeated()) return false
        addLeaderName(civInfo)
        addTopicHeader("DECLARATION OF WAR", LIGHTER_RED_COLOR)
        val leaderMessage = civInfo.nation.declaringWar
        if (leaderMessage.isNotEmpty())
            addGoodSizedLabel(leaderMessage).row()
        addCloseButton("You'll pay for this!")
        addCloseButton("Very well.")
        equalizeLastTwoButtonWidths()
        music.chooseTrack(civInfo.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.declaringWar")
        return true
    }

    private fun addTopicHeader(text: String, color: Color) {
        addGoodSizedLabel(text, color=color, size=Constants.smallerHeadingFontSize)
            .padBottom(20f).row()
    }

    private fun addWonderBuilt() {
        val wonder = gameInfo.ruleset.buildings[popupAlert.value]!!
        val png = Gdx.files.internal("ExtraImages/WonderScenes/${wonder.name}.png")
        val scene = if (png.exists()) png else Gdx.files.internal("ExtraImages/WonderScenes/${wonder.name}.jpg")
        if (stageHeight > stageWidth) {
            addPortraitWonderBuilt(wonder, scene)
            music.chooseTrack(wonder.name, MusicMood.Wonder, MusicTrackChooserFlags.setSpecific)
            return
        }
        addGoodSizedLabel(wonder.name)
        addSeparator().padBottom(10f)
        if(ImageGetter.wonderImageExists(wonder.name)) {    // Wonder Graphic exists
            if(stageHeight * 3 > stageWidth * 4) {    // Portrait
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 1.5f)
                    .height(stageWidth / 3)
                    .row()
            }
            else {  // Landscape (or squareish)
                add(ImageGetter.getWonderImage(wonder.name))
                    .width(stageWidth / 2.5f)
                    .height(stageWidth / 5)
                    .row()
            }
        } else {    // Fallback
            add(ImageGetter.getConstructionPortrait(wonder.name, 100f)).pad(20f).row()
        }

        val centerTable = Table()
        val portrait = stageHeight > stageWidth
        val centerTableColumnWidth = if (portrait) goodTextWidth else stageWidth / if (wonder.quote.isEmpty()) 2 else 3
        if (wonder.quote.isNotEmpty()) {
            centerTable.add(wonder.quote.toLabel().apply { wrap = true })
                .width(centerTableColumnWidth)
                .pad(10f)
            if (portrait) centerTable.row() // quote above the effect, one column
        }
        centerTable.add(wonder.getShortDescription().toLabel().apply { wrap = true })
            .width(centerTableColumnWidth)
            .pad(10f)
        add(centerTable).row()
        addCloseButton()
        music.chooseTrack(wonder.name, MusicMood.Wonder, MusicTrackChooserFlags.setSpecific)
    }

    private fun addPortraitWonderBuilt(wonder: com.unciv.models.ruleset.Building, scene: com.badlogic.gdx.files.FileHandle) {
        background = null
        innerTable.background = null
        clickBehindToClose = false
        val canvas = worldScreen.portraitCanvasBounds()
        val logicalWidth = 393f
        val scale = stageWidth / logicalWidth
        if (scene.exists() || ImageGetter.wonderImageExists(wonder.name)) {
            val picture = if (scene.exists()) {
                val texture = Texture(scene)
                wonderSceneTexture = texture
                texture.setFilter(TextureFilter.Linear, TextureFilter.Linear)
                Image(TextureRegionDrawable(TextureRegion(texture)))
            } else ImageGetter.getWonderImage(wonder.name)
            picture.setScaling(Scaling.fill)
            picture.touchable = Touchable.disabled
            picture.setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
            picture.setOrigin(picture.width / 2f, picture.height / 2f)
            picture.setScale(1.22f)
            picture.addAction(Actions.scaleTo(1f, 1f, 2.6f, Interpolation.pow3Out))
            addActorAt(0, picture)
        } else {
            val backdrop = Image(ImageGetter.getWhiteDotDrawable()).apply {
                color = Color.valueOf("142c40")
                touchable = Touchable.disabled
                setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
            }
            addActorAt(0, backdrop)
            val halo = ImageGetter.getCircle().apply {
                color = Color(1f, .76f, .39f, .16f)
                touchable = Touchable.disabled
                setBounds(canvas.x + canvas.width * .05f, canvas.y + canvas.height * .35f,
                    canvas.width * .9f, canvas.width * .9f)
            }
            addActor(halo)
            val landmark = ImageGetter.getConstructionPortrait(wonder.name, 220f).apply {
                touchable = Touchable.disabled
                setPosition(canvas.x + (canvas.width - width) / 2f, canvas.y + canvas.height * .57f)
                setOrigin(width / 2f, height / 2f)
                setScale(.82f)
                addAction(Actions.scaleTo(1f, 1f, 2.6f, Interpolation.pow3Out))
            }
            addActor(landmark)
        }

        val flash = Image(ImageGetter.getWhiteDotDrawable()).apply {
            color = Color.valueOf("fff8e1")
            touchable = Touchable.disabled
            setBounds(canvas.x, canvas.y, canvas.width, canvas.height)
            addAction(Actions.alpha(0f, 1.1f))
        }
        addActorAt(1, flash)
        repeat(12) { index ->
            val sparkle = "✦".toLabel(Color.valueOf("fff6c9"), 20).apply {
                touchable = Touchable.disabled
                setPosition(canvas.x + canvas.width * ((index * 37 + 11) % 96) / 100f,
                    canvas.y + canvas.height * (1f - ((index * 53) % 58 + 8) / 100f))
                color.a = 0f
                addAction(Actions.forever(Actions.sequence(
                    Actions.delay((index * .37f) % 2.6f),
                    Actions.alpha(.9f, .55f), Actions.alpha(0f, .85f), Actions.delay(1.2f)
                )))
            }
            addActor(sparkle)
        }

        val builtCity = viewingCiv.cities.firstOrNull { it.cityConstructions.isBuilt(wonder.name) }
        val card = Table().apply {
            background = BaseScreen.skinStrings.getUiBackground("",
                BaseScreen.skinStrings.roundedEdgeRectangleShape,
                Color(16f / 255f, 31f / 255f, 47f / 255f, .92f))
            pad(20f, 18f, 16f, 18f)
            add(wonder.name.toLabel(Color.valueOf("ffd97a"), 32, hideIcons = true).apply { wrap = true })
                .width(logicalWidth - 60f).left().row()
            val builtText = if (builtCity == null) "Completed on turn ${gameInfo.turns}"
                else "Built in ${builtCity.name} on turn ${gameInfo.turns}"
            add(builtText.toLabel(Color.valueOf("b7cde0"), 14).apply { wrap = true })
                .width(logicalWidth - 60f).left().padTop(2f).row()
            val effects = Table()
            effects.add(wonder.getShortDescription().toLabel(Color.WHITE, 14).apply { wrap = true })
                .width(logicalWidth - 80f).left()
            add(effects).left().padTop(13f).row()
            add("World wonder".toLabel(Color.valueOf("ffd97a"), 13)).left().padTop(4f).row()
            if (wonder.quote.isNotEmpty()) {
                add(wonder.quote.toLabel(Color.valueOf("9db7ca"), 13).apply { wrap = true })
                    .width(logicalWidth - 60f).left().padTop(12f).row()
            }
            val continueButton = Table().apply {
                background = BaseScreen.skinStrings.getUiBackground("",
                    BaseScreen.skinStrings.roundedEdgeRectangleShape, Color.valueOf("ffc93c"))
                touchable = Touchable.enabled
                add("Continue".toLabel(Color.valueOf("322800"), 18)).center()
                onClick { close() }
            }
            add(continueButton).growX().height(56f).padTop(16f).row()
            if (builtCity != null) {
                val viewCity = Table().apply {
                    background = BaseScreen.skinStrings.getUiBackground("",
                        BaseScreen.skinStrings.roundedEdgeRectangleShape, Color.valueOf("344250"))
                    touchable = Touchable.enabled
                    add("View ${builtCity.name}".toLabel(fontSize = 16).apply { wrap = true }).growX().center()
                    onClick {
                        close()
                        worldScreen.game.pushScreen { CityScreen(worldScreen.selectedGameView.getCityView(builtCity)) }
                    }
                }
                add(viewCity).growX().height(48f).padTop(8f).row()
            }
        }
        card.isTransform = true
        card.pack()
        card.setScale(scale)
        val safeBottom = worldScreen.safeAreaBoundsInWorld().y
        card.setPosition((stageWidth - card.width * scale) / 2f,
            maxOf(canvas.y + 30f * scale, safeBottom))
        card.touchable = Touchable.enabled
        card.color.a = 0f
        card.moveBy(0f, -60f)
        card.addAction(Actions.sequence(Actions.delay(.9f), Actions.parallel(
            Actions.fadeIn(.7f, Interpolation.pow3Out),
            Actions.moveBy(0f, 60f, .7f, Interpolation.pow3Out)
        )))
        addActor(card)
    }

    //endregion
    //region Helpers

    private fun addLeaderName(civInfo: Civilization) {
        add(LeaderIntroTable(civInfo))
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
    }

    private fun addQuestionAboutTheCity(cityName: String) {
        addGoodSizedLabel("What would you like to do with the city of [$cityName]?",
            Constants.headingFontSize, hideIcons = true).padBottom(20f).row()
    }

    private fun addDestroyOption(destroyAction: () -> Unit) {
        val button = "Destroy".toTextButton()
        button.onActivation {
            destroyAction()
            close()
        }
        button.keyShortcuts.add('d')
        add(button).row()
        addGoodSizedLabel("Destroying the city instantly razes the city to the ground.").row()
    }

    private fun addAnnexOption(city: City, mayAnnex: Boolean, annexAction: () -> Unit) {
        val button = "Annex".toTextButton()
        button.apply {
            if (!mayAnnex) disable() else {
                button.onActivation {
                    annexAction()
                    city.annexCity()
                    close()
                }
                button.keyShortcuts.add('a')
            }
        }
        add(button).row()
        if (mayAnnex) {
            addGoodSizedLabel("Annexed cities become part of your regular empire.").row()
            addGoodSizedLabel("Their citizens generate 2x the unhappiness, unless you build a courthouse.").row()
        } else {
            addGoodSizedLabel("Your civilization may not annex this city.").row()
        }

    }

    private fun addPuppetOption(mayAnnex: Boolean, puppetAction: () -> Unit) {
        val button = "Puppet".toTextButton()
        button.onActivation {
            puppetAction()
            close()
        }
        button.keyShortcuts.add('p')
        add(button).row()
        addGoodSizedLabel("Puppeted cities do not increase your tech or policy cost.").row()
        addGoodSizedLabel("You have no control over the the production of puppeted cities.").row()
        addGoodSizedLabel("Puppeted cities also generate 25% less Science and Culture.").row()
        if (mayAnnex) addGoodSizedLabel("A puppeted city can be annexed at any time.").row()
    }

    private fun addLiberateOption(city: City, conqueringCiv: Civilization) {
        val button = "Liberate (city returns to [originalOwner])".fillPlaceholders(city.foundingCivObject!!.civName).toTextButton()
        button.onActivation {
            city.liberateCity(conqueringCiv)
            close()
        }
        button.keyShortcuts.add('l')
        add(button).row()
        addGoodSizedLabel("Liberating a city returns it to its original owner, giving you a massive relationship boost with them!")
    }

    private fun addRazeOption(city: City, mayAnnex: Boolean, conqueringCiv: Civilization) {
        val canRaze = city.canBeDestroyed(justCaptured = true)
        val button = "Raze".toTextButton()
        button.apply {
            if (!canRaze) disable()
            else {
                onActivation {
                    city.puppetCity(conqueringCiv)
                    if (mayAnnex) { city.annexCity() }
                    city.isBeingRazed = true
                    close()
                }
                keyShortcuts.add('r')
            }
        }
        add(button).row()
        if (canRaze) {
            if (mayAnnex) {
                addGoodSizedLabel("Razing the city annexes it, and starts burning the city to the ground.").row()
            } else {
                addGoodSizedLabel("Razing the city puppets it, and starts burning the city to the ground.").row()
            }
            addGoodSizedLabel("The population will gradually dwindle until the city is destroyed.").row()
        } else {
            addGoodSizedLabel("Original capitals and holy cities cannot be razed.").row()
        }
    }

    /** Returns if event was triggered correctly */
    private fun addEvent(): Boolean {
        // The event string is in the format "eventName" + (Constants.stringSplitCharacter + "unitId=1234")?
        // We explicitly specify that this is a unitId, to enable us to add other context info in the future - for example city id
        val splitString = popupAlert.value.split(Constants.stringSplitCharacter)
        val eventName = splitString[0]
        var unit: MapUnit? = null
        for (i in 1 until splitString.size) {
            if (splitString[i].startsWith("unitId=")){
                val unitId = splitString[i].substringAfter("unitId=").toInt()
                unit = viewingCiv.units.getUnitById(unitId)
            }
        }
        
        
        val event = gameInfo.ruleset.events[eventName] ?: return false
        val render = RenderEvent(event, worldScreen, unit) { close() }
        if (!render.isValid) return false
        add(render).pad(0f).row()
        getScrollPane()?.fadeScrollBars = false
        return true
    }

    //endregion

    override fun close() {
        viewingCiv.popupAlerts.remove(popupAlert)
        worldScreen.shouldUpdate = true
        super.close()
        wonderSceneTexture?.dispose()
        wonderSceneTexture = null
        leaderSceneTexture?.dispose()
        leaderSceneTexture = null
    }
}
