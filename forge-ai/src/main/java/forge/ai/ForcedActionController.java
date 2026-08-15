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
import forge.game.zone.ZoneType;

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
 * still has to pass {@link SpellAbility#canPlay()} and {@link ComputerUtilCost#canPayCost},
 * and — the part that decides whether the rollout measured anything — it has to actually
 * get played.
 *
 * <p>That last one is not free. {@code canPayCost} is an estimate: it asks the rules
 * whether a cost <em>could</em> be paid, while paying it runs through the AI's own cost
 * decisions, which can refuse. A sacrifice cost whose only legal creature is the creature
 * the fixed targeting rule just aimed the spell at is the clearest case — the estimate says
 * yes, the payment says no. And {@link PlayerControllerAi#playChosenSpellAbility} returns
 * {@code true} whether or not the payment worked, so nothing downstream can see it. So this
 * class does not believe its own offer: it confirms the play and only then records
 * {@link Outcome#FORCED}. A rollout whose forced cast never happened comes back as
 * {@link Outcome#PAYMENT_FAILED} instead of being pooled as a measurement of a decision
 * that was never taken.
 *
 * <p>Targets are chosen by a fixed, stated rule rather than by the AI: the controlled legal
 * target with the highest power, and failing that the first legal target the rules offer.
 * The rule is deliberately crude. Its only job is to be independent of the targeting code
 * being investigated, so that a forced result cannot be explained away by "the AI picked a
 * bad target".
 *
 * <p>The window is one turn wide and, by default, one action wide. With no turn given the
 * controller arms itself on the first priority it sees — the turn a restored position
 * starts in — and after that turn it is inert, an ordinary heuristic seat again. Within the
 * window it plays the action {@code maxForces} times and then stops, because the question
 * being asked is "would taking this action have changed who won", not "would spending every
 * available mana on it have". Forcing a repeatable, self-damaging ability such as
 * Pestilence until the mana ran out measured something nobody asked about, and its cost
 * landed on the forced arm alone. A caller that really wants repetition has to say how many
 * times. {@value #MAX_ATTEMPTS} offers is the hard ceiling on top of that, so a force whose
 * payment keeps failing terminates instead of looping.
 *
 * <p>Each rollout records how many times the action was really played, the phase of the
 * first force, and how far the forcing got when it did not happen ({@link Outcome}). That
 * last one is the point: it separates "the replayed position lost something the card
 * needed" from "the AI was right to refuse".
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
        /**
         * The action was legal and the cost estimate approved it, but paying actually
         * failed, so it was never played. Until 2026-08-15 this was reported as
         * {@link #FORCED}: the forced arm was byte-for-byte the natural arm, and a
         * candidate that was never once forced was filed as measured.
         */
        PAYMENT_FAILED,
        /** The action was played. */
        FORCED
    }

    /**
     * Hard ceiling on how many times the action may be <em>offered</em> in one rollout.
     * This is the runaway guard, not the estimand: what bounds how many times the action is
     * played is {@link #getMaxForces()}, which defaults to one. The ceiling is what stops a
     * force whose payment keeps failing from being re-offered at every priority of the turn.
     */
    public static final int MAX_ATTEMPTS = 12;

    /** Actions forced per rollout when the caller does not say otherwise. */
    public static final int DEFAULT_MAX_FORCES = 1;

    private static final String PREFIX = "[AI FORCED] ";

    /** Host card name the forced action must have, exactly as the card is named. */
    private final String cardName;
    /** Lower-case substring of the ability description; empty matches any of the card's abilities. */
    private final String descNeedle;
    /** How many times the action may be played this rollout. */
    private final int maxForces;

    /** The one turn forcing is allowed in; 0 until the first priority latches it. */
    private int armedTurn;
    private boolean windowClosed;

    /** Times the action was handed to the engine, whether or not the play then succeeded. */
    private int offers;
    /** Times the action was really played. */
    private int attempts;
    /** The action handed back at this priority and not yet confirmed played. */
    private SpellAbility pendingForced;
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
        this(game, p, lp, spec, forcedTurn, DEFAULT_MAX_FORCES);
    }

    /**
     * @param spec {@code "Card Name|description-substring"}; the description part is optional.
     * @param forcedTurn the turn to force in, or 0 to arm on the first priority seen.
     * @param maxForces how many times to play the action; clamped to
     *        [1, {@value #MAX_ATTEMPTS}]. One action per rollout is what keeps the
     *        difference between the arms attributable to one decision.
     */
    public ForcedActionController(final Game game, final Player p, final LobbyPlayer lp, final String spec,
            final int forcedTurn, final int maxForces) {
        super(game, p, lp);
        final String[] parts = spec == null ? new String[0] : spec.split("\\|", 2);
        this.cardName = parts.length > 0 ? parts[0].trim() : "";
        this.descNeedle = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "";
        this.armedTurn = Math.max(forcedTurn, 0);
        this.maxForces = Math.min(Math.max(maxForces, 1), MAX_ATTEMPTS);
    }

    /** How many times the forced action was actually played this rollout. */
    public int getAttempts() {
        return attempts;
    }

    /**
     * How many times the action was handed to the engine, played or not. Larger than
     * {@link #getAttempts()} exactly when a cost the estimate approved could not be paid.
     */
    public int getOffers() {
        return offers;
    }

    /** How many times this rollout was allowed to play the action. */
    public int getMaxForces() {
        return maxForces;
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
        // Booked as an offer, not as a force. Whether it counts as a force is decided in
        // playChosenSpellAbility, once the engine has either played it or failed to.
        offers++;
        pendingForced = forced;
        emit("offered", describe(forced));
        return Lists.newArrayList(forced);
    }

    /**
     * Play the action the engine just took from {@link #chooseSpellAbilityToPlay()}, and
     * record what really happened to it.
     *
     * <p>{@link PlayerControllerAi#playChosenSpellAbility} throws away the boolean that
     * says whether the cost was paid and returns {@code true} regardless, and nothing
     * downstream of it looks either. So the play is run here instead, on the same code
     * path, and its answer is kept. Anything the controller did not force is handed
     * straight on, unchanged.
     */
    @Override
    public boolean playChosenSpellAbility(final SpellAbility sa) {
        if (sa == null || sa != pendingForced) {
            return super.playChosenSpellAbility(sa);
        }
        pendingForced = null;
        if (playAndConfirm(sa)) {
            attempts++;
            if (firstForcedPhase == null) {
                firstForcedPhase = String.valueOf(getGame().getPhaseHandler().getPhase());
            }
            outcome = Outcome.FORCED;
            emit("forced", describe(sa));
        } else {
            // furthest(), not assignment: a rollout that forced successfully earlier and
            // then failed a repeat payment still forced.
            outcome = furthest(outcome, Outcome.PAYMENT_FAILED);
            emit("payment-failed", describe(sa));
        }
        // The engine's contract for an AI seat is unchanged: true either way. A false here
        // would send the game down the snapshot-rollback path, which attribution runs with
        // snapshots off.
        return true;
    }

    /**
     * Play {@code sa} and say whether it really happened.
     *
     * <p>A land play resolves immediately and reports nothing, so it is confirmed by the
     * only evidence there is — the card reaching the battlefield. Everything else is run
     * through the same helper {@link PlayerControllerAi} uses, keeping the answer it drops.
     */
    private boolean playAndConfirm(final SpellAbility sa) {
        if (!sa.isLandAbility()) {
            return ComputerUtil.handlePlayingSpellAbility(player, sa, getGame());
        }
        final Card host = sa.getHostCard();
        final ZoneType before = zoneOf(host);
        super.playChosenSpellAbility(sa);
        final ZoneType after = zoneOf(host == null ? null : getGame().getCardState(host));
        return after == ZoneType.Battlefield && before != ZoneType.Battlefield;
    }

    private static ZoneType zoneOf(final Card card) {
        if (card == null || card.getZone() == null) {
            return null;
        }
        return card.getZone().getZoneType();
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
        if (attempts >= maxForces) {
            // The action has been played as many times as this rollout asked for. The turn
            // is not over, so say so once and go back to being an ordinary heuristic seat.
            windowClosed = true;
            emit("force-limit-reached", null);
            return false;
        }
        if (offers >= MAX_ATTEMPTS) {
            // Offered over and over without ever being played — a cost the estimate keeps
            // approving and the payment keeps refusing. Stop rather than spin.
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
     * One line per offer, one per force or failed payment, and one the first time the
     * forcing reaches a new stage, so a rollout run as a subprocess can be read from its
     * output alone. No line at all means the action was never offered.
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
        sb.append(",\"offers\":").append(offers);
        sb.append(",\"limit\":").append(maxForces);
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
