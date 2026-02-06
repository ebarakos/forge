package forge.cli;

import forge.gui.card.CardReaderExperiments;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Parse subcommand for card data parsing utilities.
 */
@Command(
    name = "parse",
    description = "Parse card data files",
    mixinStandardHelpOptions = true
)
public class ParseCommand implements Callable<Integer> {

    @Parameters(
        description = "Operations to perform (e.g., updateAbilityManaSymbols)",
        arity = "0..*"
    )
    private List<String> operations = new ArrayList<>();

    @Override
    public Integer call() {
        // Build args array compatible with existing CardReaderExperiments
        String[] args = new String[operations.size() + 1];
        args[0] = "parse";
        for (int i = 0; i < operations.size(); i++) {
            args[i + 1] = operations.get(i);
        }

        try {
            CardReaderExperiments.parseAllCards(args);
            return ExitCode.SUCCESS;
        } catch (Exception e) {
            System.err.println("Error during card parsing: " + e.getMessage());
            return ExitCode.RUNTIME_ERROR;
        }
    }
}
