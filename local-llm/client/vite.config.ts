import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
export default defineConfig({ plugins: [vue()], server: { port: 8483, proxy: { '/api': 'http://localhost:8482' } }, test: { environment: 'jsdom', include: ['src/**/*.test.ts'] } })
