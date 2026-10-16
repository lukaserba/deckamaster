// Plays a whole game through the web UI (clicks only) in headless Chromium.
// Screenshots key moments: first attack, first block decision, first non-empty stack, game over.
// Usage: NODE_PATH=... node tools/ui-play.js <outDir> [myDeck] [oppDeck] [url]
const { chromium } = require("playwright");
const [out = ".", myDeck = "Pauper Mono Red Burn", oppDeck = "Pauper Mono Blue Tempo", url = "http://localhost:8080/"] = process.argv.slice(2);

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto(url);
  await page.waitForSelector("#startBtn:not([disabled])");
  await page.selectOption("#deckMine", myDeck);
  await page.selectOption("#deckTheirs", oppDeck);
  await page.click("#startBtn");
  await page.waitForSelector("#table:not(.hidden)", { timeout: 30000 });

  const shots = new Set();
  const shot = async (name) => { if (!shots.has(name)) { shots.add(name); await page.screenshot({ path: `${out}/${name}.png` }); } };
  const tried = new Set();
  const stats = { clicks: 0, dialogs: 0, attacks: 0, blocks: 0, casts: 0, lands: 0 };

  for (let step = 0; step < 900; step++) {
    await page.waitForTimeout(120);
    if (await page.isVisible("#gameover")) { await shot("9-gameover"); break; }
    if (await page.isVisible("#dialog")) {
      stats.dialogs++;
      await shot("6-dialog");
      const okVisible = await page.isVisible("#dialogOk") && !(await page.isDisabled("#dialogOk"));
      const label = okVisible ? await page.textContent("#dialogOk") : "";
      if (okVisible && /Close|Confirm/.test(label)) await page.click("#dialogOk");
      else if (await page.$("#dialogItems .opt:not(.picked)")) await page.click("#dialogItems .opt:not(.picked) >> nth=0");
      else await page.click("#dialogOk");
      continue;
    }
    const v = await page.evaluate(() => {
      const s = app.state, p = app.prompt;
      if (!s || !p) return null;
      const me = s.players.find((x) => x.me), op = s.players.find((x) => !x.me);
      return { s, p, me, op };
    });
    if (!v) continue;
    const { s, p, me, op } = v;
    const text = p.text || "";
    const myTurn = s.activePlayer === me.id;
    const key = (k) => `${s.turn}:${k}`;
    if (s.stack.length) await shot("7-stack");

    if (/keep your hand/i.test(text) && p.ok) { await page.click("#okBtn"); continue; }
    if (/Pay Mana Cost/i.test(text)) {
      if (p.ok) { await page.click("#okBtn"); continue; }
      if (p.cancel) { await page.click("#cancelBtn"); continue; }
    }
    if (/target/i.test(text) && !tried.has(key("tgt" + text))) {
      tried.add(key("tgt" + text));
      await page.click("#oppBar");
      continue;
    }
    if (myTurn && s.phase === "COMBAT_DECLARE_ATTACKERS" && /attack/i.test(text)) {
      const c = me.battlefield.find((c) => c.creature && !c.tapped && !c.sick && !c.attacking && !tried.has(key("atk" + c.id)));
      if (c) { tried.add(key("atk" + c.id)); stats.attacks++; await page.click(`#myField .card[data-id="${c.id}"]`); continue; }
      await shot("5-attack");
      await page.click("#okBtn");
      continue;
    }
    if (!myTurn && /block/i.test(text)) {
      await shot("8-block");
      const blocker = me.battlefield.find((c) => c.creature && !c.tapped);
      if (blocker && s.combat.length && !tried.has(key("blk"))) {
        tried.add(key("blk"));
        stats.blocks++;
        await page.click(`#oppField .card[data-id="${s.combat[0].attacker}"]`);
        await page.waitForTimeout(150);
        await page.click(`#myField .card[data-id="${blocker.id}"]`);
        continue;
      }
      if (p.ok) await page.click("#okBtn");
      continue;
    }
    if (p.selectable.length && !p.ok) { await page.click(`.card[data-id="${p.selectable[0]}"]`); continue; }
    if (myTurn && (s.phase === "MAIN1" || s.phase === "MAIN2") && !s.stack.length && /^Priority/.test(text)) {
      const land = me.hand.find((c) => c.land);
      if (land && me.landsPlayed === 0 && !tried.has(key("land"))) {
        tried.add(key("land")); stats.lands++;
        await page.click(`#myHand .card[data-id="${land.id}"]`);
        continue;
      }
      const spell = me.hand.find((c) => !c.land && !tried.has(key("cast" + c.id)));
      if (spell) {
        tried.add(key("cast" + spell.id)); stats.casts++;
        await page.click(`#myHand .card[data-id="${spell.id}"]`);
        continue;
      }
    }
    if (p.ok) { await page.click("#okBtn"); stats.clicks++; continue; }
    if (p.cancel && p.cancelLabel !== "End Turn") { await page.click("#cancelBtn"); continue; }
  }
  const final = await page.evaluate(() => ({ over: document.querySelector("#gameoverTitle").textContent, players: app.state.players.map((p) => `${p.name}: ${p.life}`), turn: app.state.turn }));
  console.log(JSON.stringify({ final, stats, errors }, null, 1));
  await browser.close();
})();
