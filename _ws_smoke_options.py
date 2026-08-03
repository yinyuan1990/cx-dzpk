# -*- coding: utf-8 -*-
"""建房参数档位 + 扣钻矩阵 冒烟:
1 419 ROOM_OPTIONS → 492 六组档位
2 档外参数建房应被拒(settleTimeMins=37 / sb=77)
3 档内建房成功
4 管理后台改档位(加 37 分钟档) → 再建 37 分钟房应成功
5 管理后台存矩阵 JSON → 校验 configs 里能读回
"""
import asyncio, json, urllib.request
import websockets

BASE = "http://127.0.0.1:18081"
WS = "ws://127.0.0.1:18081/ws/dzpk"

seq = 0
def mk(t, data=None, room=None):
    global seq
    seq += 1
    m = {"type": t, "sequence": seq, "data": data or {}}
    if room: m["roomId"] = room
    return json.dumps(m)

async def send(ws, t, data=None, room=None):
    await ws.send(mk(t, data, room))

async def recv_type(ws, types, timeout=15):
    while True:
        m = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if m.get("type") in types:
            return m

def admin(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE + "/api/admin" + path, method=method,
                                 data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json",
                                          **({"X-Admin-Token": token} if token else {})})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())

async def main():
    async with websockets.connect(WS) as ws:
        await send(ws, 401, {"guest": "配置哥"})
        await recv_type(ws, {451})
        print("1 登录 OK")

        await send(ws, 419, {})
        o = (await recv_type(ws, {492}))["data"]
        assert o["settleTimes"] == [30, 45, 60, 90, 120], o["settleTimes"]
        assert o["blinds"] == [50, 100, 250, 500, 1000], o["blinds"]
        assert o["rakePercents"] == [0, 3, 5, 10], o["rakePercents"]
        print("2 ROOM_OPTIONS OK:", {k: o[k] for k in ("settleTimes", "blinds")})

        await send(ws, 403, {"name": "档外时长", "sb": 50, "settleTimeMins": 37})
        m = await recv_type(ws, {453, 499})
        assert m["type"] == 499 and "不在可选档" in m["data"]["msg"], m
        print("3 档外时长被拒 OK:", m["data"]["msg"])

        await send(ws, 403, {"name": "档外小盲", "sb": 77, "settleTimeMins": 30})
        m = await recv_type(ws, {453, 499})
        assert m["type"] == 499 and "不在可选档" in m["data"]["msg"], m
        print("4 档外小盲被拒 OK:", m["data"]["msg"])

        await send(ws, 403, {"name": "档内房", "sb": 100, "settleTimeMins": 45})
        m = await recv_type(ws, {453, 499})
        assert m["type"] == 453, m
        print("5 档内建房 OK roomId=", m["roomId"])

        token = admin("/login", "POST", {"password": "dz@admin2026"})["token"]
        r = admin("/configs", "PUT", {"key": "room_settle_time_options", "value": "30,37,45,60,90,120"}, token)
        assert r["code"] == 0
        await send(ws, 403, {"name": "新档37", "sb": 50, "settleTimeMins": 37})
        m = await recv_type(ws, {453, 499})
        assert m["type"] == 453, m
        print("6 加档后 37 分钟建房 OK")

        tiers = json.dumps([{"minutes": 30, "baseScore": 100, "cost": 8},
                            {"minutes": 45, "baseScore": 200, "cost": 15}])
        r = admin("/configs", "PUT", {"key": "owner_period_diamond_tiers", "value": tiers}, token)
        assert r["code"] == 0
        cfgs = admin("/configs", token=token)["configs"]
        saved = next(c for c in cfgs if c["key"] == "owner_period_diamond_tiers")["value"]
        assert json.loads(saved)[0]["cost"] == 8
        print("7 矩阵保存/读回 OK")

        admin("/configs", "PUT", {"key": "room_settle_time_options", "value": "30,45,60,90,120"}, token)
        print("8 档位还原 OK。全部通过")

asyncio.run(main())
