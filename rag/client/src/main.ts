import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import LabView from './features/lab/LabView.vue'
import './style.css'

const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: LabView }, { path: '/:pathMatch(.*)*', redirect: '/' }] })
createApp(App).use(createPinia()).use(router).mount('#app')
