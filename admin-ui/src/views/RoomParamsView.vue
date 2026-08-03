<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'

// 牌局参数(对齐扯旋 v50/v51 配置界面):
//   上半部分:建房可选档(结算时长/盲注/思考时间/带入倍数/最短上桌/抽水%),点加号增档、点×删档
//   下半部分:扣群主钻石矩阵 —— 行=结算时长、列=盲注(大盲),格子里填钻石数。
//   行列自动跟随上面的「结算时长」「盲注」两组档位联动重排,已填的数不丢。
const toast = ref('')
const loading = ref(true)

// 六组可选档(数字数组)
const settleTimes = ref([])
const blinds = ref([]) // 存小盲;大盲恒 = ×2
const opTimes = ref([])
const maxRates = ref([])
const minTimes = ref([])
const rakePercents = ref([])
const fallbackCost = ref('5')

const LISTS = [
  { key: 'room_settle_time_options', ref: settleTimes, label: '可选结算时长(分钟)', unit: '分钟', hint: '同时决定下方矩阵的行' },
  { key: 'room_blind_options', ref: blinds, label: '可选盲注(填小盲,大盲自动×2)', unit: '', hint: '同时决定下方矩阵的列' },
  { key: 'room_op_time_options', ref: opTimes, label: '可选思考时间(秒)', unit: '秒', hint: '' },
  { key: 'room_max_rate_options', ref: maxRates, label: '可选最大带入倍数(带入上限=100大盲×倍数)', unit: '倍', hint: '' },
  { key: 'room_min_time_options', ref: minTimes, label: '可选最短上桌(分钟,0=不限)', unit: '分钟', hint: '' },
  { key: 'room_rake_percent_options', ref: rakePercents, label: '可选抽水比例(%)', unit: '%', hint: '' },
]
const newVal = ref({}) // 每组"新增档位"的输入框

// ==================== 矩阵(行=结算时长,列=大盲) ====================
// costMap key = `${minutes}_${bb}`;载入时从 owner_period_diamond_tiers JSON 解析,保存时序列化回去
const costMap = ref({})
const matrixCols = computed(() => blinds.value.map((sb) => ({ sb, bb: sb * 2 })))
const matrixRows = computed(() => settleTimes.value)

function cellKey(minutes, bb) {
  return `${minutes}_${bb}`
}

function parseList(str) {
  return String(str || '').split(/[,，\s]+/).map((s) => parseInt(s, 10)).filter((n) => !isNaN(n))
}

async function load() {
  loading.value = true
  const res = await api.configs()
  if (res.code !== 0) { loading.value = false; return }
  const byKey = Object.fromEntries(res.configs.map((c) => [c.key, String(c.value)]))
  for (const l of LISTS) l.ref.value = parseList(byKey[l.key])
  fallbackCost.value = byKey['owner_period_diamond_cost'] || '5'
  costMap.value = {}
  try {
    const tiers = JSON.parse(byKey['owner_period_diamond_tiers'] || '[]')
    if (Array.isArray(tiers)) {
      for (const t of tiers) costMap.value[cellKey(Number(t.minutes), Number(t.baseScore))] = Number(t.cost) || 0
    }
  } catch { /* 旧值损坏时从空矩阵开始 */ }
  loading.value = false
}

function addItem(l) {
  const n = parseInt(newVal.value[l.key], 10)
  if (isNaN(n) || n < 0) { showToast('请输入非负整数'); return }
  if (l.ref.value.includes(n)) { showToast('该档位已存在'); return }
  l.ref.value = [...l.ref.value, n].sort((a, b) => a - b)
  newVal.value[l.key] = ''
}

function removeItem(l, n) {
  if (l.ref.value.length <= 1) { showToast('至少保留一个档位'); return }
  l.ref.value = l.ref.value.filter((x) => x !== n)
}

async function saveAll() {
  // 六组档位 + 兜底值 + 矩阵一起存
  for (const l of LISTS) {
    const res = await api.updateConfig(l.key, l.ref.value.join(','))
    if (res.code !== 0) { showToast(`保存 ${l.key} 失败: ${res.msg || ''}`); return }
  }
  const fb = await api.updateConfig('owner_period_diamond_cost', String(parseInt(fallbackCost.value, 10) || 0))
  if (fb.code !== 0) { showToast('保存兜底值失败'); return }
  const tiers = []
  for (const minutes of matrixRows.value) {
    for (const col of matrixCols.value) {
      tiers.push({ minutes, baseScore: col.bb, cost: Number(costMap.value[cellKey(minutes, col.bb)]) || 0 })
    }
  }
  const mt = await api.updateConfig('owner_period_diamond_tiers', JSON.stringify(tiers))
  if (mt.code !== 0) { showToast('保存矩阵失败: ' + (mt.msg || '')); return }
  showToast(`已保存:${tiers.length} 个矩阵档位 + 全部可选档(立即生效)`)
}

let toastTimer
function showToast(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 3000)
}

onMounted(load)
</script>

<template>
  <div v-if="loading" class="hint">加载中…</div>
  <div v-else>
    <p class="hint">
      建房时玩家只能从这些档位里选。「结算时长」和「盲注」两组档位同时决定下方扣钻矩阵的行列,
      增删档位后矩阵自动重排(已填的钻石数不丢),改完统一点底部「保存全部」。
    </p>

    <section v-for="l in LISTS" :key="l.key" class="group">
      <h2>{{ l.label }} <span v-if="l.hint" class="sub">{{ l.hint }}</span></h2>
      <div class="chips">
        <span v-for="n in l.ref.value" :key="n" class="chip">
          {{ l.key === 'room_blind_options' ? `${n}/${n * 2}` : n + (l.unit || '') }}
          <button class="chip-x" title="删除" @click="removeItem(l, n)">×</button>
        </span>
        <input
          v-model="newVal[l.key]"
          class="chip-add"
          type="number"
          min="0"
          placeholder="新增"
          @keyup.enter="addItem(l)"
        />
        <button class="add-btn" @click="addItem(l)">+</button>
      </div>
    </section>

    <section class="group">
      <h2>扣群主钻石矩阵(结算时长 × 盲注,格子=每周期扣群主的钻石数,0=不扣)</h2>
      <p class="hint">只对俱乐部房生效:每个玩家周期到点结算时按房间的「时长+盲注」查此表扣群主钻石。没配到的组合走兜底值。</p>
      <div class="fallback">
        矩阵没匹配到时的兜底值:
        <input v-model="fallbackCost" type="number" min="0" class="fb-input" /> 钻石
      </div>
      <table v-if="matrixRows.length && matrixCols.length" class="matrix">
        <thead>
          <tr>
            <th class="corner">时长 \ 盲注</th>
            <th v-for="c in matrixCols" :key="c.bb">{{ c.sb }}/{{ c.bb }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="m in matrixRows" :key="m">
            <th>{{ m }}分钟</th>
            <td v-for="c in matrixCols" :key="c.bb">
              <input
                v-model.number="costMap[cellKey(m, c.bb)]"
                type="number"
                min="0"
                class="cell"
                placeholder="0"
              />
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="hint">矩阵为空:请先在上方配置「可选结算时长」和「可选盲注」。</p>
    </section>

    <button class="save-all" @click="saveAll">保存全部(档位 + 矩阵,立即生效)</button>
    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
.hint { color: #7d8fa0; font-size: 13px; margin-bottom: 14px; line-height: 1.6; }
.group { margin-bottom: 24px; }
h2 {
  font-size: 14px; color: #d4a24a; padding-bottom: 6px;
  border-bottom: 1px solid #22303d; margin-bottom: 10px;
}
.sub { color: #7d8fa0; font-weight: 400; font-size: 12px; margin-left: 8px; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  background: #1c2833; border: 1px solid #2d3a48; border-radius: 16px;
  padding: 5px 6px 5px 14px; font-size: 13px; color: #e6edf3;
}
.chip-x {
  background: #2d3a48; color: #9fb0c0; border-radius: 50%;
  width: 18px; height: 18px; line-height: 1; font-size: 12px; padding: 0;
}
.chip-x:hover { background: #4a2530; color: #e08a9a; }
.chip-add { width: 76px; padding: 5px 8px; }
.add-btn { background: #d4a24a; color: #1a1206; font-weight: 700; padding: 5px 12px; }
.fallback { color: #9fb0c0; font-size: 13px; margin-bottom: 12px; }
.fb-input { width: 90px; margin: 0 6px; }
.matrix { border-collapse: collapse; font-size: 13px; }
.matrix th, .matrix td { border: 1px solid #22303d; padding: 6px 8px; text-align: center; }
.matrix thead th { color: #d4a24a; background: #131d27; }
.matrix tbody th { color: #9fb0c0; background: #131d27; font-weight: 500; }
.corner { color: #7d8fa0 !important; font-weight: 400; }
.cell { width: 80px; text-align: center; }
.save-all {
  background: #d4a24a; color: #1a1206; font-weight: 700;
  padding: 10px 26px; font-size: 14px; margin-top: 6px;
}
.toast {
  position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
  background: #1c2833; border: 1px solid #d4a24a; color: #e6edf3;
  padding: 10px 20px; border-radius: 8px; font-size: 13px;
}
</style>
