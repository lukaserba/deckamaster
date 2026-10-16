package cc.deckamaster.server;

import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;

import java.nio.file.Path;

/**
 * Entry point: boots the Forge engine headless, then serves the web client and
 * the game WebSocket on one port.
 *
 * Usage: java -cp forge.jar:deckamaster-server.jar cc.deckamaster.server.Main [port] [webDir] [deckDir]
 * The working directory must contain Forge's "res" folder (or a link to it).
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path webDir = Path.of(args.length > 1 ? args[1] : "web").toAbsolutePath();
        Path deckDir = Path.of(args.length > 2 ? args[2] : "decks").toAbsolutePath();

        long t0 = System.currentTimeMillis();
        // GuiDesktop is only used for paths, threading helpers and preferences; with
        // java.awt.headless=true it never opens a window.
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, prefs -> {
            // A server has no audio device; Forge's sound system would throw on every event.
            prefs.setPref(ForgePreferences.FPref.UI_ENABLE_SOUNDS, false);
            prefs.setPref(ForgePreferences.FPref.UI_ENABLE_MUSIC, false);
            return null;
        });
        System.out.printf("Forge engine ready in %d ms%n", System.currentTimeMillis() - t0);

        Decks decks = new Decks(deckDir);
        System.out.println("Decks available: " + decks.names());

        new WebServer(port, webDir, decks).run();
    }
}
