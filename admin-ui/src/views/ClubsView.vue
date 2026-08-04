<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from '../api'

// 俱乐部管理(对齐扯旋):列表 → 点进俱乐部 →
//   ① 机器人池:一键生成真实机器人账号(随机昵称/头像,入会+上积分,与牌局无关)、一键补分;
//   ② 活跃牌局:从池子派机器人上桌 / 撤回 / 强制解散。带入扣机器人自己的俱乐部积分,全程真人流程。
const clubs = ref([])
const rooms = ref([])          // overview 全部活跃房间
const robotByRoom = ref({})
const current = ref(null)      // 当前点开的俱乐部
const msg = ref('')
const busy = ref(false)
let timer

// 机器人池(当前俱乐部)
const pool = ref([])
const genCount = ref(4)
const genScore = ref(1000000)  // 初始积分(分):默认 1 万元
const topupAmount = ref(1000000)

function toast(t) {
  msg.value = t
  setTimeout(() => { msg.value = '' }, 4000)
}

async function load() {
  const [cl, ov, rb] = await Promise.all([api.clubs(), api.overview(), api.robots()])
  if (cl.code === 0) clubs.value = cl.clubs || []
  if (ov.code === 0) rooms.value = ov.rooms || []
  if (rb.code === 0) robotByRoom.value = rb.rooms || {}
  if (current.value) loadPool()
}

async function loadPool() {
  const res = await api.clubRobots(current.value.clubId)
  if (res.code === 0) pool.value = res.robots || []
}

async function openClub(c) {
  current.value = c
  pool.value = []
  await loadPool()
}

async function generate() {
  if (busy.value) return
  busy.value = true
  try {
    const res = await api.generateRobots(current.value.clubId, Number(genCount.value) || 1, Number(genScore.value) || 0)
    if (res.code === 0) toast(`已生成 ${res.created} 个机器人(已入会并上分)`)
    else toast(res.msg || '生成失败')
    await loadPool()
  } finally {
    busy.value = false
  }
}

async function topUp() {
  if (busy.value) return
  busy.value = true
  try {
    const res = await api.topUpRobots(current.value.clubId, Number(topupAmount.value) || 0)
    if (res.code === 0) toast(`已给 ${res.affected} 个机器人各补 ${res.amount} 积分`)
    else toast(res.msg || '补分失败')
    await loadPool()
  } finally {
    busy.value = false
  }
}

// 每个俱乐部的活跃房间数
function roomCountOf(clubId) {
  return rooms.value.filter((r) => Number(r.clubId) === Number(clubId)).length
}

// 当前俱乐部的房间
const clubRooms = computed(() => {
  if (!current.value) return []
  return rooms.value.filter((r) => Number(r.clubId) === Number(current.value.clubId))
})

// 每个房间行内的数量选择
const countOf = ref({})
function cntFor(roomId) {
  return countOf.value[roomId] || 2
}

async function spawn(roomId, count) {
  if (busy.value) return
  busy.value = true
  try {
    const res = await api.spawnRobots(Number(roomId), Number(count) || 1)
    if (res.code === 0) toast(`已派 ${res.deployed} 个机器人上桌(池内还剩 ${res.poolIdle} 个空闲)`)
    else toast(res.msg || '派桌失败')
    setTimeout(load, 800)
  } finally {
    busy.value = false
  }
}

async function clearBots(roomId) {
  if (busy.value) return
  busy.value = true
  try {
    const res = await api.clearRobots(Number(roomId))
    if (res.code === 0) toast(`已撤回 ${res.cleared} 个机器人(牌局中的局末落地,账号留在池子)`)
    else toast(res.msg || '撤回失败')
    setTimeout(load, 800)
  } finally {
    busy.value = false
  }
}

async function dismiss(r) {
  if (!confirm(`确定强制解散房间「${r.name || r.roomId}」?桌上筹码会按站起流程退还。`)) return
  await api.dismiss(r.roomId)
  toast(`房间 ${r.roomId} 已解散`)
  setTimeout(load, 800)
}

function fmtTime(ts) {
  if (!ts) return ''
  return String(ts).replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div>
    <div v-if="msg" class="msgbar">{{ msg }}</div>

    <!-- 俱乐部列表 -->
    <template v-if="!current">
      <p class="hint">点击俱乐部:先一键生成机器人(真实账号,入会+上积分,与牌局无关),再对牌局派机器人上桌。</p>
      <p v-if="!clubs.length" class="empty">还没有俱乐部</p>
      <div v-for="c in clubs" :key="c.clubId" class="club" @click="openClub(c)">
        <div class="c-main">
          <b>{{ c.name }}</b>
          <span class="tag">编号 {{ c.clubNo }}</span>
          <span class="tag">ID {{ c.clubId }}</span>
        </div>
        <div class="c-sub">
          群主:{{ c.ownerNick || c.ownerId }} · 成员 {{ c.memberCount }} 人
          · 活跃牌局 <b :class="{ hot: roomCountOf(c.clubId) > 0 }">{{ roomCountOf(c.clubId) }}</b>
          · 创建于 {{ fmtTime(c.createdAt) }}
        </div>
        <span class="arrow">›</span>
      </div>
    </template>

    <!-- 俱乐部详情:房间列表 + 机器人操作 -->
    <template v-else>
      <div class="crumb">
        <button class="back" @click="current = null">‹ 俱乐部列表</button>
        <b>{{ current.name }}</b>
        <span class="tag">编号 {{ current.clubNo }}</span>
        <span class="tag">群主 {{ current.ownerNick || current.ownerId }}</span>
        <span class="tag">成员 {{ current.memberCount }}</span>
      </div>
      <!-- 机器人池(与牌局无关,对齐扯旋:真实账号+真实成员+真实积分) -->
      <div class="pool">
        <div class="pool-head">
          <b>机器人池</b>
          <span class="tag">{{ pool.length }} 个</span>
          <span class="tag">空闲 {{ pool.filter((p) => !p.inRoom).length }}</span>
        </div>
        <div class="pool-ops">
          <select v-model.number="genCount">
            <option v-for="n in 10" :key="n" :value="n">{{ n }} 个</option>
          </select>
          <input v-model.number="genScore" type="number" min="0" title="初始积分(分)" />
          <button class="btn primary" :disabled="busy" @click="generate">一键生成机器人</button>
          <span class="gap"></span>
          <input v-model.number="topupAmount" type="number" min="1" title="补分金额(分)" />
          <button class="btn" :disabled="busy || !pool.length" @click="topUp">一键补分</button>
        </div>
        <div v-if="pool.length" class="pool-list">
          <span v-for="p in pool" :key="p.userId" class="bot" :class="{ playing: p.inRoom }"
            :title="'ID:' + p.userId + ' 积分:' + p.score">
            <img v-if="p.avatar" :src="p.avatar" />
            {{ p.nickname }}<i>{{ p.inRoom ? '在桌' : p.score }}</i>
          </span>
        </div>
        <p v-else class="empty small">还没有机器人,点上方「一键生成」。生成的是真实账号(随机昵称/头像),已入会并上积分,打牌走真人流程、带入扣自己积分。</p>
      </div>

      <p v-if="!clubRooms.length" class="empty">该俱乐部当前没有活跃牌局。先在游戏里开一桌,刷新后就能派机器人上桌。</p>
      <div v-for="r in clubRooms" :key="r.roomId" class="room">
        <div class="head">
          <b>{{ r.name || '房间' + r.roomId }}</b>
          <span class="tag">#{{ r.roomId }}</span>
          <span class="tag">{{ r.sb }}/{{ r.bb }}</span>
          <span class="tag">{{ r.maxPlayers }}人桌</span>
          <span class="tag">{{ r.settleTimeMins }}分钟</span>
          <span class="tag stage">{{ r.stage }} · 第{{ r.handNo }}手</span>
          <span class="tag">在座 {{ r.players.length }}/{{ r.maxPlayers }}</span>
          <span v-if="robotByRoom[String(r.roomId)]" class="tag bots">机器人 {{ robotByRoom[String(r.roomId)] }}</span>
        </div>
        <table v-if="r.players.length">
          <thead>
            <tr><th>座位</th><th>玩家</th><th>ID</th><th>筹码</th></tr>
          </thead>
          <tbody>
            <tr v-for="p in r.players" :key="p.userId">
              <td>{{ p.seat + 1 }}</td>
              <td>{{ p.nickname }}<span v-if="p.userId >= 800000001" class="bot-mark">[AI]</span></td>
              <td>{{ p.userId }}</td>
              <td>{{ p.stack }}</td>
            </tr>
          </tbody>
        </table>
        <div class="ops">
          <select v-model.number="countOf[r.roomId]">
            <option v-for="n in 8" :key="n" :value="n">{{ n }} 个</option>
          </select>
          <button class="btn primary" :disabled="busy" @click="spawn(r.roomId, cntFor(r.roomId))">派机器人上桌</button>
          <button class="btn danger" :disabled="busy || !robotByRoom[String(r.roomId)]"
            @click="clearBots(r.roomId)">撤回机器人</button>
          <button class="btn dark" @click="dismiss(r)">强制解散</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hint { color: #7d8fa0; font-size: 13px; margin-bottom: 14px; line-height: 1.7; }
.msgbar {
  background: #1c2f22; border: 1px solid #2c5238; color: #8fd8a0;
  border-radius: 8px; padding: 8px 14px; font-size: 13px; margin-bottom: 12px;
}
.club {
  position: relative; cursor: pointer;
  background: #131d27; border: 1px solid #22303d; border-radius: 10px;
  padding: 12px 40px 12px 14px; margin-bottom: 10px;
}
.club:hover { border-color: #3a5068; }
.c-main { display: flex; align-items: center; gap: 8px; }
.c-sub { color: #7d8fa0; font-size: 12px; margin-top: 6px; }
.c-sub .hot { color: #6cc06c; }
.arrow {
  position: absolute; right: 16px; top: 50%; transform: translateY(-50%);
  color: #566a7d; font-size: 22px;
}
.crumb { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.back { background: #1c2833; color: #9fb0c0; padding: 6px 12px; font-size: 13px; }
.tag {
  font-size: 12px; background: #1c2833; color: #9fb0c0;
  padding: 2px 8px; border-radius: 10px;
}
.tag.stage { color: #6cc06c; }
.tag.bots { color: #6cb8f0; }
.room {
  background: #131d27; border: 1px solid #22303d; border-radius: 10px;
  padding: 12px 14px; margin-bottom: 12px;
}
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
table { width: 100%; margin-top: 10px; border-collapse: collapse; font-size: 13px; }
th { text-align: left; color: #7d8fa0; font-weight: 400; padding: 4px 8px; }
td { padding: 4px 8px; border-top: 1px solid #1c2833; }
.bot-mark { margin-left: 4px; color: #6cb8f0; font-size: 11px; }
.ops { display: flex; gap: 10px; align-items: center; margin-top: 12px; }
.ops select {
  background: #0f1821; border: 1px solid #2a3947; color: #dbe5ee;
  border-radius: 6px; padding: 6px 8px; font-size: 13px;
}
.btn { padding: 7px 16px; font-size: 13px; border-radius: 6px; }
.btn.primary { background: #2c5238; color: #a8e8b8; }
.btn.danger { background: #7a2e2e; color: #f0d0d0; }
.btn.dark { background: #2d3a48; color: #cbd6e0; margin-left: auto; }
.btn:disabled { opacity: 0.45; }
.empty { color: #7d8fa0; font-size: 14px; }
.empty.small { font-size: 12px; margin: 8px 0 0; }
.pool {
  background: #16202b; border: 1px solid #2a3947; border-radius: 10px;
  padding: 12px 14px; margin-bottom: 14px;
}
.pool-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.pool-ops { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.pool-ops select, .pool-ops input {
  background: #0f1821; border: 1px solid #2a3947; color: #dbe5ee;
  border-radius: 6px; padding: 6px 8px; font-size: 13px;
}
.pool-ops input { width: 110px; }
.gap { width: 14px; }
.pool-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.bot {
  display: inline-flex; align-items: center; gap: 6px;
  background: #1c2833; border-radius: 14px; padding: 4px 10px 4px 4px;
  font-size: 12px; color: #cbd6e0;
}
.bot img { width: 22px; height: 22px; border-radius: 50%; object-fit: cover; }
.bot i { font-style: normal; color: #7d8fa0; font-size: 11px; }
.bot.playing { border: 1px solid #2c5238; }
.bot.playing i { color: #6cc06c; }
</style>
