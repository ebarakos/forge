/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.view;

import forge.GuiDesktop;
import forge.Singletons;
import forge.cli.ExitCode;
import forge.cli.ForgeCli;
import forge.error.ExceptionHandler;
import forge.gui.GuiBase;
import forge.util.BuildInfo;
import io.sentry.Sentry;
import picocli.CommandLine;

/**
 * Main class for Forge's swing application view.
 */
public final class Main {
    /**
     * Main entry point for Forge
     */
    public static void main(final String[] args) {
        // Initialize Sentry for error tracking
        Sentry.init(options -> {
            options.setEnableExternalConfiguration(true);
            options.setRelease(BuildInfo.getVersionString());
            options.setEnvironment(System.getProperty("os.name"));
            options.setTag("Java Version", System.getProperty("java.version"));
            options.setShutdownTimeoutMillis(5000);
            // these belong to sentry.properties, but somehow some OS/Zip tool discards it?
            if (options.getDsn() == null || options.getDsn().isEmpty())
                options.setDsn("https://87bc8d329e49441895502737c069067b@sentry.cardforge.org//3");
        }, true);

        // HACK - temporary solution to "Comparison method violates it's general contract!" crash
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");

        // Turn off the Java 2D system's use of Direct3D to improve rendering speed
        System.setProperty("sun.java2d.d3d", "false");

        // Setup GUI interface (needed for both GUI and CLI modes)
        GuiBase.setInterface(new GuiDesktop());

        // Install our error handler
        ExceptionHandler.registerErrorHandling();

        // No arguments = launch GUI
        if (args.length == 0) {
            launchGui();
            return;
        }

        // CLI mode - use picocli for parsing
        int exitCode = new CommandLine(new ForgeCli())
            .setExecutionExceptionHandler(new ExecutionExceptionHandler())
            .setParameterExceptionHandler(new ParameterExceptionHandler())
            .execute(args);

        System.exit(exitCode);
    }

    /**
     * Launch the graphical user interface.
     */
    private static void launchGui() {
        Singletons.initializeOnce(true);
        // Controller can now step in and take over.
        Singletons.getControl().initialize();
    }

    /**
     * Handle execution exceptions (runtime errors during command execution).
     */
    private static class ExecutionExceptionHandler implements CommandLine.IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine cmd,
                CommandLine.ParseResult parseResult) {
            System.err.println("Error: " + ex.getMessage());
            // Show stack trace if debug mode is enabled
            if (System.getProperty("forge.debug") != null) {
                ex.printStackTrace(System.err);
            }
            return ExitCode.RUNTIME_ERROR;
        }
    }

    /**
     * Handle parameter/parsing exceptions (invalid arguments).
     */
    private static class ParameterExceptionHandler implements CommandLine.IParameterExceptionHandler {
        @Override
        public int handleParseException(CommandLine.ParameterException ex, String[] args) {
            CommandLine cmd = ex.getCommandLine();
            System.err.println("Error: " + ex.getMessage());
            System.err.println();
            cmd.usage(System.err);
            return ExitCode.ARGS_ERROR;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void finalize() throws Throwable {
        try {
            ExceptionHandler.unregisterErrorHandling();
        } finally {
            super.finalize();
        }
    }

    // Disallow instantiation
    private Main() {
    }
}
