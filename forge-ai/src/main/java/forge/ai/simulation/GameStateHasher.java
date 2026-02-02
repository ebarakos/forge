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

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for hashing game states to detect infinite loops during simulation.
 * Uses a simplified hash based on key game state elements to identify repeated positions.
 */
public class GameStateHasher {
    private final Set<Long> seenStates;
    private final int maxSeenStates;

    /**
     * Creates a new GameStateHasher with default maximum tracked states.
     */
    public GameStateHasher() {
        this(10000);
    }

    /**
     * Creates a new GameStateHasher with specified maximum tracked states.
     * @param maxStates maximum number of states to track before clearing
     */
    public GameStateHasher(int maxStates) {
        this.seenStates = new HashSet<>();
        this.maxSeenStates = maxStates;
    }

    /**
     * Computes a hash for the current game state.
     * The hash includes:
     * - Life totals for all players
     * - Number of cards in hand for each player
     * - Number of cards in graveyard for each player
     * - Battlefield permanents (by card ID)
     * - Current turn number
     * - Current phase
     *
     * @param game the game to hash
     * @param aiPlayer the AI player (perspective)
     * @return a hash value representing the game state
     */
    public long computeHash(Game game, Player aiPlayer) {
        long hash = 17;
        final long prime = 31;

        // Hash turn number
        hash = hash * prime + game.getPhaseHandler().getTurn();

        // Hash phase
        hash = hash * prime + game.getPhaseHandler().getPhase().ordinal();

        // Hash player states
        for (Player p : game.getPlayers()) {
            // Life total
            hash = hash * prime + p.getLife();

            // Hand size
            hash = hash * prime + p.getCardsIn(ZoneType.Hand).size();

            // Graveyard size
            hash = hash * prime + p.getCardsIn(ZoneType.Graveyard).size();

            // Library size (helps differentiate states)
            hash = hash * prime + p.getCardsIn(ZoneType.Library).size();

            // Poison counters
            hash = hash * prime + p.getPoisonCounters();
        }

        // Hash battlefield permanents
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            // Use card ID for unique identification
            hash = hash * prime + c.getId();

            // Include tapped state
            hash = hash * prime + (c.isTapped() ? 1 : 0);

            // Include sick state for creatures
            if (c.isCreature()) {
                hash = hash * prime + (c.isSick() ? 1 : 0);
                hash = hash * prime + c.getNetPower();
                hash = hash * prime + c.getNetToughness();
            }
        }

        // Hash stack size
        hash = hash * prime + game.getStack().size();

        return hash;
    }

    /**
     * Checks if the given game state has been seen before.
     * If not, records it and returns false.
     * If yes, returns true (indicating a potential loop).
     *
     * @param game the game to check
     * @param aiPlayer the AI player
     * @return true if this state has been seen before (potential loop), false otherwise
     */
    public boolean hasSeenState(Game game, Player aiPlayer) {
        // Clear if we've accumulated too many states
        if (seenStates.size() >= maxSeenStates) {
            seenStates.clear();
        }

        long hash = computeHash(game, aiPlayer);
        return !seenStates.add(hash); // add returns false if already present
    }

    /**
     * Clears all recorded states.
     */
    public void clear() {
        seenStates.clear();
    }

    /**
     * Returns the number of states currently tracked.
     * @return count of tracked states
     */
    public int getSeenStateCount() {
        return seenStates.size();
    }
}
