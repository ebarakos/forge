package forge.ai.simulation;

import forge.ai.AiDeckStatistics;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CreatureEvaluator;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostSacrifice;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantAttackBlock;
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
    private int lifePressureWeight = 20;

    // Category weight multipliers (percent scale, 100 = neutral). Loaded from the
    // AI profile alongside the combo bonus; with all at 100 the produced scores are
    // identical to the unweighted evaluator.
    private int handWeightPct = 100;
    private int lifeWeightPct = 100;
    private int boardWeightPct = 100;
    private int tempoWeightPct = 100;
    private int clockWeightPct = 100;

    // Card evaluation cache for faster repeated evaluations
    // Key format: "cardName:P/T:tapped:counters" for creatures, "cardName:loyalty" for planeswalkers
    private final java.util.Map<String, Integer> cardEvalCache = new java.util.HashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;

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
            this.lifePressureWeight = AiProfileUtil.getIntProperty(player, AiProps.SIM_EVAL_LIFE_PRESSURE_WEIGHT);
            this.handWeightPct = AiProfileUtil.getIntProperty(player, AiProps.SIM_EVAL_HAND_WEIGHT_PCT);
            this.lifeWeightPct = AiProfileUtil.getIntProperty(player, AiProps.SIM_EVAL_LIFE_WEIGHT_PCT);
            this.boardWeightPct = AiProfileUtil.getIntProperty(player, AiProps.SIM_EVAL_BOARD_WEIGHT_PCT);
            this.tempoWeightPct = AiProfileUtil.getIntProperty(player, AiProps.SIM_EVAL_TEMPO_WEIGHT_PCT);
            this.clockWeightPct = AiProfileUtil.getIntProperty(player, AiProps.SIM_EVAL_CLOCK_WEIGHT_PCT);
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
            String text = c.getOracleText().toLowerCase();

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

    // --- Learned evaluator modes (FORGE_SIM_EVAL=learned|blend) ---
    // Sysprop forge.sim.eval wins over the env var, mirroring the policy/combat
    // mode plumbing. The model path comes from forge.sim.evalmodel /
    // FORGE_SIM_EVAL_MODEL. Load failures warn once and fall back to the
    // linear evaluator — an advisory layer must never end a run.
    //
    // "learned" replaces the linear score with win-prob millionths. "blend"
    // keeps the linear score (full fine-grained action discrimination — the
    // GBDT exact-ties on ~46% of small board deltas, which the picker's
    // strict-improvement gates turn into held actions) and adds a learned
    // strategic correction: score += (p - 0.5) * blendScale.
    private enum EvalMode { LINEAR, LEARNED, BLEND }
    private static final EvalMode EVAL_MODE = parseEvalMode();
    private static final int BLEND_SCALE = parseBlendScale();
    private static volatile LearnedEvaluator LEARNED_INSTANCE;
    private static volatile boolean learnedLoadFailed;

    private static EvalMode parseEvalMode() {
        String mode = System.getProperty("forge.sim.eval");
        if (mode == null || mode.isEmpty()) {
            mode = System.getenv("FORGE_SIM_EVAL");
        }
        if ("learned".equalsIgnoreCase(mode)) {
            return EvalMode.LEARNED;
        }
        if ("blend".equalsIgnoreCase(mode)) {
            return EvalMode.BLEND;
        }
        return EvalMode.LINEAR;
    }

    private static int parseBlendScale() {
        String s = System.getProperty("forge.sim.evalblendscale");
        if (s == null || s.isEmpty()) {
            s = System.getenv("FORGE_SIM_EVAL_BLEND_SCALE");
        }
        if (s != null && !s.isEmpty()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                System.err.println("GameStateEvaluator: bad blend scale '" + s + "', using 6000");
            }
        }
        // 6000 ⇒ a 10pp win-probability swing is worth ~600 linear points
        // (roughly a good creature) — strategic signal that matters without
        // drowning the linear evaluator's per-card resolution.
        return 6000;
    }

    /** Whether the pure learned mode is active (scores in win-probability millionths). */
    public static boolean isLearnedMode() {
        return EVAL_MODE == EvalMode.LEARNED;
    }

    private static LearnedEvaluator learnedInstance() {
        LearnedEvaluator le = LEARNED_INSTANCE;
        if (le != null || learnedLoadFailed) {
            return le;
        }
        synchronized (GameStateEvaluator.class) {
            if (LEARNED_INSTANCE != null || learnedLoadFailed) {
                return LEARNED_INSTANCE;
            }
            String path = System.getProperty("forge.sim.evalmodel");
            if (path == null || path.isEmpty()) {
                path = System.getenv("FORGE_SIM_EVAL_MODEL");
            }
            try {
                if (path == null || path.isEmpty()) {
                    throw new java.io.IOException(
                            "FORGE_SIM_EVAL=learned but no model path set "
                            + "(forge.sim.evalmodel / FORGE_SIM_EVAL_MODEL)");
                }
                LEARNED_INSTANCE = LearnedEvaluator.load(path);
                System.err.println("GameStateEvaluator: learned evaluator loaded from " + path);
            } catch (Exception e) {
                learnedLoadFailed = true;
                System.err.println("GameStateEvaluator: learned evaluator unavailable, "
                        + "falling back to linear: " + e);
            }
            return LEARNED_INSTANCE;
        }
    }

    private Score getScoreForGameStateImpl(Game game, Player aiPlayer) {
        if (EVAL_MODE == EvalMode.LEARNED) {
            LearnedEvaluator le = learnedInstance();
            if (le != null) {
                int s = le.score(extractFeaturesInstance(game, aiPlayer));
                // Pre-MAIN2, also score with own summon-sick creatures zeroed —
                // the picker's hold-creatures-for-MAIN2 gate compares this variant.
                int sick = game.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)
                        ? le.score(extractFeaturesInstance(game, aiPlayer, true)) : s;
                return new Score(s, sick);
            }
        }
        int score = 0;
        // TODO: more than 2 players
        // TODO: try and reuse evaluateBoardPosition
        Player weakestOpponent = aiPlayer.getWeakestOpponent();

        // --- Card quality weighting ---
        // Weight hand cards by castability rather than flat count
        int myAvailableMana = countUntappedManaProducers(aiPlayer);
        int myHandValue = castabilityWeightedHandValue(game, aiPlayer, myAvailableMana);
        int theirCards = 0;
        for (Card c : game.getCardsIn(ZoneType.Hand)) {
            if (c.getController() != aiPlayer) {
                theirCards++;
            }
        }
        int myCards = aiPlayer.getCardsIn(ZoneType.Hand).size();
        debugPrint("My cards in hand: " + myCards + " (value: " + myHandValue + ")");
        debugPrint("Their cards in hand: " + theirCards);
        // Excess cards will be discarded — count them for less
        myHandValue -= excessHandSizePenalty(aiPlayer);
        score += (myHandValue - 4 * theirCards) * handWeightPct / 100;

        debugPrint("  My life: " + aiPlayer.getLife());
        score += 2 * aiPlayer.getLife() * lifeWeightPct / 100;
        int opponentIndex = 1;
        int opponentLife = 0;
        for (Player opponent : aiPlayer.getOpponents()) {
            debugPrint("  Opponent " + opponentIndex + " life: -" + opponent.getLife());
            opponentLife += opponent.getLife();
            opponentIndex++;
        }
        score -= (2 * opponentLife / (game.getPlayers().size() - 1)) * lifeWeightPct / 100;

        // Life pressure: damage already dealt toward the weakest opponent's lethal
        // is worth progressively more as their life drops. Without this, one small
        // creature (~165 pts) outweighs the entire life race (2 pts/life), so
        // racing decks never go face. Quadratic ramp: negligible early, decisive
        // near lethal. Tuned via SIM_EVAL_LIFE_PRESSURE_WEIGHT.
        if (weakestOpponent != null && lifePressureWeight > 0) {
            int startLife = max(1, weakestOpponent.getStartingLife());
            int dealt = max(0, startLife - weakestOpponent.getLife());
            int pressure = dealt * dealt * lifePressureWeight / startLife;
            if (pressure != 0) {
                debugPrint("  Life pressure: dealt=" + dealt + " bonus=" + pressure);
                score += pressure;
            }
        }

        // Add combo state bonus if enabled
        int comboBonus = evaluateComboState(game, aiPlayer);
        if (comboBonus > 0) {
            debugPrint("  Combo state bonus: " + comboBonus);
            score += comboBonus;
        }

        // evaluate mana base quality
        score += evalManaBase(game, aiPlayer, AiDeckStatistics.fromPlayer(aiPlayer));

        // --- Tempo score ---
        // Having more untapped mana means more options and trick potential
        if (weakestOpponent != null) {
            int theirUntappedMana = countUntappedManaProducers(weakestOpponent);
            int tempoBonus = (myAvailableMana - theirUntappedMana) * 3 * tempoWeightPct / 100;
            if (tempoBonus != 0) {
                debugPrint("  Tempo: my mana=" + myAvailableMana + " their mana=" + theirUntappedMana + " bonus=" + tempoBonus);
                score += tempoBonus;
            }
        }

        int summonSickScore = score;
        PhaseType gamePhase = game.getPhaseHandler().getPhase();

        // Track evasive damage for clock calculation during battlefield iteration
        int myEvasiveDamage = 0;
        int theirEvasiveDamage = 0;

        // Accumulate the signed battlefield sums raw, then apply the board weight
        // once to the totals (per-card rounding would change results at pct != 100).
        int boardScore = 0;
        int boardSummonSickScore = 0;

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
                boardScore += value;
                boardSummonSickScore += summonSickValue;
            } else {
                debugPrint("  Battlefield: " + str + " = -" + value);
                boardScore -= value;
                boardSummonSickScore -= summonSickValue;
            }
            String nonAbilityText = c.getNonAbilityText();
            if (!nonAbilityText.isEmpty()) {
                debugPrint("    "+nonAbilityText.replaceAll("CARDNAME", c.getName()));
            }

            // Track evasive damage for clock (only non-sick, untapped creatures)
            if (c.isCreature() && !c.isTapped() && !c.isSick() && !c.hasKeyword(Keyword.DEFENDER)) {
                int power = c.getNetCombatDamage();
                if (power > 0 && (c.hasKeyword(Keyword.FLYING) || c.hasKeyword(Keyword.HORSEMANSHIP)
                        || StaticAbilityCantAttackBlock.cantBlockBy(c, null))) {
                    if (c.getController() == aiPlayer) {
                        myEvasiveDamage += power;
                    } else {
                        theirEvasiveDamage += power;
                    }
                }
            }
        }

        score += boardScore * boardWeightPct / 100;
        summonSickScore += boardSummonSickScore * boardWeightPct / 100;

        // --- Clock calculation ---
        // Being ahead on the evasive damage clock is strategically valuable
        if (weakestOpponent != null && (myEvasiveDamage > 0 || theirEvasiveDamage > 0)) {
            int myTurnsToKill = myEvasiveDamage > 0
                    ? (weakestOpponent.getLife() + myEvasiveDamage - 1) / myEvasiveDamage : 99;
            int theirTurnsToKill = theirEvasiveDamage > 0
                    ? (aiPlayer.getLife() + theirEvasiveDamage - 1) / theirEvasiveDamage : 99;
            // Each turn of clock advantage is worth ~15 points, capped
            int clockBonus = max(-80, min(80, (theirTurnsToKill - myTurnsToKill) * 15)) * clockWeightPct / 100;
            if (clockBonus != 0) {
                debugPrint("  Clock: my turns=" + myTurnsToKill + " their turns=" + theirTurnsToKill + " bonus=" + clockBonus);
                score += clockBonus;
                summonSickScore += clockBonus;
            }
        }

        if (EVAL_MODE == EvalMode.BLEND) {
            LearnedEvaluator le = learnedInstance();
            if (le != null) {
                int corr = (int) ((le.winProbability(extractFeaturesInstance(game, aiPlayer)) - 0.5)
                        * BLEND_SCALE);
                int sickCorr = game.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)
                        ? (int) ((le.winProbability(extractFeaturesInstance(game, aiPlayer, true)) - 0.5)
                                * BLEND_SCALE)
                        : corr;
                debugPrint("  Learned blend correction: " + corr);
                score += corr;
                summonSickScore += sickCorr;
            }
        }

        debugPrint("Score = " + score);
        return new Score(score, summonSickScore);
    }

    /**
     * Castability-weighted value of a player's hand: lands and currently
     * uncastable spells count 3, castable spells count 6. Shared between the
     * scoring path and {@link #extractFeatures(Game, Player)}.
     */
    private static int castabilityWeightedHandValue(Game game, Player aiPlayer, int availableMana) {
        int handValue = 0;
        for (Card c : game.getCardsIn(ZoneType.Hand)) {
            if (c.getController() != aiPlayer) {
                continue;
            }
            if (c.isLand()) {
                handValue += 3; // lands in hand have diminishing value
            } else if (c.getCMC() <= availableMana) {
                handValue += 6; // castable spell — real option
            } else {
                handValue += 3; // too expensive right now
            }
        }
        return handValue;
    }

    /**
     * Penalty for cards above the maximum hand size (they will be discarded).
     */
    private static int excessHandSizePenalty(Player aiPlayer) {
        int myCards = aiPlayer.getCardsIn(ZoneType.Hand).size();
        if (!aiPlayer.isUnlimitedHandSize() && myCards > aiPlayer.getMaxHandSize()) {
            return (myCards - aiPlayer.getMaxHandSize()) * 2;
        }
        return 0;
    }

    /**
     * Extracts raw evaluation features for player {@code p} from the live game
     * state, for offline analysis (e.g. fitting the SIM_EVAL_*_PCT weights).
     * <p>
     * Computed directly on the real game with a fresh evaluator instance:
     * no combat simulation, no game copying, no RNG consumption, and no
     * profile loading. Keys missing an opponent are omitted.
     */
    public static java.util.Map<String, Integer> extractFeatures(Game game, Player p) {
        if (game == null || p == null) {
            return new java.util.LinkedHashMap<>();
        }
        return new GameStateEvaluator().extractFeaturesInstance(game, p);
    }

    /**
     * Instance variant of {@link #extractFeatures(Game, Player)} that reuses
     * this evaluator's card-evaluation cache — the form the learned-evaluator
     * scoring path calls per simulated state.
     */
    java.util.Map<String, Integer> extractFeaturesInstance(Game game, Player p) {
        return extractFeaturesInstance(game, p, false);
    }

    /**
     * With {@code zeroSickOwn} set, the perspective player's summon-sick
     * creatures are excluded from board features (the learned-path analogue
     * of the linear evaluator's summonSickScore, used pre-MAIN2 to keep the
     * hold-creatures-for-MAIN2 behaviour).
     */
    java.util.Map<String, Integer> extractFeaturesInstance(Game game, Player p, boolean zeroSickOwn) {
        java.util.Map<String, Integer> features = new java.util.LinkedHashMap<>();
        GameStateEvaluator evaluator = this;
        Player opp = p.getWeakestOpponent();

        features.put("turn", game.getPhaseHandler().getTurn());
        // Sample-point context: whose turn it is and a coarse phase ordinal
        // (0 pre-main, 1 main1, 2 combat, 3 main2, 4 end) — lets a model
        // trained on mixed-phase data condition on the sampling point instead
        // of conflating turn-start and mid-turn resource profiles.
        features.put("my_turn", game.getPhaseHandler().getPlayerTurn() == p ? 1 : 0);
        PhaseType ph = game.getPhaseHandler().getPhase();
        int phaseOrd;
        if (ph == null || ph.isBefore(PhaseType.MAIN1)) {
            phaseOrd = 0;
        } else if (ph == PhaseType.MAIN1) {
            phaseOrd = 1;
        } else if (ph.isBefore(PhaseType.MAIN2)) {
            phaseOrd = 2;
        } else if (ph == PhaseType.MAIN2) {
            phaseOrd = 3;
        } else {
            phaseOrd = 4;
        }
        features.put("phase_ord", phaseOrd);
        features.put("my_life", p.getLife());
        if (opp != null) {
            features.put("opp_life", opp.getLife());
            features.put("opp_dealt", max(0, opp.getStartingLife() - opp.getLife()));
        }

        int myAvailableMana = evaluator.countUntappedManaProducers(p);
        int myHandValue = castabilityWeightedHandValue(game, p, myAvailableMana)
                - excessHandSizePenalty(p);
        features.put("my_hand_value", myHandValue);
        features.put("my_hand_count", p.getCardsIn(ZoneType.Hand).size());
        if (opp != null) {
            features.put("opp_hand_count", opp.getCardsIn(ZoneType.Hand).size());
        }

        // Signed battlefield sums, same per-card evaluation (and perspective)
        // as the scoring loop; split by controller instead of signed into one total.
        int myBoardEval = 0;
        int oppBoardEval = 0;
        int myLands = 0;
        int oppLands = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            boolean mine = c.getController() == p;
            boolean zeroed = zeroSickOwn && mine && c.isCreature() && c.isSick();
            int value = zeroed ? 0 : evaluator.evalCard(game, p, c);
            if (mine) {
                myBoardEval += value;
            } else {
                oppBoardEval += value;
            }
            if (c.isLand()) {
                if (mine) {
                    myLands++;
                } else {
                    oppLands++;
                }
            }
        }
        features.put("my_board_eval", myBoardEval);
        features.put("opp_board_eval", oppBoardEval);
        features.put("my_lands", myLands);
        features.put("opp_lands", oppLands);

        features.put("my_untapped_mana", myAvailableMana);
        if (opp != null) {
            features.put("opp_untapped_mana", evaluator.countUntappedManaProducers(opp));
        }

        features.put("my_evasive", evasiveDamage(p));
        if (opp != null) {
            features.put("opp_evasive", evasiveDamage(opp));
        }

        // Stall / inevitability: in locked board states the terminal resource
        // is the library, the exit routes are lifegain loops and evasion, and
        // "no life lost for two turns" is the stall fingerprint itself.
        features.put("my_library", p.getZone(ZoneType.Library).size());
        features.put("my_gy", p.getCardsIn(ZoneType.Graveyard).size());
        features.put("my_life_lost_recent", p.getLifeLostThisTurn() + p.getLifeLostLastTurn());
        if (opp != null) {
            features.put("opp_library", opp.getZone(ZoneType.Library).size());
            features.put("opp_gy", opp.getCardsIn(ZoneType.Graveyard).size());
            features.put("opp_life_lost_recent", opp.getLifeLostThisTurn() + opp.getLifeLostLastTurn());
        }

        boardStructureFeatures(p, "my_", features, zeroSickOwn);
        if (opp != null) {
            boardStructureFeatures(opp, "opp_", features, false);
        }

        // Own-hand composition (own perspective only — the evaluator may know
        // its own hand, never the opponent's).
        int castable = 0;
        int counterspells = 0;
        int burn = 0;
        int removal = 0;
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (c.isLand()) {
                continue;
            }
            if (c.getCMC() <= myAvailableMana) {
                castable++;
            }
            SpellAbility first = c.getFirstSpellAbility();
            forge.game.ability.ApiType api = first == null ? null : first.getApi();
            if (api == forge.game.ability.ApiType.Counter) {
                counterspells++;
            } else if (api == forge.game.ability.ApiType.DealDamage) {
                burn++;
            } else if (api == forge.game.ability.ApiType.Destroy
                    || api == forge.game.ability.ApiType.DestroyAll) {
                removal++;
            }
        }
        features.put("my_castable", castable);
        features.put("my_counterspells", counterspells);
        features.put("my_burn", burn);
        features.put("my_removal", removal);

        return features;
    }

    /**
     * Battlefield-structure features for one player: creature counts and
     * power/toughness distribution (wall math), tribal concentration, static
     * ability density (anthems/lords), and lifegain-loop sources.
     */
    private static void boardStructureFeatures(Player pl, String prefix,
            java.util.Map<String, Integer> out, boolean zeroSick) {
        int creatures = 0;
        int untappedCreatures = 0;
        int power = 0;
        int toughness = 0;
        int maxPower = 0;
        int maxToughness = 0;
        int statics = 0;
        int lifegainSources = 0;
        java.util.Map<String, Integer> typeCounts = new java.util.HashMap<>();
        for (Card c : pl.getCardsIn(ZoneType.Battlefield)) {
            statics += c.getStaticAbilities().size();
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() == forge.game.ability.ApiType.GainLife) {
                    lifegainSources++;
                }
            }
            if (!c.isCreature() || (zeroSick && c.isSick())) {
                continue;
            }
            creatures++;
            if (!c.isTapped()) {
                untappedCreatures++;
            }
            int pw = max(0, c.getNetPower());
            int tf = max(0, c.getNetToughness());
            power += pw;
            toughness += tf;
            maxPower = max(maxPower, pw);
            maxToughness = max(maxToughness, tf);
            for (String type : c.getType().getCreatureTypes()) {
                typeCounts.merge(type, 1, Integer::sum);
            }
        }
        int tribalMax = 0;
        for (int count : typeCounts.values()) {
            tribalMax = max(tribalMax, count);
        }
        out.put(prefix + "creatures", creatures);
        out.put(prefix + "untapped_creatures", untappedCreatures);
        out.put(prefix + "power", power);
        out.put(prefix + "toughness", toughness);
        out.put(prefix + "max_power", maxPower);
        out.put(prefix + "max_toughness", maxToughness);
        out.put(prefix + "tribal_max", tribalMax);
        out.put(prefix + "statics", statics);
        out.put(prefix + "lifegain_sources", lifegainSources);
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
        // Creatures use context-aware evaluation (depends on board state, not cacheable)
        if (c.isCreature()) {
            return eval.evaluateCreatureInContext(c, game, aiPlayer);
        }

        // Player-attachment check must come BEFORE the cache: the cache key does not
        // encode the attachment target, so a signed curse value must never be cached.
        GameEntity attachedTo = c.getEntityAttachedTo();
        if (attachedTo instanceof Player) {
            if (c.isCurse()) {
                // Score from the controller's perspective (battlefield loop handles
                // the sign flip for opponent-controlled cards, so don't double-negate).
                int base = 50 + 30 * c.getCMC();
                return (attachedTo == c.getController()) ? -base : base;
            }
            // Non-curse player-attached aura: neutral (mirrors isEnchantingCard() == 0)
            return 0;
        }

        // Non-creature cards can be cached
        String cacheKey = getCardCacheKey(c);
        Integer cachedValue = cardEvalCache.get(cacheKey);
        if (cachedValue != null) {
            return cachedValue;
        }

        int value;
        if (c.isLand()) {
            value = evaluateLand(c);
        } else if (c.isEnchantingCard()) {
            // TODO: Should provide value in whatever it's enchanting?
            // Else the computer would think that casting a Lifelink enchantment
            // on something that already has lifelink is a net win.
            value = 0;
        } else {
            // TODO treat cards like Captive Audience negative
            // e.g. a 5 CMC permanent results in 200, whereas a 5/5 creature is ~225
            value = 50 + 30 * c.getCMC();
            if (c.isPlaneswalker()) {
                value += 2 * c.getCounters(CounterEnumType.LOYALTY);
            }
        }

        // Store in cache (with size limit)
        if (cardEvalCache.size() < MAX_CACHE_SIZE) {
            cardEvalCache.put(cacheKey, value);
        }

        return value;
    }

    /**
     * Generates a cache key for a card based on its current state.
     * The key includes properties that affect the card's evaluation.
     */
    private String getCardCacheKey(Card c) {
        StringBuilder key = new StringBuilder(c.getName());
        if (c.isCreature()) {
            // For creatures: include P/T which affects evaluation significantly
            key.append(':').append(c.getNetPower()).append('/').append(c.getNetToughness());
            // Include +1/+1 counters as they affect value
            int p1p1 = c.getCounters(CounterEnumType.P1P1);
            if (p1p1 > 0) {
                key.append(":+").append(p1p1);
            }
        } else if (c.isPlaneswalker()) {
            // For planeswalkers: include loyalty
            key.append(':').append(c.getCounters(CounterEnumType.LOYALTY));
        }
        return key.toString();
    }

    /**
     * Clears the card evaluation cache.
     * Should be called at the start of a new evaluation to ensure fresh results.
     */
    public void clearCache() {
        cardEvalCache.clear();
    }

    /**
     * Compute turns until {@code damagePerTurn} kills a player at {@code life}.
     * Returns {@link Integer#MAX_VALUE} when there is no clock (damage == 0).
     * Used by the LLM player's threat tiering and by the heuristic evaluator's
     * clock-advantage bonus.
     */
    public static int turnsToKill(int life, int damagePerTurn) {
        if (damagePerTurn <= 0) return Integer.MAX_VALUE;
        return (Math.max(life, 0) + damagePerTurn - 1) / damagePerTurn;
    }

    /**
     * Sum a player's evasive combat damage from non-sick, untapped, non-defender
     * creatures with flying or horsemanship (or other unblockable static effects).
     * The figure is a lower bound on damage they can land next turn.
     */
    public static int evasiveDamage(Player p) {
        int total = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isCreature() || c.isTapped() || c.isSick()
                    || c.hasKeyword(Keyword.DEFENDER)) continue;
            int power = c.getNetCombatDamage();
            if (power <= 0) continue;
            if (c.hasKeyword(Keyword.FLYING) || c.hasKeyword(Keyword.HORSEMANSHIP)
                    || StaticAbilityCantAttackBlock.cantBlockBy(c, null)) {
                total += power;
            }
        }
        return total;
    }

    /**
     * Sum a player's total combat damage from non-sick, untapped, non-defender
     * creatures (assumes nothing blocks). Optimistic upper bound on the clock.
     */
    public static int totalCombatDamage(Player p) {
        int total = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isCreature() || c.isTapped() || c.isSick()
                    || c.hasKeyword(Keyword.DEFENDER)) continue;
            int power = c.getNetCombatDamage();
            if (power > 0) total += power;
        }
        return total;
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

        /**
         * Evaluates a creature considering the current board state.
         * Adjusts base value based on evasion relevance, board density,
         * and threat sizing relative to opponent's creatures.
         */
        public int evaluateCreatureInContext(Card c, Game game, Player aiPlayer) {
            int baseValue = evaluateCreature(c);
            if (game == null || aiPlayer == null) return baseValue;

            Player opponent = aiPlayer.getWeakestOpponent();
            if (opponent == null) return baseValue;

            boolean isOurs = c.getController() == aiPlayer;
            Player enemy = isOurs ? opponent : aiPlayer;

            int power = c.getNetCombatDamage();
            int toughness = c.getNetToughness();
            int contextBonus = 0;

            CardCollection enemyCreatures = enemy.getCreaturesInPlay();
            int totalEnemyCreatures = enemyCreatures.size();

            // --- Evasion relevance ---
            // Bonus when few/no enemy creatures can block this attacker
            if (power > 0 && !c.hasKeyword(Keyword.DEFENDER) && totalEnemyCreatures > 0) {
                int potentialBlockers = 0;
                for (Card blocker : enemyCreatures) {
                    if (!blocker.isTapped() && canPotentiallyBlock(c, blocker)) {
                        potentialBlockers++;
                    }
                }
                if (potentialBlockers == 0) {
                    contextBonus += addValue(power * 8, "ctx:no-blockers");
                } else if (potentialBlockers == 1) {
                    contextBonus += addValue(power * 4, "ctx:one-blocker");
                }
            }

            // --- Board density ---
            // Creatures matter more on sparse boards
            int ourCount = (isOurs ? aiPlayer : opponent).getCreaturesInPlay().size();
            int totalCreatures = ourCount + totalEnemyCreatures;
            if (totalCreatures <= 2) {
                contextBonus += addValue(25, "ctx:sparse-board");
            } else if (totalCreatures <= 4) {
                contextBonus += addValue(10, "ctx:medium-board");
            }

            // --- Threat sizing ---
            // Bonus for creatures that dominate the opposing board
            if (power > 0 && totalEnemyCreatures >= 2) {
                int killsCount = 0;
                int survivesCount = 0;
                for (Card ec : enemyCreatures) {
                    if (power >= ec.getNetToughness()) killsCount++;
                    if (toughness > ec.getNetCombatDamage()) survivesCount++;
                }
                if (killsCount >= totalEnemyCreatures) {
                    contextBonus += addValue(15, "ctx:kills-all");
                }
                if (survivesCount >= totalEnemyCreatures) {
                    contextBonus += addValue(10, "ctx:survives-all");
                }
            }

            // Deathtouch is more valuable against big creatures
            if (c.hasKeyword(Keyword.DEATHTOUCH) && totalEnemyCreatures > 0) {
                int bigThreats = 0;
                for (Card ec : enemyCreatures) {
                    if (ec.getNetCombatDamage() >= 4) bigThreats++;
                }
                if (bigThreats > 0) {
                    contextBonus += addValue(min(bigThreats * 10, 30), "ctx:dt-vs-big");
                }
            }

            return baseValue + contextBonus;
        }

        /**
         * Simplified check for whether a blocker can potentially block an attacker
         * based on evasion keywords. Used for evaluation, not full rules enforcement.
         */
        private boolean canPotentiallyBlock(Card attacker, Card blocker) {
            // Flying: only flying/reach can block
            if (attacker.hasKeyword(Keyword.FLYING)) {
                if (!blocker.hasKeyword(Keyword.FLYING) && !blocker.hasKeyword(Keyword.REACH)) {
                    return false;
                }
            }
            // Horsemanship: only horsemanship can block
            if (attacker.hasKeyword(Keyword.HORSEMANSHIP)) {
                if (!blocker.hasKeyword(Keyword.HORSEMANSHIP)) {
                    return false;
                }
            }
            // Shadow: only shadow can block shadow
            if (attacker.hasKeyword(Keyword.SHADOW)) {
                if (!blocker.hasKeyword(Keyword.SHADOW)) {
                    return false;
                }
            }
            // Fear: only artifact or black creatures
            if (attacker.hasKeyword(Keyword.FEAR)) {
                if (!blocker.isArtifact() && !blocker.isBlack()) {
                    return false;
                }
            }
            // Intimidate: only artifact or color-sharing creatures
            if (attacker.hasKeyword(Keyword.INTIMIDATE)) {
                if (!blocker.isArtifact() && !blocker.sharesColorWith(attacker)) {
                    return false;
                }
            }
            return true;
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
