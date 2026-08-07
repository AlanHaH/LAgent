<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { api } from '../api/http'
import { isDark } from '../theme'

use([CanvasRenderer, BarChart, PieChart, GridComponent, LegendComponent, TooltipComponent])

type PageData<T> = { items:T[]; total:number; page:number; pageSize:number }

const tab = ref('overview')
const loading = ref(false)
const metrics = ref<any>({})
const jobs = ref<any>({ planning:[], documents:[], modelRuns:[], running:0, failed:0, outboxPending:0 })
const activeGoals = ref<any[]>([])
const activeGoalsVisible = ref(false)
const activeGoalsLoading = ref(false)
const users = ref<any[]>([])
const roles = ref<any[]>([])
const directions = ref<any[]>([])
const knowledge = ref<any[]>([])
const dependencies = ref<any[]>([])
const questions = ref<any[]>([])
const models = ref<any[]>([])
const prompts = ref<any[]>([])
const audits = ref<any[]>([])
const appeals = ref<any[]>([])
const appealStatus = ref('PENDING')
const charts = ref<any>({})
const allSpaces = ref<any[]>([])
const spaceDocuments = ref<Record<string, any[]>>({})
const spaceDocsLoading = ref('')
const knowledgeQuery = reactive({ keyword:'', userId:'' })
const questionDetail = ref<any>(null)
const contentDrawer = ref(false)
const contentDoc = ref<any>(null)
const contentChunks = ref<any[]>([])
const contentIndex = ref(0)
const contentLoading = ref(false)

const userQuery = reactive({ page:1, pageSize:20, total:0, keyword:'', status:'' })
const questionQuery = reactive({ page:1, pageSize:20, total:0, keyword:'', type:'', status:'' })
const auditQuery = reactive({ page:1, pageSize:20, total:0, keyword:'', action:'', result:'' })
const catalogQuery = reactive({ directionId:'', keyword:'' })
const jobTab = ref('planning')
const jobQuery = reactive({ status:'', keyword:'' })
const jobRecords = ref<any>({ planning:[], documents:[], modelRuns:[] })

const roleDialog = ref(false)
const selectedUser = ref<any>(null)
const roleForm = reactive({ roles:[] as string[], reason:'' })
const learningFileDrawer = ref(false)
const learningFileLoading = ref(false)
const learningFileTab = ref('profile')
const learningFile = ref<any>(null)
const directionDialog = ref(false)
const directionForm = reactive<any>({ id:null, parentId:null, code:'', name:'', status:'ACTIVE', sortNo:100, version:null })
const knowledgeDialog = ref(false)
const knowledgeForm = reactive<any>({ id:null, directionId:'', parentId:null, code:'', name:'', level:1, defaultWeight:1, status:'ACTIVE', version:null })
const dependencyDialog = ref(false)
const dependencyForm = reactive<any>({ predecessorId:'', successorId:'' })
const questionDialog = ref(false)
const questionForm = reactive<any>({ type:'SINGLE_CHOICE', stem:'', optionsText:'', answerText:'A', rubricText:'', analysis:'', difficulty:2, knowledgePointIds:[] })
const modelDialog = ref(false)
const modelForm = reactive<any>({ publicId:'', version:null, status:'DISABLED', provider:'OPENAI_COMPATIBLE', providerName:'DeepSeek', baseUrl:'https://api.deepseek.com', secretRef:'', purpose:'GENERAL', modelName:'', parametersText:'{"maxOutputTokens":1200,"thinking":"disabled"}', timeoutSeconds:60, dailyLimit:1000 })
const modelActionId = ref('')
const promptDialog = ref(false)
const promptForm = reactive<any>({ code:'', content:'', schemaText:'' })
const promptDetail = ref<any>(null)
const auditDetail = ref<any>(null)

const selectedDirectionPoints = computed(() => knowledge.value.filter(item =>
  !catalogQuery.directionId || String(item.directionId) === String(catalogQuery.directionId)))
const dependencyCandidates = computed(() => knowledge.value.filter(item =>
  !catalogQuery.directionId || String(item.directionId) === String(catalogQuery.directionId)))
const activeDirectionName = computed(() =>
  directions.value.find(item => String(item.id) === String(catalogQuery.directionId))?.name || '全部方向')
// 学习方向按父方向聚合：父级在前、子级缩进展示；agg 为该方向子树的知识点总数（含子孙）
const directionTree = computed(() => {
  const byId = new Map(directions.value.map((d:any) => [String(d.id), d]))
  const childrenOf = new Map<string, any[]>()
  for (const d of directions.value) {
    const pid = d.parentId != null ? String(d.parentId) : ''
    if (pid && byId.has(pid)) {
      if (!childrenOf.has(pid)) childrenOf.set(pid, [])
      childrenOf.get(pid)!.push(d)
    }
  }
  const roots = directions.value.filter((d:any) => !(d.parentId != null && byId.has(String(d.parentId))))
  const flat:any[] = []
  const visit = (d:any, depth:number): number => {
    const children = childrenOf.get(String(d.id)) || []
    const direct = d.knowledgeCount || 0
    // 父级先入数组（占位，agg 先填 0），再递归子级，保证树按「父在上、子在下」输出
    const entry = { item: d, depth, childCount: children.length, agg: 0 }
    flat.push(entry)
    let childAgg = 0
    for (const c of children) childAgg += visit(c, depth + 1)
    entry.agg = direct + childAgg
    return entry.agg
  }
  for (const r of roots) visit(r, 0)
  return flat
})
const adoptedRecommendationIds = computed(() => new Set(
  (learningFile.value?.goals || [])
    .map((goal:any) => goal.recommendation?.recommendationId)
    .filter(Boolean),
))

function statusType(status:string) {
  if (['ACTIVE','SUCCESS','SUCCEEDED','PUBLISHED','INDEXED'].includes(status)) return 'success'
  if (['FAILED','DISABLED','LOCKED','CANCELED'].includes(status)) return 'danger'
  if (['RUNNING','QUEUED','DRAFT','PENDING'].includes(status)) return 'warning'
  return 'info'
}
function fmt(value:any) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12:false })
}
function duration(ms:any) {
  const value = Number(ms || 0)
  return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
}
function studyHours(seconds:any) { return `${(Number(seconds || 0) / 3600).toFixed(1)} h` }
function scorePercent(value:any) {
  const score = Number(value || 0)
  return `${score <= 1 ? (score * 100).toFixed(0) : score.toFixed(0)}%`
}
function weekday(value:any) { return `周${['一','二','三','四','五','六','日'][Number(value) - 1] || value}` }
function recommendationsOf(batch:any) { return batch?.response?.recommendations || [] }
function parseJson(text:string, fallback:any) {
  if (!text.trim()) return fallback
  try { return JSON.parse(text) } catch { throw new Error('JSON 格式不正确') }
}
function errorMessage(error:any) { return error?.response?.data?.error?.message || error?.response?.data?.message || error?.message || '操作失败' }
// 状态标签中文化：tag 显示中文，原始枚举码保留在 title 里便于悬停查看
const statusLabels: Record<string,string> = {
  ACTIVE:'启用', DISABLED:'停用', LOCKED:'锁定', DRAFT:'草稿', PUBLISHED:'已发布', ARCHIVED:'已归档',
  SUCCESS:'成功', SUCCEEDED:'成功', FAILED:'失败', RUNNING:'运行中', QUEUED:'排队中', PENDING:'待处理',
  INDEXED:'已索引', UPLOADED:'已上传', SECURITY_CHECKING:'安全校验', PARSING:'解析中', OCR_PROCESSING:'OCR 中',
  CHUNKING:'切块中', PARSE_FAILED:'解析失败', INDEX_FAILED:'索引失败',
  CANCELED:'已取消', COMPLETED:'已完成', NOT_STARTED:'未开始', IN_PROGRESS:'进行中', PAUSED:'已暂停',
  URGENT:'紧急', HIGH:'高', MEDIUM:'中', LOW:'低', PRIVATE:'私有',
}
function statusLabel(status:any) { return statusLabels[String(status || '')] || String(status || '') }
function formatSize(bytes:any) {
  const value = Number(bytes || 0)
  if (value >= 1024 * 1024 * 1024) return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`
  if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${value} B`
}
// 分类路径以 "/" 开头（如 "/教材"），显示时去掉前导斜杠
function displayPath(path:any) { return String(path || '').replace(/^\/+/, '') }
// ---- 总览图表调色板（跟随黑夜模式） ----
const axisColor = computed(() => (isDark.value ? '#93a79b' : '#9aa59e'))
const splitColor = computed(() => (isDark.value ? 'rgba(255, 255, 255, .08)' : '#e9ebe6'))
const legendColor = computed(() => (isDark.value ? '#93a79b' : '#7c8a82'))
const green = '#2d8a63', gold = '#d9a03f', red = '#c0564f'
const chartText = { color: legendColor.value, fontSize: 10 }
function goalColor(status:string) { return { ACTIVE:green, COMPLETED:'#7fb39a', CANCELED:red, DRAFT:'#b9c2bb' }[status] || '#9aa59e' }

// 近 14 日学习时长：自动计时 + 手工补录 堆叠柱状
const studyChart = computed(() => ({
  tooltip: {
    trigger: 'axis', axisPointer: { type: 'shadow' },
    formatter: (params:any[]) => {
      const date = (charts.value.studyDaily || [])[params[0]?.dataIndex]?.date
      return `${date || ''}<br/>${params.map((p:any) => `${p.marker}${p.seriesName}：${(Number(p.value || 0) / 3600).toFixed(1)} h`).join('<br/>')}`
    },
  },
  grid: { left: 44, right: 14, top: 30, bottom: 28 },
  legend: { data: ['自动记录', '手工补录'], top: 0, itemWidth: 9, itemHeight: 9, textStyle: chartText },
  xAxis: { type: 'category', data: (charts.value.studyDaily || []).map((x:any) => String(x.date).slice(5)), axisLine: { lineStyle: { color: splitColor.value } }, axisTick: { show: false }, axisLabel: { color: axisColor.value } },
  yAxis: { type: 'value', axisLabel: { formatter: (v:number) => `${Math.round(v / 3600)}h`, color: axisColor.value }, splitLine: { lineStyle: { color: splitColor.value } } },
  series: [
    { name: '自动记录', type: 'bar', stack: 't', data: (charts.value.studyDaily || []).map((x:any) => x.autoSeconds), itemStyle: { color: green, borderRadius: [3, 3, 0, 0] } },
    { name: '手工补录', type: 'bar', stack: 't', data: (charts.value.studyDaily || []).map((x:any) => x.manualSeconds), itemStyle: { color: gold } },
  ],
}))

// 近 14 日任务完成：计划 / 完成 / 逾期
const taskTrendChart = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 40, right: 14, top: 30, bottom: 28 },
  legend: { data: ['计划', '完成', '逾期'], top: 0, itemWidth: 9, itemHeight: 9, textStyle: chartText },
  xAxis: { type: 'category', data: (charts.value.taskDaily || []).map((x:any) => String(x.date).slice(5)), axisLine: { lineStyle: { color: splitColor.value } }, axisTick: { show: false }, axisLabel: { color: axisColor.value } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: axisColor.value }, splitLine: { lineStyle: { color: splitColor.value } } },
  series: [
    { name: '计划', type: 'bar', data: (charts.value.taskDaily || []).map((x:any) => x.planned), itemStyle: { color: '#a8c3b2' } },
    { name: '完成', type: 'bar', data: (charts.value.taskDaily || []).map((x:any) => x.completed), itemStyle: { color: green } },
    { name: '逾期', type: 'bar', data: (charts.value.taskDaily || []).map((x:any) => x.overdue), itemStyle: { color: red } },
  ],
}))

// 近 7 日模型调用：成功 / 失败 堆叠柱状
const modelChart = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 40, right: 14, top: 30, bottom: 28 },
  legend: { data: ['成功', '失败'], top: 0, itemWidth: 9, itemHeight: 9, textStyle: chartText },
  xAxis: { type: 'category', data: (charts.value.modelDaily || []).map((x:any) => String(x.date).slice(5)), axisLine: { lineStyle: { color: splitColor.value } }, axisTick: { show: false }, axisLabel: { color: axisColor.value } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: axisColor.value }, splitLine: { lineStyle: { color: splitColor.value } } },
  series: [
    { name: '成功', type: 'bar', stack: 'm', data: (charts.value.modelDaily || []).map((x:any) => x.success), itemStyle: { color: green, borderRadius: [3, 3, 0, 0] } },
    { name: '失败', type: 'bar', stack: 'm', data: (charts.value.modelDaily || []).map((x:any) => x.failed), itemStyle: { color: red } },
  ],
}))

// 目标状态分布：环形图
const goalChart = computed(() => ({
  tooltip: { trigger: 'item', formatter: '{b}：{c} 个（{d}%）' },
  legend: { bottom: 0, itemWidth: 9, itemHeight: 9, textStyle: chartText },
  series: [{
    type: 'pie', radius: ['46%', '70%'], center: ['50%', '42%'],
    itemStyle: { borderColor: isDark.value ? '#151d19' : '#fafbf7', borderWidth: 2 },
    label: { show: false },
    emphasis: { label: { show: true, fontWeight: 600, formatter: '{b}\n{c} 个' } },
    data: (charts.value.goalStatus || []).map((x:any) => ({ name: statusLabel(x.status), value: x.count, itemStyle: { color: goalColor(x.status) } })),
  }],
}))

// 近 7 日学习时长 Top 5：横向条形
const topLearnersChart = computed(() => ({
  tooltip: {
    trigger: 'axis', axisPointer: { type: 'shadow' },
    formatter: (params:any) => {
      const point = params?.[0]
      if (!point) return ''
      const item = (charts.value.topLearners || [])[point.dataIndex]
      return `${item?.username || ''}<br/>${(Number(point.value || 0) / 3600).toFixed(1)} 小时`
    },
  },
  grid: { left: 70, right: 34, top: 10, bottom: 26 },
  xAxis: { type: 'value', axisLabel: { formatter: (v:number) => `${Math.round(v / 3600)}h`, color: axisColor.value }, splitLine: { lineStyle: { color: splitColor.value } } },
  yAxis: { type: 'category', inverse: true, data: (charts.value.topLearners || []).map((x:any) => x.username), axisLine: { lineStyle: { color: splitColor.value } }, axisTick: { show: false }, axisLabel: { color: axisColor.value } },
  series: [{
    type: 'bar', data: (charts.value.topLearners || []).map((x:any) => x.seconds),
    itemStyle: { color: green, borderRadius: [0, 3, 3, 0] }, barWidth: 12,
    label: { show: true, position: 'right', formatter: (p:any) => `${(Number(p.value || 0) / 3600).toFixed(1)}h`, color: axisColor.value, fontSize: 9 },
  }],
}))

function openSection(name:string, childTab?:string) {
  if (childTab) jobTab.value = childTab
  if (tab.value === name) {
    void loadTab(name)
    return
  }
  tab.value = name
}

async function loadActiveGoals() {
  activeGoalsLoading.value = true
  try {
    activeGoals.value = await api<any[]>({ url:'/admin/active-goals' })
  } finally {
    activeGoalsLoading.value = false
  }
}

async function toggleActiveGoals() {
  activeGoalsVisible.value = !activeGoalsVisible.value
  if (activeGoalsVisible.value) await loadActiveGoals()
}

function openGoalOwner(row:any) {
  void openLearningFile({
    publicId:row.userPublicId,
    username:row.username,
    email:row.email,
    status:'ACTIVE',
  })
}

async function loadOverview() {
  const [metricData, jobData, chartData] = await Promise.all([
    api<any>({ url:'/admin/system-metrics' }),
    api<any>({ url:'/admin/jobs' }),
    api<any>({ url:'/admin/dashboard-charts' }),
  ])
  metrics.value = metricData
  jobs.value = { planning:[], documents:[], modelRuns:[], running:0, failed:0, outboxPending:0, ...jobData }
  jobRecords.value = { planning: jobData.planning ?? [], documents: jobData.documents ?? [], modelRuns: jobData.modelRuns ?? [] }
  charts.value = chartData
}
// 运行记录筛选：只刷新表格数据（jobRecords），jobs 保留全局汇总供概览与摘要使用
const jobPlaceholder = computed(() =>
  jobTab.value === 'planning' ? '目标名称 / 用户名'
    : jobTab.value === 'documents' ? '文档名称 / 用户名'
      : '用途 / 错误码 / 用户名')
async function loadJobs() {
  const params:any = {}
  if (jobQuery.status) params.status = jobQuery.status
  if (jobQuery.keyword.trim()) params.keyword = jobQuery.keyword.trim()
  jobRecords.value = await api<any>({ url:'/admin/jobs', params })
}
function resetJobs() {
  jobQuery.status = ''
  jobQuery.keyword = ''
  loadJobs()
}
async function loadKnowledge() {
  const params:any = {}
  if (knowledgeQuery.keyword.trim()) params.keyword = knowledgeQuery.keyword.trim()
  if (knowledgeQuery.userId) params.userId = knowledgeQuery.userId
  allSpaces.value = await api<any[]>({ url:'/admin/knowledge-spaces', params })
}
function toggleSpaceDocs(space:any, expanded:any[]) {
  const open = expanded.some((item:any) => item.publicId === space.publicId)
  if (!open) { delete spaceDocuments.value[space.publicId]; return }
  if (spaceDocuments.value[space.publicId]) return
  spaceDocsLoading.value = space.publicId
  api<any[]>({ url:`/admin/knowledge-spaces/${space.publicId}/documents` })
    .then(docs => { spaceDocuments.value[space.publicId] = docs })
    .catch(() => ElMessage.error('加载文档失败'))
    .finally(() => { spaceDocsLoading.value = '' })
}
const currentChunk = computed(() => contentChunks.value[contentIndex.value] || null)
function chunkTitle(chunk:any) {
  try {
    const titlePath = JSON.parse(chunk.titlePath || '[]')
    if (Array.isArray(titlePath) && titlePath.length) return titlePath[titlePath.length - 1]
  } catch { /* 标题缺失时忽略 */ }
  return ''
}
function jumpChunk(index:number) {
  contentIndex.value = Math.max(0, Math.min(contentChunks.value.length - 1, Number(index) || 0))
}
async function openDocumentContent(doc:any, space:any) {
  contentDoc.value = { ...doc, spaceName: space?.name, owner: space?.username }
  contentIndex.value = 0
  contentChunks.value = []
  contentDrawer.value = true
  contentLoading.value = true
  try {
    contentChunks.value = await api<any[]>({ url:`/admin/documents/${doc.publicId}/content` })
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    contentLoading.value = false
  }
}
function openQuestionDetail(row:any) {
  let options:any[] = []
  try { options = typeof row.optionsJson === 'string' ? JSON.parse(row.optionsJson) : (row.optionsJson || []) } catch { /* 忽略损坏的 JSON */ }
  let answer:any = row.answerJson
  try { answer = typeof row.answerJson === 'string' ? JSON.parse(row.answerJson) : row.answerJson } catch { /* 按原样展示 */ }
  let rubric:any = null
  try { rubric = row.rubricJson ? (typeof row.rubricJson === 'string' ? JSON.parse(row.rubricJson) : row.rubricJson) : null } catch { /* 忽略损坏的 JSON */ }
  questionDetail.value = { ...row, options, answer, rubric }
}
function formatAnswer(answer:any) {
  if (Array.isArray(answer)) return answer.join('、')
  if (answer === null || answer === undefined) return '—'
  return String(answer)
}
async function loadUsers() {
  const [page, roleData] = await Promise.all([
    api<PageData<any>>({ url:'/admin/users', params:userQuery }),
    roles.value.length ? Promise.resolve(roles.value) : api<any[]>({ url:'/admin/roles' }),
  ])
  users.value = page.items
  userQuery.total = page.total
  roles.value = roleData
}
async function loadCatalog() {
  const [dirData, pointData, dependencyData] = await Promise.all([
    api<any[]>({ url:'/admin/learning-directions' }),
    api<any[]>({ url:'/admin/knowledge-points', params:{ directionId:catalogQuery.directionId || undefined, keyword:catalogQuery.keyword || undefined } }),
    api<any[]>({ url:'/admin/knowledge-dependencies', params:{ directionId:catalogQuery.directionId || undefined } }),
  ])
  directions.value = dirData
  knowledge.value = pointData
  dependencies.value = dependencyData
}
async function loadQuestions() {
  const page = await api<PageData<any>>({ url:'/admin/questions', params:questionQuery })
  questions.value = page.items
  questionQuery.total = page.total
  if (!directions.value.length || !knowledge.value.length) {
    const [dirData, pointData] = await Promise.all([
      api<any[]>({ url:'/admin/learning-directions' }),
      api<any[]>({ url:'/admin/knowledge-points' }),
    ])
    directions.value = dirData
    knowledge.value = pointData
  }
}
async function loadAi() {
  const [modelData, promptData] = await Promise.all([
    api<any[]>({ url:'/admin/model-configs' }),
    api<any[]>({ url:'/admin/prompt-templates' }),
  ])
  models.value = modelData
  prompts.value = promptData
}
async function loadAudits() {
  const page = await api<PageData<any>>({ url:'/admin/audit-logs', params:auditQuery })
  audits.value = page.items
  auditQuery.total = page.total
}
async function loadAppeals() {
  appeals.value = await api<any[]>({ url:'/admin/evaluation/appeals', params:{ status:appealStatus.value || undefined } })
}
function showAudit(row:any) { auditDetail.value = row }
async function loadTab(name = tab.value) {
  loading.value = true
  try {
    if (name === 'overview' || name === 'jobs') await loadOverview()
    else if (name === 'users') await loadUsers()
    else if (name === 'knowledge') await loadKnowledge()
    else if (name === 'catalog') await loadCatalog()
    else if (name === 'questions') await loadQuestions()
    else if (name === 'ai') await loadAi()
    else if (name === 'appeals') await loadAppeals()
    else if (name === 'audit') await loadAudits()
  } finally { loading.value = false }
}

async function resolveAppeal(row:any, accepted:boolean) {
  const resolution = await ElMessageBox.prompt(accepted ? '填写接受申诉的复核说明' : '填写驳回申诉的复核说明',
    accepted ? '接受评分申诉' : '驳回评分申诉', { inputType:'textarea', inputValidator:value=>Boolean(value?.trim()) || '复核说明不能为空' })
    .then(result=>result.value).catch(()=>null)
  if (!resolution) return
  let correctedScore:number|undefined
  if (accepted) {
    const score = await ElMessageBox.prompt('请输入复核后的分数（不得超过该题满分）','修正分数',{
      inputValue:String(row.score ?? 0), inputPattern:/^\d+(\.\d{1,2})?$/, inputErrorMessage:'请输入有效分数',
    }).then(result=>Number(result.value)).catch(()=>undefined)
    if (score === undefined) return
    correctedScore = score
  }
  await api({ method:'POST', url:`/admin/evaluation/appeals/${row.publicId}/resolution`, data:{ accepted,resolution,correctedScore } })
  ElMessage.success('申诉已处理，评分版本与掌握度已经同步')
  await loadAppeals()
}
watch(tab, name => loadTab(name))
onMounted(() => loadTab())

async function changeUserStatus(user:any) {
  const target = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const action = user.status === 'LOCKED' ? '解锁' : (target === 'ACTIVE' ? '启用' : '禁用')
  const reason = await ElMessageBox.prompt(`请输入${action}“${user.username}”的原因`, `${action}用户`, {
    inputPlaceholder: user.status === 'LOCKED'
      ? '解锁会清空失败计数与锁定时间，该原因会写入审计日志'
      : '该原因会写入审计日志',
    inputValidator:value => Boolean(value?.trim()) || '原因不能为空',
    confirmButtonText:`确认${action}`,
  }).then(result => result.value).catch(() => null)
  if (!reason) return
  await api({ method:'POST', url:`/admin/users/${user.publicId}/status`, data:{ status:target, version:user.version, reason } })
  ElMessage.success(`用户已${action}`)
  await loadUsers()
}
function openRoles(user:any) {
  selectedUser.value = user
  roleForm.roles = [...(user.roles || [])]
  roleForm.reason = ''
  roleDialog.value = true
}
async function openLearningFile(user:any) {
  selectedUser.value = user
  learningFile.value = null
  learningFileTab.value = 'profile'
  learningFileDrawer.value = true
  learningFileLoading.value = true
  try {
    learningFile.value = await api<any>({ url:`/admin/users/${user.publicId}/learning-file` })
  } catch (error) {
    learningFileDrawer.value = false
    ElMessage.error(errorMessage(error))
  } finally {
    learningFileLoading.value = false
  }
}
async function saveRoles() {
  if (!roleForm.roles.length || !roleForm.reason.trim()) return ElMessage.warning('请选择角色并填写原因')
  await api({ method:'PUT', url:`/admin/users/${selectedUser.value.publicId}/roles`, data:roleForm })
  roleDialog.value = false
  ElMessage.success('用户角色已更新，重新登录后生效')
  await loadUsers()
}

function openDirection(item?:any) {
  Object.assign(directionForm, item
    ? { id:item.id, parentId:item.parentId || null, code:item.code, name:item.name, status:item.status, sortNo:item.sortNo, version:item.version }
    : { id:null, parentId:null, code:'', name:'', status:'ACTIVE', sortNo:100, version:null })
  directionDialog.value = true
}
async function saveDirection() {
  if (!directionForm.code || !directionForm.name) return ElMessage.warning('请填写编码和名称')
  directionForm.code = directionForm.code.trim().toUpperCase()
  await api({ method:'POST', url:'/admin/learning-directions', data:directionForm })
  directionDialog.value = false
  ElMessage.success(directionForm.id ? '方向已更新' : '方向已创建')
  await loadCatalog()
}
function openKnowledge(item?:any) {
  Object.assign(knowledgeForm, item
    ? { id:item.id, directionId:item.directionId, parentId:item.parentId || null, code:item.code, name:item.name, level:item.level, defaultWeight:Number(item.defaultWeight), status:item.status, version:item.version }
    : { id:null, directionId:catalogQuery.directionId || directions.value[0]?.id || '', parentId:null, code:'', name:'', level:1, defaultWeight:1, status:'ACTIVE', version:null })
  knowledgeDialog.value = true
}
async function saveKnowledge() {
  if (!knowledgeForm.directionId || !knowledgeForm.code || !knowledgeForm.name) return ElMessage.warning('请完整填写知识点')
  knowledgeForm.code = knowledgeForm.code.trim().toUpperCase()
  await api({ method:'POST', url:'/admin/knowledge-points', data:knowledgeForm })
  knowledgeDialog.value = false
  ElMessage.success(knowledgeForm.id ? '知识点已更新' : '知识点已创建')
  await loadCatalog()
}
function openDependency() {
  dependencyForm.predecessorId = ''
  dependencyForm.successorId = ''
  dependencyDialog.value = true
}
async function saveDependency() {
  if (!dependencyForm.predecessorId || !dependencyForm.successorId) return ElMessage.warning('请选择两个知识点')
  await api({ method:'POST', url:'/admin/knowledge-dependencies', data:dependencyForm })
  dependencyDialog.value = false
  ElMessage.success('前置关系已创建')
  await loadCatalog()
}
async function deleteDependency(item:any) {
  await ElMessageBox.confirm(`删除“${item.predecessorName} → ${item.successorName}”吗？`, '删除前置关系', { type:'warning' })
  await api({ method:'DELETE', url:'/admin/knowledge-dependencies', params:{ predecessorId:item.predecessorId, successorId:item.successorId } })
  ElMessage.success('前置关系已删除')
  await loadCatalog()
}

function openQuestion() {
  Object.assign(questionForm, { type:'SINGLE_CHOICE', stem:'', optionsText:'', answerText:'A', rubricText:'', analysis:'', difficulty:2, knowledgePointIds:[] })
  questionDialog.value = true
}
async function saveQuestion() {
  try {
    const options = questionForm.optionsText.split('\n').map((item:string) => item.trim()).filter(Boolean)
    let answer:any = questionForm.answerText.trim()
    if (questionForm.type === 'MULTIPLE_CHOICE') answer = answer.split(/[,，]/).map((item:string) => item.trim()).filter(Boolean)
    else if (answer.startsWith('{') || answer.startsWith('[') || answer === 'true' || answer === 'false') answer = parseJson(answer, answer)
    const rubric = parseJson(questionForm.rubricText, null)
    await api({
      method:'POST', url:'/admin/questions',
      data:{
        type:questionForm.type, stem:questionForm.stem, options,
        answer, rubric, analysis:questionForm.analysis || null,
        difficulty:questionForm.difficulty, knowledgePointIds:questionForm.knowledgePointIds,
      },
    })
    questionDialog.value = false
    ElMessage.success('公共题目已发布')
    await loadQuestions()
  } catch (error) { ElMessage.error(errorMessage(error)) }
}

function openModel(item?:any) {
  const parametersText = item
    ? (typeof item.parametersJson === 'string' ? item.parametersJson : JSON.stringify(item.parametersJson || {}, null, 2))
    : '{"maxOutputTokens":1200,"thinking":"disabled"}'
  Object.assign(modelForm, item
    ? { publicId:item.publicId, version:item.version, status:item.status, provider:item.provider, providerName:item.providerName, baseUrl:item.baseUrl, secretRef:'', purpose:item.purpose, modelName:item.modelName, parametersText, timeoutSeconds:item.timeoutSeconds, dailyLimit:item.dailyLimit }
    : { publicId:'', version:null, status:'DISABLED', provider:'OPENAI_COMPATIBLE', providerName:'DeepSeek', baseUrl:'https://api.deepseek.com', secretRef:'', purpose:'GENERAL', modelName:'', parametersText, timeoutSeconds:60, dailyLimit:1000 })
  modelDialog.value = true
}
async function saveModel() {
  try {
    const parameters = parseJson(modelForm.parametersText, {})
    const editing = Boolean(modelForm.publicId)
    await api({
      method:editing ? 'PUT' : 'POST',
      url:editing ? `/admin/model-configs/${modelForm.publicId}` : '/admin/model-configs',
      data:{ ...modelForm, parameters },
    })
    modelDialog.value = false
    ElMessage.success(editing
      ? (modelForm.status === 'ACTIVE' ? '当前运行模型已测试并热更新' : '模型配置已更新，请测试后启用')
      : '模型配置已保存，请测试后启用')
    await loadAi()
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function testModel(item:any) {
  modelActionId.value = item.publicId
  try {
    const result = await api<any>({
      method:'POST',
      url:`/admin/model-configs/${item.publicId}/test`,
      timeout:330_000
    })
    ElMessage.success(`模型连接正常：${result.model || item.modelName} · ${result.latencyMs || 0} ms`)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    modelActionId.value = ''
  }
}
async function activateModel(item:any) {
  const confirmed = await ElMessageBox.confirm(
    `启用“${item.modelName}”后，新的 AI 请求将立即切换到该模型。确认继续吗？`,
    '切换运行模型',
    { type:'warning', confirmButtonText:'测试并启用' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  modelActionId.value = item.publicId
  try {
    await api({ method:'PATCH', url:`/admin/model-configs/${item.publicId}/status`, data:{ status:'ACTIVE', version:item.version } })
    ElMessage.success(`运行模型已切换为 ${item.modelName}`)
    await loadAi()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    modelActionId.value = ''
  }
}
async function deleteModel(item:any) {
  if (item.status === 'ACTIVE') {
    ElMessage.warning('当前运行模型不能删除，请先启用另一个模型')
    return
  }
  const confirmed = await ElMessageBox.confirm(
    `删除“${item.modelName}”后将无法恢复，确认继续吗？`,
    '删除模型配置',
    { type:'warning', confirmButtonText:'删除', confirmButtonClass:'el-button--danger' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  modelActionId.value = item.publicId
  try {
    await api({ method:'DELETE', url:`/admin/model-configs/${item.publicId}`, params:{ version:item.version } })
    ElMessage.success('模型配置已删除')
    await loadAi()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    modelActionId.value = ''
  }
}
function openPrompt(item?:any) {
  Object.assign(promptForm, item
    ? { code:item.code, content:item.content, schemaText:item.schemaJson || '' }
    : { code:'', content:'', schemaText:'' })
  promptDialog.value = true
}
async function savePrompt() {
  try {
    const schema = parseJson(promptForm.schemaText, null)
    await api({ method:'POST', url:'/admin/prompt-templates', data:{ code:promptForm.code.trim().toUpperCase(), content:promptForm.content, schema } })
    promptDialog.value = false
    ElMessage.success('提示词新版本已创建')
    await loadAi()
  } catch (error) { ElMessage.error(errorMessage(error)) }
}
async function promptStatus(item:any, status:string) {
  await api({ method:'PATCH', url:`/admin/prompt-templates/${item.publicId}/status`, data:{ status } })
  ElMessage.success(`提示词已设为 ${status}`)
  await loadAi()
}
</script>

<template>
  <div class="admin-page" v-loading="loading">
    <header class="admin-hero">
      <div>
        <span class="eyebrow">CONTROL CENTER · GOVERNANCE</span>
        <h2>系统治理中心</h2>
        <p>管理用户、知识目录、公共题库与 AI 配置，并通过运行记录和审计链追踪系统行为。</p>
      </div>
      <div class="hero-actions">
        <span class="live-state"><i/>服务在线</span>
        <el-button @click="loadTab()">刷新当前数据</el-button>
      </div>
    </header>

    <section class="admin-shell panel">
      <el-tabs v-model="tab" class="admin-tabs">
        <el-tab-pane label="总览" name="overview">
          <div class="metric-grid">
            <button type="button" class="metric-card metric-card-link" aria-label="查看用户与权限" title="打开用户与权限" data-testid="admin-metric-users" @click="openSection('users')">
              <span>用户</span><strong>{{ metrics.userCount ?? 0 }}</strong><small>{{ metrics.activeUsers ?? 0 }} 位处于活动状态</small><i aria-hidden="true">→</i>
            </button>
            <button type="button" class="metric-card metric-card-link" aria-label="展示全部活动目标" title="在总览中展示全部活动目标" data-testid="admin-metric-active-goals" :aria-expanded="activeGoalsVisible" @click="toggleActiveGoals">
              <span>活动目标</span><strong>{{ metrics.activeGoals ?? 0 }}</strong><small>所有用户正在执行的目标</small><i :class="{ expanded:activeGoalsVisible }" aria-hidden="true">↓</i>
            </button>
            <button type="button" class="metric-card metric-card-link" aria-label="查看文档运行记录" title="打开文档运行记录" @click="openSection('jobs','documents')">
              <span>已索引资料</span><strong>{{ metrics.indexedDocuments ?? 0 }}</strong><small>可参与知识检索</small><i aria-hidden="true">→</i>
            </button>
            <button type="button" class="metric-card metric-card-link" aria-label="查看公共题库" title="打开公共题库" @click="openSection('questions')">
              <span>公共题目</span><strong>{{ metrics.publishedQuestions ?? 0 }}</strong><small>已发布到诊断题库</small><i aria-hidden="true">→</i>
            </button>
            <button type="button" class="metric-card metric-card-link" aria-label="查看模型调用记录" title="打开模型调用记录" @click="openSection('jobs','models')">
              <span>24h 模型调用</span><strong>{{ metrics.modelCalls24h ?? 0 }}</strong><small>{{ metrics.modelFailures24h ?? 0 }} 次失败</small><i aria-hidden="true">→</i>
            </button>
            <button type="button" class="metric-card metric-card-link" aria-label="按用户查看学习记录" title="进入用户学习档案" @click="openSection('users')">
              <span>近 7 日学习</span><strong>{{ studyHours(metrics.studySeconds7d) }}</strong><small>自动与手工时长合计</small><i aria-hidden="true">→</i>
            </button>
          </div>
          <section class="charts-panel">
            <div class="section-title"><div><span class="eyebrow">PLATFORM ANALYTICS</span><h3>平台数据可视化</h3><p>学习投入、任务完成与 AI 调用趋势，覆盖全部学习者。</p></div></div>
            <div class="charts-grid">
              <article class="chart-card chart-wide">
                <div class="chart-head"><h4>近 14 日学习时长</h4><small>自动计时与手工补录 · 全部用户</small></div>
                <v-chart class="chart" :option="studyChart" autoresize/>
              </article>
              <article class="chart-card">
                <div class="chart-head"><h4>目标状态分布</h4><small>全部学习目标</small></div>
                <v-chart class="chart" :option="goalChart" autoresize/>
              </article>
              <article class="chart-card">
                <div class="chart-head"><h4>近 7 日模型调用</h4><small>成功与失败次数</small></div>
                <v-chart class="chart" :option="modelChart" autoresize/>
              </article>
              <article class="chart-card">
                <div class="chart-head"><h4>近 14 日任务完成</h4><small>计划 / 完成 / 逾期</small></div>
                <v-chart class="chart" :option="taskTrendChart" autoresize/>
              </article>
              <article class="chart-card">
                <div class="chart-head"><h4>学习时长 Top 5</h4><small>近 7 日 · 自动 + 手工</small></div>
                <v-chart class="chart" :option="topLearnersChart" autoresize/>
              </article>
            </div>
          </section>
          <section v-if="activeGoalsVisible" class="active-goals-panel" data-testid="admin-active-goals-panel">
            <div class="active-goals-head">
              <div><span class="eyebrow">ACTIVE GOALS · ALL LEARNERS</span><h3>全部活动目标</h3><p>按优先级和截止日期排列，可直接查看目标所属用户的完整学习档案。</p></div>
              <div><strong>{{activeGoals.length}}</strong><span>个进行中</span><el-button :loading="activeGoalsLoading" @click="loadActiveGoals">刷新</el-button><el-button text @click="activeGoalsVisible=false">收起</el-button></div>
            </div>
            <el-table v-loading="activeGoalsLoading" :data="activeGoals" max-height="460" empty-text="当前没有活动目标">
              <el-table-column label="用户" min-width="180" fixed>
                <template #default="{row}">
                  <button class="goal-owner" type="button" @click="openGoalOwner(row)"><b>{{row.username}}</b><small>{{row.email}}</small></button>
                </template>
              </el-table-column>
              <el-table-column label="活动目标" min-width="250">
                <template #default="{row}"><div class="goal-summary"><b>{{row.name}}</b><small>{{row.directionName || '自定义方向'}} · {{row.type}}</small></div></template>
              </el-table-column>
              <el-table-column label="优先级" width="100"><template #default="{row}"><el-tag effect="plain" :title="row.priority">{{statusLabel(row.priority)}}</el-tag></template></el-table-column>
              <el-table-column label="周期" min-width="190"><template #default="{row}">{{row.startDate}} → {{row.dueDate}}</template></el-table-column>
              <el-table-column label="每周投入" width="105"><template #default="{row}">{{row.weeklyBudgetMinutes}} 分钟</template></el-table-column>
              <el-table-column label="任务进度" width="150">
                <template #default="{row}">
                  <div class="goal-progress"><el-progress :percentage="row.taskCount ? Math.round(Number(row.completedTaskCount||0)*100/Number(row.taskCount)) : 0" :stroke-width="7"/><small>{{row.completedTaskCount || 0}} / {{row.taskCount || 0}}</small></div>
                </template>
              </el-table-column>
              <el-table-column label="计划" width="105"><template #default="{row}"><el-tag :type="statusType(row.planStatus)" effect="plain" :title="row.planStatus">{{row.planStatus ? statusLabel(row.planStatus) : '未规划'}}</el-tag></template></el-table-column>
              <el-table-column label="更新" min-width="165"><template #default="{row}">{{fmt(row.updatedAt)}}</template></el-table-column>
            </el-table>
          </section>
          <div class="overview-grid">
            <article class="govern-card">
              <div class="section-title"><div><span class="eyebrow">OPERATIONS</span><h3>运行脉搏</h3></div><el-button link @click="tab='jobs'">查看全部</el-button></div>
              <div class="pulse-list">
                <div><span class="pulse-icon healthy">01</span><p><b>运行中作业</b><small>规划、文档解析与索引</small></p><strong>{{ jobs.running ?? 0 }}</strong></div>
                <div><span class="pulse-icon" :class="{ alert:jobs.failed }">02</span><p><b>近期失败</b><small>规划、文档与模型调用</small></p><strong>{{ jobs.failed ?? 0 }}</strong></div>
                <div><span class="pulse-icon">03</span><p><b>待投递事件</b><small>事务 Outbox 队列</small></p><strong>{{ jobs.outboxPending ?? 0 }}</strong></div>
              </div>
            </article>
            <article class="govern-card">
              <div class="section-title"><div><span class="eyebrow">AI OBSERVABILITY</span><h3>最近模型调用</h3></div><el-button link @click="tab='ai'">配置治理</el-button></div>
              <el-table :data="jobs.modelRuns?.slice(0,5)" size="small" empty-text="暂无模型调用">
                <el-table-column prop="purpose" label="用途" min-width="120"/>
                <el-table-column prop="username" label="用户" min-width="100"/>
                <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                <el-table-column label="延迟" width="90"><template #default="{row}">{{duration(row.latencyMs)}}</template></el-table-column>
              </el-table>
            </article>
          </div>
        </el-tab-pane>

        <el-tab-pane label="用户与权限" name="users">
          <div class="section-title">
            <div><span class="eyebrow">IDENTITY & ACCESS</span><h3>用户治理</h3><p>状态与角色变化立即生效，并写入审计日志。</p></div>
            <div class="filters">
              <el-input v-model="userQuery.keyword" clearable placeholder="搜索用户名或邮箱" @keyup.enter="userQuery.page=1;loadUsers()"/>
              <el-select v-model="userQuery.status" clearable placeholder="全部状态"><el-option v-for="item in ['ACTIVE','DISABLED','LOCKED']" :key="item" :label="item" :value="item"/></el-select>
              <el-button @click="userQuery.page=1;loadUsers()">查询</el-button>
            </div>
          </div>
          <el-table :data="users" empty-text="暂无用户">
            <el-table-column label="用户" min-width="210"><template #default="{row}"><div class="identity-cell"><b>{{row.username}}</b><small>{{row.email}}</small></div></template></el-table-column>
            <el-table-column prop="timezone" label="时区" width="140"/>
            <el-table-column label="角色" min-width="150"><template #default="{row}"><el-tag v-for="role in row.roles" :key="role" effect="plain" class="role-tag">{{role}}</el-tag></template></el-table-column>
            <el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
            <el-table-column label="最后登录" min-width="165"><template #default="{row}">{{fmt(row.lastLoginAt)}}</template></el-table-column>
            <el-table-column label="注册时间" min-width="165"><template #default="{row}">{{fmt(row.createdAt)}}</template></el-table-column>
            <el-table-column label="操作" width="250" fixed="right"><template #default="{row}">
              <el-button link type="primary" @click="openLearningFile(row)">学习档案</el-button>
              <el-button link @click="openRoles(row)">角色</el-button>
              <el-button v-if="row.status==='LOCKED'" link type="success" :title="row.lockedUntil?('锁定至 '+fmt(row.lockedUntil)+'，失败 '+row.loginFailedCount+' 次'):''" @click="changeUserStatus(row)">解锁</el-button>
              <el-button v-else link :type="row.status==='ACTIVE'?'danger':'success'" @click="changeUserStatus(row)">{{row.status==='ACTIVE'?'禁用':'启用'}}</el-button>
            </template></el-table-column>
          </el-table>
          <div class="pager"><el-pagination v-model:current-page="userQuery.page" v-model:page-size="userQuery.pageSize" :total="userQuery.total" layout="total, prev, pager, next" @current-change="loadUsers"/></div>
        </el-tab-pane>

        <el-tab-pane label="知识库" name="knowledge">
          <div class="section-title">
            <div><span class="eyebrow">LEARNER KNOWLEDGE</span><h3>全部用户的个人知识库</h3><p>跨用户查看知识空间与文档，只读治理视图；展开空间可查看文档明细。</p></div>
            <div class="filters">
              <el-input v-model="knowledgeQuery.keyword" clearable placeholder="搜索用户名 / 邮箱 / 空间名" @keyup.enter="loadKnowledge()"/>
              <el-button @click="loadKnowledge()">查询</el-button>
            </div>
          </div>
          <el-table :data="allSpaces" row-key="publicId" empty-text="暂无知识空间" v-loading="spaceDocsLoading" @expand-change="(row: any, expanded: any[]) => toggleSpaceDocs(row, expanded)">
            <el-table-column type="expand">
              <template #default="{row}">
                <div class="space-doc-panel">
                  <div class="space-doc-head"><div><span class="eyebrow">DOCUMENTS · {{row.name}}</span><h5>{{row.username}} 的空间文档（{{spaceDocuments[row.publicId]?.length || 0}} 份）</h5></div></div>
                  <el-table v-if="spaceDocuments[row.publicId]" :data="spaceDocuments[row.publicId]" size="small">
                    <el-table-column label="文档" min-width="240"><template #default="{row:d}"><b>{{d.displayName}}</b></template></el-table-column>
                    <el-table-column label="资料分类" min-width="150"><template #default="{row:d}">{{d.categoryPath ? displayPath(d.categoryPath) : '未分类'}}</template></el-table-column>
                    <el-table-column label="状态" width="120"><template #default="{row:d}"><el-tag :type="statusType(d.status)" effect="plain" :title="d.status">{{statusLabel(d.status)}}</el-tag></template></el-table-column>
                    <el-table-column label="版本" width="70"><template #default="{row:d}">V{{d.activeVersionNo}}</template></el-table-column>
                    <el-table-column label="解析方式" min-width="130"><template #default="{row:d}">{{d.parserVersion || '—'}}</template></el-table-column>
                    <el-table-column label="大小" width="90"><template #default="{row:d}">{{formatSize(d.fileSize)}}</template></el-table-column>
                    <el-table-column label="更新时间" min-width="165"><template #default="{row:d}">{{fmt(d.updatedAt)}}</template></el-table-column>
                    <el-table-column label="操作" width="90"><template #default="{row:d}"><el-button link type="primary" @click="openDocumentContent(d, row)">查看内容</el-button></template></el-table-column>
                  </el-table>
                  <el-empty v-else-if="spaceDocsLoading!==row.publicId" description="该空间暂无文档" :image-size="60"/>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="所有者" min-width="180"><template #default="{row}"><div class="identity-cell"><b>{{row.username}}</b><small>{{row.email}}</small></div></template></el-table-column>
            <el-table-column prop="name" label="空间名" min-width="160"/>
            <el-table-column label="文档" width="130"><template #default="{row}"><span class="space-stat"><b>{{row.documentCount}}</b><small>已索引 {{row.indexedCount}}</small></span></template></el-table-column>
            <el-table-column prop="categoryCount" label="分类数" width="80"/>
            <el-table-column label="占用空间" width="100"><template #default="{row}">{{formatSize(row.totalSize)}}</template></el-table-column>
            <el-table-column label="可见性" width="90"><template #default="{row}"><el-tag effect="plain" :title="row.visibility">{{row.visibility==='PRIVATE'?'私有':'共享'}}</el-tag></template></el-table-column>
            <el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
            <el-table-column label="创建时间" min-width="165"><template #default="{row}">{{fmt(row.createdAt)}}</template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="知识目录" name="catalog">
          <div class="section-title">
            <div><span class="eyebrow">CURRICULUM GRAPH</span><h3>方向、知识点与前置关系</h3><p>内部 ID 全程按字符串传输，避免长整型精度丢失。</p></div>
            <div class="toolbar"><el-button @click="openDirection()">新增方向</el-button><el-button type="primary" @click="openKnowledge()">新增知识点</el-button></div>
          </div>
          <div class="catalog-grid">
            <article class="sub-panel">
              <div class="sub-head"><h4>学习方向</h4><span>{{directions.length}}</span></div>
              <div class="direction-list">
                <button :class="{active:!catalogQuery.directionId}" @click="catalogQuery.directionId='';loadCatalog()"><span><b>全部方向</b><small>查看完整知识图谱</small></span><em>{{knowledge.length}}</em></button>
                <button v-for="node in directionTree" :key="'d'+node.item.id" :class="{active:String(catalogQuery.directionId)===String(node.item.id),child:node.depth>0}" :style="node.depth>0?{marginLeft:node.depth*18+'px'}:undefined" @click="catalogQuery.directionId=node.item.id;loadCatalog()">
                  <span><b>{{node.item.name}}</b><small>{{node.item.code}} · {{statusLabel(node.item.status)}}<template v-if="node.depth===0&&node.childCount"> · {{node.childCount}} 个子方向</template></small></span><em>{{node.agg}}</em>
                  <i @click.stop="openDirection(node.item)">编辑</i>
                </button>
              </div>
            </article>
            <article class="sub-panel wide">
              <div class="sub-head">
                <div><h4>{{activeDirectionName}} · 知识点</h4><small>支持父子层级、权重和状态治理</small></div>
                <el-input v-model="catalogQuery.keyword" clearable placeholder="筛选知识点" @keyup.enter="loadCatalog()"/>
              </div>
              <el-table :data="selectedDirectionPoints" max-height="430" empty-text="该方向暂无知识点">
                <el-table-column prop="code" label="编码" min-width="130"/>
                <el-table-column label="名称" min-width="180"><template #default="{row}"><div class="identity-cell"><b>{{row.name}}</b><small>{{row.parentName || row.directionName}}</small></div></template></el-table-column>
                <el-table-column prop="level" label="层级" width="70"/>
                <el-table-column prop="defaultWeight" label="权重" width="80"/>
                <el-table-column label="状态" width="95"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="80"><template #default="{row}"><el-button link @click="openKnowledge(row)">编辑</el-button></template></el-table-column>
              </el-table>
            </article>
          </div>
          <article class="sub-panel dependency-panel">
            <div class="sub-head"><div><h4>知识前置关系</h4><small>系统会拒绝自环、跨方向依赖和有向环</small></div><el-button @click="openDependency()">新增关系</el-button></div>
            <div class="dependency-list" v-if="dependencies.length">
              <div v-for="item in dependencies" :key="`${item.predecessorId}-${item.successorId}`">
                <span>{{item.predecessorName}}</span><i>→</i><span>{{item.successorName}}</span><small>{{item.directionName}}</small><el-button link type="danger" @click="deleteDependency(item)">删除</el-button>
              </div>
            </div>
            <div v-else class="empty">当前范围暂无前置关系</div>
          </article>
        </el-tab-pane>

        <el-tab-pane label="公共题库" name="questions">
          <div class="section-title">
            <div><span class="eyebrow">ASSESSMENT BANK</span><h3>公共诊断题库</h3><p>新建题目会固化不可变版本，并立即进入公共诊断抽题范围。</p></div>
            <div class="toolbar"><el-input v-model="questionQuery.keyword" clearable placeholder="搜索题干" @keyup.enter="questionQuery.page=1;loadQuestions()"/><el-select v-model="questionQuery.type" clearable placeholder="全部题型"><el-option v-for="item in ['SINGLE_CHOICE','MULTIPLE_CHOICE','TRUE_FALSE','FILL_BLANK']" :key="item" :label="item" :value="item"/></el-select><el-select v-model="questionQuery.status" clearable placeholder="全部状态"><el-option v-for="item in ['PUBLISHED','DRAFT','ARCHIVED']" :key="item" :label="statusLabel(item)" :value="item"/></el-select><el-button @click="loadQuestions()">查询</el-button><el-button type="primary" @click="openQuestion()">新增题目</el-button></div>
          </div>
          <el-table :data="questions" empty-text="暂无题目">
            <el-table-column prop="type" label="题型" width="145"/>
            <el-table-column label="题干" min-width="330"><template #default="{row}"><div class="question-stem">{{row.stem}}</div><small class="muted">{{row.knowledgePointNames || '未关联知识点'}}</small></template></el-table-column>
            <el-table-column label="难度" width="110"><template #default="{row}"><el-rate :model-value="row.difficulty" disabled/></template></el-table-column>
            <el-table-column label="版本" width="70"><template #default="{row}">V{{row.currentVersionNo}}</template></el-table-column>
            <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
            <el-table-column label="更新时间" min-width="165"><template #default="{row}">{{fmt(row.updatedAt)}}</template></el-table-column>
            <el-table-column label="操作" width="70"><template #default="{row}"><el-button link type="primary" @click="openQuestionDetail(row)">查看</el-button></template></el-table-column>
          </el-table>
          <div class="pager"><el-pagination v-model:current-page="questionQuery.page" :page-size="questionQuery.pageSize" :total="questionQuery.total" layout="total, prev, pager, next" @current-change="loadQuestions"/></div>
        </el-tab-pane>

        <el-tab-pane label="评分申诉" name="appeals">
          <div class="table-toolbar"><div><span class="eyebrow">HUMAN REVIEW</span><h3>评分申诉复核</h3></div><el-select v-model="appealStatus" clearable placeholder="全部状态" style="width:150px" @change="loadAppeals"><el-option value="PENDING" label="待处理"/><el-option value="ACCEPTED" label="已接受"/><el-option value="REJECTED" label="已驳回"/><el-option value="WITHDRAWN" label="已撤回"/></el-select></div>
          <el-table :data="appeals" empty-text="暂无评分申诉">
            <el-table-column label="用户" prop="username" min-width="120"/>
            <el-table-column label="答题记录" prop="answerId" min-width="210" show-overflow-tooltip/>
            <el-table-column label="原分数" prop="score" width="90"/>
            <el-table-column label="申诉原因" prop="reason" min-width="260"/>
            <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
            <el-table-column label="提交时间" min-width="165"><template #default="{row}">{{fmt(row.createdAt)}}</template></el-table-column>
            <el-table-column label="处理" width="150" fixed="right"><template #default="{row}"><template v-if="row.status==='PENDING'"><el-button link type="success" @click="resolveAppeal(row,true)">接受</el-button><el-button link type="danger" @click="resolveAppeal(row,false)">驳回</el-button></template><span v-else>{{row.resolution || '—'}}</span></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="AI 治理" name="ai">
          <div class="section-title">
            <div><span class="eyebrow">MODEL & PROMPT GOVERNANCE</span><h3>运行模型与提示词</h3><p>模型配置保存在业务库，测试通过后可立即切换 Python AI 服务的运行模型。</p></div>
            <div class="toolbar"><el-button @click="openPrompt()">新建提示词版本</el-button><el-button type="primary" @click="openModel()">新增模型</el-button></div>
          </div>
          <article class="sub-panel">
            <div class="sub-head"><h4>模型配置</h4><span>{{models.length}}</span></div>
            <el-table :data="models" empty-text="暂无模型治理配置">
              <el-table-column label="服务商" min-width="170"><template #default="{row}"><div class="identity-cell"><b>{{row.providerName}}</b><small>{{row.provider}}</small></div></template></el-table-column>
              <el-table-column prop="purpose" label="用途" min-width="140"/>
              <el-table-column prop="modelName" label="模型" min-width="170"/>
              <el-table-column label="超时 / 日限额" width="150"><template #default="{row}">{{row.timeoutSeconds}}s · {{row.dailyLimit}}</template></el-table-column>
              <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="285"><template #default="{row}">
                <el-button link :loading="modelActionId===row.publicId" @click="testModel(row)">测试</el-button>
                <el-button link :disabled="modelActionId===row.publicId" @click="openModel(row)">编辑</el-button>
                <el-button v-if="row.status!=='ACTIVE'" link type="success" :disabled="modelActionId===row.publicId" @click="activateModel(row)">启用</el-button>
                <el-tag v-else type="success" effect="dark" size="small">当前使用</el-tag>
                <el-button link type="danger" :disabled="modelActionId===row.publicId" @click="deleteModel(row)">删除</el-button>
              </template></el-table-column>
            </el-table>
          </article>
          <article class="sub-panel prompt-panel">
            <div class="sub-head"><h4>提示词版本</h4><span>{{prompts.length}}</span></div>
            <p class="hint">此处即 AI 服务实际使用的系统提示词（状态为「启用」的版本生效）。修改后新建版本并「启用」，约 1 分钟内自动生效，无需重启服务；同一编码只会保留一个启用版本。</p>
            <el-table :data="prompts" empty-text="暂无提示词模板">
              <el-table-column prop="code" label="编码" min-width="180"/>
              <el-table-column label="版本" width="80"><template #default="{row}">V{{row.versionNo}}</template></el-table-column>
              <el-table-column label="内容摘要" min-width="300"><template #default="{row}"><span class="line-clamp">{{row.content}}</span></template></el-table-column>
              <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="210"><template #default="{row}">
                <el-button link @click="promptDetail=row">查看</el-button><el-button link @click="openPrompt(row)">新版本</el-button><el-button v-if="row.status!=='ACTIVE'" link type="success" @click="promptStatus(row,'ACTIVE')">启用</el-button><el-button v-else link type="warning" @click="promptStatus(row,'ARCHIVED')">归档</el-button>
              </template></el-table-column>
            </el-table>
          </article>
        </el-tab-pane>

        <el-tab-pane label="运行记录" name="jobs">
          <div class="section-title"><div><span class="eyebrow">RUNTIME LEDGER</span><h3>作业与模型运行记录</h3><p>展示最近 100 条记录，支持按状态与关键字筛选；不提供绕过业务规则的强制重试。</p></div><div class="job-toolbar"><div class="filters"><el-select v-model="jobQuery.status" clearable placeholder="全部状态"><el-option label="运行中" value="RUNNING"/><el-option label="成功" value="SUCCESS"/><el-option label="失败" value="FAILED"/></el-select><el-input v-model="jobQuery.keyword" clearable :placeholder="jobPlaceholder" @keyup.enter="loadJobs()"/><el-button type="primary" @click="loadJobs()">查询</el-button><el-button v-if="jobQuery.status||jobQuery.keyword" @click="resetJobs()">重置</el-button></div><div class="job-summary"><span>运行中 <b>{{jobs.running}}</b></span><span>失败 <b class="danger">{{jobs.failed}}</b></span><span>待投递 <b>{{jobs.outboxPending}}</b></span></div></div></div>
          <el-tabs v-model="jobTab" type="card">
            <el-tab-pane label="规划作业" name="planning"><el-table :data="jobRecords.planning"><el-table-column prop="username" label="用户"/><el-table-column prop="goalName" label="目标" min-width="180"/><el-table-column prop="jobType" label="类型"/><el-table-column label="状态"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column><el-table-column label="开始时间" min-width="165"><template #default="{row}">{{fmt(row.startedAt)}}</template></el-table-column><el-table-column prop="errorMessage" label="错误" min-width="180"/></el-table></el-tab-pane>
            <el-tab-pane label="文档作业" name="documents"><el-table :data="jobRecords.documents"><el-table-column prop="username" label="用户"/><el-table-column prop="documentName" label="文档" min-width="210"/><el-table-column prop="jobType" label="类型"/><el-table-column prop="attempts" label="次数" width="70"/><el-table-column label="状态"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column><el-table-column prop="errorMessage" label="错误" min-width="190"/></el-table></el-tab-pane>
            <el-tab-pane label="模型调用" name="models"><el-table :data="jobRecords.modelRuns"><el-table-column prop="username" label="用户"/><el-table-column prop="purpose" label="用途"/><el-table-column label="状态"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column><el-table-column label="Token" width="120"><template #default="{row}">{{row.tokenIn || 0}} / {{row.tokenOut || 0}}</template></el-table-column><el-table-column label="延迟" width="100"><template #default="{row}">{{duration(row.latencyMs)}}</template></el-table-column><el-table-column prop="errorCode" label="错误码"/><el-table-column label="时间" min-width="165"><template #default="{row}">{{fmt(row.createdAt)}}</template></el-table-column></el-table></el-tab-pane>
          </el-tabs>
        </el-tab-pane>

        <el-tab-pane label="审计日志" name="audit">
          <div class="section-title">
            <div><span class="eyebrow">AUDIT TRAIL</span><h3>不可变操作记录</h3><p>按操作人、资源和请求链路解释治理动作。</p></div>
            <div class="filters"><el-input v-model="auditQuery.keyword" clearable placeholder="资源类型或标识" @keyup.enter="auditQuery.page=1;loadAudits()"/><el-input v-model="auditQuery.action" clearable placeholder="操作类型" @keyup.enter="auditQuery.page=1;loadAudits()"/><el-select v-model="auditQuery.result" clearable placeholder="全部结果"><el-option label="成功" value="SUCCESS"/><el-option label="失败" value="FAILED"/></el-select><el-button @click="auditQuery.page=1;loadAudits()">查询</el-button></div>
          </div>
          <el-table :data="audits" empty-text="暂无审计记录" @row-click="showAudit">
            <el-table-column label="时间" min-width="170"><template #default="{row}">{{fmt(row.createdAt)}}</template></el-table-column>
            <el-table-column prop="operatorName" label="操作人" min-width="110"/>
            <el-table-column prop="action" label="操作" min-width="180"/>
            <el-table-column prop="resourceType" label="资源" min-width="150"/>
            <el-table-column prop="resourceId" label="公开标识" min-width="210" show-overflow-tooltip/>
            <el-table-column label="结果" width="100"><template #default="{row}"><el-tag :type="statusType(row.result)" effect="plain" :title="row.result">{{statusLabel(row.result)}}</el-tag></template></el-table-column>
          </el-table>
          <div class="pager"><el-pagination v-model:current-page="auditQuery.page" :page-size="auditQuery.pageSize" :total="auditQuery.total" layout="total, prev, pager, next" @current-change="loadAudits"/></div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="roleDialog" title="调整用户角色" width="500px">
      <p class="dialog-lead">{{selectedUser?.username}} · {{selectedUser?.email}}</p>
      <el-form label-position="top">
        <el-form-item label="角色"><el-checkbox-group v-model="roleForm.roles"><el-checkbox v-for="role in roles" :key="role.code" :value="role.code">{{role.name}}（{{role.code}}）</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="变更原因"><el-input v-model="roleForm.reason" type="textarea" :rows="3" maxlength="1000" show-word-limit/></el-form-item>
      </el-form>
      <template #footer><el-button @click="roleDialog=false">取消</el-button><el-button type="primary" @click="saveRoles">保存角色</el-button></template>
    </el-dialog>

    <el-drawer v-model="learningFileDrawer" size="88%" class="learning-file-drawer">
      <template #header>
        <div class="file-drawer-head">
          <div><span class="eyebrow">LEARNER RECORD</span><h3>{{learningFile?.user?.username || selectedUser?.username}} 的学习档案</h3><p>{{learningFile?.user?.email || selectedUser?.email}} · 数据按画像、推荐、规划和学习证据版本追溯</p></div>
          <el-tag v-if="learningFile?.user" :type="statusType(learningFile.user.status)" effect="plain">{{learningFile.user.status}}</el-tag>
        </div>
      </template>
      <div v-loading="learningFileLoading" class="learning-file">
        <template v-if="learningFile">
          <div class="file-metrics">
            <article><span>画像版本</span><strong>{{learningFile.summary?.profileVersionCount || 0}}</strong></article>
            <article><span>推荐批次</span><strong>{{learningFile.summary?.recommendationBatchCount || 0}}</strong></article>
            <article><span>目标 / 活动</span><strong>{{learningFile.summary?.goalCount || 0}} / {{learningFile.summary?.activeGoalCount || 0}}</strong></article>
            <article><span>任务完成</span><strong>{{learningFile.summary?.completedTaskCount || 0}} / {{learningFile.summary?.taskCount || 0}}</strong></article>
            <article><span>有效学习</span><strong>{{studyHours(learningFile.summary?.totalStudySeconds)}}</strong></article>
            <article><span>掌握记录</span><strong>{{learningFile.summary?.masteryCount || 0}}</strong></article>
          </div>

          <el-tabs v-model="learningFileTab" class="file-tabs">
            <el-tab-pane label="画像档案" name="profile">
              <div class="file-grid">
                <article class="file-panel">
                  <div class="file-panel-title"><h4>当前画像</h4><el-tag :type="statusType(learningFile.profile?.current?.profileStatus)" effect="plain">{{learningFile.profile?.current?.profileStatus || '未创建'}}</el-tag></div>
                  <el-descriptions v-if="learningFile.profile?.current" :column="2" border>
                    <el-descriptions-item label="画像版本">V{{learningFile.profile.current.currentVersionNo}}</el-descriptions-item>
                    <el-descriptions-item label="时区">{{learningFile.profile.current.timezone}}</el-descriptions-item>
                    <el-descriptions-item label="学习周期">{{learningFile.profile.current.planStartDate}} 至 {{learningFile.profile.current.planEndDate}}</el-descriptions-item>
                    <el-descriptions-item label="最后更新">{{fmt(learningFile.profile.current.updatedAt)}}</el-descriptions-item>
                    <el-descriptions-item label="学习背景" :span="2">{{learningFile.profile.current.backgroundText || '未填写'}}</el-descriptions-item>
                  </el-descriptions>
                  <el-empty v-else description="该用户尚未创建学习画像" :image-size="70"/>
                  <div v-if="learningFile.profile?.directions?.length" class="record-list">
                    <div v-for="item in learningFile.profile.directions" :key="`${item.directionName}-${item.currentStage}`">
                      <span><b>{{item.directionName}}</b><small>{{item.directionCode || item.sourceType}} · {{item.currentStage}} · {{item.knowledgeBaseDirection ? '系统知识库方向' : '自定义探索方向'}}</small></span>
                      <span class="record-tags">
                        <el-tag :type="item.knowledgeBaseDirection ? 'success' : 'warning'" effect="plain">{{item.knowledgeBaseDirection ? '知识库方向' : '自定义方向'}}</el-tag>
                        <el-tag v-if="item.primaryDirection" type="success" effect="plain">主方向</el-tag>
                      </span>
                    </div>
                  </div>
                </article>
                <article class="file-panel">
                  <div class="file-panel-title"><h4>偏好与每周容量</h4><small>{{learningFile.profile?.availability?.reduce((sum:number,item:any)=>sum+Number(item.availableMinutes||0),0) || 0}} 分钟 / 周</small></div>
                  <el-descriptions v-if="learningFile.profile?.preference" :column="2" border>
                    <el-descriptions-item label="指导方式">{{learningFile.profile.preference.guidanceStyle}}</el-descriptions-item>
                    <el-descriptions-item label="任务粒度">{{learningFile.profile.preference.taskGranularity}}</el-descriptions-item>
                    <el-descriptions-item label="专注时长">{{learningFile.profile.preference.focusMinutes}} 分钟</el-descriptions-item>
                    <el-descriptions-item label="容量系数">{{scorePercent(learningFile.profile.preference.capacityRatio)}}</el-descriptions-item>
                    <el-descriptions-item label="难度范围">{{learningFile.profile.preference.difficultyMin}} ～ {{learningFile.profile.preference.difficultyMax}}</el-descriptions-item>
                    <el-descriptions-item label="内容形式">{{learningFile.profile.preference.contentModes?.join('、') || '—'}}</el-descriptions-item>
                  </el-descriptions>
                  <div class="availability-list" v-if="learningFile.profile?.availability?.length">
                    <span v-for="slot in learningFile.profile.availability" :key="`${slot.weekday}-${slot.startTime}`">{{weekday(slot.weekday)}} {{slot.startTime}}–{{slot.endTime}} · {{slot.energyLevel}}</span>
                  </div>
                  <el-empty v-else description="尚未配置学习偏好或时间" :image-size="70"/>
                  <div v-if="learningFile.profile?.exceptions?.length" class="exceptions-block">
                    <div class="file-section-head"><h4>特殊日期容量</h4><small>例外覆盖每周规则；0 分钟 = 当天不安排任务</small></div>
                    <div class="availability-list">
                      <span v-for="exc in learningFile.profile.exceptions" :key="exc.localDate" class="exception-chip">
                        <b>{{ exc.localDate }}</b>{{ exc.availableMinutes === 0 ? '不安排任务' : exc.availableMinutes + ' 分钟' }}{{ exc.reason ? ` · ${exc.reason}` : '' }}
                      </span>
                    </div>
                  </div>
                </article>
              </div>
              <article class="file-panel file-panel-spaced">
                <div class="file-panel-title"><h4>画像版本记录</h4><small>这里只展示固化快照摘要，不展示完整访谈对话</small></div>
                <el-table :data="learningFile.profile?.versions" max-height="300" empty-text="暂无画像版本">
                  <el-table-column label="版本" width="80"><template #default="{row}">V{{row.versionNo}}</template></el-table-column>
                  <el-table-column label="置信度" width="100"><template #default="{row}">{{scorePercent(row.confidence)}}</template></el-table-column>
                  <el-table-column label="推荐难度" width="100"><template #default="{row}">{{row.snapshot?.recommendedDifficulty || '—'}}</template></el-table-column>
                  <el-table-column label="每日任务" width="100"><template #default="{row}">{{row.snapshot?.dailyRecommendedTasks || '—'}}</template></el-table-column>
                  <el-table-column prop="triggerType" label="生成方式" min-width="130"/>
                  <el-table-column label="生成时间" min-width="165"><template #default="{row}">{{fmt(row.createdAt)}}</template></el-table-column>
                </el-table>
              </article>
              <article class="file-panel file-panel-spaced">
                <div class="file-panel-title"><h4>AI 访谈记录</h4><small>仅显示状态和完整度，避免后台暴露完整私人对话</small></div>
                <el-table :data="learningFile.profile?.interviews" max-height="240" empty-text="暂无 AI 访谈">
                  <el-table-column prop="assistantMode" label="模式"/>
                  <el-table-column label="完整度"><template #default="{row}"><el-progress :percentage="Number(row.completenessPercent || 0)" :stroke-width="8"/></template></el-table-column>
                  <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                  <el-table-column label="确认时间" min-width="165"><template #default="{row}">{{fmt(row.confirmedAt)}}</template></el-table-column>
                  <el-table-column label="更新时间" min-width="165"><template #default="{row}">{{fmt(row.updatedAt)}}</template></el-table-column>
                </el-table>
              </article>
            </el-tab-pane>

            <el-tab-pane label="推荐与目标" name="goals">
              <div class="file-section-head"><div><h4>AI 推荐历史</h4><p>“已采用”由目标中固化的 recommendationId 与画像版本共同追溯。</p></div></div>
              <el-collapse v-if="learningFile.recommendations?.length" class="recommendation-batches">
                <el-collapse-item v-for="batch in learningFile.recommendations" :key="batch.batchId" :name="batch.batchId">
                  <template #title><span class="batch-title"><b>画像 V{{batch.profileVersionNo}} · {{batch.source}}</b><small>{{fmt(batch.generatedAt)}} · {{recommendationsOf(batch).length}} 个候选</small></span></template>
                  <div class="recommendation-grid">
                    <article v-for="item in recommendationsOf(batch)" :key="item.id">
                      <div><el-tag :type="adoptedRecommendationIds.has(item.id)?'success':'info'" effect="plain">{{adoptedRecommendationIds.has(item.id)?'已采用':'未采用'}}</el-tag><span>{{item.directionName}}</span></div>
                      <h5>{{item.name}}</h5><p>{{item.reason || item.description}}</p>
                      <small>{{item.startDate}} 至 {{item.dueDate}} · 每周 {{item.weeklyBudgetMinutes}} 分钟</small>
                    </article>
                  </div>
                </el-collapse-item>
              </el-collapse>
              <el-empty v-else description="尚未生成目标推荐" :image-size="80"/>
              <article class="file-panel file-panel-spaced">
                <div class="file-panel-title"><h4>用户目标</h4><small>自定义目标与推荐采纳结果统一展示</small></div>
                <el-table :data="learningFile.goals" max-height="390" empty-text="暂无目标">
                  <el-table-column label="目标" min-width="230"><template #default="{row}"><div class="identity-cell"><b>{{row.name}}</b><small>{{row.directionName}} · {{row.type}}</small></div></template></el-table-column>
                  <el-table-column label="来源" width="140"><template #default="{row}"><el-tag :type="row.sourceType==='CUSTOM'?'info':'success'" effect="plain">{{row.sourceType}}</el-tag></template></el-table-column>
                  <el-table-column label="依据版本" width="100"><template #default="{row}">{{row.profileVersionNo?'画像 V'+row.profileVersionNo:'—'}}</template></el-table-column>
                  <el-table-column label="周期" min-width="190"><template #default="{row}">{{row.startDate}} 至 {{row.dueDate}}</template></el-table-column>
                  <el-table-column prop="weeklyBudgetMinutes" label="周预算" width="90"/>
                  <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                </el-table>
              </article>
            </el-tab-pane>

            <el-tab-pane label="规划与任务" name="plans">
              <article class="file-panel">
                <div class="file-panel-title"><h4>学习计划</h4><small>{{learningFile.plans?.length || 0}} 个计划，{{learningFile.planVersions?.length || 0}} 条版本记录</small></div>
                <el-table :data="learningFile.plans" max-height="280" empty-text="尚未生成学习计划">
                  <el-table-column label="计划" min-width="220"><template #default="{row}"><div class="identity-cell"><b>{{row.name}}</b><small>{{row.goalName}}</small></div></template></el-table-column>
                  <el-table-column label="当前版本" width="100"><template #default="{row}">{{row.currentVersionNo?'V'+row.currentVersionNo:'—'}}</template></el-table-column>
                  <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                  <el-table-column label="更新时间" min-width="165"><template #default="{row}">{{fmt(row.updatedAt)}}</template></el-table-column>
                </el-table>
              </article>
              <article class="file-panel file-panel-spaced">
                <div class="file-panel-title"><h4>任务执行</h4><small>最近 100 条任务</small></div>
                <el-table :data="learningFile.tasks" max-height="430" empty-text="暂无学习任务">
                  <el-table-column label="任务" min-width="250"><template #default="{row}"><div class="identity-cell"><b>{{row.title}}</b><small>{{row.goalName}} · {{row.taskType}}</small></div></template></el-table-column>
                  <el-table-column label="进度" width="150"><template #default="{row}"><el-progress :percentage="Number(row.progressPercent || 0)" :stroke-width="8"/></template></el-table-column>
                  <el-table-column label="预计" width="85"><template #default="{row}">{{row.estimatedMinutes}} 分钟</template></el-table-column>
                  <el-table-column label="计划版本" width="90"><template #default="{row}">{{row.originPlanVersionNo?'V'+row.originPlanVersionNo:'—'}}</template></el-table-column>
                  <el-table-column label="截止时间" min-width="165"><template #default="{row}">{{fmt(row.dueAt)}}</template></el-table-column>
                  <el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                </el-table>
              </article>
            </el-tab-pane>

            <el-tab-pane label="学习证据" name="evidence">
              <div class="file-grid">
                <article class="file-panel">
                  <div class="file-panel-title"><h4>知识掌握度</h4><small>{{learningFile.evidence?.mastery?.length || 0}} 个知识点</small></div>
                  <el-table :data="learningFile.evidence?.mastery" max-height="360" empty-text="暂无掌握度证据">
                    <el-table-column label="知识点" min-width="180"><template #default="{row}"><div class="identity-cell"><b>{{row.knowledgeName}}</b><small>{{row.directionName}}</small></div></template></el-table-column>
                    <el-table-column label="掌握度" width="125"><template #default="{row}"><el-progress :percentage="Number(row.score || 0)" :stroke-width="8"/></template></el-table-column>
                    <el-table-column prop="level" label="等级" width="95"/>
                    <el-table-column prop="evidenceCount" label="证据数" width="80"/>
                  </el-table>
                </article>
                <article class="file-panel">
                  <div class="file-panel-title"><h4>测评记录</h4><small>{{learningFile.evidence?.assessments?.length || 0}} 次作答</small></div>
                  <el-table :data="learningFile.evidence?.assessments" max-height="360" empty-text="暂无测评记录">
                    <el-table-column label="测评" min-width="180"><template #default="{row}"><div class="identity-cell"><b>{{row.title}}</b><small>第 {{row.attemptNo}} 次 · {{row.type}}</small></div></template></el-table-column>
                    <el-table-column label="得分" width="100"><template #default="{row}">{{row.totalScore ?? '—'}} / {{row.maxScore}}</template></el-table-column>
                    <el-table-column label="状态" width="105"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                    <el-table-column label="提交时间" min-width="165"><template #default="{row}">{{fmt(row.submittedAt)}}</template></el-table-column>
                  </el-table>
                </article>
              </div>
              <article class="file-panel file-panel-spaced">
                <div class="file-panel-title"><h4>最近学习会话</h4><small>有效时长来自任务计时会话，不依赖页面停留时间</small></div>
                <el-table :data="learningFile.evidence?.studySessions" max-height="330" empty-text="暂无学习会话">
                  <el-table-column prop="taskTitle" label="任务" min-width="240"/>
                  <el-table-column prop="source" label="来源" width="100"/>
                  <el-table-column label="有效时长" width="110"><template #default="{row}">{{Math.round(Number(row.effectiveSeconds || 0)/60)}} 分钟</template></el-table-column>
                  <el-table-column label="开始时间" min-width="165"><template #default="{row}">{{fmt(row.startedAt)}}</template></el-table-column>
                  <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain" :title="row.status">{{statusLabel(row.status)}}</el-tag></template></el-table-column>
                </el-table>
              </article>
              <article class="file-panel file-panel-spaced">
                <div class="file-panel-title"><h4>知识点自评</h4><small>{{learningFile.evidence?.selfAssessments?.length || 0}} 条 · 主观证据，权重 0.1，仅作掌握度补充</small></div>
                <el-table :data="learningFile.evidence?.selfAssessments" max-height="330" empty-text="暂无自评证据">
                  <el-table-column label="知识点" min-width="220"><template #default="{row}"><div class="identity-cell"><b>{{row.knowledgeName}}</b><small>{{row.directionName}} · {{row.knowledgeCode}}</small></div></template></el-table-column>
                  <el-table-column label="自评等级" width="95"><template #default="{row}"><el-tag :type="row.level>=3?'success':row.level>=2?'warning':'danger'" effect="plain">{{row.level}} / 5</el-tag></template></el-table-column>
                  <el-table-column label="最近学习" width="110"><template #default="{row}">{{row.lastStudiedAt || '—'}}</template></el-table-column>
                  <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip/>
                  <el-table-column label="自评时间" min-width="165"><template #default="{row}">{{fmt(row.assessedAt)}}</template></el-table-column>
                </el-table>
              </article>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="directionDialog" :title="directionForm.id?'编辑学习方向':'新增学习方向'" width="540px">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="编码"><el-input v-model="directionForm.code" placeholder="例如 ECONOMICS"/></el-form-item><el-form-item label="名称"><el-input v-model="directionForm.name"/></el-form-item><el-form-item label="父方向"><el-select v-model="directionForm.parentId" clearable><el-option v-for="item in directions.filter(x=>String(x.id)!==String(directionForm.id))" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item label="状态"><el-select v-model="directionForm.status"><el-option v-for="item in ['ACTIVE','DRAFT','DISABLED']" :key="item" :label="item" :value="item"/></el-select></el-form-item><el-form-item label="排序"><el-input-number v-model="directionForm.sortNo" :min="0"/></el-form-item></div></el-form>
      <template #footer><el-button @click="directionDialog=false">取消</el-button><el-button type="primary" @click="saveDirection">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="knowledgeDialog" :title="knowledgeForm.id?'编辑知识点':'新增知识点'" width="620px">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="学习方向"><el-select v-model="knowledgeForm.directionId"><el-option v-for="item in directions" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item label="父知识点"><el-select v-model="knowledgeForm.parentId" clearable><el-option v-for="item in knowledge.filter(x=>String(x.directionId)===String(knowledgeForm.directionId)&&String(x.id)!==String(knowledgeForm.id))" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item label="编码"><el-input v-model="knowledgeForm.code" placeholder="大写字母、数字、下划线"/></el-form-item><el-form-item label="名称"><el-input v-model="knowledgeForm.name"/></el-form-item><el-form-item label="层级"><el-input-number v-model="knowledgeForm.level" :min="1" :max="20"/></el-form-item><el-form-item label="默认权重"><el-input-number v-model="knowledgeForm.defaultWeight" :min="0.0001" :step="0.1" :precision="4"/></el-form-item><el-form-item label="状态"><el-select v-model="knowledgeForm.status"><el-option v-for="item in ['ACTIVE','DRAFT','DISABLED']" :key="item" :label="item" :value="item"/></el-select></el-form-item></div></el-form>
      <template #footer><el-button @click="knowledgeDialog=false">取消</el-button><el-button type="primary" @click="saveKnowledge">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="dependencyDialog" title="新增知识前置关系" width="560px">
      <p class="dialog-lead">请选择同一学习方向内的两个知识点，前置知识点必须先掌握。</p>
      <el-form label-position="top"><el-form-item label="前置知识点"><el-select v-model="dependencyForm.predecessorId" filterable><el-option v-for="item in dependencyCandidates" :key="item.id" :label="`${item.name} · ${item.code}`" :value="item.id"/></el-select></el-form-item><el-form-item label="后续知识点"><el-select v-model="dependencyForm.successorId" filterable><el-option v-for="item in dependencyCandidates.filter(x=>String(x.id)!==String(dependencyForm.predecessorId))" :key="item.id" :label="`${item.name} · ${item.code}`" :value="item.id"/></el-select></el-form-item></el-form>
      <template #footer><el-button @click="dependencyDialog=false">取消</el-button><el-button type="primary" @click="saveDependency">创建关系</el-button></template>
    </el-dialog>

    <el-dialog v-model="questionDialog" title="新增公共题目" width="760px">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="题型"><el-select v-model="questionForm.type"><el-option v-for="item in ['SINGLE_CHOICE','MULTIPLE_CHOICE','TRUE_FALSE','FILL_BLANK']" :key="item" :label="item" :value="item"/></el-select></el-form-item><el-form-item label="难度"><el-rate v-model="questionForm.difficulty"/></el-form-item></div><el-form-item label="题干"><el-input v-model="questionForm.stem" type="textarea" :rows="3" maxlength="4000" show-word-limit/></el-form-item><el-form-item label="选项（每行一个）"><el-input v-model="questionForm.optionsText" type="textarea" :rows="4" placeholder="选项 A&#10;选项 B&#10;选项 C"/></el-form-item><el-form-item label="标准答案"><el-input v-model="questionForm.answerText" placeholder="单选填 A；多选填 A,B；布尔值可填 true"/></el-form-item><el-form-item label="关联知识点"><el-select v-model="questionForm.knowledgePointIds" multiple filterable><el-option v-for="item in knowledge" :key="item.id" :label="`${item.directionName} · ${item.name}`" :value="item.id"/></el-select></el-form-item><el-form-item label="解析"><el-input v-model="questionForm.analysis" type="textarea" :rows="3"/></el-form-item><el-form-item label="评分规则 JSON（可选）"><el-input v-model="questionForm.rubricText" type="textarea" :rows="2" placeholder='{"keywords":["关键词"]}'/></el-form-item></el-form>
      <template #footer><el-button @click="questionDialog=false">取消</el-button><el-button type="primary" @click="saveQuestion">发布题目</el-button></template>
    </el-dialog>

    <el-dialog v-model="questionDetail" title="题目详情" width="680px">
      <template v-if="questionDetail">
        <div class="question-detail-head">
          <el-tag effect="plain" :title="questionDetail.type">{{questionDetail.type}}</el-tag>
          <el-rate :model-value="questionDetail.difficulty" disabled/>
          <span class="muted">V{{questionDetail.currentVersionNo}} · {{statusLabel(questionDetail.status)}}</span>
        </div>
        <h4 class="question-stem">{{questionDetail.stem}}</h4>
        <div v-if="questionDetail.options?.length" class="question-options">
          <div v-for="(option, index) in questionDetail.options" :key="index"><b>{{String.fromCharCode(65 + index)}}.</b><span>{{option}}</span></div>
        </div>
        <el-descriptions :column="1" border class="question-answer">
          <el-descriptions-item label="标准答案"><b class="answer-text">{{formatAnswer(questionDetail.answer)}}</b></el-descriptions-item>
          <el-descriptions-item label="关联知识点">{{questionDetail.knowledgePointNames || '未关联'}}</el-descriptions-item>
          <el-descriptions-item label="解析">{{questionDetail.analysis || '无'}}</el-descriptions-item>
          <el-descriptions-item v-if="questionDetail.rubric" label="评分规则"><pre class="rubric-pre">{{JSON.stringify(questionDetail.rubric, null, 2)}}</pre></el-descriptions-item>
        </el-descriptions>
      </template>
    </el-dialog>

    <el-dialog v-model="modelDialog" :title="modelForm.publicId?'编辑运行模型':'新增运行模型'" width="650px">
      <el-alert :title="modelForm.status==='ACTIVE'?'保存当前运行模型时会先测试新配置，成功后立即热更新。':'可直接输入 API Key，后端会使用 AES-GCM 加密保存；也支持 env:环境变量名。'" type="info" :closable="false"/>
      <el-form label-position="top" class="dialog-form"><div class="form-grid"><el-form-item label="适配类型"><el-input v-model="modelForm.provider"/></el-form-item><el-form-item label="服务商名称"><el-input v-model="modelForm.providerName"/></el-form-item><el-form-item label="Base URL"><el-input v-model="modelForm.baseUrl" placeholder="https://api.example.com/v1"/></el-form-item><el-form-item label="API Key / 密钥引用"><el-input v-model="modelForm.secretRef" type="password" show-password autocomplete="new-password" placeholder="粘贴 API Key，或 env:MODEL_API_KEY"/></el-form-item><el-form-item label="用途标识"><el-input v-model="modelForm.purpose"/></el-form-item><el-form-item label="模型名称"><el-input v-model="modelForm.modelName" placeholder="例如 deepseek-chat"/></el-form-item><el-form-item label="超时秒数"><el-input-number v-model="modelForm.timeoutSeconds" :min="1" :max="300"/></el-form-item><el-form-item label="每日限额"><el-input-number v-model="modelForm.dailyLimit" :min="1"/></el-form-item></div><el-form-item label="运行参数 JSON"><el-input v-model="modelForm.parametersText" type="textarea" :rows="3" placeholder='{"maxOutputTokens":1200,"thinking":"disabled","allowHttp":false}'/></el-form-item></el-form>
      <p v-if="modelForm.publicId" class="dialog-lead">密钥引用留空时保持原配置不变。</p>
      <template #footer><el-button @click="modelDialog=false">取消</el-button><el-button type="primary" @click="saveModel">{{modelForm.status==='ACTIVE'?'测试并保存':'保存配置'}}</el-button></template>
    </el-dialog>

    <el-dialog v-model="promptDialog" title="创建提示词新版本" width="760px">
      <el-form label-position="top"><el-form-item label="提示词编码"><el-input v-model="promptForm.code" placeholder="例如 PROFILE_INTERVIEW"/></el-form-item><el-form-item label="提示词内容"><el-input v-model="promptForm.content" type="textarea" :rows="12"/></el-form-item><el-form-item label="输出 Schema JSON（可选）"><el-input v-model="promptForm.schemaText" type="textarea" :rows="4"/></el-form-item></el-form>
      <template #footer><el-button @click="promptDialog=false">取消</el-button><el-button type="primary" @click="savePrompt">创建 DRAFT 版本</el-button></template>
    </el-dialog>

    <el-drawer v-model="promptDetail" title="提示词详情" size="52%">
      <template v-if="promptDetail"><el-descriptions :column="2" border><el-descriptions-item label="编码">{{promptDetail.code}}</el-descriptions-item><el-descriptions-item label="版本">V{{promptDetail.versionNo}}</el-descriptions-item><el-descriptions-item label="状态">{{promptDetail.status}}</el-descriptions-item><el-descriptions-item label="创建时间">{{fmt(promptDetail.createdAt)}}</el-descriptions-item></el-descriptions><h4>内容</h4><pre class="content-preview">{{promptDetail.content}}</pre><h4>Schema</h4><pre class="content-preview">{{promptDetail.schemaJson || '未配置'}}</pre></template>
    </el-drawer>
    <el-drawer v-model="auditDetail" title="审计记录详情" size="48%">
      <template v-if="auditDetail"><el-descriptions :column="1" border><el-descriptions-item label="时间">{{fmt(auditDetail.createdAt)}}</el-descriptions-item><el-descriptions-item label="操作人">{{auditDetail.operatorName}}</el-descriptions-item><el-descriptions-item label="操作">{{auditDetail.action}}</el-descriptions-item><el-descriptions-item label="资源">{{auditDetail.resourceType}} · {{auditDetail.resourceId}}</el-descriptions-item><el-descriptions-item label="请求 ID">{{auditDetail.requestId}}</el-descriptions-item><el-descriptions-item label="结果">{{auditDetail.result}}</el-descriptions-item><el-descriptions-item label="变更前">{{auditDetail.beforeSummary || '—'}}</el-descriptions-item><el-descriptions-item label="变更后">{{auditDetail.afterSummary || '—'}}</el-descriptions-item></el-descriptions></template>
    </el-drawer>
    <el-drawer v-model="contentDrawer" size="62%" class="content-viewer-drawer">
      <template #header>
        <div class="file-drawer-head">
          <div><span class="eyebrow">DOCUMENT CONTENT</span><h3>{{contentDoc?.displayName || '文档内容预览'}}</h3><p v-if="contentDoc">{{contentDoc.spaceName || ''}}{{contentDoc.owner ? ' · ' + contentDoc.owner : ''}} · 当前版本 V{{contentDoc.activeVersionNo}} 的切块原文</p></div>
        </div>
      </template>
      <div v-loading="contentLoading" class="content-viewer">
        <template v-if="contentChunks.length">
          <div class="content-toolbar">
            <el-select :model-value="contentIndex" style="width:210px" @change="jumpChunk">
              <el-option v-for="(chunk, i) in contentChunks" :key="chunk.chunkNo" :label="`第 ${chunk.chunkNo} 段${chunk.pageFrom ? ' · 第 ' + chunk.pageFrom + ' 页' : ''}`" :value="i"/>
            </el-select>
            <el-button :disabled="contentIndex === 0" @click="jumpChunk(contentIndex - 1)">上一段</el-button>
            <el-button :disabled="contentIndex === contentChunks.length - 1" @click="jumpChunk(contentIndex + 1)">下一段</el-button>
            <span class="muted">{{ contentIndex + 1 }} / {{ contentChunks.length }}</span>
          </div>
          <div v-if="currentChunk" class="chunk-card">
            <div class="chunk-meta">
              <b>第 {{ currentChunk.chunkNo }} 段</b>
              <span v-if="chunkTitle(currentChunk)">{{ chunkTitle(currentChunk) }}</span>
              <span v-if="currentChunk.pageFrom">第 {{ currentChunk.pageFrom }}{{ currentChunk.pageTo && currentChunk.pageTo !== currentChunk.pageFrom ? '–' + currentChunk.pageTo : '' }} 页</span>
              <span v-if="currentChunk.paragraphFrom">段落 {{ currentChunk.paragraphFrom }}–{{ currentChunk.paragraphTo }}</span>
              <span>{{ currentChunk.tokenCount }} tokens</span>
            </div>
            <pre class="chunk-text">{{ currentChunk.text }}</pre>
          </div>
        </template>
        <el-empty v-else-if="!contentLoading" description="该文档没有可预览的内容（可能解析失败或尚未索引）" :image-size="80"/>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.admin-page{--admin-navy:#132d25;--admin-soft:#eef3ed;display:grid;gap:22px}
.admin-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;padding:8px 4px}
.admin-hero h2{margin:8px 0 4px;font:500 clamp(30px,4vw,46px) var(--display);letter-spacing:-.03em}.admin-hero p{max-width:760px;margin:0;color:var(--muted);font-size:13px;line-height:1.8}
.hero-actions,.filters,.toolbar,.job-summary{display:flex;align-items:center;gap:10px}.live-state{display:flex;align-items:center;gap:8px;color:#4c685d;font-size:11px;font-weight:700}.live-state i{width:8px;height:8px;border-radius:50%;background:#2f9d6d;box-shadow:0 0 0 5px rgba(47,157,109,.1)}
.admin-shell{padding:10px 26px 28px;min-height:680px}.admin-tabs :deep(.el-tabs__header){margin:0 0 26px}.admin-tabs :deep(.el-tabs__nav-scroll){padding:0 4px}.admin-tabs :deep(.el-tabs__item){height:58px;padding:0 22px}
.metric-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px}.metric-card{position:relative;min-width:0;padding:20px;border:1px solid rgba(31,62,49,.08);border-radius:18px;text-align:left;background:linear-gradient(145deg,rgba(255,255,255,.72),rgba(236,242,235,.58))}.metric-card span,.metric-card small{display:block}.metric-card span{color:#718078;font-size:10px;font-weight:800;letter-spacing:.08em}.metric-card strong{display:block;margin:13px 0 7px;color:var(--admin-navy);font:500 30px var(--display)}.metric-card small{overflow:hidden;padding-right:20px;color:#8b958f;font-size:9px;text-overflow:ellipsis;white-space:nowrap}.metric-card-link{appearance:none;font-family:inherit;cursor:pointer;transition:transform .18s ease,border-color .18s ease,box-shadow .18s ease,background .18s ease}.metric-card-link i{position:absolute;right:17px;bottom:17px;color:#789287;font-size:15px;font-style:normal;transition:transform .18s ease,color .18s ease}.metric-card-link i.expanded{transform:rotate(180deg)}.metric-card-link:hover{transform:translateY(-3px);border-color:rgba(40,105,77,.25);background:linear-gradient(145deg,rgba(255,255,255,.9),rgba(226,239,230,.78));box-shadow:0 14px 30px rgba(31,62,49,.1)}.metric-card-link:hover i{color:var(--admin-green)}.metric-card-link:hover i:not(.expanded){transform:translateX(3px)}.metric-card-link:focus-visible{outline:3px solid rgba(56,132,99,.24);outline-offset:3px}.active-goals-panel{margin-top:16px;padding:20px 22px 10px;border:1px solid rgba(38,92,68,.14);border-radius:20px;background:rgba(251,253,249,.82);box-shadow:0 16px 40px rgba(28,58,45,.07)}.active-goals-head{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-bottom:16px}.active-goals-head h3,.active-goals-head p{margin:0}.active-goals-head h3{margin-top:5px;color:var(--admin-navy);font:500 24px var(--display)}.active-goals-head p{margin-top:5px;color:#819087;font-size:10px}.active-goals-head>div:last-child{display:flex;align-items:center;gap:10px}.active-goals-head>div:last-child>strong{color:var(--admin-green);font:500 25px var(--display)}.active-goals-head>div:last-child>span{margin-right:4px;color:#819087;font-size:9px}.goal-owner{display:block;width:100%;padding:0;border:0;text-align:left;background:transparent;cursor:pointer}.goal-owner b,.goal-owner small,.goal-summary b,.goal-summary small{display:block}.goal-owner b{color:var(--admin-green)}.goal-owner small,.goal-summary small{margin-top:4px;color:#89948e;font-size:9px}.goal-owner:hover b{text-decoration:underline}.goal-summary b{color:var(--admin-navy)}.goal-progress{display:grid;grid-template-columns:1fr auto;align-items:center;gap:7px}.goal-progress small{color:#829087;font-size:9px}
.overview-grid{display:grid;grid-template-columns:.86fr 1.14fr;gap:16px;margin-top:16px}.govern-card,.sub-panel{padding:22px;border:1px solid rgba(31,62,49,.08);border-radius:20px;background:rgba(255,255,255,.46)}.section-title,.sub-head{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.section-title{margin-bottom:20px}.section-title h3,.sub-head h4{margin:3px 0 0;font:500 23px var(--display)}.section-title p,.sub-head small{margin:5px 0 0;color:var(--muted);font-size:11px}.sub-head h4{font-size:19px}.sub-head>span{display:grid;place-items:center;min-width:32px;height:32px;border-radius:10px;background:var(--admin-soft);color:var(--green);font-size:11px;font-weight:800}.hint{margin:10px 0 14px;color:var(--muted);font-size:11px;line-height:1.8}
.pulse-list{display:grid;gap:5px;margin-top:12px}.pulse-list>div{display:grid;grid-template-columns:38px 1fr auto;align-items:center;gap:12px;padding:14px 4px;border-bottom:1px solid var(--line)}.pulse-list p{margin:0}.pulse-list b,.pulse-list small{display:block}.pulse-list b{font-size:12px}.pulse-list small{margin-top:3px;color:var(--muted);font-size:9px}.pulse-list>div>strong{font:500 22px var(--display)}.pulse-icon{display:grid;place-items:center;width:34px;height:34px;border-radius:11px;color:#64736b;background:#edf0ec;font-size:9px;font-weight:800}.pulse-icon.healthy{color:#257252;background:#deeee4}.pulse-icon.alert{color:#a54b45;background:#f5e3e0}
.filters .el-input{width:210px}.filters .el-select{width:140px}.section-title>.toolbar .el-input{width:210px}.section-title>.toolbar .el-select{width:180px}.identity-cell b,.identity-cell small{display:block}.identity-cell small{margin-top:4px;color:var(--muted);font-size:10px}.role-tag{margin:2px 4px 2px 0}.pager{display:flex;justify-content:flex-end;padding-top:20px}
.catalog-grid{display:grid;grid-template-columns:280px 1fr;gap:16px}.sub-panel.wide{min-width:0}.direction-list{display:grid;gap:5px;margin-top:16px}.direction-list button{position:relative;display:grid;grid-template-columns:1fr auto;align-items:center;gap:10px;width:100%;padding:13px;border:0;border-radius:14px;color:#495950;background:transparent;text-align:left;transition:.2s}.direction-list button:hover,.direction-list button.active{color:var(--admin-navy);background:#edf3ed}.direction-list b,.direction-list small{display:block}.direction-list b{font-size:12px}.direction-list small{margin-top:4px;color:#8b978f;font-size:9px}.direction-list em{display:grid;place-items:center;min-width:27px;height:27px;border-radius:9px;background:rgba(255,255,255,.72);font-size:10px;font-style:normal}.direction-list i{position:absolute;right:47px;opacity:0;color:var(--green);font-size:9px;font-style:normal}.direction-list button:hover i{opacity:1}.direction-list button.child{margin-left:16px;padding-left:14px;border-left:2px solid var(--line);background:#fbfcf8;border-radius:0 13px 13px 0}.direction-list button.child:hover,.direction-list button.child.active{background:#eef4ec}.sub-head .el-input{width:210px}.dependency-panel,.prompt-panel{margin-top:16px}.dependency-list{display:flex;flex-wrap:wrap;gap:9px;margin-top:16px}.dependency-list>div{display:flex;align-items:center;gap:9px;padding:9px 10px 9px 14px;border:1px solid var(--line);border-radius:13px;background:#f8faf6;font-size:11px}.dependency-list i{color:var(--green);font-style:normal}.dependency-list small{color:var(--muted)}
.question-stem{margin-bottom:5px;line-height:1.6}.line-clamp{display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;-webkit-line-clamp:2;line-height:1.6}.job-toolbar{display:flex;flex-direction:column;align-items:flex-end;gap:10px}.job-summary{padding:10px 14px;border-radius:14px;background:var(--admin-soft);font-size:11px}.job-summary b{margin-left:5px;font:500 18px var(--display)}
.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.form-grid .el-select{width:100%}.dialog-lead{margin:-4px 0 20px;color:var(--muted);font-size:12px}.dialog-form{margin-top:20px}.content-preview{padding:18px;overflow:auto;border-radius:16px;background:#17231e;color:#dce9e1;font:12px/1.8 ui-monospace,Consolas,monospace;white-space:pre-wrap}
.file-drawer-head{display:flex;align-items:center;justify-content:space-between;width:100%;padding-right:14px}.file-drawer-head h3{margin:4px 0 2px;color:var(--admin-navy);font:500 26px var(--display)}.file-drawer-head p{margin:0;color:var(--muted);font-size:11px}.learning-file{min-height:540px}.file-metrics{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px}.file-metrics article{padding:15px 17px;border:1px solid var(--line);border-radius:15px;background:#f7faf6}.file-metrics span,.file-metrics strong{display:block}.file-metrics span{color:var(--muted);font-size:9px;font-weight:800;letter-spacing:.06em}.file-metrics strong{margin-top:8px;color:var(--admin-navy);font:500 22px var(--display)}.file-tabs{margin-top:18px}.file-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.file-panel{min-width:0;padding:18px;border:1px solid var(--line);border-radius:17px;background:rgba(248,250,247,.76)}.file-panel-spaced{margin-top:14px}.file-panel-title,.file-section-head{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin-bottom:14px}.file-panel-title h4,.file-section-head h4{margin:0;font:500 18px var(--display)}.file-panel-title small,.file-section-head p{margin:3px 0 0;color:var(--muted);font-size:10px}.record-list{display:grid;gap:7px;margin-top:13px}.record-list>div{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-radius:12px;background:#fff}.record-list b,.record-list small{display:block}.record-list small{margin-top:3px;color:var(--muted);font-size:9px}.availability-list{display:flex;flex-wrap:wrap;gap:7px;margin-top:13px}.availability-list span{padding:7px 9px;border-radius:9px;background:#eaf1ea;color:#4a6258;font-size:9px}.exceptions-block{margin-top:16px;padding-top:14px;border-top:1px solid var(--line)}.exceptions-block .availability-list{margin-top:8px}.exception-chip b{display:block;margin-bottom:2px;color:var(--admin-navy);font-size:10px}.batch-title{display:flex;align-items:center;gap:12px}.batch-title b{font-size:12px}.batch-title small{color:var(--muted);font-size:9px}.recommendation-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;padding:4px 2px 14px}.recommendation-grid article{padding:15px;border:1px solid var(--line);border-radius:14px;background:#f8faf6}.recommendation-grid article>div{display:flex;align-items:center;gap:7px;color:var(--muted);font-size:9px}.recommendation-grid h5{margin:11px 0 7px;font-size:13px}.recommendation-grid p{min-height:40px;margin:0;color:#596b62;font-size:10px;line-height:1.6}.recommendation-grid article>small{display:block;margin-top:10px;color:var(--muted);font-size:9px}
.record-tags{display:flex;align-items:center;gap:6px}
.charts-panel{margin-top:22px}.charts-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.chart-card{min-width:0;padding:18px 18px 8px;border:1px solid rgba(31,62,49,.08);border-radius:18px;background:rgba(255,255,255,.46)}.chart-card.chart-wide{grid-column:span 2}.chart-head{display:flex;align-items:baseline;justify-content:space-between;gap:10px;margin-bottom:4px}.chart-head h4{margin:0;color:var(--admin-navy);font:500 14px var(--display)}.chart-head small{color:var(--muted);font-size:9px}.chart{height:230px}
.space-doc-panel{padding:4px 28px 18px 60px}.space-doc-head{margin-bottom:10px}.space-doc-head h5{margin:3px 0 0;font:500 15px var(--display)}.space-stat b,.space-stat small{display:block}.space-stat small{margin-top:3px;color:var(--muted);font-size:9px}
.content-viewer{min-height:380px}.content-toolbar{display:flex;align-items:center;gap:10px;margin-bottom:14px}.content-toolbar .muted{font-size:10px}.chunk-card{padding:18px;border:1px solid var(--line);border-radius:15px;background:#fbfdf9}.chunk-meta{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px;color:var(--muted);font-size:10px}.chunk-meta b{color:var(--admin-navy);font-size:12px}.chunk-meta span{padding:3px 8px;border-radius:7px;background:#eef3ed}.chunk-text{margin:0;white-space:pre-wrap;word-break:break-word;font:12.5px/1.9 ui-monospace,Consolas,monospace;color:#3c4a42}
.question-detail-head{display:flex;align-items:center;gap:10px;margin-bottom:12px}.question-detail-head .muted{font-size:10px}.question-options{display:grid;gap:7px;margin:14px 0}.question-options>div{display:flex;gap:9px;padding:9px 12px;border-radius:10px;background:#f4f7f3;font-size:13px}.question-options b{color:var(--green)}.question-answer{margin-top:14px}.answer-text{color:var(--green)}.rubric-pre{margin:0;padding:12px;overflow:auto;border-radius:10px;background:#17231e;color:#dce9e1;font:11px/1.7 ui-monospace,Consolas,monospace;white-space:pre-wrap}
@media(max-width:1200px){.charts-grid{grid-template-columns:1fr}.chart-card.chart-wide{grid-column:span 1}}
@media(max-width:1200px){.metric-grid{grid-template-columns:repeat(3,1fr)}.overview-grid{grid-template-columns:1fr}.section-title{align-items:flex-start;flex-direction:column}.filters,.toolbar{flex-wrap:wrap}.catalog-grid{grid-template-columns:240px 1fr}}
@media(max-width:1200px){.file-metrics{grid-template-columns:repeat(3,1fr)}.recommendation-grid{grid-template-columns:repeat(2,1fr)}}
@media(max-width:760px){.admin-hero{align-items:flex-start;flex-direction:column}.admin-shell{padding:8px 14px 22px}.metric-grid,.file-metrics{grid-template-columns:repeat(2,1fr)}.catalog-grid,.file-grid,.recommendation-grid{grid-template-columns:1fr}.form-grid{grid-template-columns:1fr}.filters .el-input,.filters .el-select,.section-title>.toolbar .el-input,.section-title>.toolbar .el-select{width:100%}.hero-actions{width:100%;justify-content:space-between}.admin-tabs :deep(.el-tabs__item){padding:0 13px}}
</style>
