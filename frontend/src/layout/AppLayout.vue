<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { api } from '../api/http'
import { Bell, DataAnalysis, Document, House, Notebook, Operation, Reading, Setting, Stopwatch, TrendCharts, User } from '@element-plus/icons-vue'

const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const collapsed = ref(false); const unread = ref(0)
const menu = computed(() => [
  ['/dashboard', '学习总览', House], ['/onboarding', '学习画像', User], ['/goals', '目标与项目', TrendCharts],
  ['/plans', 'Agent 计划', Operation], ['/today', '今日执行', Stopwatch], ['/knowledge', '知识库', Document],
  ['/qa', '知识问答', Reading], ['/assessments', '评估与错题', Notebook], ['/analytics', '学习分析', DataAnalysis], ['/settings', '账户设置', Setting],
  ...(auth.isAdmin ? [['/admin', '系统管理', Setting] as const] : []),
])
onMounted(async () => {
  try { await auth.hydrate(); const page = await api<any>({ url: '/notifications', params: { unread: true, pageSize: 1 } }); unread.value = page.total || 0 } catch { /* interceptor handles */ }
})
async function logout() { await auth.logout(); await router.replace('/login') }
</script>

<template>
  <div class="shell">
    <aside class="sidebar" :class="{ collapsed }">
      <button class="brand" @click="collapsed = !collapsed"><span class="brand-mark">序</span><span v-if="!collapsed"><b>知序</b><small>Adaptive Learning</small></span></button>
      <nav>
        <router-link v-for="item in menu" :key="item[0] as string" :to="item[0] as string" :class="{ active: route.path.startsWith(item[0] as string) }">
          <el-icon><component :is="item[2]" /></el-icon><span v-if="!collapsed">{{ item[1] }}</span>
        </router-link>
      </nav>
      <div class="sidebar-foot" v-if="!collapsed"><span class="status-dot"></span>AI 服务正常</div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div><span class="eyebrow">{{ new Date().toLocaleDateString('zh-CN', { weekday: 'long' }) }}</span><h1>{{ route.meta.title }}</h1></div>
        <div class="top-actions">
          <el-badge :value="unread" :hidden="!unread"><el-button circle :icon="Bell" /></el-badge>
          <el-dropdown @command="(c: string) => c === 'logout' ? logout() : router.push('/settings')">
            <button class="user-chip"><span>{{ auth.user?.username?.slice(0, 1).toUpperCase() || 'U' }}</span><div><b>{{ auth.user?.username }}</b><small>{{ auth.isAdmin ? '管理员' : '学习者' }}</small></div></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="settings">账户设置</el-dropdown-item><el-dropdown-item divided command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <section class="page"><router-view /></section>
    </main>
  </div>
</template>
