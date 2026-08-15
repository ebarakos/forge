package forge.gamesimulationtests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.ai.GameState;
import forge.game.Game;
import forge.item.IPaperCard;
import forge.util.ThreadUtil;

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

    private static GameState newGameState() {
        return new GameState() {
            @Override
            public IPaperCard getPaperCard(final String cardName, final String setCode, final int artId) {
                if (setCode != null && !setCode.isEmpty()) {
                    final IPaperCard exact = StaticData.instance().getCommonCards()
                            .getCard(cardName, setCode, artId);
                    if (exact != null) {
                        return exact;
                    }
                }
                return StaticData.instance().getCommonCards().getCard(cardName);
            }
        };
    }

    /**
     * Build a game and apply a scripted board to it, the way {@link AiDecisionPuzzleTest}
     * does: the state has to be applied on a Forge game thread, and the latch is what makes
     * the caller see the finished position.
     */
    private Game scriptedGame(final String... lines) {
        final Game game = initAndCreateGame();
        final GameState state = newGameState();
        state.parse(Arrays.asList(lines));
        final CountDownLatch applied = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        ThreadUtil.invokeInGameThread(() -> {
            try {
                state.applyToGame(game);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                applied.countDown();
            }
        });
        try {
            Assert.assertTrue(applied.await(10, TimeUnit.SECONDS), "Timed out applying scripted game state");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while applying scripted game state", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("Could not apply scripted game state", failure.get());
        }
        return game;
    }
}
