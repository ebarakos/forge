package forge.ai.llm;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Serializes decision options into numbered text for LLM consumption.
 */
public final class OptionSerializer {
    private OptionSerializer() {}

    /**
     * Serialize a list of SpellAbilities as numbered options.
     * Includes a PASS option at the end.
     */
    public static String serializeSpellOptions(List<SpellAbility> spells) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = 0; i < spells.size(); i++) {
            SpellAbility sa = spells.get(i);
            sb.append(i).append(": ");
            serializeSpellAbility(sb, sa);
            sb.append('\n');
        }
        sb.append(spells.size()).append(": PASS (do nothing)\n");
        return sb.toString();
    }

    /**
     * Serialize a list of cards as numbered options.
     */
    public static String serializeCardOptions(List<? extends Card> cards, boolean includeNone) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = 0; i < cards.size(); i++) {
            sb.append(i).append(": ").append(GameStateSerializer.serializeCardFull(cards.get(i))).append('\n');
        }
        if (includeNone) {
            sb.append(cards.size()).append(": NONE (choose nothing)\n");
        }
        return sb.toString();
    }

    /**
     * Serialize a list of entities (cards, players) as numbered options.
     */
    public static <T extends GameEntity> String serializeEntityOptions(List<T> entities, boolean includeNone) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = 0; i < entities.size(); i++) {
            T entity = entities.get(i);
            sb.append(i).append(": ");
            if (entity instanceof Card) {
                sb.append(GameStateSerializer.serializeCardFull((Card) entity));
            } else {
                sb.append(entity.getName());
            }
            sb.append('\n');
        }
        if (includeNone) {
            sb.append(entities.size()).append(": NONE (choose nothing)\n");
        }
        return sb.toString();
    }

    /**
     * Serialize a boolean choice.
     */
    public static String serializeBooleanOptions(String question) {
        return "QUESTION: " + question + "\nOPTIONS:\n0: Yes\n1: No\n";
    }

    /**
     * Serialize a list of strings as numbered options.
     */
    public static String serializeStringOptions(List<String> options) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = 0; i < options.size(); i++) {
            sb.append(i).append(": ").append(options.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Serialize a number range as options.
     */
    public static String serializeNumberOptions(int min, int max) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = min; i <= max; i++) {
            sb.append(i - min).append(": ").append(i).append('\n');
        }
        return sb.toString();
    }

    /**
     * Serialize a list of generic objects as numbered options.
     */
    public static <T> String serializeGenericOptions(List<T> options) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = 0; i < options.size(); i++) {
            sb.append(i).append(": ").append(options.get(i).toString()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Serialize attackers for combat declaration.
     * Each creature: attack or don't attack.
     */
    public static String serializeAttackOption(Card creature) {
        String desc = GameStateSerializer.serializeCardBattlefield(creature);
        return "Should this creature attack?\n" + desc
                + "\nOPTIONS:\n0: Attack\n1: Don't attack\n";
    }

    /**
     * Serialize blocking options for one attacker.
     */
    public static String serializeBlockOptions(Card attacker, List<Card> blockerCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Attacker: ").append(GameStateSerializer.serializeCardBattlefield(attacker)).append('\n');
        sb.append("OPTIONS:\n");
        for (int i = 0; i < blockerCandidates.size(); i++) {
            sb.append(i).append(": Block with ")
              .append(GameStateSerializer.serializeCardBattlefield(blockerCandidates.get(i))).append('\n');
        }
        sb.append(blockerCandidates.size()).append(": Don't block\n");
        return sb.toString();
    }

    /**
     * Serialize scry/surveil options for one card.
     */
    public static String serializeScryOption(Card card, boolean isSurveil) {
        String desc = GameStateSerializer.serializeCardFull(card);
        String bottomLabel = isSurveil ? "Graveyard" : "Bottom";
        return "Card: " + desc + "\nOPTIONS:\n0: Top of library\n1: " + bottomLabel + "\n";
    }

    /**
     * Serialize all potential attackers as a single batch prompt.
     * The LLM responds with comma-separated indices of creatures to attack with.
     */
    public static String serializeBatchAttackOptions(List<Card> canAttack) {
        StringBuilder sb = new StringBuilder();
        sb.append("Which creatures should attack? Respond with comma-separated numbers (e.g. 0,2) or NONE.\n\n");
        for (int i = 0; i < canAttack.size(); i++) {
            sb.append(i).append(": ").append(GameStateSerializer.serializeCardBattlefield(canAttack.get(i))).append('\n');
        }
        return sb.toString();
    }

    private static void serializeSpellAbility(StringBuilder sb, SpellAbility sa) {
        Card host = sa.getHostCard();
        if (host == null) {
            sb.append(sa.toString());
            return;
        }

        // Card name + mana cost
        sb.append(host.getName());
        if (!host.getManaCost().isNoCost()) {
            sb.append(" (").append(host.getManaCost().getSimpleString()).append(')');
        }

        // Card type
        sb.append(" [").append(host.getType().toString()).append(']');

        // P/T for creatures
        if (host.isCreature()) {
            sb.append(' ').append(host.getNetPower()).append('/').append(host.getNetToughness());
        }

        // Oracle text — most important for LLM understanding
        String oracle = host.getOracleText();
        if (oracle != null && !oracle.isEmpty()) {
            if (oracle.length() > 150) {
                oracle = oracle.substring(0, 147) + "...";
            }
            sb.append(" - \"").append(oracle).append('"');
        } else {
            // Fall back to SA description if no oracle text
            String desc = sa.getStackDescription();
            if (desc != null && !desc.isEmpty()) {
                sb.append(" - ").append(desc);
            } else {
                String saDesc = sa.getDescription();
                if (saDesc != null && !saDesc.isEmpty()) {
                    if (saDesc.length() > 150) {
                        saDesc = saDesc.substring(0, 147) + "...";
                    }
                    sb.append(" - \"").append(saDesc).append('"');
                }
            }
        }
    }
}
