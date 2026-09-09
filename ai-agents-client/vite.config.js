import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5174,
    proxy: {
      '/api': { target: process.env.PYTHON_API_URL || 'http://localhost:8082', changeOrigin: true },
    },
  },
  test: { environment: 'jsdom', globals: true },
})

