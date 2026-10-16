package cc.deckamaster.server;

import forge.GuiDesktop;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;

/**
 * Forge's desktop GUI glue with the parts that need a real screen switched off.
 * Paths, preferences and threading helpers are inherited unchanged.
 */
final class HeadlessGui extends GuiDesktop {
    /** Achievement trophies are drawn from skin images that are never loaded on a server. */
    @Override
    public ISkinImage createLayeredImage(PaperCard paperCard, FSkinProp background, String overlayFilename, float opacity) {
        return null;
    }

    /** Only used to spectate AI-vs-AI games, which this server never hosts. */
    @Override
    public IGuiGame getNewGuiGame() {
        throw new UnsupportedOperationException("No local GUI on the server");
    }
}
