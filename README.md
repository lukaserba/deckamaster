# Deckamaster

A browser client for Magic: The Gathering — constructed, cube draft, sealed and multiplayer pods —
running on the [Forge](https://github.com/Card-Forge/forge) rules engine (GPL-3.0).

## Roadmap

1. **Engine spike** — build Forge, run headless AI vs AI matches ✅
2. **Core** — WebSocket adapter, game table, 1v1 constructed vs AI ✅ (deckbuilder next)
3. **Online** — lobby, play vs people, reconnect, spectating, accounts / guest mode
4. **Limited** — cube draft, sealed, booster draft vs bots
5. **Pods & variants** — multiplayer, Winston, Richman, Momir Basic, Forgetful Fish
6. **Metagame** — public archetype stats

## Try it

Download `deckamaster.zip` from the **dev-build** release, unzip, and double-click `run.command`
(macOS) or `run.bat` (Windows). Needs Java 17+ ([adoptium.net](https://adoptium.net)).

## Layout

- `server/` — Java game server. `WebGuiGame` implements Forge's `IGuiGame`: prompts, buttons and
  choice dialogs go to the browser as JSON over a WebSocket; clicks come back through `IGameController`.
- `web/` — browser client (plain HTML/CSS/JS, no build step).
- `decks/` — Forge `.dck` decks offered in the lobby.
- `tools/` — protocol bot (`bot.py`), Playwright UI tests, dev scripts.
- `.github/workflows/ci.yml` — builds Forge and the server, runs bot games, publishes the bundle.

## Develop

```
server/build.sh path/to/forge.jar        # compile the server
tools/dev-server.sh path/to/forge.jar path/to/forge-gui/res   # run on :8080
python3 tools/bot.py                     # bot plays a full game over the WebSocket
```

## Credits

- Forge — rules engine, Card-Forge contributors, GPL-3.0
- Scryfall — card data and images, per Scryfall API terms
- Mana Font — Andrew Gioia, SIL OFL 1.1 / MIT

Unofficial Fan Content permitted under the Wizards of the Coast Fan Content Policy.
Not approved/endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. ©Wizards of the Coast LLC.
