<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, idempotencyKey } from '../api/http'

const router = useRouter()

type Direction = { id: number | string; name: string; currentStage?: string; primary?: boolean; source: 'PROFILE' | 'CATALOG'; custom?: boolean }
type ProfileView = {
  status: string
  currentVersionNo: number
  planStartDate: string
  planEndDate: string
  directions: Array<{ directionId?: number; name?: string; customDirection?: string; currentStage: string; primary: boolean }>
}
type CatalogDirection = { id: number; name: string; status: string; parent_id?: number; parentId?: number }
type Recommendation = {
  id: string
  directionId?: number
  customDirection?: string
  directionName: string
  name: string
  type: string
  description: string
  priority: string
  startDate: string
  dueDate: string
  weeklyBudgetMinutes: number
  successCriteria: Array<Record<string, any>>
  reason: string
  milestones: string[]
  source: 'AI' | 'RULE_FALLBACK'
}
type RecommendationResponse = {
  profileVersionId: string
  profileVersionNo: number
  generatedAt: string
  source: 'AI' | 'RULE_FALLBACK'
  recommendations: Recommendation[]
}

const tab = ref<'skill' | 'project' | 'exam'>('skill')
const goals = ref<any[]>([])
const projects = ref<any[]>([])
const projectMilestones = ref<Record<string, any[]>>({})
const projectGoalIds = ref<Record<string, string>>({})
const profile = ref<ProfileView | null>(null)
const directions = ref<Direction[]>([])
const profileDirections = ref<Direction[]>([])
const recommendations = ref<Recommendation[]>([])
const recommendationMeta = ref<RecommendationResponse | null>(null)
const recommending = ref(false)
const dialog = ref(false)
const loading = ref(false)
const completedCollapsed = ref(true)
const skillGoals = computed(() => goals.value.filter((g) => g.type !== 'PROJECT' && g.type !== 'EXAM'))
const projectGoals = computed(() => goals.value.filter((g) => g.type === 'PROJECT'))
const examGoals = computed(() => goals.value.filter((g) => g.type === 'EXAM'))
const groupedGoals = computed<Record<string, any[]>>(() => ({ skill: skillGoals.value, project: projectGoals.value, exam: examGoals.value }))
function activeOf(list: any[]) { return list.filter((g) => !['COMPLETED', 'CANCELED'].includes(g.status)) }
function completedOf(list: any[]) { return list.filter((g) => ['COMPLETED', 'CANCELED'].includes(g.status)) }
const activeGoalsByTab = computed(() => activeOf(groupedGoals.value[tab.value] || []))
const completedGoalsByTab = computed(() => completedOf(groupedGoals.value[tab.value] || []))
const activeCounts = computed(() => ({
  skill: activeOf(skillGoals.value).length,
  project: activeOf(projectGoals.value).length,
  exam: activeOf(examGoals.value).length,
}))
const emptyText = computed(() => tab.value === 'project' ? '创建第一个项目型目标' : tab.value === 'exam' ? '写下第一个考试目标' : '写下第一个技能目标')
const emptyHint = computed(() => tab.value === 'project' ? '把所学变成一件真实的作品' : tab.value === 'exam' ? '为一次考核做准备' : '从一个真正想发生的变化开始')
const form = reactive<any>({})
const canRecommendGoals = computed(() => profile.value?.status === 'GENERATED'
  && Boolean(profileDirections.value.length))
const recommendationOutdated = computed(() => Boolean(
  recommendationMeta.value && profile.value
  && recommendationMeta.value.profileVersionNo !== profile.value.currentVersionNo,
))

function resetForm(type?: string) {
  Object.assign(form, {
    directionId: directions.value[0]?.id,
    name: '', type: type ?? form.type ?? 'SKILL', description: '', priority: 'MEDIUM',
    startDate: dayjs().format('YYYY-MM-DD'),
    dueDate: dayjs().add(60, 'day').format('YYYY-MM-DD'),
    weeklyBudgetMinutes: 420,
    repositoryUrl: '',
    successCriteria: [{ type: 'OUTCOME', description: '完成目标验收', completed: false }],
    sourceType: 'CUSTOM', profileVersionId: undefined,
    recommendationId: '', recommendationReason: '',
    milestonesText: '明确需求与验收标准\n完成核心成果\n验收与复盘',
  })
}

async function load() {
  const [goalPage, projectPage, currentProfile, catalogDirections, latestRecommendation] = await Promise.all([
    api<any>({ url: '/goals', params: { pageSize: 100 } }),
    api<any>({ url: '/projects', params: { pageSize: 100 } }),
    api<ProfileView | null>({ url: '/profiles/me' }),
    api<CatalogDirection[]>({ url: '/learning-directions' }),
    api<RecommendationResponse | null>({ url: '/goals/recommendations/latest' }),
  ])
  goals.value = goalPage.items
  projects.value = projectPage.items
  const goalProjectEntries = await Promise.all(goals.value.map(async (goal:any) => [
    goal.publicId, await api<any[]>({ url:`/goals/${goal.publicId}/projects` }),
  ] as const))
  projectGoalIds.value = Object.fromEntries(goalProjectEntries.flatMap(([goalId, linkedProjects]) =>
    linkedProjects.map((project:any) => [project.publicId, goalId]),
  ))
  const milestoneEntries = await Promise.all(projects.value.map(async (project:any) => [
    project.publicId, await api<any[]>({ url:`/projects/${project.publicId}/milestones` }),
  ] as const))
  projectMilestones.value = Object.fromEntries(milestoneEntries)
  profile.value = currentProfile
  const currentDirections: Direction[] = []
  for (const item of currentProfile?.directions || []) {
    if (item.directionId && item.name) {
      currentDirections.push({ id: Number(item.directionId), name: item.name, currentStage: item.currentStage, primary: item.primary, source: 'PROFILE' })
    } else if (item.customDirection?.trim()) {
      const custom = item.customDirection.trim()
      currentDirections.push({ id: `custom:${custom}`, name: custom, currentStage: item.currentStage, primary: item.primary, source: 'PROFILE', custom: true })
    }
  }
  profileDirections.value = currentDirections
  const activeCatalog = (catalogDirections || [])
    .filter((item) => item.status === 'ACTIVE')
    .map((item) => ({ id: Number(item.id), name: item.name, source: 'CATALOG' as const }))
  directions.value = profileDirections.value.length ? profileDirections.value : activeCatalog
  recommendationMeta.value = latestRecommendation
  recommendations.value = latestRecommendation?.recommendations || []
}
onMounted(load)

function create(type?: string) {
  resetForm(type ?? (tab.value === 'project' ? 'PROJECT' : tab.value === 'exam' ? 'EXAM' : 'SKILL'))
  dialog.value = true
}

async function recommendGoals() {
  recommending.value = true
  try {
    const result = await api<RecommendationResponse>({
      method: 'POST', url: '/goals/recommendations', data: { count: 3 },
    })
    recommendationMeta.value = result
    recommendations.value = result.recommendations
  } catch (e) {
    // AI 不可用时全局拦截器已弹出错误提示，不渲染兜底推荐
  } finally { recommending.value = false }
}

function useRecommendation(item: Recommendation) {
  resetForm(item.type)
  Object.assign(form, {
    directionId: item.directionId ?? `custom:${item.customDirection}`,
    name: item.name,
    type: item.type,
    description: item.description,
    priority: item.priority,
    startDate: item.startDate,
    dueDate: item.dueDate,
    weeklyBudgetMinutes: item.weeklyBudgetMinutes,
    successCriteria: item.successCriteria,
    sourceType: item.source === 'AI' ? 'AI_RECOMMENDED' : 'RULE_RECOMMENDED',
    profileVersionId: recommendationMeta.value?.profileVersionId,
    recommendationId: item.id,
    recommendationReason: item.reason,
    milestonesText: item.type === 'PROJECT' ? (item.milestones || []).join('\n') : form.milestonesText,
  })
  dialog.value = true
}

async function save() {
  if (!String(form.name || '').trim()) return void ElMessage.warning('请填写名称')
  if (!form.directionId || !String(form.directionId).trim()) return void ElMessage.warning('请选择或输入学习方向')
  if (!form.startDate || !form.dueDate || dayjs(form.dueDate).isBefore(dayjs(form.startDate))) {
    return void ElMessage.warning('截止日期不能早于开始日期')
  }
  const projectMilestones = String(form.milestonesText || '').split(/\r?\n/).map((value) => value.trim()).filter(Boolean)
  if (form.type === 'PROJECT' && !projectMilestones.length) {
    return void ElMessage.warning('项目型目标必须填写至少一个里程碑')
  }
  const isFirstGoal = goals.value.length === 0
  let firstCreatedGoal: any
  loading.value = true
  try {
    const selected = form.directionId
    const directionId = typeof selected === 'number' ? selected : undefined
    const customDirection = directionId === undefined
      ? String(selected).replace(/^custom:/, '').trim()
      : undefined
    const created = await api<any>({
      method: 'POST', url: '/goals',
      data: {
        directionId, customDirection, name: form.name.trim(), type: form.type,
        description: form.description, priority: form.priority, startDate: form.startDate,
        dueDate: form.dueDate, weeklyBudgetMinutes: Number(form.weeklyBudgetMinutes),
        successCriteria: form.successCriteria,
        sourceType: form.sourceType, profileVersionId: form.profileVersionId,
        recommendationId: form.recommendationId, recommendationReason: form.recommendationReason,
        milestones: form.type === 'PROJECT' ? projectMilestones : undefined,
        repositoryUrl: form.type === 'PROJECT' ? form.repositoryUrl || undefined : undefined,
      },
    })
    if (isFirstGoal) firstCreatedGoal = created.goal
    if (form.type === 'PROJECT') tab.value = 'project'
    else if (form.type === 'EXAM') tab.value = 'exam'
    else tab.value = 'skill'
    dialog.value = false
    ElMessage.success('创建成功')
    await load()
  } finally { loading.value = false }
  if (firstCreatedGoal) await showFirstGoalActivationGuide(firstCreatedGoal)
}

async function completeMilestone(project:any, milestone:any) {
  const evidence = await ElMessageBox.prompt('写下用于验收这个里程碑的成果或证据', '完成里程碑', {
    inputType:'textarea', inputValidator:(value)=>Boolean(value?.trim()) || '请填写验收证据',
  }).then(result=>result.value).catch(()=>null)
  if (!evidence) return
  await api({ method:'POST', url:`/milestones/${milestone.publicId}/completion`, data:{ summary:evidence, allConfirmed:true } })
  ElMessage.success('里程碑已完成，关联目标进度已同步更新')
  await load()
}

async function cancelMilestone(milestone:any) {
  await ElMessageBox.confirm('取消后该里程碑不再计入项目权重，是否继续？','取消里程碑',{type:'warning'})
  await api({ method:'POST', url:`/milestones/${milestone.publicId}/cancellation` })
  await load()
}

async function showFirstGoalActivationGuide(goal: any) {
  try {
    await ElMessageBox.confirm(
      '第一个目标已经保存为草稿。Agent 只会为“正在推进”的目标生成计划；你需要先推进目标，Agent 才能读取目标、画像和可用时间进行规划。',
      '还差一步：推进目标',
      {
        confirmButtonText: '推进并去 Agent 规划',
        cancelButtonText: '先保留草稿',
        distinguishCancelAndClose: true,
        closeOnClickModal: false,
        type: 'info',
      },
    )
  } catch {
    return
  }
  await api({
    method: 'POST',
    url: `/goals/${goal.publicId}/activation`,
    data: { reason: '首次创建目标后由用户确认推进', exceptionConfirmed: false },
  })
  ElMessage.success('目标已开始推进，Agent 正在生成分块学习方案')
  await load()
  await createInitialPlan(goal.publicId)
}

async function createInitialPlan(goalPublicId: string) {
  try {
    await api<any>({
      method: 'POST',
      url: `/goals/${goalPublicId}/planning-jobs`,
      headers: { 'Idempotency-Key': idempotencyKey() },
      data: {
        type: 'INITIAL',
        userRequirement: '按独立知识块规划；每块包含资料、练习和通过测试。预计时长仅作可用时间参考。',
        knowledgeSpaceIds: [],
      },
    })
    ElMessage.success('规划请求已提交，Agent 正在后台生成可审阅方案')
  } finally {
    await router.push(`/plans/${goalPublicId}`)
  }
}

async function action(item: any, value: string) {
  let body: any = { reason: `用户执行${value}`, exceptionConfirmed: false }
  if (value === 'completion') {
    const incomplete = goalCriteria(item).filter((criterion:any) => !criterion.completed)
    if (incomplete.length) {
      try {
        await ElMessageBox.confirm(
          `还有 ${incomplete.length} 项成功标准未确认。是否按例外方式完成目标并保留记录？`,
          '成功标准尚未全部满足',
          { type:'warning', confirmButtonText:'确认例外完成', cancelButtonText:'返回检查标准' },
        )
        body.exceptionConfirmed = true
      } catch { return }
    }
  }
  if (['completion', 'cancellation'].includes(value)) {
    try {
      body.reason = await ElMessageBox.prompt('请填写这次状态变化的原因', '记录一个决定', {
        inputValidator: (text) => Boolean(text) || '原因不能为空',
      }).then((result) => result.value)
    } catch { return }
  }
  await api({ method: 'POST', url: `/goals/${item.publicId}/${value}`, data: body })
  if (value === 'activation') {
    ElMessage.success('目标已启动，Agent 正在规划知识块')
    await load()
    await createInitialPlan(item.publicId)
    return
  }
  ElMessage.success('状态已更新')
  await load()
}

async function projectAction(project: any, value: string) {
  let body: any = { reason: `用户执行${value}`, exceptionConfirmed: false }
  if (['completion', 'cancellation'].includes(value)) {
    try {
      body.reason = await ElMessageBox.prompt('请填写这次状态变化的原因', '记录一个决定', {
        inputValidator: (text) => Boolean(text) || '原因不能为空',
      }).then((result) => result.value)
    } catch { return }
  }
  await api({ method: 'POST', url: `/projects/${project.publicId}/${value}`, data: body })
  ElMessage.success('状态已更新')
  await load()
}

function projectsForGoal(goal: any) {
  return projects.value.filter((project:any) => projectGoalIds.value[project.publicId] === goal.publicId)
}

function statusText(status: string) {
  return ({ DRAFT: '尚未启程', ACTIVE: '正在推进', PAUSED: '暂时停靠', COMPLETED: '已经抵达', CANCELED: '已结束' } as Record<string, string>)[status] || status
}
function goalCriteria(goal:any):any[] {
  try { return JSON.parse(goal.successCriteriaJson || '[]') } catch { return [] }
}
async function toggleCriterion(goal:any, index:number, completed:boolean) {
  await api<any>({
    method:'PATCH', url:`/goals/${goal.publicId}/success-criteria/${index}`,
    data:{ completed, version:goal.version },
  })
  ElMessage.success(completed ? '成功标准已确认' : '成功标准已恢复为待确认')
  await load()
}
function stageText(stage: string) {
  return ({ BEGINNER: '入门阶段', INTERMEDIATE: '进阶阶段', ADVANCED: '高级阶段' } as Record<string, string>)[stage] || stage
}
function goalSourceText(goal: any) {
  if (!['AI_RECOMMENDED', 'RULE_RECOMMENDED'].includes(goal.sourceType)) return '自定义'
  let version = ''
  try { version = JSON.parse(goal.recommendationSnapshotJson || '{}').profileVersionNo || '' } catch { /* 兼容旧数据 */ }
  const source = goal.sourceType === 'AI_RECOMMENDED' ? 'AI 推荐' : '画像建议'
  return version ? `${source} · 画像 v${version}` : source
}
</script>

<template>
  <div class="goal-page">
    <section class="goal-hero">
      <div>
        <span class="eyebrow light">DIRECTION BEFORE SPEED</span>
        <h1>先选择值得抵达的地方，<br>再让每一天有方向。</h1>
        <p>目标定义成长的方向，项目把知识变成可以被看见的成果。</p>
      </div>
      <div class="goal-totals">
        <div><strong>{{ activeCounts.skill }}</strong><small>技能</small></div>
        <i />
        <div><strong>{{ activeCounts.project }}</strong><small>项目</small></div>
        <i />
        <div><strong>{{ activeCounts.exam }}</strong><small>考试</small></div>
      </div>
    </section>

    <div class="collection-bar">
      <div class="collection-switch">
        <button :class="{ active: tab === 'skill' }" @click="tab = 'skill'"><span>技能</span><small>{{ skillGoals.length }}</small></button>
        <button :class="{ active: tab === 'project' }" @click="tab = 'project'"><span>项目</span><small>{{ projectGoals.length }}</small></button>
        <button :class="{ active: tab === 'exam' }" @click="tab = 'exam'"><span>考试</span><small>{{ examGoals.length }}</small></button>
      </div>
      <button class="new-direction" @click="create()"><span>＋</span> 新建目标</button>
    </div>

    <section class="recommendation-board">
      <div class="recommendation-heading">
        <div>
          <span class="eyebrow">PROFILE-GROUNDED GOALS</span>
          <h2>基于当前画像推荐目标</h2>
          <p v-if="profile?.status === 'GENERATED' && profileDirections.length">
            画像 v{{ profile.currentVersionNo }} ·
            {{ profileDirections.map((item) => `${item.name} / ${stageText(item.currentStage || '')}`).join('，') }}
          </p>
          <p v-else-if="profile && profileDirections.length">画像有尚未固化的修改，请先在画像页生成新版本。</p>
          <p v-else>可以直接创建任意方向的自定义目标；AI 推荐需要先完成画像。</p>
        </div>
        <button v-if="canRecommendGoals" class="recommend-trigger" :disabled="recommending" @click="recommendGoals">
          {{ recommending ? '正在分析画像…' : recommendations.length ? '重新让 AI 推荐' : '让 AI 推荐' }}
        </button>
        <button v-else class="recommend-trigger" @click="$router.push('/onboarding')">去完善画像</button>
      </div>

      <div v-if="recommendationMeta && !recommending" class="recommendation-saved">
        <span>已保存 {{ dayjs(recommendationMeta.generatedAt).format('YYYY-MM-DD HH:mm') }} 的推荐结果</span>
        <small v-if="recommendationOutdated">当前画像已经更新；这些候选仍会保留，点击“重新让 AI 推荐”才会生成新结果。</small>
        <small v-else>刷新或重新进入页面仍会显示；只有点击“重新让 AI 推荐”才会调用模型。</small>
      </div>

      <div v-if="recommending" class="recommendation-loading">
        <span v-for="item in 3" :key="item"><i /><i /><i /></span>
      </div>
      <div v-else-if="recommendations.length" class="recommendation-list">
        <article v-for="item in recommendations" :key="item.id" class="recommendation-card">
          <div class="recommendation-card-top">
            <span>{{ item.directionName }} · {{ item.type }}</span>
            <em>{{ item.source === 'AI' ? 'AI 推荐' : '规则建议' }}</em>
          </div>
          <h3>{{ item.name }}</h3>
          <p>{{ item.reason }}</p>
          <div class="recommendation-metrics">
            <span><small>周期</small><b>{{ item.startDate }} 至 {{ item.dueDate }}</b></span>
            <span><small>每周投入</small><b>{{ item.weeklyBudgetMinutes }} 分钟</b></span>
          </div>
          <div class="recommendation-milestones">
            <span v-for="milestone in item.milestones" :key="milestone">{{ milestone }}</span>
          </div>
          <button class="adopt-goal" @click="useRecommendation(item)">查看并采用这个目标 <b>→</b></button>
        </article>
      </div>
      <div v-else class="recommendation-empty">
        <span>画像提供边界，AI 提供候选，你负责最后确认。</span>
        <small>推荐不会直接写入目标，也不会自动激活或生成计划。</small>
      </div>
    </section>

    <section class="goal-gallery">
      <article v-for="(goal, index) in activeGoalsByTab" :key="goal.publicId" class="direction-card" :class="[`status-${goal.status?.toLowerCase()}`, { featured: index === 0 }]">
        <div class="card-top"><span>{{ String(index + 1).padStart(2, '0') }} / {{ goal.type }} · {{ goalSourceText(goal) }}</span><em>{{ statusText(goal.status) }}</em></div>
        <div class="card-copy"><h2>{{ goal.name }}</h2><p>{{ goal.description || '还没有写下说明。也许可以补充：为什么这件事对你重要？' }}</p></div>
        <div class="goal-horizon"><i /><span>{{ goal.startDate }}</span><b>→</b><span>{{ goal.dueDate }}</span></div>
        <div class="card-facts"><span><small>每周投入</small><b>{{ goal.weeklyBudgetMinutes }} 分钟</b></span><span><small>优先级</small><b>{{ goal.priority || 'MEDIUM' }}</b></span></div>
        <div v-if="goalCriteria(goal).length" class="goal-criteria">
          <span>成功标准</span>
          <el-checkbox v-for="(criterion, criterionIndex) in goalCriteria(goal)" :key="`${goal.publicId}-${criterionIndex}`"
            :model-value="Boolean(criterion.completed)" :disabled="['COMPLETED','CANCELED'].includes(goal.status)"
            @change="(value:boolean)=>toggleCriterion(goal,criterionIndex,value)">
            {{ criterion.description || criterion.name || `标准 ${criterionIndex + 1}` }}
          </el-checkbox>
        </div>
        <template v-if="goal.type === 'PROJECT'">
          <template v-for="project in projectsForGoal(goal)" :key="project.publicId">
            <div class="project-mark"><span>实践</span><i>→</i><span>反馈</span><i>→</i><span>成果</span></div>
            <div class="project-milestones">
              <div v-for="milestone in projectMilestones[project.publicId] || []" :key="milestone.publicId" :class="`milestone-${milestone.status?.toLowerCase()}`">
                <span><b>{{ milestone.sequenceNo }}. {{ milestone.name }}</b><small>{{ milestone.dueDate }} · {{ Math.round(Number(milestone.weight)*100) }}%</small></span>
                <button v-if="project.status === 'ACTIVE' && milestone.status !== 'COMPLETED'" @click="completeMilestone(project,milestone)">完成</button>
                <button v-if="project.status === 'DRAFT' && milestone.status !== 'CANCELED'" @click="cancelMilestone(milestone)">取消</button>
              </div>
            </div>
            <div class="project-inline-actions">
              <button v-if="project.status === 'DRAFT' && goal.status !== 'DRAFT'" @click="projectAction(project, 'activation')">开始项目</button>
              <button class="quiet-action" @click="$router.push({path:`/plans/${goal.publicId}`,query:{project:project.publicId}})">按项目规划 ↗</button>
            </div>
          </template>
          <div v-if="!projectsForGoal(goal).length" class="project-inline-empty">该目标暂无配套项目，可在创建时填写里程碑。</div>
        </template>
        <div class="card-actions">
          <button v-if="goal.status === 'DRAFT'" @click="action(goal, 'activation')">开始推进</button>
          <button v-if="goal.status === 'ACTIVE'" @click="action(goal, 'pause')">暂停</button>
          <button v-if="goal.status === 'PAUSED'" @click="action(goal, 'resume')">重新开始</button>
          <button v-if="goal.status === 'ACTIVE'" @click="action(goal, 'completion')">标记抵达</button>
          <button class="quiet-action" @click="$router.push(`/plans/${goal.publicId}`)">交给 Agent 规划 ↗</button>
          <button v-if="!['COMPLETED', 'CANCELED'].includes(goal.status)" class="danger-action" @click="action(goal, 'cancellation')">结束目标</button>
        </div>
      </article>
      <button v-if="!activeGoalsByTab.length" class="empty-collection" @click="create()"><span>＋</span><b>{{ emptyText }}</b><small>{{ emptyHint }}</small></button>
      <div v-if="completedGoalsByTab.length" class="completed-fold">
        <button class="fold-toggle" @click="completedCollapsed = !completedCollapsed"><span>已完成 / 已结束</span><small>{{ completedGoalsByTab.length }}</small><i>{{ completedCollapsed ? '展开' : '收起' }}</i></button>
        <div v-show="!completedCollapsed" class="fold-body">
          <article v-for="goal in completedGoalsByTab" :key="goal.publicId" class="direction-card compact" :class="`status-${goal.status?.toLowerCase()}`">
            <div class="card-top"><span>{{ goal.type }} · {{ goalSourceText(goal) }}</span><em>{{ statusText(goal.status) }}</em></div>
            <div class="card-copy"><h2>{{ goal.name }}</h2><p>{{ goal.description || '—' }}</p></div>
            <div class="goal-horizon"><i /><span>{{ goal.startDate }}</span><b>→</b><span>{{ goal.dueDate }}</span></div>
          </article>
        </div>
      </div>
    </section>

    <el-dialog v-model="dialog" :title="form.sourceType === 'CUSTOM' ? '定义一个新目标' : '确认画像推荐目标'" width="620">
      <el-alert v-if="form.sourceType !== 'CUSTOM'" class="recommendation-alert" type="success" :closable="false">
        <template #title>基于画像 v{{ recommendationMeta?.profileVersionNo }} 生成，保存前仍可修改</template>
        {{ form.recommendationReason }}
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="120" /></el-form-item>
        <el-form-item label="学习方向">
          <el-select v-model="form.directionId" class="full" filterable allow-create default-first-option placeholder="选择目录方向，或输入任意学习方向">
            <el-option v-for="direction in directions" :key="direction.id" :value="direction.id" :label="direction.source === 'PROFILE' ? `${direction.name}（画像${direction.custom ? '·自定义' : ''}）` : direction.name" />
          </el-select>
          <small class="direction-help">目录之外的课程也可以直接输入；AI 会按自定义方向生成目标和计划。</small>
        </el-form-item>
        <div class="grid grid-2">
          <el-form-item label="目标类型"><el-select v-model="form.type" class="full"><el-option value="SKILL" label="技能" /><el-option value="PROJECT" label="项目" /><el-option value="EXAM" label="考试" /></el-select></el-form-item>
          <el-form-item label="优先级"><el-select v-model="form.priority" class="full"><el-option value="LOW" /><el-option value="MEDIUM" /><el-option value="HIGH" /><el-option value="URGENT" /></el-select></el-form-item>
          <el-form-item label="开始日期"><el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" class="full" /></el-form-item>
          <el-form-item label="截止日期"><el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" class="full" /></el-form-item>
        </div>
        <el-form-item label="每周预算（分钟）"><el-input-number v-model="form.weeklyBudgetMinutes" :min="10" :max="6720" /></el-form-item>
        <template v-if="form.type === 'PROJECT'">
          <el-form-item label="代码仓库"><el-input v-model="form.repositoryUrl" placeholder="可选，https 仓库链接" /></el-form-item>
          <el-form-item label="项目里程碑（每行一个）">
            <el-input v-model="form.milestonesText" type="textarea" :rows="4" maxlength="1000" />
            <small class="direction-help">创建时会自动分配日期和 100% 权重，启动后锁定结构；里程碑验收会计入掌握度证据。</small>
          </el-form-item>
        </template>
        <el-form-item label="为什么要做这件事？"><el-input v-model="form.description" type="textarea" :rows="4" /></el-form-item>
        <el-form-item v-if="form.successCriteria?.length" label="成功标准">
          <div class="criteria-preview"><span v-for="criterion in form.successCriteria" :key="criterion.description">{{ criterion.description }}</span></div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="loading" @click="save">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.goal-page { display: grid; gap: 23px; }
.direction-help { display: block; margin-top: 6px; color: var(--muted); font-size: 10px; line-height: 1.6; }
.goal-hero { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 30px; overflow: hidden; padding: 22px clamp(26px, 4vw, 44px); border-radius: 28px; color: #eef4ef; background: radial-gradient(circle at 84% 8%, rgba(115, 174, 142, .32), transparent 30%), linear-gradient(135deg, #0e2e24 0%, #174737 62%, #245c47 100%); box-shadow: 0 22px 60px rgba(20, 59, 45, .2); }
.goal-hero::after { position: absolute; right: -70px; bottom: -150px; width: 320px; height: 320px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 42px rgba(255, 255, 255, .022), 0 0 0 84px rgba(255, 255, 255, .015); content: ""; }
.goal-hero > div { position: relative; z-index: 1; }
.goal-hero h1 { margin: 9px 0 7px; font: 500 clamp(23px, 2.6vw, 30px)/1.3 var(--display); letter-spacing: -.02em; }
.goal-hero p { margin: 0; color: #bfd0c8; font-size: 11px; }
.goal-totals { display: flex; align-items: center; gap: 20px; min-width: 220px; padding: 12px 18px; border: 1px solid rgba(255, 255, 255, .1); border-radius: 18px; background: rgba(255, 255, 255, .06); backdrop-filter: blur(12px); }
.goal-totals > div { text-align: center; }
.goal-totals strong, .goal-totals small { display: block; }
.goal-totals strong { font: 500 24px var(--display); }
.goal-totals small { margin-top: 3px; color: #a8c0b4; font-size: 8px; }
.goal-totals i { width: 1px; height: 32px; background: rgba(255, 255, 255, .11); }
.collection-bar { display: flex; align-items: center; justify-content: space-between; padding: 0 4px; }
.collection-switch { display: flex; gap: 4px; padding: 5px; border-radius: 16px; background: rgba(222, 230, 222, .7); }
.collection-switch button { display: flex; align-items: center; gap: 9px; min-width: 105px; padding: 10px 14px; border: 0; border-radius: 12px; color: var(--muted); background: transparent; font-size: 11px; font-weight: 700; }
.collection-switch button.active { color: var(--green); background: rgba(255, 255, 255, .9); box-shadow: 0 8px 20px rgba(33, 58, 48, .08); }
.collection-switch small { display: grid; place-items: center; min-width: 19px; height: 19px; padding: 0 4px; border-radius: 99px; background: var(--chip); font-size: 8px; }
.new-direction { display: flex; align-items: center; gap: 8px; padding: 7px 15px 7px 7px; border: 1px solid rgba(28, 88, 63, .13); border-radius: 99px; color: var(--green); background: rgba(255, 255, 255, .7); font-size: 10px; font-weight: 700; }
.new-direction span { display: grid; place-items: center; width: 27px; height: 27px; border-radius: 50%; color: #fff; background: var(--green); font-size: 16px; }
.recommendation-board { overflow: hidden; padding: clamp(24px, 4vw, 38px); border: 1px solid rgba(41, 91, 68, .1); border-radius: 28px; background: linear-gradient(135deg, rgba(249, 252, 247, .92), rgba(233, 242, 232, .72)); box-shadow: 0 18px 48px rgba(35, 70, 53, .08); }
.recommendation-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
.recommendation-heading h2 { margin: 8px 0 5px; color: var(--ink); font: 500 clamp(24px, 3vw, 34px)/1.2 var(--display); }
.recommendation-heading p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.7; }
.recommend-trigger { flex: 0 0 auto; padding: 11px 17px; border: 0; border-radius: 99px; color: #fff; background: var(--green); box-shadow: 0 10px 24px rgba(23, 71, 50, .17); font-size: 10px; font-weight: 800; }
.recommend-trigger:disabled { cursor: wait; opacity: .65; }
.recommendation-saved { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 18px; padding: 10px 13px; border-radius: 12px; color: #426957; background: rgba(255, 255, 255, .58); font-size: 8px; }
.recommendation-saved span { font-weight: 800; }
.recommendation-saved small { color: var(--muted); text-align: right; }
.recommendation-list, .recommendation-loading { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 13px; margin-top: 24px; }
.recommendation-card { display: flex; flex-direction: column; min-height: 310px; padding: 22px; border: 1px solid rgba(255, 255, 255, .9); border-radius: 21px; background: var(--card); box-shadow: 0 13px 30px rgba(39, 73, 57, .065); }
.recommendation-card-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--muted); font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.recommendation-card-top em { padding: 4px 7px; border-radius: 99px; color: #276448; background: var(--chip); font-style: normal; letter-spacing: 0; }
.recommendation-card h3 { margin: 20px 0 8px; color: var(--ink); font: 500 21px/1.35 var(--display); }
.recommendation-card > p { flex: 1; margin: 0; color: var(--muted); font-size: 9px; line-height: 1.75; }
.recommendation-metrics { display: grid; grid-template-columns: 1.35fr .65fr; gap: 8px; margin: 18px 0 13px; }
.recommendation-metrics span { padding: 10px; border-radius: 11px; background: var(--chip); }
.recommendation-metrics small, .recommendation-metrics b { display: block; }
.recommendation-metrics small { color: var(--muted); font-size: 7px; }
.recommendation-metrics b { margin-top: 4px; color: #345b49; font-size: 8px; }
.recommendation-milestones { display: flex; flex-wrap: wrap; gap: 5px; }
.recommendation-milestones span, .criteria-preview span { padding: 5px 8px; border-radius: 8px; color: #557163; background: var(--chip); font-size: 8px; }
.adopt-goal { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; padding: 11px 13px; border: 0; border-radius: 11px; color: #fff; background: #1b513d; font-size: 9px; font-weight: 800; }
.adopt-goal b { font-size: 14px; }
.recommendation-empty { display: flex; align-items: center; flex-direction: column; margin-top: 23px; padding: 27px; border: 1px dashed rgba(40, 91, 67, .17); border-radius: 17px; color: #365e4b; text-align: center; }
.recommendation-empty small { margin-top: 5px; color: var(--muted); font-size: 8px; }
.recommendation-loading > span { display: grid; gap: 13px; min-height: 230px; padding: 22px; border-radius: 21px; background: rgba(255, 255, 255, .66); }
.recommendation-loading i { height: 15px; border-radius: 99px; background: linear-gradient(90deg, #e8eee7 25%, #f6f9f5 50%, #e8eee7 75%); background-size: 200% 100%; animation: goal-shimmer 1.3s infinite; }
.recommendation-loading i:nth-child(2) { height: 70px; }
.recommendation-alert { margin-bottom: 18px; }
.criteria-preview { display: flex; flex-wrap: wrap; gap: 7px; width: 100%; }
@keyframes goal-shimmer { to { background-position: -200% 0; } }
.goal-gallery { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 19px; align-items: start; }
.direction-card { position: relative; display: flex; flex-direction: column; min-height: 300px; overflow: hidden; padding: 24px 26px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 27px; background: var(--paper-soft); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.direction-card.featured { color: #edf4ee; background: radial-gradient(circle at 90% 10%, rgba(105, 165, 134, .24), transparent 25%), linear-gradient(145deg, #1a4536, #112f26); box-shadow: 0 23px 58px rgba(20, 55, 42, .18); }
.card-top { display: flex; align-items: center; justify-content: space-between; }
.card-top > span { color: var(--muted); font-size: 8px; font-weight: 800; letter-spacing: .14em; }
.card-top em { padding: 5px 9px; border-radius: 99px; color: var(--green); background: var(--mint); font-size: 8px; font-style: normal; font-weight: 700; }
.featured .card-top > span { color: #91ad9f; }
.featured .card-top em { color: #dfc27d; background: rgba(221, 189, 118, .12); }
.card-copy { flex: 1; padding: 20px 0 14px; }
.card-copy h2 { margin: 0; font: 500 25px/1.28 var(--display); }
.card-copy p { max-width: 560px; margin: 8px 0 0; color: var(--muted); font-size: 10px; line-height: 1.75; }
.featured .card-copy p { color: #a8beb3; }
.goal-horizon { display: grid; grid-template-columns: 1fr auto auto auto; align-items: center; gap: 9px; margin: 2px 0 14px; }
.goal-horizon i { height: 2px; border-radius: 99px; background: linear-gradient(90deg, var(--green) 0 64%, var(--chip) 64%); }
.goal-horizon span { color: var(--muted); font: 500 8px ui-monospace, monospace; }
.goal-horizon b { color: #a2aaa5; font-size: 9px; }
.featured .goal-horizon i { background: linear-gradient(90deg, #dfbe78 0 64%, rgba(255, 255, 255, .11) 64%); }
.featured .goal-horizon span { color: #96aea2; }
.card-facts { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.card-facts > span { padding: 12px 0; }
.card-facts > span + span { padding-left: 18px; border-left: 1px solid var(--line); }
.card-facts small, .card-facts b { display: block; }
.card-facts small { color: var(--muted); font-size: 8px; }
.card-facts b { margin-top: 5px; font-size: 9px; }
.featured .card-facts { border-color: rgba(255, 255, 255, .09); }
.featured .card-facts > span + span { border-color: rgba(255, 255, 255, .09); }
.featured .card-facts small { color: #85a093; }
.card-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 14px; }
.card-actions button { padding: 7px 10px; border: 1px solid rgba(30, 86, 62, .12); border-radius: 9px; color: var(--green); background: var(--chip); font-size: 8px; font-weight: 700; }
.card-actions .quiet-action { margin-left: auto; border-color: transparent; background: transparent; }
.card-actions .danger-action { border-color: rgba(181, 77, 69, .09); color: #a94e47; background: #f3e4e2; }
.featured .card-actions button { border-color: rgba(255, 255, 255, .09); color: #dce9e2; background: rgba(255, 255, 255, .07); }
.featured .card-actions .quiet-action { color: #e0c17b; background: transparent; }
.featured .card-actions .danger-action { color: #d59a94; }
.project-mark { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 21px; padding: 13px 15px; border-radius: 13px; color: var(--muted); background: var(--el-fill-color-light); font-size: 9px; }
.goal-criteria{display:grid;gap:6px;margin:0 0 16px;padding:12px;border-radius:12px;background:var(--chip)}.goal-criteria>span{font-size:8px;font-weight:800;letter-spacing:.12em;color:var(--muted)}.goal-criteria :deep(.el-checkbox){height:auto;margin-right:0}.goal-criteria :deep(.el-checkbox__label){white-space:normal;font-size:10px;color:var(--ink)}
.project-milestones{display:grid;gap:6px;margin:-10px 0 18px}.project-milestones>div{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 11px;border-radius:11px;background:var(--chip)}.project-milestones span,.project-milestones b,.project-milestones small{display:block}.project-milestones b{font-size:9px}.project-milestones small{margin-top:3px;color:var(--muted);font-size:8px}.project-milestones button{border:0;color:var(--green);background:transparent;font-size:9px;font-weight:700}.project-milestones .milestone-completed{opacity:.65}.project-milestones .milestone-completed b{text-decoration:line-through}
.project-inline-actions{display:flex;flex-wrap:wrap;gap:7px;margin:-12px 0 18px;justify-content:flex-end}.project-inline-actions button{padding:7px 10px;border:1px solid rgba(30,86,62,.12);border-radius:9px;color:var(--green);background:var(--chip);font-size:8px;font-weight:700}.project-inline-actions .quiet-action{margin-left:auto;border-color:transparent;background:transparent}.project-inline-empty{margin:-10px 0 18px;padding:10px 12px;border:1px dashed rgba(31,88,64,.16);border-radius:11px;color:var(--muted);font-size:9px;text-align:center}
.featured .project-mark { color: #a7beb2; background: rgba(255, 255, 255, .055); }
.project-mark i { color: var(--gold); font-style: normal; }
.empty-collection { display: flex; align-items: center; justify-content: center; flex-direction: column; min-height: 300px; border: 1px dashed rgba(31, 88, 64, .18); border-radius: 27px; color: var(--green); background: rgba(249, 251, 247, .45); }
.empty-collection > span { display: grid; place-items: center; width: 58px; height: 58px; margin-bottom: 16px; border-radius: 50%; background: var(--chip); font-size: 24px; }
.empty-collection b, .empty-collection small { display: block; }
.empty-collection b { font: 500 18px var(--display); }
.empty-collection small { margin-top: 6px; color: var(--muted); font-size: 9px; }
.completed-fold { grid-column: 1 / -1; margin-top: 19px; }
.fold-toggle { display: flex; align-items: center; gap: 10px; width: 100%; padding: 14px 20px; border: 1px solid var(--line); border-radius: 16px; background: var(--paper-soft); color: var(--muted); font-size: 11px; font-weight: 700; cursor: pointer; transition: background .15s; }
.fold-toggle:hover { background: var(--el-fill-color-light); }
.fold-toggle small { display: grid; place-items: center; min-width: 20px; height: 20px; padding: 0 5px; border-radius: 99px; background: var(--chip); color: var(--green); font-size: 9px; }
.fold-toggle i { margin-left: auto; font-style: normal; font-size: 10px; }
.fold-body { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 19px; margin-top: 12px; }
.direction-card.compact { min-height: auto; padding: 20px; }
.direction-card.compact .card-copy { padding: 12px 0 8px; }
.direction-card.compact .card-copy h2 { font-size: 20px; }
.direction-card.compact .card-copy p { font-size: 9px; }

@media (max-width: 850px) {
  .goal-hero { grid-template-columns: 1fr; }
  .goal-totals { justify-self: start; }
  .goal-gallery { grid-template-columns: 1fr; }
  .recommendation-list, .recommendation-loading { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .goal-hero { padding: 24px 20px; border-radius: 25px; }
  .goal-hero h1 { font-size: 28px; }
  .collection-bar { align-items: flex-start; flex-direction: column; gap: 12px; }
  .collection-switch { width: 100%; }
  .collection-switch button { flex: 1; justify-content: center; }
  .recommendation-heading { align-items: flex-start; flex-direction: column; }
  .recommendation-saved { align-items: flex-start; flex-direction: column; }
  .recommendation-saved small { text-align: left; }
  .recommend-trigger { width: 100%; }
  .direction-card { min-height: 290px; padding: 20px; border-radius: 22px; }
  .card-actions .quiet-action { margin-left: 0; }
}

/* 黑夜模式：scoped 覆盖（无法用 token 表达的暗色规则） */
html.dark .collection-switch { background: rgba(255, 255, 255, .06); }
html.dark .collection-switch button.active { background: rgba(255, 255, 255, .08); }
html.dark .new-direction { background: rgba(255, 255, 255, .07); }
html.dark .recommendation-board { background: linear-gradient(135deg, rgba(24, 37, 31, .92), rgba(30, 52, 42, .78)); }
html.dark .recommendation-saved { color: var(--green); background: rgba(255, 255, 255, .06); }
html.dark .recommendation-card { border-color: rgba(255, 255, 255, .08); }
html.dark .recommendation-card-top em { color: var(--green); }
html.dark .recommendation-metrics b { color: var(--ink); }
html.dark .recommendation-milestones span,
html.dark .criteria-preview span { color: var(--ink); }
html.dark .recommendation-empty { color: var(--ink); }
html.dark .recommendation-loading > span { background: rgba(255, 255, 255, .06); }
html.dark .recommendation-loading i { background: linear-gradient(90deg, rgba(255, 255, 255, .05) 25%, rgba(255, 255, 255, .1) 50%, rgba(255, 255, 255, .05) 75%); }
html.dark .direction-card { border-color: rgba(255, 255, 255, .08); }
html.dark .card-actions .danger-action { color: var(--red); background: rgba(217, 124, 116, .13); }
html.dark .featured .card-actions .danger-action { color: #d59a94; background: rgba(255, 255, 255, .07); }
html.dark .goal-horizon b { color: var(--muted); }
html.dark .empty-collection { background: var(--paper-soft); }
</style>
