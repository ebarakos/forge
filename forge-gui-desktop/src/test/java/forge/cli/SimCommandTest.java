package forge.cli;

import org.testng.Assert;
import org.testng.annotations.Test;
import picocli.CommandLine;

/**
 * Tests for SimCommand CLI option parsing.
 */
public class SimCommandTest {

    private SimCommand parseArgs(String... args) {
        SimCommand cmd = new SimCommand();
        new CommandLine(cmd).parseArgs(args);
        return cmd;
    }

    @Test
    public void testSnapshotEnabledByDefault() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck");
        Assert.assertTrue(cmd.isUseSnapshot(), "Snapshot should be enabled by default in sim mode");
    }

    @Test
    public void testNoSnapshotFlagDisablesSnapshot() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "--no-snapshot");
        Assert.assertFalse(cmd.isUseSnapshot(), "--no-snapshot should disable snapshot restore");
    }

    @Test
    public void testExplicitSnapshotFlagStillWorks() {
        // -s flag is now a no-op (snapshot is default on), but should still parse without error
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "-s");
        Assert.assertTrue(cmd.isUseSnapshot(), "-s flag should keep snapshot enabled");
    }

    @Test
    public void testNoSnapshotOverridesExplicitSnapshot() {
        // If both -s and --no-snapshot are passed, --no-snapshot wins
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "-s", "--no-snapshot");
        Assert.assertFalse(cmd.isUseSnapshot(), "--no-snapshot should override -s flag");
    }

    @Test
    public void testDefaultGameCount() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck");
        Assert.assertEquals(cmd.getNumGames(), 1, "Default game count should be 1");
    }

    @Test
    public void testQuietModeFlag() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "-q");
        Assert.assertTrue(cmd.isQuiet(), "-q should enable quiet mode");
    }

    @Test
    public void testJsonOutputFlag() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "--json");
        Assert.assertTrue(cmd.isJsonOutput(), "--json should enable JSON output");
    }

    @Test
    public void testPlayerProfileParsing() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "-P1", "Enhanced", "-P2", "Ascended");
        Assert.assertEquals(cmd.getPlayerProfile(0), "Enhanced");
        Assert.assertEquals(cmd.getPlayerProfile(1), "Ascended");
        Assert.assertNull(cmd.getPlayerProfile(2), "Unset profile should be null");
    }
}
