import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const routes = [
  { path: '/login', component: () => import('./views/AuthView.vue'), meta: { public: true } },
  {
    path: '/', component: () => import('./layout/AppLayout.vue'), redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('./views/DashboardView.vue'), meta: { title: '学习总览' } },
      { path: 'onboarding', component: () => import('./views/ProfileView.vue'), meta: { title: '学习画像' } },
      { path: 'goals', component: () => import('./views/GoalsView.vue'), meta: { title: '目标与项目' } },
      { path: 'plans/:id?', component: () => import('./views/PlansView.vue'), meta: { title: 'Agent 计划' } },
      { path: 'today', component: () => import('./views/TodayView.vue'), meta: { title: '今日执行' } },
      { path: 'knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { title: '知识库' } },
      { path: 'qa', component: () => import('./views/QaView.vue'), meta: { title: '知识问答' } },
      { path: 'assessments', component: () => import('./views/AssessmentView.vue'), meta: { title: '评估与错题' } },
      { path: 'analytics', component: () => import('./views/AnalyticsView.vue'), meta: { title: '学习分析' } },
      { path: 'settings', component: () => import('./views/SettingsView.vue'), meta: { title: '账户设置' } },
      { path: 'admin', component: () => import('./views/AdminView.vue'), meta: { title: '系统管理', admin: true } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.authenticated) return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  if (to.path === '/login' && auth.authenticated) return '/dashboard'
  if (to.meta.admin && !auth.isAdmin) return '/dashboard'
  document.title = `${to.meta.title || '知序'} · 自适应学习`
})
export default router
