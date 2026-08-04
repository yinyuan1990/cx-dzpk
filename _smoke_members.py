# -*- coding: utf-8 -*-
"""冒烟:成员分类列表 + 机器人德州昵称一键改名"""
import json, urllib.request

op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
BASE = 'http://47.122.115.33:19100/api/admin'


def post(p, b, t=None):
    h = {'Content-Type': 'application/json'}
    if t:
        h['X-Admin-Token'] = t
    return json.loads(op.open(urllib.request.Request(BASE + p, data=json.dumps(b).encode(), headers=h), timeout=10).read())


def get(p, t):
    return json.loads(op.open(urllib.request.Request(BASE + p, headers={'X-Admin-Token': t}), timeout=10).read())


tok = post('/login', {'password': 'dz@admin2026'})['token']
clubs = get('/clubs', tok)['clubs']
target = None
for c in clubs:
    r = get(f"/clubs/{c['clubId']}/members?type=robot", tok)
    if r['code'] == 0 and r['members']:
        target = c['clubId']
        break
assert target, 'no club with robots'

r = get(f'/clubs/{target}/members?type=all', tok)
print('club', target, 'members:', r['total'], 'robots:', r['robotCount'])
assert r['total'] >= r['robotCount'] > 0

before = [m['nickname'] for m in get(f'/clubs/{target}/members?type=robot', tok)['members']]
rn = post(f'/clubs/{target}/robots/rename', {}, tok)
assert rn['code'] == 0, rn
after = [m['nickname'] for m in get(f'/clubs/{target}/members?type=robot', tok)['members']]
print('rename changed:', rn['changed'])
print('before:', before)
print('after :', after)
assert before != after and len(set(after)) == len(after)

hu = get(f'/clubs/{target}/members?type=human', tok)['members']
assert all(m['isRobot'] == 0 for m in hu)
print('human filter ok, count:', len(hu))
print('ALL PASS')
