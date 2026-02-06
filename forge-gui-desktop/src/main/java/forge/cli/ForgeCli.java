package forge.cli;

import forge.util.BuildInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

/**
 * Main CLI command for Forge.
 * Uses picocli for command-line parsing with proper help/version support.
 */
@Command(
    name = "forge",
    description = "Forge: Play Magic: the Gathering",
    mixinStandardHelpOptions = true,
    versionProvider = ForgeCli.VersionProvider.class,
    subcommands = {
        CommandLine.HelpCommand.class,
        SimCommand.class,
        ParseCommand.class,
        ServerCommand.class
    }
)
public class ForgeCli implements Runnable {

    /**
     * When no subcommand is provided, show help.
     * The GUI is launched separately when no args are passed at all.
     */
    @Override
    public void run() {
        // If we get here, user ran "forge" without subcommand but with some flags
        // Show help by default
        CommandLine.usage(this, System.out);
    }

    /**
     * Provides dynamic version information from BuildInfo.
     */
    public static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {
                "Forge " + BuildInfo.getVersionString(),
                "Java: " + System.getProperty("java.version"),
                "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
            };
        }
    }
}
