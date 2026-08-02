<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api } from '../api'

const data = ref({ onlineCount: 0, roomCount: 0, rooms: [] })
let timer

async function load() {
  const res = await api.overview()
  if (res.code === 0) data.value = res
}

async function dismiss(room) {
  if (!confirm(`确定强制解散房间「${room.name || room.roomId}」?桌上筹码会按站起流程退还。`)) return
  await api.dismiss(room.roomId)
  setTimeout(load, 500)
}

onMounted(() => {
  load()
  timer = setInterval(load, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div>
    <div class="stats">
      <div class="stat"><span>在线连接</span><b>{{ data.onlineCount }}</b></div>
      <div class="stat"><span>活跃房间</span><b>{{ data.roomCount }}</b></div>
    </div>
    <p v-if="!data.rooms.length" class="empty">当前没有活跃房间</p>
    <div v-for="r in data.rooms" :key="r.roomId" class="room">
      <div class="head">
        <b>{{ r.name || '房间' + r.roomId }}</b>
        <span class="tag">#{{ r.roomId }}</span>
        <span v-if="r.clubId > 0" class="tag club">俱乐部 {{ r.clubId }}</span>
        <span class="tag">{{ r.sb }}/{{ r.bb }}</span>
        <span class="tag">{{ r.maxPlayers }}人桌</span>
        <span class="tag">{{ r.settleTimeMins }}分钟结算</span>
        <span class="tag stage">{{ r.stage }} · 第{{ r.handNo }}手</span>
        <button class="dismiss" @click="dismiss(r)">强制解散</button>
      </div>
      <table v-if="r.players.length">
        <thead>
          <tr><th>座位</th><th>玩家</th><th>ID</th><th>筹码</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in r.players" :key="p.userId">
            <td>{{ p.seat + 1 }}</td>
            <td>{{ p.nickname }}</td>
            <td>{{ p.userId }}</td>
            <td>{{ p.stack }}</td>
            <td>
              <span v-if="p.offline" class="bad">断线</span>
              <span v-else-if="p.sittingOut" class="warn">暂离</span>
              <span v-else class="ok">在座</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty small">暂无人入座(旁观 {{ r.memberCount }} 人)</p>
    </div>
  </div>
</template>

<style scoped>
.stats { display: flex; gap: 14px; margin-bottom: 18px; }
.stat {
  background: #16202b; border: 1px solid #22303d; border-radius: 10px;
  padding: 12px 22px; display: flex; flex-direction: column; gap: 4px;
}
.stat span { font-size: 12px; color: #7d8fa0; }
.stat b { font-size: 22px; color: #d4a24a; }
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
.dismiss { margin-left: auto; background: #7a2e2e; color: #f0d0d0; padding: 5px 12px; }
table { width: 100%; margin-top: 10px; border-collapse: collapse; font-size: 13px; }
th { text-align: left; color: #7d8fa0; font-weight: 400; padding: 4px 8px; }
td { padding: 4px 8px; border-top: 1px solid #1c2833; }
.ok { color: #6cc06c; }
.warn { color: #d4a24a; }
.bad { color: #ef6a6a; }
.empty { color: #7d8fa0; font-size: 14px; }
.empty.small { font-size: 12px; margin-top: 8px; }
</style>
