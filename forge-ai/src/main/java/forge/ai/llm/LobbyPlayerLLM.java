package forge.ai.llm;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.player.Player;

/**
 * Lobby player that creates LLM-backed controllers.
 */
public class LobbyPlayerLLM extends LobbyPlayerAi {

    private final LLMClient client;

    public LobbyPlayerLLM(String name, LLMClient client) {
        super(name, null);
        this.client = client;
    }

    @Override
    protected PlayerControllerAi createControllerFor(Player ai) {
        return new LLMFullController(ai.getGame(), ai, this, client);
    }
}
