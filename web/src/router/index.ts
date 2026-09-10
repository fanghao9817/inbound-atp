import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'availability', component: () => import('../views/AvailabilityView.vue') },
    { path: '/inbound', name: 'inbound', component: () => import('../views/InboundView.vue') },
    { path: '/exceptions', name: 'exceptions', component: () => import('../views/ExceptionsView.vue') },
    { path: '/lanes', name: 'lanes', component: () => import('../views/LanesView.vue') },
  ],
})

export default router
