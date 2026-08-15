package forge.ai;

import java.util.List;
import java.util.Locale;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * An AI seat that plays one named spell or ability during one turn whether or not the
 * heuristic AI wants to.
 *
 * <p>It exists to answer a question the heuristic AI cannot be asked directly: when the AI
 * refuses a card, is the refusal the reason it went on to lose the game? The only way to
 * find out is to replay the same position with the refusal overruled and compare outcomes.
 * So this controller overrules exactly one decision — which spell or ability to play at
 * priority — and nothing else.
 *
 * <p>What it bypasses is the AI's judgement: {@code canPlaySa}, {@code willPayCosts} and
 * the per-API targeting logic, all of which are the code under investigation. What it never
 * bypasses is the rules. The forced action still has to be a normally enumerated option,
 * still has to pass {@link SpellAbility#canPlay()}, and still has to pass
 * {@link ComputerUtilCost#canPayCost} before it is handed back — that last check because
 * {@link PlayerControllerAi#playChosenSpellAbility} returns {@code true} even when payment
 * fails, so an unpayable pick would vanish without a trace.
 *
 * <p>Targets are chosen by a fixed, stated rule rather than by the AI: the controlled legal
 * target with the highest power, and failing that the first legal target the rules offer.
 * The rule is deliberately crude. Its only job is to be independent of the targeting code
 * being investigated, so that a forced result cannot be explained away by "the AI picked a
 * bad target".
 *
 * <p>The window is one turn wide. With no turn given the controller arms itself on the
 * first priority it sees — the turn a restored position starts in — and after that turn it
 * is inert, an ordinary heuristic seat again. Within the window it forces at most
 * {@value #MAX_ATTEMPTS} times, which is what stops a repeatable ability from looping
 * forever.
 *
 * <p>Each rollout records how many times the action was forced, the phase of the first
 * force, and how far the forcing got when it did not happen ({@link Outcome}). That last
 * one is the point: it separates "the replayed position lost something the card needed"
 * from "the AI was right to refuse".
 */
public class ForcedActionController extends PlayerControllerAi {

    /**
     * How far the forcing got. The constants are ordered by how far along the pipeline they
     * are, so the furthest one reached during a rollout is the one worth reporting.
     *
     * <p>{@link #NEVER_OFFERED} also covers an option that was enumerated but rules-illegal
     * — from the outside those are the same thing, an action that was not available to play.
     */
    public enum Outcome {
        /** No enumerated option named the card, or the ones that did could not legally be played. */
        NEVER_OFFERED,
        /** The action was legal, but {@link ComputerUtilCost#canPayCost} said it could not be paid for. */
        COST_CHECK_FAILED,
        /** The action was legal and payable, but the rules offered no target the fixed rule could use. */
        NO_LEGAL_TARGET,
        /** The action was played. */
        FORCED
    }

    /** Most forces allowed in one rollout; the guard against a repeatable ability looping. */
    public static final int MAX_ATTEMPTS = 12;

    private static final String PREFIX = "[AI FORCED] ";

    /** Host card name the forced action must have, exactly as the card is named. */
    private final String cardName;
    /** Lower-case substring of the ability description; empty matches any of the card's abilities. */
    private final String descNeedle;

    /** The one turn forcing is allowed in; 0 until the first priority latches it. */
    private int armedTurn;
    private boolean windowClosed;

    private int attempts;
    private String firstForcedPhase;
    private Outcome outcome = Outcome.NEVER_OFFERED;

    /** Arms on the first priority this controller sees. */
    public ForcedActionController(final Game game, final Player p, final LobbyPlayer lp, final String spec) {
        this(game, p, lp, spec, 0);
    }

    /**
     * @param spec {@code "Card Name|description-substring"}; the description part is optional.
     * @param forcedTurn the turn to force in, or 0 to arm on the first priority seen.
     */
    public ForcedActionController(final Game game, final Player p, final LobbyPlayer lp, final String spec,
            final int forcedTurn) {
        super(game, p, lp);
        final String[] parts = spec == null ? new String[0] : spec.split("\\|", 2);
        this.cardName = parts.length > 0 ? parts[0].trim() : "";
        this.descNeedle = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        this.armedTurn = Math.max(forcedTurn, 0);
    }

    /** How many times the forced action was actually played this rollout. */
    public int getAttempts() {
        return attempts;
    }

    /** The phase of the first force, or null if it never happened. */
    public String getFirstForcedPhase() {
        return firstForcedPhase;
    }

    /** The furthest the forcing got this rollout. */
    public Outcome getOutcome() {
        return outcome;
    }

    /** The turn forcing is allowed in; 0 until the first priority latches it. */
    public int getArmedTurn() {
        return armedTurn;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // The heuristic runs first and unconditionally, so every per-priority reset it does
        // still happens and a closed window leaves behaviour exactly as it was.
        final List<SpellAbility> natural = super.chooseSpellAbilityToPlay();
        if (!armed()) {
            return natural;
        }
        final SpellAbility forced = findForcedAction();
        if (forced == null) {
            return natural;
        }
        attempts++;
        if (firstForcedPhase == null) {
            firstForcedPhase = String.valueOf(getGame().getPhaseHandler().getPhase());
        }
        outcome = Outcome.FORCED;
        emit("forced", describe(forced));
        return Lists.newArrayList(forced);
    }

    /** Is this priority inside the forcing window? Latches the window on the first call. */
    private boolean armed() {
        if (cardName.isEmpty() || windowClosed) {
            return false;
        }
        final int turn = getGame().getPhaseHandler().getTurn();
        if (armedTurn == 0) {
            armedTurn = turn;
        }
        if (turn > armedTurn) {
            windowClosed = true;
            return false;
        }
        if (turn < armedTurn) {
            return false;
        }
        if (attempts >= MAX_ATTEMPTS) {
            // Spent, but the turn is not over; say so once and stop looking.
            windowClosed = true;
            emit("attempt-cap-reached", null);
            return false;
        }
        return true;
    }

    /**
     * The forced action if it can be played right now, otherwise null with the taxonomy
     * updated to say how far it got.
     *
     * <p>The option list is built the way {@link AiController} builds its own, so "offered"
     * here means the same thing it means to the AI. Cost is checked before targets are
     * chosen; the price of that order is that a Ward cost on the eventual target is not
     * counted, since there is no target yet to read it from.
     */
    private SpellAbility findForcedAction() {
        final Game game = getGame();
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        cards = ComputerUtilCard.dedupeCards(cards);
        final List<SpellAbility> offered = ComputerUtilAbility.getSpellAbilities(cards, player);

        Outcome furthest = Outcome.NEVER_OFFERED;
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(offered, player)) {
            if (!matches(sa)) {
                continue;
            }
            sa.setActivatingPlayer(player);
            if (!sa.canPlay()) {
                continue;
            }
            if (!ComputerUtilCost.canPayCost(sa, player, sa.isTrigger())) {
                furthest = furthest(furthest, Outcome.COST_CHECK_FAILED);
                continue;
            }
            if (!assignTargets(sa)) {
                furthest = furthest(furthest, Outcome.NO_LEGAL_TARGET);
                continue;
            }
            return sa;
        }
        if (furthest.ordinal() > outcome.ordinal()) {
            outcome = furthest;
            emit(label(furthest), null);
        }
        return null;
    }

    /** Does this option name the card and, if a description substring was given, contain it? */
    private boolean matches(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (host == null || !cardName.equals(host.getName())) {
            return false;
        }
        if (descNeedle.isEmpty()) {
            return true;
        }
        final String desc = sa.getDescription();
        return desc != null && desc.toLowerCase(Locale.ROOT).contains(descNeedle);
    }

    /**
     * Fill in targets by the fixed rule, for the ability and every sub-ability that targets.
     * Leaves no targets behind when it fails, so a rejected option is handed on untouched.
     */
    private boolean assignTargets(final SpellAbility sa) {
        boolean filled = true;
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (!part.usesTargeting()) {
                continue;
            }
            part.resetTargets();
            // One target unless the rules demand more; the fixed rule is a rule about
            // which target, not about how many.
            final int want = Math.min(Math.max(part.getMinTargets(), 1), part.getMaxTargets());
            while (part.getTargets().size() < want && part.canAddMoreTarget()) {
                final GameEntity pick = pickTarget(part);
                if (pick == null) {
                    break;
                }
                part.getTargets().add(pick);
            }
            if (!part.isTargetNumberValid()) {
                filled = false;
                break;
            }
        }
        if (!filled) {
            for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
                if (part.usesTargeting()) {
                    part.resetTargets();
                }
            }
        }
        return filled;
    }

    /**
     * The fixed targeting rule: the controlled legal target with the highest power, and
     * failing that the first legal target the rules offer. Deliberately not the AI's choice.
     */
    private GameEntity pickTarget(final SpellAbility sa) {
        final List<GameEntity> candidates = sa.getTargetRestrictions().getAllCandidates(sa, true);
        Card best = null;
        for (final GameEntity candidate : candidates) {
            if (!(candidate instanceof Card) || sa.getTargets().contains(candidate)) {
                continue;
            }
            final Card card = (Card) candidate;
            if (!player.equals(card.getController())) {
                continue;
            }
            if (best == null || card.getNetPower() > best.getNetPower()) {
                best = card;
            }
        }
        if (best != null) {
            return best;
        }
        for (final GameEntity candidate : candidates) {
            if (!sa.getTargets().contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Outcome furthest(final Outcome a, final Outcome b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static String label(final Outcome stage) {
        return stage.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String describe(final SpellAbility sa) {
        final String host = sa.getHostCard() == null ? "?" : sa.getHostCard().getName();
        String desc = sa.getDescription();
        if (desc == null || desc.isEmpty()) {
            return host;
        }
        if (desc.length() > 80) {
            desc = desc.substring(0, 80);
        }
        return host + " (" + desc + ")";
    }

    /**
     * One line per force and one line the first time the forcing reaches a new stage, so a
     * rollout run as a subprocess can be read from its output alone. No line at all means
     * the action was never offered.
     */
    private void emit(final String event, final String option) {
        final StringBuilder sb = new StringBuilder(PREFIX);
        sb.append("{\"event\":");
        quote(sb, event);
        sb.append(",\"card\":");
        quote(sb, cardName);
        sb.append(",\"turn\":").append(getGame().getPhaseHandler().getTurn());
        sb.append(",\"phase\":");
        quote(sb, String.valueOf(getGame().getPhaseHandler().getPhase()));
        sb.append(",\"attempts\":").append(attempts);
        sb.append(",\"option\":");
        quote(sb, option);
        sb.append('}');
        System.err.println(sb);
    }

    private static void quote(final StringBuilder sb, final String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}
