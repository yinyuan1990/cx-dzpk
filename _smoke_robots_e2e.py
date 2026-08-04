# -*- coding: utf-8 -*-
"""端到端冒烟(生产):建俱乐部房 → 真人B坐下 → 管理台一键生成机器人 → 机器人入座并开局"""
import json, time, random, struct, socket, base64, urllib.request

HOST, PORT = "47.122.115.33", 19100
BASE = f"http://{HOST}:{PORT}"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http(path, body, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Admin-Token"] = token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers)
    return json.loads(opener.open(req, timeout=15).read())


def http_get(path, token):
    req = urllib.request.Request(BASE + path, headers={"X-Admin-Token": token})
    return json.loads(opener.open(req, timeout=15).read())


class Sock:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT), timeout=15)
        key = base64.b64encode(b"0123456789abcdef").decode()
        self.s.send((f"GET /ws/dzpk HTTP/1.1\r\nHost: {HOST}:{PORT}\r\nUpgrade: websocket\r\n"
                     f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        hdr = b""
        while b"\r\n\r\n" not in hdr:
            hdr += self.s.recv(1)
        self.buf = b""
        self.seq = 0

    def send(self, t, data, room=0):
        self.seq += 1
        payload = json.dumps({"type": t, "sequence": self.seq, "roomId": room, "data": data}).encode()
        n = len(payload)
        mask = bytes(random.randrange(256) for _ in range(4))
        if n < 126:
            frame = struct.pack("!BB", 0x81, 0x80 | n)
        elif n < 65536:
            frame = struct.pack("!BBH", 0x81, 0x80 | 126, n)
        else:
            frame = struct.pack("!BBQ", 0x81, 0x80 | 127, n)
        frame += mask + bytes(payload[i] ^ mask[i % 4] for i in range(n))
        self.s.send(frame)

    def recv_msg(self, timeout=10):
        self.s.settimeout(timeout)
        while True:
            while len(self.buf) < 2:
                self.buf += self.s.recv(4096)
            b1, b2 = self.buf[0], self.buf[1]
            ln = b2 & 0x7F
            off = 2
            if ln == 126:
                while len(self.buf) < 4:
                    self.buf += self.s.recv(4096)
                ln = struct.unpack("!H", self.buf[2:4])[0]
                off = 4
            elif ln == 127:
                while len(self.buf) < 10:
                    self.buf += self.s.recv(4096)
                ln = struct.unpack("!Q", self.buf[2:10])[0]
                off = 10
            while len(self.buf) < off + ln:
                self.buf += self.s.recv(65536)
            payload = self.buf[off:off + ln]
            self.buf = self.buf[off + ln:]
            if (b1 & 0x0F) == 1:
                return json.loads(payload)

    def wait(self, t, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            m = self.recv_msg(max(0.5, end - time.time()))
            if m["type"] == t:
                return m
            if m["type"] == 499:
                raise AssertionError("ERROR: " + json.dumps(m.get("data"), ensure_ascii=False))
        raise AssertionError(f"等不到 {t}")


def new_account(nick):
    phone = "139" + str(random.randrange(10 ** 8)).zfill(8)
    r = http("/api/auth/register", {"phone": phone, "username": nick, "avatar": "/assets/a.png",
                                    "password": "abc123", "confirmPassword": "abc123", "registerDevice": 3})
    assert r.get("code") == 0, r
    r = http("/api/auth/login", {"phone": phone, "password": "abc123"})
    assert r.get("code") == 0, r
    return r["token"]


def ws_login(token):
    sk = Sock()
    sk.send(401, {"token": token})
    res = sk.wait(451)
    return sk, res["data"]


ok = 0


def check(name, cond, extra=""):
    global ok
    assert cond, f"FAIL {name} {extra}"
    ok += 1
    print("PASS", name, extra)


ROBOT_ID_BASE = 800_000_001

# ===== 管理台登录 =====
adm = http("/api/admin/login", {"password": "dz@admin2026"})
assert adm.get("code") == 0, adm
tok = adm["token"]

# ===== 账号:群主A、成员B =====
tokA = new_account("机测群主")
tokB = new_account("机测成员")
a, infoA = ws_login(tokA)
b, infoB = ws_login(tokB)

# A 充钻石(周期扣钻要求群主有钻)
r = http("/api/admin/users/diamond", {"userId": infoA["userId"], "amount": 1000, "remark": "robot-smoke"}, tok)
assert r.get("code") == 0, r

# ===== A 建俱乐部,B 加入并上分 =====
a.send(420, {"name": "机测", "remark": "机器人测试俱乐部", "avatar": "http://x/a.jpg"})
club = a.wait(480)["data"]
clubId = club["clubId"]
b.send(422, {"code": club["clubNo"]})
b.wait(482)
a.send(423, {"clubId": clubId})
reqs = a.wait(483)["data"]["requests"]
a.send(424, {"clubId": clubId, "requestId": reqs[0]["requestId"], "approve": True})
a.wait(484)
a.send(430, {"clubId": clubId, "op": "ownerAdd", "userId": 0, "amount": 100000})
a.wait(486)
a.send(430, {"clubId": clubId, "op": "distribute", "userId": infoB["userId"], "amount": 60000})
a.wait(486)

# ===== A 建俱乐部房(6人),B 进房坐下带入 =====
a.send(403, {"clubId": clubId, "sb": 100, "maxPlayers": 6, "settleTimeMins": 30})
room = a.wait(453)["data"]
roomId = room["roomId"]
check("建俱乐部房", roomId > 0, f"roomId={roomId}")
b.send(404, {}, roomId)
b.wait(454)
b.send(406, {"seat": 0, "buyin": 20000}, roomId)
b.wait(456)

# ===== 一键生成 2 个机器人 =====
r = http("/api/admin/robots/spawn", {"roomId": roomId, "count": 2}, tok)
check("spawn 接口", r.get("code") == 0 and r.get("spawned") == 2, json.dumps(r))

time.sleep(4)  # 等机器人坐下带入 + 自动开局

ov = http_get("/api/admin/overview", tok)
rooms = {x["roomId"]: x for x in ov.get("rooms", [])}
rm = rooms.get(roomId)
assert rm, f"overview 里没有房间 {roomId}"
bots = [p for p in rm["players"] if p["userId"] >= ROBOT_ID_BASE]
check("机器人已入座", len(bots) == 2, json.dumps(bots, ensure_ascii=False))
check("机器人已带入筹码", all(p["stack"] > 0 for p in bots), json.dumps([p["stack"] for p in bots]))
check("牌局已自动开始", rm["stage"] != "WAITING", f"stage={rm['stage']} hand={rm['handNo']}")

# robots 列表
r = http_get("/api/admin/robots", tok)
check("robots 列表", r.get("rooms", {}).get(str(roomId)) == 2, json.dumps(r))

# ===== 清场 =====
r = http("/api/admin/robots/clear", {"roomId": roomId}, tok)
check("clear 接口", r.get("code") == 0 and r.get("cleared") == 2, json.dumps(r))

# 清桌收尾:解散房间(测试残留不留在生产)
http(f"/api/admin/rooms/{roomId}/dismiss", {}, tok)
print(f"\n=== 全部 {ok} 项通过(房间已解散清理) ===")
