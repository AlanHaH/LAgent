<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, postSse } from '../api/http'

type Slot = { weekday:number; start:string; end:string; energyLevel:string }
type Preference = { contentModes:string[]; guidanceStyle:string; taskGranularity:string; focusMinutes:number; capacityRatio:number; difficultyMin:number; difficultyMax:number; reminders:Record<string,boolean> }
type Draft = {
  timezone:string; weekStart:number; planStartDate?:string; planEndDate?:string; directionId?:number;
  directionName?:string; customDirection?:string; currentStage?:string; backgroundText?:string;
  preference:Preference; availability:Slot[]; evidence:Record<string,string>
}
type InterviewMessage = { id:string; role:string; content:string; source:string; createdAt:string }
type InterviewSession = { id:string; status:string; draft:Draft; missingFields:string[]; completenessPercent:number; readyToConfirm:boolean; assistantMode:string; version:number; messages:InterviewMessage[] }
type ProfileVersionView = {
  id?:number|string; versionNo:number; snapshotJson?:string; confidence:number; triggerType?:string; createdAt?:string; snapshot:Record<string,any>
}

const directions = ref<any[]>([])
const router = useRouter()
const session = ref<InterviewSession>()
const starting = ref(true)
const sending = ref(false)
const confirming = ref(false)
const manualSaving = ref(false)
const manualOpen = ref(false)
const chatInput = ref('')
const chatBox = ref<HTMLElement>()
const pendingUserMessage = ref('')
const streamingAssistant = ref('')
let streamController:AbortController|undefined
const profileVersion = ref<number>()
const preferenceVersion = ref<number>()
const profileVersions = ref<ProfileVersionView[]>([])
const versionHistoryVisible = ref(false)

const today = dayjs().format('YYYY-MM-DD')
const profile = reactive({
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai', weekStart: 1,
  planDates: [today, dayjs().add(27, 'day').format('YYYY-MM-DD')] as string[], backgroundText: '',
  directionId: undefined as number|undefined, customDirection: '', currentStage: 'BEGINNER',
})
const pref = reactive<Preference>({ contentModes:['TEXT','PRACTICE'], guidanceStyle:'SOCRATIC', taskGranularity:'MEDIUM', focusMinutes:45, capacityRatio:.85, difficultyMin:1, difficultyMax:4, reminders:{ TASK_DUE:true, TASK_OVERDUE:true } })
const slots = ref<Slot[]>([
  { weekday:1,start:'19:00',end:'21:00',energyLevel:'HIGH' },
  { weekday:3,start:'19:00',end:'21:00',energyLevel:'MEDIUM' },
  { weekday:6,start:'09:00',end:'12:00',energyLevel:'HIGH' },
])

const contentModeOptions = [
  { value:'TEXT', label:'文档阅读' },
  { value:'PRACTICE', label:'练习测验' },
]
const weekdayNames = ['周一','周二','周三','周四','周五','周六','周日']
const stageNames:Record<string,string> = { BEGINNER:'入门', INTERMEDIATE:'进阶', ADVANCED:'高级' }
const modeNames:Record<string,string> = { TEXT:'文档阅读', PRACTICE:'练习测验' }
const guidanceNames:Record<string,string> = { SOCRATIC:'启发式引导', DIRECT:'直接讲解' }
const versionTriggerNames:Record<string,string> = { USER_REQUEST:'手动生成', INTERVIEW_CONFIRM:'访谈确认', MANUAL_SAVE:'手动编辑' }
const manualPeriodDays = computed(() => profile.planDates?.length === 2 ? dayjs(profile.planDates[1]).diff(dayjs(profile.planDates[0]), 'day') + 1 : 0)
const interviewPeriodDays = computed(() => {
  const d = session.value?.draft
  return d?.planStartDate && d?.planEndDate ? dayjs(d.planEndDate).diff(dayjs(d.planStartDate), 'day') + 1 : 0
})
const hasUserMessages = computed(() => pendingUserMessage.value || session.value?.messages.some(m => m.role === 'USER'))
const recentProfileVersions = computed(() => profileVersions.value.slice(0, 3))

onBeforeUnmount(() => streamController?.abort())

onMounted(async () => {
  try {
    directions.value = await api<any[]>({ url:'/learning-directions' })
    const current = await api<any>({ url:'/profiles/me' })
    if (current) applyCurrentProfile(current)
    try { await loadAvailability(false) } catch { /* 新用户尚无画像 */ }
    if (current?.status === 'GENERATED') await loadGenerated()
    session.value = await api<InterviewSession|undefined>({ url:'/profiles/me/interview-sessions/active' })
    if (!session.value) session.value = await api<InterviewSession>({ method:'POST', url:'/profiles/me/interview-sessions', data:{} })
    await scrollToBottom()
  } finally { starting.value = false }
})

function applyCurrentProfile(current:any) {
  const primary = current.directions?.find((d:any) => d.primary) || current.directions?.[0]
  Object.assign(profile, {
    timezone:current.timezone, weekStart:current.weekStart, backgroundText:current.backgroundText||'',
    planDates:current.planStartDate && current.planEndDate ? [current.planStartDate,current.planEndDate] : profile.planDates,
    directionId:primary?.directionId, customDirection:primary?.customDirection||'', currentStage:primary?.currentStage||'BEGINNER',
  })
  profileVersion.value = current.version
  if (current.preference) {
    Object.assign(pref,current.preference)
    pref.contentModes = sanitizeContentModes(current.preference.contentModes)
    preferenceVersion.value=current.preference.version
  }
}

async function sendMessage() {
  const content = chatInput.value.trim()
  if (!content || !session.value || sending.value) return
  const sessionId = session.value.id
  const version = session.value.version
  pendingUserMessage.value = content
  streamingAssistant.value = ''
  chatInput.value = ''
  sending.value = true
  streamController = new AbortController()
  let completed = false
  try {
    await scrollToBottom()
    await postSse(`/profiles/me/interview-sessions/${sessionId}/messages`, { content, version }, ({ event, data }) => {
      if (event === 'message.delta') {
        streamingAssistant.value += String(data?.delta || '')
        void scrollToBottom()
      } else if (event === 'message.replace') {
        streamingAssistant.value = String(data?.content || '')
        void scrollToBottom()
      } else if (event === 'stream.compatibility') {
        ElMessage.warning('当前后端仍是旧版本，已兼容显示完整回复；重启后端后将恢复流式输出')
      } else if (event === 'message.completed') {
        session.value = data as InterviewSession
        completed = true
        pendingUserMessage.value = ''
        streamingAssistant.value = ''
        void scrollToBottom()
      } else if (event === 'message.failed') {
        throw new Error(data?.message || '画像访谈生成失败')
      }
    }, streamController.signal)
    if (!completed) throw new Error('流式响应意外结束，请重试')
  } catch (error:any) {
    if (error?.name === 'AuthenticationExpiredError') return
    if (error?.name === 'AbortError') ElMessage.info('已停止生成')
    else ElMessage.error(error?.message || '画像访谈生成失败，请重试')
    // 若服务端恰好已完成提交，以最新版本为准；否则保留原输入方便再次发送。
    try {
      const latest = await api<InterviewSession>({ url:`/profiles/me/interview-sessions/${sessionId}` })
      if (latest.version !== version) { session.value = latest; completed = true }
    } catch { /* 保持当前页面状态 */ }
    if (!completed) chatInput.value = content
  } finally {
    pendingUserMessage.value = ''
    streamingAssistant.value = ''
    sending.value = false
    streamController = undefined
    await scrollToBottom()
  }
}

function stopStreaming() { streamController?.abort() }

async function restartInterview() {
  if (sending.value) return
  session.value = await api<InterviewSession>({ method:'POST', url:'/profiles/me/interview-sessions', data:{ restart:true } })
  chatInput.value = ''
  await scrollToBottom()
}

async function confirmDraft() {
  if (!session.value?.readyToConfirm) return
  confirming.value = true
  try {
    const result = await api<any>({ method:'POST', url:`/profiles/me/interview-sessions/${session.value.id}/confirmation`, data:{ version:session.value.version } })
    applyCurrentProfile(result.profile)
    await loadAvailability(true)
    session.value = { ...session.value, status:'CONFIRMED', readyToConfirm:false, completenessPercent:100 }
    await loadGenerated()
    ElMessage.success('学习画像已确认保存，并生成了可追溯版本')
    showProfileNextStepGuide()
  } finally { confirming.value = false }
}

function showProfileNextStepGuide() {
  ElMessageBox.confirm(
    '画像已经成为后续计划生成的基础。接下来建议先创建学习目标；如果你已经有明确目标，也可以直接去知识库上传文档资料。',
    '画像已完成，下一步做什么？',
    {
      confirmButtonText:'创建学习目标',
      cancelButtonText:'上传学习资料',
      distinguishCancelAndClose:true,
      closeOnClickModal:false,
      type:'success',
    },
  ).then(() => {
    void router.push('/goals')
  }).catch((action) => {
    if (action === 'cancel') void router.push('/knowledge')
  })
}

async function saveManual() {
  if (!profile.directionId && !profile.customDirection.trim()) return ElMessage.warning('请选择或填写学习方向')
  if (manualPeriodDays.value < 1 || manualPeriodDays.value > 365) return ElMessage.warning('计划日期范围必须是 1～365 天')
  if (!slots.value.length) return ElMessage.warning('请至少设置一个每周可用时段')
  const contentModes = validContentModes(pref.contentModes)
  if (!contentModes.length) return ElMessage.warning('请至少选择一种文档学习方式')
  manualSaving.value = true
  try {
    const saved = await api<any>({ method:'PUT',url:'/profiles/me',data:{
      timezone:profile.timezone,weekStart:profile.weekStart,planPeriodDays:manualPeriodDays.value,
      planStartDate:profile.planDates[0],planEndDate:profile.planDates[1],backgroundText:profile.backgroundText,
      directions:[{directionId:profile.directionId,customDirection:profile.directionId?undefined:profile.customDirection,currentStage:profile.currentStage,primary:true}],version:profileVersion.value,
    } })
    profileVersion.value=saved.version
    const savedPref = await api<any>({ method:'PUT',url:'/profiles/me/preferences',data:{...pref,contentModes,version:preferenceVersion.value} })
    preferenceVersion.value=savedPref.version
    await api({ method:'PUT',url:'/profiles/me/availability',data:{slots:slots.value} })
    await api({ method:'POST',url:'/profiles/me/generation-jobs' })
    await loadGenerated()
    manualOpen.value=false
    ElMessage.success('手动画像已保存，并生成了新版本')
  } finally { manualSaving.value=false }
}

async function loadGenerated() {
  const versions=await api<any[]>({url:'/profiles/me/versions'})
  profileVersions.value=(versions||[]).map(normalizeProfileVersion)
  const latest=profileVersions.value[0]
  if(!latest) return
}

function normalizeProfileVersion(raw:any):ProfileVersionView {
  let snapshot:Record<string,any> = {}
  try { snapshot = raw?.snapshotJson ? JSON.parse(raw.snapshotJson) : {} } catch { snapshot = {} }
  return {
    ...raw,
    versionNo:Number(raw?.versionNo || 0),
    confidence:Number(raw?.confidence ?? snapshot.confidence ?? 0),
    snapshot,
  }
}

function versionConfidence(item:ProfileVersionView) { return Math.round(Number(item.confidence || 0) * 100) }
function versionDate(item:ProfileVersionView) {
  const value = item.createdAt || item.snapshot?.generatedAt
  if (!value) return '未知时间'
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : '未知时间'
}
function versionPeriod(item:ProfileVersionView) {
  const start = item.snapshot?.planStartDate
  const end = item.snapshot?.planEndDate
  if (!start || !end) return '未记录周期'
  return `${start} → ${end}`
}
function versionTriggerName(item:ProfileVersionView) { return versionTriggerNames[item.triggerType || ''] || item.triggerType || '画像生成' }

async function loadAvailability(replaceWhenEmpty:boolean) {
  const availability = await api<any>({ url:'/profiles/me/availability' })
  if (availability.rules?.length || replaceWhenEmpty) {
    slots.value = (availability.rules||[]).map((x:any) => ({
      weekday:x.weekday,start:shortTime(x.startTime||x.start),end:shortTime(x.endTime||x.end),energyLevel:x.energyLevel,
    }))
  }
}

async function scrollToBottom() { await nextTick(); chatBox.value?.scrollTo({ top:chatBox.value.scrollHeight, behavior:'smooth' }) }
function addSlot(){ slots.value.push({weekday:2,start:'19:00',end:'20:00',energyLevel:'MEDIUM'}) }
function fillPrompt(text:string){ chatInput.value=text; nextTick(() => document.querySelector<HTMLTextAreaElement>('.chat-compose textarea')?.focus()) }
function displayDirection(d?:Draft){ return d?.directionName || d?.customDirection || '待补充' }
function displayDateRange(d?:Draft){ return d?.planStartDate && d?.planEndDate ? `${d.planStartDate} → ${d.planEndDate}（${interviewPeriodDays.value} 天）` : '待补充' }
function shortTime(value?:string){ return value ? value.slice(0,5) : '' }
function validContentModes(modes?:string[]) { return contentModeOptions.map(x => x.value).filter(value => modes?.includes(value)) }
function sanitizeContentModes(modes?:string[]) { const selected = validContentModes(modes); return selected.length ? selected : ['TEXT','PRACTICE'] }
</script>

<template>
  <div class="profile-wrap">
    <div class="page-head">
      <div><span class="eyebrow">AI PROFILE INTERVIEW</span><h2>聊一聊，让画像自己成形</h2><p>AI 只整理草稿，不会自行修改正式数据；你检查并确认后才会保存。</p></div>
      <div class="head-actions"><el-button :disabled="sending" @click="manualOpen=!manualOpen">{{ manualOpen?'收起手动编辑':'高级手动编辑' }}</el-button><el-button text :disabled="sending" @click="restartInterview">重新访谈</el-button></div>
    </div>

    <el-skeleton v-if="starting" :rows="8" animated class="panel"/>
    <template v-else-if="session">
      <div class="interview-layout">
        <section class="panel chat-panel">
          <div class="chat-head">
            <div><span class="assistant-orb">序</span><div><b>画像访谈助手</b><small>{{ sending?'正在流式生成…':session.assistantMode==='AI'?'AI 正在参与结构化整理':'规则引导模式' }} · 保存前由你确认</small></div></div>
            <el-tag :type="session.status==='CONFIRMED'?'success':'info'" effect="light">{{ session.status==='CONFIRMED'?'已确认':'草稿中' }}</el-tag>
          </div>
          <div ref="chatBox" class="chat-stream">
            <div v-for="message in session.messages" :key="message.id" class="message-row" :class="message.role.toLowerCase()">
              <div class="message-bubble"><p>{{ message.content }}</p><small>{{ message.role==='USER'?'你':message.source==='AI'?'AI 助手':'画像助手' }}</small></div>
            </div>
            <div v-if="pendingUserMessage" class="message-row user"><div class="message-bubble"><p>{{ pendingUserMessage }}</p><small>你</small></div></div>
            <div v-if="sending" class="message-row assistant">
              <div v-if="streamingAssistant" class="message-bubble streaming"><p>{{ streamingAssistant }}<i class="stream-caret"/></p><small>AI 助手 · 生成中</small></div>
              <div v-else class="message-bubble typing"><i/><i/><i/></div>
            </div>
          </div>
          <div v-if="!hasUserMessages" class="quick-prompts">
            <button @click="fillPrompt('我想学习计算机科学，目前是零基础，希望从今天开始学 8 周。')">零基础开始一个方向</button>
            <button @click="fillPrompt('我有一些基础，想制定一个有明确截止日期的进阶计划。')">已有基础做进阶计划</button>
            <button @click="fillPrompt('我想先告诉你每周能学习的时间，再一起确定周期。')">先说每周可用时间</button>
          </div>
          <div v-if="session.status==='ACTIVE'" class="chat-compose">
            <el-input v-model="chatInput" type="textarea" :rows="3" maxlength="2000" resize="none" :disabled="sending" placeholder="例如：我想学 Java，零基础，从 8 月 1 日到 10 月 1 日，每周一三五 19:00-21:00 有空……" @keydown.ctrl.enter.prevent="sendMessage"/>
            <div><small>Ctrl + Enter 发送 · 不要输入密码、密钥或授权码</small><el-button v-if="sending" type="danger" plain @click="stopStreaming">停止生成</el-button><el-button v-else type="primary" :disabled="!chatInput.trim()" @click="sendMessage">发送</el-button></div>
          </div>
          <div v-else class="confirmed-note">这轮访谈已经确认保存。若要调整，请点击“重新访谈”或使用高级手动编辑。</div>
        </section>

        <aside class="panel draft-panel">
          <div class="draft-title"><div><span class="eyebrow">STRUCTURED DRAFT</span><h3>待确认的画像草稿</h3></div><strong>{{ session.completenessPercent }}%</strong></div>
          <el-progress :percentage="session.completenessPercent" :show-text="false" :stroke-width="8"/>
          <div v-if="session.readyToConfirm" class="ready-box"><b>画像草稿已完整</b><span>确认后才会写入正式画像；如果有不满意的地方，继续在左侧告诉助手修改。</span></div>
          <div v-else-if="session.missingFields.length" class="missing-box"><b>还需要确认</b><span v-for="item in session.missingFields" :key="item">{{ item }}</span></div>
          <dl class="draft-list">
            <div><dt>学习方向</dt><dd>{{ displayDirection(session.draft) }}</dd></div>
            <div><dt>当前阶段</dt><dd>{{ stageNames[session.draft.currentStage||''] || '待补充' }}</dd></div>
            <div><dt>自定义周期</dt><dd>{{ displayDateRange(session.draft) }}</dd></div>
            <div><dt>时区 / 周起始</dt><dd>{{ session.draft.timezone }} · {{ weekdayNames[(session.draft.weekStart||1)-1] }}</dd></div>
            <div><dt>学习方式</dt><dd>{{ session.draft.preference.contentModes.map(x=>modeNames[x]||x).join('、') }} · {{ guidanceNames[session.draft.preference.guidanceStyle] }}</dd></div>
            <div><dt>专注节奏</dt><dd>{{ session.draft.preference.focusMinutes }} 分钟 / 次 · 使用 {{ Math.round(session.draft.preference.capacityRatio*100) }}% 容量</dd></div>
            <div><dt>每周时间</dt><dd v-if="session.draft.availability.length" class="slot-summary"><span v-for="(s,i) in session.draft.availability" :key="i">{{ weekdayNames[s.weekday-1] }} {{ shortTime(s.start) }}–{{ shortTime(s.end) }}</span></dd><dd v-else>待补充</dd></div>
            <div v-if="session.draft.backgroundText"><dt>背景摘要</dt><dd>{{ session.draft.backgroundText }}</dd></div>
          </dl>
          <details v-if="Object.keys(session.draft.evidence||{}).length" class="evidence-box"><summary>查看字段依据</summary><p v-for="(value,key) in session.draft.evidence" :key="key"><b>{{ key }}</b>{{ value }}</p></details>
          <div class="confirm-area"><p>{{ session.readyToConfirm ? '字段已经和后端画像要求吻合。确认后会写入画像、偏好和每周可用时间，并生成一个只读版本。' : '继续对话补齐缺失字段后，才能确认保存画像。' }}</p><el-button class="full" type="primary" size="large" :loading="confirming" :disabled="sending || !session.readyToConfirm" @click="confirmDraft">{{ session.readyToConfirm ? '确认并保存画像' : '等待补齐画像字段' }}</el-button></div>
          <button v-if="profileVersions.length" class="version-stack" type="button" @click="versionHistoryVisible=true">
            <span class="version-stack-title">画像版本历史</span>
            <span class="version-stack-cards" aria-hidden="true">
              <span
                v-for="(item,index) in recentProfileVersions"
                :key="item.id || item.versionNo"
                class="version-card"
                :style="{ transform:`translateY(${index*7}px) scale(${1-index*.035})`, zIndex:recentProfileVersions.length-index }"
              >
                <b>V{{ item.versionNo }}</b>
                <small>{{ versionConfidence(item) }}% · {{ versionDate(item) }}</small>
              </span>
            </span>
            <em>点击查看 {{ profileVersions.length }} 条历史记录</em>
          </button>
        </aside>
      </div>

      <section v-if="manualOpen" class="panel manual-panel">
        <div class="panel-title"><div><h3>高级手动编辑</h3><p>用于精确修正 AI 草稿或直接维护字段。日期范围支持任意 1～365 天。</p></div><span class="tag">不会自动覆盖访谈草稿</span></div>
        <el-form label-position="top">
          <div class="manual-grid">
            <el-form-item label="学习方向"><el-select v-model="profile.directionId" clearable filterable placeholder="选择目录方向；清空后可自定义"><el-option v-for="d in directions" :key="d.id" :value="Number(d.id)" :label="d.name"/></el-select></el-form-item>
            <el-form-item v-if="!profile.directionId" label="自定义方向"><el-input v-model="profile.customDirection" maxlength="120"/></el-form-item>
            <el-form-item label="当前阶段"><el-select v-model="profile.currentStage"><el-option value="BEGINNER" label="入门"/><el-option value="INTERMEDIATE" label="进阶"/><el-option value="ADVANCED" label="高级"/></el-select></el-form-item>
            <el-form-item label="计划起止日期"><el-date-picker v-model="profile.planDates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false"/><small class="field-help">共 {{ manualPeriodDays }} 天</small></el-form-item>
            <el-form-item label="业务时区"><el-input v-model="profile.timezone"/></el-form-item>
            <el-form-item label="每周起始日"><el-select v-model="profile.weekStart"><el-option v-for="(name,index) in weekdayNames" :key="name" :label="name" :value="index+1"/></el-select></el-form-item>
            <el-form-item label="背景说明" class="wide"><el-input v-model="profile.backgroundText" type="textarea" :rows="3" maxlength="2000" show-word-limit/></el-form-item>
          </div>
          <div class="manual-divider"><b>学习偏好</b><span>这是规划软约束，不会覆盖截止日期和前置知识。</span></div>
          <div class="manual-grid">
            <el-form-item label="文档学习方式"><el-checkbox-group v-model="pref.contentModes"><el-checkbox v-for="option in contentModeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox></el-checkbox-group></el-form-item>
            <el-form-item label="引导方式"><el-radio-group v-model="pref.guidanceStyle"><el-radio-button value="SOCRATIC">启发式</el-radio-button><el-radio-button value="DIRECT">直接讲解</el-radio-button></el-radio-group></el-form-item>
            <el-form-item label="专注时长"><el-slider v-model="pref.focusMinutes" :min="10" :max="180" show-input/></el-form-item>
            <el-form-item label="容量比例"><el-slider v-model="pref.capacityRatio" :min=".6" :max=".95" :step=".05" show-input/></el-form-item>
          </div>
          <div class="manual-divider"><b>每周可用时间</b><el-button size="small" @click="addSlot">添加时段</el-button></div>
          <div v-for="(s,i) in slots" :key="i" class="slot-row"><el-select v-model="s.weekday"><el-option v-for="(name,index) in weekdayNames" :key="name" :value="index+1" :label="name"/></el-select><el-time-select v-model="s.start" start="00:00" step="00:30" end="23:30"/><span>至</span><el-time-select v-model="s.end" start="00:00" step="00:30" end="23:30"/><el-select v-model="s.energyLevel"><el-option value="HIGH" label="高能量"/><el-option value="MEDIUM" label="中等"/><el-option value="LOW" label="低能量"/></el-select><el-button text type="danger" @click="slots.splice(i,1)">删除</el-button></div>
          <div class="manual-actions"><el-button @click="manualOpen=false">取消</el-button><el-button type="primary" :loading="manualSaving" @click="saveManual">保存并生成画像版本</el-button></div>
        </el-form>
      </section>
    </template>

    <el-dialog v-model="versionHistoryVisible" title="画像版本历史" width="760px" class="profile-version-dialog">
      <div v-if="profileVersions.length" class="version-history">
        <article v-for="item in profileVersions" :key="item.id || item.versionNo" class="version-history-item">
          <header>
            <div><b>画像 V{{ item.versionNo }}</b><small>{{ versionDate(item) }} · {{ versionTriggerName(item) }}</small></div>
            <span>置信度 {{ versionConfidence(item) }}%</span>
          </header>
          <dl>
            <div><dt>计划周期</dt><dd>{{ versionPeriod(item) }}</dd></div>
            <div><dt>推荐难度</dt><dd>{{ item.snapshot?.recommendedDifficulty || '未记录' }}</dd></div>
            <div><dt>每日任务</dt><dd>{{ item.snapshot?.dailyRecommendedTasks || '未记录' }}</dd></div>
          </dl>
          <ul v-if="item.snapshot?.riskNotices?.length">
            <li v-for="risk in item.snapshot.riskNotices" :key="risk">{{ risk }}</li>
          </ul>
        </article>
      </div>
      <el-empty v-else description="暂无画像历史" />
    </el-dialog>
  </div>
</template>

<style scoped>
.profile-wrap{max-width:1240px;margin:auto}.head-actions{display:flex;align-items:center;gap:8px}.interview-layout{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(330px,.75fr);gap:18px;align-items:start}.chat-panel{padding:0;overflow:hidden}.chat-head{display:flex;align-items:center;justify-content:space-between;padding:18px 22px;border-bottom:1px solid var(--line);background:rgba(255,255,255,.38)}.chat-head>div{display:flex;align-items:center;gap:11px}.chat-head b,.chat-head small{display:block}.chat-head b{font-size:13px}.chat-head small{margin-top:3px;color:var(--muted);font-size:10px}.assistant-orb{display:grid;place-items:center;width:39px;height:39px;border-radius:14px 14px 14px 5px;color:#f7f3e8;background:linear-gradient(145deg,#225e49,#0f372a);font:600 18px var(--display)}.chat-stream{height:480px;overflow:auto;padding:24px;background:radial-gradient(circle at 85% 12%,rgba(214,233,221,.45),transparent 28%)}.message-row{display:flex;margin-bottom:16px}.message-row.user{justify-content:flex-end}.message-bubble{max-width:78%;padding:13px 15px 10px;border-radius:7px 18px 18px 18px;background:rgba(255,255,255,.87);box-shadow:0 8px 25px rgba(38,68,55,.07)}.message-row.user .message-bubble{border-radius:18px 7px 18px 18px;color:white;background:linear-gradient(145deg,#26795d,#14533f)}.message-bubble p{margin:0;white-space:pre-wrap;font-size:13px;line-height:1.75}.message-bubble small{display:block;margin-top:6px;color:#91a098;font-size:9px}.message-row.user small{color:rgba(255,255,255,.65);text-align:right}.typing{display:flex;gap:5px;padding:17px 20px}.typing i{width:6px;height:6px;border-radius:50%;background:#7d9588;animation:pulse 1.1s infinite}.typing i:nth-child(2){animation-delay:.16s}.typing i:nth-child(3){animation-delay:.32s}.streaming{min-width:90px}.stream-caret{display:inline-block;width:2px;height:1em;margin-left:3px;vertical-align:-2px;background:var(--green);animation:blink .8s step-end infinite}@keyframes pulse{0%,70%,100%{opacity:.3;transform:translateY(0)}35%{opacity:1;transform:translateY(-4px)}}@keyframes blink{50%{opacity:0}}.quick-prompts{display:flex;gap:8px;overflow:auto;padding:0 20px 14px}.quick-prompts button{flex:none;padding:8px 11px;border:1px solid var(--line);border-radius:99px;color:#53635b;background:rgba(255,255,255,.55);font-size:10px}.quick-prompts button:hover{color:var(--green);border-color:rgba(23,107,80,.28)}.chat-compose{padding:16px 20px 18px;border-top:1px solid var(--line);background:rgba(248,250,246,.72)}.chat-compose>div{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:9px}.chat-compose small{color:var(--muted);font-size:9px}.confirmed-note{padding:18px 22px;color:var(--green);background:var(--mint);font-size:11px;text-align:center}.draft-panel{position:sticky;top:110px}.draft-title{display:flex;align-items:center;justify-content:space-between}.draft-title h3{margin:5px 0 14px;font:500 22px var(--display)}.draft-title strong{color:var(--green);font:500 27px var(--display)}.missing-box,.ready-box{display:flex;flex-wrap:wrap;gap:6px;margin:17px 0 4px;padding:12px;border-radius:13px}.missing-box{background:#fff7e8}.missing-box b{width:100%;color:#75562d;font-size:10px}.missing-box span{padding:4px 8px;border-radius:99px;color:#75562d;background:rgba(201,150,69,.14);font-size:9px}.ready-box{background:rgba(223,238,229,.82);border:1px solid rgba(23,107,80,.12)}.ready-box b{width:100%;color:var(--green);font-size:11px}.ready-box span{color:#4e6258;font-size:10px;line-height:1.6}.draft-list{margin:12px 0 0}.draft-list>div{display:grid;grid-template-columns:92px 1fr;gap:10px;padding:11px 0;border-bottom:1px solid rgba(31,62,49,.075)}.draft-list dt{color:var(--muted);font-size:10px}.draft-list dd{margin:0;color:#26372f;font-size:11px;line-height:1.55;text-align:right}.slot-summary{display:flex;flex-direction:column;gap:3px}.evidence-box{margin-top:14px;padding:11px 13px;border-radius:12px;background:rgba(225,236,228,.55)}.evidence-box summary{color:var(--green);font-size:10px;font-weight:700;cursor:pointer}.evidence-box p{margin:9px 0 0;color:var(--muted);font-size:9px;line-height:1.5}.evidence-box p b{margin-right:6px;color:#42544a}.confirm-area{margin-top:18px}.confirm-area p{margin:0 0 11px;color:var(--muted);font-size:9px;line-height:1.6}.version-chip{margin-top:10px;color:var(--muted);font-size:9px;text-align:center}.manual-panel{margin-top:20px}.manual-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px 24px}.wide{grid-column:1/-1}.field-help{display:block;margin-top:5px;color:var(--muted);font-size:9px}.manual-divider{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:19px 0 14px;padding-top:17px;border-top:1px solid var(--line)}.manual-divider b{font:500 17px var(--display)}.manual-divider span{color:var(--muted);font-size:10px}.slot-row{display:grid;grid-template-columns:120px 145px 22px 145px 130px 56px;gap:9px;align-items:center;margin-bottom:10px}.manual-actions{display:flex;justify-content:flex-end;gap:9px;margin-top:22px}@media(max-width:1000px){.interview-layout{grid-template-columns:1fr}.draft-panel{position:static}.chat-stream{height:440px}}@media(max-width:700px){.head-actions{align-items:stretch;flex-direction:column}.chat-stream{height:410px;padding:16px}.message-bubble{max-width:90%}.manual-grid{grid-template-columns:1fr}.wide{grid-column:auto}.slot-row{grid-template-columns:1fr 1fr}.slot-row>span{display:none}.manual-divider{align-items:flex-start;flex-direction:column}.draft-list>div{grid-template-columns:82px 1fr}}
.version-stack{display:block;width:100%;margin-top:14px;padding:13px 13px 17px;border:1px solid rgba(23,107,80,.14);border-radius:18px;background:linear-gradient(145deg,rgba(255,255,255,.9),rgba(226,239,231,.72));box-shadow:0 14px 34px rgba(39,74,58,.08);cursor:pointer;text-align:left;transition:.2s ease}
.version-stack:hover{transform:translateY(-2px);border-color:rgba(23,107,80,.28);box-shadow:0 18px 42px rgba(39,74,58,.12)}
.version-stack-title{display:block;margin-bottom:10px;color:#31473b;font-size:11px;font-weight:700}
.version-stack-cards{position:relative;display:block;height:69px}
.version-card{position:absolute;inset:0;display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 14px;border:1px solid rgba(23,107,80,.12);border-radius:15px;background:rgba(255,255,255,.94);box-shadow:0 10px 24px rgba(39,74,58,.08);transform-origin:top center}
.version-card b{color:var(--green);font:600 18px var(--display)}
.version-card small{color:var(--muted);font-size:9px}
.version-stack em{display:block;margin-top:12px;color:#5b6d63;font-size:10px;font-style:normal;text-align:center}
.version-history{display:flex;flex-direction:column;gap:12px;max-height:62vh;overflow:auto;padding-right:4px}
.version-history-item{padding:15px 16px;border:1px solid rgba(23,107,80,.12);border-radius:16px;background:linear-gradient(145deg,rgba(255,255,255,.98),rgba(245,249,246,.88))}
.version-history-item header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:13px}
.version-history-item header b,.version-history-item header small{display:block}
.version-history-item header b{color:#243a2f;font:600 18px var(--display)}
.version-history-item header small{margin-top:4px;color:var(--muted);font-size:10px}
.version-history-item header span{flex:none;padding:5px 9px;border-radius:99px;color:var(--green);background:rgba(223,238,229,.9);font-size:10px;font-weight:700}
.version-history-item dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:0}
.version-history-item dl div{padding:10px;border-radius:12px;background:rgba(244,247,243,.9)}
.version-history-item dt{color:var(--muted);font-size:9px}
.version-history-item dd{margin:5px 0 0;color:#31473b;font-size:11px;line-height:1.45}
.version-history-item ul{margin:12px 0 0;padding-left:18px;color:#76623d;font-size:10px;line-height:1.6}
@media(max-width:700px){.version-history-item dl{grid-template-columns:1fr}.version-history-item header{flex-direction:column}.version-history-item header span{align-self:flex-start}}
</style>
