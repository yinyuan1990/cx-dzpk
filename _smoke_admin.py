import urllib.request, json

BASE = 'http://localhost:9100'

def get(u):
    try:
        return urllib.request.urlopen(u, timeout=5).status
    except Exception as e:
        return str(e)

def post(path, body, token=None, method='POST'):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-Admin-Token'] = token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers, method=method)
    return json.load(urllib.request.urlopen(req, timeout=5))

def getj(path, token):
    req = urllib.request.Request(BASE + path, headers={'X-Admin-Token': token})
    return json.load(urllib.request.urlopen(req, timeout=5))

print('game /       :', get(BASE + '/'))
print('admin /admin/:', get(BASE + '/admin/'))
print('login wrong  :', post('/api/admin/login', {'password': 'wrong'}))
res = post('/api/admin/login', {'password': 'dz@admin2026'})
print('login ok     :', res['code'])
tok = res['token']
cfgs = getj('/api/admin/configs', tok)
print('configs      :', cfgs['code'], len(cfgs['configs']), 'items')
tiers = [c for c in cfgs['configs'] if c['key'] == 'owner_period_diamond_tiers']
print('tiers cfg    :', tiers[0]['value'] if tiers else 'MISSING')
upd = post('/api/admin/configs',
           {'key': 'owner_period_diamond_tiers',
            'value': '[{"minutes":30,"baseScore":100,"cost":10}]'}, tok, 'PUT')
print('update tiers :', upd)
cfgs2 = getj('/api/admin/configs', tok)
t2 = [c for c in cfgs2['configs'] if c['key'] == 'owner_period_diamond_tiers'][0]
print('tiers after  :', t2['value'])
ov = getj('/api/admin/overview', tok)
print('overview     :', ov['code'], 'online=', ov['onlineCount'], 'rooms=', ov['roomCount'])
print('no-token deny:', getj('/api/admin/overview', 'bad')['code'])
