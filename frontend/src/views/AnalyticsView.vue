<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { api } from '../api/http'

use([CanvasRenderer, BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent])

type Metric = {
  value: number | string | null
  numerator?: number | string | null
  denominator?: number | string | null
  periodStart?: string
  periodEnd?: string
  timezone?: string
  refreshedAt?: string
  metricVersion?: string
}

type MasterySnapshot = {
  knowledgePointId: number
  name: string
  score: number | string
  confidence: number | string
  level: string
  evidenceCount: number
}

type AnalyticsOverview = {
  metrics: Record<string, Metric>
  studyTime: Array<{ date: string; autoSeconds: number; manualSeconds: number; totalSeconds: number }>
  mastery: MasterySnapshot[]
}

type MasteryTrendSnapshot = {
  snapshotAt: string
  mastery: MasterySnapshot[]
  calcVersion: string
}

type StudyReport = {
  publicId: string
  type: string
  periodStart: string
  periodEnd: string
  timezone: string
  revisionNo: number
  metricSnapshotJson: string | AnalyticsOverview
  narrative: string
  status: string
  createdAt: string
}

const range = ref<[string, string]>([
  dayjs().subtract(29, 'day').format('YYYY-MM-DD'),
  dayjs().format('YYYY-MM-DD'),
])
const overview = ref<AnalyticsOverview>({ metrics: {}, studyTime: [], mastery: [] })
const masteryTrend = ref<MasteryTrendSnapshot[]>([])
const reports = ref<StudyReport[]>([])
const loading = ref(false)
const generating = ref(false)
const reportLoading = ref(false)
const reportDrawerVisible = ref(false)
const selectedReport = ref<StudyReport | null>(null)

const m = computed(() => overview.value.metrics || {})
const chart = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 42, right: 18, top: 25, bottom: 35 },
  xAxis: {
    type: 'category',
    data: overview.value.studyTime.map((x) => dayjs(x.date).format('M/D')),
    axisLine: { lineStyle: { color: '#d8ddd7' } },
  },
  yAxis: {
    type: 'value',
    axisLabel: { formatter: (value: number) => `${Math.round(value / 60)}m` },
    splitLine: { lineStyle: { color: '#e8eae5' } },
  },
  series: [
    {
      name: '自动记录',
      type: 'bar',
      stack: 'time',
      data: overview.value.studyTime.map((x) => x.autoSeconds),
      itemStyle: { color: '#1f6b4f', borderRadius: [3, 3, 0, 0] },
    },
    {
      name: '手工补录',
      type: 'bar',
      stack: 'time',
      data: overview.value.studyTime.map((x) => x.manualSeconds),
      itemStyle: { color: '#d39a3c' },
    },
  ],
}))

const masteryTrendChart = computed(() => {
  const names = [...new Set(masteryTrend.value.flatMap((snapshot) => snapshot.mastery.map((item) => item.name)))].slice(0, 8)
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: names, bottom: 0 },
    grid: { left: 42, right: 18, top: 25, bottom: 52 },
    xAxis: { type: 'category', data: masteryTrend.value.map((snapshot) => dayjs(snapshot.snapshotAt).format('M/D HH:mm')) },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series: names.map((name) => ({
      name,
      type: 'line',
      smooth: true,
      connectNulls: true,
      data: masteryTrend.value.map((snapshot) => {
        const item = snapshot.mastery.find((value) => value.name === name)
        return item ? Math.round(Number(item.score)) : null
      }),
    })),
  }
})

const reportSnapshot = computed<AnalyticsOverview | null>(() => {
  const raw = selectedReport.value?.metricSnapshotJson
  if (!raw) return null
  if (typeof raw !== 'string') return raw
  try {
    return JSON.parse(raw) as AnalyticsOverview
  } catch {
    return null
  }
})

const metricDefinitions = [
  { key: 'effectiveStudySeconds', label: '有效学习时长', kind: 'duration' },
  { key: 'automaticStudySeconds', label: '自动记录', kind: 'duration' },
  { key: 'manualStudySeconds', label: '手工补录', kind: 'duration' },
  { key: 'plannedTasks', label: '计划任务', kind: 'count' },
  { key: 'completedTasks', label: '完成任务', kind: 'count' },
  { key: 'taskCompletionRate', label: '任务完成率', kind: 'rate' },
  { key: 'onTimeCompletionRate', label: '按时完成率', kind: 'rate' },
  { key: 'overdueRate', label: '逾期率', kind: 'rate' },
] as const

const reportMetrics = computed(() => metricDefinitions
  .map((definition) => ({ ...definition, metric: reportSnapshot.value?.metrics?.[definition.key] }))
  .filter((item) => item.metric))

function formatMetric(value: Metric['value'], kind: 'duration' | 'count' | 'rate') {
  if (value === null || value === undefined) return '不适用'
  const numeric = Number(value)
  if (kind === 'duration') {
    if (!Number.isFinite(numeric)) return String(value)
    if (numeric < 3600) return `${Math.round(numeric / 60)} 分钟`
    return `${Math.round(numeric / 360) / 10} 小时`
  }
  if (kind === 'rate') return `${value}%`
  return `${value} 项`
}

function formatDateTime(value: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'
}

function reportTypeLabel(type: string) {
  return ({ CUSTOM: '自定义周期', WEEKLY: '周报', STAGE: '阶段报告' } as Record<string, string>)[type] || type
}

async function load() {
  loading.value = true
  try {
    ;[overview.value, reports.value, masteryTrend.value] = await Promise.all([
      api<AnalyticsOverview>({
        url: '/analytics/overview',
        params: { start: range.value[0], end: range.value[1] },
      }),
      api<StudyReport[]>({ url: '/reports' }),
      api<MasteryTrendSnapshot[]>({ url: '/analytics/mastery-trend' }),
    ])
  } finally {
    loading.value = false
  }
}

async function viewReport(report: StudyReport) {
  selectedReport.value = report
  reportDrawerVisible.value = true
  reportLoading.value = true
  try {
    selectedReport.value = await api<StudyReport>({ url: `/reports/${report.publicId}` })
  } finally {
    reportLoading.value = false
  }
}

async function generateReport() {
  generating.value = true
  try {
    const generated = await api<StudyReport>({
      method: 'POST',
      url: '/reports/generation-jobs',
      data: { type: 'CUSTOM', periodStart: range.value[0], periodEnd: range.value[1] },
    })
    await load()
    await viewReport(generated)
  } finally {
    generating.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-head">
      <div>
        <h2>让进步可见，也让不确定性可见</h2>
        <p>所有指标包含分子、分母、时区、刷新时间和算法版本；无分母时显示“不适用”。</p>
      </div>
      <div class="toolbar">
        <el-date-picker
          v-model="range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始"
          end-placeholder="结束"
          @change="load"
        />
        <el-button type="primary" :loading="generating" @click="generateReport">生成报告</el-button>
      </div>
    </div>

    <div class="grid grid-4">
      <div class="panel stat">
        <span class="label">有效学习时长</span>
        <div class="value">{{ Math.round(Number(m.effectiveStudySeconds?.value || 0) / 3600 * 10) / 10 }}<small> 小时</small></div>
      </div>
      <div class="panel stat">
        <span class="label">任务完成率</span>
        <div class="value">{{ m.taskCompletionRate?.value ?? '—' }}<small v-if="m.taskCompletionRate?.value">%</small></div>
        <span v-if="m.taskCompletionRate?.value == null" class="muted">期间无计划任务</span>
      </div>
      <div class="panel stat">
        <span class="label">按时完成率</span>
        <div class="value">{{ m.onTimeCompletionRate?.value ?? '—' }}<small v-if="m.onTimeCompletionRate?.value">%</small></div>
      </div>
      <div class="panel stat">
        <span class="label">逾期率</span>
        <div class="value">{{ m.overdueRate?.value ?? '—' }}<small v-if="m.overdueRate?.value">%</small></div>
      </div>
    </div>

    <div class="grid analytics-grid">
      <section class="panel">
        <div class="panel-title">
          <div><h3>每日学习时长</h3><p>自动计时与手工补录分别展示，避免数据口径混淆。</p></div>
        </div>
        <v-chart class="chart" :option="chart" autoresize />
      </section>
      <section class="panel">
        <div class="panel-title">
          <div><h3>掌握度快照</h3><p>分数不等同于置信度</p></div>
        </div>
        <div v-if="!overview.mastery.length" class="empty">暂无掌握度数据</div>
        <div v-for="item in overview.mastery.slice(0, 8)" :key="item.knowledgePointId" class="mastery-row">
          <div>
            <b>{{ item.name }}</b>
            <small>{{ item.evidenceCount }} 条证据 · 置信度 {{ Math.round(Number(item.confidence) * 100) }}%</small>
          </div>
          <el-progress :percentage="Math.round(Number(item.score))" />
        </div>
      </section>
    </div>

    <section v-if="masteryTrend.length" class="panel mastery-trend">
      <div class="panel-title">
        <div><h3>掌握度变化趋势</h3><p>每次测验完成后保存快照，可以看到知识点掌握度如何变化。</p></div>
      </div>
      <v-chart class="chart" :option="masteryTrendChart" autoresize />
    </section>

    <section class="panel reports">
      <div class="panel-title">
        <div><h3>学习报告</h3><p>同一周期重新生成会保留修订号，点击查看可回溯当时的指标快照。</p></div>
      </div>
      <el-table :data="reports" row-key="publicId">
        <el-table-column label="类型" min-width="110">
          <template #default="{ row }">{{ reportTypeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="周期" min-width="210">
          <template #default="{ row }">{{ row.periodStart }} — {{ row.periodEnd }}</template>
        </el-table-column>
        <el-table-column prop="revisionNo" label="修订" width="80">
          <template #default="{ row }">R{{ row.revisionNo }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'COMPLETED' ? 'success' : 'info'" effect="plain">
              {{ row.status === 'COMPLETED' ? '已完成' : row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成时间" min-width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewReport(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="empty">还没有报告，先选择周期并生成一份。</div></template>
      </el-table>
    </section>

    <el-drawer v-model="reportDrawerVisible" size="min(720px, 100%)" destroy-on-close>
      <template #header>
        <div v-if="selectedReport" class="report-drawer-head">
          <div>
            <span>学习报告 · R{{ selectedReport.revisionNo }}</span>
            <h2>{{ reportTypeLabel(selectedReport.type) }}</h2>
          </div>
          <el-tag type="success" effect="plain">{{ selectedReport.status === 'COMPLETED' ? '已完成' : selectedReport.status }}</el-tag>
        </div>
      </template>

      <div v-if="selectedReport" v-loading="reportLoading" class="report-detail">
        <div class="report-meta">
          <div><small>统计周期</small><b>{{ selectedReport.periodStart }} — {{ selectedReport.periodEnd }}</b></div>
          <div><small>统计时区</small><b>{{ selectedReport.timezone }}</b></div>
          <div><small>生成时间</small><b>{{ formatDateTime(selectedReport.createdAt) }}</b></div>
        </div>

        <section class="report-section narrative">
          <span class="section-kicker">REPORT SUMMARY</span>
          <h3>周期总结</h3>
          <p>{{ selectedReport.narrative || '本报告暂未生成文字总结。' }}</p>
        </section>

        <section class="report-section">
          <span class="section-kicker">METRIC SNAPSHOT</span>
          <h3>指标快照</h3>
          <p class="section-note">以下数字冻结于报告生成时，不会随之后的数据修改而改变。</p>
          <div v-if="reportMetrics.length" class="report-metrics">
            <div v-for="item in reportMetrics" :key="item.key" class="report-metric">
              <small>{{ item.label }}</small>
              <b>{{ formatMetric(item.metric!.value, item.kind) }}</b>
              <span v-if="item.kind === 'rate' && item.metric!.denominator !== null && item.metric!.denominator !== undefined">
                {{ item.metric!.numerator ?? 0 }} / {{ item.metric!.denominator }}
              </span>
            </div>
          </div>
          <el-empty v-else description="报告指标快照无法解析" :image-size="72" />
        </section>

        <section v-if="reportSnapshot?.mastery?.length" class="report-section">
          <span class="section-kicker">MASTERY SNAPSHOT</span>
          <h3>掌握度证据</h3>
          <div v-for="item in reportSnapshot.mastery.slice(0, 10)" :key="item.knowledgePointId" class="report-mastery">
            <div>
              <b>{{ item.name }}</b>
              <small>{{ item.level }} · {{ item.evidenceCount }} 条证据 · 置信度 {{ Math.round(Number(item.confidence) * 100) }}%</small>
            </div>
            <strong>{{ Math.round(Number(item.score)) }}%</strong>
          </div>
        </section>

        <p class="report-footnote">
          指标版本 {{ reportSnapshot?.metrics?.effectiveStudySeconds?.metricVersion || '—' }} ·
          报告 ID {{ selectedReport.publicId }}
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.analytics-grid { grid-template-columns: 1.5fr 1fr; margin-top: 18px; }
.chart { height: 330px; }
.mastery-trend { margin-top: 18px; }
.mastery-row { border-top: 1px solid var(--line); padding: 12px 0; }
.mastery-row b,
.mastery-row small { display: block; }
.mastery-row small { font-size: 10px; color: var(--muted); margin: 4px 0; }
.reports { margin-top: 18px; }
.report-drawer-head { display: flex; align-items: center; justify-content: space-between; width: 100%; padding-right: 14px; }
.report-drawer-head span { color: var(--muted); font-size: 12px; letter-spacing: .08em; }
.report-drawer-head h2 { margin: 4px 0 0; font-family: var(--display); color: var(--ink); }
.report-detail { min-height: 420px; }
.report-meta { display: grid; grid-template-columns: 1.5fr 1fr 1fr; gap: 10px; margin-bottom: 24px; }
.report-meta div { padding: 13px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--paper-soft); }
.report-meta small,
.report-meta b { display: block; }
.report-meta small { margin-bottom: 5px; color: var(--muted); }
.report-meta b { color: var(--ink); font-size: 13px; }
.report-section { padding: 25px 0; border-top: 1px solid var(--line); }
.report-section h3 { margin: 5px 0 8px; font-family: var(--display); font-size: 22px; color: var(--ink); }
.section-kicker { color: var(--brand); font-size: 10px; font-weight: 700; letter-spacing: .17em; }
.section-note { margin: 0 0 16px; color: var(--muted); font-size: 12px; }
.narrative p { margin: 12px 0 0; padding: 18px 20px; border-left: 3px solid var(--gold); background: var(--paper-soft); color: var(--ink); line-height: 1.9; white-space: pre-wrap; }
.report-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.report-metric { min-height: 102px; padding: 14px; border: 1px solid var(--line); border-radius: 13px; background: var(--paper-soft); }
.report-metric small,
.report-metric b,
.report-metric span { display: block; }
.report-metric small { color: var(--muted); }
.report-metric b { margin: 9px 0 4px; color: var(--ink); font-family: var(--display); font-size: 20px; }
.report-metric span { color: var(--muted); font-size: 11px; }
.report-mastery { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 13px 0; border-top: 1px solid var(--line); }
.report-mastery b,
.report-mastery small { display: block; }
.report-mastery small { margin-top: 4px; color: var(--muted); font-size: 11px; }
.report-mastery strong { color: var(--brand); font-family: var(--display); font-size: 20px; }
.report-footnote { overflow-wrap: anywhere; color: var(--muted); font-size: 10px; line-height: 1.7; }

@media (max-width: 900px) {
  .analytics-grid { grid-template-columns: 1fr; }
  .report-meta { grid-template-columns: 1fr; }
  .report-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
