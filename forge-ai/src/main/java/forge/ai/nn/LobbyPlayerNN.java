package forge.ai.nn;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.player.Player;

/**
 * Lobby player that creates NN-backed controllers (hybrid or full mode).
 */
public class LobbyPlayerNN extends LobbyPlayerAi {

    private final NNBridge bridge;
    private final String exportDir; // nullable — only if --nn-export
    private final boolean fullMode; // true = Full, false = Hybrid

    public LobbyPlayerNN(String name, NNBridge bridge, String exportDir, boolean fullMode) {
        super(name, null);
        this.bridge = bridge;
        this.exportDir = exportDir;
        this.fullMode = fullMode;
    }

    @Override
    protected PlayerControllerAi createControllerFor(Player ai) {
        TrainingDataWriter dataWriter = null;
        if (exportDir != null) {
            dataWriter = new TrainingDataWriter(exportDir);
        }

        if (fullMode) {
            return new NNFullController(ai.getGame(), ai, this, bridge, dataWriter);
        } else {
            return new NNHybridController(ai.getGame(), ai, this, bridge, dataWriter);
        }
    }
}
