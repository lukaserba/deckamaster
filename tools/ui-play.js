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

  // The UI can change between reading app state and clicking; a missed click is simply retried next step.
  const click = (sel) => page.click(sel, { timeout: 3000 }).catch(() => {});
  const shots = new Set();
  const shot = async (name) => { if (!shots.has(name)) { shots.add(name); await page.screenshot({ path: `${out}/${name}.png` }); } };
  const tried = new Set();
  const seenDialogs = {};
  const stats = { clicks: 0, dialogs: 0, attacks: 0, blocks: 0, casts: 0, lands: 0 };

  const promptCounts = {};
  for (let step = 0; step < 2500; step++) {
    await page.waitForTimeout(120);
    if (await page.isVisible("#gameover")) { await shot("9-gameover"); break; }
    if (await page.isVisible("#dialog")) {
      stats.dialogs++;
      await shot("6-dialog");
      const d = await page.evaluate(() => ({
        title: document.querySelector("#dialogTitle").textContent,
        items: document.querySelectorAll("#dialogItems .opt").length,
        picked: document.querySelectorAll("#dialogItems .opt.picked").length,
        hint: document.querySelector("#dialogHint").textContent,
        ok: document.querySelector("#dialogOk").className + " disabled=" + document.querySelector("#dialogOk").disabled,
      }));
      const dk = d.title + "|" + d.items;
      seenDialogs[dk] = (seenDialogs[dk] || 0) + 1;
      if (seenDialogs[dk] === 1 || seenDialogs[dk] === 30) console.log("dialog", seenDialogs[dk], JSON.stringify(d), JSON.stringify(await page.evaluate(() => { const m = { ...app.dialog }; m.items = (m.items || []).map((i) => i.label); return m; })));
      if (seenDialogs[dk] === 30) await page.screenshot({ path: `${out}/stuck-dialog.png` });
      const okVisible = await page.isVisible("#dialogOk") && !(await page.isDisabled("#dialogOk"));
      const label = okVisible ? await page.textContent("#dialogOk") : "";
      if (okVisible && /Close|Confirm/.test(label)) await click("#dialogOk");
      else if (await page.$("#dialogItems .opt:not(.picked)")) await click("#dialogItems .opt:not(.picked) >> nth=0");
      else await click("#dialogOk");
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
    const pk = `${s.turn}|${s.phase}|${text.split("\n")[0]}|ok=${p.ok}:${p.okLabel}|cancel=${p.cancel}:${p.cancelLabel}|sel=${p.selectable.length}`;
    promptCounts[pk] = (promptCounts[pk] || 0) + 1;
    const myTurn = s.activePlayer === me.id;
    const key = (k) => `${s.turn}:${k}`;
    if (s.stack.length) await shot("7-stack");

    if (/keep your hand/i.test(text) && p.ok) { await click("#okBtn"); continue; }
    if (/Pay Mana Cost/i.test(text)) {
      if (p.ok) { await click("#okBtn"); continue; }
      if (p.cancel) { await click("#cancelBtn"); continue; }
    }
    if (/target/i.test(text) && !tried.has(key("tgt" + text))) {
      tried.add(key("tgt" + text));
      await click("#oppBar");
      continue;
    }
    if (myTurn && s.phase === "COMBAT_DECLARE_ATTACKERS" && /attack/i.test(text)) {
      const c = me.battlefield.find((c) => c.creature && !c.tapped && !c.sick && !c.attacking && !tried.has(key("atk" + c.id)));
      if (c) { tried.add(key("atk" + c.id)); stats.attacks++; await click(`#myField .card[data-id="${c.id}"]`); continue; }
      await shot("5-attack");
      await click("#okBtn");
      continue;
    }
    if (!myTurn && /block/i.test(text)) {
      await shot("8-block");
      const blocker = me.battlefield.find((c) => c.creature && !c.tapped);
      if (blocker && s.combat.length && !tried.has(key("blk"))) {
        tried.add(key("blk"));
        stats.blocks++;
        await click(`#oppField .card[data-id="${s.combat[0].attacker}"]`);
        await page.waitForTimeout(150);
        await click(`#myField .card[data-id="${blocker.id}"]`);
        continue;
      }
      if (p.ok) await click("#okBtn");
      continue;
    }
    if (p.selectable.length && !p.ok) {
      const next = p.selectable.find((id) => !(p.picked || []).includes(id));
      if (next != null) { await click(`.card[data-id="${next}"]`); continue; }
    }
    if (myTurn && (s.phase === "MAIN1" || s.phase === "MAIN2") && !s.stack.length && /^Priority/.test(text)) {
      const land = me.hand.find((c) => c.land);
      if (land && me.landsPlayed === 0 && !tried.has(key("land"))) {
        tried.add(key("land")); stats.lands++;
        await click(`#myHand .card[data-id="${land.id}"]`);
        continue;
      }
      const spell = me.hand.find((c) => !c.land && !tried.has(key("cast" + c.id)));
      if (spell) {
        tried.add(key("cast" + spell.id)); stats.casts++;
        await click(`#myHand .card[data-id="${spell.id}"]`);
        continue;
      }
    }
    if (p.ok) { await click("#okBtn"); stats.clicks++; continue; }
    if (p.cancel && p.cancelLabel !== "End Turn") { await click("#cancelBtn"); continue; }
  }
  const final = await page.evaluate(() => ({ over: document.querySelector("#gameoverTitle").textContent, players: app.state.players.map((p) => `${p.name}: ${p.life}`), turn: app.state.turn }));
  const top = Object.entries(promptCounts).sort((a, b) => b[1] - a[1]).slice(0, 4);
  console.log("top prompts:", JSON.stringify(top));
  console.log(JSON.stringify({ final, stats, errors }, null, 1));
  await browser.close();
})();
