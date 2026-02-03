package forge.ai.simulation;

import forge.ai.AiDeckStatistics;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CreatureEvaluator;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostSacrifice;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static java.lang.Math.max;
import static java.lang.Math.min;

public class GameStateEvaluator {
    private boolean debugging = false;
    private SimulationCreatureEvaluator eval = new SimulationCreatureEvaluator();

    // Combo state bonus from profile (cached)
    private int comboStateBonus = 0;

    public void setDebugging(boolean debugging) {
        this.debugging = debugging;
    }

    /**
     * Sets the combo state bonus from the player's AI profile.
     * @param player the AI player
     */
    public void setComboStateBonusFromProfile(Player player) {
        if (player != null) {
            this.comboStateBonus = AiProfileUtil.getIntProperty(player, AiProps.COMBO_STATE_BONUS);
        }
    }

    /**
     * Evaluates the game state for potential combo conditions.
     * Returns a bonus score if the AI is in a favorable combo position.
     *
     * @param game the game state
     * @param aiPlayer the AI player
     * @return bonus score for combo-ready states
     */
    public int evaluateComboState(Game game, Player aiPlayer) {
        if (comboStateBonus == 0) {
            return 0;
        }

        int bonus = 0;

        // Check for low opponent life (potential lethal)
        for (Player opponent : aiPlayer.getOpponents()) {
            if (opponent.getLife() <= 5) {
                bonus += comboStateBonus / 2;
            }
            if (opponent.getLife() <= 3) {
                bonus += comboStateBonus;
            }
        }

        // Check for high mana availability (combo potential)
        int untappedMana = countUntappedManaProducers(aiPlayer);
        if (untappedMana >= 7) {
            bonus += comboStateBonus / 4;
        }

        // Check for large hand size (combo pieces)
        int handSize = aiPlayer.getCardsIn(ZoneType.Hand).size();
        if (handSize >= 7) {
            bonus += comboStateBonus / 4;
        }

        // Check for creatures with key combo keywords
        for (Card c : aiPlayer.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) {
                // Creatures that can tap for value
                if (c.hasKeyword(Keyword.VIGILANCE) && c.getNetPower() >= 3) {
                    bonus += comboStateBonus / 8;
                }
                // Infinite combo enablers often have these keywords
                if (c.hasKeyword(Keyword.HASTE) && c.hasKeyword(Keyword.LIFELINK)) {
                    bonus += comboStateBonus / 8;
                }
            }
        }

        // Check for potential infinite mana (multiple mana doublers)
        int manaDoublerCount = countManaDoublers(aiPlayer);
        if (manaDoublerCount >= 2) {
            bonus += comboStateBonus;
        }

        // Additional archetype synergy detection
        bonus += evaluateGraveyardSynergy(aiPlayer);
        bonus += evaluateSacrificeSynergy(aiPlayer);
        bonus += evaluateCounterSynergy(aiPlayer);
        bonus += evaluateTribalSynergy(aiPlayer);

        return bonus;
    }

    /**
     * Counts the number of untapped mana producers the player controls.
     */
    private int countUntappedManaProducers(Player player) {
        int count = 0;
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isTapped() && !c.getManaAbilities().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts cards that double mana production (basic heuristic).
     */
    private int countManaDoublers(Player player) {
        int count = 0;
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            String name = c.getName().toLowerCase();
            // Common mana doublers and mana multipliers
            if (name.contains("mana reflection") ||
                name.contains("vorinclex") ||
                name.contains("nyxbloom") ||
                name.contains("mirari's wake") ||
                name.contains("zendikar resurgent") ||
                // Additional mana doublers/multipliers
                name.contains("caged sun") ||
                name.contains("gauntlet of power") ||
                name.contains("mana flare") ||
                name.contains("dictate of karametra") ||
                name.contains("heartbeat of spring") ||
                name.contains("regal behemoth") ||
                name.contains("sasaya") ||
                // Virtual mana doublers (untap effects)
                name.contains("wilderness reclamation") ||
                name.contains("seedborn muse") ||
                name.contains("prophet of kruphix") ||
                name.contains("sword of feast and famine") ||
                name.contains("bear umbra") ||
                name.contains("nature's will") ||
                name.contains("patron of the orochi")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Evaluates graveyard synergy for reanimator/dredge strategies.
     * Provides bonus for having high-value creatures in graveyard.
     */
    private int evaluateGraveyardSynergy(Player player) {
        if (comboStateBonus == 0) {
            return 0;
        }
        int bonus = 0;
        int creatureCount = 0;
        int totalCMC = 0;

        for (Card c : player.getCardsIn(ZoneType.Graveyard)) {
            if (c.isCreature()) {
                creatureCount++;
                totalCMC += c.getCMC();
            }
        }

        // Bonus for having high-CMC creatures in graveyard (reanimator targets)
        if (creatureCount >= 2 && totalCMC >= 10) {
            bonus += comboStateBonus / 4;
        }

        // Check for graveyard size (dredge, flashback synergy)
        int graveyardSize = player.getCardsIn(ZoneType.Graveyard).size();
        if (graveyardSize >= 10) {
            bonus += comboStateBonus / 8;
        }
        if (graveyardSize >= 15) {
            bonus += comboStateBonus / 8;
        }

        return bonus;
    }

    /**
     * Evaluates sacrifice synergy for aristocrats strategies.
     * Detects sacrifice outlets combined with death triggers.
     */
    private int evaluateSacrificeSynergy(Player player) {
        if (comboStateBonus == 0) {
            return 0;
        }
        int bonus = 0;
        int sacrificeOutlets = 0;
        int deathTriggers = 0;

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            String name = c.getName().toLowerCase();
            String text = c.getText().toLowerCase();

            // Sacrifice outlets - free sac outlets are most valuable
            if (name.contains("viscera seer") ||
                name.contains("carrion feeder") ||
                name.contains("yahenni") ||
                name.contains("woe strider") ||
                name.contains("phyrexian altar") ||
                name.contains("ashnod's altar") ||
                name.contains("goblin bombardment") ||
                name.contains("altar of dementia") ||
                name.contains("blasting station")) {
                sacrificeOutlets++;
            } else if (text.contains("sacrifice a creature") || text.contains("sacrifice another")) {
                sacrificeOutlets++;
            }

            // Death triggers / Blood Artist effects
            if (name.contains("blood artist") ||
                name.contains("zulaport cutthroat") ||
                name.contains("cruel celebrant") ||
                name.contains("bastion of remembrance") ||
                name.contains("judith") ||
                name.contains("mayhem devil") ||
                name.contains("vindictive vampire") ||
                name.contains("falkenrath noble") ||
                name.contains("syr konrad")) {
                deathTriggers += 2; // Worth extra
            } else if (text.contains("when") && (text.contains("dies") || text.contains("put into a graveyard from the battlefield"))) {
                if (c.isCreature() || c.isEnchantment()) {
                    deathTriggers++;
                }
            }
        }

        // Synergy bonus when both sacrifice outlet and payoff present
        if (sacrificeOutlets >= 1 && deathTriggers >= 1) {
            bonus += comboStateBonus / 4;
        }
        if (sacrificeOutlets >= 2 && deathTriggers >= 2) {
            bonus += comboStateBonus / 2;
        }

        return bonus;
    }

    /**
     * Evaluates +1/+1 counter synergy potential.
     * Detects counter doublers and creatures with counters.
     */
    private int evaluateCounterSynergy(Player player) {
        if (comboStateBonus == 0) {
            return 0;
        }
        int bonus = 0;
        int creaturesWithCounters = 0;
        int counterSynergyCards = 0;

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature() && c.getCounters(CounterEnumType.P1P1) > 0) {
                creaturesWithCounters++;
            }

            String name = c.getName().toLowerCase();
            if (name.contains("hardened scales") ||
                name.contains("winding constrictor") ||
                name.contains("branching evolution") ||
                name.contains("doubling season") ||
                name.contains("corpsejack menace") ||
                name.contains("vorinclex, monstrous") ||
                name.contains("cathars' crusade") ||
                name.contains("ozolith") ||
                name.contains("conclave mentor") ||
                name.contains("rishkar") ||
                c.hasKeyword(Keyword.MODULAR) ||
                c.hasKeyword(Keyword.EVOLVE)) {
                counterSynergyCards++;
            }
        }

        if (creaturesWithCounters >= 3 && counterSynergyCards >= 1) {
            bonus += comboStateBonus / 4;
        }
        if (creaturesWithCounters >= 5 && counterSynergyCards >= 2) {
            bonus += comboStateBonus / 4;
        }

        return bonus;
    }

    /**
     * Evaluates tribal synergy for common creature types.
     * Provides bonus for concentrated tribal boards.
     */
    private int evaluateTribalSynergy(Player player) {
        if (comboStateBonus == 0) {
            return 0;
        }
        int bonus = 0;

        // Count creatures by type
        java.util.Map<String, Integer> typeCounts = new java.util.HashMap<>();
        String[] relevantTribes = {"Elf", "Goblin", "Zombie", "Vampire", "Merfolk",
                                   "Soldier", "Wizard", "Dragon", "Human", "Cleric",
                                   "Knight", "Elemental", "Spirit", "Angel", "Demon"};

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isCreature()) {
                for (String tribe : relevantTribes) {
                    if (c.getType().hasCreatureType(tribe)) {
                        typeCounts.merge(tribe, 1, Integer::sum);
                    }
                }
            }
        }

        // Bonus for tribal concentration
        for (int count : typeCounts.values()) {
            if (count >= 4) {
                bonus += comboStateBonus / 8;
            }
            if (count >= 6) {
                bonus += comboStateBonus / 4;
            }
        }

        return bonus;
    }

    private static void debugPrint(String s) {
        GameSimulator.debugPrint(s);
    }

    private static class CombatSimResult {
        public GameCopier copier;
        public Game gameCopy;
    }
    private CombatSimResult simulateUpcomingCombatThisTurn(final Game evalGame, final Player aiPlayer) {
        PhaseType phase = evalGame.getPhaseHandler().getPhase();
        if (phase.isAfter(PhaseType.COMBAT_DAMAGE) || evalGame.isGameOver()) {
            return null;
        }
        // If the current player has no creatures in play, there won't be any combat. This avoids
        // an expensive game copy operation.
        // Note: This is is safe to do because the simulation is based on the current game state,
        // so there isn't a chance to play creatures in between.
        if (evalGame.getPhaseHandler().getPlayerTurn().getCreaturesInPlay().isEmpty()) {
            return null;
        }

        Game gameCopy;
        GameCopier copier = new GameCopier(evalGame);

        if (evalGame.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            gameCopy = copier.makeCopy();
        } else {
            gameCopy = copier.makeCopy(null, aiPlayer);
        }

        gameCopy.getPhaseHandler().devAdvanceToPhase(PhaseType.COMBAT_DAMAGE, () -> GameSimulator.resolveStack(gameCopy, aiPlayer.getWeakestOpponent()));
        CombatSimResult result = new CombatSimResult();
        result.copier = copier;
        result.gameCopy = gameCopy;
        return result;
    }

    private static String cardToString(Card c) {
        String str = c.getName();
        if (c.isCreature()) {
            str += " " + c.getNetPower() + "/" + c.getNetToughness();
        }
        return str;
    }

    private Score getScoreForGameOver(Game game, Player aiPlayer) {
        if (game.getOutcome().getWinningTeam() == aiPlayer.getTeam() ||
                game.getOutcome().isWinner(aiPlayer.getRegisteredPlayer())) {
            return new Score(Integer.MAX_VALUE);
        }

        return new Score(Integer.MIN_VALUE);
    }

    public Score getScoreForGameState(Game game, Player aiPlayer) {
        if (game.isGameOver()) {
            return getScoreForGameOver(game, aiPlayer);
        }

        CombatSimResult result = simulateUpcomingCombatThisTurn(game, aiPlayer);
        if (result != null) {
            Player aiPlayerCopy = (Player) result.copier.find(aiPlayer);
            if (result.gameCopy.isGameOver()) {
                return getScoreForGameOver(result.gameCopy, aiPlayerCopy);
            }
            return getScoreForGameStateImpl(result.gameCopy, aiPlayerCopy);
        }
        return getScoreForGameStateImpl(game, aiPlayer);
    }

    private Score getScoreForGameStateImpl(Game game, Player aiPlayer) {
        int score = 0;
        // TODO: more than 2 players
        // TODO: try and reuse evaluateBoardPosition
        int myCards = 0;
        int theirCards = 0;
        for (Card c : game.getCardsIn(ZoneType.Hand)) {
            if (c.getController() == aiPlayer) {
                myCards++;
            } else {
                theirCards++;
            }
        }
        debugPrint("My cards in hand: " + myCards);
        debugPrint("Their cards in hand: " + theirCards);
        if (!aiPlayer.isUnlimitedHandSize() && myCards > aiPlayer.getMaxHandSize()) {
            // Count excess cards for less.
            score += myCards - aiPlayer.getMaxHandSize();
            myCards = aiPlayer.getMaxHandSize();
        }
        // TODO weight cards in hand more if opponent has discard or if we have looting or can bluff a trick
        score += 5 * myCards - 4 * theirCards;
        debugPrint("  My life: " + aiPlayer.getLife());
        score += 2 * aiPlayer.getLife();
        int opponentIndex = 1;
        int opponentLife = 0;
        for (Player opponent : aiPlayer.getOpponents()) {
            debugPrint("  Opponent " + opponentIndex + " life: -" + opponent.getLife());
            opponentLife += opponent.getLife();
            opponentIndex++;
        }
        score -= 2* opponentLife / (game.getPlayers().size() - 1);

        // Add combo state bonus if enabled
        int comboBonus = evaluateComboState(game, aiPlayer);
        if (comboBonus > 0) {
            debugPrint("  Combo state bonus: " + comboBonus);
            score += comboBonus;
        }

        // evaluate mana base quality
        score += evalManaBase(game, aiPlayer, AiDeckStatistics.fromPlayer(aiPlayer));
        // TODO deal with opponents. Do we want to use perfect information to evaluate their manabase?
        //int opponentManaScore = 0;
        //for (Player opponent : aiPlayer.getOpponents()) {
        //    opponentManaScore += evalManaBase(game, opponent);
        //}
        //score -= opponentManaScore / (game.getPlayers().size() - 1);

        // TODO evaluate holding mana open for counterspells

        int summonSickScore = score;
        PhaseType gamePhase = game.getPhaseHandler().getPhase();
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            int value = evalCard(game, aiPlayer, c);
            int summonSickValue = value;
            // To make the AI hold-off on playing creatures before MAIN2 if they give no other benefits,
            // keep track of the score while treating summon sick creatures as having a value of 0.
            if (gamePhase.isBefore(PhaseType.MAIN2) && c.isSick() && c.getController() == aiPlayer) {
                summonSickValue = 0;
            }
            String str = cardToString(c);
            if (c.getController() == aiPlayer) {
                debugPrint("  Battlefield: " + str + " = " + value);
                score += value;
                summonSickScore += summonSickValue;
            } else {
                debugPrint("  Battlefield: " + str + " = -" + value);
                score -= value;
                summonSickScore -= summonSickValue;
            }
            String nonAbilityText = c.getNonAbilityText();
            if (!nonAbilityText.isEmpty()) {
                debugPrint("    "+nonAbilityText.replaceAll("CARDNAME", c.getName()));
            }
        }

        debugPrint("Score = " + score);
        return new Score(score, summonSickScore);
    }

    public int evalManaBase(Game game, Player player, AiDeckStatistics statistics) {
        // TODO should these be fixed quantities or should they be linear out of like 1000/(desired - total)?
        int value = 0;
        // get the colors of mana we can produce and the maximum number of pips
        int max_total = 0;
        // this logic taken from ManaCost.getColorShardCounts()
        int[] counts = new int[6]; // in WUBRGC order

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            int max_produced = 0;
            for (SpellAbility m: c.getManaAbilities()) {
                m.setActivatingPlayer(c.getController());
                int mana_cost = m.getPayCosts().getTotalMana().getCMC();
                max_produced = max(max_produced, m.amountOfManaGenerated(true) - mana_cost);
                for (AbilityManaPart mp : m.getAllManaParts()) {
                    for (String part : mp.mana(m).split(" ")) {
                        // TODO handle any
                        int index = ManaAtom.getIndexFromName(part);
                        if (index != -1) {
                            counts[index] += 1;
                        }
                    }
                }
            }
            max_total += max_produced;
        }

        // Compare against the maximums in the deck and in the hand
        // TODO check number of castable cards in hand
        for (int i = 0; i < counts.length; i++) {
            // for each color pip, add 100
            value += Math.min(counts[i], statistics.maxPips[i]) * 100;
        }
        // value for being able to cast all the cards in your deck
        value += min(max_total, statistics.maxCost) * 100;

        // excess mana is valued less than getting enough to use everything
        value += max(0, max_total - statistics.maxCost) * 5;

        return value;
    }

    public int evalCard(Game game, Player aiPlayer, Card c) {
        // TODO: These should be based on other considerations - e.g. in relation to opponents state.
        if (c.isCreature()) {
            return eval.evaluateCreature(c);
        } else if (c.isLand()) {
            return evaluateLand(c);
        } else if (c.isEnchantingCard()) {
            // TODO: Should provide value in whatever it's enchanting?
            // Else the computer would think that casting a Lifelink enchantment
            // on something that already has lifelink is a net win.
            return 0;
        } else {
            // TODO treat cards like Captive Audience negative
            // e.g. a 5 CMC permanent results in 200, whereas a 5/5 creature is ~225
            int value = 50 + 30 * c.getCMC();
            if (c.isPlaneswalker()) {
                value += 2 * c.getCounters(CounterEnumType.LOYALTY);
            }
            return value;
        }
    }

    public static int evaluateLand(Card c) {
        int value = 3;
        // for each mana color a land generates for free, increase the value by one
        // for each mana a land can produce, add one hundred.
        int max_produced = 0;
        Set<String> colors_produced = new HashSet<>();
        for (SpellAbility m: c.getManaAbilities()) {
            m.setActivatingPlayer(c.getController());
            int mana_cost = m.getPayCosts().getTotalMana().getCMC();
            max_produced = max(max_produced, m.amountOfManaGenerated(true) - mana_cost);
            for (AbilityManaPart mp : m.getAllManaParts()) {
                colors_produced.addAll(Arrays.asList(mp.mana(m).split(" ")));
            }
        }
        value += 100 * max_produced;
        int size = max(colors_produced.size(), colors_produced.contains("Any") ? 5 : 0);
        value += size * 3;

        // add a value for each activated ability that the land has that's not an activated ability.
        // The value should be more than the value of having a card in hand, so if a land has an
        // activated ability but not a mana ability, it will still be played.
        for (SpellAbility m: c.getNonManaAbilities()) {
            if (m.isLandAbility()) {
                // Land Ability has no extra Score
                continue;
            } if (!m.getPayCosts().hasTapCost()) {
                // probably a manland, rate it higher than a rainbow land
                value += 25;
            } else if (m.getPayCosts().hasSpecificCostType(CostSacrifice.class)) {
                // Sacrifice ability, so not repeatable. Less good than a utility land that gets you ahead
                value += 10;
            } else {
                // Repeatable utility land, probably gets you ahead on board over time.
                // big value, probably more than a manland
                value += 50;
            }
        }

        // Add a value for each static ability that the land has
        for (StaticAbility s : c.getStaticAbilities()) {
            // More than the value of having a card in hand. See comment above
            value += 6;
        }

        return value;
    }

    private class SimulationCreatureEvaluator extends CreatureEvaluator {
        @Override
        protected int addValue(int value, String text) {
            if (debugging && value != 0) {
                GameSimulator.debugPrint(value + " via " + text);
            }
            return super.addValue(value, text);
        }
    }

    public static class Score {
        public final int value;
        public final int summonSickValue;
        
        public Score(int value) {
            this.value = value;
            this.summonSickValue = value;
        }

        public Score(int value, int summonSickValue) {
            this.value = value;
            this.summonSickValue = summonSickValue;
        }

        public boolean equals(Score other) {
            if (other == null)
                return false;
            return value == other.value && summonSickValue == other.summonSickValue;
        }

        public String toString() {
            return value + (summonSickValue != value ? " (ss " + summonSickValue + ")" :"");
        }
    }
}
