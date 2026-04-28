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

        // Single batched LLM call for all attack decisions.
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
        String prompt = PromptTemplates.batchAttack(gameState, options);
        String response = ctrl.callLLMRaw(prompt, "declareAttackers", LLMResponseSchema.INDICES);

        if (response == null) {
            ctrl.lastAttackPlan = "";
            // LLM failure — fall back to heuristic attack logic
            ctrl.defaultDeclareAttackers(attacker, combat);
            return;
        }

        // B2: extract the PLAN: line (if any) for carry-over into declareBlockers.
        ctrl.lastAttackPlan = extractPlanLine(response);

        Set<Integer> attackIndices = ResponseParser.parseBatchIndices(response, canAttack.size());
        logCombatShadow("declareAttackers", canAttack, heuristicAttackIdx, attackIndices);
        for (int idx : attackIndices) {
            combat.addAttacker(canAttack.get(idx), defaultDefender);
        }

        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            ctrl.defaultDeclareAttackers(attacker, combat);
        }

        // Record attack action
        if (!attackIndices.isEmpty()) {
            int turn = ctrl.getGame().getPhaseHandler().getTurn();
            StringBuilder actionSb = new StringBuilder();
            actionSb.append("Turn ").append(turn).append(" COMBAT: Attacked with ");
            boolean first = true;
            for (int idx : attackIndices) {
                if (!first) actionSb.append(", ");
                first = false;
                Card c = canAttack.get(idx);
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

        // Single batched LLM call for all block assignments
        String gameState = ctrl.buildGameStateWithHistory();
        // B2: prepend the attack-phase plan note if we have one (continuity context).
        if (!ctrl.lastAttackPlan.isEmpty()) {
            gameState = gameState + "\nYOUR PRIOR ATTACK-PLAN NOTE: " + ctrl.lastAttackPlan + "\n";
        }
        String options = OptionSerializer.serializeBatchBlockOptions(
                attackerList, blockerList, ctrl.getPlayer().getLife(), heuristicAssignment);
        String prompt = PromptTemplates.batchBlock(gameState, options);
        String response = ctrl.callLLMRaw(prompt, "declareBlockers", LLMResponseSchema.BLOCKS);
        ctrl.lastAttackPlan = ""; // consume once

        if (response == null) {
            ctrl.defaultDeclareBlockers(defender, combat);
            return;
        }

        Map<Integer, Set<Integer>> assignments = ResponseParser.parseBatchBlockAssignments(
                response, attackerList.size(), blockerList.size());
        logBlockShadow(attackerList, blockerList, heuristicAssignment, assignments);

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

        // Record block action
        if (!assignments.isEmpty()) {
            int turn = ctrl.getGame().getPhaseHandler().getTurn();
            StringBuilder actionSb = new StringBuilder();
            actionSb.append("Turn ").append(turn).append(" COMBAT: Blocked ");
            boolean first = true;
            for (Map.Entry<Integer, Set<Integer>> entry2 : assignments.entrySet()) {
                if (!first) actionSb.append("; ");
                first = false;
                Card att = attackerList.get(entry2.getKey());
                actionSb.append(att.getName()).append(" with ");
                boolean firstB = true;
                for (int bIdx : entry2.getValue()) {
                    if (!firstB) actionSb.append("+");
                    firstB = false;
                    actionSb.append(blockerList.get(bIdx).getName());
                }
            }
            ctrl.recordAction(actionSb.toString());
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

    /**
     * Shadow-mode telemetry for combat: emit one line summarising the heuristic
     * baseline vs the LLM's actual pick and whether they agree on the *set* of
     * attacking creatures. No-op unless {@code FORGE_LLM_SHADOW=1}. Skips when
     * the heuristic baseline could not be computed.
     */
    private void logCombatShadow(String label, List<Card> canAttack,
                                  Set<Integer> heuristicIdx, Set<Integer> llmIdx) {
        if (!LLMFullController.SHADOW_MODE || heuristicIdx == null) return;
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
     * order doesn't matter). Skips when no baseline could be computed.
     */
    private void logBlockShadow(List<Card> attackerList, List<Card> blockerList,
                                 Map<Integer, Set<Integer>> heuristicAssignment,
                                 Map<Integer, Set<Integer>> llmAssignment) {
        if (!LLMFullController.SHADOW_MODE || heuristicAssignment == null) return;
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
