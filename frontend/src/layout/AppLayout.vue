<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Collection, House, Moon, MoreFilled, Operation, Reading, Stopwatch, Sunny, TrendCharts, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { api, checkPythonAiHealth } from '../api/http'
import { isDark, toggleTheme } from '../theme'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const unread = ref(0)
const profilePromptVisible = ref(false)
const profilePromptChecking = ref(false)
const pythonAiOnline = ref<boolean | null>(null)
const pythonAiChecking = ref(false)
let pythonAiHealthTimer: number | undefined

const learnerPrimaryMenu = [
  { path: '/dashboard', label: '总览', icon: House },
  { path: '/onboarding', label: '画像', icon: User },
  { path: '/goals', label: '目标', icon: TrendCharts },
  { path: '/plans', label: '计划', icon: Operation },
  { path: '/today', label: '任务', icon: Stopwatch },
  { path: '/knowledge', label: '个人知识库', icon: Collection },
  { path: '/qa', label: '问答', icon: Reading },
]

const primaryMenu = computed(() => auth.isAdmin
  ? []
  : learnerPrimaryMenu)

const learnerMobilePaths = ['/dashboard', '/onboarding', '/goals', '/today', '/knowledge']
const mobileMenu = computed(() => auth.isAdmin
  ? [{ path: '/admin', label: '管理后台', icon: Operation }]
  : learnerMobilePaths.map((path) => learnerPrimaryMenu.find((item) => item.path === path)!))

const journeyGroups = computed(() => [
  {
    title: '建立方向',
    caption: '让 Agent 先理解你',
    items: [
      { path: '/onboarding', label: '学习画像', hint: '节奏、偏好与能力' },
      { path: '/goals', label: '目标与项目', hint: '定义下一段旅程' },
    ],
  },
  {
    title: '沉淀知识',
    caption: '把学习变成长期资产',
    items: [
      { path: '/knowledge', label: '个人知识库', hint: '资料与知识块' },
      { path: '/assessments', label: '评估与错题', hint: '验证真实掌握' },
    ],
  },
  {
    title: '个人空间',
    caption: '账户与服务设置',
    items: [
      { path: '/settings', label: '偏好设置', hint: '通知、模型与账户' },
      ...(auth.isAdmin ? [{ path: '/admin', label: '系统管理', hint: '管理员专属入口' }] : []),
    ],
  },
])

const routeTitle = computed(() => String(route.meta.title || '学习空间'))
const routeHint = computed(() => {
  if (auth.isAdmin) return '用户、内容、AI 与系统治理'
  const found = primaryMenu.value.find((item) => navMatch(item.path))
  if (found) return found.path === '/today' ? '专注于此刻的一小步' : '你的自适应学习空间'
  return '探索、成长、沉淀'
})
const homePath = computed(() => auth.isAdmin ? '/admin' : '/dashboard')
const homeLabel = computed(() => auth.isAdmin ? '返回管理后台' : '返回学习总览')

// 精确或子路径匹配：/plans 下的 AI 提案、正式生效总览/详情子路由统一高亮「计划」
function navMatch(path: string) {
  return route.path === path || route.path.startsWith(path + '/')
}

function isActive(path: string) {
  return navMatch(path)
}

function profilePromptKey() {
  return `adaptive-learning:profile-prompt-dismissed:${auth.user?.publicId || 'unknown'}`
}

function isOnProfilePage() {
  return route.path.startsWith('/onboarding')
}

async function checkProfilePrompt() {
  if (!auth.authenticated || !auth.user || auth.isAdmin || profilePromptChecking.value || isOnProfilePage()) return
  if (sessionStorage.getItem(profilePromptKey()) === '1') return
  profilePromptChecking.value = true
  try {
    const profile = await api<any | null>({ url: '/profiles/me' })
    if (!profile && !isOnProfilePage()) profilePromptVisible.value = true
    if (profile) sessionStorage.removeItem(profilePromptKey())
  } catch {
    /* 画像提示不能影响正常进入系统 */
  } finally {
    profilePromptChecking.value = false
  }
}

async function refreshApiStatus() {
  if (pythonAiChecking.value) return
  pythonAiChecking.value = true
  try {
    pythonAiOnline.value = await checkPythonAiHealth()
  } finally {
    pythonAiChecking.value = false
  }
}

function dismissProfilePrompt() {
  sessionStorage.setItem(profilePromptKey(), '1')
  profilePromptVisible.value = false
}

async function goProfile() {
  profilePromptVisible.value = false
  await router.push('/onboarding')
}

onMounted(async () => {
  await refreshApiStatus()
  pythonAiHealthTimer = window.setInterval(refreshApiStatus, 30_000)
  window.addEventListener('online', refreshApiStatus)
  window.addEventListener('offline', refreshApiStatus)
  try {
    await auth.hydrate()
    await checkProfilePrompt()
    const page = await api<any>({ url: '/notifications', params: { unread: true, pageSize: 1 } })
    unread.value = page.total || 0
  } catch { /* interceptor handles */ }
})

onBeforeUnmount(() => {
  if (pythonAiHealthTimer !== undefined) window.clearInterval(pythonAiHealthTimer)
  window.removeEventListener('online', refreshApiStatus)
  window.removeEventListener('offline', refreshApiStatus)
})

watch(() => route.path, () => {
  void checkProfilePrompt()
})

async function logout() {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="experience-shell">
    <div class="ambient ambient-one" />
    <div class="ambient ambient-two" />

    <header class="experience-header">
      <div class="wordmark-group">
        <button class="wordmark" :aria-label="homeLabel" @click="router.push(homePath)">
          <span class="wordmark-seal">序</span>
          <span><b>知序</b><small>{{ auth.isAdmin ? 'ADMIN CONTROL CENTER' : 'AI LEARNING STUDIO' }}</small></span>
        </button>
        <router-link v-if="auth.isAdmin" to="/admin" class="admin-text-link">后台管理</router-link>
      </div>

      <nav v-if="primaryMenu.length" class="primary-nav" aria-label="主要导航">
        <router-link
          v-for="item in primaryMenu"
          :key="item.path"
          :to="item.path"
          :class="{ active: isActive(item.path) }"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>

        <el-popover v-if="!auth.isAdmin" placement="bottom" :width="620" trigger="click" popper-class="journey-popper">
          <template #reference>
            <button class="explore-trigger" :class="{ active: !primaryMenu.some((item) => isActive(item.path)) }">
              <el-icon><MoreFilled /></el-icon><span>探索</span>
            </button>
          </template>
          <div class="journey-menu">
            <div class="journey-intro">
              <span class="eyebrow">LEARNING MAP</span>
              <h3>你的学习，不只有一条路径</h3>
              <p>从认识自己到沉淀知识，选择此刻需要的空间。</p>
            </div>
            <div class="journey-groups">
              <section v-for="group in journeyGroups" :key="group.title">
                <b>{{ group.title }}</b><small>{{ group.caption }}</small>
                <router-link v-for="item in group.items" :key="item.path" :to="item.path">
                  <span>{{ item.label }}</span><em>{{ item.hint }}</em>
                </router-link>
              </section>
            </div>
          </div>
        </el-popover>
      </nav>

      <div class="header-actions">
        <button
          class="api-health"
          :class="{ online: pythonAiOnline === true, offline: pythonAiOnline === false, checking: pythonAiOnline === null }"
          :title="pythonAiOnline === true ? 'Python AI 模型接口调用正常' : 'Python AI 模型接口调用失败，点击重试'"
          @click="refreshApiStatus"
        >
          <span />
          <small>{{ pythonAiOnline === null ? 'AI 检查中' : pythonAiOnline ? 'AI 接口正常' : 'AI 接口异常' }}</small>
        </button>
        <button
          class="round-action"
          :aria-label="isDark ? '切换为亮色模式' : '切换为黑夜模式'"
          :title="isDark ? '切换为亮色模式' : '切换为黑夜模式'"
          @click="toggleTheme"
        >
          <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon>
        </button>
        <el-badge :value="unread" :hidden="!unread">
          <button class="round-action" aria-label="通知"><el-icon><Bell /></el-icon></button>
        </el-badge>
        <el-dropdown @command="(command: string) => command === 'logout' ? logout() : router.push('/settings')">
          <button class="identity-chip">
            <span>{{ auth.user?.username?.slice(0, 1).toUpperCase() || 'U' }}</span>
            <div><b>{{ auth.user?.username }}</b><small>{{ auth.isAdmin ? 'ADMINISTRATOR' : 'LEARNER' }}</small></div>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="settings">账户设置</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main class="experience-main">
      <div class="route-context">
        <span>{{ routeTitle }}</span><i /> <small>{{ routeHint }}</small>
      </div>
      <section class="page"><router-view /></section>
    </main>

    <el-dialog
      v-model="profilePromptVisible"
      width="460px"
      align-center
      :show-close="false"
      :close-on-click-modal="false"
      class="profile-nudge-dialog"
    >
      <div class="profile-nudge">
        <span class="profile-nudge-mark">序</span>
        <span class="eyebrow">FIRST SETUP</span>
        <h3>先让系统认识你</h3>
        <p>你还没有学习画像。补充学习方向、时间安排和偏好后，AI 才能更准确地生成计划、推荐任务和组织文档问答。</p>
        <div class="profile-nudge-points">
          <span>方向</span>
          <span>时间</span>
          <span>节奏</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="dismissProfilePrompt">稍后再说</el-button>
        <el-button type="primary" @click="goProfile">
          <el-icon><Operation /></el-icon>
          去完善画像
        </el-button>
      </template>
    </el-dialog>

    <nav class="mobile-dock" aria-label="移动端主要导航" :class="{ 'admin-mobile-dock': auth.isAdmin }">
      <router-link v-for="item in mobileMenu" :key="item.path" :to="item.path" :class="{ active: isActive(item.path) }">
        <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
      </router-link>
      <router-link to="/settings" :class="{ active: isActive('/settings') }">
        <el-icon><MoreFilled /></el-icon><span>我的</span>
      </router-link>
    </nav>
  </div>
</template>

<style>
.profile-nudge-dialog.el-dialog {
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, .76);
  border-radius: 22px;
  background:
    radial-gradient(circle at 86% 16%, rgba(223, 238, 229, .95), transparent 34%),
    rgba(252, 253, 249, .98);
  box-shadow: 0 26px 80px rgba(24, 54, 42, .18);
}

.profile-nudge-dialog .el-dialog__header { display: none; }
.profile-nudge-dialog .el-dialog__body { padding: 30px 30px 8px; }
.profile-nudge-dialog .el-dialog__footer { padding: 8px 30px 30px; }
.profile-nudge-dialog .dialog-footer, .profile-nudge-dialog .el-dialog__footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.profile-nudge { position: relative; padding-right: 18px; }
.profile-nudge-mark {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: 18px;
  border-radius: 16px 16px 16px 6px;
  color: #f7f3e8;
  background: linear-gradient(145deg, #225e49, #0f372a);
  box-shadow: 0 12px 24px rgba(18, 58, 46, .18);
  font: 600 22px var(--display);
}

.profile-nudge h3 {
  margin: 8px 0 10px;
  color: var(--ink);
  font: 500 28px/1.18 var(--display);
}

.profile-nudge p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.8;
}

.profile-nudge-points {
  display: flex;
  gap: 8px;
  margin-top: 18px;
}

.profile-nudge-points span {
  padding: 6px 10px;
  border: 1px solid rgba(23, 107, 80, .12);
  border-radius: 999px;
  color: var(--green);
  background: rgba(223, 238, 229, .68);
  font-size: 11px;
  font-weight: 700;
}

.admin-mobile-dock {
  grid-template-columns: repeat(2, minmax(0, 1fr)) !important;
}

@media (max-width: 520px) {
  .profile-nudge-dialog.el-dialog { width: calc(100% - 32px) !important; }
  .profile-nudge-dialog .el-dialog__body { padding: 26px 22px 6px; }
  .profile-nudge-dialog .el-dialog__footer { align-items: stretch; flex-direction: column-reverse; padding: 8px 22px 24px; }
  .profile-nudge-dialog .el-button { width: 100%; margin-left: 0 !important; }
}
</style>
