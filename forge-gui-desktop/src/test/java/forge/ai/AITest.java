package forge.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.Lists;

import forge.GuiDesktop;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.ThreadUtil;

public class AITest {
    private static boolean initialized = false;

    public Game resetGame() {
        return resetGame(new LobbyPlayerAi("p1", null));
    }

    /**
     * The same empty two-player game, with a caller-supplied AI seat as p1 (player index 1).
     * Tests that need a non-standard controller on the seat under test build it here rather
     * than swapping the controller afterwards, so the game is never in a state Forge itself
     * could not have produced.
     */
    public Game resetGame(LobbyPlayerAi aiSeat) {
        // need to be done after FModel.initialize, or the Localizer isn't loaded yet
        List<RegisteredPlayer> players = Lists.newArrayList();
        Deck d1 = new Deck();
        players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi("p2", null)));
        players.add(new RegisteredPlayer(d1).setPlayer(aiSeat));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "Test");
        Game game = new Game(players, rules, match);
        Player p = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getPhaseHandler().onStackResolved();
        // game.getAction().checkStateEffects(true);

        return game;
    }

    protected Game initAndCreateGame() {
        initModel();
        // Deliberately the no-argument resetGame: subclasses override it to build a
        // different game (SimulationTest, for one), and going through the overload
        // instead would silently throw their setup away.
        return resetGame();
    }

    protected Game initAndCreateGame(LobbyPlayerAi aiSeat) {
        initModel();
        return resetGame(aiSeat);
    }

    private static void initModel() {
        if (!initialized) {
            GuiBase.setInterface(new GuiDesktop());
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            initialized = true;
        }
    }

    /**
     * A {@link GameState} that resolves card names against the loaded card database, falling
     * back to any printing when the exact set and art are not available.
     */
    protected GameState newGameState() {
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

    /** Build a game and apply a board written in {@link GameState} text to it. */
    protected Game scriptedGame(final String... lines) {
        return applyScriptedState(initAndCreateGame(), lines);
    }

    /**
     * Build a game with the given AI seat as p1 and apply a board written in
     * {@link GameState} text to it.
     */
    protected Game scriptedGame(final LobbyPlayerAi aiSeat, final String... lines) {
        return applyScriptedState(initAndCreateGame(aiSeat), lines);
    }

    /**
     * Apply a board written in {@link GameState} text to an already built game.
     *
     * <p>The state has to be applied on a Forge game thread; the latch is what makes the
     * calling test see the finished position rather than a half-applied one.
     */
    private Game applyScriptedState(final Game game, final String... lines) {
        final GameState state = newGameState();
        state.parse(Arrays.asList(lines));
        final CountDownLatch applied = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        ThreadUtil.invokeInGameThread(() -> {
            try {
                // applyToGame runs inline here because this is already a Forge game
                // thread, so the latch observes the fully restored position.
                state.applyToGame(game);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out applying scripted game state");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while applying scripted game state", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("Could not apply scripted game state", failure.get());
        }
        return game;
    }

    protected int countCardsWithName(Game game, String name) {
        int i = 0;
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                i++;
            }
        }
        return i;
    }

    protected Card findCardWithName(Game game, String name) {
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }


    protected SpellAbility findSAWithPrefix(Card c, String prefix) {
        return findSAWithPrefix(c.getSpellAbilities(), prefix);
    }

    protected SpellAbility findSAWithPrefix(Iterable<SpellAbility> abilities, String prefix) {
        for (SpellAbility sa : abilities) {
            if (sa.getDescription().startsWith(prefix)) {
                return sa;
            }
        }
        return null;
    }

    protected Card createCard(String name, Player p) {
        IPaperCard paperCard = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paperCard == null) {
            StaticData.instance().attemptToLoadCard(name);
            paperCard = FModel.getMagicDb().getCommonCards().getCard(name);
        }
        return Card.fromPaperCard(paperCard, p);
    }

    protected Card addCardToZone(String name, Player p, ZoneType zone) {
        Card c = createCard(name, p);
        // card need a new Timestamp otherwise Static Abilities might collide
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(zone).add(c);
        return c;
    }

    protected Card addCard(String name, Player p) {
        return addCardToZone(name, p, ZoneType.Battlefield);
    }

    protected List<Card> addCards(String name, int count, Player p) {
        List<Card> cards = Lists.newArrayList();
        for (int i = 0; i < count; i++) {
            cards.add(addCard(name, p));
        }
        return cards;
    }

    protected Card createToken(String name, Player p) {
        PaperToken token = FModel.getMagicDb().getAllTokens().getToken(name);
        if (token == null) {
            System.out.println("Failed to find token name " + name);
            return null;
        }
        return CardFactory.getCard(token, p, p.getGame());
    }

    protected List<Card> addTokens(String name, int amount, Player p) {
        List<Card> cards = new ArrayList<>();

        for(int i = 0; i < amount; i++) {
            cards.add(addToken(name, p));
        }

        return cards;
    }

    protected Card addToken(String name, Player p) {
        Card c = createToken(name, p);
        // card need a new Timestamp otherwise Static Abilities might collide
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Battlefield).add(c);
        return c;
    }

    protected void playUntilStackClear(Game game) {
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (!game.isGameOver() && !game.getStack().isEmpty());
    }

    protected void playUntilPhase(Game game, PhaseType phase) {
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (!game.isGameOver() && !game.getPhaseHandler().is(phase));
    }

    protected void playUntilNextTurn(Game game) {
        Player current = game.getPhaseHandler().getPlayerTurn();
        do {
            game.getPhaseHandler().mainLoopStep();
        } while (!game.isGameOver() && game.getPhaseHandler().getPlayerTurn().equals(current));
    }

    protected String gameStateToString(Game game) {
        StringBuilder sb = new StringBuilder();
        for (ZoneType zone : ZoneType.values()) {
            CardCollectionView cards = game.getCardsIn(zone);
            if (!cards.isEmpty()) {
                sb.append("Zone ").append(zone.name()).append(":\n");
                for (Card c : game.getCardsIn(zone)) {
                    sb.append("  ").append(c);
                    if (c.isTapped()) {
                        sb.append(" (T)");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }
}
