package forge.ai.llm;

import forge.ai.simulation.GameStateEvaluator;
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

        // Clock summary (turns to kill at current damage rates).
        serializeClock(sb, me, opp);

        // Your battlefield
        sb.append("\nYOUR BATTLEFIELD:\n");
        serializeBattlefield(sb, me.getCardsIn(ZoneType.Battlefield), 0, false);

        // Opponent's battlefield (with threat tiers for creatures)
        if (opp != null) {
            sb.append("\nOPPONENT'S BATTLEFIELD:\n");
            int myLife = me.getLife();
            serializeBattlefield(sb, opp.getCardsIn(ZoneType.Battlefield), myLife, true);
        }

        // Your hand (full details). Duplicate cards are collapsed with an "Nx"
        // prefix so an aggro hand of {Lightning Bolt, Lightning Bolt, Mountain,
        // Mountain, Mountain} prints 2 lines instead of 5. The model still sees
        // each card's full text exactly once.
        sb.append("\nYOUR HAND:\n");
        CardCollectionView hand = me.getCardsIn(ZoneType.Hand);
        if (hand.isEmpty()) {
            sb.append("  (empty)\n");
        } else {
            LinkedHashMap<String, int[]> grouped = new LinkedHashMap<>();
            LinkedHashMap<String, Card> firstSeen = new LinkedHashMap<>();
            for (Card c : hand) {
                String key = c.getName();
                grouped.computeIfAbsent(key, k -> new int[]{0})[0]++;
                firstSeen.putIfAbsent(key, c);
            }
            for (Map.Entry<String, int[]> entry : grouped.entrySet()) {
                int count = entry.getValue()[0];
                sb.append("  - ");
                if (count > 1) sb.append(count).append("x ");
                sb.append(serializeCardFull(firstSeen.get(entry.getKey()))).append('\n');
            }
        }

        // Stack — flag spell vs triggered/activated ability and counterability so
        // the LLM does not waste a counterspell on a trigger (a real bug we
        // observed on gpt-oss-20b). Generic: works for any deck.
        if (!game.getStack().isEmpty()) {
            sb.append("\nSTACK:\n");
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility sa = si.getSpellAbility();
                Card source = si.getSourceCard();
                Player controller = sa != null ? sa.getActivatingPlayer() : null;
                String tag;
                String counterTag;
                if (sa != null && sa.isSpell()) {
                    tag = "[spell]";
                    // Spells are counterable unless host card has the keyword.
                    counterTag = (source != null && source.hasKeyword("CARDNAME can't be countered."))
                            ? " [uncounterable]" : " [counterable]";
                } else if (sa != null && sa.isTrigger()) {
                    tag = "[triggered ability — counterspells DO NOT work]";
                    counterTag = "";
                } else {
                    tag = "[activated ability — counterspells DO NOT work]";
                    counterTag = "";
                }
                String who = controller != null
                        ? (controller == me ? "yours" : "opponent's")
                        : "?";
                sb.append("  - ").append(tag).append(counterTag)
                  .append(" (").append(who).append(") ")
                  .append(si.getStackDescription()).append('\n');
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
     * Serialize the combat clock for both players: how many turns until each
     * player dies at current attack rates. Two figures per side: total combat
     * damage (assuming nothing blocks — optimistic), and evasive-only damage
     * (lower bound, since evasive creatures are unblockable for this stat).
     */
    private static void serializeClock(StringBuilder sb, Player me, Player opp) {
        if (opp == null) return;
        int myTotal = GameStateEvaluator.totalCombatDamage(me);
        int myEvasive = GameStateEvaluator.evasiveDamage(me);
        int oppTotal = GameStateEvaluator.totalCombatDamage(opp);
        int oppEvasive = GameStateEvaluator.evasiveDamage(opp);
        if (myTotal == 0 && oppTotal == 0) return;
        sb.append("Clock: ");
        sb.append("you kill them in ").append(turnsLabel(opp.getLife(), myTotal, myEvasive));
        sb.append("; they kill you in ").append(turnsLabel(me.getLife(), oppTotal, oppEvasive));
        sb.append('\n');
    }

    private static String turnsLabel(int life, int total, int evasive) {
        int totalT = GameStateEvaluator.turnsToKill(life, total);
        int evasiveT = GameStateEvaluator.turnsToKill(life, evasive);
        if (total == 0) return "no clock";
        if (evasive >= total || evasive == 0) {
            return totalT + (totalT == 1 ? " turn" : " turns")
                    + " (" + total + " dmg/turn)";
        }
        return totalT + " turns unblocked / " + evasiveT + " turns evasive-only";
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

    /**
     * Serialize one player's battlefield. When {@code annotateThreats} is true,
     * each opposing creature is tagged with a T1..T4 threat tier based on the
     * recipient's life total (T1 = lethal-next-turn class).
     */
    private static void serializeBattlefield(StringBuilder sb, CardCollectionView cards,
                                              int recipientLife, boolean annotateThreats) {
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

        // Non-lands with details. Creatures stay individual (combat math depends
        // on per-creature P/T, tap, sickness, counters). Other permanents that
        // share an identical board state — same name AND no distinguishing
        // counters/attachments/tapped state — are collapsed to "Nx <name>" so
        // a board with three Journey to Nowhere or four Clue Tokens emits one
        // oracle line, not three. Generic: works for any deck.
        List<Card> creatures = new ArrayList<>();
        List<Card> nonCreaturePermanents = new ArrayList<>();
        for (Card c : nonLands) {
            if (c.isCreature()) creatures.add(c);
            else nonCreaturePermanents.add(c);
        }
        for (Card c : creatures) {
            sb.append("  - ").append(serializeCardBattlefield(c));
            if (annotateThreats) {
                String tier = creatureThreatTier(c, recipientLife);
                if (tier != null) sb.append(' ').append(tier);
            }
            sb.append('\n');
        }
        // Group by (name, stateKey). Stateful copies stay separate.
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        LinkedHashMap<String, Card> firstByKey = new LinkedHashMap<>();
        for (Card c : nonCreaturePermanents) {
            String key = c.getName() + "|" + permanentStateKey(c);
            counts.computeIfAbsent(key, k -> new int[]{0})[0]++;
            firstByKey.putIfAbsent(key, c);
        }
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            int n = entry.getValue()[0];
            sb.append("  - ");
            if (n > 1) sb.append(n).append("x ");
            sb.append(serializeCardBattlefield(firstByKey.get(entry.getKey()))).append('\n');
        }
    }

    /** Stable identity for collapsing identical-state permanents. Empty when
     *  the permanent has any distinguishing state, which forces individual
     *  emission. */
    private static String permanentStateKey(Card c) {
        if (c.isTapped() || c.hasCounters() || c.isEquipped() || c.isEnchanted()) {
            // Use a per-card UID so identical-named-but-differently-stated
            // permanents do not collapse together.
            return "id:" + c.getId();
        }
        return "clean";
    }

    /**
     * Tier an opposing creature: T1 = lethal-class (clocks in 1-2 turns), T2 =
     * fast clock (3 turns), T3 = engine/value, T4 = filler. Tapped/sick/defender
     * creatures get no tier (they aren't an immediate threat).
     */
    private static String creatureThreatTier(Card c, int targetLife) {
        if (!c.isCreature()) return null;
        int power = c.getNetCombatDamage();
        if (power <= 0) {
            // Not attacking, but may be a value engine.
            String oracle = c.getOracleText();
            if (oracle != null && (oracle.toLowerCase().contains("draw") || oracle.toLowerCase().contains("each turn"))) {
                return "[T3 engine]";
            }
            return null;
        }
        if (c.isTapped() || c.isSick() || c.hasKeyword(forge.game.keyword.Keyword.DEFENDER)) {
            return null;
        }
        int turns = (Math.max(targetLife, 0) + power - 1) / Math.max(power, 1);
        if (turns <= 2) return "[T1 lethal in " + turns + "]";
        if (turns <= 3) return "[T2 kills in " + turns + "]";
        if (turns <= 5) return "[T3 clock]";
        return "[T4 filler]";
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

        // Key keywords. Some keywords are systematically misread by LLMs in
        // mirror-eval transcripts (notably "Defender" — multiple models
        // reasoned "no untapped blockers" while a {Defender} creature sat on
        // opponent's battlefield). Annotate the most-misread ones inline.
        List<String> keywords = getRelevantKeywords(c);
        if (!keywords.isEmpty()) {
            sb.append(" {").append(joinAndAnnotateKeywords(keywords)).append('}');
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

    /**
     * Stringify a list of keywords, attaching a one-clause clarifier to
     * the keywords most-commonly misread by LLMs in transcript audits.
     * Keep the additions tight — these are emitted on every creature on
     * the battlefield, so verbose annotations would balloon the prompt.
     */
    private static String joinAndAnnotateKeywords(List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String kw : keywords) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(kw);
            // Defender is the worst offender — observed multiple LLMs
            // reasoning "opponent has no blockers" while a {Defender} 0/3
            // sat untapped opposite an attacker. The keyword sounds like
            // "can't block" to a model that's pattern-matching on the
            // word "Defender = can't get past", but rules-wise it's the
            // opposite: cannot ATTACK, blocks normally.
            if ("Defender".equals(kw)) {
                sb.append("=cannot-attack-but-blocks");
            }
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
        // Dedupe identical names with "Nx" prefix so a graveyard with four
        // Lightning Bolts emits "4x Lightning Bolt" instead of repeating the
        // name. Order preserved by first-seen. Generic: works for any deck.
        LinkedHashMap<String, int[]> counts = new LinkedHashMap<>();
        for (Card c : cards) {
            counts.computeIfAbsent(c.getName(), k -> new int[]{0})[0]++;
        }
        sb.append('\n').append(label).append(": ");
        boolean first = true;
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            int n = entry.getValue()[0];
            if (n > 1) sb.append(n).append("x ");
            sb.append(entry.getKey());
        }
        sb.append('\n');
    }
}
