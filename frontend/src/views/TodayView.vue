<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { isDark } from '../theme'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import { api } from '../api/http'

use([CanvasRenderer, GraphChart, TooltipComponent])
const router = useRouter()
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: true })

function escapeHtml(value: unknown) {
  return String(value ?? '').replace(/[&<>'"]/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;',
  }[character] || character))
}

function safeExternalUrl(value: unknown) {
  if (typeof value !== 'string' || !value.trim()) return undefined
  try {
    const parsed = new URL(value, window.location.origin)
    return ['http:', 'https:'].includes(parsed.protocol) ? parsed.href : undefined
  } catch { return undefined }
}

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
  temporalState: 'PAST' | 'TODAY' | 'FUTURE' | 'UNSCHEDULED'
}

type TaskGraphView = {
  today: string
  timezone: string
  nodes: TaskGraphNode[]
  edges: Array<{ source: string; target: string }>
}

const screen = ref<'graph' | 'detail'>('graph')
const graphLoading = ref(false)
const graph = ref<TaskGraphView>({
  today: dayjs().format('YYYY-MM-DD'),
  timezone: '',
  nodes: [],
  edges: [],
})
const date = ref(dayjs().format('YYYY-MM-DD'))
const tasks = ref<any[]>([])
const upcoming = ref<any[]>([])
const selected = ref<any>()
const session = ref<any>()
const elapsed = ref(0)
const note = ref({ title: '学习笔记', markdown: '', version: undefined as number | undefined })
const timer = ref<number>()
const nbOpen = ref<string>('')
function togglePanel(key: string) {
  nbOpen.value = nbOpen.value === key ? '' : key
}
const queueCollapsed = ref(false)

// 左栏（今日路径 + Agent 讨论 + 笔记）可拖动调整宽度，上限防止挤压右侧学习区
const layoutRef = ref<HTMLElement | null>(null)
const LEFT_MIN = 300
const LEFT_MAX = 460
const savedLeft = Number(localStorage.getItem('today-left-width') ?? '')
const leftWidth = ref(savedLeft >= LEFT_MIN && savedLeft <= LEFT_MAX ? savedLeft : 350)
const leftDragging = ref(false)

function startResize(e: PointerEvent) {
  if (queueCollapsed.value || !layoutRef.value) return
  e.preventDefault()
  leftDragging.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  const layoutLeft = layoutRef.value.getBoundingClientRect().left
  const move = (ev: PointerEvent) => {
    leftWidth.value = Math.min(LEFT_MAX, Math.max(LEFT_MIN, Math.round(ev.clientX - layoutLeft)))
  }
  const up = () => {
    leftDragging.value = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    localStorage.setItem('today-left-width', String(leftWidth.value))
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}
const chatMessages = ref<any[]>([])
const chatDraft = ref('')
const chatLoading = ref(false)
const chatScroll = ref<HTMLElement>()
const learningBlock = ref<any>()
const blockLoading = ref(false)
const blockGenerating = ref(false)
const blockTestAnswers = ref<Record<string, string>>({})
const blockTestResult = ref<any>()
const openExerciseAnswers = ref<number[]>([])
const blockStep = ref(1)
const availableBlockSpaces = ref<any[]>([])
const selectedBlockSpaceIds = ref<string[]>([])
const blockSourcesLoading = ref(false)
const rescheduleVisible = ref(false)
const rescheduleSubmitting = ref(false)
const rescheduleForm = ref({ scheduledStart:'', dueAt:'', reason:'' })

const entity = computed(() => selected.value?.task || {})
const running = computed(() => Boolean(session.value) && session.value.status === 'RUNNING')
const completedCount = computed(() => tasks.value.filter((row) => row.task.lifecycleStatus === 'COMPLETED').length)
const plannedMinutes = computed(() => tasks.value.reduce((total, row) => total + Number(row.task.estimatedMinutes || 0), 0))
const currentDateLabel = computed(() => dayjs(date.value).isSame(dayjs(), 'day') ? '今天' : dayjs(date.value).format('M 月 D 日'))
const formatTime = computed(() => `${String(Math.floor(elapsed.value / 60)).padStart(2, '0')}:${String(elapsed.value % 60).padStart(2, '0')}`)
const todayNodes = computed(() => graph.value.nodes.filter((node) => node.availableToday))
const futureNodes = computed(() => graph.value.nodes.filter((node) => node.temporalState === 'FUTURE'))
const goalCount = computed(() => new Set(graph.value.nodes.map((node) => node.goalId)).size)
const blockGenerated = computed(() => learningBlock.value?.generationStatus === 'GENERATED')
const blockPassed = computed(() => learningBlock.value?.status === 'COMPLETED')
const lastStudyText = computed(() => {
  const seconds = Number(learningBlock.value?.effectiveSeconds || 0)
  const minutes = seconds > 0 ? `${Math.max(1, Math.round(seconds / 60))} 分钟` : ''
  const when = entity.value?.completedAt ? dayjs(entity.value.completedAt).format('M 月 D 日 HH:mm') : ''
  return [when, minutes].filter(Boolean).join(' · ')
})
const allBlockQuestionsAnswered = computed(() => {
  const questions = learningBlock.value?.testQuestions || []
  return questions.length > 0 && questions.every((question: any) => Boolean(blockTestAnswers.value[question.id]))
})
const selectedAvailableToday = computed(() => Boolean(
  selected.value && graph.value.today && dayjs(date.value).isSame(dayjs(graph.value.today), 'day'),
))
const selectedGraphNode = computed(() => graph.value.nodes.find((node) => node.publicId === entity.value.publicId))
const graphOption = computed(() => ({
  tooltip: {
    trigger: 'item',
    confine: true,
    borderWidth: 0,
    backgroundColor: 'rgba(17, 25, 21, .94)',
    textStyle: { color: '#f4f5f1', fontSize: 12 },
    formatter: (params: any) => {
      if (params.dataType === 'edge') return '计划中的前置关系'
      const node = params.data.raw as TaskGraphNode
      const dateText = node.scheduledStart ? dayjs(node.scheduledStart).format('M 月 D 日 HH:mm') : '待安排'
      return [
        `<b>${escapeHtml(node.title)}</b>`,
        `${escapeHtml(node.goalName)} · ${node.estimatedMinutes} 分钟`,
        dateText,
        node.availableToday ? '今天可进入'
          : node.status === 'COMPLETED' ? '已完成 · 点击回顾学习内容'
          : '未到执行日期，仅供预览',
      ].join('<br/>')
    },
  },
  series: [{
    type: 'graph',
    layout: 'force',
    roam: true,
    draggable: false,
    animationDuration: 700,
    animationDurationUpdate: 500,
    edgeSymbol: ['none', 'arrow'],
    edgeSymbolSize: [0, 8],
    force: { repulsion: 420, edgeLength: [145, 225], gravity: 0.09, friction: 0.55 },
    data: graph.value.nodes.map((node) => {
      const isToday = node.availableToday
      const isCompleted = node.status === 'COMPLETED'
      const isPast = node.temporalState === 'PAST'
      const dark = isDark.value
      const openable = isToday || isCompleted // 今天可进入，或已完成可回顾
      return {
        id: node.publicId,
        name: node.title,
        raw: node,
        symbolSize: openable ? (isToday ? 116 : 104) : 94,
        itemStyle: {
          color: isCompleted ? '#2f7d58' : isToday ? '#151a17' : (dark ? '#46534b' : '#d2d5d1'),
          borderColor: openable ? '#d8b66b' : (dark ? '#5a6a60' : '#c7cbc6'),
          borderWidth: openable ? (isToday ? 4 : 2) : 1,
          shadowBlur: isToday ? 24 : 0,
          shadowColor: dark ? 'rgba(0, 0, 0, .5)' : 'rgba(21, 26, 23, .24)',
        },
        label: {
          show: true,
          width: isToday ? 88 : 72,
          overflow: 'truncate',
          color: isCompleted ? '#f0f7f2' : isToday ? '#f7f5ef' : (dark ? '#9fb3a6' : '#7d837f'),
          fontSize: openable ? 11 : 10,
          fontWeight: isToday ? 700 : 500,
          formatter: node.title,
        },
        emphasis: {
          scale: openable ? 1.08 : 1,
          itemStyle: { borderColor: openable ? '#e8ca85' : (dark ? '#5a6a60' : '#c7cbc6') },
        },
      }
    }),
    links: graph.value.edges,
    lineStyle: { color: '#aeb5af', width: 1.4, curveness: 0.06, opacity: 0.72 },
    emphasis: { focus: 'adjacency', lineStyle: { width: 2.4, opacity: 1 } },
  }],
}))

async function loadGraph() {
  graphLoading.value = true
  try {
    graph.value = await api<TaskGraphView>({ url: '/tasks/graph' })
    date.value = graph.value.today
  } finally {
    graphLoading.value = false
  }
}

async function openGraphTask(node: TaskGraphNode) {
  if (!node.availableToday && node.status !== 'COMPLETED') return
  date.value = graph.value.today
  await load()
  let target = tasks.value.find((row) => row.task.publicId === node.publicId)
  if (!target && node.status === 'COMPLETED') {
    // 已完成任务不在今天的执行清单，直接拉任务与知识块用于回顾
    try {
      const detail = await api<any>({ url: `/tasks/${node.publicId}` })
      const block = await api<any>({ url: `/tasks/${node.publicId}/learning-block` })
      // GET /tasks/{id} 返回 TaskView（任务实体在 .task 字段），与 /tasks 列表的 row 结构对齐
      target = { task: detail.task, learningBlock: block }
    } catch {
      ElMessage.warning('回顾任务加载失败，请刷新图谱')
      await loadGraph()
      return
    }
  }
  if (!target) {
    ElMessage.warning('这项任务已不在今天的执行清单中，请刷新图谱')
    await loadGraph()
    return
  }
  await select(target)
  screen.value = 'detail'
}

async function handleGraphClick(params: any) {
  if (params.dataType !== 'node') return
  await openGraphTask(params.data.raw as TaskGraphNode)
}

async function backToGraph() {
  await loadGraph()
  screen.value = 'graph'
}

async function load() {
  tasks.value = await api<any[]>({ url: '/tasks', params: { date: date.value } })
  upcoming.value = []
  if (!tasks.value.length) {
    const all = await api<any[]>({ url: '/tasks' })
    const end = dayjs(date.value).endOf('day')
    upcoming.value = all
      .filter((row) => row.task?.scheduledStart && dayjs(row.task.scheduledStart).isAfter(end) && !['COMPLETED', 'CANCELED'].includes(row.task.lifecycleStatus))
      .sort((a, b) => dayjs(a.task.scheduledStart).valueOf() - dayjs(b.task.scheduledStart).valueOf())
      .slice(0, 3)
  }
  if (selected.value) selected.value = tasks.value.find((row) => row.task.publicId === entity.value.publicId)
  if (!selected.value && tasks.value.length) await select(tasks.value.find((row) => row.task.lifecycleStatus !== 'COMPLETED') || tasks.value[0])
}

onMounted(loadGraph)
onUnmounted(() => timer.value && clearInterval(timer.value))

async function select(row: any) {
  selected.value = row
  note.value = { title: '学习笔记', markdown: '', version: undefined }
  chatMessages.value = []
  chatDraft.value = ''
  learningBlock.value = undefined
  blockStep.value = 1
  blockTestAnswers.value = {}
  blockTestResult.value = undefined
  openExerciseAnswers.value = []
  selectedBlockSpaceIds.value = []
  if (row.learningBlock) await loadLearningBlock(row.task.publicId)
  try {
    const result = await api<any>({ url: `/tasks/${row.task.publicId}/note` })
    if (result?.note) note.value = { title: result.note.title, markdown: result.currentVersion?.contentMarkdown || '', version: result.note.version }
  } catch { /* empty note */ }
  try {
    const result = await api<any>({ url: `/tasks/${row.task.publicId}/chats` })
    chatMessages.value = result?.messages || []
    await scrollChat()
  } catch { /* empty chat */ }
}

async function loadLearningBlock(taskId = entity.value.publicId) {
  if (!taskId) return
  blockLoading.value = true
  try {
    learningBlock.value = await api<any>({ url: `/tasks/${taskId}/learning-block` })
    if (learningBlock.value?.sourceStatus !== 'READY' && !availableBlockSpaces.value.length) {
      await loadAvailableBlockSpaces()
    }
  } finally {
    blockLoading.value = false
  }
}

async function loadAvailableBlockSpaces() {
  const spaces = await api<any[]>({ url: '/knowledge-spaces' })
  const values = await Promise.all(spaces.map(async (space) => {
    const documents = await api<any[]>({ url: `/knowledge-spaces/${space.publicId}/documents` })
    return {
      ...space,
      indexedDocumentCount: documents.filter((document) => document.status === 'INDEXED').length,
    }
  }))
  availableBlockSpaces.value = values.filter((space) => space.indexedDocumentCount > 0)
}

async function attachBlockSources() {
  if (!selectedBlockSpaceIds.value.length) return
  blockSourcesLoading.value = true
  try {
    learningBlock.value = await api<any>({
      method: 'POST',
      url: `/tasks/${entity.value.publicId}/learning-block/sources`,
      data: { knowledgeSpaceIds: selectedBlockSpaceIds.value },
    })
    ElMessage.success('已把知识库资料关联到当前知识块，现在可以按来源生成内容')
  } finally {
    blockSourcesLoading.value = false
  }
}

async function generateLearningBlock() {
  blockGenerating.value = true
  try {
    learningBlock.value = await api<any>({
      method: 'POST',
      url: `/tasks/${entity.value.publicId}/learning-block/generation`,
    })
    for (let attempt = 0; learningBlock.value?.generationStatus === 'GENERATING' && attempt < 90; attempt++) {
      await new Promise((resolve) => window.setTimeout(resolve, 1000))
      learningBlock.value = await api<any>({
        url: `/tasks/${entity.value.publicId}/learning-block`,
      })
    }
    if (learningBlock.value?.generationStatus === 'FAILED') {
      throw new Error('知识块生成失败，请检查模型服务后重试')
    }
    if (learningBlock.value?.generationStatus !== 'GENERATED') {
      throw new Error('知识块仍在后台生成，请稍后刷新查看')
    }
    blockTestAnswers.value = {}
    ElMessage.success('当前知识块的资料、练习和块测已生成')
  } finally {
    blockGenerating.value = false
  }
}

async function submitBlockTest() {
  if (!learningBlock.value?.publicId || !allBlockQuestionsAnswered.value) return
  const result = await api<any>({
    method: 'POST',
    url: `/learning-blocks/${learningBlock.value.publicId}/test-attempts`,
    data: { answers: blockTestAnswers.value },
  })
  blockTestResult.value = result
  learningBlock.value = result.block
  if (result.passed) {
    ElMessage.success(result.goalReadyToComplete
      ? `块测 ${result.score} 分，目标内所有知识块均已通过`
      : `块测 ${result.score} 分，当前知识块已完成`)
    await load()
    await loadGraph()
  } else {
    ElMessage.warning(`本次 ${result.score} 分，达到 ${result.passScore} 分即可通过；复习后可再次测试`)
  }
}

function materialHtml(value: string) {
  return DOMPurify.sanitize(markdown.render(value || ''))
}

function toggleExercise(index: number) {
  openExerciseAnswers.value = openExerciseAnswers.value.includes(index)
    ? openExerciseAnswers.value.filter((item) => item !== index)
    : [...openExerciseAnswers.value, index]
}

function formatActualTime(seconds: number) {
  const value = Number(seconds || 0)
  if (value < 60) return `${value} 秒`
  const hours = Math.floor(value / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  return hours ? `${hours} 小时 ${minutes} 分钟` : `${minutes} 分钟`
}

async function scrollChat() {
  await nextTick()
  if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight
}

async function sendChat() {
  const text = chatDraft.value.trim()
  if (!text || chatLoading.value || !entity.value.publicId) return
  chatDraft.value = ''
  const history = chatMessages.value.slice(-400).map((item) => ({ role: item.role, content: item.content }))
  chatMessages.value.push({ role: 'USER', content: text })
  chatLoading.value = true
  await scrollChat()
  try {
    const result = await api<any>({ method: 'POST', url: `/tasks/${entity.value.publicId}/chats`, data: { message: text, history } })
    chatMessages.value.push({ role: 'ASSISTANT', content: result.answer, citations: result.citations || [], mode: result.mode })
  } catch {
    chatMessages.value.pop()
    chatDraft.value = text
  } finally {
    chatLoading.value = false
    await scrollChat()
  }
}

async function clearChat() {
  if (!entity.value.publicId || !chatMessages.value.length) return
  await ElMessageBox.confirm('清空后会结束当前辅导会话，但不会影响学习任务和笔记。', '清空辅导记录', { type: 'warning' })
  await api({ method: 'DELETE', url: `/tasks/${entity.value.publicId}/chats` })
  chatMessages.value = []
  ElMessage.success('辅导记录已清空')
}

async function jump(row: any) {
  date.value = dayjs(row.task.scheduledStart).format('YYYY-MM-DD')
  await load()
  const target = tasks.value.find((item) => item.task.publicId === row.task.publicId)
  if (target) await select(target)
}

function openReschedule() {
  rescheduleForm.value = {
    scheduledStart: entity.value.scheduledStart ? dayjs(entity.value.scheduledStart).format('YYYY-MM-DDTHH:mm:ss') : '',
    dueAt: entity.value.dueAt ? dayjs(entity.value.dueAt).format('YYYY-MM-DDTHH:mm:ss') : '',
    reason: '',
  }
  rescheduleVisible.value = true
}

async function proposeReschedule() {
  const value = rescheduleForm.value
  if (!value.scheduledStart || !value.dueAt || !value.reason.trim()) return ElMessage.warning('请填写新的起止时间和改期原因')
  if (!dayjs(value.dueAt).isAfter(dayjs(value.scheduledStart))) return ElMessage.warning('截止时间必须晚于开始时间')
  rescheduleSubmitting.value = true
  try {
    await api({ method:'POST', url:`/tasks/${entity.value.publicId}/rescheduling-proposals`, data:{
      scheduledStart:dayjs(value.scheduledStart).toISOString(), dueAt:dayjs(value.dueAt).toISOString(), reason:value.reason.trim(),
    } })
    rescheduleVisible.value = false
    ElMessage.success('改期提案已生成，需要你在计划页确认后才会生效')
    await router.push(`/plans/${selectedGraphNode.value?.goalId || ''}`)
  } finally { rescheduleSubmitting.value = false }
}

function runClock() {
  if (timer.value) clearInterval(timer.value)
  timer.value = window.setInterval(() => elapsed.value++, 1000)
}

async function start() {
  if (!selectedAvailableToday.value) return void ElMessage.warning('未来任务只能预览，请在排期当天开始')
  session.value = await api<any>({ method: 'POST', url: `/tasks/${entity.value.publicId}/start`, data: { startTimer: true } })
  elapsed.value = 0
  runClock()
  await load()
}
async function pause() {
  await api({ method: 'POST', url: `/study-sessions/${session.value.publicId}/pause` })
  session.value.status = 'PAUSED'
  if (timer.value) clearInterval(timer.value)
}
async function resume() {
  await api({ method: 'POST', url: `/study-sessions/${session.value.publicId}/resume` })
  session.value.status = 'RUNNING'
  runClock()
}
async function stop() {
  await api({ method: 'POST', url: `/study-sessions/${session.value.publicId}/stop` })
  session.value = undefined
  if (timer.value) clearInterval(timer.value)
  await load()
  ElMessage.success('这段专注时长已经记入学习记录')
}
async function saveNote() {
  const result = await api<any>({ method: 'PUT', url: `/tasks/${entity.value.publicId}/note`, data: note.value })
  note.value.version = result.note.version
  ElMessage.success('笔记已保存，并保留了历史版本')
}
async function complete() {
  const summary = await ElMessageBox.prompt('用一句话记录这次完成了什么', '完成这一小步', {
    inputValidator: (value) => Boolean(value) || '完成总结不能为空',
    confirmButtonText: '确认完成',
    cancelButtonText: '稍后再说',
  }).then((result) => result.value).catch(() => null)
  if (!summary) return
  await api({
    method: 'POST',
    url: `/tasks/${entity.value.publicId}/completion`,
    data: { summary: { learnedText: summary }, confirmed: true },
  })
  await load()
  if (selected.value?.learningBlock) await loadLearningBlock(entity.value.publicId)
  ElMessage.success(selected.value?.learningBlock ? '学习已记录，请完成块测验收当前知识块' : '任务已完成，做得好')
}

async function earlyEnd() {
  const note = await ElMessageBox.prompt('提前结束会停止计时并记录实际专注时长，任务将标记为「已提前结束」。', '提前结束这段任务？', {
    inputPlaceholder: '记一句原因（可选），会写入任务记录',
    confirmButtonText: '提前结束',
    cancelButtonText: '再想想',
    inputValidator: () => true,
  }).then((result) => result.value).catch(() => null)
  if (note === null || note === undefined) return
  const reason = String(note).trim() ? `提前结束：${String(note).trim()}` : '提前结束'
  await api({ method: 'POST', url: `/tasks/${entity.value.publicId}/cancellation`, data: { confirmed: true, reason } })
  session.value = undefined
  if (timer.value) clearInterval(timer.value)
  await load()
  ElMessage.success('已提前结束，实际时长与原因已记录')
}

function statusText(status: string) {
  return ({ PLANNED: '待开始', NOT_STARTED: '待开始', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELED: '已提前结束' } as Record<string, string>)[status] || status
}
</script>

<template>
  <div v-if="screen === 'graph'" class="task-graph-page">
    <section class="graph-hero">
      <div>
        <span class="eyebrow light">AI PLAN / TASK KNOWLEDGE GRAPH</span>
        <h1>你的任务知识图谱</h1>
        <p>这里展示 AI 计划发布后形成的完整学习路径。黑色节点是今天可以进入的任务，灰色节点是尚未到达的安排。</p>
      </div>
      <div class="graph-summary">
        <div><strong>{{ graph.nodes.length }}</strong><small>计划任务</small></div>
        <i />
        <div><strong>{{ todayNodes.length }}</strong><small>今天可执行</small></div>
        <i />
        <div><strong>{{ goalCount }}</strong><small>学习目标</small></div>
      </div>
    </section>

    <section v-loading="graphLoading" class="graph-stage">
      <div class="graph-toolbar">
        <div>
          <span class="eyebrow">LEARNING PATH</span>
          <h2>从计划到行动</h2>
          <p>{{ graph.today }} · {{ graph.timezone || '当前时区' }}</p>
        </div>
        <div class="graph-legend">
          <span><i class="today-dot" />今天，可点击</span>
          <span><i class="future-dot" />未来，仅预览</span>
          <small>拖动画布或滚轮缩放</small>
        </div>
      </div>

      <template v-if="graph.nodes.length">
        <VChart class="task-graph" :option="graphOption" autoresize @click="handleGraphClick" />
        <div v-if="todayNodes.length" class="today-entry-list">
          <div>
            <span class="eyebrow">READY TODAY</span>
            <h3>今天可以进入</h3>
          </div>
          <button v-for="node in todayNodes" :key="node.publicId" @click="openGraphTask(node)">
            <span>{{ node.scheduledStart ? dayjs(node.scheduledStart).format('HH:mm') : '今天' }}</span>
            <div><b>{{ node.title }}</b><small>{{ node.goalName }} · 建议投入 {{ node.estimatedMinutes }} 分钟</small></div>
            <i>进入任务 →</i>
          </button>
        </div>
        <div v-else class="graph-no-today">
          <span>○</span>
          <div><b>今天没有可执行任务</b><p>未来 {{ futureNodes.length }} 项安排仍保留在图谱中，到计划日期后节点会自动变为黑色并开放进入。</p></div>
        </div>
      </template>

      <div v-else-if="!graphLoading" class="graph-empty">
        <div class="empty-orbit"><span>序</span></div>
        <h2>还没有可展示的任务路径</h2>
        <p>先为目标生成并确认发布 AI 计划，正式任务和它们的前后关系才会进入图谱。</p>
        <el-button type="primary" @click="$router.push('/plans')">去生成 AI 计划</el-button>
      </div>
    </section>
  </div>

  <div v-else class="today-page">
    <section class="day-ribbon">
      <button class="graph-back" @click="backToGraph">← 返回任务图谱</button>
      <div class="day-intro">
        <span class="eyebrow light">DAILY RHYTHM / {{ dayjs(date).format('YYYY.MM.DD') }}</span>
        <h1>{{ currentDateLabel }}，只完成{{ currentDateLabel }}</h1>
        <p>不追赶整个计划，只进入眼前这一段专注。</p>
      </div>
      <div class="day-signals">
        <div><strong>{{ tasks.length }}</strong><small>段行动</small></div>
        <i />
        <div><strong>{{ plannedMinutes }}</strong><small>建议分钟</small></div>
        <i />
        <div><strong>{{ completedCount }}</strong><small>已经完成</small></div>
      </div>
      <div class="date-control">
        <span>切换日期</span>
        <el-date-picker v-model="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
      </div>
    </section>

    <div ref="layoutRef" class="day-layout" :class="{ 'queue-is-collapsed': queueCollapsed, dragging: leftDragging }" :style="{ '--left-w': leftWidth + 'px' }">
      <div class="day-left">
      <aside class="day-queue" :class="{ collapsed: queueCollapsed }">
        <template v-if="!queueCollapsed">
          <div class="queue-head">
            <div><span class="eyebrow">ITINERARY</span><h2>今日路径</h2></div>
            <div class="queue-head-side">
              <span>{{ completedCount }}/{{ tasks.length }}</span>
              <button class="queue-toggle" title="收起今日路径" @click="queueCollapsed = true">⟨</button>
            </div>
          </div>

          <div v-if="!tasks.length" class="queue-empty">
            <span>○</span><b>这一天暂时留白</b><p>没有排期不是错误，也可以把它留给休息和自由探索。</p>
          </div>

          <div v-else class="queue-timeline">
            <button v-for="(row, index) in tasks" :key="row.task.publicId" :class="{ active: entity.publicId === row.task.publicId, done: row.task.lifecycleStatus === 'COMPLETED', canceled: row.task.lifecycleStatus === 'CANCELED' }" @click="select(row)">
              <span class="queue-time">{{ row.task.scheduledStart ? dayjs(row.task.scheduledStart).format('HH:mm') : '--:--' }}</span>
              <i>{{ row.task.lifecycleStatus === 'COMPLETED' ? '✓' : index + 1 }}</i>
              <div><b>{{ row.task.title }}</b><small>建议 {{ row.task.estimatedMinutes }} 分钟 · {{ statusText(row.task.lifecycleStatus) }}</small></div>
            </button>
          </div>

          <div v-if="!tasks.length && upcoming.length" class="upcoming-block">
            <span class="eyebrow">NEXT ON YOUR PATH</span>
            <button v-for="row in upcoming" :key="row.task.publicId" @click="jump(row)">
              <span>{{ dayjs(row.task.scheduledStart).format('M.D') }}</span>
              <div><b>{{ row.task.title }}</b><small>{{ dayjs(row.task.scheduledStart).format('HH:mm') }} · 点击进入</small></div>
              <i>↗</i>
            </button>
          </div>
        </template>
        <button v-else class="queue-expand" title="展开今日路径" @click="queueCollapsed = false">
          <span>⟩</span><b>今日路径</b><i>{{ completedCount }}/{{ tasks.length }}</i>
        </button>
      </aside>

      <section class="task-chat">
        <div class="chat-head">
          <div><span class="eyebrow">DISCUSS WITH AGENT</span><h3>和 Agent 讨论{{ entity.title ? `「${entity.title}」` : '今天的学习' }}</h3></div>
          <div class="chat-head-actions"><small>对话会自动保存</small><el-button v-if="chatMessages.length" link @click="clearChat">清空记录</el-button></div>
        </div>
        <div ref="chatScroll" class="chat-messages">
          <div v-if="!chatMessages.length" class="chat-empty">先在右侧选择一段任务，就可以和我讨论概念、做法或相关资料。</div>
          <div v-for="(item, index) in chatMessages" :key="index" class="chat-msg" :class="item.role === 'USER' ? 'user' : 'assistant'">
            <div class="chat-bubble">{{ item.content }}</div>
            <div v-if="item.citations?.length" class="chat-cites">
              <span v-if="item.mode === 'KNOWLEDGE'" class="cite-source">来自你的知识库</span>
              <span v-else class="cite-source web">来自联网检索</span>
              <a v-for="cite in item.citations" :key="cite.citationId" :href="safeExternalUrl(cite.url)" :target="safeExternalUrl(cite.url) ? '_blank' : undefined" :rel="safeExternalUrl(cite.url) ? 'noopener' : undefined" :class="{ web: cite.sourceType === 'WEB' }" :title="cite.quotePreview">
                [{{ cite.citationId }}] {{ cite.sourceType === 'WEB' ? cite.title || cite.url : cite.fileName || '知识库资料' }}
              </a>
            </div>
          </div>
          <div v-if="chatLoading" class="chat-msg assistant"><div class="chat-bubble typing">正在思考…</div></div>
        </div>
        <div class="chat-input">
          <el-input v-model="chatDraft" type="textarea" :rows="3" resize="none" :placeholder="entity.publicId ? '围绕当前任务提问，Enter 发送，Shift+Enter 换行' : '先在右侧选择一段任务再提问'" :disabled="!entity.publicId" @keydown.enter.exact.prevent="sendChat" />
          <el-button type="primary" :loading="chatLoading" :disabled="!entity.publicId || !chatDraft.trim()" @click="sendChat">发送</el-button>
        </div>
      </section>

      <div class="workspace-notebook">
        <div class="nb-panel" :class="{ open: nbOpen === 'note' }">
          <button class="nb-head" @click="togglePanel('note')">
            <i class="nb-icon">✎</i>
            <span class="nb-title">学习笔记</span>
            <span v-if="note.version" class="nb-badge">V{{ note.version }}</span>
            <span class="nb-arrow">▾</span>
          </button>
          <div v-show="nbOpen === 'note'" class="nb-body">
            <div class="note-editor">
              <el-input v-model="note.title" placeholder="给这段笔记一个标题" />
              <el-input v-model="note.markdown" type="textarea" :rows="5" placeholder="写下关键想法、疑问或下一步。支持 Markdown，保存时会生成可追溯版本。" />
              <div class="note-save">
                <span>VERSION {{ note.version || 'NEW' }}</span>
                <el-button type="primary" @click="saveNote">保存这一版</el-button>
              </div>
            </div>
          </div>
        </div>
        <div class="nb-panel" :class="{ open: nbOpen === 'clues' }">
          <button class="nb-head" @click="togglePanel('clues')">
            <i class="nb-icon">◈</i>
            <span class="nb-title">任务线索</span>
            <span class="nb-arrow">▾</span>
          </button>
          <div v-show="nbOpen === 'clues'" class="nb-body">
            <div class="task-clues">
              <div><span>计划开始</span><b>{{ entity.scheduledStart || '待安排' }}</b></div>
              <div><span>截止时间</span><b>{{ entity.dueAt || '未设置' }}</b></div>
              <div><span>建议投入</span><b>{{ entity.estimatedMinutes }} 分钟（非最低要求）</b></div>
              <div v-if="learningBlock"><span>实际投入</span><b>{{ formatActualTime(learningBlock.effectiveSeconds) }}</b></div>
              <div><span>前置任务</span><b>{{ selected?.prerequisites?.length ? selected.prerequisites.map((item:any) => `${item.title}（${item.status}）`).join('、') : '无，可直接开始' }}</b></div>
              <div><span>任务来源</span><b>{{ entity.source || '个人计划' }}</b></div>
              <div v-if="selected?.knowledgeSources?.length" class="source-clue">
                <span>计划指定资料</span>
                <b v-for="source in selected.knowledgeSources" :key="source.chunkId">
                  {{ source.documentName }} · 片段 {{ source.chunkNo }}<template v-if="source.pageFrom"> · 第 {{ source.pageFrom }} 页</template>
                </b>
              </div>
            </div>
          </div>
        </div>
      </div>
      </div>

      <div class="day-resizer" title="拖动调整左栏宽度" role="separator" aria-orientation="vertical" @pointerdown="startResize"></div>

      <main class="focus-room" :class="{ waiting: !selected }">
        <div v-if="!selected" class="focus-waiting">
          <div class="waiting-orbit"><span>序</span></div>
          <span class="eyebrow light">FOCUS SPACE</span>
          <h2>{{ tasks.length ? '选择一段任务，进入专注' : '今天没有必须赶赴的任务' }}</h2>
          <p>{{ tasks.length ? '路径已经准备好，选择左侧任意一小步即可开始。' : '看看未来的安排，或者让 Agent 为目标生成一段新节奏。' }}</p>
          <el-button v-if="!tasks.length" type="primary" @click="$router.push('/plans')">去规划下一步</el-button>
        </div>

        <template v-else>
          <div class="focus-header">
            <div>
              <span class="focus-type">{{ entity.taskType || 'FOCUS SESSION' }}</span>
              <h2>{{ entity.title }}</h2>
              <p>{{ entity.description || '把注意力放在这一件事上。' }}</p>
            </div>
            <el-button v-if="!['COMPLETED','CANCELED'].includes(entity.lifecycleStatus)" plain @click="openReschedule">提出改期</el-button>
          </div>

          <div class="focus-side">
            <div v-if="session && entity.lifecycleStatus !== 'COMPLETED'" class="mini-timer" :class="{ running }">
              <i />
              <span class="mini-time">{{ formatTime }}</span>
              <small>{{ session?.status === 'PAUSED' ? '已暂停' : '专注中' }}</small>
              <el-button v-if="running" size="small" @click="pause">暂停</el-button>
              <el-button v-else size="small" type="primary" @click="resume">继续</el-button>
              <button class="mini-stop" title="结束并记录这段专注" @click="stop">结束</button>
            </div>
            <template v-if="entity.lifecycleStatus !== 'COMPLETED' && entity.lifecycleStatus !== 'CANCELED'">
              <button class="early-end-action" :disabled="!selectedAvailableToday" @click="earlyEnd">提前结束</button>
              <button class="complete-action" :disabled="!selectedAvailableToday || (Boolean(learningBlock) && !blockGenerated)"
                :title="learningBlock && !blockGenerated ? '请先生成当前知识块资料、练习和块测' : ''" @click="complete">
                <span>✓</span>{{ learningBlock && !blockGenerated ? '先生成知识块' : '完成学习，进入块测' }}
              </button>
            </template>
            <span v-else-if="entity.lifecycleStatus === 'COMPLETED' && learningBlock && !blockPassed" class="complete-state">
              ○ 学习完成 · 待块测
              <small v-if="lastStudyText">上次学习 {{ lastStudyText }}</small>
            </span>
            <span v-else-if="entity.lifecycleStatus === 'COMPLETED'" class="complete-state">
              ✓ 已完成
              <small v-if="lastStudyText">上次学习 {{ lastStudyText }}</small>
            </span>
            <span v-else class="ended-state">已提前结束</span>
          </div>

          <section v-if="!session && entity.lifecycleStatus !== 'CANCELED' && entity.lifecycleStatus !== 'COMPLETED'" class="focus-console">
            <div class="focus-dial">
              <div><span>{{ formatTime }}</span><small>准备好再开始</small></div>
            </div>
            <div class="focus-controls">
              <el-button type="primary" size="large" :disabled="!selectedAvailableToday" @click="start">{{ selectedAvailableToday ? '开始专注' : '仅可预览' }}</el-button>
            </div>
            <div class="focus-meta"><span>建议投入 {{ entity.estimatedMinutes }} 分钟，不要求学满</span><i /><span>{{ entity.priority || 'MEDIUM' }} PRIORITY</span></div>
          </section>

          <section v-if="learningBlock" v-loading="blockLoading" class="learning-block">
            <div class="block-head">
              <div>
                <span class="eyebrow">KNOWLEDGE BLOCK {{ learningBlock.sequenceNo }}</span>
                <h3>{{ learningBlock.title }}</h3>
                <p>{{ learningBlock.objective }}</p>
              </div>
              <div class="block-status">
                <span :class="learningBlock.sourceStatus === 'READY' ? 'ready' : 'needs-source'">
                  {{ learningBlock.sourceStatus === 'READY' ? '资料已就绪' : '资料待补充' }}
                </span>
                <span :class="{ passed: blockPassed }">{{ blockPassed ? '块测已通过' : blockGenerated ? '内容已生成' : '待生成' }}</span>
              </div>
            </div>

            <div v-if="learningBlock.explorationRequired" class="exploration-notice">
              <b>这是自定义方向，先完成探索与资料核验</b>
              <p>Agent 会给出检索词和按块内容。当前没有可信来源时，生成内容会标为“AI 生成待核验”，不会伪造资料出处。</p>
              <div v-if="learningBlock.sourceQueries?.length" class="query-chips">
                <span v-for="query in learningBlock.sourceQueries" :key="query">{{ query }}</span>
              </div>
              <el-button plain @click="router.push('/knowledge')">去知识库上传资料</el-button>
              <div v-if="learningBlock.sourceStatus !== 'READY'" class="attach-sources">
                <el-select v-model="selectedBlockSpaceIds" multiple collapse-tags class="full"
                  placeholder="上传并索引完成后，在这里选择知识空间">
                  <el-option v-for="space in availableBlockSpaces" :key="space.publicId" :value="space.publicId"
                    :label="`${space.name} · ${space.indexedDocumentCount} 篇已索引`" />
                </el-select>
                <el-button type="primary" :loading="blockSourcesLoading" :disabled="!selectedBlockSpaceIds.length" @click="attachBlockSources">
                  关联到当前知识块
                </el-button>
                <el-button link @click="loadAvailableBlockSpaces">刷新资料列表</el-button>
              </div>
            </div>

            <div v-if="learningBlock.sources?.length" class="block-sources">
              <span>本块资料来源</span>
              <div>
                <article v-for="(source, index) in learningBlock.sources" :key="`${source.sourceType}-${index}`">
                  <b>[S{{ index + 1 }}] {{ source.title }}</b>
                  <small>{{ source.sourceType }}</small>
                  <p v-if="source.quotePreview">{{ source.quotePreview }}</p>
                  <a v-if="safeExternalUrl(source.url)" :href="safeExternalUrl(source.url)" target="_blank" rel="noopener">查看原始来源 ↗</a>
                </article>
              </div>
            </div>

            <div v-if="!blockGenerated" class="block-generation">
              <p>只生成当前这一块的资料、练习和测试，不会把整条学习路线一次塞入上下文。</p>
              <el-button type="primary" :loading="blockGenerating" @click="generateLearningBlock">
                {{ learningBlock.sourceStatus === 'READY' ? '按当前来源生成本块' : '先生成待核验版本' }}
              </el-button>
            </div>

            <template v-else>
              <div class="block-stepper">
                <button :class="{ current: blockStep === 1, done: blockStep > 1 }" @click="blockStep = 1">01 学习资料</button>
                <i />
                <button :class="{ current: blockStep === 2, done: blockStep > 2 }" :disabled="blockStep < 2" @click="blockStep = 2">02 本块练习</button>
                <i />
                <button :class="{ current: blockStep === 3, done: blockStep > 3 }" :disabled="blockStep < 3" @click="blockStep = 3">03 知识块测试</button>
              </div>

              <article v-show="blockStep === 1" class="block-material">
                <div class="block-section-title"><span>01</span><div><b>分块学习资料</b><small>只覆盖当前知识块</small></div></div>
                <div class="block-markdown" v-html="materialHtml(learningBlock.materialMarkdown)" />
                <div class="block-next">
                  <el-button type="primary" @click="blockStep = 2">看完资料，进入本块练习 →</el-button>
                </div>
              </article>

              <article v-show="blockStep === 2" class="block-exercises">
                <div class="block-section-title"><span>02</span><div><b>本块练习</b><small>练习答案可按需展开</small></div></div>
                <div class="exercise-list">
                  <div v-for="(exercise, index) in learningBlock.exercises" :key="index">
                    <span>练习 {{ index + 1 }}</span>
                    <p>{{ exercise.prompt }}</p>
                    <button @click="toggleExercise(index)">
                      {{ openExerciseAnswers.includes(index) ? '收起参考答案' : '查看参考答案' }}
                    </button>
                    <div v-if="openExerciseAnswers.includes(index)" class="exercise-answer">
                      <b>{{ exercise.answer }}</b><p>{{ exercise.explanation }}</p>
                    </div>
                  </div>
                </div>
                <div class="block-next">
                  <el-button plain @click="blockStep = 1">← 返回资料</el-button>
                  <el-button type="primary" @click="blockStep = 3">完成练习，进入知识块测试 →</el-button>
                </div>
              </article>

              <article v-show="blockStep === 3" class="block-test">
                <div class="block-section-title"><span>03</span><div><b>知识块测试</b><small>完成学习后作答，达到 {{ learningBlock.passScore }} 分即可完成当前块</small></div></div>
                <div v-if="entity.lifecycleStatus !== 'COMPLETED'" class="test-locked">
                  先完成当前学习任务即可开始测试；无需学满建议时长，系统只记录实际投入。
                </div>
                <template v-else-if="!blockPassed">
                  <div v-for="(question, index) in learningBlock.testQuestions" :key="question.id" class="test-question">
                    <b>{{ index + 1 }}. {{ question.stem }}</b>
                    <el-radio-group v-model="blockTestAnswers[question.id]">
                      <el-radio v-for="option in question.options" :key="option" :value="option">{{ option }}</el-radio>
                    </el-radio-group>
                    <div v-if="blockTestResult?.feedback?.[index]" :class="['test-feedback', blockTestResult.feedback[index].correct ? 'correct' : 'wrong']">
                      {{ blockTestResult.feedback[index].correct ? '回答正确' : `正确答案：${blockTestResult.feedback[index].expectedAnswer}` }}
                      <p>{{ blockTestResult.feedback[index].analysis }}</p>
                    </div>
                  </div>
                  <el-button type="primary" :disabled="!allBlockQuestionsAnswered" @click="submitBlockTest">提交块测</el-button>
                </template>
                <div v-else class="test-passed">
                  <b>✓ 当前知识块已经通过</b>
                  <p>得分 {{ learningBlock.latestScore }}，实际学习 {{ formatActualTime(learningBlock.effectiveSeconds) }}。后继知识块现在可以开始。</p>
                  <div class="review-questions">
                    <div v-for="(question, index) in learningBlock.testQuestions" :key="question.id" class="test-question review">
                      <b>{{ index + 1 }}. {{ question.stem }}</b>
                      <div v-for="option in question.options" :key="option" class="review-option" :class="{ correct: option === question.answer }">
                        <span class="mark">{{ option === question.answer ? '✓' : '·' }}</span>{{ option }}
                      </div>
                      <p class="review-analysis"><b>答案解析：</b>{{ question.analysis }}</p>
                    </div>
                  </div>
                </div>
                <div class="block-next">
                  <el-button plain @click="blockStep = 2">← 返回练习</el-button>
                </div>
              </article>
            </template>
          </section>
        </template>
      </main>
    </div>
    <el-dialog v-model="rescheduleVisible" title="提出任务改期" width="520px">
      <el-form label-position="top">
        <el-form-item label="新的开始时间"><el-date-picker v-model="rescheduleForm.scheduledStart" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full"/></el-form-item>
        <el-form-item label="新的截止时间"><el-date-picker v-model="rescheduleForm.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" class="full"/></el-form-item>
        <el-form-item label="改期原因"><el-input v-model="rescheduleForm.reason" type="textarea" :rows="3" maxlength="1000" show-word-limit/></el-form-item>
      </el-form>
      <template #footer><el-button @click="rescheduleVisible=false">取消</el-button><el-button type="primary" :loading="rescheduleSubmitting" @click="proposeReschedule">生成待确认提案</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.task-graph-page { display: grid; gap: 22px; }
.graph-hero { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 30px; overflow: hidden; min-height: 128px; padding: 22px clamp(26px, 4vw, 44px); border-radius: 28px; color: #f3f5ed; background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%); box-shadow: 0 22px 60px rgba(20, 59, 45, .2); }
.graph-hero::after { position: absolute; right: -70px; bottom: -150px; width: 320px; height: 320px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 42px rgba(255, 255, 255, .022), 0 0 0 84px rgba(255, 255, 255, .015); content: ""; }
.graph-hero > div { position: relative; z-index: 1; }
.graph-hero h1 { margin: 8px 0 6px; font: 500 clamp(23px, 2.6vw, 30px)/1.2 var(--display); letter-spacing: -.02em; }
.graph-hero p { max-width: 720px; margin: 0; color: #bfd0c8; font-size: 11px; line-height: 1.7; }
.graph-summary { display: flex; align-items: center; gap: 20px; }
.graph-summary > div { min-width: 60px; text-align: center; }
.graph-summary strong, .graph-summary small { display: block; }
.graph-summary strong { font: 500 24px var(--display); }
.graph-summary small { margin-top: 3px; color: #a8c0b4; font-size: 8px; }
.graph-summary > i { width: 1px; height: 32px; background: rgba(255, 255, 255, .11); }
.graph-stage { min-height: 690px; overflow: hidden; border: 1px solid rgba(255, 255, 255, .78); border-radius: 30px; background: radial-gradient(circle at 15% 5%, rgba(225, 235, 226, .72), transparent 26%), var(--card); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .04); backdrop-filter: blur(16px); }
.graph-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 30px; padding: 29px 34px 10px; }
.graph-toolbar h2 { margin: 7px 0 3px; font: 500 27px var(--display); }
.graph-toolbar p { margin: 0; color: var(--muted); font-size: 9px; }
.graph-legend { display: flex; align-items: center; gap: 16px; color: var(--muted); font-size: 9px; }
.graph-legend > span { display: flex; align-items: center; gap: 7px; }
.graph-legend i { width: 13px; height: 13px; border-radius: 50%; }
.graph-legend .today-dot { border: 3px solid #d8b66b; background: #151a17; }
.graph-legend .future-dot { border: 1px solid #c7cbc6; background: #e4e6e3; }
.graph-legend small { padding-left: 15px; border-left: 1px solid var(--line); color: var(--muted); font-size: 8px; }
.task-graph { width: 100%; height: 510px; cursor: grab; }
.task-graph:active { cursor: grabbing; }
.today-entry-list { display: grid; grid-template-columns: 180px repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin: 0 30px 30px; padding-top: 20px; border-top: 1px solid rgba(31, 88, 64, .1); }
.today-entry-list > div { padding: 8px 8px; }
.today-entry-list h3 { margin: 7px 0 0; font: 500 20px var(--display); }
.today-entry-list > button { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px; padding: 14px 16px; border: 1px solid rgba(20, 27, 23, .1); border-radius: 16px; color: #f5f5f1; background: #171e1a; text-align: left; box-shadow: 0 12px 28px rgba(18, 27, 22, .12); transition: .22s ease; }
.today-entry-list > button:hover { border-color: #d8b66b; transform: translateY(-2px); }
.today-entry-list > button > span { color: #ddbd76; font: 600 10px ui-monospace, monospace; }
.today-entry-list b, .today-entry-list small { display: block; }
.today-entry-list b { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.today-entry-list small { margin-top: 4px; color: #aeb9b3; font-size: 8px; }
.today-entry-list > button > i { color: #d8b66b; font-size: 8px; font-style: normal; white-space: nowrap; }
.graph-no-today { display: flex; align-items: center; gap: 14px; margin: 0 30px 30px; padding: 19px 22px; border: 1px solid rgba(31, 88, 64, .08); border-radius: 18px; background: var(--el-fill-color-light); }
.graph-no-today > span { display: grid; place-items: center; width: 38px; height: 38px; flex: none; border-radius: 50%; color: var(--muted); background: var(--chip); }
.graph-no-today b { display: block; font: 500 16px var(--display); }
.graph-no-today p { margin: 4px 0 0; color: var(--muted); font-size: 9px; line-height: 1.6; }
.graph-empty { display: grid; justify-items: center; min-height: 580px; align-content: center; padding: 40px; text-align: center; }
.empty-orbit { display: grid; place-items: center; width: 104px; height: 104px; margin-bottom: 24px; border: 1px solid rgba(31, 88, 64, .1); border-radius: 50%; box-shadow: 0 0 0 19px rgba(31, 88, 64, .025), 0 0 0 38px rgba(31, 88, 64, .016); }
.empty-orbit span { display: grid; place-items: center; width: 56px; height: 56px; border-radius: 19px 19px 19px 6px; color: #f7f3e8; background: linear-gradient(145deg, #173f32, #102e25); font: 600 20px var(--display); }
.graph-empty h2 { margin: 0 0 7px; font: 500 29px var(--display); }
.graph-empty p { max-width: 530px; margin: 0 0 23px; color: var(--muted); font-size: 10px; line-height: 1.75; }
.today-page { display: grid; gap: 22px; }
.day-ribbon { position: relative; display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 28px; overflow: hidden; min-height: 128px; padding: 22px clamp(26px, 4vw, 44px); border-radius: 28px; color: #f3f5ed; background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%); box-shadow: 0 22px 60px rgba(20, 59, 45, .2); }
.day-ribbon::after { position: absolute; right: -70px; bottom: -150px; width: 320px; height: 320px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 42px rgba(255, 255, 255, .022), 0 0 0 84px rgba(255, 255, 255, .015); content: ""; }
.graph-back { position: absolute; z-index: 3; top: 7px; left: 30px; padding: 4px 0; border: 0; color: #a8c0b4; background: transparent; font-size: 9px; transition: .2s; }
.graph-back:hover { color: #f0d28d; }
.day-intro { position: relative; z-index: 1; }
.day-intro h1 { margin: 7px 0 5px; font: 500 clamp(23px, 2.6vw, 30px)/1.2 var(--display); letter-spacing: -.02em; }
.day-intro p { margin: 0; color: #bfd0c8; font-size: 10px; }
.day-signals { position: relative; z-index: 1; display: flex; align-items: center; gap: 16px; }
.day-signals > div { text-align: center; }
.day-signals strong, .day-signals small { display: block; }
.day-signals strong { font: 500 24px var(--display); }
.day-signals small { margin-top: 3px; color: #a8c0b4; font-size: 8px; }
.day-signals > i { width: 1px; height: 32px; background: rgba(255, 255, 255, .11); }
.date-control { position: relative; z-index: 1; width: 150px; }
.date-control > span { display: block; margin-bottom: 6px; color: #91aa9e; font-size: 8px; font-weight: 700; letter-spacing: .1em; }
.date-control :deep(.el-input__wrapper) { background: rgba(255, 255, 255, .09); box-shadow: 0 0 0 1px rgba(255, 255, 255, .13) inset !important; }
.date-control :deep(.el-date-editor.el-input) { width: 100%; }
.date-control :deep(.el-input__inner), .date-control :deep(.el-input__prefix) { color: #edf4ee; }

.day-layout { position: relative; display: grid; grid-template-columns: var(--left-w, 350px) 0 minmax(0, 1fr); gap: 0 10px; transition: grid-template-columns .25s ease; }
.day-layout.dragging { transition: none; }
.day-layout.queue-is-collapsed { grid-template-columns: 54px 0 minmax(0, 1fr); }
.day-resizer {
  grid-column: 2; grid-row: 1 / 4; position: sticky; top: 118px;
  height: calc(100vh - 142px); z-index: 7;
  cursor: col-resize; touch-action: none;
}
.day-resizer::after {
  content: ''; position: absolute; top: 0; bottom: 0; left: -6px; width: 12px;
  border-radius: 6px; background: rgba(31, 88, 64, .12); transition: background .18s ease;
}
.day-resizer:hover::after, .day-layout.dragging .day-resizer::after { background: rgba(74, 168, 131, .4); }
.queue-is-collapsed .day-resizer { display: none; }
.day-left { position: sticky; top: 118px; grid-column: 1; grid-row: 1 / 4; display: grid; align-content: start; align-self: start; min-width: 0; max-height: calc(100vh - 142px); overflow-y: auto; overflow-x: hidden; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: var(--paper-soft); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.day-queue { padding: 29px 25px; }
.day-queue.collapsed { display: grid; justify-items: center; padding: 14px 8px; }
.queue-is-collapsed .task-chat, .queue-is-collapsed .workspace-notebook { display: none; }
.queue-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
.queue-head h2 { margin: 6px 0 0; font: 500 24px var(--display); }
.queue-head-side { display: flex; align-items: center; gap: 8px; }
.queue-head-side > span { display: grid; place-items: center; width: 37px; height: 37px; border-radius: 50%; color: var(--green); background: var(--mint); font: 600 9px ui-monospace, monospace; }
.queue-toggle { display: grid; place-items: center; width: 24px; height: 24px; border: 1px solid rgba(31, 88, 64, .12); border-radius: 50%; color: var(--muted); background: transparent; font-size: 11px; transition: .2s; }
.queue-toggle:hover { color: var(--green); border-color: rgba(31, 88, 64, .3); background: var(--mint); }
.queue-expand { display: grid; justify-items: center; gap: 10px; padding: 6px 2px; border: 0; color: var(--green); background: transparent; }
.queue-expand > span { font-size: 12px; }
.queue-expand > b { color: var(--green); font-size: 10px; font-weight: 700; letter-spacing: .25em; writing-mode: vertical-rl; }
.queue-expand > i { display: grid; place-items: center; min-width: 30px; height: 30px; padding: 0 4px; border-radius: 99px; color: var(--green); background: var(--mint); font: 600 8px ui-monospace, monospace; font-style: normal; }
.queue-timeline { position: relative; }
.queue-timeline::before { position: absolute; top: 28px; bottom: 28px; left: 49px; width: 1px; background: rgba(31, 88, 64, .13); content: ""; }
.queue-timeline button { position: relative; z-index: 1; display: grid; grid-template-columns: 35px 31px 1fr; align-items: center; gap: 8px; width: 100%; padding: 13px 9px; border: 0; border-radius: 14px; color: var(--ink); background: transparent; text-align: left; transition: .2s; }
.queue-timeline button:hover { background: var(--el-fill-color-light); }
.queue-timeline button.active { background: var(--chip); box-shadow: inset 3px 0 0 var(--green); }
.queue-time { color: var(--muted); font: 500 9px ui-monospace, monospace; }
.queue-timeline button > i { display: grid; place-items: center; width: 25px; height: 25px; border: 4px solid var(--paper-solid); border-radius: 50%; color: var(--green); background: var(--chip); font-size: 8px; font-style: normal; font-weight: 700; box-shadow: 0 0 0 1px rgba(31, 88, 64, .1); }
.queue-timeline button.done > i { color: #fff; background: var(--green); }
.queue-timeline b, .queue-timeline small { display: block; }
.queue-timeline b { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.queue-timeline small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.queue-empty { padding: 30px 7px 22px; text-align: center; }
.queue-empty > span { display: grid; place-items: center; width: 44px; height: 44px; margin: 0 auto 12px; border-radius: 50%; color: var(--muted); background: var(--chip); }
.queue-empty b { display: block; font: 500 17px var(--display); }
.queue-empty p { margin: 7px 0 0; color: var(--muted); font-size: 9px; line-height: 1.7; }
.upcoming-block { margin-top: 19px; padding-top: 18px; border-top: 1px solid var(--line); }
.upcoming-block > button { display: grid; grid-template-columns: 36px 1fr auto; align-items: center; gap: 9px; width: 100%; padding: 12px 6px; border: 0; color: var(--ink); background: transparent; text-align: left; }
.upcoming-block > button > span { color: var(--gold); font: 600 11px var(--display); }
.upcoming-block b, .upcoming-block small { display: block; }
.upcoming-block b { overflow: hidden; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.upcoming-block small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.upcoming-block i { color: var(--green); font-style: normal; }

.focus-room { grid-column: 3; grid-row: 1 / 3; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: 16px 20px; min-height: 720px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: var(--paper-soft); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.focus-room > :not(.focus-side, .focus-header) { grid-column: 1 / -1; }
.focus-room.waiting { display: grid; place-items: center; grid-template-columns: 1fr; color: #edf4ee; background: radial-gradient(circle at 70% 25%, rgba(116, 174, 144, .25), transparent 28%), linear-gradient(145deg, #173f32, #102d25); }
.focus-waiting { max-width: 520px; padding: 50px; text-align: center; }
.waiting-orbit { display: grid; place-items: center; width: 108px; height: 108px; margin: 0 auto 25px; border: 1px solid rgba(255, 255, 255, .1); border-radius: 50%; box-shadow: 0 0 0 20px rgba(255, 255, 255, .025), 0 0 0 40px rgba(255, 255, 255, .016); }
.waiting-orbit span { display: grid; place-items: center; width: 58px; height: 58px; border-radius: 20px 20px 20px 6px; color: #15372c; background: linear-gradient(145deg, #efd08a, #d0a050); font: 600 20px var(--display); }
.focus-waiting h2 { margin: 12px 0 7px; font: 500 31px var(--display); }
.focus-waiting p { margin: 0 0 25px; color: #a9c0b4; font-size: 11px; line-height: 1.7; }
.focus-header { grid-column: 1; padding: 30px 0 22px 34px; }
.focus-type { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .14em; }
.focus-header h2 { margin: 8px 0 5px; font: 500 29px var(--display); }
.focus-header p { max-width: 680px; margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.complete-action { display: flex; align-items: center; gap: 8px; flex: none; padding: 7px 12px 7px 7px; border: 1px solid rgba(33, 102, 73, .13); border-radius: 99px; color: var(--green); background: var(--chip); font-size: 9px; font-weight: 700; }
.complete-action span { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; color: #fff; background: var(--green); }
.complete-action:disabled { cursor: not-allowed; opacity: .5; }
.complete-state { padding: 8px 12px; border-radius: 99px; color: var(--green); background: var(--mint); font-size: 9px; font-weight: 700; }
.complete-state small { display: block; margin-top: 3px; color: var(--muted); font-size: 8px; font-weight: 400; }
.early-end-action { padding: 7px 12px; border: 1px solid rgba(140, 151, 144, .3); border-radius: 99px; color: var(--muted); background: transparent; font-size: 9px; font-weight: 700; transition: .2s; }
.early-end-action:hover { color: #8a6420; border-color: rgba(176, 137, 62, .4); background: var(--seal); }
.ended-state { padding: 8px 12px; border-radius: 99px; color: #8a6420; background: var(--seal); font-size: 9px; font-weight: 700; }
.queue-timeline button.canceled b { color: var(--muted); text-decoration: line-through; }
.queue-timeline button.canceled > i { color: var(--muted); background: #eceeeb; }
.focus-side { grid-column: 2; grid-row: 1 / -1; position: sticky; top: 118px; z-index: 5; display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 20px; background: transparent; box-shadow: none; backdrop-filter: none; }
.mini-timer { display: flex; align-items: center; gap: 10px; padding: 8px 12px 8px 14px; border-radius: 99px; color: #eef4ef; background: radial-gradient(circle at 30% 20%, rgba(92, 150, 120, .3), transparent 60%), linear-gradient(145deg, #173f32, #102e25); box-shadow: 0 12px 28px rgba(16, 46, 37, .24); animation: mini-in .35s ease; }
.mini-timer > i { width: 8px; height: 8px; flex: none; border-radius: 50%; background: #7e978c; }
.mini-timer.running > i { background: #e2bd73; animation: mini-pulse 1.6s ease-in-out infinite; }
.mini-time { font: 500 20px ui-monospace, monospace; letter-spacing: .04em; }
.mini-timer small { color: #a2baae; font-size: 9px; letter-spacing: .06em; }
.mini-stop { padding: 5px 6px; border: 0; color: #a8bdb3; background: transparent; font-size: 9px; transition: .2s; }
.mini-stop:hover { color: #eef4ef; }
@keyframes mini-pulse { 50% { box-shadow: 0 0 0 6px rgba(226, 189, 115, .16); } }
@keyframes mini-in { from { opacity: 0; transform: translateY(-6px) scale(.94); } }
.focus-console { display: grid; justify-items: center; margin: 0 30px; padding: 34px; border-radius: 24px; color: #eef4ef; background: radial-gradient(circle at 50% 45%, rgba(92, 150, 120, .25), transparent 30%), linear-gradient(145deg, #173f32, #102e25); }
.focus-dial { display: grid; place-items: center; width: 210px; height: 210px; border: 1px solid rgba(233, 199, 125, .3); border-radius: 50%; box-shadow: 0 0 0 12px rgba(255, 255, 255, .025), 0 0 0 24px rgba(255, 255, 255, .015), inset 0 0 50px rgba(6, 23, 17, .18); }
.focus-dial.running { animation: focus-pulse 3s ease-in-out infinite; }
.focus-dial span, .focus-dial small { display: block; text-align: center; }
.focus-dial span { font: 400 48px ui-monospace, monospace; letter-spacing: .05em; }
.focus-dial small { margin-top: 7px; color: #a2baae; font-size: 9px; letter-spacing: .08em; }
.focus-controls { display: flex; align-items: center; gap: 11px; margin-top: 28px; }
.focus-console :deep(.el-button--primary) { border-color: #e2bd73; color: #17382d; background: linear-gradient(145deg, #edcf8b, #d1a252); }
.stop-session { padding: 9px 7px; border: 0; color: #a8bdb3; background: transparent; font-size: 9px; }
.focus-meta { display: flex; align-items: center; gap: 10px; margin-top: 17px; color: #809e90; font-size: 8px; }
.focus-meta i { width: 3px; height: 3px; border-radius: 50%; background: #d8b76f; }
.learning-block { display: grid; gap: 22px; margin: 20px 30px 0; padding: 25px; border: 1px solid rgba(31, 88, 64, .11); border-radius: 24px; background: var(--paper); }
.block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.block-head h3 { margin: 7px 0 5px; font: 500 23px var(--display); }
.block-head p { max-width: 720px; margin: 0; color: var(--muted); font-size: 10px; line-height: 1.7; }
.block-status { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.block-status span { padding: 6px 9px; border-radius: 99px; color: #7b6a44; background: var(--seal); font-size: 8px; white-space: nowrap; }
.block-status .ready, .block-status .passed { color: var(--green); background: var(--mint); }
.block-status .needs-source { color: #9b4e47; background: #f6e5e2; }
.exploration-notice { padding: 18px; border: 1px solid rgba(176, 137, 62, .2); border-radius: 17px; background: var(--seal); }
.exploration-notice b { font: 500 17px var(--display); }
.exploration-notice p { margin: 7px 0 12px; color: #776b55; font-size: 10px; line-height: 1.7; }
.query-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.query-chips span { padding: 5px 8px; border-radius: 99px; color: #7b602e; background: rgba(255, 255, 255, .7); font-size: 8px; }
.attach-sources { display: grid; grid-template-columns: minmax(220px, 1fr) auto auto; align-items: center; gap: 8px; margin-top: 13px; padding-top: 13px; border-top: 1px solid rgba(126, 96, 43, .13); }
.block-sources > span { display: block; margin-bottom: 9px; color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .1em; }
.block-sources > div { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 8px; }
.block-sources article { padding: 13px; border: 1px solid rgba(31, 88, 64, .08); border-radius: 13px; background: var(--paper-solid); }
.block-sources b, .block-sources small { display: block; }
.block-sources b { font-size: 9px; }
.block-sources small { margin-top: 4px; color: var(--muted); font-size: 7px; }
.block-sources p { margin: 8px 0; color: var(--muted); font-size: 8px; line-height: 1.6; }
.block-sources a { color: var(--green); font-size: 8px; text-decoration: none; }
.block-generation { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 18px; border-radius: 16px; color: #d8e7de; background: linear-gradient(145deg, #173f32, #102e25); }
.block-generation p { max-width: 620px; margin: 0; color: #aac0b5; font-size: 10px; line-height: 1.7; }
.block-stepper { display: flex; align-items: center; gap: 8px; margin-bottom: 18px; padding: 10px 14px; border-radius: 14px; background: var(--el-fill-color-light); }
.block-stepper button { padding: 6px 14px; border: 0; border-radius: 99px; color: var(--muted); background: transparent; font-size: 9px; cursor: pointer; transition: all .2s; }
.block-stepper button.current { color: #f4f5f1; background: linear-gradient(145deg, #173f32, #102e25); font-weight: 700; }
.block-stepper button.done { color: var(--green); background: var(--chip); }
.block-stepper button:disabled { cursor: not-allowed; opacity: .45; }
.block-stepper > i { flex: 1; height: 1px; background: var(--line); }
.block-next { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; padding-top: 16px; border-top: 1px dashed rgba(31, 88, 64, .14); }
.block-section-title { display: flex; align-items: center; gap: 11px; margin-bottom: 15px; }
.block-section-title > span { display: grid; place-items: center; width: 31px; height: 31px; border-radius: 50%; color: #17382d; background: #e2bd73; font: 700 9px ui-monospace, monospace; }
.block-section-title b, .block-section-title small { display: block; }
.block-section-title b { font: 500 17px var(--display); }
.block-section-title small { margin-top: 2px; color: var(--muted); font-size: 8px; }
.block-material, .block-exercises, .block-test { padding-top: 4px; border-top: 1px solid var(--line); }
.block-markdown { color: var(--ink); font-size: 11px; line-height: 1.8; }
.block-markdown :deep(h1), .block-markdown :deep(h2), .block-markdown :deep(h3) { margin: 19px 0 8px; color: var(--ink); font-family: var(--display); font-weight: 500; }
.block-markdown :deep(code) { padding: 2px 5px; border-radius: 5px; background: var(--chip); }
.exercise-list { display: grid; gap: 9px; }
.exercise-list > div { padding: 15px; border-radius: 14px; background: var(--el-fill-color-light); }
.exercise-list > div > span { color: var(--green); font-size: 8px; font-weight: 800; }
.exercise-list > div > p { margin: 7px 0; font-size: 10px; line-height: 1.7; }
.exercise-list button { padding: 0; border: 0; color: var(--green); background: transparent; font-size: 8px; }
.exercise-answer { margin-top: 10px; padding: 11px; border-left: 3px solid #d5b263; background: rgba(255, 255, 255, .75); }
.exercise-answer b { font-size: 9px; }
.exercise-answer p { margin: 5px 0 0; color: var(--muted); font-size: 9px; line-height: 1.6; }
.test-locked, .test-passed { padding: 18px; border-radius: 14px; color: var(--muted); background: var(--chip); font-size: 10px; line-height: 1.7; }
.test-passed b { color: var(--green); font: 500 17px var(--display); }
.test-passed p { margin: 5px 0 0; }
.review-questions { display: grid; gap: 10px; margin-top: 14px; padding-top: 14px; border-top: 1px dashed rgba(31, 88, 64, .18); }
.test-question.review { background: rgba(255, 255, 255, .55); }
.test-question.review > b { font-size: 10px; line-height: 1.6; }
.review-option { display: flex; gap: 7px; align-items: baseline; padding: 7px 10px; border-radius: 9px; background: var(--el-fill-color-light); font-size: 9px; line-height: 1.6; }
.review-option .mark { color: #a8a39a; font-size: 8px; }
.review-option.correct { color: var(--green); background: var(--chip); }
.review-option.correct .mark { color: var(--green); font-weight: 800; }
.review-analysis { margin: 2px 0 0; color: var(--muted); font-size: 9px; line-height: 1.6; }
.review-analysis b { color: var(--green); }
html.dark .test-question.review { background: rgba(255, 255, 255, .04); }
.test-question { display: grid; gap: 10px; margin-bottom: 12px; padding: 15px; border: 1px solid rgba(31, 88, 64, .09); border-radius: 14px; background: var(--paper-solid); }
.test-question > b { font-size: 10px; line-height: 1.6; }
.test-question :deep(.el-radio-group) { display: grid; gap: 6px; }
.test-feedback { padding: 9px 10px; border-radius: 9px; font-size: 9px; }
.test-feedback.correct { color: var(--green); background: var(--chip); }
.test-feedback.wrong { color: #9b4e47; background: #f7e9e6; }
.test-feedback p { margin: 4px 0 0; line-height: 1.6; }
.task-chat { display: grid; gap: 12px; min-width: 0; padding: 16px 18px 14px; border-top: 1px solid rgba(31, 88, 64, .08); }
.chat-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; }
.chat-head h3 { margin: 6px 0 0; font: 500 19px var(--display); }
.chat-head-actions { display: grid; justify-items: end; gap: 4px; }
.chat-head-actions small { flex: none; max-width: 220px; color: var(--muted); font-size: 8px; line-height: 1.6; text-align: right; }
.chat-messages { display: grid; gap: 10px; max-height: 300px; overflow-y: auto; padding-right: 4px; }
.chat-empty { padding: 18px 10px; color: var(--muted); font-size: 10px; text-align: center; }
.chat-msg { display: grid; gap: 6px; max-width: 82%; }
.chat-msg.user { justify-self: end; }
.chat-msg.assistant { justify-self: start; }
.chat-bubble { padding: 11px 15px; border-radius: 14px; font-size: 11px; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
.chat-msg.user .chat-bubble { color: #f2f7f3; border-bottom-right-radius: 4px; background: linear-gradient(145deg, #1d5c44, #143c2d); }
.chat-msg.assistant .chat-bubble { color: var(--ink); border: 1px solid rgba(31, 88, 64, .1); border-bottom-left-radius: 4px; background: var(--paper); }
.chat-bubble.typing { color: var(--muted); }
.chat-cites { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.cite-source { flex-basis: 100%; color: var(--muted); font-size: 8px; font-weight: 700; letter-spacing: .08em; }
.chat-cites a { max-width: 260px; overflow: hidden; padding: 4px 9px; border: 1px solid rgba(23, 107, 80, .14); border-radius: 99px; color: var(--green); background: var(--el-fill-color-light); font-size: 8px; text-decoration: none; text-overflow: ellipsis; white-space: nowrap; }
.chat-cites a.web { border-color: rgba(176, 137, 62, .22); color: #8a6420; background: var(--seal); }
.chat-cites a[href]:hover { filter: brightness(.96); text-decoration: underline; }
.chat-input { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: 10px; }
.workspace-notebook { padding: 0 18px 16px; border-top: 1px solid rgba(31, 88, 64, .08); }
.nb-panel + .nb-panel { border-top: 1px solid rgba(31, 88, 64, .07); }
.nb-head { display: flex; align-items: center; gap: 9px; width: 100%; padding: 14px 2px; border: 0; background: transparent; color: var(--ink); cursor: pointer; }
.nb-head:hover .nb-title { color: var(--green); }
.nb-icon { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 8px; color: var(--green); background: var(--chip); font-style: normal; font-size: 11px; line-height: 1; }
.nb-title { flex: 1; text-align: left; font-size: 11px; font-weight: 700; transition: color .18s ease; }
.nb-badge { padding: 2px 8px; border-radius: 99px; color: var(--green); background: var(--chip); font-size: 8px; font-weight: 700; }
.nb-arrow { color: var(--muted); font-size: 9px; transition: transform .2s ease; }
.nb-panel.open .nb-arrow { transform: rotate(180deg); }
.nb-body { padding: 0 2px 14px; }
.note-save { display: flex; align-items: center; justify-content: space-between; }
.note-save > span { color: var(--muted); font-size: 8px; font-weight: 700; letter-spacing: .12em; }
.note-editor { display: grid; gap: 11px; }
.note-editor > div { display: flex; align-items: center; justify-content: space-between; }
.note-editor > div > span { color: var(--muted); font-size: 8px; font-weight: 700; letter-spacing: .1em; }
.task-clues { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.task-clues > div { padding: 16px; border-radius: 14px; background: var(--el-fill-color-light); }
.task-clues span, .task-clues b { display: block; }
.task-clues span { color: var(--muted); font-size: 8px; }
.task-clues b { margin-top: 6px; font-size: 10px; font-weight: 600; }
.task-clues .source-clue { grid-column: 1 / -1; }
.source-clue b { display: inline-block; margin: 7px 7px 0 0; padding: 6px 9px; border-radius: 99px; color: var(--green); background: rgba(255, 255, 255, .72); }
@keyframes focus-pulse { 50% { box-shadow: 0 0 0 17px rgba(230, 194, 116, .045), 0 0 0 34px rgba(255, 255, 255, .012), inset 0 0 50px rgba(6, 23, 17, .18); } }

@media (max-width: 1000px) {
  .graph-hero { grid-template-columns: 1fr; }
  .graph-summary { justify-content: flex-start; }
  .graph-toolbar { align-items: flex-start; flex-direction: column; }
  .graph-legend { flex-wrap: wrap; }
  .today-entry-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .today-entry-list > div { grid-column: 1 / -1; }
  .day-ribbon { grid-template-columns: 1fr auto; }
  .date-control { grid-column: 1 / -1; width: 100%; }
  .day-layout, .day-layout.queue-is-collapsed { grid-template-columns: 1fr; grid-template-rows: auto; }
  .day-resizer { display: none; }
  .day-left { position: static; grid-column: 1; grid-row: auto; order: 1; max-height: none; overflow: visible; }
  .focus-side { position: static; grid-column: 1; grid-row: auto; padding: 0; border: 0; border-radius: 0; background: none; box-shadow: none; }
  .task-chat { max-height: 520px; }
  .focus-room { grid-column: 1; grid-row: auto; order: 2; grid-template-columns: 1fr; }
  .queue-expand { display: flex; align-items: center; gap: 10px; }
  .queue-expand > b { letter-spacing: normal; writing-mode: horizontal-tb; }
  .queue-timeline { display: grid; grid-template-columns: repeat(2, 1fr); gap: 7px; }
  .queue-timeline::before { display: none; }
  .learning-block { padding: 20px; }
}
@media (max-width: 620px) {
  .graph-hero { gap: 18px; padding: 24px 20px; border-radius: 25px; }
  .graph-summary { gap: 14px; }
  .graph-summary strong { font-size: 22px; }
  .graph-stage { min-height: 620px; border-radius: 24px; }
  .graph-toolbar { padding: 25px 22px 8px; }
  .graph-legend small { width: 100%; padding: 0; border: 0; }
  .task-graph { height: 440px; }
  .today-entry-list { grid-template-columns: 1fr; margin: 0 18px 22px; }
  .today-entry-list > div { grid-column: auto; }
  .graph-no-today { margin: 0 18px 22px; }
  .day-ribbon { grid-template-columns: 1fr; gap: 18px; padding: 24px 20px; border-radius: 25px; }
  .graph-back { top: 6px; left: 20px; }
  .day-signals { justify-content: space-between; }
  .date-control { grid-column: auto; }
  .day-queue, .focus-room { border-radius: 22px; }
  .queue-timeline { grid-template-columns: 1fr; }
  .focus-room { min-height: 600px; }
  .focus-header { align-items: flex-start; flex-direction: column; padding: 24px 22px 18px; }
  .focus-side { flex-wrap: wrap; }
  .focus-console { margin: 0 16px; padding: 30px 16px; }
  .focus-dial { width: 174px; height: 174px; }
  .focus-dial span { font-size: 39px; }
  .learning-block { margin: 16px 16px 0; padding: 18px 16px; }
  .block-head, .block-generation { align-items: flex-start; flex-direction: column; }
  .block-status { justify-content: flex-start; }
  .attach-sources { grid-template-columns: 1fr; }
  .day-left { margin: 0 16px; }
  .task-chat { padding: 18px 16px 14px; }
  .chat-head { align-items: flex-start; flex-direction: column; gap: 6px; }
  .chat-head-actions { justify-items: start; }
  .chat-head-actions small { max-width: none; text-align: left; }
  .chat-msg { max-width: 94%; }
  .workspace-notebook { padding: 0 10px 18px; }
  .task-clues { grid-template-columns: 1fr; }
}

/* 黑夜模式：scoped 覆盖（无法用 token 表达的暗色规则） */
html.dark .graph-stage {
  border-color: rgba(255, 255, 255, .08);
  background: radial-gradient(circle at 15% 5%, rgba(255, 255, 255, .04), transparent 26%), var(--card);
}
html.dark .day-left,
html.dark .focus-room { border-color: rgba(255, 255, 255, .08); }
html.dark .queue-timeline button.canceled > i { background: rgba(255, 255, 255, .08); }
html.dark .block-status span { color: var(--gold); }
html.dark .block-status .ready,
html.dark .block-status .passed { color: var(--green); }
html.dark .block-status .needs-source { color: var(--red); background: rgba(217, 124, 116, .13); }
html.dark .exploration-notice p { color: var(--muted); }
html.dark .query-chips span { color: var(--gold); background: rgba(255, 255, 255, .06); }
html.dark .exercise-answer { background: rgba(255, 255, 255, .06); }
html.dark .test-feedback.wrong { color: var(--red); background: rgba(217, 124, 116, .13); }
html.dark .task-chat,
html.dark .workspace-notebook { border-top-color: rgba(255, 255, 255, .07); }
html.dark .chat-cites a.web { color: var(--gold); }
html.dark .source-clue b { background: rgba(255, 255, 255, .06); }
html.dark .early-end-action:hover { color: var(--gold); }
html.dark .ended-state { color: var(--gold); }
html.dark .graph-legend .future-dot { border-color: #5a6a60; background: #33403a; }
</style>
