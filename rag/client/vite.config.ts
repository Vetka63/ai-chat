import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({ plugins: [vue()], server: { port: 8383, proxy: { '/api': 'http://localhost:8382', '/actuator': 'http://localhost:8382' } }, test: { environment: 'jsdom', include: ['src/**/*.test.ts'] } })
