DECKAMASTER — local test build
==============================

Play Magic: The Gathering against the Forge AI in your browser.

1. Install Java 17 or newer if you don't have it: https://adoptium.net
   (macOS: download the Temurin .pkg for your Mac and run the installer.)
2. Start the game:
   - macOS: double-click "run.command". The first time, macOS may block it:
     right-click the file > Open > Open.
   - Windows: double-click "run.bat".
   - Linux: ./run.command
3. Your browser opens http://localhost:8080 — pick decks and press Start game.

Controls
- Click a card in your hand to play it; click lands to tap them for mana while paying.
- OK / the space bar passes priority or confirms; the button on the left cancels,
  undoes, or ends your turn depending on the situation.
- Click a phase in the top bar to toggle whether you stop there.
- Click the opponent's name bar to target the opponent.

Add your own decks: drop Forge .dck files into the "decks" folder and restart.

Credits
- Rules engine: Forge (https://github.com/Card-Forge/forge), GPL-3.0.
  forge.jar is an unmodified build of Forge; its source is available at that address.
- Card data and images: Scryfall (https://scryfall.com).
- Mana Font by Andrew Gioia (SIL OFL 1.1 / MIT).
Unofficial Fan Content permitted under the Wizards of the Coast Fan Content Policy.
Not approved/endorsed by Wizards. Portions of the materials used are property of
Wizards of the Coast. ©Wizards of the Coast LLC.
