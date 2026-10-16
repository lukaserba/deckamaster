package cc.deckamaster.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One browser connection: a lobby step (pick decks) followed by a game vs the AI. */
final class GameSession {
    private final Decks decks;
    private final Consumer<JsonObject> out;
    private volatile WebGuiGame gui;
    private volatile HostedMatch match;

    GameSession(Decks decks, Consumer<JsonObject> out) {
        this.decks = decks;
        this.out = out;
        JsonObject hello = new JsonObject();
        hello.addProperty("t", "hello");
        JsonArray names = new JsonArray();
        decks.names().forEach(names::add);
        hello.add("decks", names);
        out.accept(hello);
    }

    void onMessage(JsonObject m) {
        String t = m.has("t") ? m.get("t").getAsString() : "";
        if ("start".equals(t)) {
            start(m.get("deck").getAsString(), m.get("opponentDeck").getAsString(),
                    m.has("name") ? m.get("name").getAsString() : "You");
        } else if (gui != null) {
            gui.onClientMessage(m);
        }
    }

    private void start(String deckName, String aiDeckName, String playerName) {
        if (gui != null) gui.close();
        Deck mine = decks.load(deckName);
        Deck theirs = decks.load(aiDeckName);

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);

        RegisteredPlayer human = new RegisteredPlayer(mine);
        human.setPlayer(new LobbyPlayerHuman(playerName));
        RegisteredPlayer ai = new RegisteredPlayer(theirs);
        ai.setPlayer(GamePlayerUtil.createAiPlayer("Forge AI"));
        List<RegisteredPlayer> players = new ArrayList<>(List.of(human, ai));

        WebGuiGame g = new WebGuiGame(out);
        gui = g;
        HostedMatch hm = new HostedMatch();
        match = hm;
        // startMatch does setup work and then hands the game to Forge's game thread.
        Thread t = new Thread(() -> {
            try {
                hm.startMatch(rules, null, players, human, g);
            } catch (RuntimeException e) {
                e.printStackTrace();
                JsonObject err = new JsonObject();
                err.addProperty("t", "notice");
                err.addProperty("text", "Could not start the game: " + e.getMessage());
                out.accept(err);
            }
        }, "match-setup");
        t.setDaemon(true);
        t.start();
    }

    void close() {
        if (gui != null) gui.close();
    }
}
