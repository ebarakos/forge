package forge.cli.json;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON output model for simulation results.
 * This class and its nested classes are designed to be serialized with Gson.
 */
public class SimulationResult {
    /** Forge version string */
    public String version;

    /** Configuration used for this simulation */
    public SimulationConfig config;

    /** Summary statistics */
    public SimulationSummary summary;

    /** Individual game results */
    public List<GameResult> games = new ArrayList<>();

    /** Tournament results (null if not tournament mode) */
    public TournamentResult tournament;

    /**
     * Configuration settings for the simulation.
     */
    public static class SimulationConfig {
        public List<String> decks = new ArrayList<>();
        public String format;
        public int gamesRequested;
        public Integer matchSize;
        public int timeoutSeconds;
        public boolean snapshotEnabled;
        public Integer parallelJobs;
        public List<String> aiProfiles = new ArrayList<>();
        public String tournamentType;
        public int playersPerMatch;
    }

    /**
     * Summary statistics for the simulation.
     */
    public static class SimulationSummary {
        public int totalGames;
        public int completedGames;
        public int draws;
        public long totalTimeMs;
        public double averageGameTimeMs;
        public double gamesPerSecond;
        public List<PlayerSummary> players = new ArrayList<>();
    }

    /**
     * Per-player summary statistics.
     */
    public static class PlayerSummary {
        public int playerIndex;
        public String name;
        public String deck;
        public String aiProfile;
        public int wins;
        public int losses;
        public double winRate;
    }

    /**
     * Results for a single game.
     */
    public static class GameResult {
        public int gameNumber;
        public boolean isDraw;
        public String winner;
        public Integer winnerIndex;
        public long durationMs;
        public int turns;
        public String endReason;
        public List<PlayerGameResult> players = new ArrayList<>();
        /** Full game log (only included when --json flag is used) */
        public List<GameLogEntry> log;
    }

    /**
     * Per-player result for a single game.
     */
    public static class PlayerGameResult {
        public int playerIndex;
        public String name;
        public int finalLife;
        public String outcome;
        public int cardsInHand;
        public int permanentsInPlay;
    }

    /**
     * Single game log entry.
     */
    public static class GameLogEntry {
        public String type;
        public String message;
        public Integer turn;
        public String phase;

        public GameLogEntry() {}

        public GameLogEntry(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    /**
     * Tournament results container.
     */
    public static class TournamentResult {
        public String type;
        public int totalRounds;
        public int totalPlayers;
        public List<TournamentRound> rounds = new ArrayList<>();
        public List<TournamentStanding> finalStandings = new ArrayList<>();
    }

    /**
     * Single round in a tournament.
     */
    public static class TournamentRound {
        public int roundNumber;
        public List<TournamentMatch> matches = new ArrayList<>();
    }

    /**
     * Single match in a tournament round.
     */
    public static class TournamentMatch {
        public List<String> players = new ArrayList<>();
        public String winner;
        public boolean isBye;
        public int player1Wins;
        public int player2Wins;
    }

    /**
     * Final standings entry.
     */
    public static class TournamentStanding {
        public int rank;
        public String player;
        public String deck;
        public int matchWins;
        public int matchLosses;
        public int gameWins;
        public int gameLosses;
        public double matchWinRate;
    }
}
