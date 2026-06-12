package forge.ai.simulation;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;

/**
 * Per-game collector that samples {@link GameStateEvaluator#extractFeatures}
 * for every player at the start of each turn and, once the game outcome is
 * known, appends the labelled rows as JSON Lines to the file named by the
 * {@code forge.sim.features} system property.
 * <p>
 * One collector instance is attached per game ({@link #attach}); buffered rows
 * are only touched from that game's thread (Forge's event bus dispatches
 * synchronously), so the only shared state is the static file lock guarding
 * the append. Safe under parallel game execution.
 */
public final class EvalFeatureCollector {
    /** System property naming the output JSONL path; collection is off when unset. */
    public static final String OUTPUT_PROPERTY = "forge.sim.features";

    private static final Object FILE_LOCK = new Object();

    private final Game game;
    private final String gameId = UUID.randomUUID().toString();
    private final String deck0;
    private final String deck1;
    private final List<Row> rows = new ArrayList<>();
    private final String outputPath;
    private boolean written = false;

    private static final class Row {
        final Player perspective;
        final String jsonPrefix; // row JSON without the trailing "won" field

        Row(Player perspective, String jsonPrefix) {
            this.perspective = perspective;
            this.jsonPrefix = jsonPrefix;
        }
    }

    private EvalFeatureCollector(Game game, String outputPath, String deck0, String deck1) {
        this.game = game;
        this.outputPath = outputPath;
        this.deck0 = sanitize(deck0);
        this.deck1 = sanitize(deck1);
    }

    /**
     * Attaches a feature collector to {@code game} when the
     * {@code forge.sim.features} system property is set. No-op otherwise.
     */
    public static void attach(Game game, String deckP0, String deckP1) {
        String path = System.getProperty(OUTPUT_PROPERTY);
        if (path == null || path.isEmpty() || game == null) {
            return;
        }
        EvalFeatureCollector collector = new EvalFeatureCollector(game, path, deckP0, deckP1);
        game.subscribeToEvents(collector);
    }

    @Subscribe
    public void onTurnBegan(GameEventTurnBegan event) {
        try {
            List<Player> players = game.getPlayers();
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                Map<String, Integer> features = GameStateEvaluator.extractFeatures(game, p);
                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"game_id\":\"").append(gameId).append('"');
                sb.append(",\"deck0\":\"").append(deck0).append('"');
                sb.append(",\"deck1\":\"").append(deck1).append('"');
                sb.append(",\"player_index\":").append(i);
                sb.append(",\"player\":\"").append(sanitize(p.getName())).append('"');
                for (Map.Entry<String, Integer> e : features.entrySet()) {
                    sb.append(",\"").append(e.getKey()).append("\":").append(e.getValue());
                }
                rows.add(new Row(p, sb.toString()));
            }
        } catch (Exception e) {
            // Never let data collection break the game.
            System.err.println("EvalFeatureCollector: feature extraction failed: " + e);
        }
    }

    @Subscribe
    public void onGameOutcome(GameEventGameOutcome event) {
        if (written || rows.isEmpty()) {
            return;
        }
        written = true;
        StringBuilder out = new StringBuilder(rows.size() * 280);
        for (Row row : rows) {
            boolean won = event.result() != null
                    && event.result().isWinner(row.perspective.getRegisteredPlayer());
            out.append(row.jsonPrefix).append(",\"won\":").append(won ? 1 : 0).append("}\n");
        }
        rows.clear();
        synchronized (FILE_LOCK) {
            try (FileWriter writer = new FileWriter(outputPath, true)) {
                writer.write(out.toString());
                writer.flush();
            } catch (IOException e) {
                System.err.println("EvalFeatureCollector: failed to append to "
                        + outputPath + ": " + e);
            }
        }
    }

    /** Restricts names to [A-Za-z0-9 _-] so rows never need JSON escaping. */
    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^A-Za-z0-9 _-]", "");
    }
}
