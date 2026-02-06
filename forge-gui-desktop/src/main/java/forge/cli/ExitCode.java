package forge.cli;

/**
 * Standard exit codes for Forge CLI.
 * Following Unix conventions for exit status.
 */
public final class ExitCode {
    /** Successful execution */
    public static final int SUCCESS = 0;

    /** Invalid arguments or usage error */
    public static final int ARGS_ERROR = 1;

    /** Deck loading or file error */
    public static final int DECK_ERROR = 2;

    /** Runtime/execution error */
    public static final int RUNTIME_ERROR = 3;

    private ExitCode() {
        // Prevent instantiation
    }
}
