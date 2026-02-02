/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2013  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai.simulation;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

import java.util.*;

/**
 * Orders moves (spell abilities) to improve alpha-beta pruning efficiency.
 * Better move ordering leads to more cutoffs and faster search.
 *
 * Move ordering heuristics:
 * 1. Killer moves - moves that caused cutoffs at the same depth in sibling nodes
 * 2. History heuristic - moves that have been good in the past
 * 3. Static ordering - based on move type (removal > creatures > other)
 */
public class MoveOrderer {

    // Killer moves - two slots per depth level
    private final Map<Integer, SpellAbility[]> killerMoves;

    // History table - tracks how often each move type has been good
    private final Map<String, Integer> historyTable;

    // Maximum depth to track killer moves
    private static final int MAX_KILLER_DEPTH = 20;

    // Number of killer move slots per depth
    private static final int KILLER_SLOTS = 2;

    public MoveOrderer() {
        this.killerMoves = new HashMap<>();
        this.historyTable = new HashMap<>();
    }

    /**
     * Orders a list of spell abilities for more efficient search.
     * Returns indices in the order they should be searched.
     *
     * @param abilities the list of abilities to order
     * @param depth the current search depth
     * @return list of indices in search order
     */
    public List<Integer> orderMoves(List<SpellAbility> abilities, int depth) {
        if (abilities == null || abilities.isEmpty()) {
            return Collections.emptyList();
        }

        // Create list of (index, priority) pairs
        List<int[]> prioritized = new ArrayList<>(abilities.size());
        for (int i = 0; i < abilities.size(); i++) {
            int priority = computePriority(abilities.get(i), depth);
            prioritized.add(new int[]{i, priority});
        }

        // Sort by priority (descending)
        prioritized.sort((a, b) -> Integer.compare(b[1], a[1]));

        // Extract indices
        List<Integer> result = new ArrayList<>(abilities.size());
        for (int[] pair : prioritized) {
            result.add(pair[0]);
        }

        return result;
    }

    /**
     * Computes a priority score for a spell ability.
     * Higher priority = searched first.
     */
    private int computePriority(SpellAbility sa, int depth) {
        int priority = 0;

        // Check if this is a killer move
        if (isKillerMove(sa, depth)) {
            priority += 10000;
        }

        // Check history heuristic
        String key = getMoveKey(sa);
        Integer historyScore = historyTable.get(key);
        if (historyScore != null) {
            priority += historyScore;
        }

        // Static ordering based on move type
        priority += getStaticPriority(sa);

        return priority;
    }

    /**
     * Gets a static priority based on the type of spell/ability.
     */
    private int getStaticPriority(SpellAbility sa) {
        ApiType api = sa.getApi();
        if (api == null) {
            return 100; // Default for spells without API
        }

        switch (api) {
            // Highest priority - removal and damage
            case Destroy:
            case DestroyAll:
            case Sacrifice:
            case SacrificeAll:
            case DealDamage:
            case DamageAll:
            case LoseLife:
            case Counter:
                return 500;

            // High priority - card advantage
            case Draw:
            case Mill:
            case Discard:
            case Pump:
            case PumpAll:
                return 400;

            // Medium priority - creatures and tokens
            case Token:
            case CopyPermanent:
            case PermanentCreature:
            case PermanentNoncreature:
                return 300;

            // Medium priority - mana and ramp
            case Mana:
            case ManaReflected:
            case ChangeZone:
                return 250;

            // Lower priority - utility
            case Attach:
            case Animate:
            case Regenerate:
            case GainLife:
                return 200;

            // Default
            default:
                return 100;
        }
    }

    /**
     * Checks if a spell ability is a killer move at the given depth.
     */
    private boolean isKillerMove(SpellAbility sa, int depth) {
        SpellAbility[] killers = killerMoves.get(depth);
        if (killers == null) {
            return false;
        }

        String saKey = getMoveKey(sa);
        for (SpellAbility killer : killers) {
            if (killer != null && getMoveKey(killer).equals(saKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a move as a killer move at the given depth.
     * Called when a move causes a beta cutoff.
     *
     * @param sa the spell ability that caused the cutoff
     * @param depth the depth at which the cutoff occurred
     */
    public void recordKillerMove(SpellAbility sa, int depth) {
        if (depth >= MAX_KILLER_DEPTH) {
            return;
        }

        SpellAbility[] killers = killerMoves.computeIfAbsent(depth, k -> new SpellAbility[KILLER_SLOTS]);

        // Don't add duplicates
        String saKey = getMoveKey(sa);
        for (SpellAbility killer : killers) {
            if (killer != null && getMoveKey(killer).equals(saKey)) {
                return;
            }
        }

        // Shift existing killers and add new one at front
        System.arraycopy(killers, 0, killers, 1, KILLER_SLOTS - 1);
        killers[0] = sa;
    }

    /**
     * Updates the history heuristic for a move.
     * Called when a move is part of the best line of play.
     *
     * @param sa the spell ability
     * @param depth the depth (used to weight the bonus)
     */
    public void updateHistory(SpellAbility sa, int depth) {
        String key = getMoveKey(sa);
        // Bonus increases with depth (deeper cutoffs are more valuable)
        int bonus = depth * depth;
        historyTable.merge(key, bonus, Integer::sum);

        // Prevent overflow by periodically scaling down
        if (historyTable.size() > 10000) {
            scaleDownHistory();
        }
    }

    /**
     * Scales down all history scores to prevent overflow.
     */
    private void scaleDownHistory() {
        historyTable.replaceAll((k, v) -> v / 2);
        // Remove entries that become zero
        historyTable.entrySet().removeIf(e -> e.getValue() == 0);
    }

    /**
     * Gets a unique key for a spell ability for comparison purposes.
     */
    private String getMoveKey(SpellAbility sa) {
        Card host = sa.getHostCard();
        String cardName = host != null ? host.getName() : "unknown";
        ApiType api = sa.getApi();
        String apiName = api != null ? api.name() : "spell";
        return cardName + ":" + apiName;
    }

    /**
     * Clears all killer moves and history.
     * Call at the start of a new search.
     */
    public void clear() {
        killerMoves.clear();
        // Don't clear history - it persists across searches
    }

    /**
     * Clears everything including history.
     * Call at the start of a new game.
     */
    public void clearAll() {
        killerMoves.clear();
        historyTable.clear();
    }

    /**
     * Returns statistics for debugging.
     */
    public String getStats() {
        return String.format("MoveOrderer: killerDepths=%d, historyEntries=%d",
                killerMoves.size(), historyTable.size());
    }
}
