package forge.ai.simulation;

import forge.ai.*;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.LearnAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityCondition;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public class SpellAbilityPicker {
    // Policy-guided search: how the heuristic AI's choice is combined with the simulation.
    // FORGE_SIM_POLICY: sim (default) | shadow | veto | hybrid
    private static final String POLICY_MODE = parsePolicyMode(
            System.getProperty("forge.sim.policy", System.getenv("FORGE_SIM_POLICY")));

    private static String parsePolicyMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "sim";
        }
        String mode = raw.trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "sim":
            case "shadow":
            case "veto":
            case "hybrid":
                return mode;
            default:
                System.err.println("[SIM_POLICY] Unknown policy mode '" + raw + "' (forge.sim.policy / FORGE_SIM_POLICY) - falling back to 'sim'.");
                return "sim";
        }
    }

    private Game game;
    private Player player;
    private Score bestScore;
    private boolean printOutput = Boolean.getBoolean("forge.sim.debug");
    private SpellAbilityChoicesIterator interceptor;

    private Plan plan;
    private int numSimulations;
    // Read lazily per decision: at construction time the player's controller is
    // not yet assigned, so profile lookups (which walk getController()) would NPE.
    private int planScoreTolerance = 80;

    // Reused evaluator instance to reduce GC pressure (cleared per decision)
    private final GameStateEvaluator evaluator = new GameStateEvaluator();

    // Move orderer for alpha-beta pruning efficiency
    // ThreadLocal to avoid shared mutable state across parallel simulation threads
    private static final ThreadLocal<MoveOrderer> moveOrderer =
            ThreadLocal.withInitial(MoveOrderer::new);

    public SpellAbilityPicker(Game game, Player player) {
        this.game = game;
        this.player = player;
    }

    public void setInterceptor(SpellAbilityChoicesIterator in) {
        this.interceptor = in;
    }

    private void print(String str) {
        if (printOutput) {
            System.out.println(str);
        }
    }

    private void printPhaseInfo() {
        String phaseStr = game.getPhaseHandler().getPhase().toString();
        if (game.getPhaseHandler().getPlayerTurn() != player) {
            phaseStr = "opponent " + phaseStr;
        }
        print("---- choose ability  (phase = " + phaseStr + ")");
    }

    public List<SpellAbility> getCandidateSpellsAndAbilities() {
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        cards = ComputerUtilCard.dedupeCards(cards);
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        List<SpellAbility> candidateSAs = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player);
        int writeIndex = 0;
        for (SpellAbility sa : candidateSAs) {
            if (sa.isManaAbility()) {
                continue;
            }
            sa.setActivatingPlayer(player);

            AiPlayDecision opinion = canPlayAndPayForSim(sa);
            // print("  " + opinion + ": " + sa);
            // PhaseHandler ph = game.getPhaseHandler();
            // System.out.printf("Ai thinks '%s' of %s -> %s @ %s %s >>> \n", opinion, sa.getHostCard(), sa, Lang.getPossesive(ph.getPlayerTurn().getName()), ph.getPhase());

            if (opinion != AiPlayDecision.WillPlay)
                continue;
            candidateSAs.set(writeIndex, sa);
            writeIndex++;
        }
        candidateSAs.subList(writeIndex, candidateSAs.size()).clear();
        return candidateSAs;
    }

    public SpellAbility chooseSpellAbilityToPlay(SimulationController controller) {
        //printOutput = controller == null;

        // Pass if top of stack is owned by me.
        if (!game.getStack().isEmpty() && game.getStack().peekAbility().getActivatingPlayer().equals(player)) {
            return null;
        }

        evaluator.clearCache();
        evaluator.setComboStateBonusFromProfile(player);
        planScoreTolerance = AiProfileUtil.getIntProperty(player, AiProps.SIM_PLAN_SCORE_TOLERANCE);
        if (GameStateEvaluator.isLearnedMode()) {
            // The learned evaluator scores in win-probability millionths; the
            // profile tolerance is tuned to the linear scale (~hundreds). 250x
            // maps the default 80 to 20,000 = 2pp of win probability.
            planScoreTolerance *= 250;
        }
        Score origGameScore = evaluator.getScoreForGameState(game, player);
        List<SpellAbility> candidateSAs = getCandidateSpellsAndAbilities();
        if (controller != null) {
            // This is a recursion during a higher-level simulation. Just return the head of the best
            // sequence directly, no need to create a Plan object.
            return chooseSpellAbilityToPlayImpl(controller, candidateSAs, origGameScore, null);
        }

        printPhaseInfo();

        if (!POLICY_MODE.equals("sim")) {
            AiController heuristicAi = getHeuristicAi();
            if (heuristicAi != null) {
                try {
                    if (POLICY_MODE.equals("veto")) {
                        return chooseWithVetoPolicy(heuristicAi, origGameScore);
                    }
                    if (POLICY_MODE.equals("hybrid")) {
                        return chooseWithHybridPolicy(heuristicAi, origGameScore, candidateSAs);
                    }
                    return chooseWithShadowPolicy(heuristicAi, origGameScore, candidateSAs);
                } catch (Exception e) {
                    // The policy layer is advisory — it must never end the real game.
                    // Fall back to the heuristic's own choice for this priority.
                    plan = null;
                    System.err.println(policyLog("POLICY_EXC") + " exc=" + e);
                    try {
                        return getHeuristicChoice(heuristicAi);
                    } catch (Exception e2) {
                        System.err.println(policyLog("POLICY_EXC") + " heuristic also failed: " + e2);
                        return null;
                    }
                }
            }
            // No heuristic AI controller available - fall through to plain sim behavior.
        }

        SpellAbility sa = getPlannedSpellAbility(origGameScore, candidateSAs);
        if (sa != null) {
            return sa;
        }
        // If a deferred plan is still active (waiting for its start phase), do not wipe and
        // re-formulate — that would cause flip-flop churn every priority window.
        if (hasActivePlan()) {
            return null;
        }
        createNewPlan(origGameScore, candidateSAs);
        return getPlannedSpellAbility(origGameScore, candidateSAs);
    }

    private AiController getHeuristicAi() {
        PlayerController pc = player.getController();
        if (pc instanceof PlayerControllerAi) {
            return ((PlayerControllerAi) pc).getAi();
        }
        return null;
    }

    /**
     * Asks the heuristic AI for its choice this priority. Returns the first element of the
     * returned list: either a spell/ability with targets already chosen by the heuristic, a
     * land ability, or null (heuristic passes).
     */
    private SpellAbility getHeuristicChoice(AiController heuristicAi) {
        List<SpellAbility> choice = heuristicAi.chooseSpellAbilityToPlayWithoutSim();
        return choice == null || choice.isEmpty() ? null : choice.get(0);
    }

    /**
     * Same-action test: same host card and same SpellAbility string form.
     */
    private static boolean isSameAction(SpellAbility a, SpellAbility b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.getHostCard().equals(b.getHostCard()) && a.toString().equals(b.toString());
    }

    private String policyLog(String event) {
        StringBuilder sb = new StringBuilder("[SIM_POLICY] mode=").append(POLICY_MODE);
        if (event != null) {
            sb.append(' ').append(event);
        }
        sb.append(" player=").append(player.getName())
                .append(" turn=").append(game.getPhaseHandler().getTurn())
                .append(" myTurn=").append(game.getPhaseHandler().getPlayerTurn() == player)
                .append(" phase=").append(game.getPhaseHandler().getPhase());
        return sb.toString();
    }

    private static String policyDesc(SpellAbility sa) {
        return sa == null ? "PASS" : abilityToString(sa, false);
    }

    /**
     * Saves the chosen targets along the ability's sub-ability chain so they can be
     * restored after policy simulations (which require untargeted candidates).
     */
    private static List<TargetChoices> saveTargets(SpellAbility sa) {
        List<TargetChoices> saved = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            saved.add(s.getTargets());
        }
        return saved;
    }

    private static void restoreTargets(SpellAbility sa, List<TargetChoices> saved) {
        if (saved == null) {
            return;
        }
        int i = 0;
        for (SpellAbility s = sa; s != null && i < saved.size(); s = s.getSubAbility()) {
            s.setTargets(saved.get(i++));
        }
    }

    /**
     * Strips targets from candidate abilities before policy simulations. The heuristic
     * consult chooses (and probes) targets on the live SpellAbility objects shared with
     * the candidate list; GameSimulator maps pre-set targets into the game copy, which
     * both skews evaluations and crashes outright on unmappable objects (e.g. spells on
     * the stack). Simulations select their own targets inside the copy.
     */
    private static void clearAllTargets(Iterable<SpellAbility> sas) {
        for (SpellAbility sa : sas) {
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                if (s.usesTargeting()) {
                    s.resetTargets();
                }
            }
        }
    }

    /**
     * True when the ability (or a sub-ability) targets an object on the stack
     * (counterspells and similar responses).
     */
    private static boolean targetsStack(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && s.getTargets() != null) {
                for (GameObject o : s.getTargets()) {
                    if (o instanceof SpellAbility) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * One-step simulated score of playing this single ability now (fresh one-off controller).
     * Returns {@code Integer.MIN_VALUE} when the ability cannot be evaluated; the policy
     * treats that as "no opinion" and plays the heuristic's choice.
     */
    private Score evaluateOneStep(SpellAbility sa, Score origGameScore) {
        // Stack-targeting responses cannot be evaluated: GameCopier does not map stack
        // objects, and a stackless copy has nothing to counter.
        if (targetsStack(sa)) {
            return new Score(Integer.MIN_VALUE);
        }
        try {
            SimulationController oneOff = new SimulationController(origGameScore, player);
            return evaluateSa(oneOff, null, Collections.singletonList(sa), 0);
        } catch (Exception e) {
            // The one-step probe is advisory; a copy/mapping failure must never
            // propagate into (and end) the real game.
            System.err.println(policyLog("SIM_EXC") + " sa=" + policyDesc(sa) + " exc=" + e);
            return new Score(Integer.MIN_VALUE);
        }
    }

    /**
     * Veto check shared by veto and hybrid modes. Returns true when the heuristic's choice
     * simulates far enough below the current game score to be considered a blunder.
     */
    private boolean shouldVetoHeuristicChoice(SpellAbility heurSa, Score heurOneStep, Score origGameScore) {
        if (heurOneStep.value == Integer.MIN_VALUE) {
            // The simulation infrastructure failed to even simulate the ability (e.g. SA not
            // found in the game copy). That is not evidence of a blunder - trust the heuristic.
            System.err.println(policyLog("SIM_FAIL") + " heur=" + policyDesc(heurSa)
                    + " orig=" + origGameScore.value);
            return false;
        }
        if (origGameScore.value <= Integer.MIN_VALUE / 2) {
            // The current state already evaluates as lost (e.g. lethal attackers incoming per
            // the embedded combat sim). Passing guarantees the loss; any heuristic action is
            // at worst equally bad - never veto. (Also avoids the underflow in the margin
            // subtraction below, which once vetoed a +1359 survival line against orig=MIN.)
            return false;
        }
        int vetoMargin = AiProfileUtil.getIntProperty(player, AiProps.SIM_POLICY_VETO_MARGIN);
        if ((long) heurOneStep.value < (long) origGameScore.value - vetoMargin) {
            System.err.println(policyLog("VETO") + " heur=" + policyDesc(heurSa)
                    + " heurScore=" + heurOneStep.value + " orig=" + origGameScore.value);
            return true;
        }
        return false;
    }

    /**
     * Shadow mode: the sim decides exactly as in plain sim mode, but whenever a new plan is
     * formulated the heuristic is consulted as well and the (dis)agreement is logged.
     */
    private SpellAbility chooseWithShadowPolicy(AiController heuristicAi, Score origGameScore, List<SpellAbility> candidateSAs) {
        SpellAbility sa = getPlannedSpellAbility(origGameScore, candidateSAs);
        if (sa != null) {
            return sa;
        }
        if (hasActivePlan()) {
            return null;
        }
        // Consult the heuristic before formulating the plan: getPlannedSpellAbility() below
        // re-selects targets on the chosen ability per the plan, overwriting any targets the
        // heuristic may have set on the same SpellAbility object.
        SpellAbility heurSa = getHeuristicChoice(heuristicAi);
        // The consult chooses/probes targets on live SA objects shared with the candidate
        // list; simulations must start from untargeted candidates (see clearAllTargets).
        clearAllTargets(candidateSAs);
        if (heurSa != null) {
            clearAllTargets(Collections.singletonList(heurSa));
        }
        createNewPlan(origGameScore, candidateSAs);
        SpellAbility simSa = getPlannedSpellAbility(origGameScore, candidateSAs);

        boolean agree = isSameAction(heurSa, simSa);
        String simScoreStr = plan != null ? String.valueOf(plan.getFinalScore().value) : "-";
        String heurScoreStr = "-";
        if (!agree && heurSa != null && !heurSa.isLandAbility()) {
            heurScoreStr = String.valueOf(evaluateOneStep(heurSa, origGameScore).value);
        }
        System.err.println(policyLog(null) + " heur=" + policyDesc(heurSa) + " sim=" + policyDesc(simSa)
                + " agree=" + agree + " orig=" + origGameScore.value
                + " simScore=" + simScoreStr + " heurScore=" + heurScoreStr);
        return simSa;
    }

    /**
     * Veto mode: the heuristic decides; the sim only blocks blunders (one-step score far below
     * the current score). Plans are never retained - every priority re-consults the heuristic.
     */
    private SpellAbility chooseWithVetoPolicy(AiController heuristicAi, Score origGameScore) {
        plan = null;
        SpellAbility heurSa = getHeuristicChoice(heuristicAi);
        if (heurSa == null) {
            return null;
        }
        if (heurSa.isLandAbility()) {
            // Land drops are never vetoed.
            return heurSa;
        }
        Score heurOneStep = evaluateOneStep(heurSa, origGameScore);
        if (shouldVetoHeuristicChoice(heurSa, heurOneStep, origGameScore)) {
            return null;
        }
        // The heuristic already chose its targets - return its choice directly.
        return heurSa;
    }

    /**
     * Hybrid mode: the heuristic proposes and ranks, the sim re-ranks the top-K candidates and
     * may override (with sufficient margin) or veto (blunder check) the heuristic's choice.
     */
    private SpellAbility chooseWithHybridPolicy(AiController heuristicAi, Score origGameScore, List<SpellAbility> candidateSAs) {
        // While a plan is active, follow it exactly as in plain sim mode - the heuristic is
        // not consulted.
        SpellAbility planned = getPlannedSpellAbility(origGameScore, candidateSAs);
        if (planned != null) {
            return planned;
        }
        if (hasActivePlan()) {
            return null;
        }

        SpellAbility heurSa = getHeuristicChoice(heuristicAi);
        if (heurSa != null && heurSa.isLandAbility()) {
            // Lands stay heuristic.
            return heurSa;
        }

        // The sim's lookahead is only trusted in its window of competence: the player's
        // own main phase with an empty stack (proactive sequencing — combat math, lethal
        // lines, deployment order). Reactive decisions (opponent's turn, instant speed,
        // responses on the stack) depend on hidden information and long-horizon values
        // the 1-2 ply evaluator misjudges — benchmarks showed sim overrides there are
        // net-negative for reactive decks. Outside the window, the heuristic decides.
        boolean proactiveWindow = game.getPhaseHandler().getPlayerTurn() == player
                && game.getPhaseHandler().getPhase().isMain()
                && game.getStack().isEmpty();
        if (!proactiveWindow) {
            return heurSa;
        }

        // Save the heuristic's chosen targets, then strip targets everywhere before any
        // simulation: candidates share live SA objects with the heuristic's pick, and
        // GameSimulator maps pre-set targets into the copy (crashes on stack objects,
        // double-targets otherwise). Restored below if the heuristic's pick is played.
        List<TargetChoices> savedHeurTargets = heurSa != null ? saveTargets(heurSa) : null;

        // Prune the sim's candidates to the heuristic's top-K ranking (same ranking the
        // heuristic uses in chooseSpellAbilityToPlayFromList), plus the heuristic's choice.
        int topK = AiProfileUtil.getIntProperty(player, AiProps.SIM_POLICY_TOP_K);
        List<SpellAbility> prunedSAs = new ArrayList<>(candidateSAs);
        try {
            prunedSAs.sort(ComputerUtilAbility.saEvaluator); // put best spells first
            ComputerUtilAbility.sortCreatureSpells(prunedSAs);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
        }
        if (topK > 0 && prunedSAs.size() > topK) {
            prunedSAs.subList(topK, prunedSAs.size()).clear();
        }
        if (heurSa != null) {
            boolean present = false;
            for (SpellAbility candidate : prunedSAs) {
                if (isSameAction(candidate, heurSa)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                prunedSAs.add(heurSa);
            }
        }
        clearAllTargets(candidateSAs);
        if (heurSa != null) {
            clearAllTargets(Collections.singletonList(heurSa));
        }

        // Formulate a plan over the pruned set (keeps the after-blockers deferral logic).
        createNewPlan(origGameScore, prunedSAs);
        Score heurOneStep = null;
        if (plan != null) {
            SpellAbility planRoot = getPlanRootAbility(prunedSAs);
            if (isSameAction(planRoot, heurSa)) {
                // Sim agrees with the heuristic - keep the plan and follow it as today.
                return getPlannedSpellAbility(origGameScore, prunedSAs);
            }
            // Plan root differs from the heuristic's choice: override only with margin.
            // A heuristic pass is scored as keeping the current game score.
            int heurValue = origGameScore.value;
            if (heurSa != null) {
                heurOneStep = evaluateOneStep(heurSa, origGameScore);
                heurValue = heurOneStep.value;
            }
            int overrideMargin = AiProfileUtil.getIntProperty(player, AiProps.SIM_POLICY_OVERRIDE_MARGIN);
            if (plan.getFinalScore().value > heurValue + overrideMargin) {
                System.err.println(policyLog("OVERRIDE") + " heur=" + policyDesc(heurSa)
                        + " heurScore=" + (heurSa == null ? "-" : String.valueOf(heurValue))
                        + " sim=" + policyDesc(planRoot) + " simScore=" + plan.getFinalScore().value
                        + " orig=" + origGameScore.value);
                return getPlannedSpellAbility(origGameScore, prunedSAs);
            }
            // Not enough margin - discard the sim plan and play the heuristic's choice.
            plan = null;
        }

        if (heurSa == null) {
            return null;
        }
        if (heurOneStep == null) {
            heurOneStep = evaluateOneStep(heurSa, origGameScore);
        }
        if (shouldVetoHeuristicChoice(heurSa, heurOneStep, origGameScore)) {
            return null;
        }
        // Play the heuristic's choice with its originally chosen targets re-applied. The MAIN1
        // summon-sick holdoff inside chooseSpellAbilityToPlayImpl() only applies to sim-chosen
        // lines; it intentionally does not veto the heuristic's choice here.
        restoreTargets(heurSa, savedHeurTargets);
        return heurSa;
    }

    /**
     * Resolves the first decision of the current plan back to a SpellAbility from the list the
     * plan was formulated from. Returns null if it cannot be resolved.
     */
    private SpellAbility getPlanRootAbility(List<SpellAbility> formulationSAs) {
        if (plan == null || plan.getDecisions().isEmpty()) {
            return null;
        }
        Plan.Decision first = plan.getDecisions().get(0);
        if (first.saRef == null) {
            return null;
        }
        return first.saRef.findReferencedAbility(formulationSAs);
    }

    private Plan formulatePlanWithPhase(Score origGameScore, List<SpellAbility> candidateSAs, PhaseType phase) {
        // Pass the player to SimulationController to enable profile-based settings
        SimulationController controller = new SimulationController(origGameScore, player);
        SpellAbility sa = chooseSpellAbilityToPlayImpl(controller, candidateSAs, origGameScore, phase);
        if (sa != null) {
            return controller.getBestPlan();
        }
        return null;
    }

    private void printPlan(Plan plan, String intro) {
        if (plan == null) {
            print(intro + ": no plan!");
        }
        print(intro +" plan with score " + plan.getFinalScore() + ":");
        int i = 0;
        for (Plan.Decision d : plan.getDecisions()) {
            print(++i + ". " + d);
        }
    }

    private void createNewPlan(Score origGameScore, List<SpellAbility> candidateSAs) {
        plan = null;

        Plan bestPlan = formulatePlanWithPhase(origGameScore, candidateSAs, null);
        if (bestPlan == null) {
            print("No good plan at this time");
            return;
        }

        PhaseType currentPhase = game.getPhaseHandler().getPhase();
        if (currentPhase.isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            List<SpellAbility> candidateSAs2 = new ArrayList<>();
            for (SpellAbility sa : candidateSAs) {
                if (!SpellAbilityAi.isSorcerySpeed(sa, player)) {
                    if (printOutput) {
                        System.err.println("Not sorcery: " + sa);
                    }
                    candidateSAs2.add(sa);
                }
            }
            if (!candidateSAs2.isEmpty()) {
                if (printOutput) {
                    System.err.println("Formula plan with phase bloom");
                }
                Plan afterBlockersPlan = formulatePlanWithPhase(origGameScore, candidateSAs2, PhaseType.COMBAT_DECLARE_BLOCKERS);
                if (afterBlockersPlan != null && afterBlockersPlan.getFinalScore().value > bestPlan.getFinalScore().value) {
                    printPlan(afterBlockersPlan, "After blockers");
                    print("Deciding to wait until after declare blockers.");
                    afterBlockersPlan.setStartPhase(PhaseType.COMBAT_DECLARE_BLOCKERS, game.getPhaseHandler().getTurn());
                    plan = afterBlockersPlan;
                    return;
                }
            }
        }

        printPlan(bestPlan, "Current phase (" + currentPhase + ")");
        plan = bestPlan;
    }

    private SpellAbility chooseSpellAbilityToPlayImpl(SimulationController controller, List<SpellAbility> candidateSAs, Score origGameScore, PhaseType phase) {
        long startTime = System.currentTimeMillis();

        SpellAbility bestSa = null;
        Score bestSaValue = origGameScore;
        int bestSaIndex = -1;
        print("Evaluating... (orig score = " + origGameScore +  ")");

        // Use move orderer to evaluate candidates in a better order for pruning
        List<Integer> orderedIndices = moveOrderer.get().orderMoves(candidateSAs, controller.getDepth());

        for (int idx : orderedIndices) {
            Score value = evaluateSa(controller, phase, candidateSAs, idx);
            if (value.value > bestSaValue.value) {
                bestSaValue = value;
                bestSa = candidateSAs.get(idx);
                bestSaIndex = idx;
                controller.updateAlpha(value.value);

                // Record this as a killer move since it improved our score
                if (bestSa != null) {
                    moveOrderer.get().recordKillerMove(bestSa, controller.getDepth());
                }

                // Soft beta cutoff: at depth >= 2, once we've proven this branch
                // beats the parent's best, stop searching for an even better follow-up
                if (controller.shouldBetaCutoff()) {
                    break;
                }
            }
        }

        // Update history heuristic for the best move
        if (bestSa != null && bestSaIndex >= 0) {
            moveOrderer.get().updateHistory(bestSa, controller.getDepth());
        }

        // To make the AI hold-off on playing creatures in MAIN1 if they give no other benefits,
        // check the score for the bestSA while counting summon sick creatures for 0.
        // Do it here on the best SA, rather than for all evaluations, so that if the best SA
        // is indeed a creature spell, we don't pick something else to play now and then have
        // no mana to play the truly best SA post-combat.
        if (bestSa != null && bestSaValue.summonSickValue <= origGameScore.summonSickValue) {
            bestSa = null;
        }

        long execTime = System.currentTimeMillis() - startTime;
        print("BEST: " + abilityToString(bestSa) + " SCORE: " + bestSaValue.summonSickValue + " TIME: " + execTime);
        this.bestScore = bestSaValue;
        return bestSa;
    }

    public boolean hasActivePlan() {
        return plan != null && plan.hasNextDecision();
    }

    public Plan getPlan() {
        return plan;
    }

    private void printPlannedActionFailure(Plan.Decision decision, String cause) {
        print("Failed to continue planned action (" + decision.saRef + "). Cause:");
        print("  " + cause + "!");
        plan = null;
    }

    private SpellAbility getPlannedSpellAbility(Score origGameScore, List<SpellAbility> availableSAs) {
        if (!hasActivePlan()) {
            plan = null;
            return null;
        }
        PhaseType startPhase = plan.getStartPhase();
        if (startPhase != null) {
            // Deferred plan expired: the turn advanced, so assumptions are stale.
            if (game.getPhaseHandler().getTurn() != plan.getStartPhaseTurn()) {
                print("Deferred plan expired (created turn " + plan.getStartPhaseTurn()
                        + ", now turn " + game.getPhaseHandler().getTurn() + ") — abandoning.");
                plan = null;
                return null;
            }
            if (game.getPhaseHandler().getPhase().isBefore(startPhase)) {
                print("Waiting until phase " + startPhase + " to proceed with the plan.");
                return null;
            }
        }
        Plan.Decision decision = plan.selectNextDecision();
        // Abandon only when the live score is WORSE than planned by more than the tolerance; transient
        // score gains (opponent lost a creature, etc.) must not discard a valid plan.
        if (origGameScore.value < decision.initialScore.value - planScoreTolerance) {
            printPlannedActionFailure(decision, "Game score dropped too far (planned=" + decision.initialScore.value + " actual=" + origGameScore.value + ")");
            return null;
        }
        SpellAbility sa = decision.saRef.findReferencedAbility(availableSAs);
        if (sa == null) {
            printPlannedActionFailure(decision, "Couldn't find spell/ability!");
            return null;
        }
        // If modes != null, targeting will be done in chooseModeForAbility().
        if (decision.modes == null && decision.targets != null) {
            MultiTargetSelector selector = new MultiTargetSelector(sa, null);
            if (!selector.selectTargets(decision.targets)) {
                printPlannedActionFailure(decision, "Bad targets");
                return null;
            }
        }
        if (decision.xMana != null) {
            sa.setXManaCostPaid(decision.xMana);
        }
        print("Planned decision " + plan.getNextDecisionIndex() + ": " + decision);
        return sa;
    }

    public Score getScoreForChosenAbility() {
        return bestScore;
    }

    public static String abilityToString(SpellAbility sa) {
        return abilityToString(sa, true);
    }
    public static String abilityToString(SpellAbility sa, boolean withTargets) {
        StringBuilder saString = new StringBuilder("N/A");
        if (sa != null) {
            saString = new StringBuilder(sa.toString());
            String cardName = sa.getHostCard().getName();
            if (!cardName.isEmpty()) {
                saString = new StringBuilder(TextUtil.fastReplace(saString.toString(), cardName, "<$>"));
            }
            if (saString.length() > 40) {
                saString = new StringBuilder(saString.substring(0, 40) + "...");
            }
            if (withTargets) {
                SpellAbility saOrSubSa = sa;
                do {
                    if (saOrSubSa.usesTargeting()) {
                        saString.append(" (targets: ").append(saOrSubSa.getTargets()).append(")");
                    }
                    saOrSubSa = saOrSubSa.getSubAbility();
                } while (saOrSubSa != null);
            }
            saString.insert(0, sa.getHostCard() + " -> ");
        }
        return saString.toString();
    }

    private boolean shouldWaitForLater(final SpellAbility sa) {
        final PhaseType phase = game.getPhaseHandler().getPhase();
        final boolean isEarlyPhase = phase == PhaseType.UNTAP || phase == PhaseType.UPKEEP || phase == PhaseType.DRAW;

        // Until the AI can be made smarter, hold off playing instants until MAIN1,
        // so that they can be compared to sorcery-speed spells. Else, the AI is too
        // eager to play them.
        if (isEarlyPhase) {
            // Only hold off if this spell can actually be played in MAIN1.
            final SpellAbilityCondition conditions = sa.getConditions();
            if (conditions == null) {
                return true;
            }
            Set<PhaseType> phases = conditions.getPhases();
            return phases.isEmpty() || phases.contains(PhaseType.MAIN1);
        }

        return false;
    }

    private boolean atLeastOneConditionMet(SpellAbility saOrSubSa) {
        do {
            SpellAbilityCondition conditions = saOrSubSa.getConditions();
            if (conditions == null || conditions.areMet(saOrSubSa)) {
                return true;
            }
            saOrSubSa = saOrSubSa.getSubAbility();
        } while (saOrSubSa != null);
        return false;
    }

    private AiPlayDecision canPlayAndPayForSim(final SpellAbility sa) {
        if (!sa.checkRestrictions(sa.getHostCard(), player)) {
            return AiPlayDecision.CantPlaySa;
        }

        if (sa.isLandAbility()) {
            return AiPlayDecision.WillPlay;
        }
        if (!sa.isLegalAfterStack()) {
            return AiPlayDecision.CantPlaySa;
        }
        if (!sa.canPlay()) {
            return AiPlayDecision.CantPlaySa;
        }

        // Note: Can't just check condition on the top ability, because it may have
        // sub-abilities without conditions (e.g. wild slash's main ability has a
        // main ability with conditions but the burn sub-ability has none).
        if (!atLeastOneConditionMet(sa)) {
            return AiPlayDecision.CantPlaySa;
        }

        if (!ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
            return AiPlayDecision.CantAfford;
        }
        if (!ComputerUtilAbility.isFullyTargetable(sa)) {
            return AiPlayDecision.TargetingFailed;
        }
        if (shouldWaitForLater(sa)) {
            return AiPlayDecision.AnotherTime;
        }

        return AiPlayDecision.WillPlay;
    }

    public Score evaluateSa(final SimulationController controller, PhaseType phase, List<SpellAbility> saList, int saIndex) {
        controller.evaluateSpellAbility(saList, saIndex);
        SpellAbility sa = saList.get(saIndex);

        // Use a deterministic random seed when evaluating different choices of a spell ability.
        // This is needed as otherwise random effects may result in a different number of choices
        // each iteration, which will break the logic in SpellAbilityChoicesIterator.
        Random origRandom = MyRandom.getRandom();
        long randomSeedToUse = origRandom.nextLong();

        Score bestScore = new Score(Integer.MIN_VALUE);
        final SpellAbilityChoicesIterator choicesIterator = new SpellAbilityChoicesIterator(controller);
        Score lastScore;
        // MyRandom is now ThreadLocal, so this swap is thread-safe; the finally block ensures the
        // original RNG is always restored even if a simulation throws.
        try {
            do {
                MyRandom.setRandom(new Random(randomSeedToUse));
                GameSimulator simulator = new GameSimulator(controller, game, player, phase);
                simulator.setInterceptor(choicesIterator);
                // I feel like something here is making a wrong assumption about what the target is
                lastScore = simulator.simulateSpellAbility(sa);
                numSimulations++;
                if (lastScore.value > bestScore.value) {
                    bestScore = lastScore;
                }
            } while (choicesIterator.advance(lastScore));
        } finally {
            MyRandom.setRandom(origRandom);
        }
        controller.doneEvaluating(bestScore);
        return bestScore;
    }

    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> choices, int min, int num, boolean allowRepeat) {
        if (interceptor != null) {
            return interceptor.chooseModesForAbility(sa, choices, min, num, allowRepeat);
        }
        if (plan != null && plan.getSelectedDecision() != null && plan.getSelectedDecision().modes != null) {
            Plan.Decision decision = plan.getSelectedDecision();
            // TODO: Validate that there's no discrepancies between choices and modes?
            List<AbilitySub> plannedModes = SpellAbilityChoicesIterator.getModeCombination(choices, decision.modes);
            if (plan.getSelectedDecision().targets != null) {
                MultiTargetSelector selector = new MultiTargetSelector(sa, plannedModes);
                if (!selector.selectTargets(decision.targets)) {
                    printPlannedActionFailure(decision, "Bad targets for modes");
                    return null;
                }
            }
            return plannedModes;
        }
        return null;
    }

    private Card getPlannedChoice(CardCollection fetchList) {
        // TODO: Make the below more robust?
        if (plan != null && plan.getSelectedDecision() != null) {
            String choice = plan.getSelectedDecisionNextChoice();
            for (Card c : fetchList) {
                if (c.getName().equals(choice)) {
                    print("  Planned choice: " + c);
                    return c;
                }
            }
            print("Failed to use planned choice (" + choice + "). Not found!");
        }
        return null;
    }

    public Card chooseCardToHiddenOriginChangeZone(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, Player player2, Player decider) {
        if (fetchList.size() >= 2) {
            if (interceptor != null) {
                return interceptor.chooseCard(fetchList);
            }
            Card card = getPlannedChoice(fetchList);
            if (card != null) {
                plan.advanceNextChoice();
                return card;
            }
        }
        if (sa.getApi() == ApiType.Learn) {
            return LearnAi.chooseCardToLearn(fetchList, decider, sa);
        } else {
            return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }
    }

    public CardCollectionView chooseSacrificeType(String type, SpellAbility ability, final boolean effect, int amount, final CardCollectionView exclude) {
        if (amount == 1) {
            Card source = ability.getHostCard();
            CardCollection cardList = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), type.split(";"), source.getController(), source, ability);
            cardList = CardLists.filter(cardList, CardPredicates.canBeSacrificedBy(ability, effect));
            if (cardList.size() >= 2) {
                if (interceptor != null) {
                    return new CardCollection(interceptor.chooseCard(cardList));
                }
                Card card = getPlannedChoice(cardList);
                if (card != null) {
                    plan.advanceNextChoice();
                    return new CardCollection(card);
                }
            }
        }
        return ComputerUtil.chooseSacrificeType(player, type, ability, ability.getTargetCard(), effect, amount, exclude);
    }

    public int getNumSimulations() {
        return numSimulations;
    }
}
