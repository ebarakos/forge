package forge.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Simulation subcommand for running AI vs AI matches.
 */
@Command(
    name = "sim",
    description = "Run AI vs AI match simulation",
    mixinStandardHelpOptions = true,
    sortOptions = false
)
public class SimCommand implements Callable<Integer> {

    // === Deck Options ===

    @Option(
        names = {"-d", "--deck"},
        description = "Deck file or name. Can be specified multiple times for each player.",
        paramLabel = "DECK"
    )
    private List<String> decks = new ArrayList<>();

    @Option(
        names = {"-D", "--deck-directory"},
        description = "Directory to load ALL decks from (batch mode for tournaments).",
        paramLabel = "DIR"
    )
    private File deckDirectory;

    @Option(
        names = {"-B", "--base-dir"},
        description = "Base directory for relative deck paths (overrides default constructed/commander dirs).",
        paramLabel = "DIR"
    )
    private File baseDir;

    // === Game Configuration ===

    @Option(
        names = {"-n", "--games"},
        description = "Number of games to simulate. Default: ${DEFAULT-VALUE}",
        defaultValue = "1",
        paramLabel = "N"
    )
    private int numGames;

    @Option(
        names = {"-m", "--match"},
        description = "Play full match of N games (best of N, typically 1, 3, or 5). Overrides --games.",
        paramLabel = "N"
    )
    private Integer matchSize;

    @Option(
        names = {"-f", "--format"},
        description = "Game format. Default: ${DEFAULT-VALUE}. Valid: Constructed, Commander, etc.",
        defaultValue = "Constructed",
        paramLabel = "FORMAT"
    )
    private String format;

    @Option(
        names = {"-c", "--clock"},
        description = "Maximum time in seconds before calling match a draw. Default: ${DEFAULT-VALUE}",
        defaultValue = "120",
        paramLabel = "SECS"
    )
    private int timeout;

    // === Tournament Options ===

    @Option(
        names = {"-t", "--tournament"},
        description = "Tournament type: Bracket, RoundRobin, or Swiss.",
        paramLabel = "TYPE"
    )
    private String tournamentType;

    @Option(
        names = {"-p", "--players"},
        description = "Number of players per match in tournament mode. Default: ${DEFAULT-VALUE}",
        defaultValue = "2",
        paramLabel = "N"
    )
    private int playersPerMatch;

    // === Performance Options ===

    @Option(
        names = {"-j", "--jobs"},
        description = "Number of parallel threads for batch simulation. Implies quiet mode.",
        paramLabel = "N"
    )
    private Integer numJobs;

    @Option(
        names = {"-s", "--snapshot"},
        description = "Enable snapshot restore for faster AI simulation (enabled by default in sim mode)."
    )
    private boolean useSnapshot;

    @Option(
        names = {"--no-snapshot"},
        description = "Disable snapshot restore (overrides default)."
    )
    private boolean noSnapshot;

    // === Output Options ===

    @Option(
        names = {"-q", "--quiet"},
        description = "Suppress game logs, only show game results."
    )
    private boolean quiet;

    @Option(
        names = {"--json"},
        description = "Output results in JSON format with full game logs to stdout."
    )
    private boolean jsonOutput;

    @Option(
        names = {"--csv"},
        description = "Output per-game results in CSV format to stdout."
    )
    private boolean csvOutput;

    // === Neural Network Options ===

    @Option(
        names = {"--nn-hybrid"},
        description = "Use NN hybrid mode: NN controls mulligan, attack, block, targeting; heuristic handles rest."
    )
    private boolean nnHybrid;

    @Option(
        names = {"--nn-full"},
        description = "Use NN full mode: NN controls all decisions."
    )
    private boolean nnFull;

    @Option(
        names = {"--nn-model"},
        description = "Path to ONNX model file for NN inference.",
        paramLabel = "FILE"
    )
    private File nnModel;

    @Option(
        names = {"--nn-random"},
        description = "Use random bridge instead of ONNX model (for testing/data generation)."
    )
    private boolean nnRandom;

    @Option(
        names = {"--nn-export"},
        description = "Directory to export training data (JSONL) to.",
        paramLabel = "DIR"
    )
    private File nnExportDir;

    @Option(
        names = {"--nn-epsilon"},
        description = "Epsilon for epsilon-greedy exploration (0.0 = pure model, 1.0 = pure random). Default: ${DEFAULT-VALUE}",
        defaultValue = "0.0",
        paramLabel = "FLOAT"
    )
    private float nnEpsilon;

    @Option(
        names = {"--nn-player"},
        description = "Which player uses NN: 1, 2, or all. Default: ${DEFAULT-VALUE}",
        defaultValue = "all",
        paramLabel = "PLAYER"
    )
    private String nnPlayer;

    // === AI Profile Options ===

    @Option(
        names = {"-P", "--profile"},
        description = "AI profile assignment as N:PROFILE (e.g. 1:Simulation 2:Default). Can be repeated.",
        paramLabel = "N:PROFILE"
    )
    private List<String> profileAssignments = new ArrayList<>();

    @Option(
        names = {"--list-profiles"},
        description = "List available AI profiles and exit."
    )
    private boolean listProfiles;

    // Legacy per-player profile flags (kept for backward compatibility)

    @Option(names = {"-P1", "--player1-profile"}, description = "AI profile for player 1 (legacy, prefer -P 1:PROFILE).", paramLabel = "PROFILE", hidden = true)
    private String player1Profile;

    @Option(names = {"-P2", "--player2-profile"}, description = "AI profile for player 2 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player2Profile;

    @Option(names = {"-P3", "--player3-profile"}, description = "AI profile for player 3 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player3Profile;

    @Option(names = {"-P4", "--player4-profile"}, description = "AI profile for player 4 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player4Profile;

    @Option(names = {"-P5", "--player5-profile"}, description = "AI profile for player 5 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player5Profile;

    @Option(names = {"-P6", "--player6-profile"}, description = "AI profile for player 6 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player6Profile;

    @Option(names = {"-P7", "--player7-profile"}, description = "AI profile for player 7 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player7Profile;

    @Option(names = {"-P8", "--player8-profile"}, description = "AI profile for player 8 (legacy).", paramLabel = "PROFILE", hidden = true)
    private String player8Profile;

    // === Getters ===

    public List<String> getDecks() {
        return decks;
    }

    public File getDeckDirectory() {
        return deckDirectory;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public int getNumGames() {
        return numGames;
    }

    public Integer getMatchSize() {
        return matchSize;
    }

    public String getFormat() {
        return format;
    }

    public int getTimeout() {
        return timeout;
    }

    public String getTournamentType() {
        return tournamentType;
    }

    public int getPlayersPerMatch() {
        return playersPerMatch;
    }

    public Integer getNumJobs() {
        return numJobs;
    }

    public boolean isUseSnapshot() {
        // Snapshot is enabled by default in sim mode; --no-snapshot disables it
        if (noSnapshot) {
            return false;
        }
        return true; // default on, -s flag is now a no-op (kept for backward compat)
    }

    public boolean isQuiet() {
        return quiet;
    }

    public boolean isJsonOutput() {
        return jsonOutput;
    }

    public boolean isCsvOutput() {
        return csvOutput;
    }

    public boolean isListProfiles() {
        return listProfiles;
    }

    public boolean isNnHybrid() {
        return nnHybrid;
    }

    public boolean isNnFull() {
        return nnFull;
    }

    public File getNnModel() {
        return nnModel;
    }

    public boolean isNnRandom() {
        return nnRandom;
    }

    public File getNnExportDir() {
        return nnExportDir;
    }

    public boolean isNnMode() {
        return nnHybrid || nnFull;
    }

    public float getNnEpsilon() {
        return nnEpsilon;
    }

    public String getNnPlayer() {
        return nnPlayer;
    }

    /**
     * Get the AI profile for a specific player (0-indexed).
     * New -P N:PROFILE syntax takes precedence over legacy -P1..-P8 flags.
     *
     * @param playerIndex 0-based player index
     * @return AI profile string or null if not set
     */
    public String getPlayerProfile(int playerIndex) {
        // Check new -P syntax first (takes precedence)
        Map<Integer, String> parsed = parseProfileAssignments();
        if (parsed.containsKey(playerIndex)) {
            return parsed.get(playerIndex);
        }

        // Fall back to legacy -P1..-P8 flags
        switch (playerIndex) {
            case 0: return player1Profile;
            case 1: return player2Profile;
            case 2: return player3Profile;
            case 3: return player4Profile;
            case 4: return player5Profile;
            case 5: return player6Profile;
            case 6: return player7Profile;
            case 7: return player8Profile;
            default: return null;
        }
    }

    /**
     * Parse -P N:PROFILE assignments into a map of 0-indexed player to profile.
     */
    private Map<Integer, String> parseProfileAssignments() {
        Map<Integer, String> result = new HashMap<>();
        for (String assignment : profileAssignments) {
            int colonIdx = assignment.indexOf(':');
            if (colonIdx > 0 && colonIdx < assignment.length() - 1) {
                try {
                    int playerNum = Integer.parseInt(assignment.substring(0, colonIdx));
                    String profile = assignment.substring(colonIdx + 1);
                    result.put(playerNum - 1, profile); // Convert 1-indexed to 0-indexed
                } catch (NumberFormatException e) {
                    System.err.println("Warning: Invalid profile assignment '" + assignment + "' (expected N:PROFILE)");
                }
            } else {
                System.err.println("Warning: Invalid profile assignment '" + assignment + "' (expected N:PROFILE, e.g. 1:Simulation)");
            }
        }
        return result;
    }

    @Override
    public Integer call() {
        // Handle --list-profiles
        if (listProfiles) {
            return forge.view.SimulateMatch.listProfiles();
        }
        // Delegate to SimulateMatch with the new interface
        return forge.view.SimulateMatch.simulate(this);
    }
}
