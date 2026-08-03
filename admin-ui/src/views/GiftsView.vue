<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'

// 礼物配置管理(对齐扯旋 gift_config):改价/上下架/排序/扣费源
const gifts = ref([])
const editing = ref(null) // 当前编辑的礼物(拷贝);null=不在编辑
const toast = ref('')

const COST_TYPES = [
  { v: '', label: '自动(俱乐部房扣积分/其它扣带入)' },
  { v: 'CLUB_SCORE', label: '俱乐部积分(流水type18)' },
  { v: 'SCORE', label: '桌面带入筹码' },
  { v: 'DIAMOND', label: '钻石' },
]

async function load() {
  const res = await api.gifts()
  if (res.code === 0) gifts.value = res.gifts
}

function edit(g) {
  editing.value = { ...g }
}

function addNew() {
  editing.value = { id: '', giftKey: '', name: '', costScore: 100, costType: '', iconUrl: '', animKey: '', enabled: true, sortNo: gifts.value.length }
}

async function save() {
  const res = await api.saveGift(editing.value)
  if (res.code === 0) {
    showToast('已保存(立即生效)')
    editing.value = null
    load()
  } else {
    showToast(res.msg || '保存失败')
  }
}

async function toggleEnabled(g) {
  const res = await api.saveGift({ ...g, enabled: !g.enabled })
  if (res.code === 0) {
    showToast(`${g.name} 已${g.enabled ? '下架' : '上架'}`)
    load()
  } else {
    showToast(res.msg || '操作失败')
  }
}

async function del(g) {
  if (!confirm(`确定删除礼物「${g.name}」?`)) return
  const res = await api.deleteGift(g.id)
  if (res.code === 0) {
    showToast('已删除')
    load()
  }
}

function costTypeLabel(v) {
  const f = COST_TYPES.find((c) => c.v === (v || ''))
  return f ? f.label : v
}

let toastTimer
function showToast(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2500)
}

onMounted(load)
</script>

<template>
  <div>
    <p class="hint">
      giftKey 需对应前端动画键(meigui/xihongshi/zhuaji/zhadan/poshui/dianzan/kiss/buyu/motou/huojiantong)。
      改价/上下架立即生效。
    </p>
    <button class="add" @click="addNew">+ 新增礼物</button>

    <table>
      <thead>
        <tr>
          <th>排序</th><th>giftKey</th><th>名称</th><th>价格</th><th>扣费源</th><th>状态</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="g in gifts" :key="g.id" :class="{ off: !g.enabled }">
          <td>{{ g.sortNo }}</td>
          <td class="mono">{{ g.giftKey }}</td>
          <td>{{ g.name }}</td>
          <td>{{ g.costScore }}</td>
          <td class="dim">{{ costTypeLabel(g.costType) }}</td>
          <td>{{ g.enabled ? '上架' : '下架' }}</td>
          <td class="ops">
            <button @click="edit(g)">编辑</button>
            <button @click="toggleEnabled(g)">{{ g.enabled ? '下架' : '上架' }}</button>
            <button class="danger" @click="del(g)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="editing" class="mask" @click.self="editing = null">
      <div class="dialog">
        <h3>{{ editing.id ? '编辑礼物' : '新增礼物' }}</h3>
        <label>giftKey <input v-model="editing.giftKey" placeholder="meigui" /></label>
        <label>名称 <input v-model="editing.name" placeholder="玫瑰" /></label>
        <label>价格 <input v-model.number="editing.costScore" type="number" min="0" /></label>
        <label>扣费源
          <select v-model="editing.costType">
            <option v-for="c in COST_TYPES" :key="c.v" :value="c.v">{{ c.label }}</option>
          </select>
        </label>
        <label>动画键(留空=同 giftKey) <input v-model="editing.animKey" /></label>
        <label>排序 <input v-model.number="editing.sortNo" type="number" /></label>
        <div class="btns">
          <button class="save" @click="save">保存</button>
          <button @click="editing = null">取消</button>
        </div>
      </div>
    </div>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
.hint { color: #7d8fa0; font-size: 13px; margin-bottom: 14px; }
.add { background: #d4a24a; color: #1a1206; padding: 7px 16px; font-weight: 600; margin-bottom: 12px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { text-align: left; color: #7d8fa0; font-weight: 500; padding: 6px 10px; border-bottom: 1px solid #22303d; }
td { padding: 8px 10px; color: #e6edf3; }
tbody tr:nth-child(even) { background: #131d27; }
tr.off td { color: #5c6b7a; }
.mono { font-family: Consolas, monospace; }
.dim { color: #9fb0c0; }
.ops { display: flex; gap: 6px; }
.ops button { background: #2d3a48; color: #cbd6e0; padding: 4px 10px; font-size: 12px; }
.ops .danger { background: #4a2530; color: #e08a9a; }
.mask {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55);
  display: flex; align-items: center; justify-content: center; z-index: 10;
}
.dialog {
  background: #17222d; border: 1px solid #22303d; border-radius: 12px;
  padding: 22px 26px; width: 380px; display: flex; flex-direction: column; gap: 10px;
}
h3 { color: #d4a24a; font-size: 15px; margin-bottom: 4px; }
label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #7d8fa0; }
input, select { width: 100%; }
.btns { display: flex; gap: 10px; margin-top: 8px; }
.save { background: #d4a24a; color: #1a1206; font-weight: 600; padding: 7px 18px; }
.btns button:not(.save) { background: #2d3a48; color: #cbd6e0; padding: 7px 14px; }
.toast {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  background: #1c2833; border: 1px solid #d4a24a; color: #e6edf3;
  padding: 10px 20px; border-radius: 8px; font-size: 13px;
}
</style>
