# -*- coding: utf-8 -*-
"""端到端冒烟(生产):房间保留逻辑(对齐扯旋) + 机器人驻场
1. 同小盲两桌:一桌人走光自动解散,同小盲最后一桌保留;
2. 不同小盲各自保底一桌;
3. 真人离开俱乐部房,机器人继续留在桌上打(不再自动撤场)。
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
    phone = "137" + str(random.randrange(10 ** 8)).zfill(8)
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


adm = http("/api/admin/login", {"password": "dz@admin2026"})
tok = adm["token"]

# ===== 账号/俱乐部 =====
tokA = new_account("留房群主")
tokB = new_account("留房成员")
a, infoA = ws_login(tokA)
b, infoB = ws_login(tokB)
http("/api/admin/users/diamond", {"userId": infoA["userId"], "amount": 1000, "remark": "retain-smoke"}, tok)
a.send(420, {"name": "留房", "remark": "房间保留测试", "avatar": "http://x/a.jpg"})
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


def create_room(sb):
    a.send(403, {"clubId": clubId, "sb": sb, "maxPlayers": 6, "settleTimeMins": 30})
    return a.wait(453)["data"]["roomId"]


def alive_rooms():
    ov = http_get("/api/admin/overview", tok)
    return {x["roomId"] for x in ov.get("rooms", []) if x.get("clubId") == clubId}


# ===== 场景1:同小盲两桌,一桌人走光 → 自动解散;最后一桌保留 =====
r1 = create_room(100)
r2 = create_room(100)
r3 = create_room(200)
check("建3桌(同小盲x2+不同小盲x1)", r1 and r2 and r3, f"r1={r1} r2={r2} r3={r3}")

# B 坐进 r1 带入再站起(人走光,同小盲 r2 还在 → r1 应被自动解散)
b.send(404, {}, r1)
b.wait(454)
b.send(406, {"seat": 0}, r1)
b.wait(456)
b.send(407, {"amount": 20000}, r1)
time.sleep(0.8)
b.send(408, {}, r1)  # 站起
time.sleep(2.5)
rooms = alive_rooms()
check("同小盲人走光自动解散", r1 not in rooms, f"alive={sorted(rooms)}")
check("同小盲另一桌保留", r2 in rooms)
check("不同小盲桌保留", r3 in rooms)

# B 坐进 r2 带入再站起(r2 是小盲100的最后一桌 → 保留)
b.send(404, {}, r2)
b.wait(454)
b.send(406, {"seat": 0}, r2)
b.wait(456)
b.send(407, {"amount": 20000}, r2)
time.sleep(0.8)
b.send(408, {}, r2)
time.sleep(2.5)
rooms = alive_rooms()
check("同小盲最后一桌保留(人走光不拆)", r2 in rooms, f"alive={sorted(rooms)}")

# ===== 场景2:真人离开,机器人继续驻场 =====
http(f"/api/admin/clubs/{clubId}/robots/generate", {"count": 2, "initScore": 500000}, tok)
b.send(404, {}, r2)
b.wait(454)
b.send(406, {"seat": 0}, r2)
b.wait(456)
b.send(407, {"amount": 20000}, r2)
time.sleep(0.8)
r = http("/api/admin/robots/spawn", {"roomId": r2, "count": 2}, tok)
check("派2机器人", r.get("code") == 0 and r.get("deployed") == 2, json.dumps(r))
time.sleep(3)
# 真人 B 站起并离开房间(牌局中站起是局末落地,B 未弃牌时立即;这里 B 可能在牌局中 → 等一手结束)
b.send(408, {}, r2)
time.sleep(2)
b.send(405, {}, r2)  # LEAVE_ROOM
time.sleep(6)
ov = http_get("/api/admin/overview", tok)
rm = next((x for x in ov.get("rooms", []) if x["roomId"] == r2), None)
check("真人离开后房间还在", rm is not None)
bots_seated = [p for p in rm["players"]] if rm else []
check("机器人还在桌上继续打", len(bots_seated) == 2 and rm["stage"] != "WAITING",
      f"players={len(bots_seated)} stage={rm['stage'] if rm else '-'}")

# ===== 清理 =====
http("/api/admin/robots/clear", {"roomId": r2}, tok)
time.sleep(1)
for rid in (r2, r3):
    try:
        http(f"/api/admin/rooms/{rid}/dismiss", {}, tok)
    except Exception:
        pass
print(f"\n=== 全部 {ok} 项通过(已清理) ===")
