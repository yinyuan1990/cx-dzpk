<script setup>
import { ref } from 'vue'
import { api, setToken } from '../api'

const emit = defineEmits(['ok'])
const password = ref('')
const err = ref('')
const busy = ref(false)

async function login() {
  if (!password.value || busy.value) return
  busy.value = true
  err.value = ''
  try {
    const res = await api.login(password.value)
    if (res.code === 0) {
      setToken(res.token)
      emit('ok')
    } else {
      err.value = res.msg || '登录失败'
    }
  } catch (e) {
    err.value = '网络错误'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="card">
      <h1>德州管理后台</h1>
      <input v-model="password" type="password" placeholder="管理密码" @keyup.enter="login" />
      <button :disabled="busy" @click="login">{{ busy ? '登录中…' : '登 录' }}</button>
      <p v-if="err" class="err">{{ err }}</p>
    </div>
  </div>
</template>

<style scoped>
.login-wrap { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
.card {
  width: 320px; padding: 32px 28px; background: #16202b;
  border: 1px solid #22303d; border-radius: 12px;
  display: flex; flex-direction: column; gap: 14px;
}
h1 { font-size: 18px; text-align: center; color: #d4a24a; margin-bottom: 6px; }
input { padding: 10px 12px; font-size: 14px; }
button { background: #d4a24a; color: #1a1206; padding: 10px; font-size: 15px; font-weight: 600; }
.err { color: #ef6a6a; font-size: 13px; text-align: center; }
</style>
