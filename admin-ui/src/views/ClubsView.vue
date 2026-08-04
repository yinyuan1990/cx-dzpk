<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from '../api'

// 俱乐部管理:列表 → 点进俱乐部 → 该俱乐部的活跃房间,对房间生成/清场机器人、强制解散。
//   机器人随机昵称+头像,自动坐空位、带入、按牌力打;豁免俱乐部成员/积分限制,筹码不入账。
const clubs = ref([])
const rooms = ref([])          // overview 全部活跃房间
const robotByRoom = ref({})
const current = ref(null)      // 当前点开的俱乐部
const msg = ref('')
const busy = ref(false)
let timer

function toast(t) {
  msg.value = t
  setTimeout(() => { msg.value = '' }, 4000)
}

async function load() {
  const [cl, ov, rb] = await Promise.all([api.clubs(), api.overview(), api.robots()])
  if (cl.code === 0) clubs.value = cl.clubs || []
  if (ov.code === 0) rooms.value = ov.rooms || []
  if (rb.code === 0) robotByRoom.value = rb.rooms || {}
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
    if (res.code === 0) toast(`已生成 ${res.spawned} 个机器人(房间 ${roomId} 共 ${res.total} 个)`)
    else toast(res.msg || '生成失败')
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
    if (res.code === 0) toast(`已清掉 ${res.cleared} 个机器人(牌局中的局末落地)`)
    else toast(res.msg || '清场失败')
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
      <p class="hint">点击俱乐部查看它的牌局,对牌局生成机器人陪打(测试房间参数用)。</p>
      <p v-if="!clubs.length" class="empty">还没有俱乐部</p>
      <div v-for="c in clubs" :key="c.clubId" class="club" @click="current = c">
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
      <p class="hint">
        机器人随机昵称/头像,自动坐空位、带入、按牌力打;豁免成员/积分限制,筹码不入账,不污染俱乐部账目。真人全部离开后自动撤场。
      </p>

      <p v-if="!clubRooms.length" class="empty">该俱乐部当前没有活跃牌局。先在游戏里开一桌,这里刷新后就能加机器人。</p>
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
          <button class="btn primary" :disabled="busy" @click="spawn(r.roomId, cntFor(r.roomId))">生成机器人</button>
          <button class="btn danger" :disabled="busy || !robotByRoom[String(r.roomId)]"
            @click="clearBots(r.roomId)">清掉机器人</button>
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
</style>
