const BASE = '/api/admin'

let token = sessionStorage.getItem('dz_admin_token') || ''

export function setToken(t) {
  token = t
  sessionStorage.setItem('dz_admin_token', t)
}

export function hasToken() {
  return !!token
}

export function clearToken() {
  token = ''
  sessionStorage.removeItem('dz_admin_token')
}

async function req(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-Admin-Token': token,
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = await res.json()
  if (data.code === 401) {
    clearToken()
    location.reload()
    throw new Error('登录过期')
  }
  return data
}

export const api = {
  login: (password) => req('POST', '/login', { password }),
  configs: () => req('GET', '/configs'),
  updateConfig: (key, value) => req('PUT', '/configs', { key, value }),
  overview: () => req('GET', '/overview'),
  dismiss: (roomId) => req('POST', `/rooms/${roomId}/dismiss`),
  gifts: () => req('GET', '/gifts'),
  saveGift: (gift) => req('PUT', '/gifts', gift),
  deleteGift: (id) => req('DELETE', `/gifts/${id}`),
}
