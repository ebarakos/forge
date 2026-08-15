package forge.gamesimulationtests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.GameState;
import forge.ai.StateDumpChecksum;
import forge.game.Game;

/**
 * A board written out as {@link GameState} text and read back must be the same board.
 *
 * <p>This is the guarantee the AI's board dumps rest on: a dumped position is replayed by
 * restoring it into a fresh game, so anything the format silently drops on the way out or
 * fails to put back on the way in turns a measurement into a fiction. The test dumps a
 * hand-built board, restores it, dumps it again, and compares.
 *
 * <p>The board deliberately carries every card property the dump records and the AI cares
 * about: tapped permanents, counters, marked damage, an aura and an equipment attached to
 * one creature, and ordered libraries on both sides. Libraries matter twice over — a
 * restored game keeps exactly the cards the file lists and never shuffles, so a dump with a
 * missing or reordered library would replay a different game.
 *
 * <p>What this cannot test is what the format never sees. Until-end-of-turn effects,
 * anything on the stack, and most this-turn memory are absent from the dump, so a
 * round trip cannot notice losing them. Dumping only at the deciding player's own first
 * main phase with an empty stack is what keeps that loss small.
 */
public class GameStateRoundTripTest extends AITest {

    /**
     * Card ids in the two dumps, wherever one card points at another. They are new objects
     * in the restored game, so the numbers themselves differ and mean nothing; what has to
     * survive is which card points at which.
     */
    private static final Pattern ID_REFERENCE = Pattern.compile(
            "\\b(Id|AttachedTo|ExiledWith|Attacking|ChosenCards|RememberedCards|Imprinting):(\\d+(?:,\\d+)*)");

    @Test(description = "A dumped board restores to the same board")
    public void dumpedBoardRestoresToTheSameBoard() {
        final Game original = scriptedGame(
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "p0life=17",
                "p0battlefield=Mountain|Tapped;Mountain;Grizzly Bears|Damage:1",
                "p0hand=Lightning Bolt;Mountain",
                "p0graveyard=Lightning Bolt;Mountain",
                "p0library=Island;Swamp;Plains;Forest;Mountain",
                "p1life=13",
                "p1battlefield=Forest;Forest|Tapped;Grizzly Bears|Id:1|Counters:P1P1=2|Damage:1;"
                        + "Rancor|AttachedTo:1;Bonesplitter|AttachedTo:1",
                "p1hand=Grizzly Bears;Forest",
                "p1graveyard=",
                "p1library=Plains;Island;Swamp;Mountain;Forest");
        final String dumped = dump(original);

        // Guard against a test that passes because both dumps are empty or featureless.
        assertRecords(dumped, "|Tapped");
        assertRecords(dumped, "|Counters:P1P1=2");
        assertRecords(dumped, "|Damage:1");
        assertRecords(dumped, "|AttachedTo:");
        assertRecords(dumped, "Rancor");
        assertRecords(dumped, "Bonesplitter");

        final Game restored = scriptedGame(dumped.split("\n"));
        final String redumped = dump(restored);

        Assert.assertEquals(stableFields(redumped), stableFields(dumped),
                "A restored board dumped again does not match the board it was restored from");
    }

    @Test(description = "A restored library keeps its exact order")
    public void restoredLibrariesKeepTheirOrder() {
        // Restoring never shuffles, so the order in the file is the order the replayed game
        // draws in. A silent reversal here would still round-trip, which is why the order is
        // checked against the board that was written by hand rather than against the dump.
        final List<String> order = Arrays.asList("Plains", "Island", "Swamp", "Mountain", "Forest");
        final Game original = scriptedGame(
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "p0life=20",
                "p0battlefield=",
                "p0library=Mountain;Forest;Island",
                "p1life=20",
                "p1battlefield=",
                "p1library=" + String.join(";", order));
        final String dumped = dump(original);
        Assert.assertEquals(cardNames(dumped, "p1library"), order,
                "The dump did not write the library in the order the game held it");

        final String redumped = dump(scriptedGame(dumped.split("\n")));
        Assert.assertEquals(cardNames(redumped, "p1library"), order,
                "Restoring the dump reordered the library");
        Assert.assertEquals(cardNames(redumped, "p0library"),
                Arrays.asList("Mountain", "Forest", "Island"),
                "Restoring the dump reordered the opponent's library");
    }

    /**
     * A dump that survives the round trip can still be the wrong board, because the file
     * can have changed since it was written. A truncated dump is a valid dump of a
     * different position: it restores cleanly, it round-trips cleanly, and every check that
     * compares the game against the file passes, because the file agrees with itself.
     *
     * <p>The checksum in the header is the only thing that can tell the difference, so what
     * is checked here is that it does: an intact dump passes, a dump one line shorter
     * fails, and a board with no checksum line — hand-written, or dumped before the header
     * carried one — is still accepted rather than being locked out.
     */
    @Test(description = "A truncated dump fails the checksum its header carries")
    public void aTruncatedDumpIsCaughtByItsChecksum() {
        final String board = dump(scriptedGame(
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "p0life=17",
                "p0battlefield=Mountain|Tapped;Mountain;Grizzly Bears|Damage:1",
                "p0hand=Lightning Bolt;Mountain",
                "p0library=Island;Swamp;Plains;Forest;Mountain",
                "p1life=13",
                "p1battlefield=Forest;Forest|Tapped;Grizzly Bears|Counters:P1P1=2",
                "p1hand=Grizzly Bears;Forest",
                "p1library=Plains;Island;Swamp;Mountain;Forest"));

        // Written exactly the way the AI decision trace writes it: comment header, checksum
        // line, then the board.
        final String file = "# forge ai board dump\n"
                + "# card=Grizzly Bears\n"
                + "# seat=1\n"
                + StateDumpChecksum.headerLineFor(board) + "\n"
                + board;
        Assert.assertNull(StateDumpChecksum.problem(file),
                "An intact dump was reported as not matching its own checksum");

        // Cutting the last line off is what a killed process or a full disk leaves behind.
        final int lastBreak = file.stripTrailing().lastIndexOf('\n');
        final String truncated = file.substring(0, lastBreak);
        Assert.assertNotEquals(truncated, file, "The test failed to truncate the dump");
        Assert.assertNotNull(StateDumpChecksum.problem(truncated),
                "A truncated dump was accepted as the board that was written");

        // Editing a value in place is the other way a file stops being the dump.
        final String edited = file.replace("p1life=13", "p1life=3");
        Assert.assertNotEquals(edited, file, "The test failed to edit the dump");
        Assert.assertNotNull(StateDumpChecksum.problem(edited),
                "An edited dump was accepted as the board that was written");

        // Comments are outside the digest on purpose, so a header field can be added later
        // without invalidating every dump already written.
        Assert.assertNull(StateDumpChecksum.problem(file + "\n# note=added afterwards"),
                "Adding a comment line invalidated the checksum");

        // And a board with no checksum at all stays usable.
        Assert.assertNull(StateDumpChecksum.problem(board),
                "A hand-built board with no checksum line was refused");
    }

    private String dump(final Game game) {
        final GameState state = newGameState();
        state.initFromGame(game);
        return state.toString();
    }

    /**
     * The part of a dump that has to survive a round trip, with card ids renumbered in order
     * of first appearance so that two games' id counters do not fail the comparison.
     */
    private static List<String> stableFields(final String dump) {
        final Map<String, String> canonicalIds = new LinkedHashMap<>();
        final List<String> lines = new ArrayList<>();
        for (final String line : dump.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            lines.add(canonicalizeIds(line, canonicalIds));
        }
        return lines;
    }

    private static String canonicalizeIds(final String line, final Map<String, String> canonicalIds) {
        final Matcher matcher = ID_REFERENCE.matcher(line);
        final StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            final List<String> renumbered = new ArrayList<>();
            for (final String id : matcher.group(2).split(",")) {
                renumbered.add(canonicalIds.computeIfAbsent(id, key -> "#" + (canonicalIds.size() + 1)));
            }
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group(1) + ":" + String.join(",", renumbered)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The card names of one zone line, in the order the dump wrote them. */
    private static List<String> cardNames(final String dump, final String key) {
        for (final String line : dump.split("\n")) {
            if (!line.startsWith(key + "=")) {
                continue;
            }
            final String value = line.substring(key.length() + 1);
            final List<String> names = new ArrayList<>();
            if (value.isEmpty()) {
                return names;
            }
            for (final String card : value.split(";")) {
                names.add(card.split("\\|")[0]);
            }
            return names;
        }
        throw new AssertionError("The dump has no " + key + " line:\n" + dump);
    }

    private static void assertRecords(final String dump, final String expected) {
        Assert.assertTrue(dump.contains(expected),
                "The dump does not record " + expected + ", so the round trip proves nothing about it:\n" + dump);
    }

}
