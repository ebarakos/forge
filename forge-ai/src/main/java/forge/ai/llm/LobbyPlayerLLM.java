package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.player.Player;

/**
 * Lobby player that creates LLM-backed controllers.
 */
public class LobbyPlayerLLM extends LobbyPlayerAi {

    private final LLMClient client;
    private final LLMMode mode;

    public LobbyPlayerLLM(String name, LLMClient client, LLMMode mode) {
        super(name, null);
        this.client = client;
        this.mode = mode != null ? mode : LLMMode.LIGHT;
    }

    public LobbyPlayerLLM(String name, LLMClient client) {
        this(name, client, LLMMode.LIGHT);
    }

    @Override
    protected PlayerControllerAi createControllerFor(Player ai) {
        return new LLMFullController(ai.getGame(), ai, this, client, mode);
    }
}
