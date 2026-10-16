// Plays the opening turns through the real web UI in headless Chromium and takes screenshots.
// Usage: node tools/ui-test.js <outDir> [url]
const { chromium } = require(process.env.PLAYWRIGHT || "playwright");
const out = process.argv[2] || ".";
const url = process.argv[3] || "http://localhost:8080/";
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error" && !/scryfall|ERR_|Failed to load/.test(m.text())) errors.push(m.text()); });
  await page.goto(url);
  await page.waitForSelector("#startBtn:not([disabled])");
  await page.screenshot({ path: `${out}/1-lobby.png` });
  await page.click("#startBtn");
  await page.waitForSelector("#table:not(.hidden)", { timeout: 30000 });
  const prompt = async () => (await page.textContent("#promptText")).trim();
  const ok = async () => { await page.waitForSelector("#okBtn:not([disabled])", { timeout: 20000 }); await page.click("#okBtn"); await page.waitForTimeout(400); };
  // Coin toss choice (play/draw) may come as a dialog
  for (let i = 0; i < 4; i++) {
    await page.waitForTimeout(600);
    if (await page.isVisible("#dialog")) { await page.click("#dialogItems .opt >> nth=0"); continue; }
    const p = await prompt();
    if (/keep your hand/i.test(p)) { await page.screenshot({ path: `${out}/2-mulligan.png` }); await ok(); break; }
    if (/coin toss/i.test(p)) { await ok(); }
  }
  // Pass until it's our main phase
  for (let i = 0; i < 30; i++) {
    await page.waitForTimeout(500);
    if (await page.isVisible("#dialog")) { await page.click("#dialogOk").catch(() => page.click("#dialogItems .opt >> nth=0")); continue; }
    const p = await prompt();
    if (/Turn: \d+ \(You\)[\s\S]*Main phase, precombat/.test(p)) break;
    if (!(await page.isDisabled("#okBtn"))) await page.click("#okBtn");
  }
  await page.screenshot({ path: `${out}/3-my-main.png` });
  // Play a land from hand
  const land = await page.$$eval("#myHand .card.land", (els) => els.map((e) => e.dataset.id));
  if (land.length) {
    await page.click(`#myHand .card[data-id="${land[0]}"]`);
    await page.waitForTimeout(800);
  }
  // Hover a card for the preview
  const firstHand = await page.$("#myHand .card");
  if (firstHand) await firstHand.hover();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${out}/4-land-played.png` });
  // Try casting the first non-land card
  const spell = await page.$$eval("#myHand .card:not(.land)", (els) => els.map((e) => e.dataset.id));
  if (spell.length) {
    await page.click(`#myHand .card[data-id="${spell[0]}"]`);
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${out}/5-casting.png` });
  }
  const state = await page.evaluate(() => ({ prompt: document.querySelector("#promptText").textContent, field: [...document.querySelectorAll("#myField .card")].map((c) => c.querySelector(".nm").textContent), hand: [...document.querySelectorAll("#myHand .card")].length }));
  console.log(JSON.stringify(state, null, 1));
  console.log("page errors:", errors);
  await browser.close();
})();
