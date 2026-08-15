package forge.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import forge.StaticData;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;

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
 *
 * <h2>Board dumps</h2>
 *
 * <p>The trace says the AI refused a card; it does not say whether taking that action
 * would have changed who won. Answering that needs the board the refusal happened on, so
 * the position can be replayed. Set {@code FORGE_AI_STATE_DUMP_DIR} to a directory and
 * {@code FORGE_AI_STATE_DUMP_CARDS} to the cards worth watching, and a qualifying refusal
 * writes the whole board there as {@link GameState} text — the same format the puzzle
 * tests restore — and names the file in the trace record:
 *
 * <pre>
 * FORGE_AI_STATE_DUMP_CARDS="Basilisk Gate|target creature gets|4;Pestilence||3"
 * ...,"pick":null,"state":"3f9c1a02_g2_t7_Basilisk-Gate_1.state.txt","state_seat":1,...
 * </pre>
 *
 * <p>Each entry is {@code Name|description-substring|min-mana-sources} and entries are
 * joined by {@code ;}. The description substring tells two abilities of one card apart
 * (a pump from its mana ability); it is matched case-insensitively against the same
 * {@code "Card (description)"} string the trace records, so it must fall inside the first
 * 80 characters of the description. The mana-source floor is the number of the deciding
 * player's permanents that can produce mana, and it exists to stop turn-two positions,
 * where almost nothing is castable, from eating the per-game cap.
 *
 * <p>What is dumped is deliberately narrow, because the format cannot represent
 * everything:
 *
 * <ul>
 * <li><b>Own first main phase, empty stack, only.</b> There the opponent's
 * until-end-of-turn effects have expired and combat has not happened, so most of what the
 * format drops is unreachable.</li>
 * <li><b>Three dumps per game and watched card.</b> A long game would otherwise fill a
 * directory with near-identical boards.</li>
 * <li><b>Positions with monarch, initiative, or day/night set are refused</b>, and the
 * refusal does not spend the cap: the format carries none of the three, so such a board
 * would restore into a different game.</li>
 * </ul>
 *
 * <p>File names are {@code {nonce}_g{game}_t{turn}_{card}_{n}.state.txt}. The nonce is
 * random per process because seat-swapped halves of one matchup write into a single
 * directory and each half restarts its game ids at 1.
 *
 * <p>With {@code FORGE_AI_STATE_DUMP_DIR} unset, nothing here runs and the trace is
 * exactly what it was before dumps existed.
 */
public final class AiDecisionLog {

    /** Set {@code FORGE_AI_DECISION_LOG=1} to turn the trace on. */
    public static final boolean ENABLED = isTruthy(System.getenv("FORGE_AI_DECISION_LOG"));

    private static final String PREFIX = "[AI DECISION] ";
    private static final String MULLIGAN_PREFIX = "[AI MULLIGAN] ";
    private static final String COMBAT_PREFIX = "[AI COMBAT] ";
    private static final String STATE_PREFIX = "[AI STATE DUMP] ";

    /** Where board dumps go. Unset — the default — turns dumping off completely. */
    private static final String STATE_DUMP_DIR = System.getenv("FORGE_AI_STATE_DUMP_DIR");

    /** The cards worth dumping a board for, from {@code FORGE_AI_STATE_DUMP_CARDS}. */
    private static final List<WatchedCard> WATCHED =
            parseWatchedCards(System.getenv("FORGE_AI_STATE_DUMP_CARDS"));

    /** Dumping needs the trace itself, a directory to write to, and something to watch. */
    private static final boolean STATE_DUMP_ENABLED =
            ENABLED && STATE_DUMP_DIR != null && !STATE_DUMP_DIR.isEmpty() && !WATCHED.isEmpty();

    private static final int MAX_DUMPS_PER_GAME_AND_CARD = 3;

    /**
     * Separates one process's files from another's. Seat-swapped halves of a matchup write
     * into one directory and each half starts counting game ids again at 1, so file names
     * built from the game id alone would collide.
     */
    private static final String NONCE = UUID.randomUUID().toString().substring(0, 8);

    /** How many boards each (game, watched card) pair has already produced. */
    private static final ConcurrentMap<String, AtomicInteger> DUMP_COUNTS = new ConcurrentHashMap<>();

    private AiDecisionLog() {
    }

    /** One entry of {@code FORGE_AI_STATE_DUMP_CARDS}. */
    private static final class WatchedCard {
        /** Exact card name, as the trace prints it. */
        final String name;
        /** Lower-case substring of the ability description; empty matches any ability. */
        final String descNeedle;
        /** Skip the position unless the deciding player has at least this many mana sources. */
        final int minManaSources;

        private WatchedCard(String name, String descNeedle, int minManaSources) {
            this.name = name;
            this.descNeedle = descNeedle;
            this.minManaSources = minManaSources;
        }
    }

    /**
     * Read {@code Name|description-substring|min-mana-sources} entries joined by {@code ;}.
     * The last two parts are optional. An entry that cannot be read is reported and skipped
     * rather than failing the run — a broken watch list should cost dumps, not a match.
     */
    private static List<WatchedCard> parseWatchedCards(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<WatchedCard> watched = new ArrayList<>();
        for (String entry : spec.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 3);
            String name = parts[0].trim();
            if (name.isEmpty()) {
                System.err.println(STATE_PREFIX + "ignoring an entry with no card name: \"" + trimmed + "\"");
                continue;
            }
            String descNeedle = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
            int minManaSources = 0;
            if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                try {
                    minManaSources = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException e) {
                    System.err.println(STATE_PREFIX + "ignoring an unreadable mana-source floor in \""
                            + trimmed + "\"; watching the card without one");
                }
            }
            watched.add(new WatchedCard(name, descNeedle, minManaSources));
        }
        return watched;
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
     * How deep the current thread is inside a speculative evaluation — one card's AI
     * asking what it would do with a <em>different</em> card, rather than deciding the
     * option being recorded.
     *
     * <p>Some checks answer "is this worth playing?" by trying other cards. A mana
     * ritual asks whether anything in hand becomes castable with the extra mana, which
     * runs the full AI check for each of those cards. Every rejection along the way
     * calls {@link #reason(String)}. Because the first writer wins, the first card
     * tried inside such a loop would claim the reason slot, and the card actually being
     * evaluated would then be recorded carrying a stranger's explanation — a ritual
     * blamed on "sacrifice cost has no synergistic creature" when it sacrifices
     * nothing. Worse, its own genuine reason, set later, would be dropped as a
     * duplicate.
     *
     * <p>Counted rather than a flag because these loops can nest.
     */
    private static final ThreadLocal<int[]> SPECULATION = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Enter a speculative evaluation: reasons recorded until the matching
     * {@link #endSpeculation()} describe some other card and are dropped. Always pair
     * the two with try/finally, or every later reason on this thread is lost.
     */
    public static void beginSpeculation() {
        if (ENABLED) {
            SPECULATION.get()[0]++;
        }
    }

    /** Leave a speculative evaluation opened by {@link #beginSpeculation()}. */
    public static void endSpeculation() {
        if (!ENABLED) {
            return;
        }
        final int[] depth = SPECULATION.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /**
     * Name the check that is rejecting the current option, e.g.
     * {@code "PumpAi: X pump amount computes to 0"}. Call it right before returning a
     * rejection. Safe to call unconditionally: a no-op when the trace is off, while a
     * speculative evaluation is open, or when a reason is already held for this option.
     */
    public static void reason(String why) {
        if (!ENABLED || why == null || SPECULATION.get()[0] > 0) {
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
        emit(record, player, game, context, picked, null);
    }

    /**
     * Emit the decision, saying whether the option list was cut short before it was
     * exhausted.
     *
     * <p>{@code cutShort} names what ended the evaluation early, or is null when every
     * option was really considered. It matters because the two look identical otherwise:
     * a pass whose candidate list the AI time budget ended mid-way writes {@code
     * "pick":null} with a short considered list, exactly like a pass where every option
     * was weighed and rejected, and every reader treats {@code pick == null} as "the AI
     * chose to do nothing". The options never reached are the ones the evaluator ranked
     * last, so they also drop out of the considered counts without trace.
     *
     * <p>Written as a separate top-level field rather than as an entry in the considered
     * list, so a reader that does not know about it is unaffected.
     */
    public static void emit(List<String[]> record, Player player, Game game, String context,
            SpellAbility picked, String cutShort) {
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
        if (cutShort != null) {
            appendKey(sb, "truncated", cutShort);
        }
        String stateFile = dumpStateIfWatched(record, player, game, picked);
        if (stateFile != null) {
            appendKey(sb, "state", stateFile);
            sb.append(",\"state_seat\":").append(game.getPlayers().indexOf(player));
        }
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

    /**
     * Write the board to a file when this record refuses a watched card at a position the
     * {@link GameState} format can represent, and return the file name to put in the trace.
     * Returns null — writing nothing, spending nothing — in every other case.
     */
    private static String dumpStateIfWatched(List<String[]> record, Player player, Game game,
            SpellAbility picked) {
        if (!STATE_DUMP_ENABLED || player == null || game == null) {
            return null;
        }
        try {
            return dumpState(record, player, game, picked);
        } catch (Throwable t) {
            // A diagnostic must never take the match down with it: an unwritable directory
            // or a board the dumper chokes on costs this one file and nothing else.
            System.err.println(STATE_PREFIX + "could not dump the board: " + t);
            return null;
        }
    }

    private static String dumpState(List<String[]> record, Player player, Game game,
            SpellAbility picked) throws IOException {
        PhaseHandler ph = game.getPhaseHandler();
        // The deciding player's own first main phase with nothing on the stack. Elsewhere
        // the format's blind spots — the stack, until-end-of-turn effects, this-turn
        // memory — are reachable, and a board that restores wrong is worse than none.
        if (ph == null || ph.getPhase() != PhaseType.MAIN1 || !player.equals(ph.getPlayerTurn())) {
            return null;
        }
        if (game.getStack() == null || !game.getStack().isEmpty()) {
            return null;
        }
        WatchedCard watched = firstRefusedWatchedCard(record, player, picked);
        if (watched == null) {
            return null;
        }
        // Monarch, initiative and day/night have no place in the format at all, so these
        // positions are refused before the cap is touched: a game that happens to have a
        // monarch should still be able to contribute its later, cleaner positions.
        if (game.getMonarch() != null || game.getHasInitiative() != null || game.getDayTime() != null) {
            return null;
        }

        AtomicInteger count = DUMP_COUNTS.computeIfAbsent(game.getId() + "|" + watched.name,
                key -> new AtomicInteger());
        int n = count.incrementAndGet();
        if (n > MAX_DUMPS_PER_GAME_AND_CARD) {
            count.decrementAndGet();
            return null;
        }
        try {
            int seat = game.getPlayers().indexOf(player);
            String fileName = NONCE + "_g" + game.getId() + "_t" + ph.getTurn() + "_"
                    + fileSafe(watched.name) + "_" + n + ".state.txt";
            GameState state = new GameState() {
                @Override
                public IPaperCard getPaperCard(String cardName, String setCode, int artID) {
                    return StaticData.instance().getCommonCards().getCard(cardName, setCode, artID);
                }
            };
            state.initFromGame(game);
            // Comment lines are skipped by the parser, so the file stays restorable while
            // saying on its face which refusal it came from.
            String board = state.toString();
            StringBuilder text = new StringBuilder(4096);
            text.append("# forge ai board dump\n");
            text.append("# card=").append(watched.name).append('\n');
            text.append("# seat=").append(seat).append('\n');
            text.append("# game=").append(game.getId()).append('\n');
            // The digest of everything below. A dump cut short by a full disk or a killed
            // process is still a valid, restorable, different position that agrees with
            // itself; without this line a reader has no way to tell it from the board that
            // was meant to be written. See StateDumpChecksum.
            text.append(StateDumpChecksum.headerLineFor(board)).append('\n');
            text.append(board);
            Path dir = Paths.get(STATE_DUMP_DIR);
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), text.toString().getBytes(StandardCharsets.UTF_8));
            return fileName;
        } catch (RuntimeException | IOException e) {
            count.decrementAndGet(); // a board that was never written did not use up the cap
            throw e;
        }
    }

    /**
     * The first watched card this record refused, or null if it refused none. An option the
     * AI actually played is not a refusal, and a watched card is skipped while the deciding
     * player is below its mana-source floor.
     */
    private static WatchedCard firstRefusedWatchedCard(List<String[]> record, Player player,
            SpellAbility picked) {
        String played = picked == null ? null : describe(picked);
        int manaSources = -1;
        for (String[] entry : record) {
            String option = entry[0];
            if (option == null || option.equals(played)) {
                continue;
            }
            for (WatchedCard watched : WATCHED) {
                if (!matchesWatched(option, watched)) {
                    continue;
                }
                if (manaSources < 0) {
                    manaSources = countManaSources(player);
                }
                if (manaSources >= watched.minManaSources) {
                    return watched;
                }
            }
        }
        return null;
    }

    /**
     * Does a considered option — {@code "Card Name (description)"} — name this watched card?
     * The host name must match exactly; the description substring is matched without regard
     * to case against the whole option, so it has to lie within the first 80 characters of
     * the description, which is all {@link #describe(SpellAbility)} keeps.
     */
    private static boolean matchesWatched(String option, WatchedCard watched) {
        if (option == null || watched == null) {
            return false;
        }
        int descStart = option.indexOf(" (");
        String host = descStart < 0 ? option : option.substring(0, descStart);
        if (!host.equals(watched.name)) {
            return false;
        }
        return watched.descNeedle.isEmpty()
                || option.toLowerCase(Locale.ROOT).contains(watched.descNeedle);
    }

    /** Permanents the player controls that can make mana, tapped ones included. */
    private static int countManaSources(Player player) {
        int sources = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (!card.getManaAbilities().isEmpty()) {
                sources++;
            }
        }
        return sources;
    }

    /** A card name reduced to something safe to put in a file name. */
    private static String fileSafe(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "card" : cleaned;
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
