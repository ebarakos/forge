package forge.ai.llm;

import forge.game.GameEntity;
import forge.game.IEntityMap;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Combat logic for {@link LLMFullController}: declare-attackers and declare-blockers,
 * with heuristic-prior baselines and shadow-mode telemetry. Extracted to keep
 * {@link LLMFullController} focused on the engine contract. Stateless except for
 * the controller back-reference; carry-over state ({@code lastAttackPlan}) lives
 * on the controller so the spell-selection path can read it too.
 */
final class LLMCombat {

    private final LLMFullController ctrl;

    LLMCombat(LLMFullController ctrl) {
        this.ctrl = ctrl;
    }

    /** Implements {@link LLMFullController#declareAttackers(Player, Combat)}. */
    void declareAttackers(Player attacker, Combat combat) {
        CardCollection potentialAttackers = attacker.getCreaturesInPlay();
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
        GameEntity defaultDefender = defenders.isEmpty() ? null : defenders.get(0);

        if (potentialAttackers.isEmpty() || defaultDefender == null) {
            return;
        }

        List<Card> canAttack = new ArrayList<>();
        for (Card c : potentialAttackers) {
            if (CombatUtil.canAttack(c, defaultDefender)) {
                canAttack.add(c);
            }
        }
        if (canAttack.isEmpty()) {
            return;
        }

        // Heuristic prior: ask AiAttackController what it would do, on a scratch
        // Combat we throw away. The set of recommended attack indices becomes a
        // baseline annotation in the prompt — same AlphaZero pattern as spell
        // selection (heuristic = policy prior, LLM = revisor). On any failure
        // we just skip the annotation; the LLM runs unannotated as before.
        Set<Integer> heuristicAttackIdx = computeHeuristicAttackPrior(attacker, canAttack);

        // Muzzle: FORGE_LLM_COMBAT=heuristic (the default, and the single
        // largest one — the model never fights a combat) trusts the prior
        // outright and skips the prompt build + LLM call entirely. Set
        // FORGE_LLM_COMBAT=llm, or FORGE_LLM_UNMUZZLED, to hand attacks and
        // blocks to the model. Null prior (heuristic threw) falls back the
        // same way an LLM failure would.
        if ("heuristic".equals(LLMFullController.COMBAT_MODE)) {
            ctrl.lastAttackPlan = "";
            if (heuristicAttackIdx == null) {
                ctrl.defaultDeclareAttackers(attacker, combat);
                return;
            }
            applyAttackIndices(combat, attacker, defaultDefender, canAttack, heuristicAttackIdx);
            return;
        }

        // "llm" / "shadow": single batched LLM call for all attack decisions.
        String gameState = ctrl.buildGameStateWithHistory();
        List<Card> defBlockers = new ArrayList<>();
        int defLife = 0;
        if (defaultDefender instanceof Player) {
            Player defPlayer = (Player) defaultDefender;
            defLife = defPlayer.getLife();
            for (Card c : defPlayer.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                if (c.isCreature() && !c.isTapped()) defBlockers.add(c);
            }
        } else if (defaultDefender instanceof Card) {
            // Planeswalker — its loyalty acts as life total for combat math.
            defLife = ((Card) defaultDefender).getCurrentLoyalty();
        }
        String options = OptionSerializer.serializeBatchAttackOptions(
                canAttack, defLife, defBlockers, heuristicAttackIdx);
        // D2: doctrine + action history go into the cached stable prefix;
        // game state + options stay in the volatile tail.
        PromptTemplates.PromptParts prompt = PromptTemplates.batchAttack(
                ctrl.getActionHistoryText(), gameState, options);
        String response = ctrl.callLLMRaw(prompt, "declareAttackers", LLMResponseSchema.INDICES);

        if (response == null) {
            ctrl.lastAttackPlan = "";
            if ("shadow".equals(LLMFullController.COMBAT_MODE) && heuristicAttackIdx != null) {
                // Shadow applies the heuristic anyway; the LLM consult just failed.
                applyAttackIndices(combat, attacker, defaultDefender, canAttack, heuristicAttackIdx);
            } else {
                // LLM failure — fall back to heuristic attack logic
                ctrl.defaultDeclareAttackers(attacker, combat);
            }
            return;
        }

        Set<Integer> attackIndices = ResponseParser.parseBatchIndices(response, canAttack.size());
        if (attackIndices == null) {
            // An answer nobody could read is a failed call. Applying it as an empty
            // index set would declare no attackers at all — a decision the model never
            // made, which CombatUtil.validateAttackers happily accepts, so not even the
            // rescue below would fire — and it would not be counted as a fallback.
            ctrl.client.recordFallback(ctrl.getPlayer().getName(),
                    "declareAttackers: answer named no usable attacker ("
                            + canAttack.size() + " offered)");
            ctrl.lastAttackPlan = "";
            if ("shadow".equals(LLMFullController.COMBAT_MODE) && heuristicAttackIdx != null) {
                applyAttackIndices(combat, attacker, defaultDefender, canAttack, heuristicAttackIdx);
            } else {
                ctrl.defaultDeclareAttackers(attacker, combat);
            }
            return;
        }

        // B2: extract the PLAN: line (if any) for carry-over into declareBlockers.
        ctrl.lastAttackPlan = extractPlanLine(response);

        logCombatShadow("declareAttackers", canAttack, heuristicAttackIdx, attackIndices);

        // Shadow: LLM consulted (divergence logged above) but not trusted —
        // the heuristic prior is what actually gets applied.
        Set<Integer> applied = "shadow".equals(LLMFullController.COMBAT_MODE)
                && heuristicAttackIdx != null ? heuristicAttackIdx : attackIndices;
        applyAttackIndices(combat, attacker, defaultDefender, canAttack, applied);
    }

    /**
     * Apply a set of attack indices (positions in {@code canAttack}) to the
     * live {@link Combat}: add each attacker against {@code defaultDefender},
     * fall back to the heuristic if the engine rejects the configuration, and
     * record the action-history entry. Shared by all FORGE_LLM_COMBAT modes —
     * the indices may come from the LLM response or the heuristic prior (the
     * two have identical shape by construction).
     */
    private void applyAttackIndices(Combat combat, Player attacker, GameEntity defaultDefender,
                                     List<Card> canAttack, Set<Integer> attackIndices) {
        for (int idx : attackIndices) {
            combat.addAttacker(canAttack.get(idx), defaultDefender);
        }

        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            ctrl.defaultDeclareAttackers(attacker, combat);
        }

        // Record the attack that the engine actually holds, not the one that was
        // asked for. When validateAttackers rejects the configuration above, the
        // declaration is thrown away and the heuristic makes its own — so writing
        // the requested indices into the history told the model it had attacked
        // with creatures that never attacked.
        CardCollection declared = combat.getAttackers();
        if (declared != null && !declared.isEmpty()) {
            int turn = ctrl.getGame().getPhaseHandler().getTurn();
            StringBuilder actionSb = new StringBuilder();
            actionSb.append("Turn ").append(turn).append(" COMBAT: Attacked with ");
            boolean first = true;
            for (Card c : declared) {
                if (!first) actionSb.append(", ");
                first = false;
                actionSb.append(c.getName());
                if (c.isCreature()) {
                    actionSb.append(" (").append(c.getNetPower()).append('/')
                            .append(c.getNetToughness()).append(')');
                }
            }
            ctrl.recordAction(actionSb.toString());
        }
    }

    /** Implements {@link LLMFullController#declareBlockers(Player, Combat)}. */
    void declareBlockers(Player defender, Combat combat) {
        CardCollection attackers = combat.getAttackers();
        if (attackers.isEmpty()) {
            return;
        }

        CardCollection potentialBlockers = defender.getCreaturesInPlay();
        CardCollection availableBlockers = new CardCollection();
        for (Card b : potentialBlockers) {
            if (CombatUtil.canBlock(b, combat)) {
                availableBlockers.add(b);
            }
        }
        if (availableBlockers.isEmpty()) {
            return;
        }

        // Build lists of attackers that have at least one legal blocker
        List<Card> attackerList = new ArrayList<>();
        for (Card att : attackers) {
            for (Card b : availableBlockers) {
                if (CombatUtil.canBlock(att, b, combat)) {
                    attackerList.add(att);
                    break;
                }
            }
        }
        if (attackerList.isEmpty()) {
            return;
        }

        List<Card> blockerList = new ArrayList<>(availableBlockers);

        // Heuristic prior: copy the live combat (identity-mapped) and let
        // AiBlockController fill in the recommended assignments on the copy.
        // The result is rendered into the prompt header as a baseline the LLM
        // can either confirm or diverge from with reason.
        Map<Integer, Set<Integer>> heuristicAssignment =
                computeHeuristicBlockPrior(defender, combat, attackerList, blockerList);

        // Muzzle: FORGE_LLM_COMBAT=heuristic (default) trusts the prior
        // outright and skips the prompt build + LLM call entirely. See the
        // matching note in declareAttackers.
        if ("heuristic".equals(LLMFullController.COMBAT_MODE)) {
            ctrl.lastAttackPlan = "";
            if (heuristicAssignment == null) {
                ctrl.defaultDeclareBlockers(defender, combat);
                return;
            }
            applyBlockAssignments(combat, attackerList, blockerList, heuristicAssignment);
            return;
        }

        // "llm" / "shadow": single batched LLM call for all block assignments
        String gameState = ctrl.buildGameStateWithHistory();
        // B2: prepend the attack-phase plan note if we have one (continuity context).
        // The note is volatile, so it travels with the game state in the uncached tail.
        if (!ctrl.lastAttackPlan.isEmpty()) {
            gameState = gameState + "\nYOUR PRIOR ATTACK-PLAN NOTE: " + ctrl.lastAttackPlan + "\n";
        }
        String options = OptionSerializer.serializeBatchBlockOptions(
                attackerList, blockerList, ctrl.getPlayer().getLife(), heuristicAssignment);
        // D2: action history goes into the cached stable prefix; state + options volatile.
        PromptTemplates.PromptParts prompt = PromptTemplates.batchBlock(
                ctrl.getActionHistoryText(), gameState, options);
        String response = ctrl.callLLMRaw(prompt, "declareBlockers", LLMResponseSchema.BLOCKS);
        ctrl.lastAttackPlan = ""; // consume once

        if (response == null) {
            if ("shadow".equals(LLMFullController.COMBAT_MODE) && heuristicAssignment != null) {
                // Shadow applies the heuristic anyway; the LLM consult just failed.
                applyBlockAssignments(combat, attackerList, blockerList, heuristicAssignment);
            } else {
                ctrl.defaultDeclareBlockers(defender, combat);
            }
            return;
        }

        Map<Integer, Set<Integer>> assignments = ResponseParser.parseBatchBlockAssignments(
                response, attackerList.size(), blockerList.size());
        if (assignments == null) {
            // Same as declareAttackers: an unreadable answer applied as an empty map is
            // "block with nothing", a decision the model never made and one that would
            // never be counted against the fallback rate.
            ctrl.client.recordFallback(ctrl.getPlayer().getName(),
                    "declareBlockers: answer named no usable block ("
                            + attackerList.size() + " attackers, " + blockerList.size() + " blockers)");
            if ("shadow".equals(LLMFullController.COMBAT_MODE) && heuristicAssignment != null) {
                applyBlockAssignments(combat, attackerList, blockerList, heuristicAssignment);
            } else {
                ctrl.defaultDeclareBlockers(defender, combat);
            }
            return;
        }
        logBlockShadow(attackerList, blockerList, heuristicAssignment, assignments);

        // Shadow: LLM consulted (divergence logged above) but not trusted —
        // the heuristic prior is what actually gets applied.
        Map<Integer, Set<Integer>> applied = "shadow".equals(LLMFullController.COMBAT_MODE)
                && heuristicAssignment != null ? heuristicAssignment : assignments;
        applyBlockAssignments(combat, attackerList, blockerList, applied);
    }

    /**
     * Apply a block-assignment map (attacker index → blocker indices, positions
     * in {@code attackerList} / {@code blockerList}) to the live {@link Combat},
     * re-checking legality per pair, and record the action-history entry.
     * Shared by all FORGE_LLM_COMBAT modes — the map may come from the LLM
     * response or the heuristic prior (identical shape by construction).
     */
    private void applyBlockAssignments(Combat combat, List<Card> attackerList,
                                        List<Card> blockerList,
                                        Map<Integer, Set<Integer>> assignments) {
        for (Map.Entry<Integer, Set<Integer>> entry : assignments.entrySet()) {
            int attIdx = entry.getKey();
            if (attIdx < 0 || attIdx >= attackerList.size()) continue;
            Card att = attackerList.get(attIdx);
            for (int bIdx : entry.getValue()) {
                if (bIdx < 0 || bIdx >= blockerList.size()) continue;
                Card blocker = blockerList.get(bIdx);
                if (CombatUtil.canBlock(att, blocker, combat)) {
                    combat.addBlocker(att, blocker);
                }
            }
        }

        // Record the blocks the engine accepted. Every pair above is re-checked
        // with canBlock and quietly dropped when illegal, so the requested
        // assignment and the real one are routinely different — and the history
        // used to report the requested one, telling the model an attacker was
        // blocked when it was about to connect.
        int turn = ctrl.getGame().getPhaseHandler().getTurn();
        StringBuilder actionSb = new StringBuilder();
        boolean first = true;
        for (Card att : combat.getAttackers()) {
            CardCollection blockers = combat.getBlockers(att);
            if (blockers == null || blockers.isEmpty()) continue;
            if (!first) actionSb.append("; ");
            first = false;
            actionSb.append(att.getName()).append(" with ");
            boolean firstB = true;
            for (Card blocker : blockers) {
                if (!firstB) actionSb.append("+");
                firstB = false;
                actionSb.append(blocker.getName());
            }
        }
        if (!first) {
            ctrl.recordAction("Turn " + turn + " COMBAT: Blocked " + actionSb);
        }
    }

    /**
     * Heuristic prior for declareAttackers: run {@link forge.ai.AiAttackController}
     * on a throwaway scratch {@link Combat} and read out which creatures it would
     * attack with. Returns null on any exception so callers can treat the
     * annotation as optional. Indices match positions in {@code canAttack}.
     */
    private Set<Integer> computeHeuristicAttackPrior(Player attacker, List<Card> canAttack) {
        try {
            Combat scratch = new Combat(attacker);
            new forge.ai.AiAttackController(attacker).declareAttackers(scratch);
            Set<Card> chosen = new HashSet<>(scratch.getAttackers());
            Set<Integer> idx = new HashSet<>();
            for (int i = 0; i < canAttack.size(); i++) {
                if (chosen.contains(canAttack.get(i))) idx.add(i);
            }
            return idx;
        } catch (Exception e) {
            if (ctrl.client.isDebug()) {
                System.err.println("[LLM] computeHeuristicAttackPrior failed: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Heuristic prior for declareBlockers: copy the live {@link Combat} via an
     * identity {@link IEntityMap}, run {@link forge.ai.AiBlockController} on
     * the copy, and read out the recommended assignments per attacker. The
     * returned map is keyed by {@code attackerList} index → set of {@code blockerList}
     * indices; null on any exception. Identity-mapped because we want the
     * heuristic to see the real Card instances (so it can read P/T, keywords,
     * counters), but its addBlocker / removeBlockAssignment side effects to
     * land on the scratch Combat, not the live one.
     */
    private Map<Integer, Set<Integer>> computeHeuristicBlockPrior(Player defender,
                                                                    Combat liveCombat,
                                                                    List<Card> attackerList,
                                                                    List<Card> blockerList) {
        try {
            IEntityMap identity = new IEntityMap() {
                @Override public forge.game.Game getGame() { return defender.getGame(); }
                @Override public GameObject map(GameObject o) { return o; }
            };
            Combat scratch = new Combat(liveCombat, identity);
            new forge.ai.AiBlockController(defender, false).assignBlockersForCombat(scratch);

            Map<Integer, Set<Integer>> result = new HashMap<>();
            for (int ai = 0; ai < attackerList.size(); ai++) {
                Card att = attackerList.get(ai);
                CardCollectionView blockers = scratch.getBlockers(att);
                if (blockers == null || blockers.isEmpty()) continue;
                Set<Integer> bIdx = new HashSet<>();
                for (Card b : blockers) {
                    int idx = blockerList.indexOf(b);
                    if (idx >= 0) bIdx.add(idx);
                }
                if (!bIdx.isEmpty()) result.put(ai, bIdx);
            }
            return result;
        } catch (Exception e) {
            if (ctrl.client.isDebug()) {
                System.err.println("[LLM] computeHeuristicBlockPrior failed: " + e.getMessage());
            }
            return null;
        }
    }

    /** True when combat shadow telemetry should fire: either the global
     *  {@code FORGE_LLM_SHADOW=1} flag or {@code FORGE_LLM_COMBAT=shadow}. */
    private static boolean shadowLoggingActive() {
        return LLMFullController.SHADOW_MODE
                || "shadow".equals(LLMFullController.COMBAT_MODE);
    }

    /**
     * Shadow-mode telemetry for combat: emit one line summarising the heuristic
     * baseline vs the LLM's actual pick and whether they agree on the *set* of
     * attacking creatures. No-op unless {@code FORGE_LLM_SHADOW=1} or
     * {@code FORGE_LLM_COMBAT=shadow}. Skips when the heuristic baseline could
     * not be computed.
     */
    private void logCombatShadow(String label, List<Card> canAttack,
                                  Set<Integer> heuristicIdx, Set<Integer> llmIdx) {
        if (!shadowLoggingActive() || heuristicIdx == null) return;
        boolean agree = heuristicIdx.equals(llmIdx);
        StringBuilder sb = new StringBuilder("[LLM SHADOW] ").append(label);
        sb.append(" heuristic=[").append(joinNames(canAttack, heuristicIdx)).append(']');
        sb.append(" llm=[").append(joinNames(canAttack, llmIdx)).append(']');
        sb.append(agree ? " AGREE" : " DIVERGE");
        System.err.println(sb.toString());
    }

    /**
     * Shadow-mode telemetry for declareBlockers: compares the heuristic baseline
     * assignment to what the LLM ended up choosing. Considers them in agreement
     * when, for every attacker, the *set* of blockers is identical (gang-block
     * order doesn't matter). No-op unless {@code FORGE_LLM_SHADOW=1} or
     * {@code FORGE_LLM_COMBAT=shadow}. Skips when no baseline could be computed.
     */
    private void logBlockShadow(List<Card> attackerList, List<Card> blockerList,
                                 Map<Integer, Set<Integer>> heuristicAssignment,
                                 Map<Integer, Set<Integer>> llmAssignment) {
        if (!shadowLoggingActive() || heuristicAssignment == null) return;
        boolean agree = true;
        Set<Integer> keys = new HashSet<>();
        keys.addAll(heuristicAssignment.keySet());
        keys.addAll(llmAssignment.keySet());
        for (int ai : keys) {
            Set<Integer> h = heuristicAssignment.getOrDefault(ai, java.util.Collections.emptySet());
            Set<Integer> l = llmAssignment.getOrDefault(ai, java.util.Collections.emptySet());
            if (!h.equals(l)) { agree = false; break; }
        }
        StringBuilder sb = new StringBuilder("[LLM SHADOW] declareBlockers heuristic={");
        sb.append(formatAssignment(attackerList, blockerList, heuristicAssignment));
        sb.append("} llm={").append(formatAssignment(attackerList, blockerList, llmAssignment)).append('}');
        sb.append(agree ? " AGREE" : " DIVERGE");
        System.err.println(sb.toString());
    }

    private static String joinNames(List<Card> cards, Set<Integer> indices) {
        if (indices == null || indices.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < cards.size(); i++) {
            if (!indices.contains(i)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(cards.get(i).getName());
        }
        return sb.toString();
    }

    private static String formatAssignment(List<Card> attackerList, List<Card> blockerList,
                                            Map<Integer, Set<Integer>> assignment) {
        if (assignment == null || assignment.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Integer, Set<Integer>> e : assignment.entrySet()) {
            int ai = e.getKey();
            if (ai < 0 || ai >= attackerList.size()) continue;
            if (!first) sb.append("; ");
            first = false;
            sb.append(attackerList.get(ai).getName()).append("<-");
            boolean firstB = true;
            for (int bi : e.getValue()) {
                if (bi < 0 || bi >= blockerList.size()) continue;
                if (!firstB) sb.append('+');
                firstB = false;
                sb.append(blockerList.get(bi).getName());
            }
        }
        return sb.toString();
    }

    /**
     * Pull the "PLAN: ..." line out of an LLM response (one short note
     * the attack call leaves for the follow-up block call).
     */
    private static String extractPlanLine(String response) {
        if (response == null) return "";
        for (String raw : response.split("\\R")) {
            String line = raw.strip();
            int idx = line.toUpperCase().indexOf("PLAN:");
            if (idx >= 0) {
                String tail = line.substring(idx + "PLAN:".length()).strip();
                if (tail.length() > 200) tail = tail.substring(0, 200);
                return tail;
            }
        }
        return "";
    }
}
