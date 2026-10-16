package cc.deckamaster.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns Forge's GameView into a JSON snapshot for one viewer.
 * Hidden information (opponent's hand, libraries, face-down cards) is never sent.
 */
final class StateSerializer {
    private static final byte[] MANA_COLORS = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN, MagicColor.COLORLESS};
    private static final String[] MANA_KEYS = {"W", "U", "B", "R", "G", "C"};

    private final Predicate<CardView> mayView;
    private final Map<Integer, CardView> cardIndex;
    private final Map<Integer, PlayerView> playerIndex;

    StateSerializer(Predicate<CardView> mayView, Map<Integer, CardView> cardIndex, Map<Integer, PlayerView> playerIndex) {
        this.mayView = mayView;
        this.cardIndex = cardIndex;
        this.playerIndex = playerIndex;
    }

    /** Forge mutates views on the game thread; retry a few times on concurrent modification. */
    JsonObject snapshot(GameView game, java.util.Set<PlayerView> me) {
        for (int attempt = 0; ; attempt++) {
            try {
                return build(game, me);
            } catch (ConcurrentModificationException | NullPointerException | IndexOutOfBoundsException e) {
                if (attempt >= 4) throw e;
                try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private JsonObject build(GameView game, java.util.Set<PlayerView> me) {
        JsonObject s = new JsonObject();
        s.addProperty("turn", game.getTurn());
        s.addProperty("phase", game.getPhase() == null ? "" : game.getPhase().name());
        PlayerView active = game.getPlayerTurn();
        s.addProperty("activePlayer", active == null ? -1 : active.getId());
        s.addProperty("gameOver", game.isGameOver());

        JsonArray players = new JsonArray();
        for (PlayerView p : copy(game.getPlayers())) {
            playerIndex.put(p.getId(), p);
            players.add(player(p, me.contains(p)));
        }
        s.add("players", players);

        JsonArray stack = new JsonArray();
        for (StackItemView si : copy(game.getStack())) {
            JsonObject o = new JsonObject();
            o.addProperty("id", si.getId());
            o.addProperty("text", si.getText());
            o.addProperty("controller", si.getActivatingPlayer() == null ? -1 : si.getActivatingPlayer().getId());
            if (si.getSourceCard() != null) o.add("source", card(si.getSourceCard()));
            stack.add(o);
        }
        s.add("stack", stack);

        JsonArray combat = new JsonArray();
        CombatView cv = game.getCombat();
        if (cv != null) {
            for (CardView att : copy(cv.getAttackers())) {
                JsonObject o = new JsonObject();
                o.addProperty("attacker", att.getId());
                GameEntityView def = cv.getDefender(att);
                if (def != null) {
                    o.addProperty("defender", def.getId());
                    o.addProperty("defenderIsPlayer", def instanceof PlayerView);
                }
                JsonArray bl = new JsonArray();
                var blockers = cv.getBlockers(att);
                if (blockers != null) for (CardView b : copy(blockers)) bl.add(b.getId());
                o.add("blockers", bl);
                combat.add(o);
            }
        }
        s.add("combat", combat);

        JsonArray log = new JsonArray();
        if (game.getGameLog() != null) {
            List<GameLogEntry> entries = copy(game.getGameLog().getLogEntries(null));
            // Forge keeps newest first; send the latest 40.
            for (int i = 0; i < Math.min(40, entries.size()); i++) log.add(entries.get(i).message());
        }
        s.add("log", log);
        return s;
    }

    private JsonObject player(PlayerView p, boolean isMe) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.getId());
        o.addProperty("name", p.getName());
        o.addProperty("me", isMe);
        o.addProperty("ai", p.isAI());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getCounters(forge.game.card.CounterEnumType.POISON));
        o.addProperty("priority", p.getHasPriority());
        o.addProperty("lost", p.getHasLost());
        o.addProperty("landsPlayed", p.getNumLandThisTurn());

        JsonObject mana = new JsonObject();
        for (int i = 0; i < MANA_COLORS.length; i++) {
            int n = p.getMana(MANA_COLORS[i]);
            if (n > 0) mana.addProperty(MANA_KEYS[i], n);
        }
        o.add("mana", mana);

        o.addProperty("libraryCount", p.getZoneSize(ZoneType.Library));
        o.addProperty("handCount", p.getZoneSize(ZoneType.Hand));
        o.add("hand", cards(p.getCards(ZoneType.Hand), true));
        o.add("battlefield", cards(p.getCards(ZoneType.Battlefield), false));
        o.add("graveyard", cards(p.getCards(ZoneType.Graveyard), false));
        o.add("exile", cards(p.getCards(ZoneType.Exile), false));
        o.add("command", cards(p.getCards(ZoneType.Command), false));
        return o;
    }

    /** In hidden zones, cards the viewer may not see are dropped (count is sent separately). */
    private JsonArray cards(Iterable<CardView> zone, boolean hiddenZone) {
        JsonArray a = new JsonArray();
        if (zone == null) return a;
        for (CardView c : copy(zone)) {
            if (hiddenZone && !mayView.test(c)) continue;
            a.add(card(c));
        }
        return a;
    }

    JsonObject card(CardView c) {
        cardIndex.put(c.getId(), c);
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        boolean visible = mayView.test(c);
        CardStateView st = c.getCurrentState();
        if (!visible || st == null) {
            o.addProperty("name", "Face-down card");
            o.addProperty("hidden", true);
        } else {
            o.addProperty("name", st.getName());
            o.addProperty("set", st.getSetCode());
            o.addProperty("cost", st.getManaCost() == null ? "" : st.getManaCost().getShortString());
            o.addProperty("type", st.getType() == null ? "" : st.getType().toString());
            o.addProperty("text", c.getText());
            if (st.isCreature()) {
                o.addProperty("power", st.getPower());
                o.addProperty("toughness", st.getToughness());
            }
            if (st.isPlaneswalker()) o.addProperty("loyalty", st.getLoyalty());
            o.addProperty("land", st.isLand());
            o.addProperty("creature", st.isCreature());
        }
        o.addProperty("tapped", c.isTapped());
        o.addProperty("sick", c.isSick());
        o.addProperty("token", c.isToken());
        o.addProperty("damage", c.getDamage());
        o.addProperty("attacking", c.isAttacking());
        o.addProperty("blocking", c.isBlocking());
        if (c.getController() != null) o.addProperty("controller", c.getController().getId());
        if (c.getOwner() != null) o.addProperty("owner", c.getOwner().getId());
        if (c.getAttachedTo() != null) o.addProperty("attachedTo", c.getAttachedTo().getId());
        var counters = c.getCounters();
        if (counters != null && !counters.isEmpty()) {
            JsonObject co = new JsonObject();
            for (CounterType t : counters.elementSet()) co.addProperty(t.getName(), counters.count(t));
            o.add("counters", co);
        }
        return o;
    }

    private static <T> List<T> copy(Iterable<T> it) {
        List<T> out = new ArrayList<>();
        if (it != null) for (T t : it) out.add(t);
        return out;
    }
}
