package forge.gamesimulationtests;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.GameState;
import forge.cli.AttributeCommand;
import forge.cli.json.AttributionResult;
import forge.game.Game;

/**
 * The {@code attribute} command replays a dumped board by restoring it into a fresh game. This
 * test covers the two things that decide whether its numbers mean anything.
 *
 * <p>First, <b>the restore has to be checked, and the check has to have teeth.</b> A board that
 * comes back wrong still plays a perfectly normal-looking game and reports a perfectly
 * normal-looking win rate, about a position nobody chose. So the command compares the restored
 * game against the file it came from — life totals, per-zone card counts, turn, phase, active
 * player — and refuses to play on any mismatch. Here that refusal is put in front of an intact
 * dump, which it must accept, and damaged ones, which it must not: a dump naming a card that
 * does not exist, which Forge drops in silence and would otherwise replay a hand one card
 * short as if nothing had happened, and a dump whose life total has been edited down to a
 * player who has already lost.
 *
 * <p>Second, <b>the seed has to reach the game, and only the seed.</b> It arrives through a
 * thread-local set on the thread the game runs on; set anywhere else it lands on a random
 * number generator nothing will ever read, and the run silently stops being reproducible while
 * still looking fine. And the libraries have to be re-shuffled after the restore, because the
 * dump records their order exactly: without that every rollout deals the same cards in the
 * same order, and a hundred of them are one game counted a hundred times. One board played
 * twice from one seed catches the first; the same board played from six seeds catches the
 * second.
 *
 * <p>What this cannot cover is the size of the campaign it feeds: whether forty rollouts from a
 * real position say anything about a real matchup is a question for the pre-registered
 * validation gates, not for a unit test.
 */
public class AttributeReplayTest extends AITest {

    /**
     * A board with something in every zone the dump records, so the count comparison is
     * checking real numbers rather than a row of zeroes.
     */
    private static final String[] BOARD = {
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
            "p1library=Plains;Island;Swamp;Mountain;Forest",
    };

    @Test(description = "An intact dump restores to the board it describes")
    public void anIntactDumpIsAccepted() {
        final String dumped = dump(scriptedGame(BOARD));

        final AttributeCommand.Replay check = AttributeCommand.replay(dumped, "Default", 60, 1L, true);

        Assert.assertEquals(AttributeCommand.refusals(check), new ArrayList<String>(),
                "An intact dump was reported as restoring to a different board");
        // A check that passes on an empty board would prove nothing, so make sure the board
        // this one passed on actually had cards in it.
        Assert.assertTrue(AttributeCommand.summarize(dumped).size() >= 10,
                "The board under test is too bare for its acceptance to mean anything:\n" + dumped);
    }

    @Test(description = "A dump naming a card that does not exist is refused")
    public void aDumpWithACardForgeCannotBuildIsRefused() {
        // Forge skips a card it cannot find and carries on, which is exactly why the count
        // comparison exists: the position would otherwise replay one card short, in silence.
        assertRefused("p0hand=Lightning Bolt", "p0hand=Definitely Not A Real Card", "p0hand");
    }

    @Test(description = "A dump whose life total was edited is refused")
    public void aDumpOfAnAlreadyDecidedPositionIsRefused() {
        // Below zero the restore leaves a player standing who has already lost, and the
        // comparison against the file cannot see it — the file agrees with itself. The
        // playable check is what catches it, and it says so in words.
        assertRefused("p1life=13", "p1life=-3", "already decided");
        // At exactly zero the player is removed while the board is still being restored, so
        // the restore never finishes. A different route to the same refusal.
        assertRefused("p1life=13", "p1life=0", null);
    }

    /**
     * Damage the dump of {@link #BOARD} by replacing one line, and insist the replay refuses
     * it. When {@code expectedReason} is given, the refusal also has to say that much about
     * what went wrong.
     */
    private void assertRefused(final String original, final String replacement, final String expectedReason) {
        final String dumped = dump(scriptedGame(BOARD));
        final String corrupted = dumped.replace(original, replacement);
        Assert.assertNotEquals(corrupted, dumped,
                "The test did not manage to damage the dump: it has no line '" + original + "'");

        final List<String> refusals = AttributeCommand.refusals(
                AttributeCommand.replay(corrupted, "Default", 60, 1L, true));

        Assert.assertFalse(refusals.isEmpty(),
                "A dump damaged with '" + replacement + "' was accepted as faithful");
        if (expectedReason != null) {
            Assert.assertTrue(String.join("\n", refusals).contains(expectedReason),
                    "The refusal does not mention " + expectedReason + ": " + refusals);
        }
    }

    @Test(description = "The same seed replays the same game", timeOut = 300000)
    public void theSameSeedReplaysTheSameGame() {
        final String dumped = dump(scriptedGame(raceDecidedByTheShuffle()));

        final AttributeCommand.Replay first = AttributeCommand.replay(dumped, "Default", 120, 4242L, false);
        final AttributeCommand.Replay second = AttributeCommand.replay(dumped, "Default", 120, 4242L, false);

        Assert.assertEquals(AttributeCommand.refusals(first), new ArrayList<String>(),
                "The first replay did not restore and play the board");
        Assert.assertEquals(AttributeCommand.refusals(second), new ArrayList<String>(),
                "The second replay did not restore and play the board");

        final AttributionResult.Rollout a = first.row;
        final AttributionResult.Rollout b = second.row;
        // A pair of draws would compare equal without proving the seed reached anything, so
        // insist the replayed board actually resolves into a win.
        Assert.assertFalse(a.timedOut, "The replay hit the clock, so there is no game to compare");
        Assert.assertFalse(a.isDraw, "The replayed board did not resolve into a win, so the comparison is empty");
        Assert.assertEquals(b.winnerIndex, a.winnerIndex, "The same seed produced a different winner");
        Assert.assertEquals(b.isDraw, a.isDraw, "The same seed produced a different result");
        Assert.assertEquals(b.turns, a.turns, "The same seed produced a game of a different length");
        // The board takes about a dozen turns of drawing to resolve. A replay that ended
        // almost immediately would have agreed with itself without playing anything.
        Assert.assertTrue(a.turns >= 9,
                "The replay ended after " + a.turns + " turns, too early to have been decided by the draws");
    }

    @Test(description = "Different seeds replay different games", timeOut = 300000)
    public void differentSeedsPlayDifferentGames() {
        final String dumped = dump(scriptedGame(raceDecidedByTheShuffle()));

        final Set<Integer> lengths = new LinkedHashSet<>();
        for (long seed = 100L; seed < 106L; seed++) {
            final AttributeCommand.Replay replay = AttributeCommand.replay(dumped, "Default", 120, seed, false);
            Assert.assertEquals(AttributeCommand.refusals(replay), new ArrayList<String>(),
                    "A replay at seed " + seed + " did not restore and play the board");
            lengths.add(replay.row.turns);
        }

        // On this board the draws are the only thing that varies, so six seeds landing on one
        // length means the libraries came back in recorded order and stayed there: every
        // rollout would replay one fixed future instead of sampling the position, and a whole
        // campaign of them would report a win rate of exactly 0% or exactly 100%.
        Assert.assertTrue(lengths.size() > 1,
                "Six seeds all produced a " + lengths + "-turn game, so the libraries were not re-shuffled");
    }

    /**
     * A board whose length depends on the shuffle, which is what makes the determinism check
     * mean anything: p0 has to draw five burn spells out of a mixed library to finish p1 off,
     * so the game runs anywhere from about fifteen to about twenty-three turns depending on
     * the order they come in. A board that resolves the same way whatever the shuffle would
     * agree with itself even with the seed thrown away.
     *
     * <p>Both libraries carry filler on purpose. A restored game never shuffles cards in from
     * anywhere else, so a side that runs out of them ends the rollout by decking rather than
     * by playing.
     */
    private static String[] raceDecidedByTheShuffle() {
        return new String[] {
                "turn=3",
                "activeplayer=p0",
                "activephase=MAIN1",
                "p0life=20",
                "p0battlefield=Mountain;Mountain;Mountain;Mountain",
                "p0hand=Lightning Bolt",
                "p0library=" + repeat("Lightning Bolt;Mountain", 6),
                "p1life=13",
                "p1battlefield=Forest;Forest",
                "p1hand=",
                "p1library=" + repeat("Forest", 12),
        };
    }

    private static String repeat(final String entry, final int times) {
        final List<String> entries = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            entries.add(entry);
        }
        return String.join(";", entries);
    }

    private String dump(final Game game) {
        final GameState state = newGameState();
        state.initFromGame(game);
        return state.toString();
    }
}
