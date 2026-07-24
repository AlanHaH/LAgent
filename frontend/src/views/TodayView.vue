<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/http'

const date = ref(dayjs().format('YYYY-MM-DD'))
const tasks = ref<any[]>([])
const upcoming = ref<any[]>([])
const selected = ref<any>()
const session = ref<any>()
const elapsed = ref(0)
const note = ref({ title: '学习笔记', markdown: '', version: undefined as number | undefined })
const timer = ref<number>()
const openPanels = ref<string[]>([])
const queueCollapsed = ref(false)
const chatMessages = ref<any[]>([])
const chatDraft = ref('')
const chatLoading = ref(false)
const chatScroll = ref<HTMLElement>()

const entity = computed(() => selected.value?.task || {})
const running = computed(() => Boolean(session.value) && session.value.status === 'RUNNING')
const completedCount = computed(() => tasks.value.filter((row) => row.task.lifecycleStatus === 'COMPLETED').length)
const plannedMinutes = computed(() => tasks.value.reduce((total, row) => total + Number(row.task.estimatedMinutes || 0), 0))
const currentDateLabel = computed(() => dayjs(date.value).isSame(dayjs(), 'day') ? '今天' : dayjs(date.value).format('M 月 D 日'))
const formatTime = computed(() => `${String(Math.floor(elapsed.value / 60)).padStart(2, '0')}:${String(elapsed.value % 60).padStart(2, '0')}`)

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

onMounted(load)
onUnmounted(() => timer.value && clearInterval(timer.value))

async function select(row: any) {
  selected.value = row
  note.value = { title: '学习笔记', markdown: '', version: undefined }
  chatMessages.value = []
  chatDraft.value = ''
  try {
    const result = await api<any>({ url: `/tasks/${row.task.publicId}/note` })
    if (result?.note) note.value = { title: result.note.title, markdown: result.currentVersion?.contentMarkdown || '', version: result.note.version }
  } catch { /* empty note */ }
}

async function scrollChat() {
  await nextTick()
  if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight
}

async function sendChat() {
  const text = chatDraft.value.trim()
  if (!text || chatLoading.value || !entity.value.publicId) return
  chatDraft.value = ''
  const history = chatMessages.value.slice(-6).map((item) => ({ role: item.role, content: item.content }))
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

async function jump(row: any) {
  date.value = dayjs(row.task.scheduledStart).format('YYYY-MM-DD')
  await load()
  const target = tasks.value.find((item) => item.task.publicId === row.task.publicId)
  if (target) await select(target)
}

function runClock() {
  if (timer.value) clearInterval(timer.value)
  timer.value = window.setInterval(() => elapsed.value++, 1000)
}

async function start() {
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
  await api({ method: 'POST', url: `/tasks/${entity.value.publicId}/completion`, data: { summary, confirmed: true } })
  await load()
  ElMessage.success('任务已完成，做得好')
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
  <div class="today-page">
    <section class="day-ribbon">
      <div class="day-intro">
        <span class="eyebrow light">DAILY RHYTHM / {{ dayjs(date).format('YYYY.MM.DD') }}</span>
        <h1>{{ currentDateLabel }}，只完成{{ currentDateLabel }}</h1>
        <p>不追赶整个计划，只进入眼前这一段专注。</p>
      </div>
      <div class="day-signals">
        <div><strong>{{ tasks.length }}</strong><small>段行动</small></div>
        <i />
        <div><strong>{{ plannedMinutes }}</strong><small>计划分钟</small></div>
        <i />
        <div><strong>{{ completedCount }}</strong><small>已经完成</small></div>
      </div>
      <div class="date-control">
        <span>切换日期</span>
        <el-date-picker v-model="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
      </div>
    </section>

    <div class="day-layout" :class="{ 'queue-is-collapsed': queueCollapsed }">
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
              <div><b>{{ row.task.title }}</b><small>{{ row.task.estimatedMinutes }} 分钟 · {{ statusText(row.task.lifecycleStatus) }}</small></div>
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
            <div class="focus-side">
              <div v-if="session" class="mini-timer" :class="{ running }">
                <i />
                <span class="mini-time">{{ formatTime }}</span>
                <small>{{ session?.status === 'PAUSED' ? '已暂停' : '专注中' }}</small>
                <el-button v-if="running" size="small" @click="pause">暂停</el-button>
                <el-button v-else size="small" type="primary" @click="resume">继续</el-button>
                <button class="mini-stop" title="结束并记录这段专注" @click="stop">结束</button>
              </div>
              <template v-if="entity.lifecycleStatus !== 'COMPLETED' && entity.lifecycleStatus !== 'CANCELED'">
                <button class="early-end-action" @click="earlyEnd">提前结束</button>
                <button class="complete-action" @click="complete"><span>✓</span>完成这一小步</button>
              </template>
              <span v-else-if="entity.lifecycleStatus === 'COMPLETED'" class="complete-state">✓ 已完成</span>
              <span v-else class="ended-state">已提前结束</span>
            </div>
          </div>

          <section v-if="!session && entity.lifecycleStatus !== 'CANCELED'" class="focus-console">
            <div class="focus-dial">
              <div><span>{{ formatTime }}</span><small>准备好再开始</small></div>
            </div>
            <div class="focus-controls">
              <el-button type="primary" size="large" @click="start">开始专注</el-button>
            </div>
            <div class="focus-meta"><span>预计 {{ entity.estimatedMinutes }} 分钟</span><i /><span>{{ entity.priority || 'MEDIUM' }} PRIORITY</span></div>
          </section>

          <section class="task-chat">
            <div class="chat-head">
              <div><span class="eyebrow">DISCUSS WITH AGENT</span><h3>和 Agent 讨论「{{ entity.title }}」</h3></div>
              <small>优先引用你的知识库，不足时联网检索并附链接</small>
            </div>
            <div ref="chatScroll" class="chat-messages">
              <div v-if="!chatMessages.length" class="chat-empty">对这段任务有疑问？可以问我概念、做法或相关资料。</div>
              <div v-for="(item, index) in chatMessages" :key="index" class="chat-msg" :class="item.role === 'USER' ? 'user' : 'assistant'">
                <div class="chat-bubble">{{ item.content }}</div>
                <div v-if="item.citations?.length" class="chat-cites">
                  <span v-if="item.mode === 'KNOWLEDGE'" class="cite-source">来自你的知识库</span>
                  <span v-else class="cite-source web">来自联网检索</span>
                  <a v-for="cite in item.citations" :key="cite.citationId" :href="cite.url || undefined" :target="cite.url ? '_blank' : undefined" :rel="cite.url ? 'noopener' : undefined" :class="{ web: cite.sourceType === 'WEB' }" :title="cite.quotePreview">
                    [{{ cite.citationId }}] {{ cite.sourceType === 'WEB' ? cite.title || cite.url : cite.fileName || '知识库资料' }}
                  </a>
                </div>
              </div>
              <div v-if="chatLoading" class="chat-msg assistant"><div class="chat-bubble typing">正在思考…</div></div>
            </div>
            <div class="chat-input">
              <el-input v-model="chatDraft" type="textarea" :rows="3" resize="none" placeholder="围绕当前任务提问，Enter 发送，Shift+Enter 换行" @keydown.enter.exact.prevent="sendChat" />
              <el-button type="primary" :loading="chatLoading" :disabled="!chatDraft.trim()" @click="sendChat">发送</el-button>
            </div>
          </section>

          <el-collapse v-model="openPanels" class="workspace-notebook">
            <el-collapse-item name="note">
              <template #title><span class="panel-title">学习笔记</span><span class="panel-hint">点击展开记录</span></template>
              <div class="note-editor">
                <el-input v-model="note.title" placeholder="给这段笔记一个标题" />
                <el-input v-model="note.markdown" type="textarea" :rows="5" placeholder="写下关键想法、疑问或下一步。支持 Markdown，保存时会生成可追溯版本。" />
                <div><span>VERSION {{ note.version || 'NEW' }}</span><el-button type="primary" @click="saveNote">保存这一版</el-button></div>
              </div>
            </el-collapse-item>
            <el-collapse-item name="clues">
              <template #title><span class="panel-title">任务线索</span></template>
              <div class="task-clues">
                <div><span>计划开始</span><b>{{ entity.scheduledStart || '待安排' }}</b></div>
                <div><span>截止时间</span><b>{{ entity.dueAt || '未设置' }}</b></div>
                <div><span>预计时长</span><b>{{ entity.estimatedMinutes }} 分钟</b></div>
                <div><span>任务来源</span><b>{{ entity.source || '个人计划' }}</b></div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.today-page { display: grid; gap: 22px; }
.day-ribbon { position: relative; display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 40px; overflow: hidden; padding: 35px 40px; border-radius: 30px; color: #edf4ee; background: radial-gradient(circle at 76% 0%, rgba(114, 178, 145, .3), transparent 29%), linear-gradient(135deg, #102f25, #1b513e); box-shadow: 0 24px 70px rgba(18, 55, 42, .2); }
.day-ribbon::after { position: absolute; right: -80px; bottom: -180px; width: 360px; height: 360px; border: 1px solid rgba(255, 255, 255, .07); border-radius: 50%; box-shadow: 0 0 0 50px rgba(255, 255, 255, .02); content: ""; }
.day-intro { position: relative; z-index: 1; }
.day-intro h1 { margin: 8px 0 5px; font: 500 clamp(30px, 4vw, 46px) var(--display); letter-spacing: -.025em; }
.day-intro p { margin: 0; color: #acc3b8; font-size: 10px; }
.day-signals { position: relative; z-index: 1; display: flex; align-items: center; gap: 18px; }
.day-signals > div { text-align: center; }
.day-signals strong, .day-signals small { display: block; }
.day-signals strong { font: 500 28px var(--display); }
.day-signals small { margin-top: 3px; color: #9eb7aa; font-size: 8px; }
.day-signals > i { width: 1px; height: 34px; background: rgba(255, 255, 255, .11); }
.date-control { position: relative; z-index: 1; width: 150px; }
.date-control > span { display: block; margin-bottom: 6px; color: #91aa9e; font-size: 8px; font-weight: 700; letter-spacing: .1em; }
.date-control :deep(.el-input__wrapper) { background: rgba(255, 255, 255, .09); box-shadow: 0 0 0 1px rgba(255, 255, 255, .13) inset !important; }
.date-control :deep(.el-date-editor.el-input) { width: 100%; }
.date-control :deep(.el-input__inner), .date-control :deep(.el-input__prefix) { color: #edf4ee; }

.day-layout { display: grid; grid-template-columns: 350px minmax(0, 1fr); gap: 22px; transition: grid-template-columns .25s ease; }
.day-layout.queue-is-collapsed { grid-template-columns: 54px minmax(0, 1fr); }
.day-queue { align-self: start; padding: 29px 25px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: rgba(252, 253, 249, .68); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.day-queue.collapsed { display: grid; justify-items: center; padding: 14px 8px; }
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
.queue-timeline button:hover { background: rgba(230, 238, 230, .58); }
.queue-timeline button.active { background: #e6efe8; box-shadow: inset 3px 0 0 var(--green); }
.queue-time { color: #7c8881; font: 500 9px ui-monospace, monospace; }
.queue-timeline button > i { display: grid; place-items: center; width: 25px; height: 25px; border: 4px solid #f7f9f4; border-radius: 50%; color: var(--green); background: #dcebe2; font-size: 8px; font-style: normal; font-weight: 700; box-shadow: 0 0 0 1px rgba(31, 88, 64, .1); }
.queue-timeline button.done > i { color: #fff; background: var(--green); }
.queue-timeline b, .queue-timeline small { display: block; }
.queue-timeline b { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.queue-timeline small { margin-top: 4px; color: #8b958f; font-size: 8px; }
.queue-empty { padding: 30px 7px 22px; text-align: center; }
.queue-empty > span { display: grid; place-items: center; width: 44px; height: 44px; margin: 0 auto 12px; border-radius: 50%; color: #7e988a; background: #e9f0e9; }
.queue-empty b { display: block; font: 500 17px var(--display); }
.queue-empty p { margin: 7px 0 0; color: var(--muted); font-size: 9px; line-height: 1.7; }
.upcoming-block { margin-top: 19px; padding-top: 18px; border-top: 1px solid var(--line); }
.upcoming-block > button { display: grid; grid-template-columns: 36px 1fr auto; align-items: center; gap: 9px; width: 100%; padding: 12px 6px; border: 0; color: var(--ink); background: transparent; text-align: left; }
.upcoming-block > button > span { color: var(--gold); font: 600 11px var(--display); }
.upcoming-block b, .upcoming-block small { display: block; }
.upcoming-block b { overflow: hidden; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.upcoming-block small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.upcoming-block i { color: var(--green); font-style: normal; }

.focus-room { min-height: 720px; overflow: hidden; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: rgba(252, 253, 249, .7); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.focus-room.waiting { display: grid; place-items: center; color: #edf4ee; background: radial-gradient(circle at 70% 25%, rgba(116, 174, 144, .25), transparent 28%), linear-gradient(145deg, #173f32, #102d25); }
.focus-waiting { max-width: 520px; padding: 50px; text-align: center; }
.waiting-orbit { display: grid; place-items: center; width: 108px; height: 108px; margin: 0 auto 25px; border: 1px solid rgba(255, 255, 255, .1); border-radius: 50%; box-shadow: 0 0 0 20px rgba(255, 255, 255, .025), 0 0 0 40px rgba(255, 255, 255, .016); }
.waiting-orbit span { display: grid; place-items: center; width: 58px; height: 58px; border-radius: 20px 20px 20px 6px; color: #15372c; background: linear-gradient(145deg, #efd08a, #d0a050); font: 600 20px var(--display); }
.focus-waiting h2 { margin: 12px 0 7px; font: 500 31px var(--display); }
.focus-waiting p { margin: 0 0 25px; color: #a9c0b4; font-size: 11px; line-height: 1.7; }
.focus-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 30px 34px 22px; }
.focus-type { color: var(--green); font-size: 8px; font-weight: 800; letter-spacing: .14em; }
.focus-header h2 { margin: 8px 0 5px; font: 500 29px var(--display); }
.focus-header p { max-width: 680px; margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.complete-action { display: flex; align-items: center; gap: 8px; flex: none; padding: 7px 12px 7px 7px; border: 1px solid rgba(33, 102, 73, .13); border-radius: 99px; color: var(--green); background: #e6f0e9; font-size: 9px; font-weight: 700; }
.complete-action span { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; color: #fff; background: var(--green); }
.complete-state { padding: 8px 12px; border-radius: 99px; color: var(--green); background: var(--mint); font-size: 9px; font-weight: 700; }
.early-end-action { padding: 7px 12px; border: 1px solid rgba(140, 151, 144, .3); border-radius: 99px; color: var(--muted); background: transparent; font-size: 9px; font-weight: 700; transition: .2s; }
.early-end-action:hover { color: #8a6420; border-color: rgba(176, 137, 62, .4); background: rgba(243, 234, 214, .5); }
.ended-state { padding: 8px 12px; border-radius: 99px; color: #8a6420; background: rgba(243, 234, 214, .65); font-size: 9px; font-weight: 700; }
.queue-timeline button.canceled b { color: #9aa39e; text-decoration: line-through; }
.queue-timeline button.canceled > i { color: #9aa39e; background: #eceeeb; }
.focus-side { display: flex; align-items: center; gap: 10px; flex: none; }
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
.task-chat { display: grid; gap: 14px; margin: 0 30px; padding: 22px 24px 18px; border-radius: 24px; background: rgba(232, 239, 232, .38); }
.chat-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; }
.chat-head h3 { margin: 6px 0 0; font: 500 19px var(--display); }
.chat-head > small { flex: none; max-width: 220px; color: var(--muted); font-size: 8px; line-height: 1.6; text-align: right; }
.chat-messages { display: grid; gap: 10px; max-height: 480px; overflow-y: auto; padding-right: 4px; }
.chat-empty { padding: 18px 10px; color: var(--muted); font-size: 10px; text-align: center; }
.chat-msg { display: grid; gap: 6px; max-width: 82%; }
.chat-msg.user { justify-self: end; }
.chat-msg.assistant { justify-self: start; }
.chat-bubble { padding: 11px 15px; border-radius: 14px; font-size: 11px; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
.chat-msg.user .chat-bubble { color: #f2f7f3; border-bottom-right-radius: 4px; background: linear-gradient(145deg, #1d5c44, #143c2d); }
.chat-msg.assistant .chat-bubble { color: var(--ink); border: 1px solid rgba(31, 88, 64, .1); border-bottom-left-radius: 4px; background: rgba(252, 253, 249, .9); }
.chat-bubble.typing { color: var(--muted); }
.chat-cites { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.cite-source { flex-basis: 100%; color: #8c9790; font-size: 8px; font-weight: 700; letter-spacing: .08em; }
.chat-cites a { max-width: 260px; overflow: hidden; padding: 4px 9px; border: 1px solid rgba(23, 107, 80, .14); border-radius: 99px; color: var(--green); background: rgba(223, 238, 229, .6); font-size: 8px; text-decoration: none; text-overflow: ellipsis; white-space: nowrap; }
.chat-cites a.web { border-color: rgba(176, 137, 62, .22); color: #8a6420; background: rgba(243, 234, 214, .65); }
.chat-cites a[href]:hover { filter: brightness(.96); text-decoration: underline; }
.chat-input { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: 10px; }
.workspace-notebook { margin: 18px 30px 0; padding: 0 24px 24px; border-top: 0; }
.workspace-notebook :deep(.el-collapse-item__header) { font-size: 11px; }
.workspace-notebook .panel-title { font-weight: 700; }
.workspace-notebook .panel-hint { margin-left: 10px; color: var(--muted); font-size: 8px; font-weight: 400; }
.workspace-notebook :deep(.el-collapse-item__content) { padding-bottom: 18px; }
.note-editor { display: grid; gap: 11px; }
.note-editor > div { display: flex; align-items: center; justify-content: space-between; }
.note-editor > div > span { color: #9aa39e; font-size: 8px; font-weight: 700; letter-spacing: .1em; }
.task-clues { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.task-clues > div { padding: 16px; border-radius: 14px; background: rgba(232, 239, 232, .58); }
.task-clues span, .task-clues b { display: block; }
.task-clues span { color: #8c9790; font-size: 8px; }
.task-clues b { margin-top: 6px; font-size: 10px; font-weight: 600; }
@keyframes focus-pulse { 50% { box-shadow: 0 0 0 17px rgba(230, 194, 116, .045), 0 0 0 34px rgba(255, 255, 255, .012), inset 0 0 50px rgba(6, 23, 17, .18); } }

@media (max-width: 1000px) {
  .day-ribbon { grid-template-columns: 1fr auto; }
  .date-control { grid-column: 1 / -1; width: 100%; }
  .day-layout, .day-layout.queue-is-collapsed { grid-template-columns: 1fr; }
  .day-queue { position: static; }
  .queue-expand { display: flex; align-items: center; gap: 10px; }
  .queue-expand > b { letter-spacing: normal; writing-mode: horizontal-tb; }
  .queue-timeline { display: grid; grid-template-columns: repeat(2, 1fr); gap: 7px; }
  .queue-timeline::before { display: none; }
}
@media (max-width: 620px) {
  .day-ribbon { grid-template-columns: 1fr; gap: 25px; padding: 28px 23px; border-radius: 25px; }
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
  .task-chat { margin: 0 16px; padding: 18px 16px 14px; }
  .chat-head { align-items: flex-start; flex-direction: column; gap: 6px; }
  .chat-head > small { max-width: none; text-align: left; }
  .chat-msg { max-width: 94%; }
  .workspace-notebook { margin: 14px 16px 0; padding: 0 10px 18px; }
  .task-clues { grid-template-columns: 1fr; }
}
</style>
