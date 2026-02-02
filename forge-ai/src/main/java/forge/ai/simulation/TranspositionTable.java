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

import forge.ai.simulation.GameStateEvaluator.Score;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A transposition table for caching evaluated game positions during simulation.
 * Uses LRU (Least Recently Used) eviction when the table reaches its maximum size.
 *
 * This optimization avoids re-evaluating the same game state multiple times,
 * which can significantly speed up deeper simulation searches.
 */
public class TranspositionTable {

    /**
     * Entry in the transposition table storing the evaluated score and search depth.
     */
    public static class TTEntry {
        public final Score score;
        public final int depth;
        public final EntryType type;

        public TTEntry(Score score, int depth, EntryType type) {
            this.score = score;
            this.depth = depth;
            this.type = type;
        }
    }

    /**
     * Type of entry - indicates how the score was computed.
     */
    public enum EntryType {
        /** Exact score at this depth */
        EXACT,
        /** Lower bound (alpha cutoff) */
        LOWER_BOUND,
        /** Upper bound (beta cutoff) */
        UPPER_BOUND
    }

    private final LinkedHashMap<Long, TTEntry> table;
    private final int maxSize;
    private int hits;
    private int misses;

    /**
     * Creates a transposition table with the specified maximum size.
     * Uses LRU eviction when full.
     *
     * @param maxSize maximum number of entries to store
     */
    public TranspositionTable(int maxSize) {
        this.maxSize = maxSize;
        this.hits = 0;
        this.misses = 0;

        // LinkedHashMap with accessOrder=true implements LRU
        this.table = new LinkedHashMap<Long, TTEntry>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, TTEntry> eldest) {
                return size() > TranspositionTable.this.maxSize;
            }
        };
    }

    /**
     * Creates a transposition table with default size (100,000 entries).
     */
    public TranspositionTable() {
        this(100000);
    }

    /**
     * Stores an entry in the transposition table.
     *
     * @param hash the game state hash
     * @param score the evaluated score
     * @param depth the search depth at which this was evaluated
     * @param type the type of entry (exact, lower bound, upper bound)
     */
    public void store(long hash, Score score, int depth, EntryType type) {
        TTEntry existing = table.get(hash);

        // Only replace if:
        // - No existing entry, OR
        // - New entry is at same or greater depth
        if (existing == null || depth >= existing.depth) {
            table.put(hash, new TTEntry(score, depth, type));
        }
    }

    /**
     * Retrieves an entry from the transposition table.
     *
     * @param hash the game state hash to look up
     * @return the cached entry, or null if not found
     */
    public TTEntry probe(long hash) {
        TTEntry entry = table.get(hash);
        if (entry != null) {
            hits++;
        } else {
            misses++;
        }
        return entry;
    }

    /**
     * Checks if an entry exists and is usable for the current search depth.
     *
     * @param hash the game state hash
     * @param depth the current search depth
     * @return the entry if it exists and was searched at >= current depth, null otherwise
     */
    public TTEntry probeForDepth(long hash, int depth) {
        TTEntry entry = probe(hash);
        if (entry != null && entry.depth >= depth) {
            return entry;
        }
        return null;
    }

    /**
     * Clears all entries from the table.
     */
    public void clear() {
        table.clear();
        hits = 0;
        misses = 0;
    }

    /**
     * Returns the number of entries currently stored.
     * @return entry count
     */
    public int size() {
        return table.size();
    }

    /**
     * Returns the hit count (successful lookups).
     * @return hit count
     */
    public int getHits() {
        return hits;
    }

    /**
     * Returns the miss count (unsuccessful lookups).
     * @return miss count
     */
    public int getMisses() {
        return misses;
    }

    /**
     * Returns the hit rate as a percentage.
     * @return hit rate (0.0 to 1.0)
     */
    public double getHitRate() {
        int total = hits + misses;
        if (total == 0) return 0.0;
        return (double) hits / total;
    }

    /**
     * Returns a summary string for debugging/logging.
     * @return summary of table statistics
     */
    public String getStatsSummary() {
        return String.format("TranspositionTable: size=%d, hits=%d, misses=%d, hitRate=%.2f%%",
                size(), hits, misses, getHitRate() * 100);
    }
}
