package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Who decides whether an LLM seat plays or draws.
 *
 * <p>The recorded Burn run is the reason this rule exists. Burn was asked to
 * choose 15 times and took the draw in 10, and five of those answers explained
 * the choice by describing Burn as a deck that "answers threats". It is not: it
 * is the deck in the format that least wants to trade a turn for a card. The
 * prompt carried no deck identity, no opening hand and no board, so the model
 * filled the gap with an invented deck role. Game one is therefore decided
 * without asking, whatever the muzzle says.
 */
public class LLMPlayOrDrawTest {

    @Test
    public void gameOneIsDecidedWithoutTheModel() {
        // Unmuzzled, with a client available — still nobody is asked.
        Assert.assertTrue(
                LLMFullController.playsFirstWithoutAsking(true, false, true),
                "game one must play first without asking the model");
    }

    @Test
    public void laterGamesReachTheModelWhenTheMuzzleIsOff() {
        // Games two and three follow a game that was actually played, which is
        // real information the model can use.
        Assert.assertFalse(
                LLMFullController.playsFirstWithoutAsking(false, false, true),
                "a post-sideboard game must still be the model's choice when unmuzzled");
    }

    @Test
    public void theMuzzleStillCoversLaterGames() {
        Assert.assertTrue(
                LLMFullController.playsFirstWithoutAsking(false, true, true),
                "FORGE_LLM_HARDCODED_PLAY_FIRST must keep games two and three too");
    }

    @Test
    public void noClientMeansPlayFirst() {
        Assert.assertTrue(
                LLMFullController.playsFirstWithoutAsking(false, false, false),
                "with nothing to ask, the answer is the heuristic's: play first");
    }
}
