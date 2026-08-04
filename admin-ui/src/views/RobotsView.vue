<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api } from '../api'

// 机器人管理:一键往指定房间(含俱乐部房)生成机器人陪打,测试房间参数用。
//   机器人随机昵称+头像,自动坐空位、带入、按牌力打;豁免俱乐部成员/积分限制,筹码不入账。
const rooms = ref([])
const robotByRoom = ref({})
const msg = ref('')
const busy = ref(false)
let timer

// 手动指定房间号(房间列表之外也可用)
const manualRoomId = ref('')
const manualCount = ref(2)

function toast(t) {
  msg.value = t
  setTimeout(() => { msg.value = '' }, 4000)
}

async function load() {
  const [ov, rb] = await Promise.all([api.overview(), api.robots()])
  if (ov.code === 0) rooms.value = ov.rooms || []
  if (rb.code === 0) robotByRoom.value = rb.rooms || {}
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

// 每个房间行内的数量选择
const countOf = ref({})
function cntFor(roomId) {
  return countOf.value[roomId] || 2
}

onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div>
    <p class="hint">
      机器人随机昵称/头像,自动坐空位、带入、按牌力打;可进<b>俱乐部房</b>(豁免成员/积分限制,筹码不入账,方便测试房间参数)。
      真人全部离开房间后机器人自动撤场。
    </p>
    <div v-if="msg" class="msgbar">{{ msg }}</div>

    <!-- 手动按房间号生成 -->
    <div class="manual">
      <input v-model="manualRoomId" placeholder="房间号" />
      <input v-model.number="manualCount" type="number" min="1" max="8" />
      <button class="btn primary" :disabled="busy || !manualRoomId" @click="spawn(manualRoomId, manualCount)">
        一键生成
      </button>
      <button class="btn danger" :disabled="busy || !manualRoomId" @click="clearBots(manualRoomId)">
        清掉机器人
      </button>
    </div>

    <!-- 活跃房间列表(直接操作) -->
    <p v-if="!rooms.length" class="empty">当前没有活跃房间。先在游戏里创建牌局,再来这里加机器人。</p>
    <div v-for="r in rooms" :key="r.roomId" class="room">
      <div class="head">
        <b>{{ r.name || '房间' + r.roomId }}</b>
        <span class="tag">#{{ r.roomId }}</span>
        <span v-if="r.clubId > 0" class="tag club">俱乐部 {{ r.clubId }}</span>
        <span class="tag">{{ r.sb }}/{{ r.bb }}</span>
        <span class="tag">{{ r.maxPlayers }}人桌</span>
        <span class="tag stage">{{ r.stage }}</span>
        <span class="tag">在座 {{ r.players.length }}/{{ r.maxPlayers }}</span>
        <span v-if="robotByRoom[String(r.roomId)]" class="tag bots">机器人 {{ robotByRoom[String(r.roomId)] }}</span>
      </div>
      <div class="ops">
        <select v-model.number="countOf[r.roomId]">
          <option v-for="n in 8" :key="n" :value="n">{{ n }} 个</option>
        </select>
        <button class="btn primary" :disabled="busy" @click="spawn(r.roomId, cntFor(r.roomId))">生成机器人</button>
        <button class="btn danger" :disabled="busy || !robotByRoom[String(r.roomId)]"
          @click="clearBots(r.roomId)">清场</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint { color: #7d8fa0; font-size: 13px; margin-bottom: 14px; line-height: 1.7; }
.hint b { color: #d4a24a; }
.msgbar {
  background: #1c2f22; border: 1px solid #2c5238; color: #8fd8a0;
  border-radius: 8px; padding: 8px 14px; font-size: 13px; margin-bottom: 12px;
}
.manual {
  display: flex; gap: 10px; align-items: center; margin-bottom: 18px;
  background: #16202b; border: 1px solid #22303d; border-radius: 10px; padding: 12px 14px;
}
.manual input {
  background: #0f1821; border: 1px solid #2a3947; color: #dbe5ee;
  border-radius: 6px; padding: 7px 10px; font-size: 14px; width: 130px;
}
.manual input[type='number'] { width: 70px; }
.btn { padding: 7px 16px; font-size: 13px; border-radius: 6px; }
.btn.primary { background: #2c5238; color: #a8e8b8; }
.btn.danger { background: #7a2e2e; color: #f0d0d0; }
.btn:disabled { opacity: 0.45; }
.room {
  background: #131d27; border: 1px solid #22303d; border-radius: 10px;
  padding: 12px 14px; margin-bottom: 12px;
}
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tag {
  font-size: 12px; background: #1c2833; color: #9fb0c0;
  padding: 2px 8px; border-radius: 10px;
}
.tag.club { color: #d4a24a; }
.tag.stage { color: #6cc06c; }
.tag.bots { color: #6cb8f0; }
.ops { display: flex; gap: 10px; align-items: center; margin-top: 10px; }
.ops select {
  background: #0f1821; border: 1px solid #2a3947; color: #dbe5ee;
  border-radius: 6px; padding: 6px 8px; font-size: 13px;
}
.empty { color: #7d8fa0; font-size: 14px; }
</style>
