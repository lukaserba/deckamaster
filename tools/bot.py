"""Protocol-level test bot: plays a full game against the Forge AI over the WebSocket.

Not a smart player - it plays lands, tries every spell, attacks with everything, never blocks,
burns the opponent's face. Its job is to exercise the server end to end.

Usage: python3 tools/bot.py [ws://localhost:8080/ws] [my deck] [opponent deck]
"""
import asyncio
import json
import sys
import time

import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://localhost:8080/ws"
MY_DECK = sys.argv[2] if len(sys.argv) > 2 else "Pauper Mono Red Burn"
OPP_DECK = sys.argv[3] if len(sys.argv) > 3 else "Pauper Mono Blue Tempo"
DEBUG = bool(__import__("os").environ.get("BOT_DEBUG"))


class Bot:
    def __init__(self, ws):
        self.ws = ws
        self.state = None
        self.prompt = None
        self.tried = set()       # (turn, card id) already attempted this turn
        self.actions = 0
        self.prompts_seen = {}
        self.choices_seen = []
        self.result = None
        self.tried_prompts = {}
        self.repeat = {}
        self.stats = {k: 0 for k in ('paid','targeted','attack_clicks','blocks','land','cast_attempts')}

    async def send(self, **m):
        self.actions += 1
        if DEBUG and self.actions < 80:
            st = self.state or {}
            print('  >', m, '|', st.get('phase'), 'T', st.get('turn'), '|', (self.prompt or {}).get('text','')[:50].replace('\n',' / '))
        await self.ws.send(json.dumps(m))

    def me(self):
        return next((p for p in self.state["players"] if p["me"]), None) if self.state else None

    def opp(self):
        return next((p for p in self.state["players"] if not p["me"]), None) if self.state else None

    async def on_prompt(self):
        p = self.prompt
        text = p["text"]
        key = text.split("\n")[0][:60]
        self.prompts_seen[key] = self.prompts_seen.get(key, 0) + 1
        s, me, opp = self.state, self.me(), self.opp()
        low = text.lower()
        ok_label = (p["okLabel"] or "").lower()
        cancel_label = (p["cancelLabel"] or "").lower()
        if not p["ok"] and not p["cancel"] and not p["selectable"]:
            return  # waiting for the opponent
        my_turn = s and me and s["activePlayer"] == me["id"]

        # Loop guard: the bot's heuristics can get stuck re-trying the same thing. If the exact
        # same prompt keeps coming back, pick the next not-yet-picked card, else confirm or cancel.
        full = (s and s["turn"], text, p["ok"], p["cancel"], tuple(p["selectable"]), tuple(p.get("picked", [])))
        self.repeat[full] = self.repeat.get(full, 0) + 1
        if self.repeat[full] > 6:
            nxt = [i for i in p["selectable"] if i not in p.get("picked", [])]
            if nxt and self.repeat[full] < 12:
                return await self.send(t="card", id=nxt[0])
            if p["ok"]:
                return await self.send(t="ok")
            if p["cancel"]:
                return await self.send(t="cancel")

        if ok_label == "keep":
            return await self.send(t="ok")

        if "pay mana cost" in low:
            if p["ok"]:
                self.stats["paid"] += 1
                return await self.send(t="ok")   # auto-pay
            return await self.send(t="cancel")

        if "target" in low and "targets" not in self.tried_prompt(s, text):
            self.mark_prompt(s, text, "targets")
            self.stats["targeted"] += 1
            return await self.send(t="player", id=opp["id"])

        if my_turn and s["phase"] == "COMBAT_DECLARE_ATTACKERS" and "attack" in low:
            for c in me["battlefield"]:
                k = ("atk", s["turn"], c["id"])
                if c.get("creature") and not c["tapped"] and not c["sick"] and k not in self.tried:
                    self.tried.add(k)
                    self.stats["attack_clicks"] += 1
                    return await self.send(t="card", id=c["id"])
            return await self.send(t="ok")

        if not my_turn and "block" in low:
            # Block the first attacker with the first untapped creature, once per turn.
            k = ("blk", s["turn"])
            blockers = [c for c in me["battlefield"] if c.get("creature") and not c["tapped"]]
            if s["combat"] and blockers and k not in self.tried:
                self.tried.add(k)
                self.stats["blocks"] += 1
                await self.send(t="card", id=s["combat"][0]["attacker"])
                await asyncio.sleep(0.05)
                return await self.send(t="card", id=blockers[0]["id"])
            return await self.send(t="ok")

        if p["selectable"] and not p["ok"]:
            return await self.send(t="card", id=p["selectable"][0])

        # Priority in my main phase with an empty stack: play something.
        if my_turn and s["phase"] in ("MAIN1", "MAIN2") and not s["stack"] and low.startswith("priority"):
            hand = sorted(me["hand"], key=lambda c: not c.get("land"))
            for c in hand:
                k = (s["turn"], c["id"])
                if k in self.tried or (c.get("land") and me["landsPlayed"] > 0):
                    continue
                self.tried.add(k)
                self.stats["land" if c.get("land") else "cast_attempts"] += 1
                return await self.send(t="card", id=c["id"])

        if p["ok"]:
            return await self.send(t="ok")
        if p["cancel"] and cancel_label != "end turn":
            return await self.send(t="cancel")

    def tried_prompt(self, s, text):
        return self.tried_prompts.get((s and s["turn"], text), set())

    def mark_prompt(self, s, text, what):
        self.tried_prompts.setdefault((s and s["turn"], text), set()).add(what)

    async def on_choose(self, m):
        self.choices_seen.append(m.get("title") or m.get("message"))
        if m.get("input"):
            return await self.send(t="answer", id=m["id"], indices=[], text=m.get("initial") or "0")
        n = max(m.get("min", 1), 1 if m.get("max", 1) >= 1 else 0)
        n = min(n, len(m.get("items", [])))
        if m.get("ordered"):
            n = m.get("max", len(m["items"]))
        await self.send(t="answer", id=m["id"], indices=list(range(n)))

    async def run(self):
        start = time.time()
        await self.send(t="start", deck=MY_DECK, opponentDeck=OPP_DECK, name="Bot")
        while True:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout=60)
            except asyncio.TimeoutError:
                print("!! no message for 60 s - stuck. Last prompt:", self.prompt)
                print("   phase:", self.state and self.state["phase"], "turn:", self.state and self.state["turn"])
                return False
            m = json.loads(raw)
            t = m["t"]
            if t == "state":
                self.state = m["state"]
            elif t == "prompt":
                if m.get("state"):
                    self.state = m["state"]
                self.prompt = m
                await self.on_prompt()
            elif t == "choose":
                await self.on_choose(m)
            elif t == "notice":
                print("   notice:", m.get("text"))
            elif t == "gameover":
                self.result = m
                break
            if self.actions > 3000:
                print("!! too many actions")
                return False
        me, opp = self.me(), self.opp()
        print(f"Game over in {time.time() - start:.1f}s, turn {self.state['turn']}: winner = {self.result.get('winner')}")
        print(f"  life: {me['name']} {me['life']}  vs  {opp['name']} {opp['life']}")
        print(f"  actions sent: {self.actions}")
        print("  stats:", self.stats)
        print("  prompts seen:")
        for k, v in sorted(self.prompts_seen.items(), key=lambda kv: -kv[1]):
            print(f"    {v:4d}  {k!r}")
        print("  dialogs seen:", sorted(set(map(str, self.choices_seen))))
        print("  last log lines:", self.state["log"][:6])
        return True


async def main():
    async with websockets.connect(URL, max_size=1 << 22) as ws:
        hello = json.loads(await ws.recv())
        print("decks:", hello["decks"])
        ok = await Bot(ws).run()
        sys.exit(0 if ok else 1)


asyncio.run(main())
