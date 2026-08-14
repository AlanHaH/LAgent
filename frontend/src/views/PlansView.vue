<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, idempotencyKey } from '../api/http'

const router = useRouter()
const route = useRoute()
const goals = ref<any[]>([])
const selectedGoal = ref('')
const projects = ref<any[]>([])
const selectedProject = ref('')
const job = ref<any>()
const detail = ref<any>()
const generating = ref(false)
const publishing = ref(false)
const loadingExisting = ref(false)
const requirement = ref('')
const knowledgeSpaces = ref<any[]>([])
const knowledgeDocuments = ref<Record<string, any[]>>({})
const selectedKnowledgeSpaceIds = ref<string[]>([])
const selectedChangeIds = ref<string[]>([])
const jobPollingMs = 3000
// 已发布版本与更新的待确认提案并存时：默认展示已发布版本（第 4 步、冻结），新提案在顶部提示
const pendingProposal = ref<any>()   // 与已发布版本并存、等待确认的新提案
const publishedDetail = ref<any>()   // 已发布版本（默认展示，可切回）
const publishedAt = ref('')          // 已发布版本的生效时间
const pendingPublishVisible = ref(false) // 「查看并确认」弹窗：展示提案内容并直接确认发布

// 等待弹窗：按流水线阶段轮流展示提示文案 + 真实等待时长，作业完成自动关闭
const waitMessages = [
  'Agent 正在理解你的目标与画像…',
  '正在比对知识库资料与学习节奏…',
  '正在设计任务建议与阶段安排…',
  '正在校验任务与日期约束…',
  '生成在后台进行，通常需要 1～3 分钟，可以先浏览其他页面',
]
const waitingText = ref(waitMessages[0])
const waitedSeconds = ref(0)
let waitTimer: ReturnType<typeof setInterval> | undefined
const waitedLabel = computed(() => {
  const minutes = Math.floor(waitedSeconds.value / 60)
  const seconds = String(waitedSeconds.value % 60).padStart(2, '0')
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
})
function startWaitUi() {
  waitingText.value = waitMessages[0]
  waitedSeconds.value = 0
  if (waitTimer) clearInterval(waitTimer)
  waitTimer = setInterval(() => {
    waitedSeconds.value += 1
    waitingText.value = waitMessages[Math.floor(waitedSeconds.value / 8) % waitMessages.length]
  }, 1000)
}
function stopWaitUi() {
  if (waitTimer) clearInterval(waitTimer)
  waitTimer = undefined
}
watch(generating, (value) => (value ? startWaitUi() : stopWaitUi()))
onUnmounted(stopWaitUi)

const changes = computed(() => detail.value?.changes || [])
const validations = computed(() => detail.value?.validation || [])
const canPublish = computed(() => detail.value?.version?.status === 'PENDING_CONFIRMATION')
const selectedGoalName = computed(() => goals.value.find((goal) => goal.publicId === selectedGoal.value)?.name || '未选择目标')
const selectedGoalData = computed(() => goals.value.find((goal) => goal.publicId === selectedGoal.value))
const customDirection = computed(() => !selectedGoalData.value?.directionId)
// 计划模块 Tab：AI 提案（当前页）与 正式生效（同模块内切换）
const proposalTabPath = computed(() => selectedGoal.value ? `/plans/${selectedGoal.value}` : '/plans')
const effectiveTabPath = computed(() => selectedGoal.value ? `/plans/${selectedGoal.value}/effective` : '/plans/effective')
const selectedKnowledgeDocumentCount = computed(() => selectedKnowledgeSpaceIds.value.reduce(
  (total, id) => total + (knowledgeDocuments.value[id] || []).filter((doc) => doc.status === 'INDEXED').length, 0,
))
const workflowStep = computed(() => {
  const status = detail.value?.version?.status
  if (status === 'PUBLISHED') return 4
  if (status === 'PENDING_CONFIRMATION') return 3
  if (detail.value) return 2
  return 1
})
const statusText = computed(() => ({
  DRAFT: '提案准备中',
  PENDING_CONFIRMATION: '等待你的确认',
  PUBLISHED: '已进入正式计划',
  REJECTED: '已拒绝',
}[detail.value?.version?.status as string] || detail.value?.version?.status || '尚未生成'))
// 可审阅状态才显示“重新校验/不采用”；已发布或已拒绝的状态不允许再校验（后端校验会改状态）
const versionStatus = computed(() => detail.value?.version?.status as string)
const canReview = computed(() => ['DRAFT', 'VALIDATION_FAILED', 'PENDING_CONFIRMATION'].includes(versionStatus.value))
// 校验失败或已拒绝的方案已不可发布：提供“重新生成方案”回到规划入口，无需刷新页面
const canRegenerate = computed(() => ['VALIDATION_FAILED', 'REJECTED'].includes(versionStatus.value))
const publishedAtText = computed(() => publishedAt.value ? String(publishedAt.value).replace('T', ' ').slice(0, 16) : '')
// 当前展示已发布版本，且存在更新的待确认提案 → 顶部提示“有新提案待确认”
const showPendingBanner = computed(() => detail.value?.version?.status === 'PUBLISHED' && !!pendingProposal.value)
// 当前展示的是提案，且该目标存在已发布版本 → 顶部提示可返回已发布版本
const canBackToPublished = computed(() => !!publishedDetail.value && !!detail.value && detail.value.version?.status !== 'PUBLISHED')
// 「查看并确认」弹窗里要展示的新提案变更内容
const pendingChanges = computed(() => pendingProposal.value?.changes || [])
watch(() => detail.value?.version?.publicId, () => {
  selectedChangeIds.value = changes.value.map((change:any) => change.publicId)
})

onMounted(async () => {
  const [page, spaces] = await Promise.all([
    api<any>({ url: '/goals', params: { pageSize: 100 } }),
    api<any[]>({ url: '/knowledge-spaces' }),
  ])
  goals.value = page.items.filter((g: any) => g.status === 'ACTIVE')
  knowledgeSpaces.value = spaces
  const documentEntries = await Promise.all(spaces.map(async (space: any) => {
    const documents = await api<any[]>({ url: `/knowledge-spaces/${space.publicId}/documents` })
    return [space.publicId, documents] as const
  }))
  knowledgeDocuments.value = Object.fromEntries(documentEntries)
  const routeGoalId = typeof route.params.id === 'string' ? route.params.id : ''
  selectedGoal.value = goals.value.some((goal) => goal.publicId === routeGoalId)
    ? routeGoalId
    : goals.value[0]?.publicId || ''
  await loadGoalProjects()
  await loadCurrentPlan()
})

async function loadGoalProjects() {
  projects.value = selectedGoal.value
    ? await api<any[]>({ url:`/goals/${selectedGoal.value}/projects` }) : []
  const routeProjectId = typeof route.query.project === 'string' ? route.query.project : ''
  const preferred = projects.value.some((project:any) => project.publicId === routeProjectId)
    ? routeProjectId : selectedProject.value
  selectedProject.value = projects.value.some((project:any) => project.publicId === preferred) ? preferred : ''
}

async function changeGoal() {
  selectedProject.value = ''
  await loadGoalProjects()
  await loadCurrentPlan()
}

async function changeProject() {
  await loadCurrentPlan()
}

function planContext() {
  const raw = detail.value?.version?.contextSnapshotJson
  if (!raw) return {}
  if (typeof raw === 'object') return raw
  try { return JSON.parse(raw) } catch { return {} }
}

function suggestKnowledgeSpaces() {
  if (selectedKnowledgeSpaceIds.value.length) return
  const goal = goals.value.find((item) => item.publicId === selectedGoal.value)
  if (!goal?.directionId) return
  selectedKnowledgeSpaceIds.value = knowledgeSpaces.value
    .filter((space) => String(space.directionId || '') === String(goal.directionId))
    .filter((space) => (knowledgeDocuments.value[space.publicId] || []).some((doc) => doc.status === 'INDEXED'))
    .map((space) => space.publicId)
}

async function loadCurrentPlan() {
  detail.value = undefined
  job.value = undefined
  pendingProposal.value = undefined
  publishedDetail.value = undefined
  publishedAt.value = ''
  if (!selectedGoal.value) return
  loadingExisting.value = true
  try {
    const params = selectedProject.value ? { projectId: selectedProject.value } : undefined
    const [proposal, effective] = await Promise.all([
      api<any | null>({ url: `/goals/${selectedGoal.value}/plan`, params }) || Promise.resolve(null),
      api<any | null>({ url: `/goals/${selectedGoal.value}/effective-plan`, silent: true, params }).catch(() => null),
    ])
    // 已发布版本与更新的待确认提案并存时，AI 提案页默认展示已生效版本（第 4 步、冻结），
    // 新提案在顶部提示等待确认；未发布过、或没有新提案时保持原有展示逻辑。
    const pending = proposal && ['DRAFT', 'VALIDATING', 'PENDING_CONFIRMATION'].includes(proposal.version?.status) ? proposal : null
    if (pending && effective?.version?.publicId) {
      pendingProposal.value = pending
      const pub = await api<any | null>({ url: `/plan-versions/${effective.version.publicId}`, silent: true }).catch(() => null)
      publishedDetail.value = pub || pending
      publishedAt.value = effective.publishedAt || ''
      detail.value = publishedDetail.value
    } else {
      detail.value = proposal || undefined
    }
    const savedSpaces = planContext().knowledgeSpaceIds
    selectedKnowledgeSpaceIds.value = Array.isArray(savedSpaces) ? savedSpaces : []
    suggestKnowledgeSpaces()
  } finally {
    loadingExisting.value = false
  }
  await resumePendingJob()
}

/** 页面刷新/切换目标后恢复轮询：目标还有后台运行中的作业时继续等待结果 */
async function resumePendingJob() {
  if (!selectedGoal.value) return
  const latest = await api<any | null>({ url: `/goals/${selectedGoal.value}/planning-jobs`, silent: true }).catch(() => null)
  if (!latest || !['QUEUED', 'RUNNING'].includes(latest.status)) return
  generating.value = true
  job.value = latest
  try { await waitForJob(latest.publicId) } finally { generating.value = false }
}

/** 轮询作业状态直到完成：SUCCEEDED 加载提案，FAILED 弹出错误信息 */
async function waitForJob(jobPublicId: string) {
  for (;;) {
    await new Promise((resolve) => setTimeout(resolve, jobPollingMs))
    const current = await api<any>({ url: `/planning-jobs/${jobPublicId}`, silent: true })
    if (current.status === 'SUCCEEDED') {
      if (current.planVersionId) detail.value = await api<any>({ url: `/plan-versions/${current.planVersionId}` })
      ElMessage.success('Agent 已生成可审阅方案，尚未修改任何正式任务')
      return
    }
    if (current.status === 'FAILED') {
      ElMessage.error(current.errorMessage || '计划生成失败，请稍后重试')
      return
    }
  }
}

async function generate() {
  generating.value = true
  try {
    job.value = await api<any>({
      method: 'POST',
      url: `/goals/${selectedGoal.value}/planning-jobs`,
      headers: { 'Idempotency-Key': idempotencyKey() },
      data: { type: 'INITIAL', projectId:selectedProject.value || undefined, userRequirement: requirement.value, knowledgeSpaceIds: selectedKnowledgeSpaceIds.value },
    })
    if (job.value.status === 'SUCCEEDED') {
      if (job.value.planVersionId) detail.value = await api<any>({ url: `/plan-versions/${job.value.planVersionId}` })
      ElMessage.success('Agent 已生成可审阅方案，尚未修改任何正式任务')
    } else {
      await waitForJob(job.value.publicId)
    }
  } catch (e) {
    // AI 不可用时全局拦截器已弹出错误提示
  } finally { generating.value = false }
}

async function optimize() {
  const value = await ElMessageBox.prompt(
    '说明哪里需要调整，例如：这周工作变忙、希望减少每天任务量，或想加强练习。',
    '让 Agent 优化当前计划',
    { inputType: 'textarea', inputValue: requirement.value, confirmButtonText: '生成优化方案', cancelButtonText: '取消' },
  ).then((result) => result.value).catch(() => null)
  if (value === null) return
  generating.value = true
  try {
    requirement.value = value
    job.value = await api<any>({
      method: 'POST',
      url: `/goals/${selectedGoal.value}/optimization-requests`,
      headers: { 'Idempotency-Key': idempotencyKey() },
      data: { type: 'OPTIMIZATION', projectId:selectedProject.value || undefined, userRequirement: value, knowledgeSpaceIds: selectedKnowledgeSpaceIds.value },
    })
    if (job.value.status === 'SUCCEEDED') {
      if (job.value.planVersionId) detail.value = await api<any>({ url: `/plan-versions/${job.value.planVersionId}` })
      ElMessage.success('新的优化方案已生成，发布前不会改动现有任务')
    } else {
      await waitForJob(job.value.publicId)
    }
  } finally { generating.value = false }
}

async function validate() {
  detail.value = await api<any>({ method: 'POST', url: `/plan-versions/${detail.value.version.publicId}/validation` })
  ElMessage.success('任务与日期约束校验完成')
}

async function publish() {
  if (!detail.value?.version?.publicId) return
  await ElMessageBox.confirm(
    `将应用 ${changes.value.length} 项变更。发布后会生成正式任务，是否继续？`,
    '确认这段学习节奏',
    { confirmButtonText: '确认发布', cancelButtonText: '再看看', type: 'warning' },
  )
  await performPublish(detail.value.version.publicId)
}

async function keepSelectedChanges() {
  if (!detail.value || selectedChangeIds.value.length === changes.value.length) return
  if (!selectedChangeIds.value.length) return ElMessage.warning('至少保留一项变更')
  detail.value = await api<any>({ method:'POST', url:`/plan-versions/${detail.value.version.publicId}/partial-selection`,
    data:{ selectedChangeIds:selectedChangeIds.value } })
  ElMessage.success(`已生成仅包含 ${changes.value.length} 项变更的新提案，请重新校验后发布`)
}

async function reject() {
  await api({ method: 'POST', url: `/plan-versions/${detail.value.version.publicId}/rejection`, data: { reason: '用户拒绝当前方案' } })
  detail.value.version.status = 'REJECTED'
  ElMessage.info('方案已拒绝，正式计划没有改变，可点击「重新生成方案」重新开始')
}

/** 回到规划入口重新开始：已拒绝/校验失败的提案留在历史中，不阻塞新作业 */
function regenerate() {
  detail.value = undefined
  job.value = undefined
}

/** 顶部提示条：点「查看并确认」→ 弹窗展示新提案内容，点确认直接发布，无需先切到审阅视图 */
function viewPendingProposal() {
  if (!pendingProposal.value) return
  pendingPublishVisible.value = true
}

/** 新提案确认发布：与审阅视图的 publish() 复用同一段后端调用，发布后跳本模块正式生效视图 */
async function performPublish(versionPublicId: string) {
  publishing.value = true
  try {
    const confirmation = await api<any>({ method: 'POST', url: `/plan-versions/${versionPublicId}/confirmation-requests` })
    const result = await api<any>({
      method: 'POST',
      url: `/plan-versions/${versionPublicId}/publication`,
      headers: { 'Idempotency-Key': idempotencyKey() },
      data: { confirmationToken: confirmation.token },
    })
    ElMessage.success(`计划 v${result.versionNo} 已发布，共生成 ${result.changedTaskIds.length} 个任务，已正式生效`)
    await router.push({ path: `/plans/${selectedGoal.value}/effective`,
      query: selectedProject.value ? { project: selectedProject.value } : undefined })
  } finally { publishing.value = false }
}

/** 弹窗内确认：直接发布这份待确认的新提案。成功即跳正式生效；失败保留弹窗与顶部提示，可重试 */
async function confirmPublishPending() {
  const proposal = pendingProposal.value
  if (!proposal?.version?.publicId || publishing.value) return
  await performPublish(proposal.version.publicId)
}

/** 顶部提示条：从新提案切回已发布版本（新提案仍留在待确认状态） */
function viewPublished() {
  if (!publishedDetail.value) return
  detail.value = publishedDetail.value
}

function after(change: any) {
  try { return JSON.parse(change.afterJson || '{}') } catch { return {} }
}
</script>

<template>
  <div class="planner-page" v-loading="loadingExisting">
    <div class="plan-module-tabs">
      <router-link :to="proposalTabPath" class="active">AI 提案</router-link>
      <router-link :to="effectiveTabPath">正式生效</router-link>
    </div>
    <!-- 已发布版本与新提案并存时的状态提示条 -->
    <div v-if="showPendingBanner" class="proposal-context-bar">
      <span class="bar-pulse" />
      <b>有 v{{ pendingProposal.version.versionNo }} 新提案待确认</b>
      <small>正式生效的仍是 v{{ detail.version.versionNo }}；新提案不会改动正式任务，直到你确认发布。</small>
      <el-button size="small" type="primary" @click="viewPendingProposal">查看并确认</el-button>
    </div>
    <div v-else-if="canBackToPublished" class="proposal-context-bar">
      <span class="bar-pulse" />
      <b>当前是新提案 v{{ detail.version.versionNo }}（尚未生效）</b>
      <small>正式生效的仍是 v{{ publishedDetail.version.versionNo }}，新提案未发布前不会改动正式任务。</small>
      <el-button size="small" plain @click="viewPublished">返回已发布版本</el-button>
    </div>
    <section v-if="!detail" class="planner-landing">
      <div class="planner-story">
        <div class="agent-glyph"><span>AI</span><i /></div>
        <span class="eyebrow light">ADAPTIVE PLANNER</span>
        <h1>不是排满时间，<br>而是设计一种能坚持的节奏。</h1>
        <p>Agent 会理解你的目标、画像和期限，先提出方案；只有得到你的确认，才会写入正式任务。</p>
        <div class="promise-list">
          <span><i>01</i>一天一小步</span>
          <span><i>02</i>每一步可解释</span>
          <span><i>03</i>发布前不落地</span>
        </div>
      </div>

      <div class="planning-composer">
        <div class="composer-head">
          <span>和 Agent 开始一轮规划</span>
          <small>{{ goals.length }} 个活动目标可用</small>
        </div>
        <el-form label-position="top">
          <el-form-item label="这次围绕哪个目标？">
            <el-select v-model="selectedGoal" class="full" placeholder="请先激活一个目标" @change="changeGoal">
              <el-option v-for="goal in goals" :key="goal.publicId" :value="goal.publicId" :label="goal.name" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="projects.length" label="是否围绕实践项目规划？（可选）">
            <el-select v-model="selectedProject" clearable class="full" placeholder="不选则生成目标通用学习计划" @change="changeProject">
              <el-option v-for="project in projects" :key="project.publicId" :value="project.publicId" :label="project.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="使用哪些知识库资料规划？（可选）">
            <el-select v-model="selectedKnowledgeSpaceIds" multiple collapse-tags collapse-tags-tooltip
              class="full" placeholder="不选择则按目标与画像规划">
              <el-option v-for="space in knowledgeSpaces" :key="space.publicId" :value="space.publicId"
                :label="`${space.name} · ${(knowledgeDocuments[space.publicId] || []).filter((doc:any) => doc.status === 'INDEXED').length} 篇可用`"
                :disabled="!(knowledgeDocuments[space.publicId] || []).some((doc:any) => doc.status === 'INDEXED')" />
            </el-select>
            <div class="knowledge-hint">
              只会读取你明确选择且已完成索引的资料；生成的每项任务都会保留引用来源。
            </div>
          </el-form-item>
          <div v-if="customDirection" class="exploration-hint">
            <b>自定义方向将先进入探索阶段</b>
            <p>Agent 会给每个知识块生成检索词并标注来源。没有可信资料的块会引导你下载后上传知识库，临时生成内容会明确标注为待核验。</p>
            <el-button link type="primary" @click="router.push('/knowledge')">先去上传资料 →</el-button>
          </div>
          <el-form-item label="告诉我你想要的节奏（可选）">
            <el-input v-model="requirement" type="textarea" :rows="6" placeholder="例如：工作日以阅读为主，周末留出一段完整时间做项目；这周不要安排得太满。" />
          </el-form-item>
          <div class="context-preview">
            <span>当前上下文</span><b>{{ selectedGoalName }}</b>
            <small>画像偏好、每周时段、特殊日期与容量比例会共同参与排期<span v-if="selectedKnowledgeDocumentCount"> · 已选择 {{ selectedKnowledgeDocumentCount }} 篇知识库资料</span></small>
          </div>
          <el-button type="primary" size="large" class="full" :disabled="!selectedGoal" :loading="generating" @click="generate">
            {{ generating ? 'Agent 正在后台生成方案…' : '让 Agent 提出方案' }} <span v-if="!generating" class="button-arrow">↗</span>
          </el-button>
          <small v-if="generating" class="planning-wait-hint">生成在后台进行，通常需要 1～3 分钟；可以先浏览其他页面，完成后会自动展示，请勿重复提交。</small>
        </el-form>
        <div v-if="!goals.length" class="no-goal">需要先创建并激活一个学习目标，Agent 才知道朝哪里规划。</div>
      </div>
    </section>

    <template v-else>
      <section class="plan-stage">
        <div class="stage-top">
          <div class="stage-copy">
            <span class="eyebrow light">{{ detail.version.status === 'PUBLISHED' ? 'PUBLISHED PLAN' : 'PROPOSAL' }} / VERSION {{ detail.version.versionNo }}</span>
            <h1>{{ detail.version.status === 'PUBLISHED' ? '当前正在执行的学习路线' : detail.version.triggerType === 'OPTIMIZATION' ? '一份更合适的新节奏' : '你的第一版学习路线' }}</h1>
            <p>{{ statusText }} · 风险等级 {{ detail.version.riskLevel }}<template v-if="detail.version.status === 'PUBLISHED'"> · 正式生效于 {{ publishedAtText }}</template><template v-else> · {{ changes.length }} 项建议变更</template></p>
            <div v-if="planContext().knowledgeDocuments?.length" class="plan-source-summary">
              <span>资料驱动</span>
              <b>基于 {{ planContext().knowledgeDocuments.length }} 篇知识库文档生成</b>
            </div>
            <div v-if="planContext().explorationMode" class="plan-source-summary exploration">
              <span>探索模式</span>
              <b>自定义方向：先核验来源，再逐块学习与测试</b>
            </div>
          </div>
          <div class="stage-tools">
            <el-select v-model="selectedGoal" class="plan-goal-switch" @change="changeGoal">
              <el-option v-for="goal in goals" :key="goal.publicId" :value="goal.publicId" :label="goal.name" />
            </el-select>
            <div class="stage-actions">
              <el-button v-if="canReview" @click="validate">重新校验</el-button>
              <el-button v-if="canReview && selectedChangeIds.length < changes.length" @click="keepSelectedChanges">仅保留已选 {{ selectedChangeIds.length }} 项</el-button>
              <el-button v-if="detail.version.status === 'PUBLISHED'" :loading="generating" @click="optimize">优化当前计划</el-button>
              <el-button type="danger" plain :disabled="!canReview" @click="reject">不采用</el-button>
              <el-button v-if="canRegenerate" @click="regenerate">重新生成方案</el-button>
              <el-button type="primary" :disabled="!canPublish" :loading="publishing" @click="publish">确认并发布</el-button>
            </div>
          </div>
        </div>
        <div class="workflow-track">
          <div v-for="(label, index) in ['理解目标', '形成提案', '等待确认', '正式生效']" :key="label" :class="{ active: workflowStep >= index + 1, current: workflowStep === index + 1 }">
            <i>{{ workflowStep > index + 1 ? '✓' : index + 1 }}</i><span>{{ label }}</span>
          </div>
        </div>
      </section>

      <div class="proposal-layout">
        <section class="change-story">
          <div class="proposal-heading">
            <div><span class="eyebrow">{{ detail.version.status === 'PUBLISHED' ? 'PUBLISHED RHYTHM' : 'PROPOSED RHYTHM' }}</span><h2>{{ detail.version.status === 'PUBLISHED' ? '这份计划这样展开' : 'Agent 建议这样展开' }}</h2></div>
            <span>{{ changes.length }} STEPS</span>
          </div>
          <div v-if="detail.stages?.length" class="stage-summary">
            <article v-for="stage in detail.stages" :key="stage.id">
              <span>阶段 {{ stage.sequenceNo }}</span>
              <b>{{ stage.name }}</b>
              <small>{{ stage.startDate }} — {{ stage.endDate }}</small>
              <p>{{ stage.outcome }}</p>
            </article>
          </div>
          <div v-if="!changes.length" class="empty">当前提案没有任务变更。</div>
          <article v-for="(change, index) in changes" :key="change.publicId" class="change-chapter">
            <div class="chapter-index"><el-checkbox v-if="canReview" v-model="selectedChangeIds" :value="change.publicId" :aria-label="`选择变更 ${index + 1}`"/><span>{{ String(index + 1).padStart(2, '0') }}</span><i /></div>
            <div class="chapter-body">
              <div class="chapter-top"><span>{{ change.action }}</span><small>{{ after(change).scheduledStart?.replace('T', ' ') || '待安排' }}</small></div>
              <h3>{{ after(change).title }}</h3>
              <p>{{ change.reason }}</p>
              <div v-if="after(change).learningBlock" class="block-outline">
                <span>知识块 {{ after(change).learningBlock.sequenceNo }}</span>
                <b>{{ after(change).learningBlock.objective }}</b>
                <small>{{ after(change).learningBlock.sourceStatus === 'READY' ? '可信资料已就绪' : '需要补充或核验资料' }}</small>
                <div v-if="after(change).learningBlock.sourceQueries?.length">
                  <i v-for="query in after(change).learningBlock.sourceQueries" :key="query">{{ query }}</i>
                </div>
              </div>
              <div class="chapter-meta"><span>建议投入 {{ after(change).estimatedMinutes }} 分钟</span><span>{{ after(change).taskType || '学习任务' }}</span><span>{{ after(change).priority || '常规优先级' }}</span><span>通过块测即完成</span></div>
              <div v-if="after(change).knowledgeSources?.length" class="chapter-sources">
                <span>本任务资料</span>
                <div>
                  <small v-for="source in after(change).knowledgeSources" :key="source.chunkId"
                    :title="source.quotePreview">
                    {{ source.documentName }} · 片段 {{ source.chunkNo }}<template v-if="source.pageFrom"> · 第 {{ source.pageFrom }} 页</template>
                  </small>
                </div>
              </div>
              <div v-else-if="after(change).learningBlock?.sourceManifest?.length" class="chapter-sources">
                <span>本块资料与引导</span>
                <div>
                  <small v-for="(source, sourceIndex) in after(change).learningBlock.sourceManifest" :key="source.url || sourceIndex" :title="source.quotePreview">
                    {{ source.title }} · {{ source.sourceType }}
                  </small>
                </div>
              </div>
            </div>
          </article>
        </section>

        <aside class="trust-console">
          <div class="trust-head">
            <span class="trust-orb">{{ validations.every((item: any) => item.severity !== 'ERROR') ? '✓' : '!' }}</span>
            <div><span class="eyebrow">GUARDRAILS</span><h2>发布守门人</h2></div>
          </div>
          <p class="trust-intro">每一项都通过后，计划才会等待你的最终确认。Agent 不能越过这一步。</p>
          <div class="validation-stack">
            <div v-for="validation in validations" :key="validation.validatorCode" :class="['validation-line', validation.severity === 'ERROR' ? 'failed' : 'passed']">
              <i>{{ validation.severity === 'ERROR' ? '!' : '✓' }}</i>
              <div><b>{{ validation.validatorCode }}</b><small>{{ validation.message }}</small></div>
            </div>
            <div v-if="!validations.length && canPublish" class="validation-line passed">
              <i>✓</i><div><b>全部约束已通过</b><small>任务、日期与冲突均符合要求，可以发布。</small></div>
            </div>
            <div v-else-if="!validations.length" class="validation-placeholder">还没有校验结果，点击“重新校验”获得发布结论。</div>
          </div>
          <div class="proposal-fingerprint"><span>PROPOSAL FINGERPRINT</span><code>{{ detail.version.proposalHash?.slice(0, 16) }}…</code></div>
        </aside>
      </div>
    </template>

    <!-- 新提案「查看并确认」弹窗：直接展示变更内容，确认即发布 -->
    <el-dialog v-model="pendingPublishVisible" :title="`确认发布新提案 v${pendingProposal?.version?.versionNo ?? ''}`"
      width="min(620px, 92vw)" align-center class="pending-publish-dialog">
      <p class="pending-publish-intro">
        新提案 v{{ pendingProposal?.version?.versionNo }} 尚未生效，不会改动任何正式任务（正式生效的仍是 v{{ detail?.version?.versionNo }}）。确认后生成正式任务并取代当前计划。
      </p>
      <div class="pending-change-list">
        <article v-for="(change, index) in pendingChanges" :key="change.publicId" class="pending-change">
          <i>{{ String(index + 1).padStart(2, '0') }}</i>
          <div>
            <span>{{ change.action }}</span>
            <b>{{ after(change).title || '未命名任务' }}</b>
            <small>{{ after(change).scheduledStart?.replace('T', ' ') || '待安排' }} · 建议投入 {{ after(change).estimatedMinutes }} 分钟</small>
          </div>
        </article>
        <div v-if="!pendingChanges.length" class="pending-change-empty">该提案没有任务变更。</div>
      </div>
      <template #footer>
        <el-button @click="pendingPublishVisible = false">再看看</el-button>
        <el-button type="primary" :loading="publishing" @click="confirmPublishPending">确认发布</el-button>
      </template>
    </el-dialog>

    <Transition name="fade">
      <div v-if="generating" class="waiting-overlay">
        <div class="waiting-card">
          <div class="agent-glyph"><span>AI</span><i /></div>
          <span class="waiting-eyebrow">ADAPTIVE PLANNER</span>
          <h2>{{ waitingText }}</h2>
          <p class="waiting-text">Agent 正在为你构思方案</p>
          <div class="waiting-dots"><i /><i /><i /></div>
          <small class="waiting-elapsed">已等待 {{ waitedLabel }}，完成后会自动展示</small>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.plan-module-tabs { display: flex; gap: 6px; width: fit-content; margin-bottom: 18px; padding: 4px; border: 1px solid rgba(38, 68, 55, .1); border-radius: 99px; background: var(--el-fill-color-light); }
.plan-module-tabs a { padding: 8px 20px; border-radius: 99px; color: var(--muted); font-size: 11px; font-weight: 700; transition: .2s; }
.plan-module-tabs a:hover { color: var(--ink); }
.plan-module-tabs a.active { color: #17382d; background: linear-gradient(145deg, #edcf8b, #d1a252); box-shadow: 0 6px 16px rgba(160, 122, 46, .22); }
html.dark .plan-module-tabs a.active { color: #17382d; }

.proposal-context-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; padding: 11px 15px; border: 1px solid rgba(176, 137, 62, .28); border-radius: 14px; color: #7a5a22; background: var(--seal); }
.proposal-context-bar .bar-pulse { width: 8px; height: 8px; flex: none; border-radius: 50%; background: #d1a252; box-shadow: 0 0 0 4px rgba(209, 162, 82, .18); }
.proposal-context-bar b { font-size: 11px; }
.proposal-context-bar small { flex: 1; color: #82765c; font-size: 9px; line-height: 1.6; }
html.dark .proposal-context-bar { border-color: rgba(217, 181, 106, .3); }
html.dark .proposal-context-bar b { color: #d9b56a; }
html.dark .proposal-context-bar small { color: #a99a78; }

.pending-publish-dialog .pending-publish-intro { margin: 0 0 14px; color: var(--muted); font-size: 10px; line-height: 1.75; }
.pending-change-list { display: grid; gap: 8px; max-height: 46vh; overflow-y: auto; padding-right: 4px; }
.pending-change { display: grid; grid-template-columns: 30px 1fr; gap: 10px; padding: 12px 14px; border: 1px solid rgba(31, 88, 64, .1); border-radius: 14px; background: var(--chip); }
.pending-change > i { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 9px; color: #8a6420; background: var(--seal); font: italic 10px var(--display); font-style: normal; }
.pending-change div > span, .pending-change div > b, .pending-change div > small { display: block; }
.pending-change div > span { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.pending-change div > b { margin-top: 4px; font-size: 11px; }
.pending-change div > small { margin-top: 4px; color: var(--muted); font-size: 9px; }
.pending-change-empty { padding: 20px 8px; color: var(--muted); font-size: 9px; text-align: center; }
html.dark .pending-change { border-color: rgba(255, 255, 255, .07); }
html.dark .pending-change > i { color: #d9b56a; }

.planner-landing { display: grid; grid-template-columns: 1.08fr .92fr; min-height: 650px; overflow: hidden; border-radius: 34px; background: #f8faf5; box-shadow: var(--lift-shadow); }
.planner-story { position: relative; display: flex; flex-direction: column; justify-content: center; overflow: hidden; padding: clamp(42px, 6vw, 78px); color: #eef5ef; background: radial-gradient(circle at 82% 18%, rgba(121, 181, 147, .28), transparent 28%), linear-gradient(150deg, #102f26, #1c5642); }
.planner-story::after { position: absolute; right: -140px; bottom: -180px; width: 460px; height: 460px; border: 1px solid rgba(255, 255, 255, .09); border-radius: 50%; box-shadow: 0 0 0 68px rgba(255, 255, 255, .02), 0 0 0 136px rgba(255, 255, 255, .015); content: ""; }
.agent-glyph { position: relative; width: 72px; height: 72px; margin-bottom: 26px; }
.agent-glyph span { position: relative; z-index: 2; display: grid; place-items: center; width: 58px; height: 58px; border-radius: 20px 20px 20px 7px; color: #163a2e; background: linear-gradient(145deg, #f0d18d, #d0a052); font: 800 15px ui-monospace, monospace; box-shadow: 0 15px 35px rgba(5, 25, 18, .28); }
.agent-glyph i { position: absolute; top: 7px; left: 7px; width: 58px; height: 58px; border: 1px solid rgba(232, 198, 126, .45); border-radius: 20px; transform: rotate(15deg); }
.planner-story h1 { position: relative; z-index: 1; max-width: 650px; margin: 17px 0; font: 500 clamp(39px, 4.2vw, 61px)/1.22 var(--display); letter-spacing: -.035em; }
.planner-story > p { position: relative; z-index: 1; max-width: 580px; margin: 0; color: #bed0c7; font-size: 13px; line-height: 1.95; }
.promise-list { position: relative; z-index: 1; display: flex; flex-wrap: wrap; gap: 18px; margin-top: 36px; }
.promise-list span { color: #c9d8d1; font-size: 10px; }
.promise-list i { margin-right: 6px; color: #dbb96f; font: italic 10px var(--display); }
.planning-composer { display: flex; flex-direction: column; justify-content: center; padding: clamp(35px, 5vw, 68px); background: linear-gradient(145deg, #f9faf6, #f0f3ed); }
.composer-head { display: flex; align-items: end; justify-content: space-between; margin-bottom: 28px; }
.composer-head span { font: 500 23px var(--display); }
.composer-head small { color: var(--muted); font-size: 9px; }
.context-preview { margin: 5px 0 20px; padding: 15px 16px; border: 1px solid rgba(36, 83, 63, .1); border-radius: 15px; background: var(--el-fill-color-light); }
.context-preview span, .context-preview b, .context-preview small { display: block; }
.context-preview span { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .12em; }
.context-preview b { margin: 6px 0 4px; overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.context-preview small { color: var(--muted); font-size: 9px; }
.knowledge-hint { margin-top: 7px; color: var(--muted); font-size: 9px; line-height: 1.6; }
.exploration-hint { margin: -2px 0 18px; padding: 14px 15px; border: 1px solid rgba(176, 137, 62, .2); border-radius: 14px; background: var(--seal); }
.exploration-hint b { color: #775b27; font: 500 15px var(--display); }
.exploration-hint p { margin: 6px 0 2px; color: #7c715b; font-size: 9px; line-height: 1.65; }
.button-arrow { margin-left: 8px; }
.planning-wait-hint { display: block; margin-top: 9px; color: var(--muted); font-size: 9px; line-height: 1.6; text-align: center; }
.no-goal { margin-top: 15px; color: var(--red); font-size: 10px; line-height: 1.6; }

.plan-stage { position: relative; overflow: hidden; padding: 24px 34px 22px; border-radius: 28px; color: #eff5ef; background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%); box-shadow: 0 22px 60px rgba(20, 59, 45, .2); }
.stage-top { position: relative; z-index: 1; display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.stage-copy { min-width: 0; }
.stage-copy h1 { margin: 7px 0 5px; font: 500 clamp(23px, 2.6vw, 30px)/1.2 var(--display); letter-spacing: -.02em; }
.stage-copy p { margin: 0; color: #acc4b8; font-size: 10px; }
.plan-source-summary { display: flex; align-items: center; gap: 8px; width: fit-content; margin-top: 10px; padding: 6px 11px; border: 1px solid rgba(226, 194, 126, .25); border-radius: 99px; background: rgba(255, 255, 255, .06); }
.plan-source-summary span { color: #e2c17a; font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.plan-source-summary b { color: #dce8e1; font-size: 9px; font-weight: 500; }
.plan-source-summary.exploration { border-color: rgba(230, 171, 105, .28); }
.plan-source-summary.exploration span { color: #f0b36e; }
.stage-tools { display: flex; align-items: center; gap: 12px; flex: none; flex-wrap: wrap; justify-content: flex-end; }
.plan-goal-switch { width: min(220px, 100%); }
.plan-stage :deep(.plan-goal-switch .el-select__wrapper) { border: 1px solid rgba(255, 255, 255, .12); box-shadow: none; background: rgba(255, 255, 255, .07); }
.plan-stage :deep(.plan-goal-switch .el-select__selected-item) { color: #e7f0eb; }
.workflow-track { position: relative; z-index: 1; display: grid; grid-template-columns: repeat(4, 1fr); max-width: 720px; margin-top: 22px; }
.workflow-track > div { position: relative; display: flex; align-items: center; gap: 8px; color: #7f9b8e; font-size: 9px; }
.workflow-track > div::after { position: absolute; z-index: 0; top: 13px; right: 4px; left: 35px; height: 1px; background: rgba(255, 255, 255, .12); content: ""; }
.workflow-track > div:last-child::after { display: none; }
.workflow-track i { position: relative; z-index: 1; display: grid; place-items: center; width: 27px; height: 27px; border: 1px solid rgba(255, 255, 255, .13); border-radius: 50%; background: #1d4b3b; font-size: 9px; font-style: normal; }
.workflow-track .active { color: #d8e5de; }
.workflow-track .active i { border-color: rgba(226, 194, 126, .45); color: #183a2e; background: #e1bd74; }
.workflow-track .current i { box-shadow: 0 0 0 5px rgba(225, 189, 116, .12); }
.stage-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.plan-stage :deep(.el-button:not(.el-button--primary):not(.el-button--danger)) { border-color: rgba(255, 255, 255, .13); color: #dce8e1; background: rgba(255, 255, 255, .07); }

.proposal-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(300px, .55fr); gap: 22px; margin-top: 22px; }
.change-story { padding: 33px 36px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: var(--card); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.proposal-heading { display: flex; align-items: end; justify-content: space-between; margin-bottom: 18px; }
.proposal-heading h2 { margin: 7px 0 0; font: 500 28px var(--display); }
.proposal-heading > span { color: var(--muted); font-size: 8px; font-weight: 700; letter-spacing: .13em; }
.stage-summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 10px; margin: 6px 0 22px; }
.stage-summary article { padding: 15px 16px; border: 1px solid rgba(34, 82, 61, .1); border-radius: 15px; background: var(--chip); }
.stage-summary span, .stage-summary b, .stage-summary small { display: block; }
.stage-summary span { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .1em; }
.stage-summary b { margin-top: 7px; font: 500 16px var(--display); }
.stage-summary small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.stage-summary p { margin: 10px 0 0; color: var(--muted); font-size: 10px; line-height: 1.65; }
.change-chapter { display: grid; grid-template-columns: 48px 1fr; gap: 12px; }
.chapter-index { position: relative; display:flex; flex-direction:column; align-items:flex-start; gap:8px; padding-top: 18px; color: var(--muted); font: italic 13px var(--display); }
.chapter-index i { position: absolute; top: 49px; bottom: 0; left: 13px; width: 1px; background: rgba(31, 80, 59, .13); }
.change-chapter:last-child .chapter-index i { display: none; }
.chapter-body { padding: 21px 0 24px; border-top: 1px solid var(--line); }
.chapter-top { display: flex; align-items: center; justify-content: space-between; }
.chapter-top span { padding: 4px 8px; border-radius: 99px; color: var(--green); background: var(--mint); font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.chapter-top small { color: var(--muted); font-size: 9px; }
.chapter-body h3 { margin: 12px 0 7px; font: 500 19px var(--display); }
.chapter-body p { max-width: 720px; margin: 0; color: var(--muted); font-size: 11px; line-height: 1.75; }
.block-outline { margin-top: 13px; padding: 13px 14px; border-left: 3px solid #d3ad60; border-radius: 0 12px 12px 0; background: var(--seal); }
.block-outline > span, .block-outline > b, .block-outline > small { display: block; }
.block-outline > span { color: #8a6420; font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.block-outline > b { margin-top: 5px; font-size: 10px; }
.block-outline > small { margin-top: 4px; color: #827864; font-size: 8px; }
.block-outline > div { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }
.block-outline i { padding: 4px 7px; border-radius: 99px; color: #7d6639; background: rgba(255, 255, 255, .72); font-size: 8px; font-style: normal; }
.chapter-meta { display: flex; flex-wrap: wrap; gap: 15px; margin-top: 13px; }
.chapter-meta span { color: var(--muted); font-size: 8px; }
.chapter-meta span::before { display: inline-block; width: 4px; height: 4px; margin: 0 6px 1px 0; border-radius: 50%; background: #c9a55f; content: ""; }
.chapter-sources { margin-top: 14px; padding: 12px 14px; border: 1px solid rgba(31, 88, 64, .09); border-radius: 13px; background: var(--chip); }
.chapter-sources > span { display: block; margin-bottom: 8px; color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .1em; }
.chapter-sources > div { display: flex; flex-wrap: wrap; gap: 6px; }
.chapter-sources small { padding: 5px 8px; border-radius: 99px; color: #66756c; background: rgba(255, 255, 255, .72); font-size: 8px; }
.trust-console { position: sticky; top: 112px; align-self: start; padding: 28px; border-radius: 28px; color: #edf3ee; background: linear-gradient(145deg, #1a4636, #102d24); box-shadow: 0 20px 55px rgba(18, 53, 41, .2); }
.trust-head { display: flex; align-items: center; gap: 13px; }
.trust-head h2 { margin: 5px 0 0; font: 500 22px var(--display); }
.trust-orb { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 50%; color: #173b2f; background: #e1bd74; font-weight: 800; }
.trust-intro { margin: 20px 0; color: #aac0b5; font-size: 10px; line-height: 1.75; }
.validation-stack { display: grid; gap: 8px; }
.validation-line { display: flex; align-items: flex-start; gap: 10px; padding: 12px; border: 1px solid rgba(255, 255, 255, .07); border-radius: 14px; background: rgba(255, 255, 255, .045); }
.validation-line > i { display: grid; place-items: center; flex: none; width: 22px; height: 22px; border-radius: 50%; color: #173b2f; background: #78bb95; font-size: 9px; font-style: normal; }
.validation-line.failed > i { color: #fff; background: #b9574f; }
.validation-line b, .validation-line small { display: block; }
.validation-line b { font-size: 9px; }
.validation-line small { margin-top: 4px; color: #9eb4a9; font-size: 8px; line-height: 1.5; }
.validation-placeholder { padding: 22px 8px; color: #91aa9e; font-size: 9px; line-height: 1.7; text-align: center; }
.proposal-fingerprint { margin-top: 22px; padding-top: 17px; border-top: 1px solid rgba(255, 255, 255, .09); }
.proposal-fingerprint span, .proposal-fingerprint code { display: block; }
.proposal-fingerprint span { color: #789486; font-size: 7px; letter-spacing: .16em; }
.proposal-fingerprint code { margin-top: 6px; color: #cfb473; font-size: 9px; }

@media (max-width: 980px) {
  .planner-landing, .proposal-layout { grid-template-columns: 1fr; }
  .planner-story { min-height: 460px; }
  .trust-console { position: static; }
  .stage-top { flex-direction: column; }
}
@media (max-width: 620px) {
  .planner-landing { border-radius: 26px; }
  .planner-story, .planning-composer { padding: 31px 24px; }
  .planner-story h1 { font-size: 39px; }
  .promise-list { align-items: flex-start; flex-direction: column; gap: 10px; }
  .plan-stage { padding: 24px 20px; border-radius: 25px; }
  .workflow-track { grid-template-columns: repeat(4, auto); gap: 5px; }
  .workflow-track > div { align-items: flex-start; flex-direction: column; }
  .workflow-track > div::after { display: none; }
  .workflow-track span { font-size: 8px; }
  .stage-actions { flex-wrap: wrap; }
  .change-story, .trust-console { padding: 22px; border-radius: 22px; }
  .change-chapter { grid-template-columns: 34px 1fr; }
  .chapter-top { align-items: flex-start; flex-direction: column; gap: 7px; }
}

.waiting-overlay { position: fixed; inset: 0; z-index: 3000; display: grid; place-items: center; background: rgba(13, 34, 26, .16); pointer-events: none; }
.waiting-card { width: min(430px, calc(100vw - 48px)); padding: 36px 38px 30px; border-radius: 26px; color: #eef5ef; text-align: center; background: radial-gradient(circle at 82% 14%, rgba(121, 181, 147, .26), transparent 32%), linear-gradient(150deg, #102f26, #1c5642); box-shadow: 0 30px 80px rgba(9, 34, 25, .35); }
.waiting-card .agent-glyph { margin: 0 auto 24px; }
.waiting-eyebrow { color: #e2c17a; font-size: 8px; font-weight: 800; letter-spacing: .16em; }
.waiting-card h2 { min-height: 52px; margin: 9px 0 0; font: 500 24px/1.45 var(--display); }
.waiting-text { margin: 10px 0 0; color: #b9cdc2; font-size: 11px; }
.waiting-dots { display: flex; justify-content: center; gap: 7px; margin: 18px 0 12px; }
.waiting-dots i { width: 7px; height: 7px; border-radius: 50%; background: #e1bd74; animation: wait-pulse 1.2s ease-in-out infinite; }
.waiting-dots i:nth-child(2) { animation-delay: .18s; }
.waiting-dots i:nth-child(3) { animation-delay: .36s; }
@keyframes wait-pulse { 0%, 100% { opacity: .25; transform: scale(.85); } 50% { opacity: 1; transform: scale(1.1); } }
.waiting-elapsed { color: #8ba798; font-size: 9px; }
.fade-enter-active, .fade-leave-active { transition: opacity .35s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* 黑夜模式：仅覆盖无法用公共 token 表达的浅色 */
html.dark .planner-landing { background: #121d17; }
html.dark .planning-composer { background: linear-gradient(145deg, #131e18, #101a15); }
html.dark .change-story { border-color: rgba(255, 255, 255, .09); }
html.dark .exploration-hint { border-color: rgba(217, 181, 106, .28); }
html.dark .exploration-hint b { color: #d9b56a; }
html.dark .exploration-hint p { color: #b7a888; }
html.dark .block-outline > span { color: #d9b56a; }
html.dark .block-outline > small { color: #a99a78; }
html.dark .block-outline i { color: #cbb183; background: rgba(255, 255, 255, .08); }
html.dark .chapter-sources small { color: var(--muted); background: rgba(255, 255, 255, .07); }
</style>
