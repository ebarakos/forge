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

        // Muzzle: FORGE_LLM_HEURISTIC_LAND_DROPS (on by default) delegates land
        // drops to the heuristic — which land, and whether to hold it for MAIN2 —
        // but ONLY the land drop; don't let the heuristic also choose spells.
        // After the land resolves, the engine loops and calls us again for spells.
        // Off, the block is skipped and land plays are listed as ordinary options
        // below, so the model owns the whole decision.
        if (LLMFullController.HEURISTIC_LAND_DROPS) {
            CardCollection landsWannaPlay = ComputerUtilAbility.getAvailableLandsToPlay(ctrl.getGame(), ctrl.getPlayer());
            if (landsWannaPlay != null && !landsWannaPlay.isEmpty()) {
                List<SpellAbility> heuristicChoice = ctrl.defaultChooseSpellAbilityToPlay();
                if (heuristicChoice != null && !heuristicChoice.isEmpty()
                        && heuristicChoice.get(0).isLandAbility()) {
                    return heuristicChoice; // Play just the land; engine will call us again for spells
                }
                // Heuristic decided not to play a land (e.g., holding for MAIN2) — fall through to LLM
            }
        }

        CardCollection cards = ComputerUtilAbility.getAvailableCards(ctrl.getGame(), ctrl.getPlayer());
        List<SpellAbility> candidates = ComputerUtilAbility.getSpellAbilities(cards, ctrl.getPlayer());

        List<SpellAbility> playable = new ArrayList<>();
        LLMFullController.enterFeasibility();
        try {
            for (SpellAbility sa : candidates) {
                // Filter out mana abilities — the engine handles these automatically.
                // Land abilities are filtered too while the heuristic owns land
                // drops (see the muzzle above); when it doesn't, they stay in the
                // list so the model can choose to play a land or not.
                if (sa.isManaAbility()) {
                    continue;
                }
                if (sa.isLandAbility() && LLMFullController.HEURISTIC_LAND_DROPS) {
                    continue;
                }
                sa.setActivatingPlayer(ctrl.getPlayer());
                if (sa.isLandAbility()) {
                    // Land plays cost nothing and target nothing, so the spell
                    // checks below say nothing about them. canPlay() is the one
                    // that matters: it enforces "your turn, sorcery speed, land
                    // drop not used yet". Offering a land the engine would then
                    // refuse would hand priority straight back and spin.
                    try {
                        if (sa.canPlay()) {
                            playable.add(sa);
                        }
                    } catch (Exception e) {
                        // Skip
                    }
                    continue;
                }
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
            LLMFullController.exitFeasibility();
        }

        if (playable.isEmpty()) {
            ctrl.mainPhasePlan.clear();
            return null;
        }

        // A1: PASS+1 short-circuit. With one option, trust the heuristic's
        // canPlaySa() answer instead of paying for an LLM call.
        // Muzzle: FORGE_LLM_SINGLE_OPTION_SHORTCUT (on by default). It saves a
        // call per forced-looking turn, but "cast it or hold it" with one card
        // in hand is a real decision and the heuristic is making it. Off, the
        // single option goes to the model like any other list.
        if (playable.size() == 1 && LLMFullController.SINGLE_OPTION_SHORTCUT) {
            SpellAbility only = playable.get(0);
            if (only.isLandAbility()) {
                // Reachable only when the model owns land drops; the heuristic
                // has no verdict for a land play, so just take it.
                return commit(only, phase);
            }
            AiPlayDecision decision;
            try {
                decision = ctrl.askHeuristicVerdict(only);
            } catch (Exception e) {
                decision = AiPlayDecision.WillPlay;
            }
            if (decision == AiPlayDecision.WillPlay) {
                return commit(only, phase);
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
                return commit(planned, phase);
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
                ctrl.planOpponentState = ctrl.opponentBoardState();
                ctrl.planOwnPermanents = ctrl.ownPermanentIds();

                // Muzzle: FORGE_LLM_TRUST_HEURISTIC_TOP (on by default).
                // When the model's first plan step is not the spell the
                // heuristic would have played, play the heuristic's spell.
                //
                // Why so aggressive: mirror-eval transcripts show the model's
                // dominant failure mode is reordering WITHIN the WillPlay
                // bucket (e.g. picking Lunarch Veteran when the heuristic
                // prefers Novice Inspector — both willingToPlay) and these
                // reorderings cost ~25-50 percentage points against the
                // heuristic mirror baseline. A narrower verdict-class check
                // (only catch wait/weak picks) misses every such case. The
                // model keeps influence when the heuristic itself has nothing
                // it wants to play, and when it returns plan:[] (the
                // empty-plan path below).
                //
                // What "the heuristic's pick" means here: askHeuristicPick()
                // runs the heuristic's own full selection, the same one the
                // fallback paths use. It is NOT pruned.get(0). pruned is
                // ordered by verdict class only — WillPlay first, then wait,
                // then weak — and within a class the order is whatever order
                // the cards came out of hand. So pruned.get(0) is "the first
                // card in hand the heuristic would be willing to play", which
                // in a hand with two playable creatures is frequently not the
                // one the heuristic would actually cast. Until 2026-08-15 the
                // veto substituted pruned.get(0), so it was not comparing the
                // model against the heuristic at all, and a run with the veto
                // on was not playing the heuristic's game.
                if (!planIndices.isEmpty() && !pruned.isEmpty()
                        && LLMFullController.TRUST_HEURISTIC_TOP) {
                    int firstIdx = planIndices.get(0);
                    if (firstIdx >= 0 && firstIdx < pruned.size()) {
                        SpellAbility llmFirst = pruned.get(firstIdx);
                        SpellAbility heurPick = askHeuristicPick();
                        if (heurPick != null && heurPick != llmFirst
                                && !sameCard(heurPick, llmFirst)) {
                            if (ctrl.client.isDebug()) {
                                System.err.println("[LLM] LLM picked "
                                        + nameOrNull(llmFirst)
                                        + " over heuristic pick "
                                        + nameOrNull(heurPick)
                                        + " → overriding with heuristic pick");
                            }
                            ctrl.mainPhasePlan.clear();
                            Card heurHost = heurPick.getHostCard();
                            if (heurHost != null) {
                                ctrl.mainPhasePlan.addLast(heurHost.getName());
                            }
                            return commit(heurPick, phase);
                        }
                    }
                }

                SpellAbility firstStep = popValidPlanStep(pruned, phase);
                if (firstStep != null) {
                    return commit(firstStep, phase);
                }
                // Muzzle: FORGE_LLM_EMPTY_PLAN_OVERRIDE (on by default).
                // Small models occasionally emit "plan":[] even when a clear
                // play exists (transcripts show this happens when reasoning
                // truncates into ellipsis garbage). If the heuristic strongly
                // recommends pruned[0] (verdict.willingToPlay), override the
                // model's PASS — it's most likely a model glitch. The cost is
                // that a model which meant to hold everything cannot: off
                // (FORGE_LLM_EMPTY_PLAN_OVERRIDE=0) the pass is honoured.
                if (planIndices.isEmpty() && !pruned.isEmpty()
                        && LLMFullController.EMPTY_PLAN_OVERRIDE) {
                    SpellAbility heuristicTop = pruned.get(0);
                    AiPlayDecision verdict;
                    try {
                        verdict = ctrl.askHeuristicVerdict(heuristicTop);
                    } catch (Exception e) {
                        verdict = null;
                    }
                    if (verdict != null && verdict.willingToPlay()) {
                        if (ctrl.client.isDebug()) {
                            System.err.println("[LLM] empty plan + heuristic WillPlay on "
                                    + heuristicTop.getHostCard().getName()
                                    + " → recovering with heuristic top pick");
                        }
                        return commit(heuristicTop, phase);
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
        return commit(selectedSa, phase);
    }

    /**
     * Hand back the ability the seat has settled on.
     *
     * <p>Two things happen here and the order matters. The targets are decided
     * first — the muzzle {@code FORGE_LLM_HEURISTIC_TARGETS} decides whether
     * that is the heuristic's existing choice or a fresh one from the model —
     * and only then is the action written into the history, so the history line
     * names the target that will actually be used.
     */
    private List<SpellAbility> commit(SpellAbility sa, PhaseType phase) {
        ctrl.chooseTargetsWithLLM(sa, "spellTargets");
        recordSpellAction(sa, phase);
        List<SpellAbility> result = new ArrayList<>();
        result.add(sa);
        return result;
    }

    /**
     * The spell the heuristic AI would actually cast right now, or null if it
     * would pass or would play a land. This runs the heuristic's own full
     * selection — the same call the fallback paths use — rather than reading
     * an entry out of the pruned option list, because the pruned list is
     * ordered by verdict class and not by the heuristic's preference within a
     * class. Any failure reads as "the heuristic has no pick", which leaves
     * the model's choice standing.
     */
    private SpellAbility askHeuristicPick() {
        List<SpellAbility> choice;
        try {
            choice = ctrl.defaultChooseSpellAbilityToPlay();
        } catch (Exception e) {
            return null;
        }
        if (choice == null || choice.isEmpty()) return null;
        SpellAbility pick = choice.get(0);
        if (pick == null || pick.isLandAbility()) return null;
        return pick;
    }

    /**
     * Whether two spell abilities are the same physical card. Identity on the
     * host card, not on the ability: the heuristic's selection rebuilds its own
     * ability objects, and alternative-cost variants of one card are separate
     * objects too, so comparing abilities would report a disagreement where
     * there is none.
     */
    private static boolean sameCard(SpellAbility a, SpellAbility b) {
        if (a == null || b == null) return false;
        Card hostA = a.getHostCard();
        return hostA != null && hostA == b.getHostCard();
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
            if (sa.isLandAbility()) {
                // Reachable only when the model owns land drops. The heuristic
                // has no canPlaySa verdict for a land play, so don't ask for
                // one — rank it with the plays the heuristic wants.
                wants.add(sa);
                wantsTags.add("PlayLand");
                continue;
            }
            AiPlayDecision verdict;
            try {
                verdict = ctrl.askHeuristicVerdict(sa);
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
                // Muzzle: FORGE_LLM_PRUNE_HOPELESS (on by default). The
                // heuristic considers this fundamentally bad, so the model
                // never sees it — cheaper prompts, but the model also cannot
                // disagree. Off, it stays in the list ranked last.
                if (LLMFullController.PRUNE_HOPELESS) {
                    continue;
                }
                weak.add(sa);
                weakTags.add(verdict.toString());
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
     * B1: Pop the next step from the cached MAIN-phase plan, if the plan still
     * describes this game and the planned card is still in {@code playable}.
     * Returns null if the plan is stale or empty, which sends the caller back
     * for a fresh plan against the board as it is now.
     *
     * <p>A plan is a sequence of casts written from one look at the board, and
     * it is played out over several priority passes. Between those passes the
     * opponent gets priority. Until 2026-08-15 the only things that ended a
     * plan early were a change of turn or phase and a stack that had grown, so
     * an opponent who countered the first spell, killed the creature the second
     * one was going to pump, or bounced the permanent the third one needed, saw
     * the rest of the plan played out anyway — every step of it chosen against
     * a board that no longer existed. Their spell had resolved by the time the
     * seat next had priority, so the stack was empty again and the plan looked
     * fine.
     *
     * <p>What is checked now is everything the plan could not have anticipated:
     * anything the opponent did ({@link LLMFullController#opponentBoardState()})
     * and any permanent of the seat's own that has left the battlefield. What
     * is not checked is the seat's own additions and the opponent's life total,
     * because those are the plan doing what it said it would do; invalidating
     * on them would cost a fresh LLM call after every step and leave no plan
     * batching at all.
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
        if (ctrl.planOpponentState != null
                && !ctrl.planOpponentState.equals(ctrl.opponentBoardState())) {
            if (ctrl.client.isDebug()) {
                System.err.println("[LLM] opponent acted since the plan was made → replanning");
            }
            ctrl.mainPhasePlan.clear();
            return null;
        }
        if (!ctrl.ownPermanentsIntact(ctrl.planOwnPermanents)) {
            if (ctrl.client.isDebug()) {
                System.err.println("[LLM] a permanent the plan counted on has left → replanning");
            }
            ctrl.mainPhasePlan.clear();
            return null;
        }

        while (!ctrl.mainPhasePlan.isEmpty()) {
            String targetName = ctrl.mainPhasePlan.pollFirst();
            SpellAbility match = findByCardName(playable, targetName);
            if (match == null) continue; // card no longer playable, try next
            // Muzzle: FORGE_LLM_PLAN_STEP_APPROVAL (on by default). Every step
            // of the model's own plan is put back to the heuristic, and a step
            // the heuristic will not endorse is dropped — so a multi-step plan
            // survives only as far as the heuristic agrees with it. Off, the
            // plan is played as the model wrote it.
            if (!LLMFullController.PLAN_STEP_APPROVAL || match.isLandAbility()) {
                return match;
            }
            try {
                AiPlayDecision decision = ctrl.askHeuristicVerdict(match);
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

    /**
     * Record the seat's decision in the sliding window history.
     *
     * <p>Worded as an attempt, because that is all it is. This runs when the
     * ability is handed back to the engine, before the cost is paid and long
     * before the spell resolves: it can still be countered, its target can be
     * removed in response, or the cost can turn out to be unpayable. Saying
     * "Cast Lightning Bolt" here put a claim in the model's own context that
     * the game had not made yet, and the model has no way to tell the
     * difference — whereas the board state travelling with every prompt does
     * show what really landed.
     */
    private void recordSpellAction(SpellAbility selectedSa, PhaseType phase) {
        String phaseName = phase != null ? phase.toString() : "MAIN";
        int turn = ctrl.getGame().getPhaseHandler().getTurn();
        Card host = selectedSa.getHostCard();
        String spellName = host != null ? host.getName() : selectedSa.toString();
        StringBuilder actionSb = new StringBuilder();
        actionSb.append("Turn ").append(turn).append(' ').append(phaseName)
                .append(selectedSa.isLandAbility() ? ": Attempted to play land " : ": Attempted to cast ")
                .append(spellName);
        actionSb.append(describeTargets(selectedSa));
        ctrl.recordAction(actionSb.toString());
    }

    /**
     * The {@code " targeting …"} clause for a history entry, or an empty string
     * when the ability points at nothing worth naming.
     *
     * <p>Spells on the stack are named too. Only cards and players were listed
     * before, so a Counterspell — whose one target is a stack entry, neither of
     * those — produced the line "Attempted to cast Counterspell targeting "
     * with nothing after it: a sentence in the model's own context that reads
     * as an unfinished thought, on the one card where what it points at is the
     * whole decision.
     */
    private static String describeTargets(SpellAbility sa) {
        if (!sa.usesTargeting() || sa.getTargets() == null || sa.getTargets().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Card tc : sa.getTargets().getTargetCards()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(tc.getName());
        }
        for (Player tp : sa.getTargets().getTargetPlayers()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(tp.getName());
        }
        for (SpellAbility ts : sa.getTargets().getTargetSpells()) {
            if (!first) sb.append(", ");
            first = false;
            Card host = ts.getHostCard();
            sb.append(host != null ? host.getName() : ts.toString()).append(" on the stack");
        }
        return first ? "" : " targeting " + sb;
    }

    /**
     * Shadow-mode telemetry: log the heuristic's top pick alongside the LLM's
     * choice so divergence can be analysed offline when a runner captures stderr
     * to per-match transcripts. No-op unless {@code FORGE_LLM_SHADOW=1}.
     *
     * <p>The "heuristic" name logged here is index 0 of {@code pruned} — the
     * first option in the list the heuristic is willing to play, not the spell
     * it would actually cast (see {@link #askHeuristicPick()}). It is a cheap
     * divergence signal for offline reading, not the veto's comparison.
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
