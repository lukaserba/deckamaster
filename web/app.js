// Deckamaster web client: renders the Forge game state and sends player actions.
"use strict";

const $ = (s) => document.querySelector(s);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

const PHASES = [
  ["UNTAP", "Untap"], ["UPKEEP", "Upkeep"], ["DRAW", "Draw"], ["MAIN1", "Main 1"],
  ["COMBAT_BEGIN", "Combat"], ["COMBAT_DECLARE_ATTACKERS", "Attack"], ["COMBAT_DECLARE_BLOCKERS", "Block"],
  ["COMBAT_FIRST_STRIKE_DAMAGE", "1st strike"], ["COMBAT_DAMAGE", "Damage"], ["COMBAT_END", "End combat"],
  ["MAIN2", "Main 2"], ["END_OF_TURN", "End"], ["CLEANUP", "Cleanup"],
];

const app = {
  ws: null,
  state: null,
  prompt: null,
  selectable: new Set(),
  // Phase stops (mirrors the server defaults; the player can toggle them).
  stops: { mine: new Set(["MAIN1", "MAIN2"]), opponent: new Set(["COMBAT_DECLARE_ATTACKERS", "END_OF_TURN"]) },
};

// ------------------------------------------------------------------ connection

function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  app.ws = new WebSocket(`${proto}://${location.host}/ws`);
  app.ws.onmessage = (ev) => handle(JSON.parse(ev.data));
  app.ws.onclose = () => {
    $("#startBtn").disabled = true;
    $("#startBtn").textContent = "Disconnected — reload";
    toast("Connection to the server was lost.");
  };
}

function send(msg) {
  if (app.ws && app.ws.readyState === 1) app.ws.send(JSON.stringify(msg));
}

function handle(m) {
  switch (m.t) {
    case "hello": return onHello(m);
    case "started": return showScreen("table");
    case "state": app.state = m.state; return render();
    case "prompt":
      if (m.state) app.state = m.state;
      app.prompt = m;
      app.selectable = new Set(m.selectable || []);
      app.picked = new Set(m.picked || []);
      app.pickedPlayers = new Set(m.pickedPlayers || []);
      renderPrompt();
      return render();
    case "choose": return openDialog(m);
    case "notice": return toast(m.text);
    case "gameover": return onGameOver(m);
  }
}

// ------------------------------------------------------------------ lobby

function onHello(m) {
  for (const id of ["#deckMine", "#deckTheirs"]) {
    const sel = $(id);
    sel.innerHTML = "";
    m.decks.forEach((d) => sel.append(new Option(d, d)));
  }
  if (m.decks.length > 1) $("#deckTheirs").selectedIndex = 1;
  $("#startBtn").disabled = false;
  $("#startBtn").textContent = "Start game";
}

$("#startBtn").onclick = () => {
  send({ t: "start", deck: $("#deckMine").value, opponentDeck: $("#deckTheirs").value, name: $("#playerName").value || "You" });
  $("#startBtn").disabled = true;
  $("#startBtn").textContent = "Shuffling…";
};

function showScreen(name) {
  $("#lobby").classList.toggle("hidden", name !== "lobby");
  $("#table").classList.toggle("hidden", name !== "table");
  if (name === "table") sendStops();
}

// ------------------------------------------------------------------ rendering

function me() { return app.state?.players.find((p) => p.me); }
function opp() { return app.state?.players.find((p) => !p.me); }

function render() {
  const s = app.state;
  if (!s) return;
  const my = me(), op = opp();
  const active = s.players.find((p) => p.id === s.activePlayer);
  $("#turnInfo").textContent = `Turn ${s.turn}${active ? " · " + (active.me ? "your turn" : active.name + "'s turn") : ""}`;
  renderPhases(s, active);

  if (op) {
    renderBar($("#oppBar"), op);
    const oh = $("#oppHand");
    oh.innerHTML = "";
    if (op.hand.length) op.hand.forEach((c) => oh.append(cardEl(c)));
    else for (let i = 0; i < op.handCount; i++) oh.append(el("div", "card-back"));
    renderField($("#oppField"), op, s);
  }
  if (my) {
    renderBar($("#myBar"), my);
    const mh = $("#myHand");
    mh.innerHTML = "";
    my.hand.forEach((c) => mh.append(cardEl(c)));
    renderField($("#myField"), my, s);
  }

  const st = $("#stackItems");
  st.innerHTML = "";
  [...s.stack].forEach((it) => {
    const d = el("div", "stack-item");
    const who = s.players.find((p) => p.id === it.controller);
    const src = it.source && !it.source.hidden ? it.source : null;
    d.innerHTML = `<b>${esc(src ? src.name : "Ability")}</b> · ${esc(who ? who.name : "")}<br>${esc(it.text)}`;
    if (src) d.onmouseenter = () => showPreview(src);
    st.append(d);
  });

  const log = $("#logItems");
  log.innerHTML = "";
  (s.log || []).forEach((line) => log.append(el("li", null, line)));
}

function renderPhases(s, active) {
  const ol = $("#phases");
  ol.innerHTML = "";
  const mine = active?.me;
  PHASES.forEach(([key, label]) => {
    const li = el("li", null, label);
    if (key === s.phase) li.classList.add("current");
    const set = mine ? app.stops.mine : app.stops.opponent;
    if (set.has(key)) li.classList.add("stop");
    li.title = `Click to toggle whether you stop here on ${mine ? "your" : "the opponent's"} turn`;
    li.onclick = () => {
      set.has(key) ? set.delete(key) : set.add(key);
      sendStops();
      render();
    };
    ol.append(li);
  });
}

function sendStops() {
  send({ t: "stops", mine: [...app.stops.mine], opponent: [...app.stops.opponent] });
}

function renderBar(node, p) {
  node.innerHTML = "";
  node.classList.toggle("priority", !!p.priority);
  node.classList.toggle("picked", !!(app.pickedPlayers && app.pickedPlayers.has(p.id)));
  node.append(el("span", "name", p.name + (p.ai ? " (AI)" : "")));
  node.append(el("span", "life", String(p.life)));
  if (p.poison) node.append(el("span", "stat", `☠ ${p.poison}`));
  node.append(el("span", "stat", `Hand ${p.handCount}`));
  node.append(el("span", "stat", `Library ${p.libraryCount}`));
  const gy = el("span", "stat", `Graveyard ${p.graveyard.length}`);
  gy.title = p.graveyard.map((c) => c.name).join(", ");
  gy.onclick = (e) => { e.stopPropagation(); showZone(`${p.name}'s graveyard`, p.graveyard); };
  node.append(gy);
  if (p.exile.length) {
    const ex = el("span", "stat", `Exile ${p.exile.length}`);
    ex.onclick = (e) => { e.stopPropagation(); showZone(`${p.name}'s exile`, p.exile); };
    node.append(ex);
  }
  const mana = el("span", "stat mana");
  for (const [k, v] of Object.entries(p.mana || {})) {
    for (let i = 0; i < v; i++) mana.append(manaIcon(k));
  }
  node.append(mana);
  node.onclick = () => send({ t: "player", id: p.id });
}

function renderField(node, p, s) {
  node.innerHTML = "";
  const cards = p.battlefield.filter((c) => !c.attachedTo);
  const attachments = p.battlefield.filter((c) => c.attachedTo);
  const blocking = blockMap(s);
  const rowFor = (list) => {
    const row = el("div", "bf-row");
    list.forEach((c) => {
      const e = cardEl(c, blocking);
      attachments.filter((a) => a.attachedTo === c.id).forEach((a) => {
        e.querySelector(".badges").append(el("span", "badge", "+" + a.name));
      });
      row.append(e);
    });
    return row;
  };
  const nonLands = rowFor(cards.filter((c) => !c.land));
  const lands = rowFor(cards.filter((c) => c.land));
  nonLands.classList.add("nonlands");
  lands.classList.add("lands");
  // Lands sit on the outside edge of the table, creatures towards the middle.
  if (p.me) node.append(nonLands, lands);
  else node.append(lands, nonLands);
}

function blockMap(s) {
  const m = new Map();
  for (const c of s.combat || []) for (const b of c.blockers) m.set(b, c.attacker);
  return m;
}

function cardName(id) {
  for (const p of app.state.players) for (const z of ["battlefield", "hand", "graveyard", "exile"]) {
    const c = p[z].find((x) => x.id === id);
    if (c) return c.name;
  }
  return "";
}

function cardEl(c, blocking) {
  const e = el("div", "card");
  e.dataset.id = c.id;
  if (c.tapped) e.classList.add("tapped");
  if (c.sick) e.classList.add("sick");
  if (c.land) e.classList.add("land");
  if (c.attacking) e.classList.add("attacking");
  if (app.selectable.has(c.id)) e.classList.add("selectable");
  if (app.picked && app.picked.has(c.id)) e.classList.add("picked");

  const frame = el("div", "frame");
  frame.append(el("div", "nm", c.name));
  if (costParts(c.cost).length) frame.append(costEl(c.cost));
  if (c.type) frame.append(el("div", "tp", c.type));
  if (c.text) frame.append(el("div", "tx", c.text));
  e.append(frame);

  if (!c.hidden) {
    const src = Images.get(c.name, "normal");
    if (src) {
      const img = new Image();
      img.alt = c.name;
      img.onload = () => e.classList.add("has-img");
      img.onerror = () => img.remove();
      img.src = src;
      e.prepend(img);
    }
  }

  if (c.creature) e.append(el("div", "pt", `${c.power}/${c.toughness}`));
  const badges = el("div", "badges");
  if (c.attacking) badges.append(el("span", "badge atk", "Attacking"));
  if (blocking && blocking.has(c.id)) badges.append(el("span", "badge blk", "Blocks " + cardName(blocking.get(c.id))));
  if (c.damage) badges.append(el("span", "badge dmg", `${c.damage} dmg`));
  for (const [k, v] of Object.entries(c.counters || {})) badges.append(el("span", "badge", `${v}× ${k}`));
  if (c.token) badges.append(el("span", "badge", "Token"));
  e.append(badges);

  e.onmouseenter = () => showPreview(c);
  e.onclick = () => send({ t: "card", id: c.id });
  return e;
}

// Card images: resolved once per card name through Scryfall's collection endpoint
// (up to 75 names per request), then served from Scryfall's image CDN and the browser cache.
// Re-renders never hit the Scryfall API again.
const Images = (() => {
  const urls = new Map();      // name -> { normal, large } | null (not found)
  const queued = new Set();
  let timer = null;
  try {
    const saved = JSON.parse(localStorage.getItem("cardImages") || "{}");
    for (const [k, v] of Object.entries(saved)) urls.set(k, v);
  } catch (e) { /* storage unavailable */ }

  function persist() {
    try {
      const obj = {};
      for (const [k, v] of urls) if (v) obj[k] = v;
      localStorage.setItem("cardImages", JSON.stringify(obj));
    } catch (e) { /* storage unavailable */ }
  }

  function pick(card, name) {
    if (card.image_uris) return card.image_uris;
    const face = (card.card_faces || []).find((f) => f.name === name) || (card.card_faces || [])[0];
    return face && face.image_uris;
  }

  async function flush() {
    timer = null;
    const names = [...queued].slice(0, 75);
    names.forEach((n) => queued.delete(n));
    if (!names.length) return;
    try {
      const res = await fetch("https://api.scryfall.com/cards/collection", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ identifiers: names.map((name) => ({ name })) }),
      });
      const data = await res.json();
      const found = new Set();
      for (const card of data.data || []) {
        // Match back to the requested name (double-faced cards may be requested by either face).
        const faces = [card.name, ...(card.card_faces || []).map((f) => f.name)];
        for (const n of names) {
          if (faces.includes(n)) {
            const iu = pick(card, n);
            if (iu) { urls.set(n, { normal: iu.normal, large: iu.large || iu.normal }); found.add(n); }
          }
        }
      }
      names.forEach((n) => { if (!found.has(n)) urls.set(n, null); });
      persist();
      render();
    } catch (e) {
      names.forEach((n) => urls.set(n, null)); // offline or blocked: fall back to text cards
    }
    if (queued.size) schedule();
  }

  function schedule() { if (!timer) timer = setTimeout(flush, 120); }

  return {
    get(name, size) {
      if (!name) return null;
      if (!urls.has(name)) { urls.set(name, undefined); queued.add(name); schedule(); return null; }
      const v = urls.get(name); // undefined while the lookup is in flight
      return v ? v[size] : null;
    },
  };
})();

function manaIcon(sym) {
  const s = String(sym).toLowerCase().replace(/[{}\/]/g, "");
  return el("i", `ms ms-${s} ms-cost`);
}

// Forge sends costs like "4 {R} {R}", "{R}", "X {U}" or "-1" (no cost).
function costParts(cost) {
  if (!cost || cost === "-1") return [];
  return cost.replace(/\}\s*\{/g, "} {").trim().split(/\s+/).map((p) => p.replace(/[{}]/g, "")).filter(Boolean);
}

function costEl(cost) {
  const span = el("span", "cost");
  costParts(cost).forEach((p) => span.append(manaIcon(p)));
  return span;
}

function showPreview(c) {
  const box = $("#preview");
  box.innerHTML = "";
  const big = el("div", "big");
  const src = !c.hidden && Images.get(c.name, "large");
  if (src) {
    const img = new Image();
    img.onerror = () => img.remove();
    img.src = src;
    big.append(img);
  }
  box.append(big);
  const t = el("div", "ptext");
  const title = el("div", "ptitle", c.name + " ");
  if (costParts(c.cost).length) title.append(costEl(c.cost));
  t.append(title);
  if (c.type) t.append(el("div", null, c.type));
  if (c.text) t.append(el("div", null, c.text));
  if (c.creature) t.append(el("div", null, `${c.power}/${c.toughness}`));
  box.append(t);
}

function showZone(title, cards) {
  if (!cards.length) return toast(`${title} is empty.`);
  openDialog({ title, items: cards.map((c) => ({ label: c.name, card: c })), min: 0, max: 0, viewOnly: true });
}

// ------------------------------------------------------------------ prompt & buttons

function renderPrompt() {
  const p = app.prompt;
  // Forge's priority prompts repeat turn/phase/stack on extra lines; the top bar already shows those.
  const lines = (p.text || "").split("\n").filter(Boolean);
  const box = $("#promptText");
  box.innerHTML = "";
  const isPriority = /^Priority:/.test(lines[0] || "");
  box.append(el("span", null, isPriority ? "Your priority: play a card, or pass with OK." : (lines[0] || "")));
  const rest = isPriority ? lines.filter((l) => /^Stack:/.test(l) && !/Empty/.test(l)) : lines.slice(1);
  if (rest.length) box.append(el("span", "sub", rest.join(" · ")));
  setBtn($("#okBtn"), p.okLabel, p.ok);
  setBtn($("#cancelBtn"), p.cancelLabel, p.cancel);
  $("#promptBar").classList.toggle("waiting", !p.ok && !p.cancel);
}

function setBtn(b, label, enabled) {
  b.textContent = label || "";
  b.disabled = !enabled;
  b.classList.toggle("hidden", !label);
}

$("#okBtn").onclick = () => send({ t: "ok" });
$("#cancelBtn").onclick = () => send({ t: "cancel" });
$("#concedeBtn").onclick = () => { if (confirm("Concede this game?")) send({ t: "concede" }); };
document.addEventListener("keydown", (e) => {
  if (!$("#dialog").classList.contains("hidden")) return;
  if (e.key === " " || e.key === "Enter") { if (!$("#okBtn").disabled) { e.preventDefault(); send({ t: "ok" }); } }
  if (e.key === "Escape" && !$("#cancelBtn").disabled) send({ t: "cancel" });
});

// ------------------------------------------------------------------ choice dialog

function openDialog(m) {
  app.dialog = m;
  const dlg = $("#dialog");
  const picked = [];
  $("#dialogTitle").textContent = m.title || "Choose";
  $("#dialogMessage").textContent = m.message || "";
  const box = $("#dialogItems");
  box.innerHTML = "";
  const input = $("#dialogInput");
  input.classList.toggle("hidden", !m.input);
  input.value = m.initial || "";
  input.type = m.numeric ? "number" : "text";

  const single = !m.viewOnly && m.max === 1 && !m.ordered;
  const hint = () => {
    if (m.viewOnly) return "";
    if (m.input) return "";
    if (m.ordered) return `Click in order (first click = first). ${picked.length} chosen.`;
    if (m.min === m.max) return `Choose ${m.min}. ${picked.length} chosen.`;
    return `Choose ${m.min}–${m.max}. ${picked.length} chosen.`;
  };
  const refresh = () => {
    [...box.children].forEach((node, i) => {
      const pos = picked.indexOf(i);
      node.classList.toggle("picked", pos >= 0);
      node.querySelector(".num")?.remove();
      if (pos >= 0 && m.ordered) node.append(el("span", "num", String(pos + 1)));
    });
    $("#dialogHint").textContent = hint();
    const okBtn = $("#dialogOk");
    okBtn.disabled = !m.viewOnly && !m.input && (picked.length < m.min || picked.length > m.max);
  };

  (m.items || []).forEach((it, i) => {
    const wrap = el("div", "opt");
    if (it.card) {
      const c = cardEl(it.card);
      c.onclick = null;
      c.classList.remove("selectable", "tapped");
      wrap.append(c);
    } else {
      wrap.append(el("div", "opt-text", it.label));
    }
    wrap.onclick = () => {
      if (m.viewOnly) return;
      if (single || m.buttons) return answer(m, [i]);
      const pos = picked.indexOf(i);
      if (pos >= 0) picked.splice(pos, 1);
      else if (picked.length < m.max) picked.push(i);
      refresh();
    };
    box.append(wrap);
  });

  const hideButtons = !!((single || m.buttons) && !m.viewOnly);
  $("#dialogOk").classList.toggle("hidden", !!(hideButtons && !(single && m.min === 0)));
  $("#dialogOk").textContent = m.viewOnly ? "Close" : (single && m.min === 0 ? "Cancel" : "Confirm");
  $("#dialogReset").classList.toggle("hidden", !!(hideButtons || m.viewOnly || m.input));
  $("#dialogOk").onclick = () => {
    if (m.viewOnly) {
      if (m.id != null) return answer(m, []); // server is waiting for us to close a reveal
      return dlg.classList.add("hidden");
    }
    if (m.input) return answer(m, [], input.value);
    answer(m, picked);
  };
  $("#dialogReset").onclick = () => { picked.length = 0; refresh(); };
  dlg.classList.remove("hidden");
  refresh();
  if (m.input) input.focus();
}

function answer(m, indices, text) {
  const msg = { t: "answer", id: m.id, indices };
  if (text != null) msg.text = text;
  send(msg);
  $("#dialog").classList.add("hidden");
}

// ------------------------------------------------------------------ misc

function onGameOver(m) {
  $("#gameoverTitle").textContent = m.winner ? `${m.winner} won the game` : "Game over";
  $("#gameover").classList.remove("hidden");
}

$("#againBtn").onclick = () => location.reload();

function toast(text) {
  if (!text) return;
  const t = el("div", "toast", text);
  $("#toasts").append(t);
  setTimeout(() => t.remove(), 4000);
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
}

connect();
