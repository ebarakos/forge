package forge.ai.simulation;

import forge.ai.AiAttackController;
import forge.ai.AiController;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Simulation-guided attack declaration (FORGE_SIM_COMBAT=sim / -Dforge.sim.combat=sim).
 *
 * <p>Attack declaration is a perfect-information, enumerable decision: instead of relying on
 * the heuristic {@link AiAttackController} rules-of-thumb (which cause large board stalls),
 * a small candidate set of attack configurations is simulated through combat resolution on
 * game copies and the best-scoring one is declared.
 *
 * <p>The candidate set is: the heuristic's own plan, no attacks, alpha (all able attackers),
 * evasive-only, and (with at most {@link #ALPHA_MINUS_ONE_MAX_ABLE} able attackers) every
 * alpha-minus-one variant — capped at {@link #MAX_CONFIGS} after set-equality dedup.
 *
 * <p>Each configuration is forced into a game copy via the {@link #FORCED_ATTACKERS}
 * ThreadLocal, which {@link AiController#declareAttackers} consults when the copy's phase
 * machinery reaches its declare-attackers turn-based action. The copy is then advanced to
 * MAIN2 so combat damage and end-of-combat fully resolve before evaluation.
 *
 * <p>The heuristic plan is sticky: another configuration must beat its score by more than
 * {@link #HEURISTIC_STICKINESS} points to be chosen. Any failure — per-config or whole-search
 * — falls back to the heuristic declaration; nothing ever propagates into the real game.
 */
public final class CombatAttackSearch {
    /** Activation: -Dforge.sim.combat (wins) or FORGE_SIM_COMBAT, value "sim". */
    private static final boolean ENABLED = isSimCombatMode(
            System.getProperty("forge.sim.combat", System.getenv("FORGE_SIM_COMBAT")));

    /** Maximum number of candidate configurations simulated per declaration. */
    private static final int MAX_CONFIGS = 12;
    /** Alpha-minus-one variants are only generated when at most this many attackers are able. */
    private static final int ALPHA_MINUS_ONE_MAX_ABLE = 8;
    /** A challenger must beat the heuristic plan's score by more than this to override it. */
    private static final int HEURISTIC_STICKINESS = 30;
    /** Maximum length of an attacker-list description in the log line. */
    private static final int DESC_MAX_LEN = 60;

    /**
     * Attack configuration being simulated on a game copy. Non-null only inside
     * {@link #simulateConfig}; consulted by {@link AiController#declareAttackers} so the
     * copy declares exactly this set. Always cleared in a finally block.
     */
    private static final ThreadLocal<Set<Card>> FORCED_ATTACKERS = new ThreadLocal<>();

    private CombatAttackSearch() {
    }

    private static boolean isSimCombatMode(String raw) {
        return raw != null && "sim".equalsIgnoreCase(raw.trim());
    }

    /** Whether the sim-combat attack search is enabled for this JVM. */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /** The attack configuration forced on the current thread's config sim, or null. */
    public static Set<Card> getForcedAttackers() {
        return FORCED_ATTACKERS.get();
    }

    /**
     * Entry point from {@link forge.ai.PlayerControllerAi#declareAttackers}. Runs the search
     * and declares the winning configuration on {@code combat}; on any failure (or when the
     * call happens inside a simulation copy) delegates to the heuristic declaration.
     */
    public static void declareAttackers(Player aiPlayer, Player attacker, Combat combat, AiController brains) {
        if (FORCED_ATTACKERS.get() != null
                || aiPlayer != attacker
                || attacker.getGame().getPhaseHandler().getPlayerTurn() != attacker
                || insideEngineSimulation()) {
            // Inside one of our own config sims (the copy's controller re-enters here and the
            // AiController consult applies the forced set), declaring for another player, or
            // inside an engine simulation copy (spell evaluation / upcoming-combat eval — the
            // copied controllers carry the simulation flag too): never search, never recurse.
            brains.declareAttackers(attacker, combat);
            return;
        }
        final long startMs = System.currentTimeMillis();
        try {
            runSearch(aiPlayer, attacker, combat, brains, startMs);
        } catch (Exception e) {
            // A search failure must NEVER propagate into the real game (the engine converts
            // AI exceptions into both-players-lose draws). Fall back to the heuristic.
            System.err.println("[SIM_COMBAT] search failed (" + e + "), falling back to heuristic");
            try {
                combat.clearAttackers();
            } catch (Exception ignored) {
            }
            brains.declareAttackers(attacker, combat);
        }
    }

    private static void runSearch(Player aiPlayer, Player attacker, Combat combat, AiController brains, long startMs) {
        final Game game = attacker.getGame();
        final GameEntity defender = combat.getDefenders().getFirst();

        // 1. Able attackers vs the default defender.
        final List<Card> able = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, defender)) {
                able.add(c);
            }
        }
        if (able.isEmpty()) {
            brains.declareAttackers(attacker, combat);
            log(aiPlayer, game, 0, 0, describe(combat.getAttackers()), "none", 0, 0, startMs);
            return;
        }

        // 2. The heuristic's plan, computed non-destructively on a scratch Combat.
        final Combat scratch = new Combat(attacker);
        new AiAttackController(attacker).declareAttackers(scratch);
        final Set<Card> heurPlan = new LinkedHashSet<>(scratch.getAttackers());

        // 3. Candidate configurations (heuristic plan always at index 0).
        final List<Set<Card>> configs = buildConfigs(able, heurPlan);

        // 4. Score each configuration by simulation. All config sims must see an identical
        // random stream: draw one seed, re-seed before each sim, restore the caller's RNG.
        final int[] scores = new int[configs.size()];
        final Random origRandom = MyRandom.getRandom();
        final long randomSeedToUse = origRandom.nextLong();
        try {
            for (int i = 0; i < configs.size(); i++) {
                try {
                    MyRandom.setRandom(new Random(randomSeedToUse));
                    scores[i] = simulateConfig(game, aiPlayer, configs.get(i));
                } catch (Exception e) {
                    // A failed config is skipped, not fatal.
                    scores[i] = Integer.MIN_VALUE;
                }
            }
        } finally {
            MyRandom.setRandom(origRandom);
        }

        // 5. Argmax with stability bias: the heuristic plan wins ties and near-ties.
        int best = 0;
        for (int i = 1; i < configs.size(); i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        if (best != 0 && (long) scores[best] <= (long) scores[0] + HEURISTIC_STICKINESS) {
            best = 0;
        }

        if (best == 0) {
            // The heuristic's own plan won: let it declare on the real combat so its defender
            // choices (planeswalkers, battles), banding reinforcement etc. are preserved.
            brains.declareAttackers(attacker, combat);
        } else {
            for (Card c : configs.get(best)) {
                if (CombatUtil.canAttack(c, defender)) {
                    combat.addAttacker(c, defender);
                }
            }
            if (!CombatUtil.validateAttackers(combat)) {
                // Attack requirements (must-attack effects) unmet by the chosen config:
                // hand the declaration back to the heuristic, which honors them.
                combat.clearAttackers();
                brains.declareAttackers(attacker, combat);
            }
        }

        log(aiPlayer, game, able.size(), configs.size(),
                describe(combat.getAttackers()), describe(heurPlan), scores[best], scores[0], startMs);
    }

    /**
     * Builds the candidate configurations: heuristic plan, empty (no attacks), alpha (all
     * able), evasive-only, and alpha-minus-one variants for small boards. Deduped by set
     * equality, capped at {@link #MAX_CONFIGS}.
     */
    private static List<Set<Card>> buildConfigs(List<Card> able, Set<Card> heurPlan) {
        final List<Set<Card>> configs = new ArrayList<>();
        addConfig(configs, heurPlan);
        addConfig(configs, Collections.emptySet());
        addConfig(configs, new LinkedHashSet<>(able));
        final Set<Card> evasive = new LinkedHashSet<>();
        for (Card c : able) {
            if (isEvasive(c)) {
                evasive.add(c);
            }
        }
        addConfig(configs, evasive);
        if (able.size() <= ALPHA_MINUS_ONE_MAX_ABLE) {
            for (Card c : able) {
                final Set<Card> minusOne = new LinkedHashSet<>(able);
                minusOne.remove(c);
                addConfig(configs, minusOne);
            }
        }
        return configs;
    }

    private static void addConfig(List<Set<Card>> configs, Set<Card> config) {
        if (configs.size() >= MAX_CONFIGS) {
            return;
        }
        for (Set<Card> existing : configs) {
            if (existing.equals(config)) {
                return;
            }
        }
        configs.add(new LinkedHashSet<>(config));
    }

    /** Mirrors the evasive keyword set used by {@link GameStateEvaluator#evasiveDamage}. */
    private static boolean isEvasive(Card c) {
        return c.hasKeyword(Keyword.FLYING) || c.hasKeyword(Keyword.HORSEMANSHIP)
                || StaticAbilityCantAttackBlock.cantBlockBy(c, null);
    }

    /**
     * Simulates one attack configuration: copies the game, rewinds the copy to COMBAT_BEGIN
     * with a fresh Combat (the copier leaves the copy at DECLARE_ATTACKERS with combat ended,
     * and the phase machinery only runs turn-based actions for newly entered phases), forces
     * the mapped configuration via {@link #FORCED_ATTACKERS}, advances through combat to
     * MAIN2 and evaluates the resulting state.
     */
    private static int simulateConfig(Game game, Player aiPlayer, Set<Card> config) {
        final GameCopier copier = new GameCopier(game);
        final Game copy;
        if (game.EXPERIMENTAL_RESTORE_SNAPSHOT) {
            copy = copier.makeCopy();
        } else {
            copy = copier.makeCopy(null, aiPlayer);
        }
        final Player copyAiPlayer = (Player) copier.find(aiPlayer);

        final PhaseHandler ph = copy.getPhaseHandler();
        ph.devModeSet(PhaseType.COMBAT_BEGIN, null, false, ph.getTurn());
        ph.setCombat(new Combat(ph.getPlayerTurn()));

        // Map the configuration into the copy; drop silently anything that no longer maps
        // (canAttack in the copy is re-checked by the forced declaration itself).
        final Set<Card> forced = new LinkedHashSet<>();
        for (Card c : config) {
            final GameObject mapped = copier.findOrNull(c);
            if (mapped instanceof Card) {
                forced.add((Card) mapped);
            }
        }

        FORCED_ATTACKERS.set(forced);
        try {
            ph.devAdvanceToPhase(PhaseType.MAIN2,
                    () -> GameSimulator.resolveStack(copy, copyAiPlayer.getWeakestOpponent()));
        } finally {
            FORCED_ATTACKERS.remove();
        }
        if (!copy.isGameOver()) {
            // Settle anything enqueued by end-of-combat / MAIN2 phase begin before evaluating.
            GameSimulator.resolveStack(copy, copyAiPlayer.getWeakestOpponent());
        }
        return new GameStateEvaluator().getScoreForGameState(copy, copyAiPlayer).value;
    }

    /**
     * True when called from inside an engine simulation (spell-ability evaluation or the
     * evaluator's upcoming-combat sim). Copied games reuse the lobby player, so their
     * controllers carry the simulation flag too — without this check every evaluator copy
     * advancing through declare-attackers would spawn a full (nested) attack search.
     */
    private static boolean insideEngineSimulation() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> {
            final String cn = f.getClassName();
            return "forge.ai.simulation.GameSimulator".equals(cn)
                    || "forge.ai.simulation.GameStateEvaluator".equals(cn)
                    || "forge.ai.simulation.GameCopier".equals(cn);
        }));
    }

    private static String describe(Collection<Card> cards) {
        if (cards.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(cards.size()).append(':');
        boolean first = true;
        for (Card c : cards) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(c.getName());
        }
        return sb.length() > DESC_MAX_LEN ? sb.substring(0, DESC_MAX_LEN) : sb.toString();
    }

    private static void log(Player aiPlayer, Game game, int able, int numConfigs,
            String chosenDesc, String heurDesc, int chosenScore, int heurScore, long startMs) {
        System.err.println("[SIM_COMBAT] player=" + aiPlayer.getName()
                + " turn=" + game.getPhaseHandler().getTurn()
                + " able=" + able
                + " configs=" + numConfigs
                + " chosen=" + chosenDesc
                + " heur=" + heurDesc
                + " chosenScore=" + chosenScore
                + " heurScore=" + heurScore
                + " timeMs=" + (System.currentTimeMillis() - startMs));
    }
}
