package forge.ai;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Focused lower-level guards for decisions that need a fully settled test game. */
public class EngineDecisionRegressionTest extends AITest {

    @Test
    public void ownSpellGuardUsesCopyAbilityOrderAfterChooserSort() {
        // The spell chooser sorts higher-cost options first. The own-stack guard must
        // compare against that sorted order as well, or it rejects the chosen copy spell.
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        final Player opponent = ai.getSingleOpponent();
        addCards("Mountain", 4, ai);
        addCardToZone("Fork", ai, ZoneType.Hand);
        addCardToZone("Fury Storm", ai, ZoneType.Hand);
        final forge.game.card.Card spell = addCardToZone("Searing Wind", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);

        final SpellAbility spellAbility = spell.getFirstSpellAbility();
        spellAbility.setActivatingPlayer(ai);
        spellAbility.getTargets().add(opponent);
        game.getStack().freezeStack(spellAbility);
        spellAbility.setHostCard(game.getAction().moveToStack(spell, spellAbility));
        game.getStack().addAndUnfreeze(spellAbility);

        final SpellAbility firstCopyBeforeSort = ComputerUtilAbility.getFirstCopySASpell(
                ComputerUtilAbility.getSpellAbilities(ai.getCardsIn(ZoneType.Hand), ai));
        Assert.assertNotNull(firstCopyBeforeSort);
        Assert.assertEquals(firstCopyBeforeSort.getHostCard().getName(), "Fork");

        final List<SpellAbility> choice = ((PlayerControllerAi) ai.getController())
                .getAi().chooseSpellAbilityToPlay();

        Assert.assertNotNull(choice);
        Assert.assertEquals(choice.get(0).getHostCard().getName(), "Fury Storm");
        Assert.assertEquals(choice.get(0).getApi(), ApiType.CopySpellAbility);
    }
}
