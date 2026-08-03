<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'

const configs = ref([])
const edits = ref({})
const saving = ref({})
const toast = ref('')

// 「牌局参数」页有专用 UI 的键不在这里裸展示(建房档位 + 扣钻矩阵JSON)
const HIDDEN_KEYS = new Set(['owner_period_diamond_tiers', 'owner_period_diamond_cost'])
const HIDDEN_GROUPS = new Set(['建房参数'])

const groups = computed(() => {
  const map = {}
  for (const c of configs.value) {
    if (HIDDEN_KEYS.has(c.key) || HIDDEN_GROUPS.has(c.group)) continue
    ;(map[c.group] ||= []).push(c)
  }
  return map
})

async function load() {
  const res = await api.configs()
  if (res.code === 0) {
    configs.value = res.configs
    edits.value = Object.fromEntries(res.configs.map((c) => [c.key, String(c.value)]))
  }
}

async function save(c) {
  const val = edits.value[c.key]
  if (val === String(c.value)) return
  saving.value[c.key] = true
  try {
    const res = await api.updateConfig(c.key, val)
    if (res.code === 0) {
      c.value = val
      showToast(`已保存 ${c.key} = ${val}(立即生效)`)
    } else {
      showToast(res.msg || '保存失败')
    }
  } finally {
    saving.value[c.key] = false
  }
}

function reset(c) {
  edits.value[c.key] = String(c.def)
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
    <p class="hint">改完点「保存」立即生效,无需重启。「恢复默认」只回填输入框,仍需保存。</p>
    <section v-for="(items, group) in groups" :key="group" class="group">
      <h2>{{ group }}</h2>
      <div v-for="c in items" :key="c.key" class="row" :class="{ dirty: edits[c.key] !== String(c.value) }">
        <div class="meta">
          <div class="key">{{ c.key }}</div>
          <div class="remark">{{ c.remark }}</div>
        </div>
        <input v-model="edits[c.key]" :title="'默认: ' + c.def" />
        <button class="save" :disabled="saving[c.key] || edits[c.key] === String(c.value)" @click="save(c)">保存</button>
        <button class="def" @click="reset(c)">恢复默认</button>
      </div>
    </section>
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
.hint { color: #7d8fa0; font-size: 13px; margin-bottom: 14px; }
.group { margin-bottom: 22px; }
h2 {
  font-size: 14px; color: #d4a24a; padding-bottom: 6px;
  border-bottom: 1px solid #22303d; margin-bottom: 10px;
}
.row {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px; border-radius: 8px;
}
.row:nth-child(even) { background: #131d27; }
.row.dirty { outline: 1px solid #d4a24a55; }
.meta { flex: 1; min-width: 0; }
.key { font-size: 13px; font-family: Consolas, monospace; color: #e6edf3; }
.remark { font-size: 12px; color: #7d8fa0; margin-top: 2px; }
input { width: 300px; }
.save { background: #d4a24a; color: #1a1206; padding: 6px 14px; font-weight: 600; }
.save:disabled { background: #2d3a48; color: #7d8fa0; cursor: default; }
.def { background: transparent; color: #7d8fa0; padding: 6px 8px; }
.toast {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  background: #1c2833; border: 1px solid #d4a24a; color: #e6edf3;
  padding: 10px 20px; border-radius: 8px; font-size: 13px;
}
</style>
