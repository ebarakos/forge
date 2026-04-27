package forge.ai.llm;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;

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
        return serializeSpellOptions(spells, null);
    }

    /**
     * Serialize a list of SpellAbilities with optional per-option score deltas.
     * Pass {@code evalDeltas == null} to skip the eval hint annotation. When
     * provided, the list must be parallel to {@code spells}; nulls within the
     * list are treated as "no hint for this index".
     */
    public static String serializeSpellOptions(List<SpellAbility> spells, List<Integer> evalDeltas) {
        StringBuilder sb = new StringBuilder("OPTIONS:\n");
        for (int i = 0; i < spells.size(); i++) {
            SpellAbility sa = spells.get(i);
            sb.append(i).append(": ");
            serializeSpellAbility(sb, sa);
            if (evalDeltas != null && i < evalDeltas.size() && evalDeltas.get(i) != null) {
                int delta = evalDeltas.get(i);
                String sign = delta >= 0 ? "+" : "";
                sb.append("  [eval ").append(sign).append(delta).append("]");
            }
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
     * Serialize all attackers and available blockers as a single batch prompt.
     * LLM responds with block assignments like "A0:B1, A2:B0,B1" or NONE.
     */
    public static String serializeBatchBlockOptions(List<Card> attackers, List<Card> blockers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Assign blockers to attackers. Format: A0:B1, A2:B0,B1 (gang-block). Use NONE for no blocks.\n\n");
        sb.append("Attackers:\n");
        for (int i = 0; i < attackers.size(); i++) {
            sb.append("  A").append(i).append(": ")
              .append(GameStateSerializer.serializeCardBattlefield(attackers.get(i))).append('\n');
        }
        sb.append("\nYour available blockers:\n");
        for (int i = 0; i < blockers.size(); i++) {
            sb.append("  B").append(i).append(": ")
              .append(GameStateSerializer.serializeCardBattlefield(blockers.get(i))).append('\n');
        }
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
     * Serialize cards for a batched scry/surveil decision.
     * LLM responds with comma-separated indices of cards to keep on top.
     */
    public static String serializeBatchScryOptions(List<Card> cards, boolean isSurveil) {
        String bottomLabel = isSurveil ? "graveyard" : "bottom of library";
        StringBuilder sb = new StringBuilder();
        sb.append("Choose which cards to keep on TOP of library. ")
          .append("Respond with comma-separated numbers (e.g. 0,2) for cards to keep on top, ")
          .append("NONE for all to ").append(bottomLabel).append(", or ALL to keep all on top.\n\n");
        for (int i = 0; i < cards.size(); i++) {
            sb.append(i).append(": ").append(GameStateSerializer.serializeCardFull(cards.get(i))).append('\n');
        }
        return sb.toString();
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

        // Show pre-validated targets (set by validateAndSetTargets before serialization)
        appendTargetInfo(sb, sa);
    }

    /**
     * Append target information to a spell ability description.
     * Targets are set by the heuristic AI's targeting logic before the LLM sees options.
     */
    private static void appendTargetInfo(StringBuilder sb, SpellAbility sa) {
        if (!sa.usesTargeting()) return;
        TargetChoices targets = sa.getTargets();
        if (targets == null || targets.isEmpty()) return;

        sb.append(" -> targeting ");
        boolean first = true;

        // Targeted cards
        CardCollectionView targetCards = targets.getTargetCards();
        for (Card c : targetCards) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(c.getName());
            if (c.isCreature()) {
                sb.append(" (").append(c.getNetPower()).append('/').append(c.getNetToughness()).append(')');
            }
        }

        // Targeted players
        for (Player p : targets.getTargetPlayers()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(p.getName());
        }

        // Targeted spells on stack
        for (SpellAbility tgtSa : targets.getTargetSpells()) {
            if (!first) sb.append(", ");
            first = false;
            Card tgtHost = tgtSa.getHostCard();
            sb.append(tgtHost != null ? tgtHost.getName() : tgtSa.toString()).append(" (on stack)");
        }
    }
}
