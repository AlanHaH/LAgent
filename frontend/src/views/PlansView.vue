<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, idempotencyKey } from '../api/http'

const goals = ref<any[]>([])
const selectedGoal = ref('')
const job = ref<any>()
const detail = ref<any>()
const generating = ref(false)
const publishing = ref(false)
const requirement = ref('')

const changes = computed(() => detail.value?.changes || [])
const validations = computed(() => detail.value?.validation || [])
const canPublish = computed(() => detail.value?.version?.status === 'PENDING_CONFIRMATION')
const selectedGoalName = computed(() => goals.value.find((goal) => goal.publicId === selectedGoal.value)?.name || '未选择目标')
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

onMounted(async () => {
  const page = await api<any>({ url: '/goals', params: { status: 'ACTIVE', pageSize: 100 } })
  goals.value = page.items
  selectedGoal.value = goals.value[0]?.publicId || ''
})

async function generate() {
  generating.value = true
  try {
    job.value = await api<any>({
      method: 'POST',
      url: `/goals/${selectedGoal.value}/planning-jobs`,
      headers: { 'Idempotency-Key': idempotencyKey() },
      data: { type: 'INITIAL', userRequirement: requirement.value },
    })
    if (job.value.planVersionId) detail.value = await api<any>({ url: `/plan-versions/${job.value.planVersionId}` })
    ElMessage.success('Agent 已生成可审阅方案，尚未修改任何正式任务')
  } finally { generating.value = false }
}

async function validate() {
  detail.value = await api<any>({ method: 'POST', url: `/plan-versions/${detail.value.version.publicId}/validation` })
  ElMessage.success('时间与容量约束校验完成')
}

async function publish() {
  await ElMessageBox.confirm(
    `将应用 ${changes.value.length} 项变更。发布后会生成正式任务，是否继续？`,
    '确认这段学习节奏',
    { confirmButtonText: '确认发布', cancelButtonText: '再看看', type: 'warning' },
  )
  publishing.value = true
  try {
    const confirmation = await api<any>({ method: 'POST', url: `/plan-versions/${detail.value.version.publicId}/confirmation-requests` })
    const result = await api<any>({
      method: 'POST',
      url: `/plan-versions/${detail.value.version.publicId}/publication`,
      headers: { 'Idempotency-Key': idempotencyKey() },
      data: { confirmationToken: confirmation.token },
    })
    ElMessage.success(`计划 v${result.versionNo} 已发布，共生成 ${result.changedTaskIds.length} 个任务`)
    detail.value.version.status = 'PUBLISHED'
  } finally { publishing.value = false }
}

async function reject() {
  await api({ method: 'POST', url: `/plan-versions/${detail.value.version.publicId}/rejection`, data: { reason: '用户拒绝当前方案' } })
  detail.value.version.status = 'REJECTED'
  ElMessage.info('方案已拒绝，正式计划没有改变')
}

function after(change: any) {
  try { return JSON.parse(change.afterJson || '{}') } catch { return {} }
}
</script>

<template>
  <div class="planner-page">
    <section v-if="!detail" class="planner-landing">
      <div class="planner-story">
        <div class="agent-glyph"><span>AI</span><i /></div>
        <span class="eyebrow light">ADAPTIVE PLANNER</span>
        <h1>不是排满时间，<br>而是设计一种能坚持的节奏。</h1>
        <p>Agent 会理解你的目标、画像、期限和空闲时间，先提出方案；只有得到你的确认，才会写入正式任务。</p>
        <div class="promise-list">
          <span><i>01</i>容量保留余地</span>
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
            <el-select v-model="selectedGoal" class="full" placeholder="请先激活一个目标">
              <el-option v-for="goal in goals" :key="goal.publicId" :value="goal.publicId" :label="goal.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="告诉我你想要的节奏（可选）">
            <el-input v-model="requirement" type="textarea" :rows="6" placeholder="例如：工作日以阅读为主，周末留出一段完整时间做项目；这周不要安排得太满。" />
          </el-form-item>
          <div class="context-preview">
            <span>当前上下文</span><b>{{ selectedGoalName }}</b>
            <small>画像偏好、可用时间与容量约束会自动带入</small>
          </div>
          <el-button type="primary" size="large" class="full" :disabled="!selectedGoal" :loading="generating" @click="generate">
            让 Agent 提出方案 <span class="button-arrow">↗</span>
          </el-button>
        </el-form>
        <div v-if="!goals.length" class="no-goal">需要先创建并激活一个学习目标，Agent 才知道朝哪里规划。</div>
      </div>
    </section>

    <template v-else>
      <section class="plan-stage">
        <div class="stage-copy">
          <span class="eyebrow light">PROPOSAL / VERSION {{ detail.version.versionNo }}</span>
          <h1>{{ detail.version.triggerType === 'OPTIMIZATION' ? '一份更合适的新节奏' : '你的第一版学习路线' }}</h1>
          <p>{{ statusText }} · 风险等级 {{ detail.version.riskLevel }} · {{ changes.length }} 项建议变更</p>
        </div>
        <div class="workflow-track">
          <div v-for="(label, index) in ['理解目标', '形成提案', '等待确认', '正式生效']" :key="label" :class="{ active: workflowStep >= index + 1, current: workflowStep === index + 1 }">
            <i>{{ workflowStep > index + 1 ? '✓' : index + 1 }}</i><span>{{ label }}</span>
          </div>
        </div>
        <div class="stage-actions">
          <el-button @click="validate">重新校验</el-button>
          <el-button type="danger" plain :disabled="detail.version.status === 'PUBLISHED'" @click="reject">不采用</el-button>
          <el-button type="primary" :disabled="!canPublish" :loading="publishing" @click="publish">确认并发布</el-button>
        </div>
      </section>

      <div class="proposal-layout">
        <section class="change-story">
          <div class="proposal-heading">
            <div><span class="eyebrow">PROPOSED RHYTHM</span><h2>Agent 建议这样展开</h2></div>
            <span>{{ changes.length }} STEPS</span>
          </div>
          <div v-if="!changes.length" class="empty">当前提案没有任务变更。</div>
          <article v-for="(change, index) in changes" :key="change.publicId" class="change-chapter">
            <div class="chapter-index"><span>{{ String(index + 1).padStart(2, '0') }}</span><i /></div>
            <div class="chapter-body">
              <div class="chapter-top"><span>{{ change.action }}</span><small>{{ after(change).scheduledStart?.replace('T', ' ') || '待安排' }}</small></div>
              <h3>{{ after(change).title }}</h3>
              <p>{{ change.reason }}</p>
              <div class="chapter-meta"><span>{{ after(change).estimatedMinutes }} 分钟</span><span>{{ after(change).taskType || '学习任务' }}</span><span>{{ after(change).priority || '常规优先级' }}</span></div>
            </div>
          </article>
        </section>

        <aside class="trust-console">
          <div class="trust-head">
            <span class="trust-orb">{{ validations.every((item: any) => item.passed) ? '✓' : '!' }}</span>
            <div><span class="eyebrow">GUARDRAILS</span><h2>发布守门人</h2></div>
          </div>
          <p class="trust-intro">每一项都通过后，计划才会等待你的最终确认。Agent 不能越过这一步。</p>
          <div class="validation-stack">
            <div v-for="validation in validations" :key="validation.ruleCode" :class="['validation-line', validation.passed ? 'passed' : 'failed']">
              <i>{{ validation.passed ? '✓' : '!' }}</i>
              <div><b>{{ validation.ruleCode }}</b><small>{{ validation.message }}</small></div>
            </div>
            <div v-if="!validations.length && canPublish" class="validation-line passed">
              <i>✓</i><div><b>全部约束已通过</b><small>容量、冲突和时间窗口均符合要求，可以发布。</small></div>
            </div>
            <div v-else-if="!validations.length" class="validation-placeholder">还没有校验结果，点击“重新校验”获得发布结论。</div>
          </div>
          <div class="proposal-fingerprint"><span>PROPOSAL FINGERPRINT</span><code>{{ detail.version.proposalHash?.slice(0, 16) }}…</code></div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
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
.context-preview { margin: 5px 0 20px; padding: 15px 16px; border: 1px solid rgba(36, 83, 63, .1); border-radius: 15px; background: rgba(224, 236, 226, .55); }
.context-preview span, .context-preview b, .context-preview small { display: block; }
.context-preview span { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .12em; }
.context-preview b { margin: 6px 0 4px; overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.context-preview small { color: #819087; font-size: 9px; }
.button-arrow { margin-left: 8px; }
.no-goal { margin-top: 15px; color: var(--red); font-size: 10px; line-height: 1.6; }

.plan-stage { position: relative; overflow: hidden; padding: 38px 42px 32px; border-radius: 30px; color: #eff5ef; background: radial-gradient(circle at 90% 15%, rgba(112, 173, 140, .3), transparent 25%), linear-gradient(135deg, #12352a, #1b5742); box-shadow: 0 25px 70px rgba(17, 55, 42, .2); }
.stage-copy h1 { margin: 9px 0 5px; font: 500 clamp(31px, 4vw, 46px) var(--display); }
.stage-copy p { margin: 0; color: #acc4b8; font-size: 10px; }
.workflow-track { display: grid; grid-template-columns: repeat(4, 1fr); max-width: 720px; margin-top: 31px; }
.workflow-track > div { position: relative; display: flex; align-items: center; gap: 8px; color: #7f9b8e; font-size: 9px; }
.workflow-track > div::after { position: absolute; z-index: 0; top: 13px; right: 4px; left: 35px; height: 1px; background: rgba(255, 255, 255, .12); content: ""; }
.workflow-track > div:last-child::after { display: none; }
.workflow-track i { position: relative; z-index: 1; display: grid; place-items: center; width: 27px; height: 27px; border: 1px solid rgba(255, 255, 255, .13); border-radius: 50%; background: #1d4b3b; font-size: 9px; font-style: normal; }
.workflow-track .active { color: #d8e5de; }
.workflow-track .active i { border-color: rgba(226, 194, 126, .45); color: #183a2e; background: #e1bd74; }
.workflow-track .current i { box-shadow: 0 0 0 5px rgba(225, 189, 116, .12); }
.stage-actions { position: absolute; top: 39px; right: 40px; display: flex; gap: 8px; }
.plan-stage :deep(.el-button:not(.el-button--primary):not(.el-button--danger)) { border-color: rgba(255, 255, 255, .13); color: #dce8e1; background: rgba(255, 255, 255, .07); }

.proposal-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(300px, .55fr); gap: 22px; margin-top: 22px; }
.change-story { padding: 33px 36px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: rgba(252, 253, 249, .7); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.proposal-heading { display: flex; align-items: end; justify-content: space-between; margin-bottom: 18px; }
.proposal-heading h2 { margin: 7px 0 0; font: 500 28px var(--display); }
.proposal-heading > span { color: #96a099; font-size: 8px; font-weight: 700; letter-spacing: .13em; }
.change-chapter { display: grid; grid-template-columns: 48px 1fr; gap: 12px; }
.chapter-index { position: relative; padding-top: 24px; color: #9aa69f; font: italic 13px var(--display); }
.chapter-index i { position: absolute; top: 49px; bottom: 0; left: 13px; width: 1px; background: rgba(31, 80, 59, .13); }
.change-chapter:last-child .chapter-index i { display: none; }
.chapter-body { padding: 21px 0 24px; border-top: 1px solid var(--line); }
.chapter-top { display: flex; align-items: center; justify-content: space-between; }
.chapter-top span { padding: 4px 8px; border-radius: 99px; color: var(--green); background: var(--mint); font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.chapter-top small { color: #8d9891; font-size: 9px; }
.chapter-body h3 { margin: 12px 0 7px; font: 500 19px var(--display); }
.chapter-body p { max-width: 720px; margin: 0; color: #6f7d75; font-size: 11px; line-height: 1.75; }
.chapter-meta { display: flex; flex-wrap: wrap; gap: 15px; margin-top: 13px; }
.chapter-meta span { color: #8b9690; font-size: 8px; }
.chapter-meta span::before { display: inline-block; width: 4px; height: 4px; margin: 0 6px 1px 0; border-radius: 50%; background: #c9a55f; content: ""; }
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
  .stage-actions { position: static; margin-top: 26px; }
}
@media (max-width: 620px) {
  .planner-landing { border-radius: 26px; }
  .planner-story, .planning-composer { padding: 31px 24px; }
  .planner-story h1 { font-size: 39px; }
  .promise-list { align-items: flex-start; flex-direction: column; gap: 10px; }
  .plan-stage { padding: 29px 23px; border-radius: 25px; }
  .workflow-track { grid-template-columns: repeat(4, auto); gap: 5px; }
  .workflow-track > div { align-items: flex-start; flex-direction: column; }
  .workflow-track > div::after { display: none; }
  .workflow-track span { font-size: 8px; }
  .stage-actions { flex-wrap: wrap; }
  .change-story, .trust-console { padding: 22px; border-radius: 22px; }
  .change-chapter { grid-template-columns: 34px 1fr; }
  .chapter-top { align-items: flex-start; flex-direction: column; gap: 7px; }
}
</style>
