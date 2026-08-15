package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests how much mana, and of which colours, an LLM seat is told it has.
 *
 * <p>The prompt's "Available mana" line is read from each untapped permanent's
 * mana abilities. Getting it wrong is worse than saying nothing: the model
 * plans a turn around mana that is not there, or holds a spell it could have
 * cast. Two readings were wrong before 2026-08-15 and both are pinned here.
 *
 * <ul>
 *   <li>Only the first mana ability of a source was counted, so a land with one
 *       ability per colour looked like it made only the first colour.</li>
 *   <li>A dual land's {@code "Combo W U"} — one mana, white or blue — was read
 *       as the plain list "W U" and counted as two mana, one of each. That
 *       overstates the total, which is the number the model uses to decide what
 *       it can cast.</li>
 * </ul>
 *
 * <p>The parsing is separated from the game objects on purpose, so what a card
 * script says can be checked without building a board.
 */
public class LLMManaSerializationTest {

    private static GameStateSerializer.ManaOption read(String produced) {
        return GameStateSerializer.ManaOption.read(produced, 1);
    }

    private static GameStateSerializer.ManaOption merge(GameStateSerializer.ManaOption... options) {
        List<GameStateSerializer.ManaOption> list = new ArrayList<>(Arrays.asList(options));
        return GameStateSerializer.ManaOption.merge(list);
    }

    // ---------------------------------------------------------------- one ability

    @Test
    public void basicLandAddsOneManaOfItsColour() {
        GameStateSerializer.ManaOption mountain = read("R");
        Assert.assertNotNull(mountain);
        Assert.assertEquals(mountain.colors, "R");
        Assert.assertEquals(mountain.amount, 1);
        Assert.assertFalse(mountain.choice);
    }

    @Test
    public void dualLandAddsOneManaOfEitherColour() {
        // Azorius Guildgate: "A:AB$ Mana | Cost$ T | Produced$ Combo W U"
        GameStateSerializer.ManaOption gate = read("Combo W U");
        Assert.assertNotNull(gate);
        Assert.assertTrue(gate.choice, "a Combo ability offers a choice of colour");
        Assert.assertEquals(gate.colors, "WU");
        Assert.assertEquals(gate.amount, 1, "a dual land taps once and adds one mana");
    }

    @Test
    public void landThatAddsTwoDifferentColoursAddsBoth() {
        // Simic Growth Chamber: "Produced$ G U" — not a choice, both are added.
        GameStateSerializer.ManaOption chamber = read("G U");
        Assert.assertNotNull(chamber);
        Assert.assertFalse(chamber.choice);
        Assert.assertEquals(chamber.colors, "GU");
        Assert.assertEquals(chamber.amount, 2);
    }

    @Test
    public void anyColourSourceIsOneManaOfAnyColour() {
        // Birds of Paradise: "Produced$ Any"
        GameStateSerializer.ManaOption birds = read("Any");
        Assert.assertNotNull(birds);
        Assert.assertEquals(birds.colors, "A");
        Assert.assertEquals(birds.amount, 1);
    }

    @Test
    public void amountRepeatsWhatTheAbilityProduces() {
        // Sol Ring: "Produced$ C | Amount$ 2"
        GameStateSerializer.ManaOption solRing = GameStateSerializer.ManaOption.read("C", 2);
        Assert.assertNotNull(solRing);
        Assert.assertEquals(solRing.colors, "CC");
        Assert.assertEquals(solRing.amount, 2);
        Assert.assertFalse(solRing.choice);
    }

    @Test
    public void genericProducedCountsAsColourless() {
        GameStateSerializer.ManaOption two = read("2");
        Assert.assertNotNull(two);
        Assert.assertEquals(two.colors, "CC");
        Assert.assertEquals(two.amount, 2);
    }

    @Test
    public void aChoiceOfEveryColourIsJustAnyColour() {
        Assert.assertEquals(read("Combo W U B R G").colors, "A");
        Assert.assertEquals(read("Combo Any").colors, "A");
        Assert.assertFalse(read("Combo Any").choice,
                "one alternative is not a choice");
    }

    @Test
    public void colourDecidedLaterIsCountedRatherThanDropped() {
        // "Chosen", "ColorID", "Special ..." name a real mana whose colour is
        // not known until the ability resolves. Dropping them silently made the
        // seat think it had less mana than it did.
        GameStateSerializer.ManaOption chosen = read("Chosen");
        Assert.assertNotNull(chosen);
        Assert.assertEquals(chosen.amount, 1);
        Assert.assertEquals(chosen.colors, "?");
    }

    @Test
    public void anAbilityNamingNoManaIsIgnored() {
        Assert.assertNull(read(""));
        Assert.assertNull(read("   "));
        Assert.assertNull(read(null));
    }

    // ------------------------------------------------------------ several abilities

    @Test
    public void everyAbilityOfASourceIsRead() {
        // A land written as one ability per colour taps once, so its colours are
        // alternatives. Reading only the first ability reported it as a source
        // of white alone.
        GameStateSerializer.ManaOption land = merge(read("W"), read("U"));
        Assert.assertNotNull(land);
        Assert.assertTrue(land.choice);
        Assert.assertEquals(land.colors, "WU");
        Assert.assertEquals(land.amount, 1);
    }

    @Test
    public void repeatedIdenticalAbilitiesAreStillOneMana() {
        GameStateSerializer.ManaOption land = merge(read("R"), read("R"));
        Assert.assertNotNull(land);
        Assert.assertFalse(land.choice);
        Assert.assertEquals(land.colors, "R");
        Assert.assertEquals(land.amount, 1);
    }

    @Test
    public void abilitiesOfDifferentSizesReportTheLargest() {
        GameStateSerializer.ManaOption land = merge(read("C"), read("G U"));
        Assert.assertNotNull(land);
        Assert.assertEquals(land.amount, 2);
        Assert.assertTrue(land.choice);
    }

    @Test
    public void aSourceWithNoManaAbilitiesContributesNothing() {
        Assert.assertNull(GameStateSerializer.ManaOption.merge(null));
        Assert.assertNull(GameStateSerializer.ManaOption.merge(
                new ArrayList<GameStateSerializer.ManaOption>()));
    }

    // ------------------------------------------------------------------- printing

    @Test
    public void choicesArePrintedAsChoices() {
        Assert.assertEquals(GameStateSerializer.choiceLabel("WU"), "{W/U}");
        Assert.assertEquals(GameStateSerializer.choiceLabel("BRG"), "{B/R/G}");
        Assert.assertEquals(GameStateSerializer.fixedLabel('R'), "R");
        Assert.assertEquals(GameStateSerializer.fixedLabel('A'), "Any");
        Assert.assertEquals(GameStateSerializer.fixedLabel('?'), "?");
    }
}
