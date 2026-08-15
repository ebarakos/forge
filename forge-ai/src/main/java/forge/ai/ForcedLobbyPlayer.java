package forge.ai;

import java.util.Set;

import forge.game.player.Player;

/**
 * Lobby player whose seat forces one named action during one turn.
 *
 * <p>Everything about the seat other than that single overruled decision is an ordinary AI
 * seat, so a game with one of these differs from a normal game in exactly one place. See
 * {@link ForcedActionController} for what "forcing" does and does not bypass.
 */
public class ForcedLobbyPlayer extends LobbyPlayerAi {

    private final String spec;
    private final int forcedTurn;
    private final int maxForces;
    private ForcedActionController controller;

    /**
     * @param spec {@code "Card Name|description-substring"}; the description part is optional.
     * @param forcedTurn the turn to force in, or 0 to arm on the first priority the seat sees.
     */
    public ForcedLobbyPlayer(final String name, final Set<AIOption> options, final String spec,
            final int forcedTurn) {
        this(name, options, spec, forcedTurn, ForcedActionController.DEFAULT_MAX_FORCES);
    }

    /**
     * @param spec {@code "Card Name|description-substring"}; the description part is optional.
     * @param forcedTurn the turn to force in, or 0 to arm on the first priority the seat sees.
     * @param maxForces how many times the seat plays the action; one keeps the difference
     *        between the arms attributable to a single decision.
     */
    public ForcedLobbyPlayer(final String name, final Set<AIOption> options, final String spec,
            final int forcedTurn, final int maxForces) {
        super(name, options);
        this.spec = spec;
        this.forcedTurn = forcedTurn;
        this.maxForces = maxForces;
    }

    /** The controller of the last player created for this seat, or null before the game starts. */
    public ForcedActionController getForcedController() {
        return controller;
    }

    @Override
    protected PlayerControllerAi createControllerFor(final Player ai) {
        controller = new ForcedActionController(ai.getGame(), ai, this, spec, forcedTurn, maxForces);
        controller.allowCheatShuffle(isAllowCheatShuffle());
        return controller;
    }
}
