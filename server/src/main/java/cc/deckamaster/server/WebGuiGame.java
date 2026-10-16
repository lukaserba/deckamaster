package cc.deckamaster.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.AbstractGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerZoneUpdate;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Forge's view of one browser player.
 *
 * Two kinds of interaction:
 *  - Inputs (priority, attack, block, targets, mana payment): Forge shows a prompt + buttons and
 *    waits; the browser answers with card/player clicks and OK/Cancel, forwarded to the game controller.
 *  - Dialogs (choose from list, confirm, order): Forge calls a blocking method on the game thread;
 *    we send a "choose" request and block until the browser answers.
 */
public final class WebGuiGame extends AbstractGuiGame {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "state-push");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<JsonObject> out;
    private final Map<Integer, CardView> cardIndex = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerView> playerIndex = new ConcurrentHashMap<>();
    private final StateSerializer serializer = new StateSerializer(this::mayView, cardIndex, playerIndex);
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger requestIds = new AtomicInteger();
    private final AtomicBoolean pushScheduled = new AtomicBoolean();
    private volatile boolean closed;

    // Current input prompt, re-sent whenever it changes.
    private volatile String promptText = "";
    private volatile String okLabel = "OK", cancelLabel = "Cancel";
    private volatile boolean okEnabled, cancelEnabled;
    private volatile List<Integer> selectableCards = List.of();

    /** Phases where the player wants to stop and get priority (others auto-pass when the stack is empty). */
    private final Set<PhaseType> myStops = EnumSet.of(PhaseType.MAIN1, PhaseType.MAIN2);
    private final Set<PhaseType> opponentStops = EnumSet.of(PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.END_OF_TURN);

    public WebGuiGame(Consumer<JsonObject> out) {
        this.out = out;
    }

    // ---------------------------------------------------------------- outbound helpers

    private void send(String type, JsonObject body) {
        if (closed) return;
        body.addProperty("t", type);
        out.accept(body);
    }

    /** Coalesces the many fine-grained update callbacks into one state message. */
    private void schedulePush() {
        if (pushScheduled.compareAndSet(false, true)) {
            TIMER.schedule(() -> {
                pushScheduled.set(false);
                pushStateNow();
            }, 40, TimeUnit.MILLISECONDS);
        }
    }

    void pushStateNow() {
        GameView gv = getGameView();
        if (gv == null || closed) return;
        try {
            JsonObject msg = new JsonObject();
            msg.add("state", serializer.snapshot(gv, getLocalPlayers()));
            send("state", msg);
        } catch (RuntimeException e) {
            System.err.println("State serialization failed: " + e);
        }
    }

    private final AtomicBoolean promptScheduled = new AtomicBoolean();

    /**
     * Forge sets the prompt text, buttons and selectables in separate calls; coalesce them so the
     * browser always receives one consistent prompt together with the matching game state.
     */
    private void pushPrompt() {
        if (promptScheduled.compareAndSet(false, true)) {
            TIMER.schedule(() -> {
                promptScheduled.set(false);
                pushPromptNow();
            }, 15, TimeUnit.MILLISECONDS);
        }
    }

    private void pushPromptNow() {
        if (closed) return;
        JsonObject p = new JsonObject();
        GameView gv = getGameView();
        if (gv != null) {
            try {
                p.add("state", serializer.snapshot(gv, getLocalPlayers()));
            } catch (RuntimeException e) {
                System.err.println("State serialization failed: " + e);
            }
        }
        p.addProperty("text", promptText);
        p.addProperty("ok", okEnabled);
        p.addProperty("cancel", cancelEnabled);
        p.addProperty("okLabel", okLabel);
        p.addProperty("cancelLabel", cancelLabel);
        JsonArray sel = new JsonArray();
        for (Integer id : selectableCards) sel.add(id);
        p.add("selectable", sel);
        JsonArray hiCards = new JsonArray(), hiPlayers = new JsonArray();
        for (Integer k : highlighted) {
            if (k <= -1000) hiPlayers.add(-1000 - k); else hiCards.add(k);
        }
        p.add("picked", hiCards);
        p.add("pickedPlayers", hiPlayers);
        send("prompt", p);
    }

    /** Sends a choice request and blocks the calling (game) thread until the browser answers. */
    private JsonObject ask(JsonObject req) {
        if (closed) return null;
        pushStateNow();
        int id = requestIds.incrementAndGet();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pending.put(id, f);
        req.addProperty("id", id);
        send("choose", req);
        try {
            return f.get();
        } catch (Exception e) {
            return null;
        } finally {
            pending.remove(id);
        }
    }

    private <T> JsonArray items(List<T> choices, FSerializableFunction<T, String> display) {
        JsonArray a = new JsonArray();
        for (T c : choices) {
            JsonObject o = new JsonObject();
            String label;
            if (display != null) label = display.apply(c);
            else label = String.valueOf(c);
            if (c instanceof CardView cv) {
                o.add("card", serializer.card(cv));
                if (display == null) label = cv.getCurrentState() != null && mayView(cv) ? cv.getCurrentState().getName() : "Card";
            } else if (c instanceof PlayerView pv) {
                playerIndex.put(pv.getId(), pv);
                o.addProperty("player", pv.getId());
                if (display == null) label = pv.getName();
            }
            o.addProperty("label", label);
            a.add(o);
        }
        return a;
    }

    private static List<Integer> indices(JsonObject answer, int size) {
        List<Integer> out = new ArrayList<>();
        if (answer == null || !answer.has("indices")) return out;
        for (JsonElement e : answer.getAsJsonArray("indices")) {
            int i = e.getAsInt();
            if (i >= 0 && i < size && !out.contains(i)) out.add(i);
        }
        return out;
    }

    private <T> List<T> chooseFromList(String title, int min, int max, List<T> choices, FSerializableFunction<T, String> display, boolean ordered) {
        if (choices == null || choices.isEmpty()) return new ArrayList<>();
        JsonObject req = new JsonObject();
        if (max < 0) {
            // Forge uses min/max = -1 for "just show these" (e.g. reveal): view-only, wait for close.
            req.addProperty("title", title == null ? "" : title);
            req.addProperty("viewOnly", true);
            req.addProperty("min", 0);
            req.addProperty("max", 0);
            req.add("items", items(choices, display));
            ask(req);
            return new ArrayList<>();
        }
        min = Math.max(0, Math.min(min, choices.size()));
        max = Math.max(min, Math.min(max, choices.size()));
        req.addProperty("title", title == null ? "" : title);
        req.addProperty("min", min);
        req.addProperty("max", max);
        req.addProperty("ordered", ordered);
        req.add("items", items(choices, display));
        List<Integer> idx = indices(ask(req), choices.size());
        // Never hand Forge an answer outside [min, max]: fall back to the first valid picks.
        for (int i = 0; idx.size() < min && i < choices.size(); i++) if (!idx.contains(i)) idx.add(i);
        while (idx.size() > max) idx.remove(idx.size() - 1);
        List<T> result = new ArrayList<>();
        for (int i : idx) result.add(choices.get(i));
        return result;
    }

    private int chooseOption(String title, String message, List<String> options, int defaultOption) {
        JsonObject req = new JsonObject();
        req.addProperty("title", title == null ? "" : title);
        req.addProperty("message", message == null ? "" : message);
        req.addProperty("min", 1);
        req.addProperty("max", 1);
        req.addProperty("buttons", true);
        req.add("items", items(options, null));
        List<Integer> idx = indices(ask(req), options.size());
        return idx.isEmpty() ? defaultOption : idx.get(0);
    }

    // ---------------------------------------------------------------- inbound (from browser)

    /** Handles one message from the browser. Called on a network thread. */
    public void onClientMessage(JsonObject m) {
        String t = m.has("t") ? m.get("t").getAsString() : "";
        switch (t) {
            case "answer" -> {
                CompletableFuture<JsonObject> f = pending.get(m.get("id").getAsInt());
                if (f != null) f.complete(m);
            }
            case "card" -> {
                CardView cv = cardIndex.get(m.get("id").getAsInt());
                if (cv != null) onEdt(() -> getGameController().selectCard(cv, null, CLICK));
            }
            case "player" -> {
                PlayerView pv = playerIndex.get(m.get("id").getAsInt());
                if (pv != null) onEdt(() -> getGameController().selectPlayer(pv, CLICK));
            }
            case "ok" -> onEdt(() -> getGameController().selectButtonOk());
            case "cancel" -> onEdt(() -> getGameController().selectButtonCancel());
            case "concede" -> onEdt(() -> getGameController().concede());
            case "stops" -> {
                setStops(myStops, m.getAsJsonArray("mine"));
                setStops(opponentStops, m.getAsJsonArray("opponent"));
            }
            case "refresh" -> pushPromptNow();
            default -> System.err.println("Unknown client message: " + t);
        }
    }

    private static void setStops(Set<PhaseType> set, JsonArray arr) {
        if (arr == null) return;
        set.clear();
        for (JsonElement e : arr) {
            try { set.add(PhaseType.valueOf(e.getAsString())); } catch (IllegalArgumentException ignored) { }
        }
    }

    /** Forge's inputs expect clicks on the GUI thread, like the desktop client does. */
    private static void onEdt(Runnable r) {
        forge.gui.GuiBase.getInterface().invokeInEdtLater(() -> {
            try { r.run(); } catch (RuntimeException e) { e.printStackTrace(); }
        });
    }

    private static final ITriggerEvent CLICK = new ITriggerEvent() {
        @Override public int getButton() { return 1; }
        @Override public int getX() { return 0; }
        @Override public int getY() { return 0; }
    };

    /** The browser went away: release every blocked dialog and concede. */
    public void close() {
        closed = true;
        // Concede first, then release any dialog the game thread is blocked on. Doing both at once
        // lets the game thread run concurrently with the concede and trips Forge's trigger handler.
        onEdt(() -> {
            try {
                if (getGameView() != null && !getGameView().isGameOver() && getGameController() != null) {
                    getGameController().concede();
                }
            } finally {
                pending.values().forEach(f -> f.complete(null));
            }
        });
    }

    // ---------------------------------------------------------------- IGuiGame: view lifecycle

    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {
        if (myPlayers != null) for (PlayerView p : myPlayers) playerIndex.put(p.getId(), p);
        send("started", new JsonObject());
        pushStateNow();
    }

    @Override protected void updateCurrentPlayer(PlayerView player) { schedulePush(); }
    @Override public void showCombat() { schedulePush(); }
    @Override public void updatePhase(boolean saveState) { schedulePush(); }
    @Override public void updateTurn(PlayerView player) { schedulePush(); }
    @Override public void updatePlayerControl() { schedulePush(); }
    @Override public void updateStack() { schedulePush(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { schedulePush(); }
    @Override public void updateCards(Iterable<CardView> cards) { schedulePush(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { schedulePush(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { schedulePush(); }
    @Override public void refreshField() { schedulePush(); }
    @Override public void refreshCardDetails(Iterable<CardView> cards) { schedulePush(); }
    @Override public void updateRevealedCards(TrackableCollection<CardView> collection) { schedulePush(); }

    @Override
    public void finishGame() {
        GameView gv = getGameView();
        JsonObject o = new JsonObject();
        if (gv != null) {
            o.addProperty("winner", gv.getWinningPlayerName());
            o.addProperty("matchOver", gv.isMatchOver());
        }
        pushStateNow();
        send("gameover", o);
    }

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
    }

    // ---------------------------------------------------------------- IGuiGame: prompt and buttons

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        promptText = message == null ? "" : message;
        pushPrompt();
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        okLabel = label1 == null ? "" : label1;
        cancelLabel = label2 == null ? "" : label2;
        okEnabled = enable1;
        cancelEnabled = enable2;
        pushPrompt();
    }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) {
        super.setSelectables(cards, min, max);
        List<Integer> ids = new ArrayList<>();
        if (cards != null) for (CardView c : cards) { cardIndex.put(c.getId(), c); ids.add(c.getId()); }
        selectableCards = ids;
        pushPrompt();
    }

    /** Cards/players already picked in the current multi-select or targeting input. */
    private final Set<Integer> highlighted = ConcurrentHashMap.newKeySet();

    @Override
    public void setHighlighted(Iterable<? extends forge.trackable.TrackableObject> objects, boolean b) {
        super.setHighlighted(objects, b);
        if (objects != null) {
            for (forge.trackable.TrackableObject o : objects) {
                if (o == null) continue;
                int key = o instanceof PlayerView ? -1000 - o.getId() : o.getId(); // players kept apart from cards
                if (b) highlighted.add(key); else highlighted.remove(key);
            }
        }
        pushPrompt();
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        selectableCards = List.of();
    }

    @Override
    public void flashIncorrectAction() {
        JsonObject o = new JsonObject();
        o.addProperty("text", "That action isn't possible right now.");
        send("notice", o);
    }

    @Override
    public void alertUser() { }

    @Override
    public void message(String message, String title) {
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        o.addProperty("text", message);
        send("notice", o);
    }

    @Override
    public void showErrorDialog(String message, String title) {
        message(message, title);
    }

    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        boolean mine = playerTurn != null && isLocalPlayer(playerTurn);
        return !(mine ? myStops : opponentStops).contains(phase);
    }

    // ---------------------------------------------------------------- IGuiGame: blocking dialogs

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected, FSerializableFunction<T, String> display) {
        return chooseFromList(message, min, max, choices, display, false);
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices, CardView referenceCard, boolean sideboardingMode, boolean showRememberCheckbox) {
        // Ask the player to click the items in the desired order (first click = first/top).
        int n = sourceChoices.size();
        int keepOut = Math.max(0, remainingObjectsMin);
        List<T> ordered = chooseFromList(title + (top == null ? "" : " — " + top), n - Math.max(keepOut, remainingObjectsMax), n - keepOut, sourceChoices, null, true);
        if (destChoices != null) {
            List<T> all = new ArrayList<>(destChoices);
            all.addAll(ordered);
            ordered = all;
        }
        return new OrderResult<>(ordered, false);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        List<String> opts = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
        return chooseOption("Confirm", question, opts, defaultIsYes ? 0 : 1) == 0;
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        return chooseOption(title, message, List.of(yesButtonText, noButtonText), defaultYes ? 0 : 1) == 0;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return chooseOption(title, message, options, defaultOption);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            return inputOptions.get(chooseOption(title, message, inputOptions, 0));
        }
        JsonObject req = new JsonObject();
        req.addProperty("title", title == null ? "" : title);
        req.addProperty("message", message == null ? "" : message);
        req.addProperty("input", true);
        req.addProperty("numeric", isNumeric);
        req.addProperty("initial", initialInput == null ? "" : initialInput);
        JsonObject a = ask(req);
        return a != null && a.has("text") ? a.get("text").getAsString() : initialInput;
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);
        List<SpellAbilityView> pick = chooseFromList("Choose an ability to play", 0, 1, abilities, null, false);
        return pick.isEmpty() ? null : pick.get(0);
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList, DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        List<GameEntityView> opts = new ArrayList<>(optionList);
        List<GameEntityView> pick = chooseFromList(title, isOptional ? 0 : 1, 1, opts, null, false);
        return pick.isEmpty() ? null : pick.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList, int min, int max, DelayedReveal delayedReveal) {
        return chooseFromList(title, min, max, new ArrayList<GameEntityView>(optionList), null, false);
    }

    @Override
    public <T> List<T> many(String title, String topCaption, int min, int max, List<T> sourceChoices, List<T> destChoices, CardView c) {
        return chooseFromList(title + (topCaption == null ? "" : " — " + topCaption), min, max, sourceChoices, null, false);
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> list = new ArrayList<>();
        for (CardView c : cards) list.add(c);
        return list; // MVP: keep the order as is
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return main.toFlatList(); // MVP: no sideboarding between games
    }

    /**
     * MVP: automatic combat damage assignment in blocker order — lethal damage to each blocker,
     * the rest to the defender if the attacker can trample, otherwise to the last blocker.
     */
    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> map = new LinkedHashMap<>();
        int left = damage;
        boolean deathtouch = attacker.getCurrentState() != null && attacker.getCurrentState().hasDeathtouch();
        boolean trample = attacker.getCurrentState() != null && attacker.getCurrentState().hasTrample();
        for (CardView b : blockers) {
            if (left <= 0) break;
            int lethal = deathtouch ? 1 : Math.max(0, b.getLethalDamage());
            int give = Math.min(left, Math.max(lethal, 0));
            map.put(b, give);
            left -= give;
        }
        if (left > 0) {
            if (trample && defender != null) map.put(null, left);
            else if (!blockers.isEmpty()) map.merge(blockers.get(blockers.size() - 1), left, Integer::sum);
            else map.put(null, left);
        }
        return map;
    }

    /** MVP: put the minimum on each target, the rest on the first one. */
    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount, boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> map = new HashMap<>();
        int left = amount;
        Object first = null;
        for (Object o : target.keySet()) {
            if (first == null) first = o;
            int give = atLeastOne ? 1 : 0;
            map.put(o, give);
            left -= give;
        }
        if (first != null && left > 0) map.merge(first, left, Integer::sum);
        return map;
    }

    // ---------------------------------------------------------------- IGuiGame: unused visual hooks

    @Override public forge.game.GameState getGamestate() { return null; }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public void setCard(CardView card) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }
    @Override public void openZones(PlayerView controller, Collection<forge.game.zone.ZoneType> zones, Map<PlayerView, Object> players) { }
}
