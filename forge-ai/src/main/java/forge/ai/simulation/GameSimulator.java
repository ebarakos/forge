package forge.ai.simulation;


import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.util.MyRandom;
import forge.util.collect.FCollectionView;

import java.util.*;

public class GameSimulator {
    public static boolean COPY_STACK = false;
    final private SimulationController controller;
    private GameCopier copier;
    private Game simGame;
    private Player aiPlayer;
    private GameStateEvaluator eval;
    private List<String> origLines;
    private Score origScore;
    private SpellAbilityChoicesIterator interceptor;

    public GameSimulator(SimulationController controller, Game origGame, Player origAiPlayer, PhaseType advanceToPhase) {
        this.controller = controller;
        copier = new GameCopier(origGame);
        simGame = copier.makeCopy(advanceToPhase, origAiPlayer);

        aiPlayer = (Player) copier.find(origAiPlayer);
        eval = new GameStateEvaluator();
        // Set combo state bonus from player's profile
        eval.setComboStateBonusFromProfile(aiPlayer);

        origLines = new ArrayList<>();
        debugLinesTL.set(origLines);

        debugPrintTL.set(false);
        // The eval embeds an upcoming-combat simulation whose block/attack choices
        // draw from MyRandom. The copy-sanity check compares two separate evals,
        // so both must see an identical RNG stream or they diverge spuriously
        // (observed: opponent life off by one between orig and copy eval).
        // Restore the caller's Random afterwards: direct constructions (e.g.
        // LLMSpellSelection.computeEvalDeltas) must not leak the fixed seed
        // into the rest of the game on this thread.
        Random callerRandom = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(EVAL_SANITY_SEED));
            origScore = eval.getScoreForGameState(origGame, origAiPlayer);

            if (advanceToPhase == null) {
                ensureGameCopyScoreMatches(origGame, origAiPlayer);
            }

            // If the stack on the original game is not empty, resolve it
            // first and get the updated eval score, since this is what we'll
            // want to compare to the eval score after simulating.
            if (COPY_STACK && !origGame.getStackZone().isEmpty()) {
                origLines = new ArrayList<>();
                debugLinesTL.set(origLines);
                Game copyOrigGame = copier.makeCopy();
                Player copyOrigAiPlayer = copyOrigGame.getPlayers().get(1);
                resolveStack(copyOrigGame, copyOrigGame.getPlayers().get(0));
                origScore = eval.getScoreForGameState(copyOrigGame, copyOrigAiPlayer);
            }
        } finally {
            MyRandom.setRandom(callerRandom);
        }

        debugPrintTL.set(false);
        debugLinesTL.set(null);
    }

    private void ensureGameCopyScoreMatches(Game origGame, Player origAiPlayer) {
        eval.setDebugging(true);
        List<String> simLines = new ArrayList<>();
        debugLinesTL.set(simLines);
        MyRandom.setRandom(new Random(EVAL_SANITY_SEED));
        Score simScore = eval.getScoreForGameState(simGame, aiPlayer);
        if (!simScore.equals(origScore)) {
            // Re-eval orig with debug printing.
            origLines = new ArrayList<>();
            debugLinesTL.set(origLines);
            MyRandom.setRandom(new Random(EVAL_SANITY_SEED));
            eval.getScoreForGameState(origGame, origAiPlayer);
            // Print debug info.
            printDiff(origLines, simLines);
            // make sure it gets printed
            System.out.flush();
            // Carry the diff in the exception too: in parallel sim runs stdout is
            // nulled, so the message is the only place the diff survives.
            throw new RuntimeException("Game copy error (orig=" + origScore.value + " copy=" + simScore.value
                    + "). Diff: " + diffSummary(origLines, simLines));
        }
        eval.setDebugging(false);
    }

    public void setInterceptor(SpellAbilityChoicesIterator interceptor) {
        this.interceptor = interceptor;
        ((PlayerControllerAi) aiPlayer.getController()).getAi().getSimulationPicker().setInterceptor(interceptor);
    }


    /** Bounded one-line diff (orig-only lines as -x, copy-only as +x) for exception messages. */
    private static String diffSummary(List<String> lines1, List<String> lines2) {
        List<String> a = new ArrayList<>(lines1);
        List<String> b = new ArrayList<>(lines2);
        Collections.sort(a);
        Collections.sort(b);
        StringBuilder sb = new StringBuilder();
        int i = 0, j = 0, shown = 0;
        while ((i < a.size() || j < b.size()) && shown < 12) {
            int cmp = i >= a.size() ? 1 : j >= b.size() ? -1 : a.get(i).compareTo(b.get(j));
            if (cmp == 0) { i++; j++; continue; }
            if (cmp < 0) { sb.append(" -").append(a.get(i++)); }
            else { sb.append(" +").append(b.get(j++)); }
            shown++;
        }
        return sb.length() == 0 ? "(no line diff; score-only divergence)" : sb.toString();
    }

    private void printDiff(List<String> lines1, List<String> lines2) {
        int i = 0;
        int j = 0;
        Collections.sort(lines1);
        Collections.sort(lines2);
        while (i < lines1.size() && j < lines2.size()) {
            String left = lines1.get(i);
            String right = lines2.get(j);
            int cmp = left.compareTo(right);
            if (cmp == 0) {
                i++; j++;
            } else if (cmp < 0) {
                System.out.println("-" + left);
                i++;
            } else { 
                System.out.println("+"  + right);
                j++;
            }
        }
        while (i < lines1.size()) {
            System.out.println("-" + lines1.get(i++));
        }
        while (j < lines2.size()) {
            System.out.println("+" + lines2.get(j++));
        }
    }

    // ThreadLocal: parallel games each run a GameSimulator on their own thread;
    // shared static debug state raced (concurrent ArrayList.add -> AIOOBE).
    private static final ThreadLocal<Boolean> debugPrintTL = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<List<String>> debugLinesTL = new ThreadLocal<>();
    public static void debugPrint(String str) {
        if (debugPrintTL.get()) {
            System.out.println(str);
        }
        List<String> lines = debugLinesTL.get();
        if (lines != null) {
            lines.add(str);
        }
    }

    // Fixed seed for the paired sanity-check evals; the value is arbitrary.
    private static final long EVAL_SANITY_SEED = 0x5EEDC0DEL;

    private SpellAbility findSaInSimGame(final SpellAbility sa) {
        // is already an ability from sim game
        if (sa.getHostCard().getGame().equals(this.simGame)) {
            return sa;
        }
        Card origHostCard = sa.getHostCard();
        Card hostCard = (Card) copier.find(origHostCard);
        String desc = sa.getDescription();
        FCollectionView<SpellAbility> candidates = hostCard.getSpellAbilities();

        SpellAbility result = saMatcher(candidates, desc);
        for (SpellAbility cSa : candidates) {
            if (result != null) {
                break;
            }
            result = saMatcher(GameActionUtil.getAlternativeCosts(cSa, aiPlayer, true), desc);
        }

        return result;
    }

    private SpellAbility saMatcher(Iterable<SpellAbility> candidates, String desc) {
        // first pass for accuracy (spells with alternative costs)
        for (SpellAbility cSa : candidates) {
            if (desc.equals(cSa.getDescription())) {
                return cSa;
            }
        }
        // fall back for safety
        for (SpellAbility cSa : candidates) {
            if (desc.startsWith(cSa.getDescription())) {
                return cSa;
            }
        }
        return null;
    }

    public Score simulateSpellAbility(SpellAbility origSa) {
        return simulateSpellAbility(origSa, this.eval, true);
    }
    public Score simulateSpellAbility(SpellAbility origSa, boolean resolve) {
        return simulateSpellAbility(origSa, this.eval, resolve);
    }
    public Score simulateSpellAbility(SpellAbility origSa, GameStateEvaluator eval, boolean resolve) {
        SpellAbility sa;
        if (origSa.isLandAbility()) {
            Card hostCard = (Card) copier.find(origSa.getHostCard());
            if (!aiPlayer.playLand(hostCard, false, origSa)) {
                System.err.println("Simulation: Couldn't play land! " + origSa);
            }
            sa = origSa;
        } else {
            // TODO: optimize: prune identical SA (e.g. two of the same card in hand)
            sa = findSaInSimGame(origSa);
            if (sa == null) {
                System.err.println("Simulation: SA not found! " + origSa + " / " + origSa.getClass());
                return new Score(Integer.MIN_VALUE);
            }

            debugPrint("Found SA " + sa + " on host card " + sa.getHostCard() + " with owner:"+ sa.getHostCard().getOwner());
            sa.setActivatingPlayer(aiPlayer);
            SpellAbility origSaOrSubSa = origSa;
            SpellAbility saOrSubSa = sa;
            do {
                if (origSaOrSubSa.usesTargeting()) {
                    final boolean divided = origSaOrSubSa.isDividedAsYouChoose();
                    for (final GameObject o : origSaOrSubSa.getTargets()) {
                        final GameObject target = copier.find(o);
                        saOrSubSa.getTargets().add(target);
                        if (divided) {
                            saOrSubSa.addDividedAllocation(target, origSaOrSubSa.getDividedValue(o));
                        }
                    }
                }
                origSaOrSubSa = origSaOrSubSa.getSubAbility();
                saOrSubSa = saOrSubSa.getSubAbility();
            } while (saOrSubSa != null);

            if (debugPrintTL.get() && !sa.getAllTargetChoices().isEmpty()) {
                debugPrint("Targets: ");
                for (TargetChoices target : sa.getAllTargetChoices()) {
                    System.out.print(target);
                }
                System.out.println();
            }
            final SpellAbility playingSa = sa;
            // Is this right?
            simGame.copyLastState();
            boolean success = ComputerUtil.handlePlayingSpellAbility(aiPlayer, sa, simGame, () -> {
                if (interceptor != null) {
                    interceptor.announceX(playingSa);
                    interceptor.chooseTargets(playingSa, GameSimulator.this);
                }
            });
            if (!success) {
                return new Score(Integer.MIN_VALUE);
            }
        }

        if (resolve) {
            // TODO: Support multiple opponents.
            Player opponent = aiPlayer.getWeakestOpponent();
            resolveStack(simGame, opponent);
        }

        // TODO: If this is during combat, before blockers are declared,
        // we should simulate how combat will resolve and evaluate that
        // state instead!
        List<String> simLines = null;
        if (debugPrintTL.get()) {
            debugPrint("SimGame:");
            simLines = new ArrayList<>();
            debugLinesTL.set(simLines);
            debugPrintTL.set(false);
        }
        Score score = eval.getScoreForGameState(simGame, aiPlayer);
        if (simLines != null) {
            debugLinesTL.set(null);
            debugPrintTL.set(true);
            printDiff(origLines, simLines);
        }
        controller.possiblyCacheResult(score, origSa);
        if (controller.shouldRecurse() && !simGame.isGameOver()
                && !controller.shouldSkipRecursion(score.value)) {
            controller.push(sa, score, this);
            SpellAbilityPicker sim = new SpellAbilityPicker(simGame, aiPlayer);
            SpellAbility nextSa = sim.chooseSpellAbilityToPlay(controller);
            if (nextSa != null) {
                score = sim.getScoreForChosenAbility();
            }
            controller.pop(score, nextSa);
        }

        return score;
    }

    public static void resolveStack(final Game game, final Player opponent) {
        // TODO: This needs to set an AI controller for all opponents, in case of multiplayer.
        PlayerControllerAi sim = new PlayerControllerAi(game, opponent, opponent.getLobbyPlayer());
        sim.setUseSimulation(true);
        opponent.runWithController(() -> {
            final Set<Card> allAffectedCards = new HashSet<>();
            game.getAction().checkStateEffects(false, allAffectedCards);
            game.getStack().addAllTriggeredAbilitiesToStack();
            while (!game.getStack().isEmpty() && !game.isGameOver()) {
                debugPrint("Resolving:" + game.getStack().peekAbility());

                // Resolve the top effect on the stack.
                game.getStack().resolveStack();

                // Evaluate state based effects as a result of resolving stack.
                // Note: Needs to happen after resolve stack rather than at the
                // top of the loop to ensure state effects are evaluated after the
                // last resolved effect
                game.getAction().checkStateEffects(false, allAffectedCards);

                // Add any triggers additional triggers as a result of the above.
                // Must be below state effects, since legendary rule is evaluated
                // as part of state effects and trigger come afterward. (e.g. to
                // correctly handle two Dark Depths - one having no counters).
                game.getStack().addAllTriggeredAbilitiesToStack();

                // Continue until stack is empty.
            }
        }, sim);
    }

    public Game getSimulatedGameState() {
        return simGame;
    }

    public Score getScoreForOrigGame() {
        return origScore;
    }

    public GameCopier getGameCopier() {
        return copier;
    }
}
