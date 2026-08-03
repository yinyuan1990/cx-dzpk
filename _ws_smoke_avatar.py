# -*- coding: utf-8 -*-
"""线上冒烟:账号 token 走 WS 登录,校验 LOGIN_RES 带 avatar;建房坐下,校验 PLAYER_SIT 带 avatar。"""
import asyncio, json, random, sys
import urllib.request

import websockets

BASE = "http://47.122.115.33:19100"
WS = "ws://47.122.115.33:19100/ws/dzpk"


_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 绕过本地系统代理


def http_post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with _opener.open(req, timeout=10) as r:
        return json.loads(r.read().decode())


async def main():
    phone = "138" + str(random.randint(10000000, 99999999))
    reg = http_post("/api/auth/register", {"phone": phone, "password": "test1234", "nickname": "WS头像烟测"})
    assert reg.get("code") == 0, reg
    token = reg["token"]

    async with websockets.connect(WS, proxy=None) as ws:
        await ws.send(json.dumps({"type": 401, "data": {"token": token, "nickname": "WS头像烟测"}}))
        login = None
        while login is None:
            m = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if m.get("type") == 451:
                login = m["data"]
        print("LOGIN_RES keys:", sorted(login.keys()))
        assert "avatar" in login, "LOGIN_RES 缺 avatar"
        print("LOGIN_RES avatar:", repr(login["avatar"]), "nickname:", login["nickname"])

        # 建房(大厅)→ 进房 → 坐下,校验 PLAYER_SIT 带 avatar
        await ws.send(json.dumps({"type": 403, "data": {"name": "烟测", "sb": 100, "settleTimeMins": 30}}))
        room_id = None
        while room_id is None:
            m = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if m.get("type") == 453:
                assert m["data"].get("roomId"), m
                room_id = m["data"]["roomId"]
            elif m.get("type") == 499:
                print("ERROR:", m["data"]); sys.exit(1)
        await ws.send(json.dumps({"type": 404, "roomId": room_id, "data": {}}))
        while True:
            m = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if m.get("type") == 454:
                break
        await ws.send(json.dumps({"type": 406, "roomId": room_id, "data": {"seat": 0}}))
        while True:
            m = json.loads(await asyncio.wait_for(ws.recv(), 10))
            if m.get("type") == 456:
                d = m["data"]
                print("PLAYER_SIT keys:", sorted(d.keys()))
                assert "avatar" in d, "PLAYER_SIT 缺 avatar"
                print("PLAYER_SIT avatar:", repr(d["avatar"]))
                break
            if m.get("type") == 499:
                print("ERROR:", m["data"]); sys.exit(1)
        print("SMOKE OK")


asyncio.run(main())
