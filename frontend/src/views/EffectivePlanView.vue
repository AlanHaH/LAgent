<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { api } from '../api/http'

dayjs.extend(utc)
dayjs.extend(timezone)

const route = useRoute()
const router = useRouter()

type TaskGraphNode = {
  publicId: string
  title: string
  goalId: string
  goalName: string
  taskType: string
  priority: string
  estimatedMinutes: number
  scheduledStart?: string
  dueAt?: string
  status: string
  availableToday: boolean
  temporalState: string
}

type TaskGraphView = {
  today: string
  timezone: string
  nodes: TaskGraphNode[]
  edges: Array<{ source: string; target: string }>
}

const emptyGraph = (): TaskGraphView => ({ today: '', timezone: '', nodes: [], edges: [] })

const loading = ref(false)
const detail = ref<any>()
const goal = ref<any>()
const graph = ref<TaskGraphView>(emptyGraph())
const entries = ref<Array<{ goal: any; plan: any; progress: Progress }>>([])

type Progress = { total: number; completed: number; percent: number; plannedMinutes: number }

// 路由为 /plans/:id/effective（详情）或 /plans/effective（总览），goalId 取自 :id
const goalId = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''))
const isDetail = computed(() => !!goalId.value)
// 计划模块 Tab：AI 提案 与 正式生效（当前页）同模块内切换
const proposalTabPath = computed(() => goalId.value ? `/plans/${goalId.value}` : '/plans')
const effectiveTabPath = computed(() => goalId.value ? `/plans/${goalId.value}/effective` : '/plans/effective')
// 用 /tasks/graph 返回的用户时区分桶，避免浏览器本地时区把早间任务偏一天
const zone = computed(() => graph.value.timezone || dayjs.tz.guess())
const goalName = computed(() => goal.value?.name || graph.value.nodes.find((node) => node.goalId === goalId.value)?.goalName || '正式计划')
const versionNo = computed(() => detail.value?.version?.versionNo)
const publishedAt = computed(() => detail.value?.publishedAt)

function localDate(iso?: string) {
  if (!iso) return null
  const value = dayjs.tz(iso, zone.value)
  return value.isValid() ? value.format('YYYY-MM-DD') : null
}

function statusText(status: string) {
  return ({ PLANNED: '待开始', NOT_STARTED: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELED: '已提前结束' } as Record<string, string>)[status] || status
}

function goalNodes(goalId: string) {
  return graph.value.nodes.filter((node) => node.goalId === goalId)
}

function progressFor(goalId: string): Progress {
  const nodes = goalNodes(goalId)
  const total = nodes.length
  const completed = nodes.filter((node) => node.status === 'COMPLETED').length
  const plannedMinutes = nodes.reduce((sum, node) => sum + (Number(node.estimatedMinutes) || 0), 0)
  const percent = total ? Math.round((completed / total) * 100) : 0
  return { total, completed, percent, plannedMinutes }
}

const detailProgress = computed(() => (goalId.value ? progressFor(goalId.value) : { total: 0, completed: 0, percent: 0, plannedMinutes: 0 }))

const stagesWithTasks = computed(() => {
  const stages = (detail.value?.stages || []) as any[]
  const nodes = goalNodes(goalId.value)
  return stages.map((stage) => ({
    ...stage,
    tasks: nodes
      .filter((node) => {
        const date = localDate(node.scheduledStart)
        return date && date >= stage.startDate && date <= stage.endDate
      })
      .sort((a, b) => String(a.scheduledStart || '').localeCompare(String(b.scheduledStart || ''))),
  }))
})

const unassignedTasks = computed(() => {
  const nodes = goalNodes(goalId.value)
  if (!(detail.value?.stages || []).length) return nodes
  const assigned = new Set(stagesWithTasks.value.flatMap((stage) => stage.tasks.map((task: TaskGraphNode) => task.publicId)))
  return nodes.filter((node) => !assigned.has(node.publicId))
})

async function load() {
  loading.value = true
  try {
    graph.value = await api<TaskGraphView>({ url: '/tasks/graph', silent: true }).catch(() => emptyGraph())
    if (isDetail.value) {
      const id = goalId.value
      const projectParam = typeof route.query.project === 'string' ? { projectId: route.query.project } : undefined
      const [planResult, goalResult] = await Promise.all([
        api<any | null>({ url: `/goals/${id}/effective-plan`, silent: true, params: projectParam }).catch(() => null),
        api<any | null>({ url: `/goals/${id}`, silent: true }).catch(() => null),
      ])
      detail.value = planResult
      goal.value = goalResult
    } else {
      detail.value = undefined
      goal.value = undefined
      const goalsResult = await api<any>({ url: '/goals', params: { status: 'ACTIVE', pageSize: 100 }, silent: true }).catch(() => ({ items: [] }))
      const goals = (goalsResult?.items || []) as any[]
      const plans = await Promise.all(goals.map((item) =>
        api<any | null>({ url: `/goals/${item.publicId}/effective-plan`, silent: true }).catch(() => null)))
      entries.value = goals
        .map((item, index) => ({ goal: item, plan: plans[index], progress: progressFor(item.publicId) }))
        .filter((entry) => entry.plan)
    }
  } finally {
    loading.value = false
  }
}

watch(() => [route.params.id, route.query.project], load)
onMounted(load)

function taskStatusClass(status: string) {
  if (status === 'COMPLETED') return 'done'
  if (status === 'IN_PROGRESS') return 'active'
  return 'pending'
}
</script>

<template>
  <div class="effective-page" v-loading="loading">
    <div class="plan-module-tabs">
      <router-link :to="proposalTabPath">AI 提案</router-link>
      <router-link :to="effectiveTabPath" class="active">正式生效</router-link>
    </div>
    <!-- 详情模式：单个正式计划的阶段时间线 -->
    <template v-if="isDetail">
      <section v-if="!detail" class="effective-empty">
        <div class="empty-orbit"><span>序</span></div>
        <span class="eyebrow">NO EFFECTIVE PLAN</span>
        <h2>该目标还没有正式生效的计划</h2>
        <p>先生成并确认发布 AI 计划，这里才会展示它的版本、阶段与任务时间线。</p>
        <div class="empty-actions">
          <el-button type="primary" @click="router.push(`/plans/${goalId}`)">去生成 AI 计划</el-button>
          <el-button plain @click="router.push('/plans/effective')">返回正式计划总览</el-button>
        </div>
      </section>

      <template v-else>
        <section class="effective-hero">
          <div class="hero-copy">
            <span class="eyebrow light">EFFECTIVE PLAN / V{{ versionNo }}</span>
            <h1>{{ goalName }}</h1>
            <p>
              <span class="live-badge"><i />正式生效</span>
              于 {{ publishedAt ? dayjs.tz(publishedAt, zone).format('YYYY-MM-DD HH:mm') : '—' }} 发布
              <template v-if="detail.version.riskLevel"> · 风险等级 {{ detail.version.riskLevel }}</template>
            </p>
            <div class="hero-progress">
              <div class="progress-bar"><i :style="{ width: detailProgress.percent + '%' }" /></div>
              <span>已完成 {{ detailProgress.completed }}/{{ detailProgress.total }} 个任务 · {{ detailProgress.percent }}%</span>
            </div>
          </div>
          <div class="hero-actions">
            <el-button plain class="light" @click="router.push(`/plans/${goalId}`)">优化当前计划</el-button>
            <el-button type="primary" class="gold" @click="router.push('/today')">进入执行 <span class="button-arrow">↗</span></el-button>
          </div>
        </section>

        <div v-if="stagesWithTasks.length || unassignedTasks.length" class="plan-timeline">
          <section v-for="stage in stagesWithTasks" :key="stage.id" class="stage-card">
            <header>
              <span>阶段 {{ stage.sequenceNo }}</span>
              <b>{{ stage.name }}</b>
              <small>{{ stage.startDate }} — {{ stage.endDate }}</small>
              <p v-if="stage.outcome">{{ stage.outcome }}</p>
            </header>
            <div v-if="stage.tasks.length" class="stage-tasks">
              <button v-for="node in stage.tasks" :key="node.publicId" class="task-row" :class="taskStatusClass(node.status)">
                <i>{{ node.status === 'COMPLETED' ? '✓' : '·' }}</i>
                <span class="task-time">{{ node.scheduledStart ? dayjs.tz(node.scheduledStart, zone).format('MM-DD HH:mm') : '待安排' }}</span>
                <b>{{ node.title }}</b>
                <small class="task-status">{{ statusText(node.status) }}</small>
                <em>{{ node.estimatedMinutes }} 分钟</em>
              </button>
            </div>
            <div v-else class="stage-no-task">该阶段还没有排入任务。</div>
          </section>

          <section v-if="unassignedTasks.length" class="stage-card">
            <header><span>待安排</span><b>未落入任何阶段的任务</b><small>以下任务未匹配到已发布计划的阶段区间</small></header>
            <div class="stage-tasks">
              <button v-for="node in unassignedTasks" :key="node.publicId" class="task-row" :class="taskStatusClass(node.status)">
                <i>{{ node.status === 'COMPLETED' ? '✓' : '·' }}</i>
                <span class="task-time">{{ node.scheduledStart ? dayjs.tz(node.scheduledStart, zone).format('MM-DD HH:mm') : '待安排' }}</span>
                <b>{{ node.title }}</b>
                <small class="task-status">{{ statusText(node.status) }}</small>
                <em>{{ node.estimatedMinutes }} 分钟</em>
              </button>
            </div>
          </section>
        </div>

        <section v-else class="effective-empty compact">
          <div class="empty-orbit"><span>序</span></div>
          <h2>计划已生效，但还没有排入任务</h2>
          <p>任务会随计划发布写入执行图谱，稍后刷新即可看到每个阶段的学习任务。</p>
        </section>
      </template>
    </template>

    <!-- 总览模式：全部正式生效计划 -->
    <template v-else>
      <section class="overview-hero">
        <div>
          <span class="eyebrow light">EFFECTIVE PLANS</span>
          <h1>正式计划</h1>
          <p>已经确认发布、正在执行的 AI 计划。点击卡片查看单个计划的阶段时间线与完成进度。</p>
        </div>
      </section>

      <section v-if="entries.length" class="plan-grid">
        <article v-for="entry in entries" :key="entry.goal.publicId" class="plan-card" @click="router.push(`/plans/${entry.goal.publicId}/effective`)">
          <header>
            <span class="card-type">{{ entry.goal.type || 'SKILL' }}</span>
            <h2>{{ entry.goal.name }}</h2>
            <small>v{{ entry.plan.version.versionNo }} · {{ entry.plan.publishedAt ? dayjs.tz(entry.plan.publishedAt, zone).format('YYYY-MM-DD') : '' }} 生效</small>
          </header>
          <div class="card-progress">
            <div class="progress-bar"><i :style="{ width: entry.progress.percent + '%' }" /></div>
            <span>已完成 {{ entry.progress.completed }}/{{ entry.progress.total }} · {{ entry.progress.percent }}%</span>
          </div>
          <div class="card-meta">
            <span>{{ entry.progress.plannedMinutes }} 分钟建议投入</span>
            <i>查看详情 ↗</i>
          </div>
        </article>
      </section>

      <section v-else class="effective-empty">
        <div class="empty-orbit"><span>序</span></div>
        <span class="eyebrow">NO EFFECTIVE PLANS</span>
        <h2>还没有正式生效的计划</h2>
        <p>创建并激活目标，让 Agent 生成方案并确认发布后，正式计划会集中展示在这里。</p>
        <el-button type="primary" @click="router.push('/plans')">去生成 AI 计划</el-button>
      </section>
    </template>
  </div>
</template>

<style scoped>
.effective-page { display: grid; gap: 22px; }

.plan-module-tabs { display: flex; gap: 6px; width: fit-content; margin-bottom: 4px; padding: 4px; border: 1px solid rgba(38, 68, 55, .1); border-radius: 99px; background: var(--el-fill-color-light); }
.plan-module-tabs a { padding: 8px 20px; border-radius: 99px; color: var(--muted); font-size: 11px; font-weight: 700; transition: .2s; }
.plan-module-tabs a:hover { color: var(--ink); }
.plan-module-tabs a.active { color: #17382d; background: linear-gradient(145deg, #edcf8b, #d1a252); box-shadow: 0 6px 16px rgba(160, 122, 46, .22); }
html.dark .plan-module-tabs a.active { color: #17382d; }

.effective-hero {
  position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 24px;
  overflow: hidden; min-height: 158px; padding: 26px clamp(26px, 4vw, 44px); border-radius: 28px; color: #f3f5ed;
  background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%);
  box-shadow: 0 22px 60px rgba(20, 59, 45, .2);
}
.effective-hero::after { position: absolute; right: -70px; bottom: -150px; width: 320px; height: 320px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 42px rgba(255, 255, 255, .022), 0 0 0 84px rgba(255, 255, 255, .015); content: ""; }
.hero-copy { position: relative; z-index: 1; }
.hero-copy h1 { margin: 8px 0 6px; font: 500 clamp(23px, 2.6vw, 30px)/1.2 var(--display); letter-spacing: -.02em; }
.hero-copy p { margin: 0; color: #bfd0c8; font-size: 10px; line-height: 1.7; }
.live-badge { display: inline-flex; align-items: center; gap: 6px; margin-right: 7px; padding: 4px 10px; border: 1px solid rgba(226, 194, 126, .4); border-radius: 99px; color: #f0d28d; font-weight: 700; }
.live-badge i { width: 7px; height: 7px; border-radius: 50%; background: #e2bd73; animation: live-pulse 1.8s ease-in-out infinite; }
@keyframes live-pulse { 50% { box-shadow: 0 0 0 5px rgba(226, 189, 115, .22); } }
.hero-progress { display: flex; align-items: center; gap: 12px; max-width: 460px; margin-top: 15px; }
.hero-progress > span { color: #a8c0b4; font-size: 9px; white-space: nowrap; }
.hero-actions { position: relative; z-index: 1; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 9px; }
.effective-hero :deep(.el-button--primary.gold) { border-color: #e2bd73; color: #17382d; background: linear-gradient(145deg, #edcf8b, #d1a252); }
.effective-hero :deep(.el-button.light) { border-color: rgba(255, 255, 255, .16); color: #dce8e1; background: rgba(255, 255, 255, .07); }
.button-arrow { margin-left: 6px; }

.progress-bar { flex: 1; height: 7px; overflow: hidden; border-radius: 99px; background: rgba(255, 255, 255, .13); }
.progress-bar i { display: block; height: 100%; border-radius: 99px; background: linear-gradient(90deg, #dfbd76, #b98c40); transition: width .4s ease; }

.plan-timeline { display: grid; gap: 16px; }
.stage-card { padding: 26px 28px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 24px; background: var(--card); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); }
.stage-card header span { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .1em; }
.stage-card header b { display: block; margin-top: 6px; font: 500 20px var(--display); }
.stage-card header small { display: block; margin-top: 4px; color: var(--muted); font-size: 9px; }
.stage-card header p { max-width: 720px; margin: 10px 0 0; color: var(--muted); font-size: 10px; line-height: 1.7; }
.stage-tasks { display: grid; gap: 8px; margin-top: 17px; }
.task-row { display: grid; grid-template-columns: 22px 88px minmax(0, 1fr) auto auto; align-items: center; gap: 10px; width: 100%; padding: 12px 14px; border: 1px solid rgba(31, 88, 64, .09); border-radius: 14px; color: var(--ink); background: var(--chip); text-align: left; transition: .2s; }
.task-row:hover { border-color: rgba(31, 88, 64, .22); transform: translateY(-1px); }
.task-row > i { display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; color: var(--muted); background: var(--paper-solid); font-size: 9px; font-style: normal; box-shadow: 0 0 0 1px rgba(31, 88, 64, .1); }
.task-row.done > i { color: #fff; background: var(--green); }
.task-row.active > i { color: #17382d; background: #e2bd73; }
.task-row .task-time { color: var(--muted); font: 500 9px ui-monospace, monospace; }
.task-row b { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.task-row .task-status { padding: 4px 9px; border-radius: 99px; color: var(--muted); background: var(--el-fill-color-light); font-size: 8px; font-weight: 700; white-space: nowrap; }
.task-row.done .task-status { color: var(--green); background: var(--mint); }
.task-row.active .task-status { color: #8a6420; background: var(--seal); }
.task-row em { color: var(--muted); font-size: 8px; font-style: normal; white-space: nowrap; }
.stage-no-task { margin-top: 15px; padding: 15px; border-radius: 13px; color: var(--muted); background: var(--el-fill-color-light); font-size: 9px; }

.overview-hero {
  position: relative; overflow: hidden; padding: 26px clamp(26px, 4vw, 44px); border-radius: 28px; color: #f3f5ed;
  background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%);
  box-shadow: 0 22px 60px rgba(20, 59, 45, .2);
}
.overview-hero::after { position: absolute; right: -70px; bottom: -150px; width: 320px; height: 320px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 42px rgba(255, 255, 255, .022), 0 0 0 84px rgba(255, 255, 255, .015); content: ""; }
.overview-hero h1 { margin: 8px 0 6px; font: 500 clamp(23px, 2.6vw, 30px)/1.2 var(--display); letter-spacing: -.02em; }
.overview-hero p { max-width: 640px; margin: 0; color: #bfd0c8; font-size: 11px; line-height: 1.7; }

.plan-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.plan-card { padding: 24px 25px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 22px; background: var(--card); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); cursor: pointer; transition: .22s ease; }
.plan-card:hover { border-color: rgba(31, 88, 64, .22); transform: translateY(-2px); box-shadow: var(--lift-shadow); }
.plan-card header .card-type { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .12em; }
.plan-card header h2 { margin: 7px 0 4px; font: 500 21px/1.3 var(--display); }
.plan-card header small { color: var(--muted); font-size: 9px; }
.card-progress { display: flex; align-items: center; gap: 10px; margin-top: 17px; }
.card-progress > span { color: var(--muted); font-size: 8px; white-space: nowrap; }
.plan-card .card-progress .progress-bar { background: var(--el-fill-color); }
.plan-card .progress-bar i { background: linear-gradient(90deg, var(--green), #2a7a54); }
.card-meta { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--line); }
.card-meta span { color: var(--muted); font-size: 8px; }
.card-meta i { color: var(--green); font-size: 9px; font-style: normal; }

.effective-empty { display: grid; justify-items: center; min-height: 480px; align-content: center; padding: 40px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: var(--card); box-shadow: var(--soft-shadow); text-align: center; }
.effective-empty.compact { min-height: 300px; }
.empty-orbit { display: grid; place-items: center; width: 104px; height: 104px; margin-bottom: 24px; border: 1px solid rgba(31, 88, 64, .1); border-radius: 50%; box-shadow: 0 0 0 19px rgba(31, 88, 64, .025), 0 0 0 38px rgba(31, 88, 64, .016); }
.empty-orbit span { display: grid; place-items: center; width: 56px; height: 56px; border-radius: 19px 19px 19px 6px; color: #f7f3e8; background: linear-gradient(145deg, #173f32, #102e25); font: 600 20px var(--display); }
.effective-empty h2 { margin: 10px 0 7px; font: 500 27px var(--display); }
.effective-empty p { max-width: 520px; margin: 0 0 22px; color: var(--muted); font-size: 10px; line-height: 1.75; }
.empty-actions { display: flex; gap: 10px; }

@media (max-width: 760px) {
  .effective-hero { grid-template-columns: 1fr; }
  .hero-actions { justify-content: flex-start; }
  .task-row { grid-template-columns: 22px 70px minmax(0, 1fr) auto; }
  .task-row em { display: none; }
}
@media (max-width: 520px) {
  .task-row { grid-template-columns: 20px minmax(0, 1fr) auto; }
  .task-row .task-time { display: none; }
  .plan-grid { grid-template-columns: 1fr; }
}

html.dark .stage-card,
html.dark .plan-card,
html.dark .effective-empty { border-color: rgba(255, 255, 255, .09); }
html.dark .task-row { border-color: rgba(255, 255, 255, .07); }
html.dark .task-row:hover { border-color: rgba(255, 255, 255, .16); }
html.dark .task-row.active .task-status { color: var(--gold); }
</style>
