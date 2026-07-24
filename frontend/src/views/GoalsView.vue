<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/http'

type Direction = { id: number; name: string; currentStage?: string; primary?: boolean; source: 'PROFILE' | 'CATALOG' }
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
  directionId: number
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

const tab = ref('goals')
const goals = ref<any[]>([])
const projects = ref<any[]>([])
const profile = ref<ProfileView | null>(null)
const directions = ref<Direction[]>([])
const profileDirections = ref<Direction[]>([])
const recommendations = ref<Recommendation[]>([])
const recommendationMeta = ref<RecommendationResponse | null>(null)
const recommending = ref(false)
const dialog = ref(false)
const kind = ref<'goal' | 'project'>('goal')
const loading = ref(false)
const form = reactive<any>({})
const customProfileDirections = computed(() => (profile.value?.directions || [])
  .filter((item) => !item.directionId && item.customDirection)
  .map((item) => item.customDirection))
const canRecommendGoals = computed(() => profile.value?.status === 'GENERATED'
  && Boolean(profileDirections.value.length || customProfileDirections.value.length))

function resetForm(value: 'goal' | 'project') {
  Object.assign(form, {
    directionId: directions.value[0]?.id,
    name: '', type: 'SKILL', description: '', priority: 'MEDIUM',
    startDate: dayjs().format('YYYY-MM-DD'),
    dueDate: dayjs().add(60, 'day').format('YYYY-MM-DD'),
    weeklyBudgetMinutes: 420,
    repositoryUrl: '',
    successCriteria: [{ type: 'OUTCOME', description: '完成目标验收', completed: false }],
    sourceType: 'CUSTOM', profileVersionId: undefined,
    recommendationId: '', recommendationReason: '',
  })
  kind.value = value
}

async function load() {
  const [goalPage, projectPage, currentProfile, catalogDirections] = await Promise.all([
    api<any>({ url: '/goals', params: { pageSize: 100 } }),
    api<any>({ url: '/projects', params: { pageSize: 100 } }),
    api<ProfileView | null>({ url: '/profiles/me' }),
    api<CatalogDirection[]>({ url: '/learning-directions' }),
  ])
  goals.value = goalPage.items
  projects.value = projectPage.items
  profile.value = currentProfile
  profileDirections.value = (currentProfile?.directions || [])
    .filter((item) => item.directionId && item.name)
    .map((item) => ({ id: Number(item.directionId), name: item.name!, currentStage: item.currentStage, primary: item.primary, source: 'PROFILE' as const }))
  const activeCatalog = (catalogDirections || [])
    .filter((item) => item.status === 'ACTIVE')
    .map((item) => ({ id: Number(item.id), name: item.name, source: 'CATALOG' as const }))
  directions.value = profileDirections.value.length ? profileDirections.value : activeCatalog
}
onMounted(load)

function create(value: 'goal' | 'project') {
  if (!directions.value.length) {
    ElMessage.warning('暂无可用学习方向，请先维护学习目录')
    return
  }
  resetForm(value)
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
  } finally { recommending.value = false }
}

function useRecommendation(item: Recommendation) {
  resetForm('goal')
  Object.assign(form, {
    directionId: item.directionId,
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
  })
  dialog.value = true
}

async function save() {
  if (!String(form.name || '').trim()) return void ElMessage.warning('请填写名称')
  if (!form.directionId) return void ElMessage.warning('请选择学习方向')
  if (!form.startDate || !form.dueDate || dayjs(form.dueDate).isBefore(dayjs(form.startDate))) {
    return void ElMessage.warning('截止日期不能早于开始日期')
  }
  loading.value = true
  try {
    if (kind.value === 'goal') {
      await api({
        method: 'POST', url: '/goals',
        data: {
          directionId: Number(form.directionId), name: form.name.trim(), type: form.type,
          description: form.description, priority: form.priority, startDate: form.startDate,
          dueDate: form.dueDate, weeklyBudgetMinutes: Number(form.weeklyBudgetMinutes),
          successCriteria: form.successCriteria,
          sourceType: form.sourceType, profileVersionId: form.profileVersionId,
          recommendationId: form.recommendationId, recommendationReason: form.recommendationReason,
        },
      })
    } else {
      await api({
        method: 'POST', url: '/projects',
        data: {
          primaryDirectionId: Number(form.directionId), name: form.name.trim(), description: form.description,
          startDate: form.startDate, dueDate: form.dueDate, priority: form.priority,
          deliverables: [{ name: '项目成果' }], repositoryUrl: form.repositoryUrl,
        },
      })
    }
    dialog.value = false
    ElMessage.success(kind.value === 'goal' && form.sourceType !== 'CUSTOM' ? '推荐目标已保存为草稿' : '创建成功')
    await load()
  } finally { loading.value = false }
}

async function action(item: any, value: string) {
  let body: any = { reason: `用户执行${value}`, exceptionConfirmed: false }
  if (['completion', 'cancellation'].includes(value)) {
    try {
      body.reason = await ElMessageBox.prompt('请填写这次状态变化的原因', '记录一个决定', {
        inputValidator: (text) => Boolean(text) || '原因不能为空',
      }).then((result) => result.value)
    } catch { return }
  }
  await api({ method: 'POST', url: `/${tab.value}/${item.publicId}/${value}`, data: body })
  ElMessage.success('状态已更新')
  await load()
}

function statusText(status: string) {
  return ({ DRAFT: '尚未启程', ACTIVE: '正在推进', PAUSED: '暂时停靠', COMPLETED: '已经抵达', CANCELED: '已结束' } as Record<string, string>)[status] || status
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
        <div><strong>{{ goals.filter((item) => item.status === 'ACTIVE').length }}</strong><small>活动目标</small></div>
        <i />
        <div><strong>{{ projects.filter((item) => item.status === 'ACTIVE').length }}</strong><small>实践项目</small></div>
      </div>
    </section>

    <div class="collection-bar">
      <div class="collection-switch">
        <button :class="{ active: tab === 'goals' }" @click="tab = 'goals'"><span>目标</span><small>{{ goals.length }}</small></button>
        <button :class="{ active: tab === 'projects' }" @click="tab = 'projects'"><span>项目</span><small>{{ projects.length }}</small></button>
      </div>
      <button class="new-direction" @click="create(tab === 'goals' ? 'goal' : 'project')"><span>＋</span> 新建{{ tab === 'goals' ? '目标' : '项目' }}</button>
    </div>

    <section v-if="tab === 'goals'" class="recommendation-board">
      <div class="recommendation-heading">
        <div>
          <span class="eyebrow">PROFILE-GROUNDED GOALS</span>
          <h2>基于当前画像推荐目标</h2>
          <p v-if="profile?.status === 'GENERATED' && profileDirections.length">
            画像 v{{ profile.currentVersionNo }} ·
            {{ profileDirections.map((item) => `${item.name} / ${stageText(item.currentStage || '')}`).join('，') }}
          </p>
          <p v-else-if="profile?.status === 'GENERATED' && customProfileDirections.length">
            当前画像方向「{{ customProfileDirections.join('，') }}」会先尝试自动匹配学习目录；如果推荐失败，请回画像页改成目录方向。
          </p>
          <p v-else-if="profile && profileDirections.length">画像有尚未固化的修改，请先在画像页生成新版本。</p>
          <p v-else>自定义目标可先用学习目录创建草稿；AI 推荐需要先完成画像。</p>
        </div>
        <button v-if="canRecommendGoals" class="recommend-trigger" :disabled="recommending" @click="recommendGoals">
          {{ recommending ? '正在分析画像…' : recommendations.length ? '换一组建议' : '让 AI 推荐' }}
        </button>
        <button v-else class="recommend-trigger" @click="$router.push('/onboarding')">去完善画像</button>
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

    <section v-if="tab === 'goals'" class="goal-gallery">
      <article v-for="(goal, index) in goals" :key="goal.publicId" class="direction-card" :class="[`status-${goal.status?.toLowerCase()}`, { featured: index === 0 }]">
        <div class="card-top"><span>{{ String(index + 1).padStart(2, '0') }} / {{ goal.type }} · {{ goalSourceText(goal) }}</span><em>{{ statusText(goal.status) }}</em></div>
        <div class="card-copy"><h2>{{ goal.name }}</h2><p>{{ goal.description || '还没有写下说明。也许可以补充：为什么这件事对你重要？' }}</p></div>
        <div class="goal-horizon"><i /><span>{{ goal.startDate }}</span><b>→</b><span>{{ goal.dueDate }}</span></div>
        <div class="card-facts"><span><small>每周投入</small><b>{{ goal.weeklyBudgetMinutes }} 分钟</b></span><span><small>优先级</small><b>{{ goal.priority || 'MEDIUM' }}</b></span></div>
        <div class="card-actions">
          <button v-if="goal.status === 'DRAFT'" @click="action(goal, 'activation')">开始推进</button>
          <button v-if="goal.status === 'ACTIVE'" @click="action(goal, 'pause')">暂停</button>
          <button v-if="goal.status === 'PAUSED'" @click="action(goal, 'resume')">重新开始</button>
          <button v-if="goal.status === 'ACTIVE'" @click="action(goal, 'completion')">标记抵达</button>
          <button class="quiet-action" @click="$router.push('/plans')">交给 Agent 规划 ↗</button>
          <button v-if="!['COMPLETED', 'CANCELED'].includes(goal.status)" class="danger-action" @click="action(goal, 'cancellation')">结束目标</button>
        </div>
      </article>
      <button v-if="!goals.length" class="empty-collection" @click="create('goal')"><span>＋</span><b>写下第一个目标</b><small>从一个真正想发生的变化开始</small></button>
    </section>

    <section v-else class="goal-gallery project-gallery">
      <article v-for="(project, index) in projects" :key="project.publicId" class="direction-card project-card" :class="[`status-${project.status?.toLowerCase()}`, { featured: index === 0 }]">
        <div class="card-top"><span>PROJECT / {{ String(index + 1).padStart(2, '0') }}</span><em>{{ statusText(project.status) }}</em></div>
        <div class="card-copy"><h2>{{ project.name }}</h2><p>{{ project.description || '用一份真实成果，验证自己已经能够做到。' }}</p></div>
        <div class="project-mark"><span>实践</span><i>→</i><span>反馈</span><i>→</i><span>成果</span></div>
        <div class="card-facts"><span><small>计划周期</small><b>{{ project.startDate }} — {{ project.dueDate }}</b></span><span><small>优先级</small><b>{{ project.priority }}</b></span></div>
        <div class="card-actions">
          <button v-if="project.status === 'DRAFT'" @click="action(project, 'activation')">开始项目</button>
          <button v-if="project.status === 'ACTIVE'" @click="action(project, 'pause')">暂停</button>
          <button v-if="project.status === 'PAUSED'" @click="action(project, 'resume')">继续</button>
          <button v-if="!['COMPLETED', 'CANCELED'].includes(project.status)" @click="action(project, 'completion')">完成项目</button>
        </div>
      </article>
      <button v-if="!projects.length" class="empty-collection" @click="create('project')"><span>＋</span><b>创建第一个实践项目</b><small>把所学变成一件真实的作品</small></button>
    </section>

    <el-dialog v-model="dialog" :title="kind === 'goal' ? (form.sourceType === 'CUSTOM' ? '定义一个新目标' : '确认画像推荐目标') : '创建一个实践项目'" width="620">
      <el-alert v-if="kind === 'goal' && form.sourceType !== 'CUSTOM'" class="recommendation-alert" type="success" :closable="false">
        <template #title>基于画像 v{{ recommendationMeta?.profileVersionNo }} 生成，保存前仍可修改</template>
        {{ form.recommendationReason }}
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="120" /></el-form-item>
        <el-form-item label="学习方向">
          <el-select v-model="form.directionId" class="full">
            <el-option v-for="direction in directions" :key="direction.id" :value="direction.id" :label="direction.source === 'PROFILE' ? `${direction.name}（画像）` : direction.name" />
          </el-select>
        </el-form-item>
        <div class="grid grid-2">
          <el-form-item v-if="kind === 'goal'" label="目标类型"><el-select v-model="form.type" class="full"><el-option value="SKILL" label="技能" /><el-option value="EXAM" label="考试" /><el-option value="PROJECT" label="项目" /></el-select></el-form-item>
          <el-form-item label="优先级"><el-select v-model="form.priority" class="full"><el-option value="LOW" /><el-option value="MEDIUM" /><el-option value="HIGH" /><el-option value="URGENT" /></el-select></el-form-item>
          <el-form-item label="开始日期"><el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" class="full" /></el-form-item>
          <el-form-item label="截止日期"><el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" class="full" /></el-form-item>
        </div>
        <el-form-item v-if="kind === 'goal'" label="每周预算（分钟）"><el-input-number v-model="form.weeklyBudgetMinutes" :min="10" :max="6720" /></el-form-item>
        <el-form-item v-else label="代码仓库"><el-input v-model="form.repositoryUrl" /></el-form-item>
        <el-form-item label="为什么要做这件事？"><el-input v-model="form.description" type="textarea" :rows="4" /></el-form-item>
        <el-form-item v-if="kind === 'goal' && form.successCriteria?.length" label="成功标准">
          <div class="criteria-preview"><span v-for="criterion in form.successCriteria" :key="criterion.description">{{ criterion.description }}</span></div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="loading" @click="save">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.goal-page { display: grid; gap: 23px; }
.goal-hero { position: relative; display: grid; grid-template-columns: 1fr auto; align-items: end; gap: 30px; overflow: hidden; padding: clamp(36px, 5vw, 64px); border-radius: 32px; color: #eef4ef; background: radial-gradient(circle at 82% 16%, rgba(111, 175, 141, .3), transparent 28%), linear-gradient(140deg, #102f25, #1b533f); box-shadow: 0 26px 76px rgba(18, 55, 42, .21); }
.goal-hero::after { position: absolute; right: -90px; bottom: -210px; width: 430px; height: 430px; border: 1px solid rgba(255, 255, 255, .08); border-radius: 50%; box-shadow: 0 0 0 65px rgba(255, 255, 255, .018); content: ""; }
.goal-hero > div { position: relative; z-index: 1; }
.goal-hero h1 { margin: 14px 0 10px; font: 500 clamp(37px, 4.5vw, 59px)/1.22 var(--display); letter-spacing: -.035em; }
.goal-hero p { margin: 0; color: #aec5b9; font-size: 12px; }
.goal-totals { display: flex; align-items: center; gap: 25px; min-width: 245px; padding: 20px 24px; border: 1px solid rgba(255, 255, 255, .1); border-radius: 20px; background: rgba(255, 255, 255, .06); backdrop-filter: blur(12px); }
.goal-totals > div { text-align: center; }
.goal-totals strong, .goal-totals small { display: block; }
.goal-totals strong { font: 500 36px var(--display); }
.goal-totals small { margin-top: 3px; color: #a3baaf; font-size: 8px; }
.goal-totals i { width: 1px; height: 42px; background: rgba(255, 255, 255, .11); }
.collection-bar { display: flex; align-items: center; justify-content: space-between; padding: 0 4px; }
.collection-switch { display: flex; gap: 4px; padding: 5px; border-radius: 16px; background: rgba(222, 230, 222, .7); }
.collection-switch button { display: flex; align-items: center; gap: 9px; min-width: 105px; padding: 10px 14px; border: 0; border-radius: 12px; color: #7b8780; background: transparent; font-size: 11px; font-weight: 700; }
.collection-switch button.active { color: var(--green); background: rgba(255, 255, 255, .9); box-shadow: 0 8px 20px rgba(33, 58, 48, .08); }
.collection-switch small { display: grid; place-items: center; min-width: 19px; height: 19px; padding: 0 4px; border-radius: 99px; background: #e1eee5; font-size: 8px; }
.new-direction { display: flex; align-items: center; gap: 8px; padding: 7px 15px 7px 7px; border: 1px solid rgba(28, 88, 63, .13); border-radius: 99px; color: var(--green); background: rgba(255, 255, 255, .7); font-size: 10px; font-weight: 700; }
.new-direction span { display: grid; place-items: center; width: 27px; height: 27px; border-radius: 50%; color: #fff; background: var(--green); font-size: 16px; }
.recommendation-board { overflow: hidden; padding: clamp(24px, 4vw, 38px); border: 1px solid rgba(41, 91, 68, .1); border-radius: 28px; background: linear-gradient(135deg, rgba(249, 252, 247, .92), rgba(233, 242, 232, .72)); box-shadow: 0 18px 48px rgba(35, 70, 53, .08); }
.recommendation-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
.recommendation-heading h2 { margin: 8px 0 5px; color: #173d30; font: 500 clamp(24px, 3vw, 34px)/1.2 var(--display); }
.recommendation-heading p { margin: 0; color: #718078; font-size: 10px; line-height: 1.7; }
.recommend-trigger { flex: 0 0 auto; padding: 11px 17px; border: 0; border-radius: 99px; color: #fff; background: var(--green); box-shadow: 0 10px 24px rgba(23, 71, 50, .17); font-size: 10px; font-weight: 800; }
.recommend-trigger:disabled { cursor: wait; opacity: .65; }
.recommendation-list, .recommendation-loading { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 13px; margin-top: 24px; }
.recommendation-card { display: flex; flex-direction: column; min-height: 310px; padding: 22px; border: 1px solid rgba(255, 255, 255, .9); border-radius: 21px; background: rgba(255, 255, 255, .78); box-shadow: 0 13px 30px rgba(39, 73, 57, .065); }
.recommendation-card-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #75837b; font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.recommendation-card-top em { padding: 4px 7px; border-radius: 99px; color: #276448; background: #e0eee3; font-style: normal; letter-spacing: 0; }
.recommendation-card h3 { margin: 20px 0 8px; color: #183e31; font: 500 21px/1.35 var(--display); }
.recommendation-card > p { flex: 1; margin: 0; color: #79877f; font-size: 9px; line-height: 1.75; }
.recommendation-metrics { display: grid; grid-template-columns: 1.35fr .65fr; gap: 8px; margin: 18px 0 13px; }
.recommendation-metrics span { padding: 10px; border-radius: 11px; background: #eff5ee; }
.recommendation-metrics small, .recommendation-metrics b { display: block; }
.recommendation-metrics small { color: #8a978f; font-size: 7px; }
.recommendation-metrics b { margin-top: 4px; color: #345b49; font-size: 8px; }
.recommendation-milestones { display: flex; flex-wrap: wrap; gap: 5px; }
.recommendation-milestones span, .criteria-preview span { padding: 5px 8px; border-radius: 8px; color: #557163; background: #edf2e9; font-size: 8px; }
.adopt-goal { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; padding: 11px 13px; border: 0; border-radius: 11px; color: #fff; background: #1b513d; font-size: 9px; font-weight: 800; }
.adopt-goal b { font-size: 14px; }
.recommendation-empty { display: flex; align-items: center; flex-direction: column; margin-top: 23px; padding: 27px; border: 1px dashed rgba(40, 91, 67, .17); border-radius: 17px; color: #365e4b; text-align: center; }
.recommendation-empty small { margin-top: 5px; color: #849188; font-size: 8px; }
.recommendation-loading > span { display: grid; gap: 13px; min-height: 230px; padding: 22px; border-radius: 21px; background: rgba(255, 255, 255, .66); }
.recommendation-loading i { height: 15px; border-radius: 99px; background: linear-gradient(90deg, #e8eee7 25%, #f6f9f5 50%, #e8eee7 75%); background-size: 200% 100%; animation: goal-shimmer 1.3s infinite; }
.recommendation-loading i:nth-child(2) { height: 70px; }
.recommendation-alert { margin-bottom: 18px; }
.criteria-preview { display: flex; flex-wrap: wrap; gap: 7px; width: 100%; }
@keyframes goal-shimmer { to { background-position: -200% 0; } }
.goal-gallery { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 19px; }
.direction-card { position: relative; display: flex; flex-direction: column; min-height: 360px; overflow: hidden; padding: 28px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 27px; background: rgba(252, 253, 249, .72); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.direction-card.featured { color: #edf4ee; background: radial-gradient(circle at 90% 10%, rgba(105, 165, 134, .24), transparent 25%), linear-gradient(145deg, #1a4536, #112f26); box-shadow: 0 23px 58px rgba(20, 55, 42, .18); }
.card-top { display: flex; align-items: center; justify-content: space-between; }
.card-top > span { color: #829087; font-size: 8px; font-weight: 800; letter-spacing: .14em; }
.card-top em { padding: 5px 9px; border-radius: 99px; color: var(--green); background: var(--mint); font-size: 8px; font-style: normal; font-weight: 700; }
.featured .card-top > span { color: #91ad9f; }
.featured .card-top em { color: #dfc27d; background: rgba(221, 189, 118, .12); }
.card-copy { flex: 1; padding: 27px 0 18px; }
.card-copy h2 { margin: 0; font: 500 29px/1.28 var(--display); }
.card-copy p { max-width: 560px; margin: 10px 0 0; color: #77847c; font-size: 10px; line-height: 1.75; }
.featured .card-copy p { color: #a8beb3; }
.goal-horizon { display: grid; grid-template-columns: 1fr auto auto auto; align-items: center; gap: 9px; margin: 4px 0 18px; }
.goal-horizon i { height: 2px; border-radius: 99px; background: linear-gradient(90deg, var(--green) 0 64%, #dce5de 64%); }
.goal-horizon span { color: #8d9791; font: 500 8px ui-monospace, monospace; }
.goal-horizon b { color: #a2aaa5; font-size: 9px; }
.featured .goal-horizon i { background: linear-gradient(90deg, #dfbe78 0 64%, rgba(255, 255, 255, .11) 64%); }
.featured .goal-horizon span { color: #96aea2; }
.card-facts { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.card-facts > span { padding: 14px 0; }
.card-facts > span + span { padding-left: 18px; border-left: 1px solid var(--line); }
.card-facts small, .card-facts b { display: block; }
.card-facts small { color: #969f99; font-size: 8px; }
.card-facts b { margin-top: 5px; font-size: 9px; }
.featured .card-facts { border-color: rgba(255, 255, 255, .09); }
.featured .card-facts > span + span { border-color: rgba(255, 255, 255, .09); }
.featured .card-facts small { color: #85a093; }
.card-actions { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 18px; }
.card-actions button { padding: 7px 10px; border: 1px solid rgba(30, 86, 62, .12); border-radius: 9px; color: var(--green); background: #e8f0e9; font-size: 8px; font-weight: 700; }
.card-actions .quiet-action { margin-left: auto; border-color: transparent; background: transparent; }
.card-actions .danger-action { border-color: rgba(181, 77, 69, .09); color: #a94e47; background: #f3e4e2; }
.featured .card-actions button { border-color: rgba(255, 255, 255, .09); color: #dce9e2; background: rgba(255, 255, 255, .07); }
.featured .card-actions .quiet-action { color: #e0c17b; background: transparent; }
.featured .card-actions .danger-action { color: #d59a94; }
.project-mark { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 21px; padding: 13px 15px; border-radius: 13px; color: #718078; background: rgba(228, 237, 229, .6); font-size: 9px; }
.featured .project-mark { color: #a7beb2; background: rgba(255, 255, 255, .055); }
.project-mark i { color: var(--gold); font-style: normal; }
.empty-collection { display: flex; align-items: center; justify-content: center; flex-direction: column; min-height: 360px; border: 1px dashed rgba(31, 88, 64, .18); border-radius: 27px; color: var(--green); background: rgba(249, 251, 247, .45); }
.empty-collection > span { display: grid; place-items: center; width: 58px; height: 58px; margin-bottom: 16px; border-radius: 50%; background: #e4eee6; font-size: 24px; }
.empty-collection b, .empty-collection small { display: block; }
.empty-collection b { font: 500 18px var(--display); }
.empty-collection small { margin-top: 6px; color: var(--muted); font-size: 9px; }

@media (max-width: 850px) {
  .goal-hero { grid-template-columns: 1fr; }
  .goal-totals { justify-self: start; }
  .goal-gallery { grid-template-columns: 1fr; }
  .recommendation-list, .recommendation-loading { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .goal-hero { padding: 29px 23px; border-radius: 25px; }
  .goal-hero h1 { font-size: 38px; }
  .collection-bar { align-items: flex-start; flex-direction: column; gap: 12px; }
  .collection-switch { width: 100%; }
  .collection-switch button { flex: 1; justify-content: center; }
  .recommendation-heading { align-items: flex-start; flex-direction: column; }
  .recommend-trigger { width: 100%; }
  .direction-card { min-height: 340px; padding: 22px; border-radius: 22px; }
  .card-actions .quiet-action { margin-left: 0; }
}
</style>
