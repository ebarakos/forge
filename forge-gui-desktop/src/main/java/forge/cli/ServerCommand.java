package forge.cli;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Server subcommand placeholder.
 * Dedicated server mode is not yet implemented.
 */
@Command(
    name = "server",
    description = "Run dedicated server mode (not implemented)",
    mixinStandardHelpOptions = true
)
public class ServerCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Dedicated server mode is not implemented.");
        return ExitCode.SUCCESS;
    }
}
