# -*- coding: utf-8 -*-
"""冒烟(生产):建房参数端到端生效核对
建房传一组全非默认参数 → 进房快照 rules 逐一核对服务端实际生效值。
"""
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
    phone = "135" + str(random.randrange(10 ** 8)).zfill(8)
    r = http("/api/auth/register", {"phone": phone, "username": nick, "avatar": "/assets/a.png",
                                    "password": "abc123", "confirmPassword": "abc123", "registerDevice": 3})
    assert r.get("code") == 0, r
    r = http("/api/auth/login", {"phone": phone, "password": "abc123"})
    assert r.get("code") == 0, r
    return r["token"]


tok = http("/api/admin/login", {"password": "dz@admin2026"})["token"]

# 从后台配置动态取可用档位(生产档位被运维改过)
req_cfg = urllib.request.Request(BASE + "/api/admin/configs", headers={"X-Admin-Token": tok})
cfgs = json.loads(opener.open(req_cfg, timeout=10).read()).get("configs", {})


def cfg_list(key, default):
    v = None
    if isinstance(cfgs, dict):
        v = cfgs.get(key)
        if isinstance(v, dict):
            v = v.get("value")
    elif isinstance(cfgs, list):
        for item in cfgs:
            if item.get("key") == key or item.get("cfgKey") == key:
                v = item.get("value") or item.get("cfgValue")
                break
    if not v:
        return default
    try:
        return [int(x) for x in str(v).split(",") if str(x).strip()]
    except ValueError:
        return default


BLINDS = cfg_list("room_blind_options", [50, 100, 250, 500, 1000])
SETTLES = cfg_list("room_settle_time_options", [30, 45, 60, 90, 120])
SB = BLINDS[min(1, len(BLINDS) - 1)]
SETTLE = SETTLES[min(1, len(SETTLES) - 1)]
print(f"生产档位: blinds={BLINDS} settles={SETTLES} → 用 sb={SB}, settle={SETTLE}")
tokA = new_account("参数核对")
a = Sock()
a.send(401, {"token": tokA})
infoA = a.wait(451)["data"]
http("/api/admin/users/diamond", {"userId": infoA["userId"], "amount": 1000, "remark": "rules-smoke"}, tok)

a.send(420, {"name": "参数", "remark": "参数核对俱乐部", "avatar": "http://x/a.jpg"})
clubId = a.wait(480)["data"]["clubId"]

# 一组全非默认参数(sb=250 档;bb=500)
req = {
    "name": "参数核对桌", "sb": SB, "maxPlayers": 6, "settleTimeMins": SETTLE,
    "rakePercent": 3, "opTimeSec": 30, "ante": SB,            # 半盲前注
    "inChip": SB * 2 * 100, "inMinRate": 2, "inMaxRate": 6,   # 带入 200BB~600BB
    "straddleOn": 1, "insuranceOn": 1, "muckOn": 1, "vpOn": 1,
    "ipLimitOn": 0, "gpsLimitOn": 1, "gameMinTime": 30, "aheadLeaveOn": 0,
    "autoStartNum": 3, "clubId": clubId,
}
a.send(403, req, 0)
roomId = a.wait(453)["data"]["roomId"]

a.send(404, {}, roomId)
snap = a.wait(454)["data"]
rules = snap.get("rules") or {}

EXPECT = {
    "sb": SB, "bb": SB * 2, "maxPlayers": 6, "settleTimeMins": SETTLE, "rakePercent": 3,
    "opTimeSec": 30, "ante": SB, "inChip": SB * 200, "inMinRate": 2, "inMaxRate": 6,
    "minBuyin": SB * 400, "maxBuyin": SB * 1200,
    "straddleOn": True, "insuranceOn": True, "muckOn": True, "vpOn": True,
    "ipLimitOn": False, "gpsLimitOn": True, "gameMinTime": 30, "aheadLeaveOn": False,
    "autoStartNum": 3,
}
bad = []
for k, want in EXPECT.items():
    got = rules.get(k)
    status = "OK " if got == want else "FAIL"
    if got != want:
        bad.append(k)
    print(f"{status} {k}: 期望 {want} / 服务端 {got}")

http(f"/api/admin/rooms/{roomId}/dismiss", {}, tok)
assert not bad, f"以下参数没生效: {bad}"
print(f"\n=== 全部 {len(EXPECT)} 项参数在服务端生效(房间已清理) ===")
