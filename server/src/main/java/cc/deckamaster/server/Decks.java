package cc.deckamaster.server;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Preconstructed decks loaded from .dck files in a directory. */
public final class Decks {
    private final Map<String, File> byName = new TreeMap<>();

    public Decks(Path dir) {
        File[] files = dir.toFile().listFiles((d, n) -> n.endsWith(".dck"));
        if (files == null) return;
        for (File f : files) {
            Deck d = DeckSerializer.fromFile(f);
            if (d != null) byName.put(d.getName(), f);
        }
    }

    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    /** Fresh Deck instance each time, so concurrent games never share one. */
    public Deck load(String name) {
        File f = byName.get(name);
        if (f == null) throw new IllegalArgumentException("Unknown deck: " + name);
        return DeckSerializer.fromFile(f);
    }
}
