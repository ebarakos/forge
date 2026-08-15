package forge.ai;

import java.util.ArrayList;
import java.util.List;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Opt-in trace of what the heuristic AI considered and what it chose.
 *
 * <p>The AI's play-by-play says what it did; this says what it was choosing between and
 * why it rejected the rest. Without that, a deck the AI pilots badly can only be
 * diagnosed by watching games by hand, which does not scale past a handful and cannot
 * count how often a given misplay happens.
 *
 * <p>Off unless {@code FORGE_AI_DECISION_LOG} is set to a truthy value, because it is
 * verbose (one line per priority) and would swamp normal output. When off the cost is a
 * single static boolean test, so the call sites are safe to leave in hot paths.
 *
 * <p>Each priority record is one line on stderr, prefixed {@code [AI DECISION]}
 * followed by a compact JSON object, so a run can be filtered with grep and parsed
 * line by line. Mulligans and declarations use {@code [AI MULLIGAN]} and
 * {@code [AI COMBAT]} respectively so their different shapes do not look like a
 * priority pass to existing parsers:
 *
 * <pre>
 * [AI DECISION] {"turn":5,"phase":"MAIN1","player":"Ai(1)","ctx":"main","game":3,
 *                "life":[20,17],"hand":5,"pick":"Lightning Bolt",
 *                "considered":[["Ponder","CantAfford",null],
 *                ["Lightning Bolt","WillPlay",null]]}
 * </pre>
 *
 * <p>The third element of a considered entry is the <b>reason</b>: which internal check
 * produced the verdict, recorded by {@link #reason(String)} from inside the rejecting
 * code. It is null when no check announced itself — the verdict alone then names the
 * layer. Reasons exist because the outer verdict is too coarse to aim a fix with: most
 * API-specific rejections surface as a bare {@code CantPlayAi}, which says a class said
 * no without saying which line.
 *
 * <p><b>The considered list is not the full option list.</b> The chooser walks options in
 * the AI's own priority order and returns the first one it is willing to play, so
 * everything after the pick was never evaluated. A {@code "pick":null} record is normally
 * a complete pass. The exception is {@code FinalGuardVeto}: the chooser found an internally
 * willing option, then the own-stack response guard rejected it before play, so evaluation
 * stopped there.
 */
public final class AiDecisionLog {

    /** Set {@code FORGE_AI_DECISION_LOG=1} to turn the trace on. */
    public static final boolean ENABLED = isTruthy(System.getenv("FORGE_AI_DECISION_LOG"));

    private static final String PREFIX = "[AI DECISION] ";
    private static final String MULLIGAN_PREFIX = "[AI MULLIGAN] ";
    private static final String COMBAT_PREFIX = "[AI COMBAT] ";

    private AiDecisionLog() {
    }

    private static boolean isTruthy(String v) {
        return v != null && !v.isEmpty()
                && !"0".equals(v) && !"false".equalsIgnoreCase(v) && !"no".equalsIgnoreCase(v);
    }

    /**
     * The specific check that rejected the option currently being evaluated. Written by
     * {@link #reason(String)} from inside the rejecting code, consumed (and cleared) by
     * {@link #consider}. First writer wins, because the deepest check runs first and is
     * the most specific; everything set later is the same rejection echoing outward.
     * Thread-local because the chooser runs its evaluation loop on its own thread.
     */
    private static final ThreadLocal<String> REASON = new ThreadLocal<>();

    /**
     * Name the check that is rejecting the current option, e.g.
     * {@code "PumpAi: X pump amount computes to 0"}. Call it right before returning a
     * rejection. Safe to call unconditionally: a no-op when the trace is off, and only
     * the first reason per option is kept.
     */
    public static void reason(String why) {
        if (!ENABLED || why == null) {
            return;
        }
        if (REASON.get() == null) {
            REASON.set(why);
        }
    }

    private static String takeReason() {
        String r = REASON.get();
        if (r != null) {
            REASON.remove();
        }
        return r;
    }

    /**
     * A scratch list for one decision. Returns null when the trace is off, so callers
     * allocate nothing in the common case.
     */
    public static List<String[]> newRecord() {
        if (!ENABLED) {
            return null;
        }
        REASON.remove(); // a stale reason from outside this decision must not attach to it
        return new ArrayList<>();
    }

    /**
     * Note one option and the verdict the AI reached on it. No-op when the trace is off.
     * Consumes any pending {@link #reason} — kept when the option was rejected, dropped
     * when the verdict is WillPlay (a reason set during a successful evaluation was a
     * sub-check that did not decide the outcome).
     */
    public static void consider(List<String[]> record, SpellAbility sa, Object verdict) {
        if (record == null || sa == null) {
            return;
        }
        // Mana abilities are offered at every priority and correctly refused at almost
        // every one ("tap Island for nothing"). They were ~40% of a real trace and say
        // nothing about play quality, so they are not recorded. Non-mana abilities of
        // lands (e.g. a pump activation) still are.
        if (sa.isManaAbility()) {
            takeReason();
            return;
        }
        String verdictName = String.valueOf(verdict);
        String why = takeReason();
        if ("WillPlay".equals(verdictName)) {
            why = null;
        }
        record.add(new String[] { describe(sa), verdictName, why });
    }

    /** Replace an internally willing pick with the final outer guard that rejected it. */
    public static void veto(List<String[]> record, SpellAbility sa, String why) {
        if (record == null || sa == null) {
            return;
        }
        String option = describe(sa);
        for (int i = record.size() - 1; i >= 0; i--) {
            String[] entry = record.get(i);
            if (option.equals(entry[0])) {
                entry[1] = "FinalGuardVeto";
                entry[2] = why;
                return;
            }
        }
    }

    /**
     * Emit the decision. {@code picked} is null when the AI chose to do nothing, which is
     * the case worth reading most closely.
     */
    public static void emit(List<String[]> record, Player player, Game game, String context,
            SpellAbility picked) {
        if (record == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(200).append(PREFIX).append('{');
        int turn = -1;
        String phase = "?";
        int gameId = -1;
        if (game != null) {
            gameId = game.getId();
            PhaseHandler ph = game.getPhaseHandler();
            if (ph != null) {
                turn = ph.getTurn();
                phase = ph.getPhase() == null ? "?" : ph.getPhase().toString();
            }
        }
        sb.append("\"turn\":").append(turn);
        appendKey(sb, "phase", phase);
        appendKey(sb, "player", player == null ? null : player.getName());
        appendKey(sb, "ctx", context);
        sb.append(",\"game\":").append(gameId);
        if (player != null) {
            sb.append(",\"life\":[").append(player.getLife()).append(',')
                    .append(player.getOpponentsSmallestLifeTotal()).append(']');
            sb.append(",\"hand\":").append(player.getCardsIn(ZoneType.Hand).size());
        }
        appendKey(sb, "pick", picked == null ? null : describe(picked));
        sb.append(",\"considered\":[");
        for (int i = 0; i < record.size(); i++) {
            String[] entry = record.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            appendQuoted(sb, entry[0]);
            sb.append(',');
            appendQuoted(sb, entry[1]);
            sb.append(',');
            appendQuoted(sb, entry.length > 2 ? entry[2] : null);
            sb.append(']');
        }
        sb.append("]}");
        System.err.println(sb);
    }

    /** Emit the actual keep/mulligan result, its scored branch, and the complete hand. */
    public static void emitMulligan(Player player, CardCollectionView hand, int cardsToReturn,
            int score, boolean keep, String reason) {
        if (!ENABLED) {
            return;
        }
        Game game = player == null ? null : player.getGame();
        StringBuilder sb = new StringBuilder(240).append(MULLIGAN_PREFIX).append('{');
        appendState(sb, player, game, "mulligan");
        appendKey(sb, "decision", keep ? "KEEP" : "MULLIGAN");
        sb.append(",\"cardsToReturn\":").append(cardsToReturn);
        sb.append(",\"score\":").append(score);
        appendKey(sb, "reason", reason);
        sb.append(",\"cards\":[");
        if (hand != null) {
            int i = 0;
            for (Card card : hand) {
                if (i++ > 0) {
                    sb.append(',');
                }
                appendQuoted(sb, describe(card));
            }
        }
        sb.append("]}");
        System.err.println(sb);
    }

    /** Emit every creature considered by the final attack declaration. */
    public static void emitAttackDeclaration(Player player, Combat combat) {
        if (!ENABLED || player == null || combat == null) {
            return;
        }
        List<String[]> record = new ArrayList<>();
        for (Card card : player.getCreaturesInPlay()) {
            if (combat.isAttacking(card)) {
                record.add(new String[] { describe(card), "Attack",
                        "AiAttackController: selected in final attack declaration against "
                                + String.valueOf(combat.getDefenderByAttacker(card)) });
            } else if (!CombatUtil.canAttack(card)) {
                record.add(new String[] { describe(card), "CantAttack",
                        "CombatUtil.canAttack: creature is not a legal attacker" });
            } else if (!CombatUtil.couldAttackButNotAttacking(combat, card)) {
                record.add(new String[] { describe(card), "CantAttack",
                        "CombatUtil.couldAttackButNotAttacking: attack cost or declaration constraint rejected creature" });
            } else {
                record.add(new String[] { describe(card), "Hold",
                        "AiAttackController: final attack heuristic held back a legal attacker" });
            }
        }
        emitCombat(record, player, player.getGame(), "attack");
    }

    /** Emit every creature considered by the final block declaration. */
    public static void emitBlockDeclaration(Player player, Combat combat) {
        if (!ENABLED || player == null || combat == null) {
            return;
        }
        List<String[]> record = new ArrayList<>();
        for (Card card : player.getCreaturesInPlay()) {
            CardCollectionView blocked = combat.getAttackersBlockedBy(card);
            if (!blocked.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Card attacker : blocked) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(attacker.getName());
                }
                record.add(new String[] { describe(card), "Block",
                        "AiBlockController: selected in final block declaration against " + names });
            } else if (!CombatUtil.canBlock(card, combat)) {
                record.add(new String[] { describe(card), "CantBlock",
                        "CombatUtil.canBlock: creature cannot be declared as a blocker" });
            } else if (!canBlockAnyAttacker(card, combat)) {
                record.add(new String[] { describe(card), "CantBlock",
                        "CombatUtil.canBlock: creature cannot legally block any current attacker" });
            } else {
                record.add(new String[] { describe(card), "Hold",
                        "AiBlockController: final block heuristic held back a legal blocker" });
            }
        }
        emitCombat(record, player, player.getGame(), "block");
    }

    private static boolean canBlockAnyAttacker(Card blocker, Combat combat) {
        for (Card attacker : combat.getAttackers()) {
            if (CombatUtil.canBlock(attacker, blocker, combat)) {
                return true;
            }
        }
        return false;
    }

    private static void emitCombat(List<String[]> record, Player player, Game game, String context) {
        StringBuilder sb = new StringBuilder(240).append(COMBAT_PREFIX).append('{');
        appendState(sb, player, game, context);
        sb.append(",\"considered\":[");
        for (int i = 0; i < record.size(); i++) {
            String[] entry = record.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            appendQuoted(sb, entry[0]);
            sb.append(',');
            appendQuoted(sb, entry[1]);
            sb.append(',');
            appendQuoted(sb, entry[2]);
            sb.append(']');
        }
        sb.append("]}");
        System.err.println(sb);
    }

    private static void appendState(StringBuilder sb, Player player, Game game, String context) {
        int turn = -1;
        String phase = "?";
        int gameId = -1;
        if (game != null) {
            gameId = game.getId();
            PhaseHandler ph = game.getPhaseHandler();
            if (ph != null) {
                turn = ph.getTurn();
                phase = ph.getPhase() == null ? "?" : ph.getPhase().toString();
            }
        }
        sb.append("\"turn\":").append(turn);
        appendKey(sb, "phase", phase);
        appendKey(sb, "player", player == null ? null : player.getName());
        appendKey(sb, "ctx", context);
        sb.append(",\"game\":").append(gameId);
        if (player != null) {
            sb.append(",\"life\":[").append(player.getLife()).append(',')
                    .append(player.getOpponentsSmallestLifeTotal()).append(']');
            sb.append(",\"hand\":").append(player.getCardsIn(ZoneType.Hand).size());
        }
    }

    /** "Card Name (ability description)" — enough to tell two abilities of one card apart. */
    private static String describe(SpellAbility sa) {
        String host = sa.getHostCard() == null ? "?" : sa.getHostCard().getName();
        String desc = sa.getDescription();
        if (desc == null || desc.isEmpty()) {
            return host;
        }
        if (desc.length() > 80) {
            desc = desc.substring(0, 80);
        }
        return host + " (" + desc + ")";
    }

    private static String describe(Card card) {
        return card == null ? "?" : card.getName();
    }

    private static void appendKey(StringBuilder sb, String key, String value) {
        sb.append(",\"").append(key).append("\":");
        appendQuoted(sb, value);
    }

    private static void appendQuoted(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '"':  sb.append("\\\""); break;
            case '\\': sb.append("\\\\"); break;
            case '\n': sb.append("\\n");  break;
            case '\r': sb.append("\\r");  break;
            case '\t': sb.append("\\t");  break;
            default:
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
