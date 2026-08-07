<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { api } from '../api/http'
import { isDark } from '../theme'

use([CanvasRenderer, BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const overview = ref<any>({ metrics: {}, studyTime: [], mastery: [] })
const tasks = ref<any[]>([])
const goals = ref<any[]>([])
const masteryTrend = ref<any[]>([])
const loading = ref(true)
const start = dayjs().subtract(6, 'day').format('YYYY-MM-DD')
const end = dayjs().format('YYYY-MM-DD')

const metrics = computed(() => overview.value.metrics || {})
const completed = computed(() => Number(metrics.value.completedTasks?.value || 0))
const planned = computed(() => Number(metrics.value.plannedTasks?.value || 0))
const weeklyMinutes = computed(() => Math.round(Number(metrics.value.effectiveStudySeconds?.value || 0) / 60))
const overallProgress = computed(() => Math.round(Number(metrics.value.overallGoalTaskProgress?.value || 0)))
const activeDays = computed(() => (overview.value.studyTime || []).filter((item: any) => Number(item.totalSeconds || 0) > 0).length)
const todayTasks = computed(() => tasks.value
  .filter((item: any) => item.task?.scheduledStart && dayjs(item.task.scheduledStart).isSame(dayjs(), 'day'))
  .sort((a: any, b: any) => dayjs(a.task.scheduledStart).valueOf() - dayjs(b.task.scheduledStart).valueOf()))
const nextTask = computed(() => {
  const unfinishedToday = todayTasks.value.find((item: any) => !['COMPLETED', 'CANCELED'].includes(item.task.lifecycleStatus))
  if (unfinishedToday) return unfinishedToday
  return tasks.value
    .filter((item: any) => item.task?.scheduledStart && dayjs(item.task.scheduledStart).isAfter(dayjs()) && !['COMPLETED', 'CANCELED'].includes(item.task.lifecycleStatus))
    .sort((a: any, b: any) => dayjs(a.task.scheduledStart).valueOf() - dayjs(b.task.scheduledStart).valueOf())[0]
})
const mastery = computed(() => overview.value.mastery || [])
const greeting = computed(() => {
  const hour = dayjs().hour()
  if (hour < 11) return '早上好，把清醒留给重要的事'
  if (hour < 18) return '下午好，沿着自己的节奏前进'
  return '晚上好，为今天温柔地收尾'
})

function masteryName(item: any) {
  return item.name || item.dimensionName || item.dimensionCode || item.knowledgePoint || '能力维度'
}
function masteryScore(item: any) {
  const value = Number(item.score ?? item.masteryScore ?? item.value ?? 0)
  return value <= 1 ? Math.round(value * 100) : Math.round(value)
}

// ---- 图表调色板（跟随黑夜模式，isDark 变化时 computed 自动重算） ----
const axisColor = computed(() => (isDark.value ? '#93a79b' : '#9aa59e'))
const splitColor = computed(() => (isDark.value ? 'rgba(255, 255, 255, .08)' : '#e9ebe6'))
const legendColor = computed(() => (isDark.value ? '#93a79b' : '#7c8a82'))
const green = '#2d8a63'
const gold = '#d9a03f'

// 近 7 天学习投入：自动计时 + 手工补录 堆叠柱状
const studyChart = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 46, right: 16, top: 28, bottom: 30 },
  legend: { data: ['自动记录', '手工补录'], top: 0, itemWidth: 9, itemHeight: 9, textStyle: { color: legendColor.value, fontSize: 10 } },
  xAxis: {
    type: 'category',
    data: overview.value.studyTime.map((x: any) => dayjs(x.date).format('M/D')),
    axisLine: { lineStyle: { color: splitColor.value } },
    axisTick: { show: false },
    axisLabel: { color: axisColor.value },
  },
  yAxis: {
    type: 'value',
    axisLabel: { formatter: (v: number) => `${Math.round(v / 60)}m`, color: axisColor.value },
    splitLine: { lineStyle: { color: splitColor.value } },
  },
  series: [
    {
      name: '自动记录',
      type: 'bar',
      stack: 'time',
      data: overview.value.studyTime.map((x: any) => x.autoSeconds),
      itemStyle: { color: green, borderRadius: [3, 3, 0, 0] },
    },
    {
      name: '手工补录',
      type: 'bar',
      stack: 'time',
      data: overview.value.studyTime.map((x: any) => x.manualSeconds),
      itemStyle: { color: gold },
    },
  ],
}))

// 任务状态分布：已完成 / 逾期未完成 / 待完成 环图
const taskChart = computed(() => {
  const plannedCount = Number(metrics.value.plannedTasks?.numerator ?? 0)
  const doneCount = Number(metrics.value.completedTasks?.numerator ?? 0)
  const overdueCount = Number(metrics.value.overdueRate?.numerator ?? 0)
  const pendingCount = Math.max(0, plannedCount - doneCount - overdueCount)
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 项（{d}%）' },
    legend: { bottom: 0, itemWidth: 9, itemHeight: 9, textStyle: { color: legendColor.value, fontSize: 10 } },
    series: [{
      type: 'pie',
      radius: ['52%', '74%'],
      center: ['50%', '44%'],
      itemStyle: { borderColor: isDark.value ? '#151d19' : '#fafbf7', borderWidth: 2 },
      label: { show: false },
      emphasis: { label: { show: true, fontWeight: 600, formatter: '{b}\n{c} 项' } },
      data: [
        { name: '已完成', value: doneCount, itemStyle: { color: green } },
        { name: '逾期未完成', value: overdueCount, itemStyle: { color: gold } },
        { name: '待完成', value: pendingCount, itemStyle: { color: isDark.value ? '#45534c' : '#d7dcd6' } },
      ],
    }],
  }
})

// 能力光谱：掌握度横向条形（置信度进 tooltip）
const masteryChart = computed(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (params: any) => {
      const point = params?.[0]
      if (!point) return ''
      const item = mastery.value.slice(0, 8)[point.dataIndex]
      const confidence = item ? Math.round(Number(item.confidence ?? 0) * 100) : 0
      return `${point.name}<br/>掌握度 <b>${point.value}%</b> · 置信度 ${confidence}%`
    },
  },
  grid: { left: 8, right: 34, top: 4, bottom: 4, containLabel: true },
  xAxis: {
    type: 'value',
    max: 100,
    axisLabel: { formatter: '{value}%', color: axisColor.value },
    splitLine: { lineStyle: { color: splitColor.value } },
  },
  yAxis: {
    type: 'category',
    data: mastery.value.slice(0, 8).map(masteryName),
    axisLabel: { color: axisColor.value, fontSize: 10, overflow: 'truncate', width: 84 },
    axisLine: { show: false },
    axisTick: { show: false },
  },
  series: [{
    type: 'bar',
    barWidth: 10,
    showBackground: true,
    backgroundStyle: { color: isDark.value ? 'rgba(255, 255, 255, .06)' : 'rgba(31, 88, 64, .08)', borderRadius: 5 },
    data: mastery.value.slice(0, 8).map((x: any) => ({ value: masteryScore(x), itemStyle: { color: green, borderRadius: 5 } })),
  }],
}))

// 掌握度变化趋势：每次评估后的快照折线
const masteryTrendChart = computed(() => {
  const names = [...new Set(masteryTrend.value.flatMap((snapshot: any) => snapshot.mastery.map((item: any) => item.name)))].slice(0, 6)
  const palette = [green, gold, '#5b8db8', '#8a6cb8', '#c25f5f', '#4a9d8a']
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: names, bottom: 0, itemWidth: 9, itemHeight: 9, textStyle: { color: legendColor.value, fontSize: 10 } },
    grid: { left: 42, right: 18, top: 26, bottom: 38 },
    xAxis: {
      type: 'category',
      data: masteryTrend.value.map((snapshot: any) => dayjs(snapshot.snapshotAt).format('M/D')),
      axisLine: { lineStyle: { color: splitColor.value } },
      axisTick: { show: false },
      axisLabel: { color: axisColor.value },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: '{value}%', color: axisColor.value },
      splitLine: { lineStyle: { color: splitColor.value } },
    },
    series: names.map((name, index) => ({
      name,
      type: 'line',
      smooth: true,
      connectNulls: true,
      symbolSize: 5,
      lineStyle: { width: 2 },
      itemStyle: { color: palette[index % palette.length] },
      data: masteryTrend.value.map((snapshot: any) => {
        const item = snapshot.mastery.find((value: any) => value.name === name)
        return item ? Math.round(Number(item.score)) : null
      }),
    })),
  }
})

onMounted(async () => {
  const results = await Promise.allSettled([
    api<any>({ url: '/analytics/overview', params: { start, end } }),
    api<any[]>({ url: '/tasks' }),
    api<any>({ url: '/goals', params: { status: 'ACTIVE', pageSize: 5 } }),
    api<any[]>({ url: '/analytics/mastery-trend' }),
  ])
  if (results[0].status === 'fulfilled') overview.value = results[0].value
  if (results[1].status === 'fulfilled') tasks.value = results[1].value
  if (results[2].status === 'fulfilled') goals.value = results[2].value.items
  if (results[3].status === 'fulfilled') masteryTrend.value = results[3].value
  loading.value = false
})
</script>

<template>
  <div v-loading="loading" class="dashboard-canvas">
    <section class="greet-strip">
      <div class="greet-copy">
        <span class="greet-date">{{ dayjs().format('YYYY · MM · DD') }} / {{ dayjs().format('dddd') }}</span>
        <h1>{{ greeting }}</h1>
        <p v-if="nextTask">下一步：{{ nextTask.task.title }} · {{ dayjs(nextTask.task.scheduledStart).format('M 月 D 日 HH:mm') }} · {{ nextTask.task.estimatedMinutes }} 分钟</p>
        <p v-else>此刻没有被安排占满 —— 可以休息，也可以和 Agent 生成一段新计划。</p>
      </div>
      <div class="greet-side">
        <div class="mini-orbit" :style="{ '--overall-progress': `${overallProgress}%` }" aria-label="当前目标总体进度">
          <div><strong>{{ overallProgress }}</strong><small>%</small><em>目标进度</em></div>
        </div>
        <div class="greet-actions">
          <el-button type="primary" @click="$router.push('/today')">进入今日节奏</el-button>
          <button class="text-action" @click="$router.push(goals.length?'/plans':'/goals')">{{goals.length?'和 Agent 规划下一步':'先创建第一个目标'}} <span>↗</span></button>
          <button class="text-action" @click="$router.push('/plans/effective')">正式计划总览 <span>↗</span></button>
        </div>
      </div>
    </section>

    <section class="signal-rail" aria-label="本周学习信号">
      <article><span>01</span><div><small>专注投入</small><strong>{{ weeklyMinutes }}<em> min</em></strong></div><p>近 7 天有效学习</p></article>
      <article><span>02</span><div><small>行动兑现</small><strong>{{ completed }}<em> / {{ planned || 0 }}</em></strong></div><p>完成与计划任务</p></article>
      <article><span>03</span><div><small>学习节律</small><strong>{{ activeDays }}<em> days</em></strong></div><p>近 7 天留下记录</p></article>
      <article><span>04</span><div><small>能力证据</small><strong>{{ overview.mastery.length }}<em> signals</em></strong></div><p>被持续追踪的维度</p></article>
    </section>

    <div class="chart-row">
      <section class="panel-card">
        <div class="section-heading compact">
          <div><span class="eyebrow">WEEKLY FOCUS</span><h2>近 7 天学习投入</h2></div>
          <small class="card-note">自动计时 + 手工补录</small>
        </div>
        <v-chart class="chart chart-lg" :option="studyChart" autoresize />
      </section>
      <section class="panel-card">
        <div class="section-heading compact">
          <div><span class="eyebrow">TASK RHYTHM</span><h2>任务状态分布</h2></div>
          <small class="card-note">完成 / 逾期 / 待完成</small>
        </div>
        <v-chart v-if="planned > 0" class="chart chart-sm" :option="taskChart" autoresize />
        <div v-else class="mini-empty">还没有计划任务，发布计划后这里会出现分布。</div>
      </section>
    </div>

    <div class="dashboard-bento">
      <section class="itinerary-studio">
        <div class="section-heading">
          <div><span class="eyebrow">TODAY'S PATH</span><h2>今天的学习路径</h2></div>
          <button @click="$router.push('/today')">打开专注空间 →</button>
        </div>
        <div v-if="!todayTasks.length" class="editorial-empty">
          <span>○</span><div><b>今天暂时留白</b><p>发布的任务会按时间在这里形成一条清晰路径。</p></div>
        </div>
        <div v-else class="path-list">
          <button v-for="(row, index) in todayTasks.slice(0, 5)" :key="row.task.publicId" @click="$router.push('/today')">
            <span class="path-time">{{ dayjs(row.task.scheduledStart).format('HH:mm') }}</span>
            <i :class="{ done: row.task.lifecycleStatus === 'COMPLETED' }">{{ row.task.lifecycleStatus === 'COMPLETED' ? '✓' : index + 1 }}</i>
            <div><b>{{ row.task.title }}</b><small>{{ row.task.estimatedMinutes }} 分钟 · {{ row.task.taskType || '学习任务' }}</small></div>
            <em>{{ row.task.lifecycleStatus === 'COMPLETED' ? '已完成' : '待进入' }}</em>
          </button>
        </div>
      </section>

      <div class="growth-column">
        <section class="panel-card">
          <div class="section-heading compact">
            <div><span class="eyebrow">ABILITY SPECTRUM</span><h2>能力光谱</h2></div>
            <button @click="$router.push('/onboarding')">查看画像</button>
          </div>
          <v-chart v-if="mastery.length" class="chart chart-sm" :option="masteryChart" autoresize />
          <div v-else class="mini-empty">完成学习与评估后，能力光谱会在这里生长。</div>
        </section>

        <section class="goal-cards">
          <div class="section-heading compact">
            <div><span class="eyebrow">IN MOTION</span><h2>正在推进</h2></div>
            <button @click="$router.push('/goals')">全部目标</button>
          </div>
          <button v-if="!goals.length" class="mini-empty" @click="$router.push('/goals')">还没有激活的目标，先创建一个明确的学习目标 →</button>
          <button v-for="(goal, index) in goals.slice(0, 3)" :key="goal.publicId" class="goal-note" @click="$router.push(`/plans/${goal.publicId}`)">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <div><b>{{ goal.name }}</b><small>{{ goal.startDate }} — {{ goal.dueDate }}</small></div>
            <i>↗</i>
          </button>
        </section>
      </div>
    </div>

    <section v-if="masteryTrend.length" class="panel-card">
      <div class="section-heading">
        <div><span class="eyebrow">MASTERY CURVE</span><h2>掌握度变化趋势</h2></div>
        <button @click="$router.push('/assessments')">评估与错题</button>
      </div>
      <v-chart class="chart chart-lg" :option="masteryTrendChart" autoresize />
    </section>
  </div>
</template>

<style scoped>
.dashboard-canvas { display: grid; gap: 20px; }

/* 问候条：单行条代替原来的大 hero，绿色块高度大幅压缩 */
.greet-strip {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 26px;
  min-height: 128px;
  padding: 22px clamp(26px, 4vw, 44px);
  overflow: hidden;
  border-radius: 28px;
  color: #f3f5ed;
  background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%);
  box-shadow: 0 22px 60px rgba(20, 59, 45, .2);
}
.greet-strip::after { position: absolute; right: -70px; bottom: -150px; width: 320px; height: 320px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 42px rgba(255, 255, 255, .022), 0 0 0 84px rgba(255, 255, 255, .015); content: ""; }
.greet-copy { position: relative; z-index: 2; min-width: 0; }
.greet-date { color: #afc8bc; font-size: 9px; font-weight: 700; letter-spacing: .2em; }
.greet-copy h1 { margin: 10px 0 7px; font: 500 clamp(23px, 2.6vw, 30px)/1.2 var(--display); letter-spacing: -.02em; }
.greet-copy p { overflow: hidden; max-width: 640px; margin: 0; color: #bfd0c8; font-size: 12px; line-height: 1.6; text-overflow: ellipsis; white-space: nowrap; }
.greet-side { position: relative; z-index: 2; display: flex; align-items: center; gap: 22px; flex: none; }
.mini-orbit { position: relative; display: grid; place-items: center; width: 92px; height: 92px; flex: none; border-radius: 50%; background: conic-gradient(#e8c47e var(--overall-progress), rgba(255, 255, 255, .12) 0); box-shadow: 0 14px 34px rgba(3, 20, 14, .22); }
.mini-orbit::before { position: absolute; inset: 8px; border-radius: 50%; background: #174333; content: ""; }
.mini-orbit > div { position: relative; z-index: 1; text-align: center; }
.mini-orbit strong { font: 500 24px var(--display); }
.mini-orbit small { color: #e9c77e; font-size: 9px; }
.mini-orbit em { display: block; margin-top: 2px; color: #9eb9ac; font-size: 8px; font-style: normal; letter-spacing: .08em; }
.greet-actions { display: flex; align-items: center; gap: 16px; }
.greet-strip :deep(.el-button--primary) { border-color: #e1b96f; color: #18362b; background: linear-gradient(145deg, #efd290, #d4a65c); box-shadow: 0 10px 24px rgba(4, 22, 16, .24); }
.text-action { border: 0; color: #dce8e1; background: transparent; font-size: 12px; }
.text-action span { display: inline-block; margin-left: 5px; transition: .2s; }
.text-action:hover span { transform: translate(3px, -3px); }

.signal-rail { display: grid; grid-template-columns: repeat(4, 1fr); padding: 6px 2px 12px; border-bottom: 1px solid var(--line); }
.signal-rail article { position: relative; display: grid; grid-template-columns: 30px 1fr; gap: 8px; padding: 16px 24px; }
.signal-rail article + article { border-left: 1px solid var(--line); }
.signal-rail article > span { color: #b1bab4; font: italic 11px var(--display); }
.signal-rail small, .signal-rail strong { display: block; }
.signal-rail small { color: var(--muted); font-size: 9px; font-weight: 700; letter-spacing: .08em; }
.signal-rail strong { margin-top: 6px; font: 500 29px var(--display); }
.signal-rail strong em { color: var(--muted); font: 500 10px Inter, sans-serif; }
.signal-rail p { grid-column: 2; margin: 1px 0 0; color: var(--muted); font-size: 9px; }

/* 图表卡片 */
.panel-card { padding: 26px 28px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: var(--paper-soft); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.card-note { color: var(--muted); font-size: 10px; }
.chart-row { display: grid; grid-template-columns: 1.5fr 1fr; gap: 20px; }
.chart { width: 100%; }
.chart-lg { height: 240px; }
.chart-sm { height: 214px; }

.dashboard-bento { display: grid; grid-template-columns: 1.25fr .75fr; gap: 20px; }
.itinerary-studio, .goal-cards { padding: 30px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: var(--paper-soft); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 15px; margin-bottom: 24px; }
.section-heading h2 { margin: 7px 0 0; font: 500 27px var(--display); }
.section-heading button { padding: 7px 0; border: 0; color: var(--green); background: transparent; font-size: 10px; font-weight: 700; }
.path-list { position: relative; }
.path-list::before { position: absolute; top: 28px; bottom: 28px; left: 87px; width: 1px; background: rgba(31, 88, 64, .13); content: ""; }
.path-list > button { position: relative; z-index: 1; display: grid; grid-template-columns: 58px 38px 1fr auto; align-items: center; gap: 11px; width: 100%; padding: 13px 0; border: 0; color: var(--ink); background: transparent; text-align: left; }
.path-list > button:hover div b { color: var(--green); }
.path-time { color: var(--muted); font: 500 11px ui-monospace, monospace; }
.path-list i { display: grid; place-items: center; width: 30px; height: 30px; border: 5px solid var(--paper-solid); border-radius: 50%; color: var(--green); background: var(--chip); font-size: 9px; font-style: normal; font-weight: 700; box-shadow: 0 0 0 1px rgba(31, 88, 64, .12); }
.path-list i.done { color: #fff; background: var(--green); }
.path-list b, .path-list small { display: block; }
.path-list b { transition: .2s; font-size: 12px; }
.path-list small { margin-top: 5px; color: var(--muted); font-size: 9px; }
.path-list em { padding: 5px 9px; border-radius: 99px; color: var(--muted); background: var(--chip); font-size: 8px; font-style: normal; }
.editorial-empty { display: flex; align-items: center; gap: 14px; padding: 35px 0; }
.editorial-empty > span { display: grid; place-items: center; width: 46px; height: 46px; border-radius: 50%; color: var(--muted); background: var(--chip); }
.editorial-empty b { font: 500 17px var(--display); }
.editorial-empty p { margin: 5px 0 0; color: var(--muted); font-size: 10px; }
.growth-column { display: grid; gap: 20px; }
.section-heading.compact { align-items: center; margin-bottom: 17px; }
.section-heading.compact h2 { font-size: 22px; }
.goal-note { display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 11px; width: 100%; padding: 14px 0; border: 0; border-top: 1px solid var(--line); color: var(--ink); background: transparent; text-align: left; }
.goal-note > span { color: var(--muted); font: italic 11px var(--display); }
.goal-note b, .goal-note small { display: block; }
.goal-note b { font-size: 11px; }
.goal-note small { margin-top: 5px; color: var(--muted); font-size: 8px; }
.goal-note i { color: var(--green); font-style: normal; transition: .2s; }
.goal-note:hover i { transform: translate(3px, -3px); }
.mini-empty { padding: 26px 0; color: var(--muted); font-size: 10px; text-align: center; }

@media (max-width: 1000px) {
  .greet-strip { align-items: flex-start; flex-direction: column; }
  .chart-row, .dashboard-bento { grid-template-columns: 1fr; }
  .signal-rail { grid-template-columns: repeat(2, 1fr); }
  .signal-rail article:nth-child(3) { border-left: 0; border-top: 1px solid var(--line); }
  .signal-rail article:nth-child(4) { border-top: 1px solid var(--line); }
}
@media (max-width: 620px) {
  .greet-strip { padding: 22px 20px; }
  .greet-copy p { white-space: normal; }
  .mini-orbit { width: 72px; height: 72px; }
  .mini-orbit strong { font-size: 19px; }
  .greet-actions { align-items: flex-start; flex-direction: column; gap: 12px; }
  .signal-rail article { padding: 14px 9px; }
  .signal-rail strong { font-size: 23px; }
  .itinerary-studio, .goal-cards, .panel-card { padding: 21px; border-radius: 22px; }
  .path-list::before { left: 37px; }
  .path-list > button { grid-template-columns: 30px 34px 1fr; }
  .path-time { font-size: 9px; }
  .path-list > button > em { display: none; }
}

/* 黑夜模式：scoped 覆盖 */
html.dark .signal-rail article > span { color: var(--muted); }
html.dark .itinerary-studio,
html.dark .goal-cards,
html.dark .panel-card { border-color: rgba(255, 255, 255, .08); }
</style>
