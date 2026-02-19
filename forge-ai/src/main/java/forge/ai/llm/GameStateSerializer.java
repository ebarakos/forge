package forge.ai.llm;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes the game state from a player's perspective into rich text
 * for LLM consumption.
 */
public final class GameStateSerializer {
    private GameStateSerializer() {}

    /**
     * Serialize complete game state from the given player's perspective.
     */
    public static String serializeGameState(Player me, Game game) {
        StringBuilder sb = new StringBuilder();

        Player opp = null;
        for (Player p : game.getPlayers()) {
            if (p != me) { opp = p; break; }
        }

        // Header
        String phase = game.getPhaseHandler().getPhase() != null
                ? game.getPhaseHandler().getPhase().toString() : "UNKNOWN";
        boolean myTurn = game.getPhaseHandler().isPlayerTurn(me);
        sb.append("GAME STATE (Turn ").append(game.getPhaseHandler().getTurn())
          .append(", ").append(myTurn ? "your " : "opponent's ")
          .append(phase).append("):\n");

        // Life totals and zone sizes
        sb.append("You: ").append(me.getLife()).append(" life");
        sb.append(" | Hand: ").append(me.getCardsIn(ZoneType.Hand).size());
        sb.append(" | Library: ").append(me.getCardsIn(ZoneType.Library).size());
        sb.append('\n');

        if (opp != null) {
            sb.append("Opponent: ").append(opp.getLife()).append(" life");
            sb.append(" | Hand: ").append(opp.getCardsIn(ZoneType.Hand).size());
            sb.append(" | Library: ").append(opp.getCardsIn(ZoneType.Library).size());
            sb.append('\n');
        }

        // Available mana
        serializeAvailableMana(sb, me);

        // Your battlefield
        sb.append("\nYOUR BATTLEFIELD:\n");
        serializeBattlefield(sb, me.getCardsIn(ZoneType.Battlefield));

        // Opponent's battlefield
        if (opp != null) {
            sb.append("\nOPPONENT'S BATTLEFIELD:\n");
            serializeBattlefield(sb, opp.getCardsIn(ZoneType.Battlefield));
        }

        // Your hand (full details)
        sb.append("\nYOUR HAND:\n");
        CardCollectionView hand = me.getCardsIn(ZoneType.Hand);
        if (hand.isEmpty()) {
            sb.append("  (empty)\n");
        } else {
            for (Card c : hand) {
                sb.append("  - ").append(serializeCardFull(c)).append('\n');
            }
        }

        // Stack
        if (!game.getStack().isEmpty()) {
            sb.append("\nSTACK:\n");
            for (SpellAbilityStackInstance si : game.getStack()) {
                sb.append("  - ").append(si.getStackDescription()).append('\n');
            }
        }

        // Graveyards (compact)
        appendZoneCompact(sb, "YOUR GRAVEYARD", me.getCardsIn(ZoneType.Graveyard));
        if (opp != null) {
            appendZoneCompact(sb, "OPPONENT'S GRAVEYARD", opp.getCardsIn(ZoneType.Graveyard));
        }

        // Exile (compact, only if non-empty)
        CardCollectionView myExile = me.getCardsIn(ZoneType.Exile);
        if (!myExile.isEmpty()) {
            appendZoneCompact(sb, "YOUR EXILE", myExile);
        }

        return sb.toString();
    }

    /**
     * Serialize available mana from untapped mana sources.
     * Shows color breakdown and total, e.g. "Available mana: 2W 1U 1C (4 total)"
     */
    private static void serializeAvailableMana(StringBuilder sb, Player player) {
        // Count producible mana by color from untapped sources
        Map<Character, Integer> colorCounts = new LinkedHashMap<>();
        colorCounts.put('W', 0);
        colorCounts.put('U', 0);
        colorCounts.put('B', 0);
        colorCounts.put('R', 0);
        colorCounts.put('G', 0);
        colorCounts.put('C', 0);

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped()) continue;
            FCollectionView<SpellAbility> manaAbilities = c.getManaAbilities();
            if (manaAbilities.isEmpty()) continue;

            // Use the first mana ability to determine what this source produces
            boolean counted = false;
            for (SpellAbility ma : manaAbilities) {
                if (counted) break;
                AbilityManaPart manaPart = ma.getManaPart();
                if (manaPart == null) continue;
                String produced = manaPart.getOrigProduced();
                if (produced == null || produced.isEmpty()) continue;

                if (produced.contains("Any")) {
                    // "Any" color — count as one of each WUBRG? No, count as 1 generic.
                    // Best representation: count as 1 "any" mana
                    colorCounts.put('A', colorCounts.getOrDefault('A', 0) + 1);
                    counted = true;
                } else {
                    // Parse individual color letters from produced string (e.g. "W", "G G", "W U")
                    for (String token : produced.split("\\s+")) {
                        if (token.length() == 1 && colorCounts.containsKey(token.charAt(0))) {
                            colorCounts.merge(token.charAt(0), 1, Integer::sum);
                            counted = true;
                        } else if ("C".equals(token) || "1".equals(token)) {
                            colorCounts.merge('C', 1, Integer::sum);
                            counted = true;
                        }
                    }
                }
                // Only count first playable mana ability per source
                if (counted) break;
            }
        }

        // Also include mana currently in pool
        int poolTotal = player.getManaPool().totalMana();

        int total = poolTotal;
        for (int v : colorCounts.values()) total += v;

        if (total == 0) return;

        sb.append("Available mana: ");
        boolean first = true;
        for (Map.Entry<Character, Integer> entry : colorCounts.entrySet()) {
            if (entry.getValue() == 0) continue;
            if (!first) sb.append(' ');
            first = false;
            char color = entry.getKey();
            String label = color == 'A' ? "Any" : String.valueOf(color);
            sb.append(entry.getValue()).append(label);
        }
        if (poolTotal > 0) {
            if (!first) sb.append(' ');
            sb.append("+").append(poolTotal).append(" in pool");
        }
        sb.append(" (").append(total).append(" total)\n");
    }

    private static void serializeBattlefield(StringBuilder sb, CardCollectionView cards) {
        if (cards.isEmpty()) {
            sb.append("  (empty)\n");
            return;
        }

        // Separate lands and non-lands for readability
        List<Card> lands = new ArrayList<>();
        List<Card> nonLands = new ArrayList<>();
        for (Card c : cards) {
            if (c.isLand()) {
                lands.add(c);
            } else {
                nonLands.add(c);
            }
        }

        // Lands on one line
        if (!lands.isEmpty()) {
            sb.append("  Lands: ");
            for (int i = 0; i < lands.size(); i++) {
                if (i > 0) sb.append(", ");
                Card c = lands.get(i);
                sb.append(c.getName());
                if (c.isTapped()) sb.append(" (tapped)");
            }
            sb.append('\n');
        }

        // Non-lands with details
        for (Card c : nonLands) {
            sb.append("  - ").append(serializeCardBattlefield(c)).append('\n');
        }
    }

    /**
     * Serialize a card on the battlefield with relevant game state info.
     */
    static String serializeCardBattlefield(Card c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getName());

        if (c.isCreature()) {
            sb.append(" (").append(c.getNetPower()).append('/').append(c.getNetToughness()).append(')');
        }
        if (c.isPlaneswalker()) {
            sb.append(" [Loyalty: ").append(c.getCurrentLoyalty()).append(']');
        }

        // Key keywords
        List<String> keywords = getRelevantKeywords(c);
        if (!keywords.isEmpty()) {
            sb.append(" {").append(String.join(", ", keywords)).append('}');
        }

        if (c.isTapped()) sb.append(" (tapped)");
        if (c.hasSickness() && c.isCreature()) sb.append(" (summoning sick)");

        // Counters
        if (c.hasCounters()) {
            for (Map.Entry<CounterType, Integer> entry : c.getCounters().entrySet()) {
                if (entry.getValue() > 0) {
                    sb.append(" [").append(entry.getValue()).append(' ')
                      .append(entry.getKey().getName()).append(']');
                }
            }
        }

        // Attached cards (auras, equipment)
        if (c.isEquipped()) {
            for (Card eq : c.getEquippedBy()) {
                sb.append(" <equipped: ").append(eq.getName()).append('>');
            }
        }
        if (c.isEnchanted()) {
            for (Card aura : c.getEnchantedBy()) {
                sb.append(" <enchanted: ").append(aura.getName()).append('>');
            }
        }

        // Oracle text for non-creature, non-land permanents (enchantments, artifacts, etc.)
        if (!c.isCreature() && !c.isLand()) {
            String oracle = c.getOracleText();
            if (oracle != null && !oracle.isEmpty()) {
                if (oracle.length() > 100) oracle = oracle.substring(0, 97) + "...";
                sb.append(" - \"").append(oracle).append('"');
            }
        }

        return sb.toString();
    }

    /**
     * Serialize a card with full details (for hand/options).
     */
    static String serializeCardFull(Card c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.getName());

        // Mana cost
        if (!c.getManaCost().isNoCost()) {
            sb.append(" (").append(c.getManaCost().getSimpleString()).append(')');
        }

        // Type line
        sb.append(" [").append(c.getType().toString()).append(']');

        // P/T for creatures
        if (c.isCreature()) {
            sb.append(" ").append(c.getNetPower()).append('/').append(c.getNetToughness());
        }

        // Oracle text (truncated)
        String oracle = c.getOracleText();
        if (oracle != null && !oracle.isEmpty()) {
            if (oracle.length() > 120) {
                oracle = oracle.substring(0, 117) + "...";
            }
            sb.append(" - \"").append(oracle).append('"');
        }

        return sb.toString();
    }

    private static List<String> getRelevantKeywords(Card c) {
        List<String> result = new ArrayList<>();
        String[] important = {
            "Flying", "First strike", "Double strike", "Trample", "Haste",
            "Vigilance", "Deathtouch", "Lifelink", "Menace", "Reach",
            "Hexproof", "Indestructible", "Flash", "Defender", "Ward"
        };
        for (String kw : important) {
            if (c.hasKeyword(kw)) {
                result.add(kw);
            }
        }
        return result;
    }

    private static void appendZoneCompact(StringBuilder sb, String label, CardCollectionView cards) {
        if (cards.isEmpty()) return;
        sb.append('\n').append(label).append(": ");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cards.get(i).getName());
        }
        sb.append('\n');
    }
}
