<script setup>
import { ref, onMounted } from 'vue'
import { hasToken, clearToken } from './api'
import LoginView from './views/LoginView.vue'
import ConfigView from './views/ConfigView.vue'
import RoomsView from './views/RoomsView.vue'
import GiftsView from './views/GiftsView.vue'
import RoomParamsView from './views/RoomParamsView.vue'

const logged = ref(false)
const tab = ref('configs')

onMounted(() => { logged.value = hasToken() })

function logout() {
  clearToken()
  logged.value = false
}
</script>

<template>
  <LoginView v-if="!logged" @ok="logged = true" />
  <div v-else class="layout">
    <header class="topbar">
      <div class="brand">德州管理后台</div>
      <nav>
        <button :class="{ on: tab === 'configs' }" @click="tab = 'configs'">系统参数</button>
        <button :class="{ on: tab === 'roomParams' }" @click="tab = 'roomParams'">牌局参数</button>
        <button :class="{ on: tab === 'gifts' }" @click="tab = 'gifts'">礼物配置</button>
        <button :class="{ on: tab === 'rooms' }" @click="tab = 'rooms'">房间监控</button>
      </nav>
      <button class="logout" @click="logout">退出</button>
    </header>
    <main>
      <ConfigView v-if="tab === 'configs'" />
      <RoomParamsView v-else-if="tab === 'roomParams'" />
      <GiftsView v-else-if="tab === 'gifts'" />
      <RoomsView v-else />
    </main>
  </div>
</template>

<style scoped>
.layout { max-width: 1100px; margin: 0 auto; padding: 0 16px 40px; }
.topbar {
  display: flex; align-items: center; gap: 24px;
  padding: 14px 0; border-bottom: 1px solid #22303d; margin-bottom: 18px;
}
.brand { font-size: 17px; font-weight: 700; color: #d4a24a; }
nav { display: flex; gap: 8px; flex: 1; }
nav button {
  background: transparent; color: #9fb0c0; padding: 7px 16px; font-size: 14px;
}
nav button.on { background: #1c2833; color: #fff; }
.logout { background: #2d3a48; color: #cbd6e0; padding: 6px 14px; }
</style>
