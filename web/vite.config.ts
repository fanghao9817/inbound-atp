import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  server: {
    // local dev: talk to the deployed API (or set VITE_API_TARGET=http://localhost:8080)
    proxy: { '/api': { target: process.env.VITE_API_TARGET ?? 'https://demo.haoyufang.dev', changeOrigin: true } },
  },
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
