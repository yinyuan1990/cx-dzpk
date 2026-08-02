import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建产物直接出到后端 static/admin,与游戏同端口 /admin/ 访问
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  build: {
    outDir: '../src/main/resources/static/admin',
    emptyOutDir: true,
  },
  server: {
    port: 5273,
    proxy: {
      '/api': { target: 'http://localhost:9100', changeOrigin: true },
    },
  },
})
