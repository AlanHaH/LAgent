import { createRouter, createWebHistory, type RouteLocationGeneric } from 'vue-router'
import { useAuthStore } from './stores/auth'

const routes = [
  { path: '/', component: () => import('./views/LandingView.vue'), meta: { public: true, title: '知序' } },
  { path: '/login', component: () => import('./views/AuthView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/app', component: () => import('./layout/AppLayout.vue'), redirect: '/dashboard',
    children: [
      { path: '/dashboard', component: () => import('./views/DashboardView.vue'), meta: { title: '学习总览', learner: true } },
      { path: '/onboarding', component: () => import('./views/ProfileView.vue'), meta: { title: '学习画像', learner: true } },
      { path: '/goals', component: () => import('./views/GoalsView.vue'), meta: { title: '目标与项目', learner: true } },
      { path: '/plans/:id?', component: () => import('./views/PlansView.vue'), meta: { title: '计划', learner: true } },
      { path: '/plans/effective', component: () => import('./views/EffectivePlanView.vue'), meta: { title: '正式计划', learner: true } },
      { path: '/plans/:id/effective', component: () => import('./views/EffectivePlanView.vue'), meta: { title: '正式计划', learner: true } },
      // 旧「正式计划」地址兼容重定向
      { path: '/plan', redirect: '/plans/effective' },
      { path: '/plan/:goalId', redirect: (to: RouteLocationGeneric) => `/plans/${to.params.goalId}/effective` },
      { path: '/today', component: () => import('./views/TodayView.vue'), meta: { title: '任务', learner: true } },
      { path: '/knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { title: '知识库', learner: true } },
      { path: '/qa', component: () => import('./views/QaView.vue'), meta: { title: '知识问答', learner: true } },
      { path: '/assessments', component: () => import('./views/AssessmentView.vue'), meta: { title: '评估与错题', learner: true } },
      { path: '/analytics', component: () => import('./views/AnalyticsView.vue'), meta: { title: '学习分析', learner: true } },
      { path: '/settings', component: () => import('./views/SettingsView.vue'), meta: { title: '账户设置' } },
      { path: '/admin', component: () => import('./views/AdminView.vue'), meta: { title: '系统管理', admin: true } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.authenticated) return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  if (to.path === '/login' && auth.authenticated) return auth.isAdmin ? '/admin' : '/dashboard'
  if (to.meta.admin && !auth.isAdmin) return '/dashboard'
  if (to.meta.learner && auth.isAdmin) return '/admin'
  document.title = `${to.meta.title || '知序'} · 自适应学习`
})
export default router
