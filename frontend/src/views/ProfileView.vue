<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, postSse } from '../api/http'

type Slot = { weekday:number; start:string; end:string; energyLevel:string }
type WeekdayOption = { value:number; label:string }
type Preference = { contentModes:string[]; guidanceStyle:string; taskGranularity:string; focusMinutes:number; capacityRatio:number; difficultyMin:number; difficultyMax:number; reminders:Record<string,boolean> }
type Draft = {
  timezone:string; weekStart:number; planStartDate?:string; planEndDate?:string; directionId?:string;
  directionName?:string; customDirection?:string; sourceType?:'CATALOG'|'CUSTOM';
  knowledgeBaseDirection?:boolean; currentStage?:string; backgroundText?:string;
  preference:Preference; availability:Slot[]; evidence:Record<string,string>
}
type InterviewMessage = { id:string; role:string; content:string; source:string; createdAt:string }
type InterviewSession = { id:string; status:string; draft:Draft; missingFields:string[]; completenessPercent:number; readyToConfirm:boolean; assistantMode:string; version:number; messages:InterviewMessage[] }
type ProfileVersionView = {
  id?:string; versionNo:number; snapshotJson?:string; confidence:number; triggerType?:string; createdAt?:string; snapshot:Record<string,any>
}
type ProfileGenerationJob = { id?:string; publicId:string; userId?:string; status:string; profileVersionId?:string; errorCode?:string }

const directions = ref<any[]>([])
const router = useRouter()
const session = ref<InterviewSession>()
const starting = ref(true)
const sending = ref(false)
const confirming = ref(false)
const manualSaving = ref(false)
const regenerating = ref(false)
const manualOpen = ref(false)
const chatInput = ref('')
const chatBox = ref<HTMLElement>()
const pendingUserMessage = ref('')
const streamingAssistant = ref('')
let streamController:AbortController|undefined
const profileVersion = ref<number>()
const profileStatus = ref<string>()
const preferenceVersion = ref<number>()
const profileVersions = ref<ProfileVersionView[]>([])
const versionHistoryVisible = ref(false)
const availabilityExceptions = ref<any[]>([])
const knowledgePoints = ref<any[]>([])
const selfAssessments = ref<any[]>([])
const exceptionForm = reactive({ date:dayjs().add(1,'day').format('YYYY-MM-DD'), availableMinutes:0, reason:'' })
const selfAssessmentForm = reactive({ knowledgePointId:undefined as string|undefined, level:2, lastStudiedAt:dayjs().format('YYYY-MM-DD'), note:'' })

const today = dayjs().format('YYYY-MM-DD')
const profile = reactive({
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai', weekStart: 1,
  planDates: [today, dayjs().add(27, 'day').format('YYYY-MM-DD')] as string[], backgroundText: '',
  directionId: undefined as string|undefined, customDirection: '', currentStage: 'BEGINNER',
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
const manualPeriodDays = computed(() => {
  if (profile.planDates?.length !== 2) return 0
  const start = dayjs(profile.planDates[0])
  const end = dayjs(profile.planDates[1])
  if (!start.isValid() || !end.isValid()) return 0
  return end.diff(start, 'day') + 1
})
const selectableWeekdays = computed<WeekdayOption[]>(() => {
  const fallback = weekdayNames.map((label, index) => ({ value:index + 1, label }))
  if (profile.planDates?.length !== 2) return fallback
  const start = dayjs(profile.planDates[0])
  const end = dayjs(profile.planDates[1])
  if (!start.isValid() || !end.isValid() || end.isBefore(start)) return fallback
  const days = end.diff(start, 'day') + 1
  if (days >= 7) return fallback
  return Array.from({ length:days }, (_, offset) => {
    const date = start.add(offset, 'day')
    const weekday = date.day() === 0 ? 7 : date.day()
    return { value:weekday, label:`${weekdayNames[weekday - 1]}（${date.format('MM-DD')}）` }
  })
})
const interviewPeriodDays = computed(() => {
  const d = session.value?.draft
  return d?.planStartDate && d?.planEndDate ? dayjs(d.planEndDate).diff(dayjs(d.planStartDate), 'day') + 1 : 0
})
const hasUserMessages = computed(() => pendingUserMessage.value || session.value?.messages.some(m => m.role === 'USER'))
const recentProfileVersions = computed(() => profileVersions.value.slice(0, 3))
const manualKnowledgeBaseDirection = computed(() => Boolean(profile.directionId))

watch(() => [...profile.planDates], () => normalizeSlotsForPeriod())
watch(() => profile.directionId, () => { void loadKnowledgePoints() })

onBeforeUnmount(() => streamController?.abort())

onMounted(async () => {
  try {
    directions.value = await api<any[]>({ url:'/learning-directions' })
    const current = await api<any>({ url:'/profiles/me' })
    if (current) applyCurrentProfile(current)
    try { await loadAvailability(false) } catch { /* 新用户尚无画像 */ }
    await Promise.all([loadKnowledgePoints(), loadSelfAssessments()])
    await loadGenerated()
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
  profileStatus.value = current.status
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
    await waitProfileGeneration(result.generationJob)
    await Promise.all([loadGenerated(), refreshCurrentProfile()])
    ElMessage.success('学习画像已确认保存，并生成了可追溯版本')
    showProfileNextStepGuide()
  } finally { confirming.value = false }
}

async function waitProfileGeneration(job:ProfileGenerationJob) {
  if (!job?.publicId) return
  let current = job
  for (let attempt = 0; ['QUEUED','RUNNING'].includes(current.status) && attempt < 90; attempt++) {
    await new Promise(resolve => window.setTimeout(resolve, 1000))
    current = await api<any>({ url:`/profiles/me/generation-jobs/${job.publicId}` })
  }
  if (current.status === 'FAILED') throw new Error(`画像分析失败：${current.errorCode || '请稍后重试'}`)
  if (current.status !== 'SUCCEEDED') throw new Error('画像仍在后台分析，请稍后查看版本记录')
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
  if (!session.value) return ElMessage.warning('画像访谈尚未就绪，请刷新后重试')
  if (!profile.directionId && !profile.customDirection.trim()) return ElMessage.warning('请选择或填写学习方向')
  if (manualPeriodDays.value < 1 || manualPeriodDays.value > 365) return ElMessage.warning('计划日期范围必须是 1～365 天')
  if (!slots.value.length) return ElMessage.warning('请至少设置一个每周可用时段')
  if (slots.value.length > 7) return ElMessage.warning('每周最多配置 7 个可用时段')
  if (new Set(slots.value.map(slot => slot.weekday)).size !== slots.value.length) {
    return ElMessage.warning('同一个星期只能配置一个可用时段')
  }
  const allowed = new Set(selectableWeekdays.value.map(option => option.value))
  if (slots.value.some(slot => !allowed.has(slot.weekday))) {
    return ElMessage.warning('可用星期必须位于当前计划起止日期范围内')
  }
  const contentModes = validContentModes(pref.contentModes)
  if (!contentModes.length) return ElMessage.warning('请至少选择一种文档学习方式')
  manualSaving.value = true
  try {
    const result = await api<any>({ method:'POST',url:'/profiles/me/manual-save',data:{
      interviewSessionId:session.value.id,
      interviewVersion:session.value.version,
      profile:{
        timezone:profile.timezone,weekStart:profile.weekStart,planPeriodDays:manualPeriodDays.value,
        planStartDate:profile.planDates[0],planEndDate:profile.planDates[1],backgroundText:profile.backgroundText,
        directions:[{directionId:profile.directionId,customDirection:profile.directionId?undefined:profile.customDirection.trim(),currentStage:profile.currentStage,primary:true}],
        version:profileVersion.value,
      },
      preference:{...pref,contentModes,version:preferenceVersion.value},
      availability:{slots:slots.value},
    } })
    applyCurrentProfile(result.profile)
    session.value=result.interview
    await loadAvailability(true)
    await waitProfileGeneration(result.generationJob)
    await Promise.all([loadGenerated(), refreshCurrentProfile()])
    manualOpen.value=false
    ElMessage.success(`手动画像已保存为 ${manualPeriodDays.value} 天周期，右侧草稿已同步`)
    showProfileNextStepGuide()
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
  try {
    let parsed:any = raw?.snapshotJson ? JSON.parse(raw.snapshotJson) : {}
    if (typeof parsed === 'string') parsed = JSON.parse(parsed)
    snapshot = parsed && typeof parsed === 'object' ? parsed : {}
  } catch { snapshot = {} }
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
  availabilityExceptions.value = availability.exceptions || []
  if (availability.rules?.length || replaceWhenEmpty) {
    slots.value = (availability.rules||[]).map((x:any) => ({
      weekday:x.weekday,start:shortTime(x.startTime||x.start),end:shortTime(x.endTime||x.end),energyLevel:x.energyLevel,
    }))
  }
}

async function loadKnowledgePoints() {
  if (!profile.directionId) {
    knowledgePoints.value = []
    selfAssessmentForm.knowledgePointId = undefined
    return
  }
  knowledgePoints.value = await api<any[]>({ url:'/knowledge-points', params:{ directionId:profile.directionId || undefined } })
  if (!knowledgePoints.value.some(item => String(item.id) === selfAssessmentForm.knowledgePointId)) {
    selfAssessmentForm.knowledgePointId = knowledgePoints.value[0]?.id ? String(knowledgePoints.value[0].id) : undefined
  }
}

async function loadSelfAssessments() {
  selfAssessments.value = await api<any[]>({ url:'/profiles/me/self-assessments' })
}

async function saveAvailabilityException() {
  await api({ method:'PUT', url:`/profiles/me/availability-exceptions/${exceptionForm.date}`,
    data:{ availableMinutes:exceptionForm.availableMinutes, reason:exceptionForm.reason } })
  await Promise.all([loadAvailability(false), refreshCurrentProfile()])
  ElMessage.success('日期例外已保存；画像已标记为需要重新生成')
}

async function addSelfAssessment() {
  if (!selfAssessmentForm.knowledgePointId) return ElMessage.warning('请先选择知识点')
  await api({ method:'POST', url:'/profiles/me/self-assessments', data:selfAssessmentForm })
  selfAssessmentForm.note=''
  await Promise.all([loadSelfAssessments(), refreshCurrentProfile()])
  ElMessage.success('自评证据已记录；重新生成画像后会纳入分析')
}

async function refreshCurrentProfile() {
  const current = await api<any>({ url:'/profiles/me' })
  if (current) applyCurrentProfile(current)
}

async function regenerateProfile() {
  regenerating.value = true
  try {
    const job = await api<ProfileGenerationJob>({ method:'POST', url:'/profiles/me/generation-jobs' })
    await waitProfileGeneration(job)
    await Promise.all([refreshCurrentProfile(), loadGenerated()])
    ElMessage.success('画像已根据最新资料重新生成')
  } finally { regenerating.value = false }
}

async function scrollToBottom() { await nextTick(); chatBox.value?.scrollTo({ top:chatBox.value.scrollHeight, behavior:'smooth' }) }
function weekdayUsedByOther(weekday:number, currentIndex:number) {
  return slots.value.some((slot, index) => index !== currentIndex && slot.weekday === weekday)
}
function addSlot(){
  const nextWeekday = selectableWeekdays.value
    .map(option => option.value)
    .find(day => !slots.value.some(slot => slot.weekday === day))
  if (!nextWeekday) return ElMessage.info('当前日期范围内的星期都已经配置')
  slots.value.push({weekday:nextWeekday,start:'19:00',end:'20:00',energyLevel:'MEDIUM'})
}
function normalizeSlotsForPeriod() {
  const allowed = new Set(selectableWeekdays.value.map(option => option.value))
  const filtered = slots.value.filter(slot => allowed.has(slot.weekday))
  if (filtered.length !== slots.value.length) {
    slots.value = filtered
    ElMessage.info('已移除不在当前计划日期范围内的可用星期')
  }
}
function applyPeriodDays(days:number) {
  const start = dayjs(profile.planDates?.[0] || today)
  const normalizedStart = start.isValid() ? start : dayjs(today)
  profile.planDates = [
    normalizedStart.format('YYYY-MM-DD'),
    normalizedStart.add(days - 1, 'day').format('YYYY-MM-DD'),
  ]
}
function fillPrompt(text:string){ chatInput.value=text; nextTick(() => document.querySelector<HTMLTextAreaElement>('.chat-compose textarea')?.focus()) }
function displayDirection(d?:Draft){ return d?.directionName || d?.customDirection || '待补充' }
function isKnowledgeBaseDirection(d?:Draft|Record<string,any>) {
  if (!d) return false
  if (typeof d.knowledgeBaseDirection === 'boolean') return d.knowledgeBaseDirection
  if (d.sourceType) return d.sourceType === 'CATALOG'
  return Boolean(d.directionId)
}
function directionSource(d?:Draft|Record<string,any>) {
  if (!d || (!d.directionId && !d.customDirection && !d.directionName)) return '待确认'
  return isKnowledgeBaseDirection(d) ? '系统知识库方向' : '自定义探索方向'
}
function versionDirectionSource(item:ProfileVersionView) {
  const values = Array.isArray(item.snapshot?.directions) ? item.snapshot.directions : []
  if (!values.length) return '未记录'
  return values.map((direction:any) => `${direction.name || '未命名方向'} · ${directionSource(direction)}`).join('；')
}
function displayDateRange(d?:Draft){ return d?.planStartDate && d?.planEndDate ? `${d.planStartDate} → ${d.planEndDate}（${interviewPeriodDays.value} 天）` : '待补充' }
function draftSlotLabel(d:Draft, slot:Slot) {
  const start = d.planStartDate ? dayjs(d.planStartDate) : undefined
  const end = d.planEndDate ? dayjs(d.planEndDate) : undefined
  let dateHint = ''
  if (start?.isValid() && end?.isValid() && end.diff(start, 'day') < 7) {
    for (let offset = 0; offset <= end.diff(start, 'day'); offset += 1) {
      const date = start.add(offset, 'day')
      const weekday = date.day() === 0 ? 7 : date.day()
      if (weekday === slot.weekday) { dateHint = `（${date.format('MM-DD')}）`; break }
    }
  }
  return `${weekdayNames[slot.weekday-1]}${dateHint} ${shortTime(slot.start)}–${shortTime(slot.end)}`
}
function shortTime(value?:string){ return value ? value.slice(0,5) : '' }
function validContentModes(modes?:string[]) { return contentModeOptions.map(x => x.value).filter(value => modes?.includes(value)) }
function sanitizeContentModes(modes?:string[]) { const selected = validContentModes(modes); return selected.length ? selected : ['TEXT','PRACTICE'] }
</script>

<template>
  <div class="profile-wrap">
    <div class="page-head">
      <div><span class="eyebrow">AI PROFILE INTERVIEW</span><h2>聊一聊，让画像自己成形</h2><p>AI 只整理草稿，不会自行修改正式数据；你检查并确认后才会保存。</p></div>
      <div class="head-actions"><el-button :disabled="sending" @click="manualOpen=true">高级手动编辑</el-button><el-button text :disabled="sending" @click="restartInterview">重新访谈</el-button></div>
    </div>

    <el-alert v-if="profileStatus==='DRAFT' && profileVersion !== undefined" type="warning" show-icon :closable="false" class="profile-regeneration-alert">
      <template #title>画像资料已经更新，需要重新生成正式画像版本</template>
      <el-button type="primary" :loading="regenerating" :disabled="confirming || manualSaving" @click="regenerateProfile">重新生成画像</el-button>
    </el-alert>

    <el-skeleton v-if="starting" :rows="8" animated class="panel"/>
    <template v-else-if="session">
      <div class="interview-layout">
        <section class="panel chat-panel">
          <div class="chat-head">
            <div><span class="assistant-orb">序</span><div><b>画像访谈助手</b><small>{{ sending?'正在流式生成…':session.assistantMode==='MANUAL'?'高级手动编辑已同步':session.assistantMode==='AI'?'AI 正在参与结构化整理':'规则引导模式' }} · 保存前由你确认</small></div></div>
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
          <div class="draft-title"><div><span class="eyebrow">STRUCTURED PROFILE</span><h3>{{ session.status==='CONFIRMED'?'已保存的正式画像':'待确认的画像草稿' }}</h3></div><strong>{{ session.completenessPercent }}%</strong></div>
          <el-progress :percentage="session.completenessPercent" :show-text="false" :stroke-width="8"/>
          <div v-if="session.readyToConfirm" class="ready-box"><b>画像草稿已完整</b><span>确认后才会写入正式画像；如果有不满意的地方，继续在左侧告诉助手修改。</span></div>
          <div v-else-if="session.missingFields.length" class="missing-box"><b>还需要确认</b><span v-for="item in session.missingFields" :key="item">{{ item }}</span></div>
          <dl class="draft-list">
            <div><dt>学习方向</dt><dd>{{ displayDirection(session.draft) }}</dd></div>
            <div>
              <dt>方向来源</dt>
              <dd><span :class="['direction-source-badge', isKnowledgeBaseDirection(session.draft) ? 'catalog' : 'custom']">
                {{ directionSource(session.draft) }}
              </span></dd>
            </div>
            <div><dt>当前阶段</dt><dd>{{ stageNames[session.draft.currentStage||''] || '待补充' }}</dd></div>
            <div><dt>自定义周期</dt><dd>{{ displayDateRange(session.draft) }}</dd></div>
            <div><dt>时区 / 周起始</dt><dd>{{ session.draft.timezone }} · {{ weekdayNames[(session.draft.weekStart||1)-1] }}</dd></div>
            <div><dt>学习方式</dt><dd>{{ session.draft.preference.contentModes.map(x=>modeNames[x]||x).join('、') }} · {{ guidanceNames[session.draft.preference.guidanceStyle] }}</dd></div>
            <div><dt>专注节奏</dt><dd>{{ session.draft.preference.focusMinutes }} 分钟 / 次 · 使用 {{ Math.round(session.draft.preference.capacityRatio*100) }}% 容量</dd></div>
            <div><dt>每周时间</dt><dd v-if="session.draft.availability.length" class="slot-summary"><span v-for="(s,i) in session.draft.availability" :key="i">{{ draftSlotLabel(session.draft,s) }}</span></dd><dd v-else>待补充具体星期和时间</dd></div>
            <div v-if="session.draft.backgroundText"><dt>背景摘要</dt><dd>{{ session.draft.backgroundText }}</dd></div>
          </dl>
          <details v-if="Object.keys(session.draft.evidence||{}).length" class="evidence-box"><summary>查看字段依据</summary><p v-for="(value,key) in session.draft.evidence" :key="key"><b>{{ key }}</b>{{ value }}</p></details>
          <div v-if="session.status==='ACTIVE'" class="confirm-area"><p>{{ session.readyToConfirm ? '字段已经和后端画像要求吻合。确认后会写入画像、偏好和每周可用时间，并生成一个只读版本。' : '继续对话补齐缺失字段后，才能确认保存画像。' }}</p><el-button class="full" type="primary" size="large" :loading="confirming" :disabled="sending || !session.readyToConfirm" @click="confirmDraft">{{ session.readyToConfirm ? '确认并保存画像' : '等待补齐画像字段' }}</el-button></div>
          <div v-else class="saved-profile-note"><b>这份画像已经保存</b><span>右侧字段与正式画像一致；需要修改时可重新访谈或再次使用高级手动编辑。</span></div>
          <button v-if="profileVersions.length" class="version-stack" type="button" @click="versionHistoryVisible=true">
            <span class="version-stack-title">画像版本历史</span>
            <span class="version-stack-cards" aria-hidden="true">
              <span v-for="item in recentProfileVersions" :key="item.id || item.versionNo" class="version-card">
                <b>V{{ item.versionNo }}</b>
                <small>{{ versionConfidence(item) }}% · {{ versionDate(item) }}</small>
              </span>
            </span>
            <em>点击查看 {{ profileVersions.length }} 条历史记录</em>
          </button>
        </aside>
      </div>

      <el-dialog v-model="manualOpen" title="高级手动编辑" width="820px" class="manual-edit-dialog" :close-on-click-modal="false">
        <p class="manual-dialog-desc">一次保存正式画像、偏好、可用时间和版本；全部成功后同步右侧画像。<span class="tag">原子保存 · 自动同步</span></p>
        <el-form label-position="top">
          <div class="manual-grid">
            <el-form-item label="学习方向">
              <el-select v-model="profile.directionId" clearable filterable placeholder="选择知识库方向；清空后可自定义">
                <el-option v-for="d in directions" :key="d.id" :value="String(d.id)" :label="`${d.name} · 知识库方向`"/>
              </el-select>
              <div :class="['direction-source-preview', manualKnowledgeBaseDirection ? 'catalog' : 'custom']">
                <b>{{ manualKnowledgeBaseDirection ? '系统知识库方向' : '自定义探索方向' }}</b>
                <span>{{ manualKnowledgeBaseDirection ? '可使用系统知识点、依赖关系和已维护资料来源' : '保存后由 Agent 进入探索阶段，可上传资料到个人知识库' }}</span>
              </div>
            </el-form-item>
            <el-form-item v-if="!profile.directionId" label="自定义方向"><el-input v-model="profile.customDirection" maxlength="120"/></el-form-item>
            <el-form-item label="当前阶段"><el-select v-model="profile.currentStage"><el-option value="BEGINNER" label="入门"/><el-option value="INTERMEDIATE" label="进阶"/><el-option value="ADVANCED" label="高级"/></el-select></el-form-item>
            <el-form-item label="计划起止日期">
              <el-date-picker v-model="profile.planDates" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false"/>
              <div class="period-tools"><small>实际保存 {{ manualPeriodDays }} 天（含首尾日期）</small><span><button type="button" @click="applyPeriodDays(7)">7 天</button><button type="button" @click="applyPeriodDays(14)">14 天</button><button type="button" @click="applyPeriodDays(30)">30 天</button></span></div>
            </el-form-item>
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
          <div class="manual-divider"><div><b>每周可用时间</b><span>只添加实际可学习的星期 · 已配置 {{ slots.length }}/{{ selectableWeekdays.length }} 天</span></div><el-button size="small" :disabled="slots.length>=selectableWeekdays.length" @click="addSlot">{{ slots.length>=selectableWeekdays.length?'日期范围内已配置完成':'添加一天' }}</el-button></div>
          <div v-for="(s,i) in slots" :key="i" class="slot-row"><el-select v-model="s.weekday"><el-option v-for="option in selectableWeekdays" :key="option.value" :value="option.value" :label="option.label" :disabled="weekdayUsedByOther(option.value,i)"/></el-select><el-time-select v-model="s.start" start="00:00" step="00:30" end="23:30"/><span>至</span><el-time-select v-model="s.end" start="00:00" step="00:30" end="23:30"/><el-select v-model="s.energyLevel"><el-option value="HIGH" label="高能量"/><el-option value="MEDIUM" label="中等"/><el-option value="LOW" label="低能量"/></el-select><el-button text type="danger" @click="slots.splice(i,1)">删除</el-button></div>
        </el-form>
        <template #footer>
          <el-button @click="manualOpen=false">取消</el-button>
          <el-button type="primary" :loading="manualSaving" @click="saveManual">保存并生成画像版本</el-button>
        </template>
      </el-dialog>

      <section class="profile-evidence-grid">
        <article class="panel evidence-editor">
          <div class="panel-title"><div><h3>特殊日期容量</h3><p>请假、考试或临时空闲可覆盖每周规则，0 分钟表示当天不安排任务。</p></div></div>
          <div class="evidence-form exception-form">
            <el-date-picker v-model="exceptionForm.date" value-format="YYYY-MM-DD" :clearable="false" :fallback-placements="['bottom', 'top']"/>
            <el-input-number v-model="exceptionForm.availableMinutes" :min="0" :max="960" :step="30"/>
            <el-input v-model="exceptionForm.reason" maxlength="500" placeholder="原因（可选）"/>
            <el-button type="primary" @click="saveAvailabilityException">保存</el-button>
          </div>
          <div v-if="availabilityExceptions.length" class="evidence-list">
            <span v-for="item in availabilityExceptions" :key="item.id || item.localDate"><b>{{ item.localDate }}</b>{{ item.availableMinutes }} 分钟 · {{ item.reason || '未填写原因' }}</span>
          </div>
          <el-empty v-else :image-size="52" description="未来 30 天暂无日期例外"/>
        </article>
        <article class="panel evidence-editor">
          <div class="panel-title"><div><h3>知识点自评</h3><p>自评作为低权重证据，不能替代测验，但能让初始计划更贴近你的基础。</p></div></div>
          <div class="evidence-form assessment-form">
            <el-select v-if="profile.directionId" v-model="selfAssessmentForm.knowledgePointId" filterable placeholder="选择知识点" :fallback-placements="['bottom', 'top']"><el-option v-for="item in knowledgePoints" :key="item.id" :value="String(item.id)" :label="item.name"/></el-select>
            <span v-else class="muted">自定义方向不关联公共知识点，请先选择公共目录方向后记录自评。</span>
            <el-rate v-model="selfAssessmentForm.level" :max="5"/>
            <el-date-picker v-model="selfAssessmentForm.lastStudiedAt" value-format="YYYY-MM-DD" :clearable="false" :fallback-placements="['bottom', 'top']"/>
            <el-input v-model="selfAssessmentForm.note" maxlength="1000" placeholder="补充说明（可选）"/>
            <el-button type="primary" :disabled="!selfAssessmentForm.knowledgePointId" @click="addSelfAssessment">记录自评</el-button>
          </div>
          <div v-if="selfAssessments.length" class="evidence-list"><span v-for="item in selfAssessments.slice(0,6)" :key="item.id"><b>知识点 #{{ item.knowledgePointId }}</b>{{ item.level }}/5 · {{ item.lastStudiedAt || '未填写学习日期' }}</span></div>
          <el-empty v-else :image-size="52" description="暂无自评证据"/>
        </article>
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
            <div><dt>方向来源</dt><dd>{{ versionDirectionSource(item) }}</dd></div>
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
.profile-regeneration-alert{margin-bottom:18px}
.profile-regeneration-alert :deep(.el-alert__content){width:100%}
.profile-regeneration-alert :deep(.el-alert__title){display:flex;align-items:center;justify-content:space-between;gap:16px;width:100%}
.profile-wrap{max-width:1240px;margin:auto}.head-actions{display:flex;align-items:center;gap:8px}.interview-layout{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(330px,.75fr);gap:18px;align-items:start}.chat-panel{padding:0;overflow:hidden}.chat-head{display:flex;align-items:center;justify-content:space-between;padding:18px 22px;border-bottom:1px solid var(--line);background:rgba(255,255,255,.38)}.chat-head>div{display:flex;align-items:center;gap:11px}.chat-head b,.chat-head small{display:block}.chat-head b{font-size:13px}.chat-head small{margin-top:3px;color:var(--muted);font-size:10px}.assistant-orb{display:grid;place-items:center;width:39px;height:39px;border-radius:14px 14px 14px 5px;color:#f7f3e8;background:linear-gradient(145deg,#225e49,#0f372a);font:600 18px var(--display)}.chat-stream{height:480px;overflow:auto;padding:24px;background:radial-gradient(circle at 85% 12%,rgba(214,233,221,.45),transparent 28%)}.message-row{display:flex;margin-bottom:16px}.message-row.user{justify-content:flex-end}.message-bubble{max-width:78%;padding:13px 15px 10px;border-radius:7px 18px 18px 18px;background:rgba(255,255,255,.87);box-shadow:0 8px 25px rgba(38,68,55,.07)}.message-row.user .message-bubble{border-radius:18px 7px 18px 18px;color:white;background:linear-gradient(145deg,#26795d,#14533f)}.message-bubble p{margin:0;white-space:pre-wrap;font-size:13px;line-height:1.75}.message-bubble small{display:block;margin-top:6px;color:var(--muted);font-size:9px}.message-row.user small{color:rgba(255,255,255,.65);text-align:right}.typing{display:flex;gap:5px;padding:17px 20px}.typing i{width:6px;height:6px;border-radius:50%;background:var(--muted);animation:pulse 1.1s infinite}.typing i:nth-child(2){animation-delay:.16s}.typing i:nth-child(3){animation-delay:.32s}.streaming{min-width:90px}.stream-caret{display:inline-block;width:2px;height:1em;margin-left:3px;vertical-align:-2px;background:var(--green);animation:blink .8s step-end infinite}@keyframes pulse{0%,70%,100%{opacity:.3;transform:translateY(0)}35%{opacity:1;transform:translateY(-4px)}}@keyframes blink{50%{opacity:0}}.quick-prompts{display:flex;gap:8px;overflow:auto;padding:0 20px 14px}.quick-prompts button{flex:none;padding:8px 11px;border:1px solid var(--line);border-radius:99px;color:#53635b;background:rgba(255,255,255,.55);font-size:10px}.quick-prompts button:hover{color:var(--green);border-color:rgba(23,107,80,.28)}.chat-compose{padding:16px 20px 18px;border-top:1px solid var(--line);background:var(--paper-soft)}.chat-compose>div{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:9px}.chat-compose small{color:var(--muted);font-size:9px}.confirmed-note{padding:18px 22px;color:var(--green);background:var(--mint);font-size:11px;text-align:center}.draft-panel{position:sticky;top:110px}.draft-title{display:flex;align-items:center;justify-content:space-between}.draft-title h3{margin:5px 0 14px;font:500 22px var(--display)}.draft-title strong{color:var(--green);font:500 27px var(--display)}.missing-box,.ready-box{display:flex;flex-wrap:wrap;gap:6px;margin:17px 0 4px;padding:12px;border-radius:13px}.missing-box{background:var(--seal)}.missing-box b{width:100%;color:#75562d;font-size:10px}.missing-box span{padding:4px 8px;border-radius:99px;color:#75562d;background:rgba(201,150,69,.14);font-size:9px}.ready-box{background:var(--mint);border:1px solid rgba(23,107,80,.12)}.ready-box b{width:100%;color:var(--green);font-size:11px}.ready-box span{color:#4e6258;font-size:10px;line-height:1.6}.draft-list{margin:12px 0 0}.draft-list>div{display:grid;grid-template-columns:92px 1fr;gap:10px;padding:11px 0;border-bottom:1px solid rgba(31,62,49,.075)}.draft-list dt{color:var(--muted);font-size:10px}.draft-list dd{margin:0;color:var(--ink);font-size:11px;line-height:1.55;text-align:right}.slot-summary{display:flex;flex-direction:column;gap:3px}.evidence-box{margin-top:14px;padding:11px 13px;border-radius:12px;background:var(--el-fill-color-light)}.evidence-box summary{color:var(--green);font-size:10px;font-weight:700;cursor:pointer}.evidence-box p{margin:9px 0 0;color:var(--muted);font-size:9px;line-height:1.5}.evidence-box p b{margin-right:6px;color:#42544a}.confirm-area{margin-top:18px}.confirm-area p{margin:0 0 11px;color:var(--muted);font-size:9px;line-height:1.6}.version-chip{margin-top:10px;color:var(--muted);font-size:9px;text-align:center}.manual-dialog-desc{margin:0 0 4px;color:var(--muted);font-size:11px;line-height:1.6}.manual-dialog-desc .tag{margin-left:8px}.manual-edit-dialog :deep(.el-dialog__body){max-height:68vh;overflow:auto;padding-top:12px}.manual-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:2px 24px}.wide{grid-column:1/-1}.field-help{display:block;margin-top:5px;color:var(--muted);font-size:9px}.manual-divider{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:19px 0 14px;padding-top:17px;border-top:1px solid var(--line)}.manual-divider b{font:500 17px var(--display)}.manual-divider span{color:var(--muted);font-size:10px}.manual-divider>div b,.manual-divider>div span{display:block}.manual-divider>div span{margin-top:4px}.slot-row{display:grid;grid-template-columns:120px 145px 22px 145px 130px 56px;gap:9px;align-items:center;margin-bottom:10px}@media(max-width:1000px){.interview-layout{grid-template-columns:1fr}.draft-panel{position:static}.chat-stream{height:440px}}@media(max-width:700px){.head-actions{align-items:stretch;flex-direction:column}.chat-stream{height:410px;padding:16px}.message-bubble{max-width:90%}.manual-grid{grid-template-columns:1fr}.wide{grid-column:auto}.slot-row{grid-template-columns:1fr 1fr}.slot-row>span{display:none}.manual-divider{align-items:flex-start;flex-direction:column}.draft-list>div{grid-template-columns:82px 1fr}.manual-edit-dialog{width:94%!important}}
.profile-evidence-grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:20px}.evidence-editor{min-width:0}.evidence-form{display:grid;gap:10px}.exception-form{grid-template-columns:175px 150px minmax(0,1fr) auto}.assessment-form{grid-template-columns:minmax(150px,1fr) 150px 175px}.assessment-form .el-input{grid-column:1/3}.assessment-form .el-button{grid-column:3}.evidence-form :deep(.el-date-editor),.evidence-form :deep(.el-input-number){width:100%}.evidence-list{display:grid;gap:7px;margin-top:14px}.evidence-list span{display:flex;justify-content:space-between;gap:12px;padding:9px 11px;border-radius:10px;background:var(--el-fill-color-light);color:var(--muted);font-size:10px}.evidence-list b{color:var(--ink)}@media(max-width:1180px){.profile-evidence-grid{grid-template-columns:1fr}.exception-form,.assessment-form{grid-template-columns:1fr 1fr}.assessment-form .el-input,.assessment-form .el-button{grid-column:auto}}@media(max-width:1000px){.profile-evidence-grid{grid-template-columns:1fr}.exception-form,.assessment-form{grid-template-columns:1fr 1fr}.assessment-form .el-input,.assessment-form .el-button{grid-column:auto}}@media(max-width:600px){.exception-form,.assessment-form{grid-template-columns:1fr}}
.version-stack{display:block;width:100%;margin-top:14px;padding:13px 13px 17px;border:1px solid rgba(23,107,80,.14);border-radius:18px;background:linear-gradient(145deg,rgba(255,255,255,.9),rgba(226,239,231,.72));box-shadow:0 14px 34px rgba(39,74,58,.08);cursor:pointer;text-align:left;transition:.2s ease}
.version-stack:hover{transform:translateY(-2px);border-color:rgba(23,107,80,.28);box-shadow:0 18px 42px rgba(39,74,58,.12)}
.version-stack-title{display:block;margin-bottom:10px;color:var(--ink);font-size:11px;font-weight:700}
.version-stack-cards{position:relative;display:flex;flex-direction:column}
.version-card{position:relative;display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 14px;border:1px solid rgba(23,107,80,.12);border-radius:15px;background:rgba(255,255,255,.94);box-shadow:0 10px 24px rgba(39,74,58,.08)}
.version-card + .version-card{margin-top:-32px}
.version-card:nth-child(1){z-index:3}.version-card:nth-child(2){z-index:2}.version-card:nth-child(3){z-index:1}
.version-card b{color:var(--green);font:600 18px var(--display)}
.direction-source-badge{display:inline-flex;padding:5px 9px;border-radius:99px;font-size:9px;font-weight:700}
.direction-source-badge.catalog{color:var(--green);background:var(--mint)}
.direction-source-badge.custom{color:#8a6420;background:var(--seal)}
.direction-source-preview{display:grid;gap:3px;width:100%;margin-top:8px;padding:10px 11px;border-radius:11px}
.direction-source-preview.catalog{color:#315c48;background:rgba(223,238,229,.72)}
.direction-source-preview.custom{color:#785f34;background:var(--seal)}
.direction-source-preview b{font-size:10px}
.direction-source-preview span{font-size:9px;line-height:1.5}
.version-card small{color:var(--muted);font-size:9px}
.version-stack em{display:block;margin-top:12px;color:var(--muted);font-size:10px;font-style:normal;text-align:center}
.version-history{display:flex;flex-direction:column;gap:12px;max-height:62vh;overflow:auto;padding-right:4px}
.version-history-item{padding:15px 16px;border:1px solid rgba(23,107,80,.12);border-radius:16px;background:linear-gradient(145deg,rgba(255,255,255,.98),rgba(245,249,246,.88))}
.version-history-item header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:13px}
.version-history-item header b,.version-history-item header small{display:block}
.version-history-item header b{color:var(--ink);font:600 18px var(--display)}
.version-history-item header small{margin-top:4px;color:var(--muted);font-size:10px}
.version-history-item header span{flex:none;padding:5px 9px;border-radius:99px;color:var(--green);background:var(--chip);font-size:10px;font-weight:700}
.version-history-item dl{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin:0}
.version-history-item dl div{padding:10px;border-radius:12px;background:rgba(244,247,243,.9)}
.version-history-item dt{color:var(--muted);font-size:9px}
.version-history-item dd{margin:5px 0 0;color:var(--ink);font-size:11px;line-height:1.45}
.version-history-item ul{margin:12px 0 0;padding-left:18px;color:#76623d;font-size:10px;line-height:1.6}
.period-tools{display:flex;align-items:center;justify-content:space-between;gap:10px;width:100%;margin-top:7px}.period-tools small{color:var(--green);font-size:10px;font-weight:700}.period-tools span{display:flex;gap:5px}.period-tools button{padding:4px 8px;border:1px solid rgba(23,107,80,.16);border-radius:999px;color:var(--green);background:var(--el-fill-color-light);font-size:9px;cursor:pointer}.period-tools button:hover{border-color:rgba(23,107,80,.36);background:var(--chip)}
.saved-profile-note{display:flex;flex-direction:column;gap:4px;margin-top:17px;padding:13px 14px;border:1px solid rgba(23,107,80,.13);border-radius:13px;background:rgba(223,238,229,.72)}.saved-profile-note b{color:var(--green);font-size:11px}.saved-profile-note span{color:#53665c;font-size:9px;line-height:1.6}
@media(max-width:700px){.version-history-item dl{grid-template-columns:1fr}.version-history-item header{flex-direction:column}.version-history-item header span{align-self:flex-start}}

/* 黑夜模式：scoped 覆盖（无法用 token 表达的暗色规则） */
html.dark .chat-head { background: rgba(255, 255, 255, .05); }
html.dark .chat-stream { background: radial-gradient(circle at 85% 12%, rgba(74, 168, 131, .12), transparent 28%); }
html.dark .message-bubble { background: rgba(255, 255, 255, .08); }
html.dark .quick-prompts button { color: var(--muted); background: rgba(255, 255, 255, .06); }
html.dark .quick-prompts button:hover { color: var(--green); border-color: rgba(74, 168, 131, .45); }
html.dark .missing-box b,
html.dark .missing-box span { color: var(--gold); }
html.dark .ready-box span { color: var(--ink); }
html.dark .evidence-box p b { color: var(--ink); }
html.dark .version-stack { background: linear-gradient(145deg, rgba(255, 255, 255, .08), rgba(74, 168, 131, .12)); }
html.dark .version-stack:hover { border-color: rgba(74, 168, 131, .4); }
html.dark .version-card { background: #1c2a23; }
html.dark .direction-source-badge.custom { color: var(--gold); }
html.dark .direction-source-preview.catalog { color: var(--green); background: rgba(74, 168, 131, .14); }
html.dark .direction-source-preview.custom { color: var(--gold); }
html.dark .version-history-item { background: linear-gradient(145deg, rgba(255, 255, 255, .08), rgba(255, 255, 255, .05)); }
html.dark .version-history-item dl div { background: rgba(255, 255, 255, .05); }
html.dark .version-history-item ul { color: var(--gold); }
html.dark .saved-profile-note { background: var(--el-fill-color-light); }
html.dark .saved-profile-note span { color: var(--ink); }
</style>
