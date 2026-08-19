package forge.ai.llm;

import com.google.common.collect.ListMultimap;
import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.PlanarDice;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.cost.CostPart;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.zone.PlayerZone;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * LLM Full controller: routes nearly every decision method through the
 * {@link LLMClient}. On any LLM failure (timeout, parse error, budget
 * exceeded), falls back to the heuristic parent {@link PlayerControllerAi}.
 */
public class LLMFullController extends PlayerControllerAi {

    /** Package-private so {@link LLMSpellSelection} and {@link LLMCombat} can call it. */
    final LLMClient client;

    /** Sliding window of recent action summaries for LLM context. */
    private final List<String> actionHistory = new ArrayList<>();
    // Without prompt caching: 6 entries ≈ 2 turns of action; older history
    // rarely informs the current decision (board state already reflects it)
    // and just costs full-price tokens. With prompt caching the history lives
    // in the byte-stable cached prefix block (cheap cache-read pricing), and
    // dropping the oldest entry shifts the prefix start — invalidating the
    // cached block — so use a generous cap that keeps the prefix append-only
    // for almost the whole game; truncation is only a late-game backstop.
    private static final int MAX_HISTORY_UNCACHED = 6;
    private static final int MAX_HISTORY_CACHED = 50;
    private final int maxHistory;

    /** B2: blocking-plan note carried over from the most recent attack LLM call.
     *  Package-private so {@link LLMCombat} can read/clear it. */
    String lastAttackPlan = "";

    /** B1: cached sequence of card names to cast this MAIN phase (from a single plan LLM call).
     *  Package-private so {@link LLMSpellSelection} can mutate it. */
    final java.util.Deque<String> mainPhasePlan = new java.util.ArrayDeque<>();
    PhaseType planPhase = null;
    int planTurn = -1;
    int planStackSize = 0;
    /** What the opponent's side looked like when the plan was written. */
    String planOpponentState = null;
    /** Which permanents the seat had when the plan was written. */
    Set<Integer> planOwnPermanents = null;

    /** D1: cached serialized game state, keyed by the board it describes. */
    private String cachedGameState = null;
    private String cachedStateKey = null;

    /**
     * Per-option eval hints (PR3.c) — opt-in via {@code FORGE_LLM_EVAL_HINTS=true}.
     * Off by default because building a fresh {@link forge.ai.simulation.GameSimulator}
     * per candidate is expensive on the current GameCopier; PR4 makes this cheap.
     * Capped at {@link #EVAL_HINT_MAX_CANDIDATES} to bound CPU when the hand is wide.
     */
    static final boolean EVAL_HINTS_ENABLED = isTruthy(switchValue("FORGE_LLM_EVAL_HINTS"));
    static final int EVAL_HINT_MAX_CANDIDATES = 6;

    // =======================================================================
    // Muzzles: which decisions the heuristic AI keeps for itself
    // =======================================================================
    //
    // An "LLM seat" does not actually decide everything. Eleven places below
    // hand a decision back to the heuristic AI, either always or whenever the
    // heuristic disagrees with the model. That is deliberate — each one was
    // added because it won games — but it also means a run labelled "LLM" is
    // measuring a mixture, and an experiment that wants to know what the model
    // alone is worth has to be able to switch every one of them off.
    //
    // So each muzzle has its own environment variable, and FORGE_LLM_UNMUZZLED
    // flips all of them to the model at once. An individual variable, when
    // set, always wins over the aggregate, so one muzzle can be put back
    // without unsetting the rest. Everything unset behaves exactly as before
    // these switches existed.
    //
    //   FORGE_LLM_UNMUZZLED             off  — aggregate: give every decision below to the model
    //   FORGE_LLM_TRUST_HEURISTIC_TOP   on   — replace the model's first plan step with the
    //                                          heuristic's own pick whenever the two differ
    //   FORGE_LLM_COMBAT                heuristic — who declares attackers and blockers
    //                                          (heuristic | llm | shadow)
    //   FORGE_LLM_TOPK                  8    — how many spell options the model gets to see
    //                                          (0 = no cap)
    //   FORGE_LLM_PRUNE_HOPELESS        on   — hide options the heuristic calls fundamentally bad
    //   FORGE_LLM_SINGLE_OPTION_SHORTCUT on  — with exactly one playable spell, let the heuristic
    //                                          decide it and skip the LLM call
    //   FORGE_LLM_PLAN_STEP_APPROVAL    on   — re-ask the heuristic before each step of the
    //                                          model's own plan is played
    //   FORGE_LLM_EMPTY_PLAN_OVERRIDE   on   — when the model chose to hold, cast anyway if the
    //                                          heuristic wants to play something
    //   FORGE_LLM_HEURISTIC_LAND_DROPS  on   — the heuristic picks which land to play, and when
    //   FORGE_LLM_HEURISTIC_TARGETS     on   — the heuristic decides where each spell points
    //   FORGE_LLM_HARDCODED_PLAY_FIRST  on   — choose to play first in games two and three
    //                                          without asking (game one never asks either way)
    //   FORGE_LLM_INSTANT_SPEED_GATE    on   — outside the seat's own main phases, the heuristic
    //                                          decides unless there is a stack response or a
    //                                          combat trick to consider
    //
    // Truthiness for the boolean switches follows the rest of FORGE_LLM_*:
    // anything except "0" or "false" (case-insensitive) counts as on; unset or
    // empty falls through to the default in the list above. All of them are read
    // the way the rest of the LLM configuration is — system property, then process
    // environment, then a .env file walked up from the working directory — so a
    // .env that de-muzzles a run really does de-muzzle it.

    /**
     * Aggregate de-muzzle switch. Truthy means every muzzle in this block
     * defaults to "the model decides" instead of "the heuristic decides".
     * Declared first because the muzzle constants below read it.
     */
    static final boolean UNMUZZLED = isTruthy(switchValue("FORGE_LLM_UNMUZZLED"));

    /**
     * Heuristic-prior pruning: cap the number of spell options shown to the
     * LLM and sort heuristic-preferred candidates first. Set
     * {@code FORGE_LLM_TOPK=0} to disable; defaults to 8 (0 when unmuzzled).
     * Lower = cheaper prompts + tighter LLM attention; higher = more freedom
     * for the LLM to disagree with the heuristic.
     */
    static final int HEURISTIC_TOPK = parseIntEnv("FORGE_LLM_TOPK", UNMUZZLED ? 0 : 8);

    /**
     * Drop options the heuristic gave a hopeless verdict (CantPlaySa,
     * BadEtbEffects, DoesntImpactGame, …) before the model ever sees them.
     * Off ({@code FORGE_LLM_PRUNE_HOPELESS=0}) keeps them in the list, ranked
     * last, so the model can disagree with the heuristic about what is bad.
     */
    static final boolean PRUNE_HOPELESS = muzzleOn("FORGE_LLM_PRUNE_HOPELESS");

    /**
     * With exactly one playable spell, ask the heuristic instead of the model
     * and skip the LLM call. Saves a call per trivial decision, but it also
     * means the heuristic — not the model — decides every forced-looking turn.
     * Off ({@code FORGE_LLM_SINGLE_OPTION_SHORTCUT=0}) sends the single option
     * to the model like any other.
     */
    static final boolean SINGLE_OPTION_SHORTCUT = muzzleOn("FORGE_LLM_SINGLE_OPTION_SHORTCUT");

    /**
     * Re-ask the heuristic before playing each step of the model's own cached
     * MAIN-phase plan; a step the heuristic will not endorse is skipped. Off
     * ({@code FORGE_LLM_PLAN_STEP_APPROVAL=0}) plays the plan as written.
     */
    static final boolean PLAN_STEP_APPROVAL = muzzleOn("FORGE_LLM_PLAN_STEP_APPROVAL");

    /**
     * When the model returns an empty plan — it chose to hold — cast the
     * heuristic's pick anyway if the heuristic is willing. Added because small
     * models emit {@code plan:[]} when their reasoning truncates, but it also
     * makes "hold everything" unreachable for a model that meant it. Off
     * ({@code FORGE_LLM_EMPTY_PLAN_OVERRIDE=0}) honours the pass.
     */
    static final boolean EMPTY_PLAN_OVERRIDE = muzzleOn("FORGE_LLM_EMPTY_PLAN_OVERRIDE");

    /**
     * Let the heuristic choose which land to play and whether to hold the land
     * drop; the model never sees land abilities. Off
     * ({@code FORGE_LLM_HEURISTIC_LAND_DROPS=0}) lists land plays among the
     * model's options like any other play.
     */
    static final boolean HEURISTIC_LAND_DROPS = muzzleOn("FORGE_LLM_HEURISTIC_LAND_DROPS");

    /**
     * Let the heuristic decide where every spell and ability points. This is
     * not a decision the seat was ever asked for: targets are set as a side
     * effect of the heuristic's own {@code canPlaySa} check while the option
     * list is being built, so a burn spell's face-versus-creature choice
     * belonged to the heuristic even on a fully "LLM" seat. Off
     * ({@code FORGE_LLM_HEURISTIC_TARGETS=0}) the model is shown the legal
     * targets of the ability it just chose and re-points it. See
     * {@link LLMTargeting} for what stays with the heuristic even then.
     */
    static final boolean HEURISTIC_TARGETS = muzzleOn("FORGE_LLM_HEURISTIC_TARGETS");

    /**
     * Choose to play first without asking anyone. Off
     * ({@code FORGE_LLM_HARDCODED_PLAY_FIRST=0}) asks the model to choose play
     * or draw in games two and three; a failed call still falls back to playing
     * first. Game one is decided without the model whatever this is set to — see
     * {@link #playsFirstWithoutAsking(boolean, boolean, boolean)}.
     */
    static final boolean HARDCODED_PLAY_FIRST = muzzleOn("FORGE_LLM_HARDCODED_PLAY_FIRST");

    /**
     * Outside the seat's own main phases, hand the decision to the heuristic unless
     * {@link #shouldCallLLMForInstantSpeed()} sees something worth asking about — a spell
     * on the stack to answer, or an attack to answer during the opponent's combat.
     *
     * <p>This one is the quietest of the muzzles and it was the only one with no switch,
     * so a fully "unmuzzled" run still had the heuristic making every upkeep, draw, own-combat
     * and end-step decision. Those are not idle windows: the heuristic deliberately holds
     * plays for them — a cycling land at the end step before its own turn, a pump waiting for
     * its own declare-attackers — so an A/B run under {@code FORGE_LLM_UNMUZZLED} was still
     * measuring a mixture and attributing the whole of it to the model.
     *
     * <p>Off ({@code FORGE_LLM_INSTANT_SPEED_GATE=0}, or {@code FORGE_LLM_UNMUZZLED}) every
     * such priority goes to the model instead. Falling through is safe — the option list is
     * filtered by {@code canCastTiming}, {@code canPayCost} and target validity, so a
     * priority pass with nothing castable is a free PASS with no LLM call — but it is not
     * free in tokens: each non-MAIN priority that does have a castable instant now costs a
     * call, which is well above the call-per-game figure a relay budget would be sized from.
     */
    static final boolean INSTANT_SPEED_GATE = muzzleOn("FORGE_LLM_INSTANT_SPEED_GATE");

    /**
     * Heuristic-prior verdict annotation: tag each option with the heuristic
     * AI's own opinion (WillPlay / WaitForMain2 / Removal / …). Off by default
     * because tags add ~10 tokens per option; flip on for tuning runs.
     * Pruning of hopeless candidates still happens regardless of this flag.
     */
    static final boolean VERDICT_TAGS_ENABLED = isTruthy(switchValue("FORGE_LLM_VERDICT_TAGS"));

    /**
     * Shadow mode: log heuristic vs LLM divergence per call to stderr without
     * changing behaviour. Useful for offline analysis of captured transcripts.
     * Opt-in via {@code FORGE_LLM_SHADOW=1}.
     */
    static final boolean SHADOW_MODE = isTruthy(switchValue("FORGE_LLM_SHADOW"));

    /**
     * Combat decision source ({@code FORGE_LLM_COMBAT}):
     * <ul>
     *   <li>{@code heuristic} (default) — apply the heuristic attack/block
     *       prior directly and skip the prompt build + LLM call entirely;</li>
     *   <li>{@code llm} — LLM decides; the prior only annotates the prompt
     *       (the pre-flag behaviour);</li>
     *   <li>{@code shadow} — LLM is consulted and divergence logged via the
     *       combat shadow telemetry, but the heuristic prior is applied.</li>
     * </ul>
     * Unset or unrecognised values normalise to {@code heuristic}
     * ({@code llm} when unmuzzled).
     */
    static final String COMBAT_MODE = parseCombatMode(switchValue("FORGE_LLM_COMBAT"),
            UNMUZZLED ? "llm" : "heuristic");

    /**
     * Heuristic-priority override: when the LLM's first plan step is not the
     * spell the heuristic would have played, play the heuristic's spell
     * instead. Documented evidence (mirror-vs-heuristic eval, 4 models ×
     * 4 decks): LLM divergences from the heuristic's pick are uniformly
     * net-negative, costing 25-50 percentage points vs the heuristic's mirror
     * baseline. On by default; flip off via
     * {@code FORGE_LLM_TRUST_HEURISTIC_TOP=0} for A/B testing.
     */
    static final boolean TRUST_HEURISTIC_TOP = muzzleOn("FORGE_LLM_TRUST_HEURISTIC_TOP");

    /**
     * How deep we are inside a heuristic feasibility check (canPayCost,
     * {@link #validateAndSetTargets}) — a question of the form "could this be
     * cast at all?", whose answer is thrown away. Cost-paying callbacks such as
     * {@link #chooseCardsToDelve} check it so they answer from the heuristic
     * instead of burning an LLM call on a spell that is not being cast.
     *
     * <p>A depth counter rather than a flag, and every entry paired with an
     * exit in a {@code finally}. It used to be a boolean that call sites set to
     * false when they were done, which is wrong two ways: an inner check
     * finishing un-guarded the outer one it was nested inside, and any throw
     * between the set and the reset left the flag stuck on for the rest of the
     * game — silently turning the model back into the heuristic for every
     * decision that consults it. Per-thread because parallel matches each run
     * on their own controller.
     */
    private static final ThreadLocal<Integer> FEASIBILITY_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    /** True while a "could this be cast?" check is running. */
    static boolean isCheckingFeasibility() {
        return FEASIBILITY_DEPTH.get() > 0;
    }

    /** Open a feasibility check. Always pair with {@link #exitFeasibility()} in a finally. */
    static void enterFeasibility() {
        FEASIBILITY_DEPTH.set(FEASIBILITY_DEPTH.get() + 1);
    }

    /** Close a feasibility check opened by {@link #enterFeasibility()}. */
    static void exitFeasibility() {
        FEASIBILITY_DEPTH.set(Math.max(0, FEASIBILITY_DEPTH.get() - 1));
    }

    /**
     * How deep we are inside a question put to the heuristic AI purely for its
     * opinion — "would you play this?", "what would you play?" — whose answer is
     * then discarded or revised.
     *
     * <p>It matters because some of those questions reach back out to this
     * controller. A card that says the opponent chooses targets sends the
     * heuristic's own {@code canPlaySa} through {@code chooseTargetsFor}, so
     * without this counter a single pass over the option list could spend one
     * LLM target call per option on abilities that are never cast. Per-thread
     * for the same reason as {@link #isCheckingFeasibility()}: parallel matches
     * each run on their own controller.
     */
    private static final ThreadLocal<Integer> HEURISTIC_CONSULT_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    /** True while an answer is being collected from the heuristic AI for comparison. */
    static boolean isConsultingHeuristic() {
        return HEURISTIC_CONSULT_DEPTH.get() > 0;
    }

    private static void enterHeuristicConsult() {
        HEURISTIC_CONSULT_DEPTH.set(HEURISTIC_CONSULT_DEPTH.get() + 1);
    }

    private static void exitHeuristicConsult() {
        HEURISTIC_CONSULT_DEPTH.set(HEURISTIC_CONSULT_DEPTH.get() - 1);
    }

    /**
     * The heuristic AI's verdict on one ability. Identical to calling
     * {@code getAi().canPlaySa(sa)} except that it marks the call as an opinion,
     * so nothing it reaches decides to spend an LLM call of its own.
     */
    AiPlayDecision askHeuristicVerdict(SpellAbility sa) {
        enterHeuristicConsult();
        try {
            return getAi().canPlaySa(sa);
        } finally {
            exitHeuristicConsult();
        }
    }

    /** Spell-selection delegate (heuristic prior, plan caching, choose ability). */
    private final LLMSpellSelection spellSelection;

    /**
     * The spell-selection delegate, so a test can read the option list this
     * seat builds without putting a decision to a model.
     */
    LLMSpellSelection spellSelection() {
        return spellSelection;
    }

    /** Combat delegate (declare attackers/blockers with heuristic baselines). */
    private final LLMCombat combat;

    /** Target-choice delegate (re-points an ability the heuristic already targeted). */
    private final LLMTargeting targeting;

    private static boolean isTruthy(String v) {
        return v != null && !v.isEmpty() && !"false".equalsIgnoreCase(v) && !"0".equals(v);
    }

    /**
     * Read one {@code FORGE_LLM_*} switch, from wherever the rest of the LLM
     * configuration is read: the {@code forge.llm.env.*} system property, the process
     * environment, then a {@code .env} file walked up from the working directory.
     *
     * <p>Not {@code System.getenv} alone. {@code .env} is the fork's documented place to
     * configure an LLM run and is where {@code FORGE_LLM_STRICT} is read from, so a
     * {@code .env} holding {@code FORGE_LLM_UNMUZZLED=1} used to give a run with strict
     * mode armed and every muzzle still on — combat, land drops, targets and the rest all
     * still the heuristic's, no warning printed, and the result written up as the
     * de-muzzled arm of an A/B.
     */
    private static String switchValue(String envVar) {
        return LLMConfig.loadProviderApiKey(envVar);
    }

    /** Read one boolean muzzle switch. */
    private static boolean muzzleOn(String envVar) {
        return resolveMuzzle(switchValue(envVar), UNMUZZLED);
    }

    /**
     * Resolve one boolean muzzle switch. Every muzzle is on by default and off
     * when the aggregate {@code FORGE_LLM_UNMUZZLED} is set; naming the
     * variable explicitly wins over the aggregate in either direction, so a
     * single muzzle can be restored inside an otherwise unmuzzled run.
     * Package-private and taking its inputs as arguments so the resolution
     * rule is testable without touching the process environment.
     */
    static boolean resolveMuzzle(String rawValue, boolean unmuzzled) {
        if (rawValue != null && !rawValue.isEmpty()) return isTruthy(rawValue);
        return !unmuzzled;
    }

    private static int parseIntEnv(String name, int fallback) {
        return resolveInt(switchValue(name), fallback);
    }

    /** @see #resolveMuzzle(String, boolean) — same reason for the argument form. */
    static int resolveInt(String rawValue, int fallback) {
        if (rawValue == null || rawValue.isEmpty()) return fallback;
        try { return Integer.parseInt(rawValue.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    /** @see #resolveMuzzle(String, boolean) — same reason for the argument form. */
    static String parseCombatMode(String rawValue, String fallback) {
        if (rawValue == null) return fallback;
        String s = rawValue.trim().toLowerCase();
        return ("llm".equals(s) || "shadow".equals(s) || "heuristic".equals(s)) ? s : fallback;
    }

    public LLMFullController(Game game, Player p, LobbyPlayer lp, LLMClient client) {
        super(game, p, lp);
        this.client = client;
        this.maxHistory = client != null && client.isPromptCachingEnabled()
                ? MAX_HISTORY_CACHED : MAX_HISTORY_UNCACHED;
        this.spellSelection = new LLMSpellSelection(this);
        this.combat = new LLMCombat(this);
        this.targeting = new LLMTargeting(this);
    }

    /**
     * Hand target choice for {@code sa} to the model, if that muzzle is off.
     * Returns true when the targets changed hands; false leaves the ability
     * exactly as the heuristic AI targeted it. Package-private so
     * {@link LLMSpellSelection} can call it on the ability it is about to play.
     */
    boolean chooseTargetsWithLLM(SpellAbility sa, String callLabel) {
        return targeting.chooseTargets(sa, callLabel);
    }

    /** Record an action summary for the sliding window history. */
    void recordAction(String summary) {
        actionHistory.add(summary);
        if (actionHistory.size() > maxHistory) {
            actionHistory.remove(0);
        }
    }

    /**
     * Format the action history section for inclusion in prompts.
     * D2: this text is append-only and byte-stable — no timestamps, no
     * re-numbering of earlier entries — so it can sit inside the cached
     * prompt prefix. Entries are only dropped (whole oldest entry first)
     * when the generous {@link #maxHistory} cap is exceeded.
     * Package-private so {@link LLMCombat} can compose it into the stable
     * prefix of its combat prompts.
     */
    String getActionHistoryText() {
        if (actionHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nRECENT ACTIONS:\n");
        for (String action : actionHistory) {
            sb.append("- ").append(action).append('\n');
        }
        return sb.toString();
    }

    /**
     * Build the game state text for the current decision.
     *
     * <p>D1: the serialized state is cached and reused while the board it
     * describes has not moved, which skips re-serialization CPU across the
     * several priority passes a phase normally contains.
     *
     * <p>The cache used to be keyed on turn and phase alone, which is not the
     * same thing at all. A MAIN phase holds many priority passes with spells
     * resolving between them, so from the second decision of the phase onward
     * the model was shown the board as it stood before its own creature
     * entered, before an opponent's removal resolved, before combat damage —
     * and then asked what to do about it. Keying on a fingerprint of the board
     * keeps the saving where the board really is unchanged and re-serializes
     * the moment it is not.
     *
     * <p>D2: Despite the historical name, action history is NO longer
     * concatenated here. The state is volatile (the model must always see it
     * fresh), so it lives in the uncached tail of the request; the append-only
     * history now travels in the byte-stable cached prefix block that
     * {@link #callLLM} / {@link #callLLMRaw} attach to every request (see
     * {@link PromptTemplates.PromptParts}). The method name is kept so the
     * delegate call sites ({@link LLMSpellSelection}, {@link LLMCombat}) stay
     * unchanged.
     */
    String buildGameStateWithHistory() {
        String key = boardFingerprint();
        if (cachedGameState == null || !key.equals(cachedStateKey)) {
            cachedGameState = GameStateSerializer.serializeGameState(getPlayer(), getGame());
            cachedStateKey = key;
        }
        return cachedGameState;
    }

    /**
     * A short fingerprint of everything the serialized state describes: turn,
     * phase, stack depth, and both players' life, zone sizes and permanents.
     *
     * <p>Deliberately not a full serialization — no oracle text, no names, no
     * formatting — so it is cheap enough to compute on every decision. Two
     * boards with the same fingerprint produce the same state text.
     */
    private String boardFingerprint() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(getGame().getPhaseHandler().getTurn()).append('|')
          .append(getGame().getPhaseHandler().getPhase()).append('|')
          .append(getGame().getStack().size());
        for (Player p : getGame().getPlayers()) {
            sb.append("|P").append(p.getId());
            appendPlayerFingerprint(sb, p);
        }
        return sb.toString();
    }

    /**
     * Fingerprint of one player's visible position: life, zone sizes, and every
     * permanent with the state that can change without it leaving the
     * battlefield.
     */
    private static void appendPlayerFingerprint(StringBuilder sb, Player p) {
        sb.append(':').append(p.getLife())
          .append('/').append(p.getCardsIn(ZoneType.Hand).size())
          .append('/').append(p.getCardsIn(ZoneType.Library).size())
          .append('/').append(p.getCardsIn(ZoneType.Graveyard).size())
          .append('/').append(p.getCardsIn(ZoneType.Exile).size());
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            sb.append(',').append(c.getId());
            if (c.isTapped()) sb.append('t');
            if (c.isSick()) sb.append('s');
            sb.append(c.getNetPower()).append('/').append(c.getNetToughness());
            int counters = totalCounters(c);
            if (counters != 0) sb.append('+').append(counters);
        }
    }

    private static int totalCounters(Card c) {
        if (!c.hasCounters()) return 0;
        int total = 0;
        for (Integer n : c.getCounters().values()) {
            if (n != null) total += n;
        }
        return total;
    }

    /**
     * Fingerprint of the opponent's side of the board, plus the number of
     * spells they have cast. Used to tell whether a cached multi-step plan is
     * still a plan about this game: the seat knows what its own steps will do,
     * but nothing in the plan anticipates the opponent countering, removing a
     * blocker, or pumping in response.
     *
     * <p>Their life is deliberately absent — the seat's own burn spell changes
     * it, and re-planning after every point of damage would defeat the plan
     * entirely. Everything here moves only when the opponent does something.
     */
    String opponentBoardState() {
        StringBuilder sb = new StringBuilder(96);
        for (Player p : getGame().getPlayers()) {
            if (p == getPlayer()) continue;
            sb.append('|').append(p.getId()).append(':').append(p.getSpellsCastThisGame())
              .append('/').append(p.getCardsIn(ZoneType.Hand).size())
              .append('/').append(p.getCardsIn(ZoneType.Graveyard).size());
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                sb.append(',').append(c.getId());
                if (c.isTapped()) sb.append('t');
                sb.append(c.getNetPower()).append('/').append(c.getNetToughness());
                int counters = totalCounters(c);
                if (counters != 0) sb.append('+').append(counters);
            }
        }
        return sb.toString();
    }

    /** The permanents the seat controls right now, by card id. */
    Set<Integer> ownPermanentIds() {
        Set<Integer> ids = new java.util.HashSet<>();
        for (Card c : getPlayer().getCardsIn(ZoneType.Battlefield)) {
            ids.add(c.getId());
        }
        return ids;
    }

    /**
     * Whether every permanent the seat had at {@code since} is still on the
     * battlefield. Additions are ignored, because casting things is exactly
     * what a plan does; a permanent that has left is not something the plan
     * accounted for.
     */
    boolean ownPermanentsIntact(Set<Integer> since) {
        if (since == null || since.isEmpty()) return true;
        return ownPermanentIds().containsAll(since);
    }

    // =======================================================================
    // Instant-speed decision check
    // =======================================================================

    /**
     * Check if the LLM should be called for an instant-speed decision.
     * Returns true when: there's a spell on the stack (counterspell opportunity)
     * or it's the opponent's combat phase (combat trick opportunity),
     * AND the player has affordable instant-speed spells in hand.
     */
    boolean shouldCallLLMForInstantSpeed() {
        try {
            boolean hasStackTarget = !getGame().getStack().isEmpty();
            PhaseType phase = getGame().getPhaseHandler().getPhase();
            // A3: dropped COMBAT_DECLARE_ATTACKERS — we can't usefully respond
            // before knowing who's attacking. Wait until DECLARE_BLOCKERS / damage.
            boolean isOpponentCombat = !getGame().getPhaseHandler().isPlayerTurn(getPlayer())
                    && (phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                        || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                        || phase == PhaseType.COMBAT_DAMAGE);

            if (!hasStackTarget && !isOpponentCombat) {
                return false;
            }

            // A3: combat-trick filter — skip when there are no actual attackers.
            if (isOpponentCombat) {
                Combat combat = getGame().getCombat();
                if (combat == null || combat.getAttackers().isEmpty()) {
                    return false;
                }
            }

            // A3: stack/combat response filter — only fire when at least one
            // affordable candidate can actually be cast right now (cost paid AND
            // targeting valid). This catches cases where we hold an instant but
            // it has no legal target on the current stack/board.
            CardCollection cards = ComputerUtilAbility.getAvailableCards(getGame(), getPlayer());
            List<SpellAbility> candidates = ComputerUtilAbility.getSpellAbilities(cards, getPlayer());
            enterFeasibility();
            try {
                for (SpellAbility sa : candidates) {
                    if (sa.isManaAbility() || sa.isLandAbility()) continue;
                    sa.setActivatingPlayer(getPlayer());
                    if (!sa.canCastTiming(getPlayer())) continue;
                    if (!ComputerUtilCost.canPayCost(sa, getPlayer(), sa.isTrigger())) continue;
                    if (validateAndSetTargets(sa)) {
                        return true;
                    }
                }
            } finally {
                exitFeasibility();
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // =======================================================================
    // Core LLM call helper
    // =======================================================================

    /**
     * Call the LLM with game state, options, and context.
     * Returns the chosen option index, or -1 on failure (caller falls back to heuristic).
     * D2: the single-string prompt is sent as the volatile tail; the action
     * history rides separately in the byte-stable cached prefix block.
     */
    int callLLM(String userPrompt, int numOptions, String callLabel) {
        return callLLM(new PromptTemplates.PromptParts(getActionHistoryText(), userPrompt),
                numOptions, callLabel);
    }

    /** Split-prompt variant — stable prefix cached, volatile tail fresh. */
    int callLLM(PromptTemplates.PromptParts parts, int numOptions, String callLabel) {
        try {
            String response = client.chatCompletion(
                    PromptTemplates.SYSTEM_PROMPT, parts.stablePrefix, parts.volatileTail,
                    callLabel, getPlayer().getName(),
                    LLMResponseSchema.CHOICE);
            int choice = ResponseParser.parseChoiceIndex(response, numOptions);
            if (choice < 0) {
                client.recordFallback(getPlayer().getName(), callLabel
                        + ": answer held no usable option index (" + numOptions + " options offered)");
                return -1;
            }
            return choice;
        } catch (Exception e) {
            client.recordFallback(getPlayer().getName(), describeFailure(callLabel, e));
            return -1;
        }
    }

    /**
     * Failure text recorded with a fallback. Wider than the debug line on
     * purpose: {@link LLMException} often wraps a cause whose own message is the
     * only useful part (a bare connection refusal arrives with a null message),
     * and this string is what strict mode prints as its whole explanation.
     */
    private static String describeFailure(String callLabel, Exception e) {
        StringBuilder sb = new StringBuilder(callLabel).append(": ")
                .append(e.getClass().getSimpleName());
        if (e.getMessage() != null && !e.getMessage().isEmpty()) {
            sb.append(" - ").append(e.getMessage());
        }
        Throwable cause = e.getCause();
        if (cause != null) {
            sb.append(" (cause: ").append(cause.getClass().getName());
            if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                sb.append(": ").append(cause.getMessage());
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * Pass a parsed batch answer through, recording a fallback when there was none.
     *
     * <p>{@link ResponseParser} returns null for an answer it could not read and an empty
     * collection for one that really said "none". Only the first is a failed call, and it
     * has to be counted: the fallback counter is what {@code FORGE_LLM_STRICT} watches and
     * what the caller's degraded-run status is computed from, so an unreadable answer that
     * is quietly replaced by the heuristic leaves a run reporting a clean measurement of a
     * model that made none of the decisions.
     *
     * @return {@code picks} unchanged, or null after recording the fallback
     */
    <T> T recordIfUnreadable(T picks, String callLabel, int optionsOffered) {
        if (picks != null) {
            return picks;
        }
        client.recordFallback(getPlayer().getName(), callLabel
                + ": answer held no usable index (" + optionsOffered + " options offered)");
        return null;
    }

    /**
     * Call the LLM and return the raw response text (not parsed as a single index).
     * Returns null on failure.
     */
    String callLLMRaw(String userPrompt, String callLabel) {
        return callLLMRaw(userPrompt, callLabel, LLMResponseSchema.INDICES);
    }

    /**
     * Schema-aware variant for batch calls.
     * D2: like {@link #callLLM(String, int, String)}, wraps the single-string
     * prompt so the action history goes into the cached prefix block.
     */
    String callLLMRaw(String userPrompt, String callLabel, LLMResponseSchema schema) {
        return callLLMRaw(new PromptTemplates.PromptParts(getActionHistoryText(), userPrompt),
                callLabel, schema);
    }

    /** Split-prompt variant — stable prefix cached, volatile tail fresh. */
    String callLLMRaw(PromptTemplates.PromptParts parts, String callLabel, LLMResponseSchema schema) {
        try {
            return client.chatCompletion(
                    PromptTemplates.SYSTEM_PROMPT, parts.stablePrefix, parts.volatileTail,
                    callLabel, getPlayer().getName(), schema);
        } catch (Exception e) {
            client.recordFallback(getPlayer().getName(), describeFailure(callLabel, e));
            return null;
        }
    }

    /**
     * Ask the heuristic AI to point {@code sa} at something, and report whether
     * it now has a legal, complete set of targets. Used to keep spells that
     * cannot legally be cast out of the option list the model chooses from.
     *
     * <p>Targeting is a side effect of the heuristic's {@code canPlaySa}: it
     * sets targets while working out whether it wants to cast the spell. That
     * makes the verdict and the targets arrive together, and the two have to be
     * kept apart, because "the heuristic would rather not cast this" and "this
     * spell has nothing legal to point at" are different answers and only the
     * second one should hide an option from the model.
     *
     * <p>So a verdict short of {@code WillPlay} is not by itself a refusal
     * here: the option stays if it is really targeted, and
     * {@link LLMSpellSelection#applyHeuristicPrior} sorts and (by default)
     * prunes on the verdict afterwards. What changed on 2026-08-15 is which
     * targets count as "really targeted". Abilities are long-lived objects that
     * are re-examined every time priority comes round, so an ability could
     * arrive already carrying targets chosen several priority passes ago —
     * possibly at a creature that has since died. The old check asked
     * {@code isTargetNumberValid()} without clearing those first, so a spell
     * the heuristic had just declined to target at all was offered to the model
     * on the strength of a stale answer. Clearing first means the question is
     * about the targets the heuristic chose this time, or none.
     *
     * @return true when the ability needs no targets, or has a full set chosen
     *         during this call
     */
    boolean validateAndSetTargets(SpellAbility sa) {
        if (!sa.usesTargeting()) {
            return true;
        }

        enterFeasibility();
        try {
            sa.setActivatingPlayer(getPlayer());
            SpellAbility root = sa.getRootAbility();
            if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
                sa.setLastStateBattlefield(getGame().getLastStateBattlefield());
                sa.setLastStateGraveyard(getGame().getLastStateGraveyard());
            }

            // Discard any targets left over from an earlier priority pass, so
            // what the check below sees is this call's work and nothing else.
            sa.resetTargets();
            AiPlayDecision decision = askHeuristicVerdict(sa);
            sa.clearLastState();

            return acceptTargetedOption(decision, sa.isTargetNumberValid());
        } catch (Exception e) {
            sa.clearLastState();
            if (client.isDebug()) {
                System.err.println("[LLM] validateAndSetTargets failed: " + e.getMessage());
            }
            return false;
        } finally {
            exitFeasibility();
        }
    }

    /**
     * Whether a targeted ability the heuristic AI has just looked at belongs in
     * the option list shown to the model.
     *
     * <p>Split out from {@link #validateAndSetTargets(SpellAbility)} because it
     * is the whole rule, and because it can be checked directly this way.
     *
     * @param decision        the heuristic's verdict on playing the ability
     * @param targetsComplete whether the ability now has a full, legal set of
     *                        targets, asked after any earlier ones were cleared
     */
    static boolean acceptTargetedOption(AiPlayDecision decision, boolean targetsComplete) {
        if (decision == AiPlayDecision.WillPlay) {
            // The heuristic wants to cast it; whatever targeting it still needs
            // it will do when the spell is actually played.
            return true;
        }
        if (decision == AiPlayDecision.TargetingFailed) {
            return false;
        }
        // Every other verdict is a strategic refusal, not a statement that the
        // spell has nowhere legal to point. The model is allowed to disagree
        // with a refusal, so the option survives — but only if the heuristic
        // really targeted it during this call.
        return targetsComplete;
    }

    // =======================================================================
    // Generic helpers (mirror NNFullController patterns)
    // =======================================================================

    private Card chooseFromCards(CardCollectionView cards, String context) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        if (cards.size() == 1) {
            return cards.get(0);
        }
        List<Card> cardList = new ArrayList<>();
        for (Card c : cards) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeCardOptions(cardList, false);
        String prompt = PromptTemplates.chooseCard(gameState, options, context);
        int chosen = callLLM(prompt, cards.size(), "chooseCard");
        if (chosen < 0) {
            return null;
        }
        return cards.get(chosen);
    }

    private <T extends GameEntity> T chooseFromEntities(FCollectionView<T> entities,
                                                          String context, boolean isOptional) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        if (entities.size() == 1 && !isOptional) {
            return entities.get(0);
        }

        List<T> entityList = new ArrayList<>();
        for (T e : entities) { entityList.add(e); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeEntityOptions(entityList, isOptional);
        int numOpts = isOptional ? entityList.size() + 1 : entityList.size();
        String prompt = PromptTemplates.chooseCard(gameState, options, context);
        int chosen = callLLM(prompt, numOpts, "chooseEntity");
        if (chosen < 0) {
            return null;
        }
        if (isOptional && chosen >= entityList.size()) {
            return null;
        }
        if (chosen >= entityList.size()) {
            chosen = entityList.size() - 1;
        }
        return entityList.get(chosen);
    }

    private int chooseNumberLLM(int min, int max, String context) {
        if (min == max) {
            return min;
        }
        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeNumberOptions(min, max);
        String prompt = PromptTemplates.numberChoice(gameState, options, context);
        int chosen = callLLM(prompt, max - min + 1, "number");
        if (chosen < 0) {
            return min;
        }
        return min + chosen;
    }

    private SpellAbility chooseFromSpellAbilities(List<SpellAbility> sas, String context) {
        if (sas == null || sas.isEmpty()) {
            return null;
        }
        if (sas.size() == 1) {
            return sas.get(0);
        }
        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeGenericOptions(sas);
        String prompt = PromptTemplates.genericChoice(gameState, options, context);
        int chosen = callLLM(prompt, sas.size(), "chooseSpellAbility");
        if (chosen < 0) {
            return sas.get(0);
        }
        return sas.get(chosen);
    }

    /**
     * B3: Single-call batched picker for cards. One LLM call returns all picks
     * as comma-separated indices. On any failure, fills from the front to satisfy
     * min and returns.
     */
    private CardCollection chooseMultipleCards(CardCollectionView sourceList, int min, int max,
                                                boolean isOptional, String context) {
        CardCollection result = new CardCollection();
        if (sourceList == null || sourceList.isEmpty()) return result;

        int effectiveMin = isOptional ? 0 : min;
        int effectiveMax = Math.min(max, sourceList.size());

        // Short-circuit: forced to take everything.
        if (effectiveMin >= sourceList.size()) {
            for (Card c : sourceList) result.add(c);
            return result;
        }

        List<Card> cardList = new ArrayList<>();
        for (Card c : sourceList) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeCardOptions(cardList, false);
        String prompt = PromptTemplates.batchPick(gameState, options, context, effectiveMin, effectiveMax);
        String response = callLLMRaw(prompt, "chooseMultipleCardsBatched");

        Set<Integer> picks = response == null ? null
                : recordIfUnreadable(ResponseParser.parseBatchIndices(response, cardList.size()),
                        "chooseMultipleCardsBatched", cardList.size());
        if (picks == null) {
            fillFromFront(result, cardList, effectiveMin);
            return result;
        }
        // Honour max
        int taken = 0;
        for (int idx : picks) {
            if (taken >= effectiveMax) break;
            result.add(cardList.get(idx));
            taken++;
        }
        // Honour min — fill from front with items not already picked
        if (result.size() < effectiveMin) {
            for (Card c : cardList) {
                if (result.size() >= effectiveMin) break;
                if (!result.contains(c)) result.add(c);
            }
        }
        return result;
    }

    /** B3 fallback: fill {@code result} from the front of {@code source} until at least {@code min}. */
    private void fillFromFront(CardCollection result, List<Card> source, int min) {
        for (Card c : source) {
            if (result.size() >= min) break;
            result.add(c);
        }
    }

    /**
     * B3: Batched picker for generic entities (Cards or Players, etc). One LLM call.
     */
    private <T extends GameEntity> List<T> chooseMultipleEntitiesBatched(List<T> sourceList,
                                                                          int min, int max, String context) {
        List<T> result = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) return result;

        int effectiveMax = Math.min(max, sourceList.size());
        if (min >= sourceList.size()) {
            result.addAll(sourceList);
            return result;
        }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeEntityOptions(sourceList, false);
        String prompt = PromptTemplates.batchPick(gameState, options, context, min, effectiveMax);
        String response = callLLMRaw(prompt, "chooseMultipleEntitiesBatched");

        Set<Integer> picks = response == null ? null
                : recordIfUnreadable(ResponseParser.parseBatchIndices(response, sourceList.size()),
                        "chooseMultipleEntitiesBatched", sourceList.size());
        if (picks == null) {
            for (int i = 0; i < min && i < sourceList.size(); i++) result.add(sourceList.get(i));
            return result;
        }
        int taken = 0;
        for (int idx : picks) {
            if (taken >= effectiveMax) break;
            result.add(sourceList.get(idx));
            taken++;
        }
        if (result.size() < min) {
            for (T e : sourceList) {
                if (result.size() >= min) break;
                if (!result.contains(e)) result.add(e);
            }
        }
        return result;
    }

    /**
     * B3: Batched picker for spell abilities. One LLM call.
     */
    private List<SpellAbility> chooseMultipleSpellAbilitiesBatched(List<SpellAbility> sourceList,
                                                                    int num, String context) {
        List<SpellAbility> result = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) return result;
        int take = Math.min(num, sourceList.size());
        if (take == sourceList.size()) {
            result.addAll(sourceList);
            return result;
        }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeGenericOptions(sourceList);
        String prompt = PromptTemplates.batchPick(gameState, options, context, take, take);
        String response = callLLMRaw(prompt, "chooseMultipleSAsBatched");

        Set<Integer> picks = response == null ? null
                : recordIfUnreadable(ResponseParser.parseBatchIndices(response, sourceList.size()),
                        "chooseMultipleSAsBatched", sourceList.size());
        if (picks == null) {
            for (int i = 0; i < take; i++) result.add(sourceList.get(i));
            return result;
        }
        int taken = 0;
        for (int idx : picks) {
            if (taken >= take) break;
            result.add(sourceList.get(idx));
            taken++;
        }
        if (result.size() < take) {
            for (SpellAbility sa : sourceList) {
                if (result.size() >= take) break;
                if (!result.contains(sa)) result.add(sa);
            }
        }
        return result;
    }

    private <T> T chooseFromGenericList(List<T> options, String context) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        String gameState = buildGameStateWithHistory();
        String optText = OptionSerializer.serializeGenericOptions(options);
        String prompt = PromptTemplates.genericChoice(gameState, optText, context);
        int chosen = callLLM(prompt, options.size(), "genericChoice");
        if (chosen < 0) {
            return options.get(0);
        }
        return options.get(chosen);
    }

    private String chooseFromStringList(List<String> options, String context) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        String gameState = buildGameStateWithHistory();
        String optText = OptionSerializer.serializeStringOptions(options);
        String prompt = PromptTemplates.genericChoice(gameState, optText, context);
        int chosen = callLLM(prompt, options.size(), "stringChoice");
        if (chosen < 0) {
            return options.get(0);
        }
        return options.get(chosen);
    }

    // =======================================================================
    // Spell selection
    // =======================================================================

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return spellSelection.chooseSpellAbilityToPlay();
    }

    /**
     * Default heuristic spell selection — exposed so {@link LLMSpellSelection}
     * can fall back to it, and so it can ask what the heuristic would do. Marked
     * as a heuristic consultation for the duration: whatever the caller does
     * with the answer, nothing reached along the way should spend an LLM call
     * of its own (see {@link #isConsultingHeuristic()}).
     */
    List<SpellAbility> defaultChooseSpellAbilityToPlay() {
        enterHeuristicConsult();
        try {
            return super.chooseSpellAbilityToPlay();
        } finally {
            exitHeuristicConsult();
        }
    }


    // =======================================================================
    // Mulligan
    // =======================================================================

    /**
     * Keep or mulligan the opening hand.
     *
     * <p>There used to be an auto-keep in front of this: a seven-card hand with
     * two to four lands and one spell costing three or less was kept without
     * asking anybody. It was deleted on 2026-08-15 because it could not tell
     * whether the lands cast the spell. Three Islands and a Lightning Bolt met
     * every one of its conditions, so a hand that cannot cast a single card was
     * auto-kept, and it fired first — ahead of both the model and the
     * heuristic's own evaluator, on the majority of opening hands.
     *
     * <p>Nothing was lost by removing it. {@code ComputerUtil.evaluateHand},
     * whose verdict is already offered to the model here as a baseline, checks
     * every colour each land can produce against the coloured pips of every
     * spell and mulligans a hand whose mana cannot cast most of it; it is also
     * deck-aware, where the auto-keep's "2 to 4 lands" was a constant. The cost
     * is one LLM call per opening hand, against the fifty-odd a game already
     * spends, for the single decision with the largest effect on the game.
     */
    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        // Heuristic prior: surface ComputerUtil.wantMulligan()'s verdict as a
        // baseline annotation in the prompt — symmetric counterpart to the
        // attack/block priors. Failure → no annotation, prompt runs as before.
        Boolean heuristicKeep = null;
        try {
            heuristicKeep = !forge.ai.ComputerUtil.wantMulligan(getPlayer(), cardsToReturn);
        } catch (Exception e) {
            if (client.isDebug()) {
                System.err.println("[LLM] mulligan heuristic prior failed: " + e.getMessage());
            }
        }

        String gameState = buildGameStateWithHistory();
        String prompt = PromptTemplates.mulligan(gameState, heuristicKeep);
        int chosen = callLLM(prompt, 2, "mulligan");
        if (chosen < 0) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        boolean keep = chosen == 0;
        if (SHADOW_MODE && heuristicKeep != null) {
            boolean agree = keep == heuristicKeep;
            System.err.println("[LLM SHADOW] mulligan heuristic="
                    + (heuristicKeep ? "KEEP" : "MULLIGAN")
                    + " llm=" + (keep ? "KEEP" : "MULLIGAN")
                    + (agree ? " AGREE" : " DIVERGE"));
        }
        recordAction(keep ? "Kept opening hand" : "Mulliganed");
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(Player mulliganingPlayer, int cardsToReturn) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        if (hand.size() <= cardsToReturn) {
            return CardCollection.getView(hand);
        }
        CardCollection chosen = chooseMultipleCards(hand, cardsToReturn, cardsToReturn,
                false, "Choose cards to put on bottom for mulligan");
        if (chosen.size() < cardsToReturn) {
            return super.tuckCardsViaMulligan(mulliganingPlayer, cardsToReturn);
        }
        return CardCollection.getView(chosen);
    }

    // confirmMulliganScry: delegated to heuristic (always scries, not worth an LLM call)

    // =======================================================================
    // Combat
    // =======================================================================

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        this.combat.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        this.combat.declareBlockers(defender, combat);
    }

    /** Default heuristic attackers — exposed so {@link LLMCombat} can fall back to it. */
    void defaultDeclareAttackers(Player attacker, Combat combat) {
        super.declareAttackers(attacker, combat);
    }

    /** Default heuristic blockers — exposed so {@link LLMCombat} can fall back to it. */
    void defaultDeclareBlockers(Player defender, Combat combat) {
        super.declareBlockers(defender, combat);
    }

    // exertAttackers: delegated to heuristic (combat-phase decision, handled by AI logic)

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return super.enlistAttackers(attackers);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return super.orderBlockers(attacker, blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return super.orderBlocker(attacker, blocker, oldBlockers);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return super.orderAttackers(blocker, attackers);
    }

    // =======================================================================
    // Card choice / targeting
    // =======================================================================

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(
            FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional,
            Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        T result = chooseFromEntities(optionList, title, isOptional);
        if (result == null && !isOptional && optionList != null && !optionList.isEmpty()) {
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }
        return result;
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(
            FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
            SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (optionList == null || optionList.isEmpty()) {
            return new ArrayList<>();
        }

        // B3: single batched LLM call instead of per-pick loop.
        List<T> sourceList = new ArrayList<>();
        for (T e : optionList) { sourceList.add(e); }
        List<T> selected = chooseMultipleEntitiesBatched(sourceList, min, max, title);

        if (selected.size() < min) {
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        }
        return selected;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                    String title, Map<String, Object> params) {
        SpellAbility result = chooseFromSpellAbilities(spells, title);
        return result != null ? result : super.chooseSingleSpellForEffect(spells, sa, title, params);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                             String title, int num, Map<String, Object> params) {
        // B3: single batched LLM call.
        List<SpellAbility> selected = chooseMultipleSpellAbilitiesBatched(spells, num, title);
        if (selected.isEmpty()) {
            return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        }
        return selected;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                           CardCollectionView validTargets, String message) {
        return chooseMultipleCards(validTargets, min, max, min == 0, "Choose permanents to sacrifice: " + message);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                         CardCollectionView validTargets, String message) {
        return chooseMultipleCards(validTargets, min, max, min == 0, "Choose permanents to destroy: " + message);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa,
                                                    String title, int min, int max,
                                                    boolean isOptional, Map<String, Object> params) {
        return chooseMultipleCards(sourceList, min, max, isOptional, title);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
                                                        SpellAbility sa, String title, boolean isOptional) {
        CardCollection choices = new CardCollection();
        for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
            CardCollection cc = new CardCollection(entry.getValue());
            cc.removeAll(choices);
            if (!cc.isEmpty()) {
                Card chosen = chooseFromCards(cc, entry.getKey());
                if (chosen != null) {
                    choices.add(chosen);
                }
            }
        }
        return choices;
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
                                                    CardCollection validCards, int min, int max) {
        return chooseMultipleCards(validCards, min, max, false, "Choose cards to discard");
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand,
                                                              String param, SpellAbility sa) {
        return chooseMultipleCards(hand, 1, num, false, "Choose cards to discard");
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        return chooseMultipleCards(hand, numDiscard, numDiscard, false, "Discard to hand size");
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        // During heuristic feasibility checks (canPayCost / canCastTiming for
        // every delve card in hand) the engine asks "can you pay?" — the LLM
        // shouldn't burn a call answering it. ~22% of all calls in transcripts
        // were speculative delve prompts where the spell was never cast. Use
        // the heuristic pick (cheapest cards first by simple grave order) for
        // feasibility; the real LLM call still fires when the spell actually
        // resolves and the choice matters.
        //
        // The same is true one level up: while the heuristic AI is being asked
        // what it would play, any delve cost it prices is hypothetical too. That
        // route was not covered until 2026-08-15, so every consultation of the
        // heuristic's own selection with a delve card in hand could spend a call
        // on a spell nobody was casting.
        if (isCheckingFeasibility() || isConsultingHeuristic()) {
            int take = Math.min(genericAmount, grave.size());
            CardCollection picked = new CardCollection();
            for (int i = 0; i < take; i++) picked.add(grave.get(i));
            return picked;
        }
        return chooseMultipleCards(grave, 0, genericAmount, true, "Choose cards to delve");
    }

    @Override
    public CardCollection chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return chooseMultipleCards(valid, min, max, min == 0, "Choose cards to reveal");
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
                                               SpellAbility sa, CardCollection fetchList,
                                               DelayedReveal delayedReveal, String selectPrompt,
                                               boolean isOptional, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (fetchList == null || fetchList.isEmpty()) {
            return null;
        }
        if (fetchList.size() == 1 && !isOptional) {
            return fetchList.get(0);
        }

        List<Card> cardList = new ArrayList<>();
        for (Card c : fetchList) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeCardOptions(cardList, isOptional);
        int numOpts = isOptional ? fetchList.size() + 1 : fetchList.size();
        String prompt = PromptTemplates.chooseCard(gameState, options, selectPrompt);
        int chosen = callLLM(prompt, numOpts, "chooseCardForZone");

        if (chosen < 0) {
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        }
        if (isOptional && chosen >= fetchList.size()) {
            return null;
        }
        if (chosen >= fetchList.size()) {
            chosen = fetchList.size() - 1;
        }
        return fetchList.get(chosen);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
                                                SpellAbility sa, CardCollection fetchList,
                                                int min, int max, DelayedReveal delayedReveal,
                                                String selectPrompt, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (fetchList == null || fetchList.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(chooseMultipleCards(fetchList, min, max, min == 0, selectPrompt));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                                                   SpellAbility source) {
        return super.orderMoveToZoneList(cards, destinationZone, source);
    }

    // =======================================================================
    // Boolean / confirm decisions
    // =======================================================================

    // confirmAction, confirmBidAction, confirmReplacementEffect, confirmStaticApplication,
    // confirmTrigger, confirmPayment: all delegated to heuristic AI (inherited from
    // PlayerControllerAi). These are secondary decisions where the heuristic's doTrigger()
    // and specialized logic outperforms LLM reasoning, especially for slow local models
    // where each call costs 15-30s. LLM value is in strategic spell/combat decisions.

    // chooseBinary, willPutCardOnTop: delegated to heuristic (same rationale as above)

    // chooseCardsPile: delegated to heuristic

    // =======================================================================
    // Number choices
    // =======================================================================

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return chooseNumberLLM(min, max, title);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max,
                             Map<String, Object> params) {
        return chooseNumberLLM(min, max, title);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values,
                             Player relatedPlayer) {
        if (values == null || values.isEmpty()) { return 0; }
        if (values.size() == 1) { return values.get(0); }
        Integer result = chooseFromGenericList(values, title);
        return result != null ? result : values.get(0);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return chooseNumberLLM(min, max, "Choose cost reduction amount");
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, forge.game.cost.Cost cost,
                                           KeywordInterface keyword, String prompt, int max) {
        return chooseNumberLLM(0, max, prompt);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, String announce) {
        return super.announceRequirements(ability, announce);
    }

    // =======================================================================
    // Scry, Surveil
    // =======================================================================

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        if (topN.size() <= 1) {
            // Single card: one LLM call with top/bottom choice
            CardCollection toTop = new CardCollection();
            CardCollection toBottom = new CardCollection();
            String gameState = buildGameStateWithHistory();
            String option = OptionSerializer.serializeScryOption(topN.get(0), false);
            String prompt = PromptTemplates.scry(gameState, option);
            int chosen = callLLM(prompt, 2, "scry");
            if (chosen == 0) { toTop.add(topN.get(0)); } else { toBottom.add(topN.get(0)); }
            return ImmutablePair.of(toTop, toBottom);
        }

        // Batch: single LLM call for all cards
        List<Card> cardList = new ArrayList<>();
        for (Card c : topN) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeBatchScryOptions(cardList, false);
        String prompt = PromptTemplates.batchScry(gameState, options);
        String response = callLLMRaw(prompt, "scry");

        CardCollection toTop = new CardCollection();
        CardCollection toBottom = new CardCollection();
        Set<Integer> keepOnTop = response == null ? null
                : recordIfUnreadable(ResponseParser.parseBatchIndices(response, cardList.size()),
                        "scry", cardList.size());
        if (keepOnTop == null) {
            return super.arrangeForScry(topN);
        }

        for (int i = 0; i < cardList.size(); i++) {
            if (keepOnTop.contains(i)) { toTop.add(cardList.get(i)); }
            else { toBottom.add(cardList.get(i)); }
        }
        return ImmutablePair.of(toTop, toBottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        if (topN.size() <= 1) {
            CardCollection toTop = new CardCollection();
            CardCollection toGraveyard = new CardCollection();
            String gameState = buildGameStateWithHistory();
            String option = OptionSerializer.serializeScryOption(topN.get(0), true);
            String prompt = PromptTemplates.scry(gameState, option);
            int chosen = callLLM(prompt, 2, "surveil");
            if (chosen == 0) { toTop.add(topN.get(0)); } else { toGraveyard.add(topN.get(0)); }
            return ImmutablePair.of(toTop, toGraveyard);
        }

        List<Card> cardList = new ArrayList<>();
        for (Card c : topN) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeBatchScryOptions(cardList, true);
        String prompt = PromptTemplates.batchScry(gameState, options);
        String response = callLLMRaw(prompt, "surveil");

        CardCollection toTop = new CardCollection();
        CardCollection toGraveyard = new CardCollection();
        Set<Integer> keepOnTop = response == null ? null
                : recordIfUnreadable(ResponseParser.parseBatchIndices(response, cardList.size()),
                        "surveil", cardList.size());
        if (keepOnTop == null) {
            return super.arrangeForSurveil(topN);
        }

        for (int i = 0; i < cardList.size(); i++) {
            if (keepOnTop.contains(i)) { toTop.add(cardList.get(i)); }
            else { toGraveyard.add(cardList.get(i)); }
        }
        return ImmutablePair.of(toTop, toGraveyard);
    }

    // =======================================================================
    // Color / type choices
    // =======================================================================

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        if (colors.countColors() <= 1) {
            return super.chooseColor(message, sa, colors);
        }
        List<String> colorNames = new ArrayList<>();
        List<MagicColor.Color> colorList = new ArrayList<>();
        for (MagicColor.Color c : colors) {
            colorList.add(c);
            colorNames.add(c.toString());
        }
        String result = chooseFromStringList(colorNames, message);
        if (result == null) {
            return super.chooseColor(message, sa, colors);
        }
        int idx = colorNames.indexOf(result);
        return colorList.get(idx >= 0 ? idx : 0).getColorMask();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        List<String> colorNames = new ArrayList<>();
        List<MagicColor.Color> colorList = new ArrayList<>();
        for (MagicColor.Color col : colors) {
            colorList.add(col);
            colorNames.add(col.toString());
        }
        if (colorList.isEmpty()) {
            return MagicColor.Color.COLORLESS.getColorMask();
        }
        if (colorList.size() == 1) {
            return colorList.get(0).getColorMask();
        }
        String result = chooseFromStringList(colorNames, message);
        if (result == null) {
            return colorList.get(0).getColorMask();
        }
        int idx = colorNames.indexOf(result);
        return colorList.get(idx >= 0 ? idx : 0).getColorMask();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return super.chooseColors(message, sa, min, max, options);
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes,
                                  boolean isOptional) {
        List<String> types = new ArrayList<>(validTypes);
        if (types.isEmpty()) { return ""; }
        // Generic prompt-size guard: when valid type list is huge (e.g. ALL ~200
        // creature types from cards like Distant Melody / Cavern of Souls), narrow
        // to types that actually appear on either player's battlefield, hand, or
        // graveyard. The relevant choice is almost always one of those. Fall back
        // to the full list if nothing matches.
        if (types.size() > 30 && "Creature".equalsIgnoreCase(kindOfType)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Player p : getGame().getPlayers()) {
                for (forge.game.zone.ZoneType z : new forge.game.zone.ZoneType[]{
                        forge.game.zone.ZoneType.Battlefield,
                        forge.game.zone.ZoneType.Hand,
                        forge.game.zone.ZoneType.Graveyard}) {
                    for (Card c : p.getCardsIn(z)) {
                        for (String t : c.getType().getCreatureTypes()) seen.add(t);
                    }
                }
            }
            List<String> filtered = new ArrayList<>();
            for (String t : types) if (seen.contains(t)) filtered.add(t);
            if (!filtered.isEmpty()) types = filtered;
        }
        String result = chooseFromStringList(types, "Choose a " + kindOfType);
        return result != null ? result : types.get(0);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        String result = chooseFromStringList(sectors, "Choose sector");
        return result != null ? result : sectors.get(0);
    }

    @Override
    public int chooseSprocket(Card assignee, boolean forceDifferent) {
        return super.chooseSprocket(assignee, forceDifferent);
    }

    @Override
    public String chooseProtectionType(String string, SpellAbility sa, List<String> choices) {
        String result = chooseFromStringList(choices, "Choose protection type");
        return result != null ? result : choices.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
                                        Card tgtCard) {
        if (options == null || options.isEmpty()) { return null; }
        String result = chooseFromStringList(options, prompt);
        return result != null ? result : options.get(0);
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
                                          Map<String, Object> params) {
        if (options == null || options.isEmpty()) { return null; }
        if (options.size() == 1) { return options.get(0); }
        return chooseFromGenericList(options, prompt);
    }

    // =======================================================================
    // Card face / state choices
    // =======================================================================

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces == null || faces.isEmpty()) { return null; }
        if (faces.size() == 1) { return faces.get(0); }
        return chooseFromGenericList(faces, message);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message,
                                            Map<String, Object> params) {
        if (states == null || states.isEmpty()) { return null; }
        if (states.size() == 1) { return states.get(0); }
        return chooseFromGenericList(states, message);
    }

    // =======================================================================
    // Replacement effects / static abilities
    // =======================================================================

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.size() <= 1) {
            return super.chooseSingleReplacementEffect(possibleReplacers);
        }
        return chooseFromGenericList(possibleReplacers, "Choose replacement effect");
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(String prompt, List<StaticAbility> possibleStatics) {
        if (possibleStatics == null || possibleStatics.size() <= 1) {
            return super.chooseSingleStaticAbility(prompt, possibleStatics);
        }
        return chooseFromGenericList(possibleStatics, prompt);
    }

    // =======================================================================
    // Mode / optional cost choices
    // =======================================================================

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                   int min, int num, boolean allowRepeat) {
        return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                                                        List<OptionalCostValue> optionalCostValues) {
        return super.chooseOptionalCosts(chosen, optionalCostValues);
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return super.orderCosts(costs);
    }

    // =======================================================================
    // Dice / planar dice
    // =======================================================================

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to ignore");
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to ignore");
    }

    // chooseDiceToReroll: delegated to heuristic

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to modify");
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to swap");
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult,
                                       int power, int toughness) {
        return chooseFromStringList(swapChoices, "Choose swap value");
    }

    // =======================================================================
    // Voting
    // =======================================================================

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
                        ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        if (options == null || options.isEmpty()) { return null; }
        return chooseFromGenericList(options, prompt);
    }

    // =======================================================================
    // Targeting
    // =======================================================================

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(
            SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return chooseFromGenericList(allTargets, "Choose target");
    }

    /**
     * Where a triggered, copied or otherwise engine-driven ability points.
     *
     * <p>The inherited version asks the heuristic AI's {@code doTrigger}, which
     * both decides whether to use the ability and sets its targets. Keep that —
     * it is the only thing that knows whether the ability is worth using at all
     * — then let the model re-point the result. With
     * {@code FORGE_LLM_HEURISTIC_TARGETS} on (the default) this is exactly the
     * inherited behaviour.
     */
    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        boolean heuristicTargeted = super.chooseTargetsFor(currentAbility);
        if (!heuristicTargeted) {
            // The heuristic found no targets it would accept. There is nothing
            // to re-point, and overriding that verdict would put an ability on
            // the stack the seat never decided to use.
            return false;
        }
        chooseTargetsWithLLM(currentAbility, "chooseTargetsFor");
        return true;
    }

    // =======================================================================
    // Starting player / hand
    // =======================================================================

    /**
     * Whether this seat plays first without asking the model.
     *
     * <p>Game one of a Constructed match is not a decision the model is in a
     * position to make. Nothing about the opponent is known yet, and the prompt
     * carries no deck identity, no opening hand and no board, so a model asked to
     * choose has to invent the context it is missing. In the recorded Burn run it
     * did exactly that: 10 of 15 calls took the draw, and five of those
     * explanations described Burn as a deck that "answers threats". Burn is the
     * deck in the format that least wants to trade a turn for a card. Playing
     * first is what the heuristic AI does, and it is right here.
     *
     * <p>The choice is still the model's in games two and three, where the
     * previous game is real information — unless
     * {@code FORGE_LLM_HARDCODED_PLAY_FIRST} (on by default) keeps those too, or
     * there is no client to ask.
     *
     * @param isFirstgame  game one of the match, so nothing is known yet
     * @param hardcoded    the FORGE_LLM_HARDCODED_PLAY_FIRST muzzle
     * @param hasClient    whether there is an LLM client that could be asked
     */
    static boolean playsFirstWithoutAsking(boolean isFirstgame, boolean hardcoded,
                                           boolean hasClient) {
        return isFirstgame || hardcoded || !hasClient;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstgame) {
        // See playsFirstWithoutAsking for why game one never reaches the model.
        // Any failure past this point (bad answer, no opponent) also keeps the
        // play-first answer.
        if (playsFirstWithoutAsking(isFirstgame, HARDCODED_PLAY_FIRST, client != null)) {
            return getPlayer();
        }
        Player opponent = null;
        for (Player p : getGame().getPlayers()) {
            if (!p.equals(getPlayer())) {
                opponent = p;
                break;
            }
        }
        if (opponent == null) {
            return getPlayer();
        }
        int chosen = callLLM(PromptTemplates.startingPlayer(isFirstgame), 2, "chooseStartingPlayer");
        boolean playFirst = chosen != 1;
        recordAction(playFirst ? "Chose to play first" : "Chose to draw first");
        return playFirst ? getPlayer() : opponent;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return chooseFromGenericList(zones, "Choose starting hand");
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    // =======================================================================
    // Mana choices
    // =======================================================================

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return chooseFromGenericList(manaChoices, "Choose mana from pool");
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount,
                                                boolean different) {
        return super.specifyManaCombo(sa, colorSet, manaAmount, different);
    }

    // =======================================================================
    // Contraptions
    // =======================================================================

    // chooseContraptionsToCrank: delegated to heuristic

    // =======================================================================
    // Splice
    // =======================================================================

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return super.chooseCardsForSplice(sa, cards);
    }

    // =======================================================================
    // Card name choices
    // =======================================================================

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return super.chooseCardName(sa, faces, message);
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid,
                                  String message) {
        return super.chooseCardName(sa, cpp, valid, message);
    }

    // =======================================================================
    // Convoke / Improvise
    // =======================================================================

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa,
                                                                      forge.card.mana.ManaCost manaCost,
                                                                      CardCollectionView untappedCards,
                                                                      boolean artifacts, boolean creatures,
                                                                      Integer maxReduction) {
        return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
    }

    // =======================================================================
    // Stats access
    // =======================================================================

    public LLMClient getClient() {
        return client;
    }
}
