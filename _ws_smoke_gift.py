# -*- coding: utf-8 -*-
"""礼物+停服维护 冒烟:
1 游客登录 → 建房 → 进房 → 坐下 → 带入
2 GIFT_LIST(417) → 490 应有上架礼物
3 等机器人入座 → GIFT_SEND(418) 指定机器人 → 收 491 ROOM_GIFT(非俱乐部房走 SCORE 扣桌面带入)
4 管理后台开 maintenance_mode → 收 479 ROOM_DISMISSED(reason=maintenance) → 关回
"""
import asyncio, json, sys, urllib.request

BASE = "http://127.0.0.1:18081"
WS = "ws://127.0.0.1:18081/ws/dzpk"
ADMIN_PWD = "dz@admin2026"

import websockets

seq = 0
def mk(t, data=None, room=None):
    global seq
    seq += 1
    m = {"type": t, "sequence": seq, "data": data or {}}
    if room: m["roomId"] = room
    return json.dumps(m)

async def send(ws, t, data=None, room=None):
    await ws.send(mk(t, data, room))

async def recv_until(ws, t, timeout=20):
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout)
        m = json.loads(raw)
        if m.get("type") == t:
            return m
        if m.get("type") == 499:
            print("  [ERROR]", m.get("data"))

def admin(path, method="GET", body=None, token=None):
    req = urllib.request.Request(BASE + "/api/admin" + path, method=method,
                                 data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json",
                                          **({"X-Admin-Token": token} if token else {})})
    return json.loads(urllib.request.urlopen(req, timeout=10).read())

async def main():
    async with websockets.connect(WS) as ws:
        await send(ws, 401, {"guest": "礼物哥"})
        login = (await recv_until(ws, 451))["data"]
        uid = login["userId"]
        print("1 登录 OK userId=", uid)

        await send(ws, 403, {"name": "礼物房", "sb": 50, "bb": 100, "maxPlayers": 6, "settleTimeMins": 30})
        rid = (await recv_until(ws, 453))["roomId"]
        print("2 建房 OK roomId=", rid)

        await send(ws, 404, {}, rid)
        await recv_until(ws, 454)
        await send(ws, 406, {"seat": 0}, rid)
        await recv_until(ws, 456)
        await send(ws, 407, {"amount": 10000}, rid)
        await recv_until(ws, 457)
        print("3 坐下+带入 OK")

        await send(ws, 417, {}, rid)
        gifts = (await recv_until(ws, 490))["data"]["gifts"]
        assert len(gifts) >= 10, "礼物列表不足10个: %d" % len(gifts)
        print("4 礼物列表 OK:", [(g["giftKey"], g["costScore"]) for g in gifts[:3]], "... 共", len(gifts))

        # 等机器人坐下(robot_fill_count=2)
        robot = None
        for _ in range(30):
            raw = await asyncio.wait_for(ws.recv(), 30)
            m = json.loads(raw)
            if m.get("type") == 456 and m["data"].get("userId") != uid:
                robot = m["data"]
                break
        assert robot, "没等到机器人入座"
        print("5 机器人入座 OK:", robot.get("userId"), "seat=", robot.get("seat"))

        gift = gifts[0]
        await send(ws, 418, {"giftId": gift["id"], "toUserId": robot["userId"]}, rid)
        g = (await recv_until(ws, 491))["data"]
        assert g["fromUserId"] == uid and g["toUserId"] == robot["userId"]
        assert g["costType"] == "SCORE", "非俱乐部房应走 SCORE: " + str(g["costType"])
        assert g["fromStack"] == 10000 - gift["costScore"], "扣带入不对: " + str(g["fromStack"])
        print("6 送礼 OK:", g["giftKey"], "cost=", g["cost"], "fromStack=", g["fromStack"])

        # 停服维护:游戏中的桌局末清 / 空闲桌立即清
        token = admin("/login", "POST", {"password": ADMIN_PWD})["token"]
        r = admin("/configs", "PUT", {"key": "maintenance_mode", "value": "1"}, token)
        assert r["code"] == 0
        print("7 已开启维护,等强制清房(游戏中的桌等本手打完)...")
        m = await recv_until(ws, 479, timeout=120)
        assert m["data"].get("reason") == "maintenance", "reason=" + str(m["data"].get("reason"))
        print("8 收到 ROOM_DISMISSED(reason=maintenance) OK")
        admin("/configs", "PUT", {"key": "maintenance_mode", "value": "0"}, token)
        print("9 维护已关回。全部通过")

asyncio.run(main())
