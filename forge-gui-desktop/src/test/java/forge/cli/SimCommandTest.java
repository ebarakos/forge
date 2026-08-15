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
    public void testSnapshotDisabledByDefault() {
        // Snapshot-restore game copying is opt-in: the copies can desync static
        // abilities, so a sim run only uses it when the caller asks for it with -s.
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck");
        Assert.assertFalse(cmd.isUseSnapshot(), "Snapshot restore should be off unless -s is passed");
    }

    @Test
    public void testNoSnapshotFlagDisablesSnapshot() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "--no-snapshot");
        Assert.assertFalse(cmd.isUseSnapshot(), "--no-snapshot should disable snapshot restore");
    }

    @Test
    public void testExplicitSnapshotFlagEnablesSnapshot() {
        SimCommand cmd = parseArgs("-d", "deck1.dck", "-d", "deck2.dck", "-s");
        Assert.assertTrue(cmd.isUseSnapshot(), "-s should turn snapshot restore on");
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
