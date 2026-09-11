package com.unciv.logic.achievements

import com.unciv.json.json
import com.unciv.models.metadata.BaseRuleset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(org.junit.runners.Parameterized::class)
@org.junit.runners.Parameterized.UseParametersRunnerFactory(com.unciv.testing.TestRunnerFactory::class)
class AchievementCorrectionsTest(private val base: BaseRuleset) {
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters() = BaseRuleset.entries.map { arrayOf(it) }
    }

    @Test fun uniqueBuildingsMeetTheSamePopulationAndTreasuryConditions() {
        for (nation in listOf("China", "Siam")) {
            val f = AchievementTestFixture(nation, base)
            f.capital.population.setPopulation(15)
            for (name in listOf("Library", "University"))
                f.capital.cityConstructions.addBuilding(f.player.getEquivalentBuilding(name))
            assertTrue(nation, "N11" in f.results(end = true))
            f.capital.population.setPopulation(14)
            assertFalse(nation, "N11" in f.results(end = true))
        }
        for (nation in listOf("Arabia", "Persia")) {
            val f = AchievementTestFixture(nation, base)
            val cities = listOf(f.capital, f.city(-4, 0), f.city(0, 0))
            f.player.addGold(1000 - f.player.gold)
            for (city in cities) for (name in listOf("Market", "Bank"))
                city.cityConstructions.addBuilding(f.player.getEquivalentBuilding(name))
            assertTrue(nation, "N12" in f.results(end = true))
            f.player.addGold(-1)
            assertFalse(nation, "N12" in f.results(end = true))
        }
    }

    @Test fun trainingExperienceAndFreePromotionsNeverBecomeCombatPromotions() {
        val f = AchievementTestFixture(baseRuleset = base)
        for (name in listOf("Barracks", "Armory", "Military Academy", "Brandenburg Gate"))
            f.capital.cityConstructions.addBuilding(name)
        val unit = f.unit("Swordsman", 0, 0)
        unit.baseUnit.addConstructionBonuses(unit, f.capital.cityConstructions)
        assertTrue(unit.promotions.XP >= 60)
        unit.promotions.addPromotion("Drill I", isFree = true)
        for (name in listOf("Shock I", "Shock II", "Shock III")) {
            assertTrue(unit.promotions.canBePromoted())
            unit.promotions.addPromotion(name)
        }
        assertEquals(3, f.history.unit(unit.id).earnedPromotions)
        assertEquals(0, f.history.unit(unit.id).combatPromotions)
        assertEquals(0, f.history.unit(unit.id).combatExperience)
        val city = f.city(2, 0, civ = f.opponents[0])
        AchievementTracker.cityBattleWon(unit, city)
        city.puppetCity(f.player)
        assertFalse("N08" in f.results())
    }

    @Test fun onlyPromotionsFullyFundedByRecordedCombatExperienceCount() {
        val f = AchievementTestFixture(baseRuleset = base)
        val unit = f.unit("Warrior", 0, 0)
        unit.promotions.XP = 60 // Non-combat XP, as for a trained unit.
        f.kill(unit, 1, 0)
        assertEquals(5, f.history.unit(unit.id).combatExperience)
        unit.promotions.addPromotion("Shock I") // 5 combat + 5 training is insufficient.
        assertEquals(0, f.history.unit(unit.id).combatPromotions)
        assertEquals(0, f.history.unit(unit.id).combatExperience)
        f.earnCombatExperience(unit, 20)
        unit.promotions.addPromotion("Shock II")
        assertEquals(1, f.history.unit(unit.id).combatPromotions)
        assertEquals(0, f.history.unit(unit.id).combatExperience)
        f.earnCombatExperience(unit, 30)
        val before = f.history.unit(unit.id).combatExperience
        unit.promotions.addPromotion("Drill I", isFree = true)
        assertEquals(before, f.history.unit(unit.id).combatExperience)
        assertEquals(1, f.history.unit(unit.id).combatPromotions)
        unit.promotions.addPromotion("Shock III")
        assertEquals(2, f.history.unit(unit.id).combatPromotions)
    }

    @Test fun oldExperienceIsNotInferredButNewCombatRecordsRoundTripAndClone() {
        val old = json().fromJson(AchievementUnitHistory::class.java,
            "{\"earnedPromotions\":5,\"majorMilitaryKills\":10}")
        assertEquals(0, old.combatExperience)
        assertEquals(0, old.combatPromotions)
        val f = AchievementTestFixture(baseRuleset = base)
        val unit = f.unit("Warrior", 0, 0)
        f.history.units[unit.id.toString()] = old
        assertFalse("N31" in f.results())
        f.earnCombatExperience(unit, 30)
        unit.promotions.addPromotion("Shock I")
        val restored = json().fromJson(AchievementGameState::class.java, json().toJson(f.state))
        assertEquals(20, restored.history.unit(unit.id).combatExperience)
        assertEquals(1, restored.history.unit(unit.id).combatPromotions)
        val cloned = restored.clone()
        cloned.history.unit(unit.id).combatPromotions = 5
        assertEquals(1, restored.history.unit(unit.id).combatPromotions)
        f.history.completed.add("N31") // Existing earned medals are not revoked on migration.
        assertTrue("N31" in f.results())
    }

    @Test fun chuKoNuMustAlreadyHaveFourCombatPromotionsBeforeBothKills() {
        val f = AchievementTestFixture("China", base)
        val unit = f.unit("Chu-Ko-Nu", 0, 0)
        f.earnCombatExperience(unit, 100)
        for (name in listOf("Accuracy I", "Accuracy II", "Accuracy III")) unit.promotions.addPromotion(name)
        f.nextTurn()
        f.kill(unit, 1, 0)
        f.kill(unit, 0, 1)
        unit.promotions.addPromotion("Barrage I")
        assertEquals(4, f.history.unit(unit.id).combatPromotions)
        assertTrue(f.history.unit(unit.id).chuKoNuKillsThisTurn.isEmpty())
        assertFalse("N34" in f.results(end = true))
        f.nextTurn()
        f.kill(unit, 1, 0)
        assertFalse("N34" in f.results(end = true))
        f.kill(unit, 0, 1)
        assertTrue("N34" in f.results(end = true))
    }

    @Test fun aRecaptureInANewTurnCountsOnceWithinThatTurn() {
        val f = AchievementTestFixture(baseRuleset = base)
        val unit = f.unit("Warrior", 0, 0)
        val cities = listOf(f.city(2, 0, civ = f.opponents[0]), f.city(4, 0, civ = f.opponents[0]), f.city(6, 0, civ = f.opponents[1]))
        fun capture(city: com.unciv.logic.city.City) {
            AchievementTracker.cityBattleWon(unit, city)
            city.puppetCity(f.player)
        }
        capture(cities[0])
        cities[0].moveToCiv(f.opponents[0])
        f.nextTurn()
        capture(cities[0])
        cities[0].moveToCiv(f.opponents[0])
        capture(cities[0])
        assertEquals(1, f.history.capturedThisTurn.size)
        capture(cities[1])
        assertFalse("N32" in f.results())
        capture(cities[2])
        assertEquals(3, f.history.capturedThisTurn.size)
        assertTrue("N32" in f.results())
    }

    @Test fun aPromotionAfterWinningTheBattleCannotQualifyAPendingConquest() {
        val f = AchievementTestFixture(baseRuleset = base)
        val unit = f.unit("Warrior", 0, 0)
        f.earnCombatExperience(unit, 60)
        unit.promotions.addPromotion("Shock I")
        unit.promotions.addPromotion("Shock II")
        val city = f.city(2, 0, civ = f.opponents[0])
        AchievementTracker.cityBattleWon(unit, city)
        unit.promotions.addPromotion("Shock III")
        city.puppetCity(f.player)
        assertFalse("N08" in f.results())
        city.moveToCiv(f.opponents[0])
        AchievementTracker.cityBattleWon(unit, city)
        city.puppetCity(f.player)
        assertTrue("N08" in f.results())
    }

    @Test fun aNewGoldenAgeCountsRecapturedCitiesWithoutErasingConquestHistory() {
        val f = AchievementTestFixture("Persia", base)
        val unit = f.unit("Warrior", 0, 0)
        val cities = listOf(f.city(2, 0, civ = f.opponents[0]), f.city(4, 0, civ = f.opponents[0]), f.city(6, 0, civ = f.opponents[1]), f.city(8, 0, civ = f.opponents[1]))
        f.player.goldenAges.enterGoldenAge()
        AchievementTracker.cityBattleWon(unit, cities[0]); cities[0].puppetCity(f.player)
        cities[0].moveToCiv(f.opponents[0])
        while (f.player.goldenAges.isGoldenAge()) f.player.goldenAges.endTurn(0)
        assertEquals(1, f.history.foreignCaptures.size)
        f.player.goldenAges.enterGoldenAge()
        for (city in cities) { AchievementTracker.cityBattleWon(unit, city); city.puppetCity(f.player) }
        assertEquals(4, f.history.goldenAgeCaptures.size)
        assertEquals(2, f.history.goldenAgeCapturedCivilizations.size)
        assertTrue("N35" in f.results())
        assertEquals(4, f.history.foreignCaptures.size)
    }
}
