package forge.ai.nn;

/**
 * Hash-based card vocabulary for neural network card identity features.
 *
 * Maps card names to integer indices in [0, VOCAB_SIZE) using
 * {@code Math.abs(name.hashCode()) % VOCAB_SIZE}. Index 0 is reserved
 * for unknown/empty slots (null or blank names hash to 0 explicitly).
 *
 * Collisions are acceptable — the embedding will still learn useful
 * clusters for cards that happen to share an index.
 */
public final class CardVocabulary {

    /** Vocabulary size. Must match Python-side CARD_VOCAB_SIZE constant. */
    public static final int VOCAB_SIZE = 512;

    private CardVocabulary() { }

    /**
     * Return the vocabulary index for a card name.
     *
     * @param cardName the card's name as returned by {@code Card.getName()}
     * @return integer in [1, VOCAB_SIZE - 1], or 0 for null/empty names
     */
    public static int getIndex(String cardName) {
        if (cardName == null || cardName.isEmpty()) {
            return 0;
        }
        // Use absolute value of hashCode, shift into [1, VOCAB_SIZE - 1] to
        // reserve 0 for the empty/unknown sentinel.
        int raw = Math.abs(cardName.hashCode()) % (VOCAB_SIZE - 1);
        return raw + 1;  // result is in [1, 511]
    }
}
