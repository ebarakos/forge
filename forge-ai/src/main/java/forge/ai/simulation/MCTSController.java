package forge.ai.simulation;

import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.ComputerUtil;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.List;

/**
 * Monte Carlo Tree Search controller for AI decision making.
 *
 * EXPERIMENTAL: Benchmarks show MCTS underperforms the minimax/alpha-beta
 * Simulation profile significantly (4% vs 70% win rate for Burn vs Default).
 * Root cause: game copy cost (~30-80ms) limits iterations to 30-200 per decision,
 * which is insufficient for MCTS convergence. The static evaluation function also
 * undervalues delayed effects (face damage in Burn) at depth 1.
 *
 * The Simulation profile with depth-8 minimax remains the recommended approach.
 * This MCTS implementation is retained for future experimentation if game copy
 * performance improves or a hybrid MCTS+minimax leaf evaluation is attempted.
 *
 * Each iteration:
 * 1. SELECT: Walk down the tree using UCB1 until reaching an expandable node
 * 2. EXPAND: Copy the game, replay action path, expand one new child
 * 3. ROLLOUT: Play rolloutDepth moves using heuristic AI on same copy
 * 4. BACKPROPAGATE: Update visit counts and rewards up the tree
 *
 * Thread safety: Each MCTSController instance is used by a single thread.
 * Game copies per iteration are isolated. No shared mutable state.
 */
public class MCTSController {
    // Configuration
    private final int maxIterations;
    private final double explorationConstant;
    private final int rolloutDepth;
    private final long timeLimitMs;

    // State
    private final Game rootGame;
    private final Player aiPlayer;
    private final Score origScore;
    private final GameStateEvaluator evaluator;

    // Score normalization scale (controls sigmoid steepness).
    // Typical score differences between actions are 50-300 points.
    // Smaller SCALE = steeper sigmoid = better differentiation.
    private static final double SCORE_SCALE = 150.0;

    // Early termination: if one child has this fraction of visits after minimum iterations, stop
    private static final int EARLY_TERMINATION_MIN_ITERATIONS = 50;
    private static final double EARLY_TERMINATION_THRESHOLD = 0.80;

    // Statistics
    private int totalIterations = 0;
    private long totalTimeMs = 0;

    public MCTSController(Game game, Player player, Score origScore) {
        this.rootGame = game;
        this.aiPlayer = player;
        this.origScore = origScore;
        this.evaluator = new GameStateEvaluator();
        evaluator.setComboStateBonusFromProfile(player);

        this.maxIterations = AiProfileUtil.getIntProperty(player, AiProps.MCTS_ITERATIONS);
        this.explorationConstant = AiProfileUtil.getDoubleProperty(player, AiProps.MCTS_EXPLORATION_CONSTANT);
        this.rolloutDepth = AiProfileUtil.getIntProperty(player, AiProps.MCTS_ROLLOUT_DEPTH);
        this.timeLimitMs = AiProfileUtil.getIntProperty(player, AiProps.SIMULATION_TIME_LIMIT_MS);
    }

    /**
     * Run MCTS and return the best action to play.
     * Returns null if no action improves over passing.
     */
    public SpellAbility findBestAction(List<SpellAbility> candidateSAs) {
        if (candidateSAs.isEmpty()) {
            return null;
        }

        // Build root actions from candidates
        List<MCTSAction> rootActions = new ArrayList<>(candidateSAs.size() + 1);
        for (int i = 0; i < candidateSAs.size(); i++) {
            rootActions.add(MCTSAction.fromSpellAbility(i, candidateSAs.get(i)));
        }
        rootActions.add(MCTSAction.PASS);

        // Initialize root node
        MCTSNode root = new MCTSNode(null, null);
        root.setLegalActions(rootActions);

        long startTime = System.currentTimeMillis();
        int iterations = 0;

        while (iterations < maxIterations) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > timeLimitMs) {
                break;
            }

            try {
                runIteration(root);
            } catch (Exception e) {
                // Non-fatal: game copy or simulation error. Skip this iteration.
                System.err.println("MCTS iteration " + iterations + " failed: " + e.getMessage());
            }
            iterations++;

            // Early termination check
            if (iterations >= EARLY_TERMINATION_MIN_ITERATIONS && shouldTerminateEarly(root)) {
                break;
            }
        }

        totalIterations = iterations;
        totalTimeMs = System.currentTimeMillis() - startTime;

        // Log summary
        logResults(root, candidateSAs);

        // Select best action: prefer non-PASS when Q-values are close
        MCTSNode bestChild = root.bestChild();
        if (bestChild == null) {
            return null;
        }

        // If PASS is "best" by visit count, check if any action has comparable Q
        if (bestChild.getAction().isPass()) {
            MCTSNode bestAction = null;
            for (MCTSNode child : root.getChildren()) {
                if (!child.getAction().isPass() && child.getVisitCount() > 0) {
                    // Prefer action over PASS if Q is within 0.03 (tiebreaker: play > pass)
                    if (child.getMeanReward() >= bestChild.getMeanReward() - 0.03) {
                        if (bestAction == null || child.getMeanReward() > bestAction.getMeanReward()) {
                            bestAction = child;
                        }
                    }
                }
            }
            if (bestAction != null) {
                bestChild = bestAction;
            } else {
                return null; // PASS is clearly best
            }
        }

        // If best action's Q is very low (< 0.35), PASS is probably better
        if (bestChild.getMeanReward() < 0.35) {
            return null;
        }

        return bestChild.getAction().findInCandidates(candidateSAs);
    }

    /**
     * Single MCTS iteration: select, expand, rollout, backpropagate.
     */
    private void runIteration(MCTSNode root) {
        // 1. SELECTION: walk tree via UCB1 to an expandable node
        MCTSNode node = select(root);

        // 2. EXPANSION: copy game, replay path, expand one child
        ExpandResult result = expand(node);
        if (result == null) {
            // Expansion failed -- backpropagate neutral reward
            node.backpropagate(0.5);
            return;
        }

        MCTSNode expandedNode = result.expandedNode;
        double reward;

        if (expandedNode.isTerminal()) {
            // 3a. Terminal node: use the terminal score directly
            reward = normalizeScore(expandedNode.getTerminalScore());
        } else {
            // 3b. ROLLOUT: heuristic playout on the sim game
            reward = rollout(result.simGame, result.simAiPlayer);
        }

        // 4. BACKPROPAGATION
        expandedNode.backpropagate(reward);
    }

    /**
     * SELECTION: Walk down tree using UCB1 until reaching a node
     * that is not fully expanded or is terminal.
     */
    private MCTSNode select(MCTSNode node) {
        while (node.isFullyExpanded() && !node.getChildren().isEmpty() && !node.isTerminal()) {
            node = node.selectChild(explorationConstant);
        }
        return node;
    }

    /**
     * EXPANSION: Copy the game, replay action path to reach this node,
     * then expand one new child action.
     */
    private ExpandResult expand(MCTSNode node) {
        if (node.isTerminal()) {
            return new ExpandResult(node, null, null);
        }

        // Get the action path from root to this node
        List<MCTSAction> actionPath = node.getActionPath();

        // Copy the game
        GameCopier copier = new GameCopier(rootGame);
        Game gameCopy = copier.makeCopy();
        Player aiCopy = gameCopy.getPlayer(aiPlayer.getId());
        if (aiCopy == null) {
            return null;
        }

        // Replay actions to reach this node's state
        if (!replayActions(gameCopy, aiCopy, actionPath, copier)) {
            return null;
        }

        // If game is over after replay, mark as terminal
        if (gameCopy.isGameOver()) {
            MCTSAction nextAction = node.getNextUnexpandedAction();
            if (nextAction == null) return null;
            MCTSNode child = node.expand(nextAction);
            Score score = evaluator.getScoreForGameState(gameCopy, aiCopy);
            child.setTerminal(true);
            child.setTerminalScore(score.value);
            return new ExpandResult(child, gameCopy, aiCopy);
        }

        // Discover legal actions at this node if not yet known
        if (!node.hasLegalActions()) {
            SpellAbilityPicker picker = new SpellAbilityPicker(gameCopy, aiCopy);
            List<SpellAbility> candidates = picker.getCandidateSpellsAndAbilities();
            List<MCTSAction> actions = new ArrayList<>(candidates.size() + 1);
            for (int i = 0; i < candidates.size(); i++) {
                actions.add(MCTSAction.fromSpellAbility(i, candidates.get(i)));
            }
            actions.add(MCTSAction.PASS);
            node.setLegalActions(actions);
        }

        // Get the next unexpanded action
        MCTSAction nextAction = node.getNextUnexpandedAction();
        if (nextAction == null) {
            return null;
        }

        // Expand the child
        MCTSNode child = node.expand(nextAction);

        if (nextAction.isPass()) {
            // Passing: not terminal -- rollout will evaluate what happens next
            return new ExpandResult(child, gameCopy, aiCopy);
        }

        // Simulate the action on the game copy
        SpellAbilityPicker picker = new SpellAbilityPicker(gameCopy, aiCopy);
        List<SpellAbility> candidates = picker.getCandidateSpellsAndAbilities();
        SpellAbility sa = nextAction.findInCandidates(candidates);

        if (sa == null) {
            // Action not found in copy -- treat as neutral (game copy mismatch)
            child.setTerminal(true);
            child.setTerminalScore(origScore.value);
            return new ExpandResult(child, gameCopy, aiCopy);
        }

        // Play the spell ability
        if (!playAction(gameCopy, aiCopy, sa)) {
            // Action failed to execute -- treat as neutral
            child.setTerminal(true);
            child.setTerminalScore(origScore.value);
            return new ExpandResult(child, gameCopy, aiCopy);
        }

        // Check if game ended
        if (gameCopy.isGameOver()) {
            Score score = evaluator.getScoreForGameState(gameCopy, aiCopy);
            child.setTerminal(true);
            child.setTerminalScore(score.value);
        }

        return new ExpandResult(child, gameCopy, aiCopy);
    }

    /**
     * ROLLOUT: From the given game state, play rolloutDepth moves using
     * the heuristic AI, then evaluate with GameStateEvaluator.
     */
    private double rollout(Game simGame, Player simAiPlayer) {
        heuristicPlayOut(simGame, simAiPlayer, rolloutDepth);
        Score score = evaluator.getScoreForGameState(simGame, simAiPlayer);
        return normalizeScore(score.value);
    }

    /**
     * Replay a sequence of actions on a game copy to reconstruct a game state.
     * Returns true if replay succeeded.
     */
    private boolean replayActions(Game gameCopy, Player aiCopy,
                                  List<MCTSAction> actions, GameCopier copier) {
        for (MCTSAction action : actions) {
            if (action.isPass()) {
                continue;
            }
            if (gameCopy.isGameOver()) {
                return false;
            }

            SpellAbilityPicker picker = new SpellAbilityPicker(gameCopy, aiCopy);
            List<SpellAbility> candidates = picker.getCandidateSpellsAndAbilities();
            SpellAbility sa = action.findInCandidates(candidates);

            if (sa == null) {
                return false;
            }

            if (!playAction(gameCopy, aiCopy, sa)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Play a spell ability on the game and resolve the stack.
     */
    private boolean playAction(Game game, Player aiPlayer, SpellAbility sa) {
        try {
            if (sa.isLandAbility()) {
                Card hostCard = sa.getHostCard();
                return aiPlayer.playLand(hostCard, false, sa);
            }

            sa.setActivatingPlayer(aiPlayer);
            boolean success = ComputerUtil.handlePlayingSpellAbility(aiPlayer, sa, game);
            if (!success) {
                return false;
            }

            // Resolve the stack
            Player opponent = aiPlayer.getWeakestOpponent();
            if (opponent != null) {
                GameSimulator.resolveStack(game, opponent);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Perform a heuristic rollout: play moves for both AI and opponent,
     * using simple heuristic ordering (lands first, then by CMC descending).
     * Uses SpellAbilityPicker.getCandidateSpellsAndAbilities() directly
     * to avoid recursive MCTS calls through AiController.
     */
    private void heuristicPlayOut(Game simGame, Player simAiPlayer, int depth) {
        Player opponent = simAiPlayer.getWeakestOpponent();
        for (int i = 0; i < depth && !simGame.isGameOver(); i++) {
            // AI plays a move
            boolean aiPlayed = tryPlayBestCandidate(simGame, simAiPlayer);
            if (simGame.isGameOver()) break;

            // Opponent plays a move
            boolean oppPlayed = false;
            if (opponent != null) {
                oppPlayed = tryPlayBestCandidate(simGame, opponent);
            }

            // If neither side can play, stop the rollout
            if (!aiPlayed && !oppPlayed) {
                break;
            }
        }
    }

    /**
     * Try to play the best-looking candidate for a player.
     * Heuristic: lands first, then highest CMC spell.
     */
    private boolean tryPlayBestCandidate(Game simGame, Player player) {
        try {
            SpellAbilityPicker picker = new SpellAbilityPicker(simGame, player);
            List<SpellAbility> candidates = picker.getCandidateSpellsAndAbilities();
            if (candidates.isEmpty()) {
                return false;
            }
            SpellAbility best = selectBestHeuristic(candidates);
            return playAction(simGame, player, best);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Select the best candidate using a simple heuristic:
     * prefer lands, then highest CMC spell (bigger = more impactful).
     */
    private SpellAbility selectBestHeuristic(List<SpellAbility> candidates) {
        SpellAbility bestLand = null;
        SpellAbility bestSpell = null;
        int bestCmc = -1;

        for (SpellAbility sa : candidates) {
            if (sa.isLandAbility()) {
                bestLand = sa;
            } else {
                int cmc = sa.getHostCard() != null ? sa.getHostCard().getCMC() : 0;
                if (cmc > bestCmc) {
                    bestCmc = cmc;
                    bestSpell = sa;
                }
            }
        }

        // Prefer playing a land (free action), then best spell
        if (bestLand != null) return bestLand;
        if (bestSpell != null) return bestSpell;
        return candidates.get(0);
    }

    /**
     * Normalize a GameStateEvaluator score to [0,1] range for MCTS.
     * Uses sigmoid centered on the original game score.
     */
    private double normalizeScore(int scoreValue) {
        if (scoreValue == Integer.MAX_VALUE) return 1.0;
        if (scoreValue == Integer.MIN_VALUE) return 0.0;
        double relativeScore = scoreValue - origScore.value;
        return 1.0 / (1.0 + Math.exp(-relativeScore / SCORE_SCALE));
    }

    /**
     * Check if one root child dominates and we can stop early.
     */
    private boolean shouldTerminateEarly(MCTSNode root) {
        if (root.getChildren().size() < 2) return false;
        MCTSNode best = root.bestChild();
        if (best == null) return false;
        int totalVisits = root.getVisitCount();
        return totalVisits > 0 && (double) best.getVisitCount() / totalVisits >= EARLY_TERMINATION_THRESHOLD;
    }

    /**
     * Build a Plan from the MCTS result for compatibility with SpellAbilityPicker.
     */
    public Plan buildPlan(SpellAbility bestSA, List<SpellAbility> candidateSAs, Score origScore) {
        int saIndex = candidateSAs.indexOf(bestSA);
        if (saIndex < 0) {
            // Find by description match
            String desc = bestSA.toString();
            for (int i = 0; i < candidateSAs.size(); i++) {
                if (candidateSAs.get(i).toString().equals(desc)) {
                    saIndex = i;
                    break;
                }
            }
        }
        if (saIndex < 0) return null;

        Plan.SpellAbilityRef saRef = new Plan.SpellAbilityRef(candidateSAs, saIndex);
        Plan.Decision decision = new Plan.Decision(origScore, null, saRef);
        ArrayList<Plan.Decision> decisions = new ArrayList<>();
        decisions.add(decision);
        return new Plan(decisions, origScore);
    }

    /**
     * Log MCTS results for debugging and benchmarking.
     * Uses System.out because System.err is redirected to null during sim mode.
     */
    private void logResults(MCTSNode root, List<SpellAbility> candidateSAs) {
        System.err.println("MCTS: " + totalIterations + " iterations in " + totalTimeMs + "ms"
                + " (" + (totalIterations > 0 ? totalTimeMs / totalIterations : 0) + "ms/iter)"
                + " origScore=" + origScore.value);
        MCTSNode bestChild = root.bestChild();
        for (MCTSNode child : root.getChildren()) {
            MCTSAction action = child.getAction();
            String name = action.isPass() ? "PASS" : SpellAbilityPicker.abilityToString(
                    action.findInCandidates(candidateSAs), false);
            String marker = (child == bestChild) ? " <-- BEST" : "";
            System.err.printf("  %s: N=%d Q=%.4f%s%n", name, child.getVisitCount(), child.getMeanReward(), marker);
        }
    }

    public int getTotalIterations() { return totalIterations; }
    public long getTotalTimeMs() { return totalTimeMs; }

    /** Result of an expand operation. */
    private static class ExpandResult {
        final MCTSNode expandedNode;
        final Game simGame;
        final Player simAiPlayer;

        ExpandResult(MCTSNode expandedNode, Game simGame, Player simAiPlayer) {
            this.expandedNode = expandedNode;
            this.simGame = simGame;
            this.simAiPlayer = simAiPlayer;
        }
    }
}
