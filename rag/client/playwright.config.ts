import { defineConfig } from '@playwright/test'
export default defineConfig({ testDir: './tests', timeout: 60_000, use: { baseURL: process.env.RAG_UI_URL ?? 'http://localhost:8383', channel: process.env.RAG_BROWSER_CHANNEL ?? 'chrome', screenshot: 'only-on-failure' }, reporter: 'list' })
