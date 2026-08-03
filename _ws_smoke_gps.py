# -*- coding: utf-8 -*-
"""GPS 防火牌冒烟:开 gpsLimitOn 的房,坐下按距离拦截
   1) 甲上报定位后建房坐下(首个入座无同桌,直接过)
   2) 乙不上报定位 → 坐下被拒
   3) 乙上报与甲相同坐标 → 距离 0 < 100m 被拒
   4) 乙上报 1km 外坐标 → 坐下成功
"""
import json
import threading
import time

import websocket

URL = "ws://127.0.0.1:9100/ws/dzpk"


class Player:
    def __init__(self, nick):
        self.nick = nick
        self.user_id = None
        self.room_id = None
        self.errors = []
        self.sat = False
        self.ws = websocket.WebSocketApp(URL, on_message=self.on_msg,
                                         on_error=lambda w, e: print(self.nick, "ERR", e))
        threading.Thread(target=self.ws.run_forever, daemon=True).start()
        time.sleep(0.5)
        self.send({"type": 401, "data": {"guest": nick}})

    def send(self, obj):
        self.ws.send(json.dumps(obj))

    def on_msg(self, ws, raw):
        m = json.loads(raw)
        t = m.get("type")
        d = m.get("data") or {}
        if t == 451:
            self.user_id = d["userId"]
        elif t == 453:
            self.room_id = d["roomId"]
        elif t == 499:
            self.errors.append(d.get("msg", ""))
            print(f"[{self.nick}] 错误: {d.get('msg')}")
        elif t == 456 and d.get("userId") == self.user_id:
            self.sat = True
            print(f"[{self.nick}] 坐下成功 seat={d.get('seat')}")


ok = True

a = Player("甲")
b = Player("乙")
time.sleep(1)
assert a.user_id and b.user_id, "登录失败"

# 甲上报定位(北京天安门附近)
a.send({"type": 432, "data": {"lat": 39.9087, "lng": 116.3975}})
time.sleep(0.3)

# 甲建 GPS 限制房并坐下
a.send({"type": 403, "data": {"name": "GPS房", "sb": 50, "maxPlayers": 6,
                              "settleTimeMins": 30, "rakePercent": 5, "gpsLimitOn": 1}})
time.sleep(1)
assert a.room_id, "建房失败"
b.room_id = a.room_id
a.send({"type": 404, "roomId": a.room_id})
time.sleep(0.3)
a.send({"type": 406, "roomId": a.room_id, "data": {"seat": 0}})
time.sleep(0.5)
if not a.sat:
    ok = False
    print("FAIL 甲应能坐下(首个入座)")

# 乙不报 GPS 直接坐 → 应被拒
b.send({"type": 404, "roomId": b.room_id})
time.sleep(0.3)
b.send({"type": 406, "roomId": b.room_id, "data": {"seat": 1}})
time.sleep(0.5)
if b.sat or not any("GPS" in e for e in b.errors):
    ok = False
    print("FAIL 乙未上报GPS应被拒:", b.errors)
else:
    print("OK 乙未上报GPS被拒")

# 乙上报相同坐标 → 距离 0 被拒
b.errors.clear()
b.send({"type": 432, "data": {"lat": 39.9087, "lng": 116.3975}})
time.sleep(0.3)
b.send({"type": 406, "roomId": b.room_id, "data": {"seat": 1}})
time.sleep(0.5)
if b.sat or not any("距离" in e for e in b.errors):
    ok = False
    print("FAIL 乙同坐标应被拒:", b.errors)
else:
    print("OK 乙距离过近被拒")

# 乙上报约 1.1km 外坐标(纬度 +0.01)→ 应可坐
b.errors.clear()
b.send({"type": 432, "data": {"lat": 39.9187, "lng": 116.3975}})
time.sleep(0.3)
b.send({"type": 406, "roomId": b.room_id, "data": {"seat": 1}})
time.sleep(0.5)
if not b.sat:
    ok = False
    print("FAIL 乙 1km 外应可坐下:", b.errors)
else:
    print("OK 乙距离达标坐下成功")

print("SMOKE", "PASS" if ok else "FAIL")
a.ws.close()
b.ws.close()
