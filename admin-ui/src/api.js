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
  users: (q) => req('GET', '/users' + (q ? `?q=${encodeURIComponent(q)}` : '')),
  adjustDiamond: (userId, amount, remark) => req('POST', '/users/diamond', { userId, amount, remark }),
  clubs: () => req('GET', '/clubs'),
  robots: () => req('GET', '/robots'),
  clubRobots: (clubId) => req('GET', `/clubs/${clubId}/robots`),
  clubMembers: (clubId, type, page, size) =>
    req('GET', `/clubs/${clubId}/members?type=${type || 'all'}&page=${page || 0}&size=${size || 20}`),
  generateRobots: (clubId, count, initScore) => req('POST', `/clubs/${clubId}/robots/generate`, { count, initScore }),
  topUpRobots: (clubId, amount) => req('POST', `/clubs/${clubId}/robots/topup`, { amount }),
  renameRobots: (clubId) => req('POST', `/clubs/${clubId}/robots/rename`, {}),
  assignRobotAvatars: (clubId, urls) => req('POST', `/clubs/${clubId}/robots/avatars`, { urls }),
  robotConfig: (clubId) => req('GET', `/clubs/${clubId}/robot-config`),
  saveRobotConfig: (clubId, config) => req('PUT', `/clubs/${clubId}/robot-config`, config),
  roomRobotParams: (roomId) => req('GET', `/rooms/${roomId}/robot-params`),
  setRoomRobotParams: (roomId, body) => req('PUT', `/rooms/${roomId}/robot-params`, body),
  spawnRobots: (roomId, count) => req('POST', '/robots/spawn', { roomId, count }),
  clearRobots: (roomId) => req('POST', '/robots/clear', { roomId }),
}
