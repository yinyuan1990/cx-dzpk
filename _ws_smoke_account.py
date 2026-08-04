# -*- coding: utf-8 -*-
"""独立账号冒烟(本地 H2):
1) 注册 → 同号重复注册被拒 → 登录(密码错拒/对成)→ 返回本地 token
2) WS LOGIN:昵称/头像以 dz_user 为准(客户端传的昵称被忽略)
3) 建俱乐部(新参数 name+remark+avatar,名称超宽/纯数字被拒)
4) 大厅建房 rake 被强制 0;带入倍数区间 inMinRate/inMaxRate 生效;超上限被拒
"""
import asyncio, json, random, sys
import urllib.request

import websockets

BASE = "http://127.0.0.1:9100"
WS = "ws://127.0.0.1:9100/ws/dzpk"
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with _opener.open(req, timeout=10) as r:
        return json.loads(r.read().decode())


async def recv_until(ws, t, timeout=10):
    while True:
        m = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if m.get("type") == t:
            return m["data"]
        if m.get("type") == 499:
            raise RuntimeError("ERROR: " + str(m["data"]))


async def main():
    phone = "137" + str(random.randint(10000000, 99999999))

    # 1) 注册(字段对标扯旋)/登录
    def reg_body(**kw):
        b = {"phone": phone, "username": "独立号", "avatar": "/assets/table/heads/head_2.png",
             "password": "abc123", "confirmPassword": "abc123", "registerDevice": 3}
        b.update(kw)
        return b

    r = post("/api/auth/register", reg_body(password="123456", confirmPassword="123456"))
    assert r["code"] == 1, "纯数字密码应被拒: " + str(r)
    r = post("/api/auth/register", reg_body(confirmPassword="abc124"))
    assert r["code"] == 1, "两次密码不一致应被拒"
    r = post("/api/auth/register", reg_body(username="12345"))
    assert r["code"] == 1, "纯数字昵称应被拒"
    r = post("/api/auth/register", reg_body(avatar=""))
    assert r["code"] == 1, "缺头像应被拒"
    reg = post("/api/auth/register", reg_body())
    assert reg["code"] == 0 and reg["token"] and reg["numberId"], reg
    dup = post("/api/auth/register", reg_body())
    assert dup["code"] == 1, "重复注册应被拒: " + str(dup)
    bad = post("/api/auth/login", {"phone": phone, "password": "wrong1x"})
    assert bad["code"] == 1, "错密码应被拒"
    login = post("/api/auth/login", {"phone": phone, "password": "abc123"})
    assert login["code"] == 0 and login["userId"] == reg["userId"], login
    print("[1] 注册字段校验/重复拒/登录 OK, userId =", login["userId"],
          "numberId =", login["numberId"], "avatar =", login["avatar"])

    async with websockets.connect(WS, proxy=None) as ws:
        # 2) WS 登录:传个假昵称,应回库里的"独立号"+注册时选的头像
        await ws.send(json.dumps({"type": 401, "data": {"token": login["token"], "nickname": "假昵称"}}))
        d = await recv_until(ws, 451)
        assert d["nickname"] == "独立号", d
        assert d.get("avatar") == "/assets/table/heads/head_2.png", d
        print("[2] WS登录昵称/头像以库为准 OK:", d["nickname"], d["avatar"])

        # 3) 建俱乐部:纯数字名/缺简介被拒 → 正常成功
        async def club_create(payload):
            await ws.send(json.dumps({"type": 420, "data": payload}))
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 10))
                if m.get("type") == 480:
                    return True, m["data"]
                if m.get("type") == 499:
                    return False, m["data"]

        ok, r = await club_create({"name": "123456", "remark": "x", "avatar": "a.png"})
        assert not ok, "纯数字名应被拒"
        ok, r = await club_create({"name": "德州之家", "avatar": "a.png"})
        assert not ok, "缺简介应被拒"
        ok, club = await club_create({"name": "德州之家", "remark": "独立账号冒烟", "avatar": "/assets/table/heads/head_1.png"})
        assert ok, club
        print("[3] 建俱乐部(新参数) OK: clubNo =", club["clubNo"])

        # 4) 大厅建房:rake 传 5 应被强制 0;倍数区间 2~6 生效;超上限 9 被拒
        async def room_create(payload):
            await ws.send(json.dumps({"type": 403, "data": payload}))
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 10))
                if m.get("type") == 453:
                    return True, m["data"]
                if m.get("type") == 499:
                    return False, m["data"]

        ok, r = await room_create({"name": "烟测", "sb": 100, "settleTimeMins": 30,
                                   "rakePercent": 5, "inMinRate": 2, "inMaxRate": 6})
        assert ok, r
        assert r["rakePercent"] == 0, "大厅房 rake 应强制 0: " + str(r)
        assert r["inMinRate"] == 2 and r["inMaxRate"] == 6, r
        print("[4] 大厅房 rake 强制0 / 倍数区间 OK: minBuyin =", r["minBuyin"], "maxBuyin =", r["maxBuyin"])

        ok, r = await room_create({"name": "超限", "sb": 100, "settleTimeMins": 30, "inMaxRate": 9})
        assert not ok, "带入倍数 9 应被拒(上限 8): " + str(r)
        print("[5] 倍数超上限被拒 OK:", r["msg"])

    print("ACCOUNT SMOKE ALL OK")


asyncio.run(main())
