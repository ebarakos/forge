package forge.ai.simulation;

import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Represents an action (spell ability to play) in the MCTS tree.
 *
 * Stores enough information to identify the action in a candidate list
 * across game copies. Does NOT store the SpellAbility directly since
 * game copies produce new SpellAbility instances.
 */
public class MCTSAction {
    private final int candidateIndex;
    private final String description;
    private final String hostCardName;

    public static final MCTSAction PASS = new MCTSAction(-1, "PASS", "");

    public MCTSAction(int candidateIndex, String description, String hostCardName) {
        this.candidateIndex = candidateIndex;
        this.description = description;
        this.hostCardName = hostCardName;
    }

    public static MCTSAction fromSpellAbility(int index, SpellAbility sa) {
        return new MCTSAction(index, sa.toString(),
                sa.getHostCard() != null ? sa.getHostCard().getName() : "");
    }

    /**
     * Find the matching SpellAbility in a candidate list from a copied game.
     * First tries index match with description verification, then falls back
     * to description-only search.
     */
    public SpellAbility findInCandidates(List<SpellAbility> candidates) {
        if (isPass()) return null;
        // Try exact index match first
        if (candidateIndex >= 0 && candidateIndex < candidates.size()) {
            SpellAbility sa = candidates.get(candidateIndex);
            if (sa.toString().equals(description)) {
                return sa;
            }
        }
        // Fall back to description matching
        for (SpellAbility sa : candidates) {
            if (sa.toString().equals(description)) {
                return sa;
            }
        }
        return null;
    }

    public boolean isPass() {
        return candidateIndex == -1;
    }

    public int getCandidateIndex() {
        return candidateIndex;
    }

    public String getDescription() {
        return description;
    }

    public String getHostCardName() {
        return hostCardName;
    }

    @Override
    public String toString() {
        if (isPass()) return "PASS";
        return hostCardName.isEmpty() ? description : hostCardName + ": " + description;
    }
}
