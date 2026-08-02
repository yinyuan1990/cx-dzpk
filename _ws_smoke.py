# -*- coding: utf-8 -*-
"""WS 全链路冒烟:两个游客登录 → 建房 → 坐下带入 → 自动打完一手到结算"""
import json
import threading
import time

import websocket

URL = "ws://127.0.0.1:9100/ws/dzpk"
NAMES = {451: "登录OK", 453: "建房OK", 454: "进房", 458: "开局", 459: "手牌",
         460: "轮到", 461: "行动", 462: "发牌", 463: "摊牌", 464: "结算",
         468: "周期结算", 469: "房态", 499: "错误"}


class Player:
    def __init__(self, nick):
        self.nick = nick
        self.user_id = None
        self.room_id = None
        self.settle_count = 0
        self.lock = threading.Lock()
        self.ws = websocket.WebSocketApp(URL, on_message=self.on_msg, on_error=lambda w, e: print(self.nick, "ERR", e))
        self.thread = threading.Thread(target=self.ws.run_forever, daemon=True)
        self.thread.start()
        time.sleep(0.5)
        self.send({"type": 401, "data": {"guest": nick}})

    def send(self, obj):
        self.ws.send(json.dumps(obj))

    def on_msg(self, ws, raw):
        m = json.loads(raw)
        t = m.get("type")
        d = m.get("data") or {}
        label = NAMES.get(t, t)
        if t in (451, 453, 458, 459, 462, 463, 464, 468, 499):
            print(f"[{self.nick}] {label}: {json.dumps(d, ensure_ascii=False)[:200]}")
        if t == 451:
            self.user_id = d["userId"]
        elif t == 453:
            self.room_id = d["roomId"]
        elif t == 460 and d.get("userId") == self.user_id:
            act = "call" if d.get("toCall", 0) > 0 else "check"
            print(f"[{self.nick}] 自动{act}")
            time.sleep(0.2)
            self.send({"type": 409, "roomId": self.room_id, "data": {"act": act, "amount": 0}})
        elif t == 464:
            self.settle_count += 1


p1 = Player("玩家甲")
p2 = Player("玩家乙")
time.sleep(1)
assert p1.user_id and p2.user_id, "登录失败"

# 甲建房
p1.send({"type": 403, "data": {"name": "冒烟局", "sb": 50, "bb": 100, "maxPlayers": 6,
                               "settleTimeMins": 30, "rakePercent": 5}})
time.sleep(1)
assert p1.room_id, "建房失败"
p2.room_id = p1.room_id
print("== 房号", p1.room_id, "==")

for i, p in enumerate((p1, p2)):
    p.send({"type": 404, "roomId": p.room_id})
    time.sleep(0.3)
    p.send({"type": 406, "roomId": p.room_id, "data": {"seat": i}})
    time.sleep(0.3)
    p.send({"type": 407, "roomId": p.room_id, "data": {"amount": 10000}})
    time.sleep(0.3)

# 等一手打完(开局延迟4s + 各街自动行动)
deadline = time.time() + 40
while time.time() < deadline and (p1.settle_count < 1):
    time.sleep(0.5)

print("== 结算次数:", p1.settle_count, "==")
print("SMOKE", "PASS" if p1.settle_count >= 1 else "FAIL")
p1.ws.close()
p2.ws.close()
