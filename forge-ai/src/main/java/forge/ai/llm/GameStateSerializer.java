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
     * Whether a creature counts toward its controller's clock.
     *
     * <p>A clock is what each side can do at its <em>next</em> combat, so whether
     * a creature is tapped or summoning-sick right now is not consulted: a tapped
     * creature untaps in its controller's untap step, and one that entered this
     * turn has lost its sickness by their next combat. Both are still there, and
     * both will attack.
     *
     * <p>Counting only what can attack this instant is what printed
     * {@code they kill you in no clock} in game 2 of the recorded Burn run. Burn
     * was at 3 life on its turn-9 draw step, facing a tapped 9/7 trample hexproof
     * Slippery Bogle that would untap and kill it on the following turn. The model
     * worked out the lethal for itself from the battlefield list, which is proof
     * the position held the information and the summary threw it away.
     *
     * <p>Defenders never attack, and a creature with no power lands nothing.
     *
     * @param hasDefender the creature has defender
     * @param power       its combat damage
     */
    static boolean clocksNextTurn(boolean hasDefender, int power) {
        return !hasDefender && power > 0;
    }

    /**
     * Total combat damage a player can land at their next combat, assuming
     * nothing blocks. Optimistic upper bound, and the projected counterpart of
     * {@link GameStateEvaluator#totalCombatDamage(Player)} — which stays as it is
     * because the heuristic evaluator reads it as a right-now figure.
     */
    private static int projectedCombatDamage(Player p) {
        int total = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isCreature()) continue;
            int power = c.getNetCombatDamage();
            if (clocksNextTurn(c.hasKeyword(forge.game.keyword.Keyword.DEFENDER), power)) {
                total += power;
            }
        }
        return total;
    }

    /**
     * The part of {@link #projectedCombatDamage(Player)} that cannot be blocked —
     * a lower bound on what lands at the next combat whatever the defender does.
     */
    private static int projectedEvasiveDamage(Player p) {
        int total = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isCreature()) continue;
            int power = c.getNetCombatDamage();
            if (!clocksNextTurn(c.hasKeyword(forge.game.keyword.Keyword.DEFENDER), power)) {
                continue;
            }
            if (c.hasKeyword(forge.game.keyword.Keyword.FLYING)
                    || c.hasKeyword(forge.game.keyword.Keyword.HORSEMANSHIP)
                    || forge.game.staticability.StaticAbilityCantAttackBlock.cantBlockBy(c, null)) {
                total += power;
            }
        }
        return total;
    }

    /**
     * Serialize the combat clock for both players: how many turns until each
     * player dies, counting what each side can attack with at its next combat.
     * Two figures per side: total combat damage (assuming nothing blocks —
     * optimistic), and evasive-only damage (a lower bound, since evasive
     * creatures are unblockable for this stat).
     *
     * <p>The line says "after untap" out loud, because the figures are a
     * projection: a side whose creatures are all tapped right now still has a
     * clock, and a reader must not take "you kill them in 1 turn" as "this turn".
     */
    private static void serializeClock(StringBuilder sb, Player me, Player opp) {
        if (opp == null) return;
        int myTotal = projectedCombatDamage(me);
        int myEvasive = projectedEvasiveDamage(me);
        int oppTotal = projectedCombatDamage(opp);
        int oppEvasive = projectedEvasiveDamage(opp);
        if (myTotal == 0 && oppTotal == 0) return;
        sb.append("Clock (each side's next combat, after untap): ");
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
     * Serialize available mana from untapped mana sources, reading <em>every</em>
     * mana ability each source has rather than only the first.
     *
     * <p>Colours a source can choose between are printed as a choice, e.g.
     * {@code "Available mana: 2R 1C 2{W/U} (5 total)"} — five mana, two of which
     * may each be spent as white or as blue. A source that adds several mana at
     * once contributes each of them.
     */
    private static void serializeAvailableMana(StringBuilder sb, Player player) {
        // Mana whose colour is already settled: one entry per colour.
        Map<Character, Integer> colorCounts = new LinkedHashMap<>();
        colorCounts.put('W', 0);
        colorCounts.put('U', 0);
        colorCounts.put('B', 0);
        colorCounts.put('R', 0);
        colorCounts.put('G', 0);
        colorCounts.put('C', 0);
        // Mana the player still chooses a colour for, grouped by the set of
        // colours on offer ("WU" -> 2 means two mana, each white or blue).
        Map<String, Integer> choiceCounts = new LinkedHashMap<>();

        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped()) continue;
            FCollectionView<SpellAbility> manaAbilities = c.getManaAbilities();
            if (manaAbilities.isEmpty()) continue;

            List<ManaOption> options = new ArrayList<>();
            for (SpellAbility ma : manaAbilities) {
                AbilityManaPart manaPart = ma.getManaPart();
                if (manaPart == null) continue;
                ManaOption option = ManaOption.read(manaPart.getOrigProduced(), repetitions(ma));
                if (option != null) options.add(option);
            }

            ManaOption offer = ManaOption.merge(options);
            if (offer == null) continue;
            if (offer.choice) {
                choiceCounts.merge(offer.colors, offer.amount, Integer::sum);
            } else {
                for (int i = 0; i < offer.colors.length(); i++) {
                    colorCounts.merge(offer.colors.charAt(i), 1, Integer::sum);
                }
            }
        }

        // Also include mana currently in pool
        int poolTotal = player.getManaPool().totalMana();

        int total = poolTotal;
        for (int v : colorCounts.values()) total += v;
        for (int v : choiceCounts.values()) total += v;

        if (total == 0) return;

        sb.append("Available mana: ");
        boolean first = true;
        for (Map.Entry<Character, Integer> entry : colorCounts.entrySet()) {
            if (entry.getValue() == 0) continue;
            if (!first) sb.append(' ');
            first = false;
            sb.append(entry.getValue()).append(fixedLabel(entry.getKey()));
        }
        for (Map.Entry<String, Integer> entry : choiceCounts.entrySet()) {
            if (entry.getValue() == 0) continue;
            if (!first) sb.append(' ');
            first = false;
            sb.append(entry.getValue()).append(choiceLabel(entry.getKey()));
        }
        if (poolTotal > 0) {
            if (!first) sb.append(' ');
            sb.append("+").append(poolTotal).append(" in pool");
        }
        sb.append(" (").append(total).append(" total)\n");
    }

    /** How many times one activation repeats its {@code Produced$} string. */
    private static int repetitions(SpellAbility ma) {
        String amount = ma.getParam("Amount");
        if (amount == null) return 1;
        try {
            return Math.max(1, Integer.parseInt(amount.trim()));
        } catch (NumberFormatException e) {
            // A computed amount ("Count$..."). Reading it needs the ability to be
            // activated; assume one rather than guess high.
            return 1;
        }
    }

    /** Print name of a settled colour. */
    static String fixedLabel(char color) {
        if (color == 'A') return "Any";
        if (color == '?') return "?";
        return String.valueOf(color);
    }

    /** Print name of a set of colours the player picks from, e.g. {@code "{W/U}"}. */
    static String choiceLabel(String colors) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < colors.length(); i++) {
            if (i > 0) sb.append('/');
            sb.append(fixedLabel(colors.charAt(i)));
        }
        return sb.append('}').toString();
    }

    /**
     * What one mana ability adds to the pool: how many mana, which colours, and
     * whether those colours are alternatives or are all produced together.
     *
     * <p>It exists because the card script's {@code Produced$} string comes in
     * shapes that mean different things and used to be read as one.
     * {@code "G U"} adds two mana, one green and one blue. {@code "Combo W U"}
     * adds one mana that is white <em>or</em> blue — a dual land. {@code "Any"}
     * adds one mana of any colour. Reading a Combo string as a plain list is
     * what made a dual land look like two mana of two fixed colours, and
     * stopping at a source's first ability is what made a land with one ability
     * per colour look like it made only the first one.
     */
    static final class ManaOption {
        /** Colour letters: one per mana when {@link #choice} is false, one per alternative when it is true. */
        final String colors;
        /** How many mana one activation adds. */
        final int amount;
        /** True when {@link #colors} lists alternatives and one is chosen per mana. */
        final boolean choice;

        private ManaOption(String colors, int amount, boolean choice) {
            this.colors = colors;
            this.amount = amount;
            this.choice = choice;
        }

        /**
         * Read one ability's output.
         *
         * @param produced    the raw {@code Produced$} string from the card script
         * @param repetitions the ability's {@code Amount$}, i.e. how many times
         *                    the produced string is repeated
         * @return what the ability adds, or null when the string names no mana
         */
        static ManaOption read(String produced, int repetitions) {
            if (produced == null) return null;
            String trimmed = produced.trim();
            if (trimmed.isEmpty()) return null;
            int reps = Math.max(1, repetitions);

            String[] tokens = trimmed.split("\\s+");
            boolean combo = tokens.length > 0 && "Combo".equals(tokens[0]);
            StringBuilder symbols = new StringBuilder();
            for (int i = combo ? 1 : 0; i < tokens.length; i++) {
                appendSymbols(symbols, tokens[i], combo);
            }
            if (symbols.length() == 0) return null;

            if (!combo) {
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < reps; i++) all.append(symbols);
                return new ManaOption(all.toString(), all.length(), false);
            }
            String alternatives = normalizeChoice(symbols.toString());
            if (alternatives.length() == 1) {
                // One alternative is not a choice: it is that colour, reps times.
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < reps; i++) all.append(alternatives);
                return new ManaOption(all.toString(), reps, false);
            }
            return new ManaOption(alternatives, reps, true);
        }

        /**
         * What a source with several mana abilities can add. Separate abilities
         * are alternatives — a land taps once — so the merged view is a choice
         * between their colours, and the amount is the most any one of them adds.
         *
         * @return null when the source names no mana at all
         */
        static ManaOption merge(List<ManaOption> options) {
            if (options == null || options.isEmpty()) return null;
            ManaOption firstOption = options.get(0);
            if (options.size() == 1) return firstOption;

            boolean allSame = true;
            for (ManaOption o : options) {
                if (o.choice != firstOption.choice || o.amount != firstOption.amount
                        || !o.colors.equals(firstOption.colors)) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) return firstOption;

            StringBuilder union = new StringBuilder();
            int amount = 0;
            for (ManaOption o : options) {
                amount = Math.max(amount, o.amount);
                for (int i = 0; i < o.colors.length(); i++) {
                    char sym = o.colors.charAt(i);
                    if (union.indexOf(String.valueOf(sym)) < 0) union.append(sym);
                }
            }
            String alternatives = normalizeChoice(union.toString());
            if (alternatives.length() == 1) {
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < amount; i++) all.append(alternatives);
                return new ManaOption(all.toString(), amount, false);
            }
            return new ManaOption(alternatives, amount, true);
        }

        /** Collapse a set of alternatives that already means "any colour". */
        private static String normalizeChoice(String symbols) {
            if (symbols.indexOf('A') >= 0) return "A";
            boolean everyColor = true;
            for (char color : new char[] {'W', 'U', 'B', 'R', 'G'}) {
                if (symbols.indexOf(color) < 0) { everyColor = false; break; }
            }
            return everyColor ? "A" : symbols;
        }

        /** Turn one {@code Produced$} token into colour letters. */
        private static void appendSymbols(StringBuilder out, String token, boolean dedupe) {
            if (token == null || token.isEmpty()) return;
            if ("Any".equals(token)) {
                append(out, 'A', dedupe);
                return;
            }
            if (token.length() == 1 && "WUBRGC".indexOf(token.charAt(0)) >= 0) {
                append(out, token.charAt(0), dedupe);
                return;
            }
            if (isNumeric(token)) {
                // Generic mana is produced as colourless.
                int n = Integer.parseInt(token);
                for (int i = 0; i < n; i++) append(out, 'C', dedupe);
                return;
            }
            // Chosen, ColorID, NotedColors, ColorIdentity, Special… — a real
            // mana whose colour is not known until the ability resolves. Say so
            // rather than drop it, which used to undercount the mana available.
            append(out, '?', dedupe);
        }

        private static void append(StringBuilder out, char symbol, boolean dedupe) {
            if (dedupe && out.indexOf(String.valueOf(symbol)) >= 0) return;
            out.append(symbol);
        }

        private static boolean isNumeric(String token) {
            for (int i = 0; i < token.length(); i++) {
                if (!Character.isDigit(token.charAt(i))) return false;
            }
            return true;
        }
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
