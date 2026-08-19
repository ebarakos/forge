package forge.ai.llm;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Records a difference between the two AI seats that nobody chose: a card
 * script carrying {@code AI:RemoveDeck:All} is invisible to the heuristic seat
 * and perfectly castable by the LLM seat.
 *
 * <p>The heuristic chooser drops every such card in
 * {@code AiController.getSpellAbilityToPlay}, so those cards sit in hand for
 * the whole game. The LLM seat builds its own option list in
 * {@link LLMSpellSelection#collectPlayableOptions()} and applies a different set
 * of filters — mana abilities, land plays, timing, cost, targeting — none of
 * which is that one. So on any deck containing such a card the two seats are
 * not playing the same sixty cards, and a heuristic-versus-model comparison on
 * that deck is not a like-for-like comparison.
 *
 * <p><b>This test endorses nothing.</b> It pins a known inequality so that a
 * later change to either seat's filtering is noticed rather than discovered
 * again from game logs. Holding the heuristic filter as it is was a deliberate
 * decision; if the seats are ever brought into line, this test is the one to
 * rewrite, and the rewrite is the record that the gap was closed on purpose.
 *
 * <p>No model call happens anywhere in here. The option lists are read
 * directly, and the one end-to-end decision uses the shipped single-option
 * shortcut, which answers from the heuristic's verdict without contacting a
 * provider.
 */
public class RemovedFromAiDecksSeatGapTest extends AITest {

    /**
     * A blocked card that makes mana. The real-world case: four copies sit in
     * the {@code elves} list and none has ever been cast by a heuristic seat.
     * Its own mana ability is filtered by both seats, which is why the card had
     * to be one whose <em>spell</em> is the thing being blocked.
     */
    private static final String BLOCKED_MANA_PRODUCER = "Birchlore Rangers";

    /**
     * A blocked card that makes no mana, so nothing below can be explained away
     * as special handling of mana abilities.
     */
    private static final String BLOCKED_NON_MANA = "Krark-Clan Shaman";

    /** An ordinary card with no AI hints at all. */
    private static final String CONTROL = "Grizzly Bears";

    /** Both blocked cards and the control in hand, with mana for any of them. */
    private static final String[] BOARD_WITH_ALL_THREE = {
            "turn=3",
            "activeplayer=p1",
            "activephase=MAIN2",
            "p0life=20",
            "p0battlefield=",
            "p1life=20",
            "p1battlefield=Forest;Forest;Mountain",
            "p1hand=" + BLOCKED_MANA_PRODUCER + ";" + BLOCKED_NON_MANA + ";" + CONTROL,
    };

    // ---------------------------------------------------------------- the flag

    @Test
    public void theTwoCardsUnderTestReallyCarryTheHint() {
        // If a future card script edit drops the hint, every assertion below
        // would still pass while testing nothing. Check the premise first.
        final Game game = scriptedGame(BOARD_WITH_ALL_THREE);
        final Player ai = game.getPlayers().get(1);
        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(handCard(ai, BLOCKED_MANA_PRODUCER)),
                BLOCKED_MANA_PRODUCER + " is expected to carry AI:RemoveDeck:All");
        Assert.assertTrue(ComputerUtilCard.isCardRemAIDeck(handCard(ai, BLOCKED_NON_MANA)),
                BLOCKED_NON_MANA + " is expected to carry AI:RemoveDeck:All");
        Assert.assertFalse(ComputerUtilCard.isCardRemAIDeck(handCard(ai, CONTROL)),
                CONTROL + " is the control and must carry no such hint");
    }

    // ------------------------------------------------- the shared starting list

    @Test
    public void bothSeatsStartFromTheSameUnfilteredList() {
        // The difference is made by what each seat removes, not by where each
        // seat looks. Both call getAvailableCards then getSpellAbilities, and
        // that list holds all three cards.
        final Game game = scriptedGame(BOARD_WITH_ALL_THREE);
        final Player ai = game.getPlayers().get(1);
        final List<String> shared = hostNames(ComputerUtilAbility.getSpellAbilities(
                ComputerUtilAbility.getAvailableCards(game, ai), ai));

        Assert.assertTrue(shared.contains(BLOCKED_MANA_PRODUCER), shared.toString());
        Assert.assertTrue(shared.contains(BLOCKED_NON_MANA), shared.toString());
        Assert.assertTrue(shared.contains(CONTROL), shared.toString());
    }

    // ------------------------------------------------------- the heuristic seat

    @Test
    public void theHeuristicSeatPlaysTheControlAndIgnoresTheBlockedCards() {
        final Game game = scriptedGame(BOARD_WITH_ALL_THREE);
        final Player ai = game.getPlayers().get(1);
        final List<SpellAbility> choice = ((PlayerControllerAi) ai.getController())
                .getAi().chooseSpellAbilityToPlay();

        Assert.assertNotNull(choice, "the heuristic seat should still cast the ordinary creature");
        Assert.assertEquals(choice.get(0).getHostCard().getName(), CONTROL);
    }

    @Test
    public void theHeuristicSeatPassesWhenOnlyBlockedCardsAreCastable() {
        // Same mana, same phase, control removed. Both remaining cards are
        // affordable creature spells the heuristic would happily cast if it
        // could see them, and it casts neither.
        final Game game = scriptedGame(
                "turn=3",
                "activeplayer=p1",
                "activephase=MAIN2",
                "p0life=20",
                "p0battlefield=",
                "p1life=20",
                "p1battlefield=Forest;Forest;Mountain",
                "p1hand=" + BLOCKED_MANA_PRODUCER + ";" + BLOCKED_NON_MANA);
        final Player ai = game.getPlayers().get(1);
        final List<SpellAbility> choice = ((PlayerControllerAi) ai.getController())
                .getAi().chooseSpellAbilityToPlay();

        Assert.assertNull(choice, "the heuristic seat has nothing it can choose here, but it chose "
                + (choice == null || choice.isEmpty() ? "" : choice.get(0).getHostCard().getName()));
    }

    // ------------------------------------------------------------- the LLM seat

    @Test
    public void theLlmSeatsOwnOptionListStillHoldsTheBlockedCards() {
        final Game game = scriptedGame(llmSeat(), BOARD_WITH_ALL_THREE);
        final LLMSpellSelection selection = llmController(game).spellSelection();
        final List<String> options = hostNames(selection.collectPlayableOptions());

        Assert.assertTrue(options.contains(BLOCKED_MANA_PRODUCER),
                "the LLM seat's option list should still hold " + BLOCKED_MANA_PRODUCER
                        + "; it held " + options);
        Assert.assertTrue(options.contains(BLOCKED_NON_MANA),
                "the LLM seat's option list should still hold " + BLOCKED_NON_MANA
                        + "; it held " + options);
        Assert.assertTrue(options.contains(CONTROL), options.toString());
    }

    @Test
    public void theHeuristicPriorLeavesTheBlockedCardsInFrontOfTheModel() {
        // collectPlayableOptions is not the last word: applyHeuristicPrior asks
        // the heuristic AI for a verdict on each option and (by default) drops
        // the ones it calls hopeless. That is the seat's last chance to remove
        // these cards, and it does not take it — the heuristic's canPlaySa has
        // no idea the cards are blocked, because the block lives in the chooser
        // and not in the verdict.
        final Game game = scriptedGame(llmSeat(), BOARD_WITH_ALL_THREE);
        final LLMSpellSelection selection = llmController(game).spellSelection();
        final List<String> shown =
                hostNames(selection.applyHeuristicPrior(selection.collectPlayableOptions(), null));

        Assert.assertTrue(shown.contains(BLOCKED_MANA_PRODUCER),
                "the model is shown " + BLOCKED_MANA_PRODUCER + "; it was shown " + shown);
        Assert.assertTrue(shown.contains(BLOCKED_NON_MANA),
                "the model is shown " + BLOCKED_NON_MANA + "; it was shown " + shown);
    }

    // ------------------------------------------- the difference, end to end

    @Test
    public void theLlmSeatCastsABlockedManaProducerTheHeuristicSeatWillNot() {
        assertSeatsDisagreeOnLoneCard(BLOCKED_MANA_PRODUCER, "Forest");
    }

    @Test
    public void theLlmSeatCastsABlockedNonManaCardTheHeuristicSeatWillNot() {
        assertSeatsDisagreeOnLoneCard(BLOCKED_NON_MANA, "Mountain");
    }

    /**
     * One blocked card in hand, exactly enough mana for it, nothing else to do.
     * Both seats are asked the same question on the same position; the
     * heuristic seat passes and the LLM seat casts the card.
     *
     * <p>The LLM seat reaches its answer without a provider because one option
     * triggers the single-option shortcut, which takes the heuristic's verdict
     * on that option. The verdict is {@code WillPlay} — the heuristic has no
     * objection to the card itself, it simply never sees it in its own chooser.
     */
    private void assertSeatsDisagreeOnLoneCard(final String blockedCard, final String land) {
        if (!LLMFullController.SINGLE_OPTION_SHORTCUT) {
            throw new SkipException("FORGE_LLM_SINGLE_OPTION_SHORTCUT is off, so the LLM seat "
                    + "would have to call a provider to answer; the option-list tests above "
                    + "cover the same gap without one.");
        }
        final String[] board = {
                "turn=3",
                "activeplayer=p1",
                "activephase=MAIN2",
                "p0life=20",
                "p0battlefield=",
                "p1life=20",
                "p1battlefield=" + land,
                "p1hand=" + blockedCard,
        };

        final Game heuristicGame = scriptedGame(board);
        final List<SpellAbility> heuristicChoice =
                ((PlayerControllerAi) heuristicGame.getPlayers().get(1).getController())
                        .getAi().chooseSpellAbilityToPlay();
        Assert.assertNull(heuristicChoice, "the heuristic seat cannot choose " + blockedCard
                + ", so it has nothing to do on this board");

        final Game llmGame = scriptedGame(llmSeat(), board);
        final LLMFullController llm = llmController(llmGame);
        final List<SpellAbility> llmChoice = llm.chooseSpellAbilityToPlay();

        Assert.assertNotNull(llmChoice, "the LLM seat passed on " + blockedCard);
        Assert.assertFalse(llmChoice.isEmpty(), "the LLM seat passed on " + blockedCard);
        Assert.assertEquals(llmChoice.get(0).getHostCard().getName(), blockedCard,
                "the LLM seat is expected to cast " + blockedCard + ", which the heuristic seat cannot");
        Assert.assertEquals(llm.client.getTotalCalls(), 0,
                "this decision is supposed to be reached without contacting a provider");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * An LLM seat wired to a client that is never used. Everything asserted
     * here is decided before any request would be sent, so the client's address
     * (the builder's Ollama default) never matters.
     */
    private static LobbyPlayerLLM llmSeat() {
        return new LobbyPlayerLLM("p1", new LLMClient(new LLMConfig.Builder().build()));
    }

    private static LLMFullController llmController(final Game game) {
        return (LLMFullController) game.getPlayers().get(1).getController();
    }

    private static Card handCard(final Player p, final String name) {
        for (final Card c : p.getCardsIn(forge.game.zone.ZoneType.Hand)) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        throw new AssertionError(name + " was not in hand; the scripted board did not apply");
    }

    private static List<String> hostNames(final List<SpellAbility> abilities) {
        final List<String> names = new ArrayList<>();
        for (final SpellAbility sa : abilities) {
            final Card host = sa.getHostCard();
            names.add(host != null ? host.getName() : null);
        }
        return names;
    }
}
