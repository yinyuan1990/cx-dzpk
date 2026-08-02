# -*- coding: utf-8 -*-
# 边缘功能 WS 冒烟:游客登录 → 建房 → 进房坐下带入 → 实时战绩 → 暂离 → 回座
import asyncio, json, sys

try:
    import websockets
except ImportError:
    print('SKIP: pip install websockets'); sys.exit(0)

URL = 'ws://localhost:9100/ws/dzpk'
seq = 0

async def send(ws, t, data, room_id=0):
    global seq
    seq += 1
    await ws.send(json.dumps({'type': t, 'roomId': room_id, 'sequence': seq, 'data': data}))

async def recv_until(ws, want, timeout=8):
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if msg.get('type') == want:
            return msg
        if msg.get('type') == 499:
            raise RuntimeError('ERROR: ' + str(msg.get('data')))

async def main():
    async with websockets.connect(URL) as ws:
        await send(ws, 401, {'guest': '冒烟员'})
        login = await recv_until(ws, 451)
        uid = login['data']['userId']
        print('login ok userId=', uid)

        await send(ws, 403, {'name': '冒烟房', 'sb': 50, 'bb': 100, 'maxPlayers': 6, 'settleTimeMins': 30})
        room = (await recv_until(ws, 453))['data']
        rid = room['roomId']
        print('room created', rid)

        await send(ws, 404, {}, rid)
        await recv_until(ws, 454)
        await send(ws, 406, {'seat': 0}, rid)
        await recv_until(ws, 456)
        await send(ws, 407, {'amount': 100 * 100}, rid)
        await recv_until(ws, 457)
        print('seated + buyin ok')

        await send(ws, 415, {}, rid)
        stats = (await recv_until(ws, 475))['data']
        me = [p for p in stats['players'] if p['userId'] == uid][0]
        assert me['bringIn'] == 10000, me
        print('realtime stats ok: bringIn=', me['bringIn'], 'profit=', me['profit'])

        await send(ws, 413, {}, rid)
        g = (await recv_until(ws, 474))['data']
        assert g['state'] in ('ON_LEAVE', 'PENDING'), g
        print('seat reserve leave ok:', g['state'], 'deadline=', g.get('deadline'))

        await send(ws, 414, {}, rid)
        g2 = (await recv_until(ws, 474))['data']
        assert g2['state'] == 'NONE', g2
        print('seat reserve resume ok')

        await send(ws, 408, {}, rid)
        su = (await recv_until(ws, 470))['data']
        print('stand up:', su)

        await send(ws, 416, {}, rid)
        await recv_until(ws, 479)
        print('dismiss ok (creator)')

    print('ALL EDGE SMOKE PASS')

asyncio.run(main())
