<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'

// 用户管理:独立账号体系(dz_user),钻石唯一来源是这里的后台充值
const users = ref([])
const q = ref('')
const msg = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  msg.value = ''
  try {
    const res = await api.users(q.value.trim())
    if (res.code !== 0) { msg.value = res.msg || '查询失败'; return }
    users.value = res.users || []
  } finally {
    loading.value = false
  }
}

const adjust = ref(null) // { user, amount, remark }
function openAdjust(u) {
  adjust.value = { user: u, amount: '', remark: '后台充值' }
}
async function confirmAdjust() {
  const a = adjust.value
  const amount = Math.floor(Number(a.amount))
  if (!amount) { msg.value = '请输入非 0 数量(负数为扣减)'; return }
  const res = await api.adjustDiamond(a.user.ID ?? a.user.id, amount, a.remark)
  if (res.code !== 0) { msg.value = res.msg || '调整失败'; return }
  adjust.value = null
  msg.value = ''
  await load()
}

function fv(u, key) {
  // H2 返回大写列名,MySQL 小写,两头兼容
  return u[key] ?? u[key.toUpperCase()] ?? ''
}

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <input v-model="q" placeholder="手机号 / 用户ID(空=最近50个)" @keyup.enter="load" />
      <button @click="load" :disabled="loading">查询</button>
      <span v-if="msg" class="msg">{{ msg }}</span>
    </div>

    <table class="tbl">
      <thead>
        <tr><th>ID</th><th>手机号</th><th>昵称</th><th>钻石</th><th>状态</th><th>注册时间</th><th>最近登录</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="u in users" :key="fv(u, 'id')">
          <td>{{ fv(u, 'id') }}</td>
          <td>{{ fv(u, 'phone') }}</td>
          <td>{{ fv(u, 'nickname') }}</td>
          <td class="diamond">{{ fv(u, 'diamond') }}</td>
          <td>{{ Number(fv(u, 'state')) === 1 ? '正常' : '封禁' }}</td>
          <td>{{ fv(u, 'created_at') }}</td>
          <td>{{ fv(u, 'last_login_at') }}</td>
          <td><button class="mini" @click="openAdjust(u)">充/扣钻</button></td>
        </tr>
        <tr v-if="!users.length"><td colspan="8" class="empty">无数据</td></tr>
      </tbody>
    </table>

    <div v-if="adjust" class="mask" @click.self="adjust = null">
      <div class="box">
        <h3>钻石调整 · {{ fv(adjust.user, 'nickname') }} (ID {{ fv(adjust.user, 'id') }})</h3>
        <p class="hint">当前钻石:{{ fv(adjust.user, 'diamond') }};正数=充值,负数=扣减</p>
        <input v-model="adjust.amount" type="number" placeholder="数量" />
        <input v-model="adjust.remark" placeholder="备注" />
        <div class="btns">
          <button @click="confirmAdjust">确定</button>
          <button class="ghost" @click="adjust = null">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bar { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; }
.bar input { width: 280px; padding: 8px 12px; background: #1c2833; border: 1px solid #2d3a48; color: #e6edf3; border-radius: 6px; }
.bar button { padding: 8px 18px; }
.msg { color: #e0a05a; font-size: 13px; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th, .tbl td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #22303d; }
.tbl th { color: #9fb0c0; font-weight: 600; }
.diamond { color: #d4a24a; font-weight: 700; }
.empty { text-align: center; color: #6b7a89; padding: 30px 0; }
.mini { padding: 4px 10px; font-size: 12px; }
.mask { position: fixed; inset: 0; background: rgba(0,0,0,.55); display: flex; align-items: center; justify-content: center; }
.box { background: #16212c; border: 1px solid #2d3a48; border-radius: 10px; padding: 22px 26px; width: 360px; display: flex; flex-direction: column; gap: 12px; }
.box h3 { margin: 0; font-size: 15px; }
.hint { margin: 0; color: #9fb0c0; font-size: 13px; }
.box input { padding: 8px 12px; background: #1c2833; border: 1px solid #2d3a48; color: #e6edf3; border-radius: 6px; }
.btns { display: flex; gap: 10px; justify-content: flex-end; }
.ghost { background: #2d3a48; }
</style>
