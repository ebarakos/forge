package forge.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
        description = "Enable experimental snapshot restore for faster AI simulation (2-3x speedup)."
    )
    private boolean useSnapshot;

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

    // === AI Profile Options ===

    @Option(
        names = {"-P1", "--player1-profile"},
        description = "AI profile for player 1. Options: Default, Cautious, Reckless, Experimental, Enhanced, Ascended, AlwaysPass",
        paramLabel = "PROFILE"
    )
    private String player1Profile;

    @Option(
        names = {"-P2", "--player2-profile"},
        description = "AI profile for player 2.",
        paramLabel = "PROFILE"
    )
    private String player2Profile;

    @Option(
        names = {"-P3", "--player3-profile"},
        description = "AI profile for player 3.",
        paramLabel = "PROFILE"
    )
    private String player3Profile;

    @Option(
        names = {"-P4", "--player4-profile"},
        description = "AI profile for player 4.",
        paramLabel = "PROFILE"
    )
    private String player4Profile;

    @Option(
        names = {"-P5", "--player5-profile"},
        description = "AI profile for player 5.",
        paramLabel = "PROFILE"
    )
    private String player5Profile;

    @Option(
        names = {"-P6", "--player6-profile"},
        description = "AI profile for player 6.",
        paramLabel = "PROFILE"
    )
    private String player6Profile;

    @Option(
        names = {"-P7", "--player7-profile"},
        description = "AI profile for player 7.",
        paramLabel = "PROFILE"
    )
    private String player7Profile;

    @Option(
        names = {"-P8", "--player8-profile"},
        description = "AI profile for player 8.",
        paramLabel = "PROFILE"
    )
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
        return useSnapshot;
    }

    public boolean isQuiet() {
        return quiet;
    }

    public boolean isJsonOutput() {
        return jsonOutput;
    }

    /**
     * Get the AI profile for a specific player (0-indexed).
     * @param playerIndex 0-based player index
     * @return AI profile string or null if not set
     */
    public String getPlayerProfile(int playerIndex) {
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

    @Override
    public Integer call() {
        // Delegate to SimulateMatch with the new interface
        return forge.view.SimulateMatch.simulate(this);
    }
}
