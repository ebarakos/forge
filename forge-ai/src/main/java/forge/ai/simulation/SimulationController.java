package forge.ai.simulation;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SimulationController {
    private static boolean DEBUG = false;
    private static final int DEFAULT_MAX_DEPTH = 3;

    private List<Plan.Decision> currentStack;
    private List<Score> scoreStack;
    private List<GameSimulator> simulatorStack;
    private Plan.Decision bestSequence; // last action of sequence
    private Score bestScore;
    private List<CachedEffect> effectCache = new ArrayList<>();
    private GameObject[] currentHostAndTarget;

    // Enhanced simulation components
    private final Player aiPlayer;
    private final int maxDepth;
    private final boolean loopDetectionEnabled;
    private final boolean useTranspositionTable;
    private final boolean alphaBetaEnabled;
    private final int futilityMargin;
    private final GameStateHasher stateHasher;
    private final TranspositionTable transpositionTable;
    private final long startTimeMs;
    private final long timeLimitMs;

    // Alpha tracking: best score found at each depth level
    // alphaStack[i] = best score found so far at depth i
    private final List<Integer> alphaStack;

    private static class CachedEffect {
        final GameObject hostCard;
        final String sa;
        final GameObject target;
        final int targetScore;
        final int scoreDelta;

        public CachedEffect(GameObject hostCard, SpellAbility sa, GameObject target, int targetScore, int scoreDelta) {
            this.hostCard = hostCard;
            this.sa = sa.toString();
            this.target = target;
            this.targetScore = targetScore;
            this.scoreDelta = scoreDelta;
        }
    }

    /**
     * Creates a SimulationController with default settings (for backwards compatibility).
     */
    public SimulationController(Score score) {
        this(score, null);
    }

    /**
     * Creates a SimulationController with profile-based settings.
     * @param score the initial score
     * @param player the AI player (used to read profile settings, can be null for defaults)
     */
    public SimulationController(Score score, Player player) {
        bestScore = score;
        scoreStack = new ArrayList<>();
        scoreStack.add(score);
        simulatorStack = new ArrayList<>();
        currentStack = new ArrayList<>();

        this.aiPlayer = player;
        this.startTimeMs = System.currentTimeMillis();

        // Read settings from profile or use defaults
        if (player != null) {
            this.maxDepth = AiProfileUtil.getIntProperty(player, AiProps.SIMULATION_MAX_DEPTH);
            this.timeLimitMs = AiProfileUtil.getIntProperty(player, AiProps.SIMULATION_TIME_LIMIT_MS);
            this.loopDetectionEnabled = AiProfileUtil.getBoolProperty(player, AiProps.LOOP_DETECTION_ENABLED);
            this.useTranspositionTable = AiProfileUtil.getBoolProperty(player, AiProps.USE_TRANSPOSITION_TABLE);
            this.alphaBetaEnabled = AiProfileUtil.getBoolProperty(player, AiProps.ALPHA_BETA_PRUNING);
            this.futilityMargin = AiProfileUtil.getIntProperty(player, AiProps.FUTILITY_MARGIN);
        } else {
            this.maxDepth = DEFAULT_MAX_DEPTH;
            this.timeLimitMs = 5000;
            this.loopDetectionEnabled = false;
            this.useTranspositionTable = false;
            this.alphaBetaEnabled = false;
            this.futilityMargin = 300;
        }

        // Initialize enhanced components if enabled
        this.stateHasher = loopDetectionEnabled ? new GameStateHasher() : null;
        this.transpositionTable = useTranspositionTable ? new TranspositionTable() : null;

        // Initialize alpha stack with the initial game score
        this.alphaStack = new ArrayList<>();
        this.alphaStack.add(score.value);
    }
    
    private int getRecursionDepth() {
        return scoreStack.size() - 1;
    }

    /**
     * Gets the current recursion depth (public accessor for move ordering).
     * @return the current depth in the search tree
     */
    public int getDepth() {
        return getRecursionDepth();
    }

    public boolean shouldRecurse() {
        // Don't recurse if we've already found a winning move
        if (bestScore.value == Integer.MAX_VALUE) {
            return false;
        }

        // Check depth limit
        if (getRecursionDepth() >= maxDepth) {
            return false;
        }

        // Check time limit
        if (System.currentTimeMillis() - startTimeMs > timeLimitMs) {
            return false;
        }

        return true;
    }

    /**
     * Checks if the given game state represents a potential infinite loop.
     * Only effective when loop detection is enabled in the profile.
     *
     * @param game the game state to check
     * @return true if this state has been seen before (potential loop)
     */
    public boolean isLoopDetected(Game game) {
        if (stateHasher == null || aiPlayer == null) {
            return false;
        }
        return stateHasher.hasSeenState(game, aiPlayer);
    }

    /**
     * Gets the transposition table entry for a game state, if available.
     *
     * @param game the game state
     * @return the cached entry, or null if not found or TT is disabled
     */
    public TranspositionTable.TTEntry probeTranspositionTable(Game game) {
        if (transpositionTable == null || aiPlayer == null) {
            return null;
        }
        long hash = stateHasher != null ? stateHasher.computeHash(game, aiPlayer) : 0;
        return transpositionTable.probeForDepth(hash, getRecursionDepth());
    }

    /**
     * Stores a score in the transposition table.
     *
     * @param game the game state
     * @param score the evaluated score
     * @param type the entry type (exact, lower bound, upper bound)
     */
    public void storeInTranspositionTable(Game game, Score score, TranspositionTable.EntryType type) {
        if (transpositionTable == null || aiPlayer == null || stateHasher == null) {
            return;
        }
        long hash = stateHasher.computeHash(game, aiPlayer);
        transpositionTable.store(hash, score, getRecursionDepth(), type);
    }

    /**
     * Gets the configured maximum depth for this controller.
     * @return the maximum recursion depth
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Gets the AI player associated with this controller.
     * @return the AI player, or null if using defaults
     */
    public Player getAiPlayer() {
        return aiPlayer;
    }

    /**
     * Returns whether alpha-beta pruning is enabled.
     */
    public boolean isAlphaBetaEnabled() {
        return alphaBetaEnabled;
    }

    /**
     * Gets the best score found so far at the current depth level.
     */
    public int getAlpha() {
        return alphaStack.get(alphaStack.size() - 1);
    }

    /**
     * Gets the parent level's best score (used as soft beta bound for child).
     * Returns MAX_VALUE if at root level (no parent).
     */
    public int getParentAlpha() {
        if (alphaStack.size() < 2) return Integer.MAX_VALUE;
        return alphaStack.get(alphaStack.size() - 2);
    }

    /**
     * Updates the best score at the current depth level.
     */
    public void updateAlpha(int scoreValue) {
        int idx = alphaStack.size() - 1;
        if (scoreValue > alphaStack.get(idx)) {
            alphaStack.set(idx, scoreValue);
        }
    }

    /**
     * Checks if recursion should be skipped for a move with the given base score
     * (futility pruning). If the base score is far below the current best at this
     * depth level, deeper search is unlikely to make this move competitive.
     *
     * @param baseScoreValue the evaluation score after simulating the move (before recursion)
     * @return true if recursion should be skipped for this move
     */
    public boolean shouldSkipRecursion(int baseScoreValue) {
        if (!alphaBetaEnabled) {
            return false;
        }
        int alpha = getAlpha();
        // Only apply futility pruning if we have a meaningful alpha (not the initial value)
        // and the base score is far below it
        return baseScoreValue + futilityMargin < alpha;
    }

    /**
     * Checks if we should stop evaluating more candidates at the current depth
     * (soft beta cutoff). At depth >= 2, once we find a score that beats the
     * parent's best, we've proven this branch is competitive and can stop.
     *
     * Only applied at depth >= 2 to avoid inaccuracy at the critical depth-1
     * level that directly feeds root decisions.
     *
     * @return true if remaining candidates should be pruned
     */
    public boolean shouldBetaCutoff() {
        if (!alphaBetaEnabled) {
            return false;
        }
        // Only apply at depth >= 2 to preserve accuracy near the root
        if (getRecursionDepth() < 2) {
            return false;
        }
        return getAlpha() >= getParentAlpha();
    }

    public Plan.Decision getLastDecision() {
        if (currentStack.isEmpty()) {
            return null;
        }
        return currentStack.get(currentStack.size() - 1);
    }

    private Score getCurrentScore() {
        return scoreStack.get(scoreStack.size() - 1);
    }

    public void evaluateSpellAbility(List<SpellAbility> saList, int saIndex) {
        currentStack.add(new Plan.Decision(getCurrentScore(), getLastDecision(), new Plan.SpellAbilityRef(saList, saIndex)));
    }

    public void evaluateCardChoice(Card choice) {
        currentStack.add(new Plan.Decision(getCurrentScore(), getLastDecision(), choice));
    }

    public void evaluateChosenModes(int[] chosenModes, String modesStr) {
        currentStack.add(new Plan.Decision(getCurrentScore(), getLastDecision(), chosenModes, modesStr));
    }

    public void evaluateTargetChoices(SpellAbility sa, MultiTargetSelector.Targets targets) {
        currentStack.add(new Plan.Decision(getCurrentScore(), getLastDecision(), targets));
    }

    public void doneEvaluating(Score score) {
        // if we're here during a deeper level this hasn't been called for the level above yet
        // in such case we need to check that this decision has really lead to the improvement in score
        if (getLastDecision().initialScore.value < score.value && score.value > bestScore.value) {
            bestScore = score;
            bestSequence = getLastDecision();
        }
        currentStack.remove(currentStack.size() - 1);
    }

    public Score getBestScore() {
        return bestScore;
    }

    public Plan getBestPlan() {
        if (!currentStack.isEmpty()) {
            throw new RuntimeException("getBestPlan() expects currentStack to be empty!");
        }

        ArrayList<Plan.Decision> sequence = new ArrayList<>();
        Plan.Decision current = bestSequence;
        while (current != null) {
            sequence.add(current);
            current = current.prevDecision;
        }
        Collections.reverse(sequence);
        // Merge targets & choices into their parents.
        int writeIndex = 0;
        for (int i = 0; i < sequence.size(); i++) {
            Plan.Decision d = sequence.get(i);
            if (d.saRef != null) {
                sequence.set(writeIndex, d);
                writeIndex++;
            } else if (d.targets != null) {
                sequence.get(writeIndex - 1).targets = d.targets;
            } else if (d.choices != null) {
                Plan.Decision to = sequence.get(writeIndex - 1);
                if (to.choices == null) {
                    to.choices = new ArrayList<>();
                }
                to.choices.addAll(d.choices);
            } else if (d.modes != null) {
                sequence.get(writeIndex - 1).modes = d.modes;
                sequence.get(writeIndex - 1).modesStr = d.modesStr;
            }
        }
        sequence.subList(writeIndex, sequence.size()).clear();
        return new Plan(sequence, getBestScore());
    }

    private Plan.Decision getLastMergedDecision() {
        MultiTargetSelector.Targets targets = null;
        List<String> choices = new ArrayList<>();
        int[] modes = null;
        String modesStr = null;

        Plan.Decision d = currentStack.get(currentStack.size() - 1);
        while (d.saRef == null) {
            if (d.targets != null) {
                targets = d.targets;
            } else if (d.choices != null) {
                // Since we're iterating backwards, add to the front.
                choices.addAll(0, d.choices);
            } else if (d.modes != null) {
                modes = d.modes;
                modesStr = d.modesStr;
            }
            d = d.prevDecision;
        }

        Plan.Decision merged  = new Plan.Decision(d.initialScore, d.prevDecision, d.saRef);
        merged.targets = targets;
        if (!choices.isEmpty()) {
            merged.choices = choices;
        }
        merged.modes = modes;
        merged.modesStr = modesStr;
        merged.xMana = d.xMana;
        return merged;
    }

    public void push(SpellAbility sa, Score score, GameSimulator simulator) {
        GameSimulator.debugPrint("Recursing DEPTH=" + getRecursionDepth());
        GameSimulator.debugPrint("  With: " + sa);
        scoreStack.add(score);
        simulatorStack.add(simulator);
        // Push new alpha for child level, initialized to the base score (doing nothing more)
        alphaStack.add(score.value);
    }

    public void pop(Score score, SpellAbility nextSa) {
        scoreStack.remove(scoreStack.size() - 1);
        simulatorStack.remove(simulatorStack.size() - 1);
        alphaStack.remove(alphaStack.size() - 1);
        GameSimulator.debugPrint("DEPTH"+getRecursionDepth()+" best score " + score + " " + nextSa);
    }

    public GameObject[] getOriginalHostCardAndTarget(SpellAbility sa) {
        SpellAbility saOrSubSa = sa;
        while (saOrSubSa != null && !saOrSubSa.usesTargeting()) {
            saOrSubSa = saOrSubSa.getSubAbility();
        }

        if (saOrSubSa == null || saOrSubSa.getTargets() == null || saOrSubSa.getTargets().size() != 1) {
            return null;
        }
        GameObject target = saOrSubSa.getTargets().get(0);
        GameObject originalTarget = target;
        if (!(target instanceof Card)) {  return null; }
        Card hostCard = sa.getHostCard();
        for (int i = simulatorStack.size() - 1; i >= 0; i--) {
            if (target == null || hostCard == null) {
                // This could happen when evaluating something that couldn't exist
                // in the original game - for example, targeting a token that came
                // into being as a result of simulating something earlier. Unfortunately,
                // we can't cache this case.
                return null;
            }
            GameCopier copier = simulatorStack.get(i).getGameCopier();
            if (copier.getCopiedGame() != hostCard.getGame()) {
                throw new RuntimeException("Expected hostCard and copier game to match!");
            }
            if (copier.getCopiedGame() != ((Card) target).getGame()) {
                throw new RuntimeException("Expected target and copier game to match!");
            }
            target = copier.reverseFind(target);
            hostCard = (Card) copier.reverseFind(hostCard);
        }
        return new GameObject[] { hostCard, target, originalTarget };
    }

    public void setHostAndTarget(SpellAbility sa, GameSimulator simulator) {
        simulatorStack.add(simulator);
        currentHostAndTarget = getOriginalHostCardAndTarget(sa);
        simulatorStack.remove(simulatorStack.size() - 1);
    }

    public Score shouldSkipTarget(SpellAbility sa, GameSimulator simulator) {
        simulatorStack.add(simulator);
        GameObject[] hostAndTarget = getOriginalHostCardAndTarget(sa);
        simulatorStack.remove(simulatorStack.size() - 1);
        if (hostAndTarget != null) {
            String saString = sa.toString();
            for (CachedEffect effect : effectCache) {
                if (effect.hostCard == hostAndTarget[0] && effect.target == hostAndTarget[1] && effect.sa.equals(saString)) {
                    GameStateEvaluator evaluator = new GameStateEvaluator();
                    Player player = sa.getActivatingPlayer();
                    int cardScore = evaluator.evalCard(player.getGame(), player, (Card) hostAndTarget[2]);
                    if (cardScore == effect.targetScore) {
                        Score currentScore = getCurrentScore();
                        // TODO: summonSick score?
                        return new Score(currentScore.value + effect.scoreDelta, currentScore.summonSickValue);
                    }
                }
            }
        }
        return null;
    }

    public void possiblyCacheResult(Score score, SpellAbility sa) {
        String cached = "";

        // TODO: Why is the check below needed by tests?
        if (!currentStack.isEmpty()) {
            Plan.Decision d = currentStack.get(currentStack.size() - 1);
            int scoreDelta = score.value - d.initialScore.value;
            // Needed to make sure below is only executed when target decisions are ended.
            // Also, only cache negative effects - so that in those cases we don't need to
            // recurse.
            if (scoreDelta <= 0 && d.targets != null) {
                // FIXME: Support more than one target in this logic.
                GameObject[] hostAndTarget = currentHostAndTarget;
                if (currentHostAndTarget != null) {
                    GameStateEvaluator evaluator = new GameStateEvaluator();
                    Player player = sa.getActivatingPlayer();
                    int cardScore = evaluator.evalCard(player.getGame(), player, (Card) hostAndTarget[2]);
                    effectCache.add(new CachedEffect(hostAndTarget[0], sa, hostAndTarget[1], cardScore, scoreDelta));
                    cached = " (added to cache)";
                }
            }
        }

        currentHostAndTarget = null;
        printState(score, sa, cached, true);
    }

    public void printState(Score score, SpellAbility origSa, String suffix, boolean useStack) {
        if (!DEBUG) {
            return;
        }

        int recursionDepth = getRecursionDepth();
        for (int i = 0; i < recursionDepth; i++)
            System.err.print("  ");
        String str;
        if (useStack && !currentStack.isEmpty()) {
            str = getLastMergedDecision().toString(true);
        } else {
            str = SpellAbilityPicker.abilityToString(origSa);
        }
        System.err.println(recursionDepth + ": [" + score.value + "] " + str + suffix);
    }
}
