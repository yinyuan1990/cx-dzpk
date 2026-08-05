# -*- coding: utf-8 -*-
"""端到端冒烟(生产):机器人参数两层(俱乐部+房间覆盖) + 控盘
1. 俱乐部机器人参数读默认 → 保存 → 读回;
2. 建房派机器人 → 房间参数生效值(俱乐部默认) → 设房间覆盖 → 生效值变(来源=房间覆盖);
3. 控盘开启(放水模式) → 打几手 → 账本有记账(ledgerNet/handCount 变化)。
"""
import json, time, random, struct, socket, base64, urllib.request

HOST, PORT = "47.122.115.33", 19100
BASE = f"http://{HOST}:{PORT}"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http(path, body, token=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Admin-Token"] = token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers, method=method)
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
    phone = "136" + str(random.randrange(10 ** 8)).zfill(8)
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


tok = http("/api/admin/login", {"password": "dz@admin2026"})["token"]

# ===== 账号/俱乐部 =====
tokA = new_account("控盘群主")
tokB = new_account("控盘成员")
a, infoA = ws_login(tokA)
b, infoB = ws_login(tokB)
http("/api/admin/users/diamond", {"userId": infoA["userId"], "amount": 1000, "remark": "params-smoke"}, tok)
a.send(420, {"name": "控盘", "remark": "参数控盘测试", "avatar": "http://x/a.jpg"})
club = a.wait(480)["data"]
clubId = club["clubId"]
b.send(422, {"code": club["clubNo"]})
b.wait(482)
a.send(423, {"clubId": clubId})
reqs = a.wait(483)["data"]["requests"]
a.send(424, {"clubId": clubId, "requestId": reqs[0]["requestId"], "approve": True})
a.wait(484)
a.send(430, {"clubId": clubId, "op": "ownerAdd", "userId": 0, "amount": 500000})
a.wait(486)
a.send(430, {"clubId": clubId, "op": "distribute", "userId": infoB["userId"], "amount": 300000})
a.wait(486)

# ===== 1. 俱乐部机器人参数:默认 → 保存 → 读回 =====
r = http_get(f"/api/admin/clubs/{clubId}/robot-config", tok)
cfg = r["config"]
check("参数默认值", cfg["min_action_delay_ms"] == 800 and cfg["profit_enabled"] == 0
      and cfg["profit_mode"] == "absolute", json.dumps(cfg)[:120])

cfg["min_action_delay_ms"] = 300
cfg["max_action_delay_ms"] = 900
cfg["profit_enabled"] = 1
cfg["profit_mode"] = "absolute"
cfg["profit_target"] = -100000       # 放水 10 万分给真人
cfg["profit_adjust_strength"] = 80
r = http(f"/api/admin/clubs/{clubId}/robot-config", cfg, tok, method="PUT")
check("参数保存", r.get("code") == 0, json.dumps(r))
r = http_get(f"/api/admin/clubs/{clubId}/robot-config", tok)
check("参数读回", r["config"]["min_action_delay_ms"] == 300 and r["config"]["profit_enabled"] == 1
      and r["config"]["profit_target"] == -100000)

# ===== 2. 建房派机器人;房间覆盖 =====
http(f"/api/admin/clubs/{clubId}/robots/generate", {"count": 2, "initScore": 500000}, tok)
a.send(403, {"clubId": clubId, "sb": 100, "maxPlayers": 6, "settleTimeMins": 30})
roomId = a.wait(453)["data"]["roomId"]
b.send(404, {}, roomId)
b.wait(454)
b.send(406, {"seat": 0}, roomId)
b.wait(456)
b.send(407, {"amount": 40000}, roomId)
time.sleep(0.8)
r = http("/api/admin/robots/spawn", {"roomId": roomId, "count": 2}, tok)
check("派2机器人", r.get("code") == 0 and r.get("deployed") == 2, json.dumps(r))

r = http_get(f"/api/admin/rooms/{roomId}/robot-params", tok)
p = r["params"]
check("房间生效值=俱乐部配置", p["min_action_delay_ms"]["value"] == 300
      and p["min_action_delay_ms"]["source"] == "俱乐部默认")
check("控盘状态可读", r["profit"]["enabled"] is True and r["profit"]["target"] == -100000,
      json.dumps(r["profit"]))

r = http(f"/api/admin/rooms/{roomId}/robot-params", {"min_action_delay_ms": 150}, tok, method="PUT")
check("设置房间覆盖", r.get("code") == 0 and r["params"]["min_action_delay_ms"]["value"] == 150
      and r["params"]["min_action_delay_ms"]["source"] == "房间覆盖")

# ===== 3. 控盘记账:打几手看账本动 =====
time.sleep(25)  # 机器人自动打(延迟已调快),等几手结算
r = http_get(f"/api/admin/rooms/{roomId}/robot-params", tok)
pf = r["profit"]
check("控盘已记账", pf["handCount"] > 0, json.dumps(pf))
print("控盘账本:", json.dumps(pf, ensure_ascii=False))

# ===== 清理 =====
http("/api/admin/robots/clear", {"roomId": roomId}, tok)
time.sleep(1)
http(f"/api/admin/rooms/{roomId}/dismiss", {}, tok)
print(f"\n=== 全部 {ok} 项通过(已清理) ===")
