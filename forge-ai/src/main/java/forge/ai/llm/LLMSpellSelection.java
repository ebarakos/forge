package forge.ai.llm;

import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spell-selection logic for {@link LLMFullController}: heuristic-prior pruning,
 * MAIN-phase plan caching, and the {@code chooseSpellAbilityToPlay} override.
 *
 * <p>Extracted from {@link LLMFullController} to keep that class focused on
 * the engine contract. State that survives across calls (mainPhasePlan,
 * planPhase, planTurn, planStackSize) lives on the controller; this class
 * is stateless and reads/writes through package-private accessors.
 */
final class LLMSpellSelection {

    private final LLMFullController ctrl;

    LLMSpellSelection(LLMFullController ctrl) {
        this.ctrl = ctrl;
    }

    /** Implements {@link LLMFullController#chooseSpellAbilityToPlay()}. */
    List<SpellAbility> chooseSpellAbilityToPlay() {
        // Use LLM for MAIN phases always; for non-MAIN phases, only when there's
        // a meaningful instant-speed decision (e.g., counterspell opportunity, combat trick)
        PhaseType phase = ctrl.getGame().getPhaseHandler().getPhase();
        if (phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2) {
            if (!ctrl.shouldCallLLMForInstantSpeed()) {
                return ctrl.defaultChooseSpellAbilityToPlay();
            }
            // Fall through to normal spell selection — getSpellAbilities() already
            // filters to only instant-speed playable spells during non-MAIN phases
        }

        // Delegate land drops to heuristic (no benefit from LLM deciding which basic to play),
        // but ONLY the land drop — don't let heuristic also choose spells.
        // After the land resolves, the engine loops and calls us again for spell decisions.
        CardCollection landsWannaPlay = ComputerUtilAbility.getAvailableLandsToPlay(ctrl.getGame(), ctrl.getPlayer());
        if (landsWannaPlay != null && !landsWannaPlay.isEmpty()) {
            List<SpellAbility> heuristicChoice = ctrl.defaultChooseSpellAbilityToPlay();
            if (heuristicChoice != null && !heuristicChoice.isEmpty()
                    && heuristicChoice.get(0).isLandAbility()) {
                return heuristicChoice; // Play just the land; engine will call us again for spells
            }
            // Heuristic decided not to play a land (e.g., holding for MAIN2) — fall through to LLM
        }

        CardCollection cards = ComputerUtilAbility.getAvailableCards(ctrl.getGame(), ctrl.getPlayer());
        List<SpellAbility> candidates = ComputerUtilAbility.getSpellAbilities(cards, ctrl.getPlayer());

        List<SpellAbility> playable = new ArrayList<>();
        LLMFullController.IN_FEASIBILITY.set(true);
        try {
            for (SpellAbility sa : candidates) {
                // Filter out mana abilities and land abilities — engine handles these automatically
                if (sa.isManaAbility() || sa.isLandAbility()) {
                    continue;
                }
                sa.setActivatingPlayer(ctrl.getPlayer());
                try {
                    // Phase-legality filter: at non-MAIN we got transcripts of sorceries
                    // (e.g. Lórien Revealed) leaking into the option list during own
                    // COMBAT_DAMAGE because canPayCost only checks mana, not timing.
                    // canCastTiming returns true for sorceries iff the player canCastSorcery()
                    // (own MAIN with empty stack) OR the spell has flash.
                    if (!sa.canCastTiming(ctrl.getPlayer())) {
                        continue;
                    }
                    if (!ComputerUtilCost.canPayCost(sa, ctrl.getPlayer(), sa.isTrigger())) {
                        continue;
                    }
                    // Pre-validate targeting: only show spells that can actually target
                    if (!ctrl.validateAndSetTargets(sa)) {
                        continue;
                    }
                    playable.add(sa);
                } catch (Exception e) {
                    // Skip
                }
            }
        } finally {
            LLMFullController.IN_FEASIBILITY.set(false);
        }

        if (playable.isEmpty()) {
            ctrl.mainPhasePlan.clear();
            return null;
        }

        // A1: PASS+1 short-circuit. With one option, trust the heuristic's
        // canPlaySa() answer instead of paying for an LLM call.
        if (playable.size() == 1) {
            SpellAbility only = playable.get(0);
            AiPlayDecision decision;
            try {
                decision = ctrl.getAi().canPlaySa(only);
            } catch (Exception e) {
                decision = AiPlayDecision.WillPlay;
            }
            if (decision == AiPlayDecision.WillPlay) {
                recordSpellAction(only, phase);
                List<SpellAbility> result = new ArrayList<>();
                result.add(only);
                return result;
            }
            return null; // heuristic refuses → PASS
        }

        boolean isMainPhase = phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2;

        // Heuristic-prior pruning + ordering. Drops fundamentally-bad options,
        // sorts WillPlay candidates first, caps to top-K. Verdict tags are
        // collected only when annotation is enabled (cost ~10 tokens/option).
        List<String> verdictTags = LLMFullController.VERDICT_TAGS_ENABLED ? new ArrayList<>() : null;
        List<SpellAbility> pruned = applyHeuristicPrior(playable, verdictTags);
        if (pruned.isEmpty()) {
            ctrl.mainPhasePlan.clear();
            return null;
        }

        // B1: MAIN-phase plan batching. Consume a cached plan step if one is
        // still valid; otherwise issue a fresh plan call.
        if (isMainPhase) {
            SpellAbility planned = popValidPlanStep(pruned, phase);
            if (planned != null) {
                recordSpellAction(planned, phase);
                List<SpellAbility> result = new ArrayList<>();
                result.add(planned);
                return result;
            }

            // No valid cached plan — request a new one.
            String gameState = ctrl.buildGameStateWithHistory();
            List<Integer> evalDeltas = computeEvalDeltas(pruned);
            String options = OptionSerializer.serializeSpellOptions(pruned, evalDeltas, verdictTags);
            String planPrompt = PromptTemplates.mainPhasePlan(gameState, options);
            String planResponse = ctrl.callLLMRaw(planPrompt, "mainPhasePlan", LLMResponseSchema.PLAN);

            if (planResponse != null) {
                List<Integer> planIndices = ResponseParser.parsePlanSequence(planResponse, pruned.size());
                logShadowMode("mainPhasePlan", pruned, planIndices);
                ctrl.mainPhasePlan.clear();
                for (int idx : planIndices) {
                    Card host = pruned.get(idx).getHostCard();
                    if (host != null) {
                        ctrl.mainPhasePlan.addLast(host.getName());
                    }
                }
                ctrl.planPhase = phase;
                ctrl.planTurn = ctrl.getGame().getPhaseHandler().getTurn();
                ctrl.planStackSize = ctrl.getGame().getStack().size();

                SpellAbility firstStep = popValidPlanStep(pruned, phase);
                if (firstStep != null) {
                    recordSpellAction(firstStep, phase);
                    List<SpellAbility> result = new ArrayList<>();
                    result.add(firstStep);
                    return result;
                }
                // Empty plan recovery: small models occasionally emit "plan":[]
                // even when a clear play exists (transcripts show this happens
                // when reasoning truncates into ellipsis garbage). If the
                // heuristic strongly recommends pruned[0] (verdict.willingToPlay),
                // override the LLM's PASS — it's almost certainly a model glitch.
                if (planIndices.isEmpty() && !pruned.isEmpty()) {
                    SpellAbility heuristicTop = pruned.get(0);
                    AiPlayDecision verdict;
                    try {
                        verdict = ctrl.getAi().canPlaySa(heuristicTop);
                    } catch (Exception e) {
                        verdict = null;
                    }
                    if (verdict != null && verdict.willingToPlay()) {
                        if (ctrl.client.isDebug()) {
                            System.err.println("[LLM] empty plan + heuristic WillPlay on "
                                    + heuristicTop.getHostCard().getName()
                                    + " → recovering with heuristic top pick");
                        }
                        recordSpellAction(heuristicTop, phase);
                        List<SpellAbility> result = new ArrayList<>();
                        result.add(heuristicTop);
                        return result;
                    }
                }
                // No matches and heuristic doesn't push back → honour PASS.
                return null;
            }
            // LLM failure → fall through to heuristic fallback.
            return ctrl.defaultChooseSpellAbilityToPlay();
        }

        // Non-MAIN (instant-speed) flow: single one-shot LLM call.
        String gameState = ctrl.buildGameStateWithHistory();
        List<Integer> evalDeltas = computeEvalDeltas(pruned);
        String options = OptionSerializer.serializeSpellOptions(pruned, evalDeltas, verdictTags);
        String prompt = PromptTemplates.spellSelection(gameState, options, false);
        int totalOptions = pruned.size() + 1; // +1 for PASS
        int chosen = ctrl.callLLM(prompt, totalOptions, "chooseSpellAbilityToPlay");

        if (chosen < 0) {
            return ctrl.defaultChooseSpellAbilityToPlay();
        }
        if (chosen >= pruned.size()) {
            return null; // PASS
        }

        SpellAbility selectedSa = pruned.get(chosen);
        logShadowMode("chooseSpellAbilityToPlay", pruned, Collections.singletonList(chosen));
        recordSpellAction(selectedSa, phase);

        List<SpellAbility> result = new ArrayList<>();
        result.add(selectedSa);
        return result;
    }

    /**
     * Heuristic prior: for each candidate ask the parent {@link forge.ai.AiController}
     * whether it would play the spell, and split the list into "wants",
     * "waits", and "rejects" buckets. The hopeless bucket (CantPlaySa,
     * BadEtbEffects, DoesntImpactGame, …) is dropped — these are spells the
     * heuristic considers fundamentally bad here, and surfacing them to the
     * LLM costs tokens without informing a real choice. The remaining options
     * are returned in priority order: WillPlay first, then "wait" (might still
     * be the right call now), then "weak but technically playable".
     *
     * <p>Returns a parallel list of short verdict tags (e.g. "Removal",
     * "WaitForMain2") aligned to the reordered spell list when
     * {@code returnTags} is non-null; otherwise tags are skipped.
     *
     * <p>The reordering also gives the prompt cache a more stable prefix across
     * turns: putting the heuristic's strongest pick at index 0 means a model
     * choosing index 0 still works even when the prompt-cache hits an earlier
     * call for a similar position.
     */
    List<SpellAbility> applyHeuristicPrior(List<SpellAbility> playable, List<String> returnTags) {
        if (playable.size() <= 1) {
            if (returnTags != null) {
                for (SpellAbility ignored : playable) returnTags.add(null);
            }
            return playable;
        }

        List<SpellAbility> wants = new ArrayList<>();
        List<String> wantsTags = new ArrayList<>();
        List<SpellAbility> waits = new ArrayList<>();
        List<String> waitsTags = new ArrayList<>();
        List<SpellAbility> weak = new ArrayList<>();
        List<String> weakTags = new ArrayList<>();

        for (SpellAbility sa : playable) {
            AiPlayDecision verdict;
            try {
                verdict = ctrl.getAi().canPlaySa(sa);
            } catch (Exception e) {
                verdict = null;
            }
            if (verdict == null) {
                weak.add(sa);
                weakTags.add(null);
                continue;
            }
            if (verdict.willingToPlay()) {
                wants.add(sa);
                wantsTags.add(verdict.toString());
            } else if (isWaitVerdict(verdict)) {
                waits.add(sa);
                waitsTags.add(verdict.toString());
            } else if (isHopelessVerdict(verdict)) {
                // Drop entirely — heuristic considers this fundamentally bad.
                continue;
            } else {
                weak.add(sa);
                weakTags.add(verdict.toString());
            }
        }

        // If everything was pruned (rare — usually means heuristic hates the
        // whole hand), restore the original list so the LLM still gets a shot.
        if (wants.isEmpty() && waits.isEmpty() && weak.isEmpty()) {
            if (returnTags != null) {
                for (SpellAbility ignored : playable) returnTags.add(null);
            }
            return playable;
        }

        List<SpellAbility> ordered = new ArrayList<>(wants.size() + waits.size() + weak.size());
        ordered.addAll(wants);
        ordered.addAll(waits);
        ordered.addAll(weak);
        if (returnTags != null) {
            returnTags.addAll(wantsTags);
            returnTags.addAll(waitsTags);
            returnTags.addAll(weakTags);
        }

        // Cap to top-K to keep the prompt tight. Heuristic-best survives the cap.
        int topK = LLMFullController.HEURISTIC_TOPK;
        if (topK > 0 && ordered.size() > topK) {
            ordered = new ArrayList<>(ordered.subList(0, topK));
            if (returnTags != null) {
                List<String> capped = new ArrayList<>(returnTags.subList(0, topK));
                returnTags.clear();
                returnTags.addAll(capped);
            }
        }
        return ordered;
    }

    /** Heuristic verdicts that mean "hold for later" but aren't outright rejections. */
    private static boolean isWaitVerdict(AiPlayDecision d) {
        switch (d) {
            case WaitForCombat:
            case WaitForMain2:
            case WaitForEndOfTurn:
            case StackNotEmpty:
            case AnotherTime:
                return true;
            default:
                return false;
        }
    }

    /** Heuristic verdicts that mean "this is fundamentally bad — drop it". */
    private static boolean isHopelessVerdict(AiPlayDecision d) {
        switch (d) {
            case CantPlaySa:
            case CantPlayAi:
            case CantAfford:
            case CantAffordX:
            case DoesntImpactGame:
            case DoesntImpactCombat:
            case BadEtbEffects:
            case CurseEffects:
            case WouldDestroyLegend:
            case WouldDestroyOtherPlaneswalker:
            case WouldBecomeZeroToughnessCreature:
            case WouldDestroyWorldEnchantment:
            case StopRunawayActivations:
            case TargetingFailed:
            case CostNotAcceptable:
            case LifeInDanger:
            case MissingNeededCards:
            case MissingLogic:
            case TimingRestrictions:
            case MissingPhaseRestrictions:
            case ConditionsNotMet:
            case NeedsToPlayCriteriaNotMet:
                return true;
            default:
                return false;
        }
    }

    /**
     * Compute one-step lookahead score deltas for each candidate spell. Returns
     * null when the feature flag is off, the candidate list is too wide, or any
     * simulation throws. Caller should treat null as "no hint annotation".
     */
    private List<Integer> computeEvalDeltas(List<SpellAbility> playable) {
        if (!LLMFullController.EVAL_HINTS_ENABLED) return null;
        if (playable.isEmpty() || playable.size() > LLMFullController.EVAL_HINT_MAX_CANDIDATES) return null;
        try {
            List<Integer> deltas = new ArrayList<>(playable.size());
            for (SpellAbility sa : playable) {
                forge.ai.simulation.SimulationController simCtrl =
                        new forge.ai.simulation.SimulationController(
                                new forge.ai.simulation.GameStateEvaluator.Score(0), ctrl.getPlayer());
                forge.ai.simulation.GameSimulator sim =
                        new forge.ai.simulation.GameSimulator(simCtrl, ctrl.getGame(), ctrl.getPlayer(), null);
                forge.ai.simulation.GameStateEvaluator.Score before = sim.getScoreForOrigGame();
                forge.ai.simulation.GameStateEvaluator.Score after = sim.simulateSpellAbility(sa);
                int delta = after.value - before.value;
                deltas.add(delta);
            }
            return deltas;
        } catch (Exception e) {
            if (ctrl.client.isDebug()) {
                System.err.println("[LLM] eval hints disabled this call: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * B1: Pop the next step from the cached MAIN-phase plan, if the plan is
     * still valid for the current turn/phase and the planned card still exists
     * in {@code playable}. Returns null if the plan is stale or empty.
     */
    private SpellAbility popValidPlanStep(List<SpellAbility> playable, PhaseType phase) {
        if (ctrl.mainPhasePlan.isEmpty()) return null;

        int currentTurn = ctrl.getGame().getPhaseHandler().getTurn();
        int currentStackSize = ctrl.getGame().getStack().size();
        if (ctrl.planPhase != phase || ctrl.planTurn != currentTurn || currentStackSize > ctrl.planStackSize) {
            // Phase/turn change or unexpected stack object → conservative invalidation.
            ctrl.mainPhasePlan.clear();
            return null;
        }

        while (!ctrl.mainPhasePlan.isEmpty()) {
            String targetName = ctrl.mainPhasePlan.pollFirst();
            SpellAbility match = findByCardName(playable, targetName);
            if (match == null) continue; // card no longer playable, try next
            try {
                AiPlayDecision decision = ctrl.getAi().canPlaySa(match);
                if (decision == AiPlayDecision.WillPlay) {
                    return match;
                }
            } catch (Exception e) {
                // Fall through to next plan step
            }
        }
        return null;
    }

    private static SpellAbility findByCardName(List<SpellAbility> playable, String name) {
        if (name == null) return null;
        for (SpellAbility sa : playable) {
            Card host = sa.getHostCard();
            if (host != null && name.equals(host.getName())) {
                return sa;
            }
        }
        return null;
    }

    /** Record a cast-spell entry in the sliding window history. */
    private void recordSpellAction(SpellAbility selectedSa, PhaseType phase) {
        String phaseName = phase != null ? phase.toString() : "MAIN";
        int turn = ctrl.getGame().getPhaseHandler().getTurn();
        Card host = selectedSa.getHostCard();
        String spellName = host != null ? host.getName() : selectedSa.toString();
        StringBuilder actionSb = new StringBuilder();
        actionSb.append("Turn ").append(turn).append(' ').append(phaseName)
                .append(": Cast ").append(spellName);
        if (selectedSa.usesTargeting() && selectedSa.getTargets() != null
                && !selectedSa.getTargets().isEmpty()) {
            actionSb.append(" targeting ");
            boolean first = true;
            for (Card tc : selectedSa.getTargets().getTargetCards()) {
                if (!first) actionSb.append(", ");
                first = false;
                actionSb.append(tc.getName());
            }
            for (Player tp : selectedSa.getTargets().getTargetPlayers()) {
                if (!first) actionSb.append(", ");
                first = false;
                actionSb.append(tp.getName());
            }
        }
        ctrl.recordAction(actionSb.toString());
    }

    /**
     * Shadow-mode telemetry: log the heuristic's top pick alongside the LLM's
     * choice so divergence can be analysed offline (mtg-discovery captures
     * stderr to per-match transcripts). No-op unless {@code FORGE_LLM_SHADOW=1}.
     * Heuristic top pick is index 0 of {@code pruned} since {@code applyHeuristicPrior}
     * sorts WillPlay verdicts first.
     */
    private void logShadowMode(String label, List<SpellAbility> pruned, List<Integer> llmIndices) {
        if (!LLMFullController.SHADOW_MODE || pruned.isEmpty()) return;
        String hName = nameOrNull(pruned.get(0));
        StringBuilder picks = new StringBuilder();
        for (int i = 0; i < llmIndices.size(); i++) {
            if (i > 0) picks.append(',');
            int idx = llmIndices.get(i);
            picks.append(idx >= 0 && idx < pruned.size() ? nameOrNull(pruned.get(idx)) : "?");
        }
        boolean agree = !llmIndices.isEmpty() && llmIndices.get(0) == 0;
        System.err.println("[LLM SHADOW] " + label
                + " heuristic=" + hName
                + " llm=[" + picks + "]"
                + (agree ? " AGREE" : " DIVERGE"));
    }

    private static String nameOrNull(SpellAbility sa) {
        if (sa == null) return "null";
        Card h = sa.getHostCard();
        return h != null ? h.getName() : sa.toString();
    }
}
