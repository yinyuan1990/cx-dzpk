<script setup>
import { ref, onMounted } from 'vue'
import { hasToken, clearToken } from './api'
import LoginView from './views/LoginView.vue'
import ConfigView from './views/ConfigView.vue'
import RoomsView from './views/RoomsView.vue'
import GiftsView from './views/GiftsView.vue'
import RoomParamsView from './views/RoomParamsView.vue'
import UsersView from './views/UsersView.vue'
import RobotsView from './views/RobotsView.vue'

// 布局对齐扯旋管理后台:左侧深色竖排菜单栏,右侧内容区。
const logged = ref(false)
const tab = ref('configs')

const MENUS = [
  { key: 'configs', label: '系统参数', icon: '⚙' },
  { key: 'roomParams', label: '牌局参数', icon: '▦' },
  { key: 'gifts', label: '礼物配置', icon: '🎁' },
  { key: 'users', label: '用户管理', icon: '👤' },
  { key: 'rooms', label: '房间监控', icon: '👁' },
  { key: 'robots', label: '机器人', icon: '🤖' },
]

onMounted(() => { logged.value = hasToken() })

function logout() {
  clearToken()
  logged.value = false
}
</script>

<template>
  <LoginView v-if="!logged" @ok="logged = true" />
  <div v-else class="layout">
    <!-- 左侧菜单栏(扯旋风格) -->
    <aside class="sidebar">
      <div class="brand">德州管理后台</div>
      <nav>
        <button v-for="m in MENUS" :key="m.key" class="menu-item"
          :class="{ on: tab === m.key }" @click="tab = m.key">
          <span class="mi-icon">{{ m.icon }}</span>{{ m.label }}
        </button>
      </nav>
      <button class="logout" @click="logout">退出登录</button>
    </aside>

    <!-- 右侧内容区 -->
    <main class="content">
      <div class="page-title">{{ (MENUS.find((m) => m.key === tab) || {}).label }}</div>
      <ConfigView v-if="tab === 'configs'" />
      <RoomParamsView v-else-if="tab === 'roomParams'" />
      <GiftsView v-else-if="tab === 'gifts'" />
      <UsersView v-else-if="tab === 'users'" />
      <RobotsView v-else-if="tab === 'robots'" />
      <RoomsView v-else />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  min-height: 100vh;
}
.sidebar {
  width: 200px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: #141c26;
  border-right: 1px solid #22303d;
  position: sticky;
  top: 0;
  height: 100vh;
}
.brand {
  font-size: 16px;
  font-weight: 700;
  color: #d4a24a;
  padding: 20px 18px 16px;
  border-bottom: 1px solid #22303d;
}
nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 10px 0;
  overflow-y: auto;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  background: transparent;
  color: #9fb0c0;
  padding: 12px 18px;
  font-size: 14px;
  text-align: left;
  border-left: 3px solid transparent;
  border-radius: 0;
}
.menu-item:hover {
  color: #dbe5ee;
  background: #18222e;
}
.menu-item.on {
  background: #1c2833;
  color: #fff;
  border-left-color: #d4a24a;
}
.mi-icon {
  width: 20px;
  text-align: center;
}
.logout {
  margin: 14px;
  background: #2d3a48;
  color: #cbd6e0;
  padding: 8px 0;
}
.content {
  flex: 1;
  min-width: 0;
  padding: 20px 26px 40px;
  max-width: 1100px;
}
.page-title {
  font-size: 18px;
  font-weight: 700;
  color: #dbe5ee;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #22303d;
}
</style>
