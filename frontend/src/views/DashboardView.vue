<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { api } from '../api/http'

const overview = ref<any>({ metrics: {}, studyTime: [], mastery: [] })
const tasks = ref<any[]>([])
const goals = ref<any[]>([])
const loading = ref(true)
const start = dayjs().subtract(6, 'day').format('YYYY-MM-DD')
const end = dayjs().format('YYYY-MM-DD')

const metrics = computed(() => overview.value.metrics || {})
const completed = computed(() => Number(metrics.value.completedTasks?.value || 0))
const planned = computed(() => Number(metrics.value.plannedTasks?.value || 0))
const weeklyMinutes = computed(() => Math.round(Number(metrics.value.effectiveStudySeconds?.value || 0) / 60))
const completionRate = computed(() => Math.round(Number(metrics.value.taskCompletionRate?.value || 0)))
const activeDays = computed(() => (overview.value.studyTime || []).filter((item: any) => Number(item.totalSeconds || 0) > 0).length)
const todayTasks = computed(() => tasks.value
  .filter((item: any) => item.task?.scheduledStart && dayjs(item.task.scheduledStart).isSame(dayjs(), 'day'))
  .sort((a: any, b: any) => dayjs(a.task.scheduledStart).valueOf() - dayjs(b.task.scheduledStart).valueOf()))
const nextTask = computed(() => {
  const unfinishedToday = todayTasks.value.find((item: any) => !['COMPLETED', 'CANCELED'].includes(item.task.lifecycleStatus))
  if (unfinishedToday) return unfinishedToday
  return tasks.value
    .filter((item: any) => item.task?.scheduledStart && dayjs(item.task.scheduledStart).isAfter(dayjs()) && !['COMPLETED', 'CANCELED'].includes(item.task.lifecycleStatus))
    .sort((a: any, b: any) => dayjs(a.task.scheduledStart).valueOf() - dayjs(b.task.scheduledStart).valueOf())[0]
})
const mastery = computed(() => (overview.value.mastery || []).slice(0, 4))
const greeting = computed(() => {
  const hour = dayjs().hour()
  if (hour < 11) return '早上好，把清醒留给重要的事'
  if (hour < 18) return '下午好，沿着自己的节奏前进'
  return '晚上好，为今天温柔地收尾'
})

function masteryName(item: any) {
  return item.name || item.dimensionName || item.dimensionCode || item.knowledgePoint || '能力维度'
}
function masteryScore(item: any) {
  const value = Number(item.score ?? item.masteryScore ?? item.value ?? 0)
  return value <= 1 ? Math.round(value * 100) : Math.round(value)
}

onMounted(async () => {
  const results = await Promise.allSettled([
    api<any>({ url: '/analytics/overview', params: { start, end } }),
    api<any[]>({ url: '/tasks' }),
    api<any>({ url: '/goals', params: { status: 'ACTIVE', pageSize: 5 } }),
  ])
  if (results[0].status === 'fulfilled') overview.value = results[0].value
  if (results[1].status === 'fulfilled') tasks.value = results[1].value
  if (results[2].status === 'fulfilled') goals.value = results[2].value.items
  loading.value = false
})
</script>

<template>
  <div v-loading="loading" class="dashboard-canvas">
    <section class="focus-hero">
      <div class="hero-copy">
        <span class="hero-date">{{ dayjs().format('YYYY · MM · DD') }} / {{ dayjs().format('dddd') }}</span>
        <h1>{{ greeting }}</h1>
        <p>知序已经把目标、可用时间和最近状态整理成清晰的一天。你只需要从下一步开始。</p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="$router.push('/today')">进入今日节奏</el-button>
          <button class="text-action" @click="$router.push('/plans')">和 Agent 规划下一步 <span>↗</span></button>
        </div>
      </div>

      <div class="hero-rhythm">
        <div class="rhythm-orbit" :style="{ '--progress': `${completionRate}%` }">
          <div><strong>{{ completionRate }}</strong><small>%</small><em>本周完成率</em></div>
        </div>
        <div v-if="nextTask" class="next-signal">
          <span>NEXT FOCUS</span>
          <b>{{ nextTask.task.title }}</b>
          <small>{{ dayjs(nextTask.task.scheduledStart).format('M 月 D 日 HH:mm') }} · {{ nextTask.task.estimatedMinutes }} 分钟</small>
        </div>
        <div v-else class="next-signal quiet">
          <span>OPEN SPACE</span><b>此刻没有被安排占满</b><small>可以休息，也可以和 Agent 生成一段新计划</small>
        </div>
      </div>
    </section>

    <section class="signal-rail" aria-label="本周学习信号">
      <article><span>01</span><div><small>专注投入</small><strong>{{ weeklyMinutes }}<em> min</em></strong></div><p>近 7 天有效学习</p></article>
      <article><span>02</span><div><small>行动兑现</small><strong>{{ completed }}<em> / {{ planned || 0 }}</em></strong></div><p>完成与计划任务</p></article>
      <article><span>03</span><div><small>学习节律</small><strong>{{ activeDays }}<em> days</em></strong></div><p>近 7 天留下记录</p></article>
      <article><span>04</span><div><small>能力证据</small><strong>{{ overview.mastery.length }}<em> signals</em></strong></div><p>被持续追踪的维度</p></article>
    </section>

    <div class="dashboard-bento">
      <section class="itinerary-studio">
        <div class="section-heading">
          <div><span class="eyebrow">TODAY'S PATH</span><h2>今天的学习路径</h2></div>
          <button @click="$router.push('/today')">打开专注空间 →</button>
        </div>
        <div v-if="!todayTasks.length" class="editorial-empty">
          <span>○</span><div><b>今天暂时留白</b><p>发布的任务会按时间在这里形成一条清晰路径。</p></div>
        </div>
        <div v-else class="path-list">
          <button v-for="(row, index) in todayTasks.slice(0, 5)" :key="row.task.publicId" @click="$router.push('/today')">
            <span class="path-time">{{ dayjs(row.task.scheduledStart).format('HH:mm') }}</span>
            <i :class="{ done: row.task.lifecycleStatus === 'COMPLETED' }">{{ row.task.lifecycleStatus === 'COMPLETED' ? '✓' : index + 1 }}</i>
            <div><b>{{ row.task.title }}</b><small>{{ row.task.estimatedMinutes }} 分钟 · {{ row.task.taskType || '学习任务' }}</small></div>
            <em>{{ row.task.lifecycleStatus === 'COMPLETED' ? '已完成' : '待进入' }}</em>
          </button>
        </div>
      </section>

      <div class="growth-column">
        <section class="goal-cards">
          <div class="section-heading compact">
            <div><span class="eyebrow">IN MOTION</span><h2>正在推进</h2></div>
            <button @click="$router.push('/goals')">全部目标</button>
          </div>
          <div v-if="!goals.length" class="mini-empty">还没有激活的目标</div>
          <button v-for="(goal, index) in goals.slice(0, 3)" :key="goal.publicId" class="goal-note" @click="$router.push('/plans')">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <div><b>{{ goal.name }}</b><small>{{ goal.startDate }} — {{ goal.dueDate }}</small></div>
            <i>↗</i>
          </button>
        </section>

        <section class="spectrum-card">
          <div class="section-heading compact">
            <div><span class="eyebrow light">ABILITY SPECTRUM</span><h2>能力光谱</h2></div>
            <button @click="$router.push('/onboarding')">查看画像</button>
          </div>
          <div v-if="!mastery.length" class="mini-empty dark-empty">完成学习与评估后，能力光谱会在这里生长。</div>
          <div v-for="item in mastery" :key="masteryName(item)" class="spectrum-row">
            <span>{{ masteryName(item) }}</span><div><i :style="{ width: `${Math.min(100, masteryScore(item))}%` }" /></div><b>{{ masteryScore(item) }}</b>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard-canvas { display: grid; gap: 22px; }
.focus-hero { position: relative; display: grid; grid-template-columns: 1.25fr .75fr; min-height: 380px; overflow: hidden; padding: clamp(34px, 5vw, 68px); border-radius: 34px; color: #f3f5ed; background: radial-gradient(circle at 82% 20%, rgba(115, 174, 142, .35), transparent 26%), linear-gradient(135deg, #0e2e24 0%, #174737 58%, #245c47 100%); box-shadow: 0 30px 90px rgba(20, 59, 45, .23); }
.focus-hero::after { position: absolute; right: -80px; bottom: -190px; width: 440px; height: 440px; border: 1px solid rgba(255, 255, 255, .09); border-radius: 50%; box-shadow: 0 0 0 58px rgba(255, 255, 255, .025), 0 0 0 116px rgba(255, 255, 255, .018); content: ""; }
.hero-copy { position: relative; z-index: 2; align-self: center; max-width: 720px; }
.hero-date { color: #afc8bc; font-size: 9px; font-weight: 700; letter-spacing: .2em; }
.hero-copy h1 { max-width: 680px; margin: 18px 0 14px; font: 500 clamp(37px, 4.5vw, 62px)/1.16 var(--display); letter-spacing: -.035em; }
.hero-copy p { max-width: 580px; margin: 0; color: #bfd0c8; font-size: 13px; line-height: 1.9; }
.hero-actions { display: flex; align-items: center; gap: 22px; margin-top: 34px; }
.focus-hero :deep(.el-button--primary) { border-color: #e1b96f; color: #18362b; background: linear-gradient(145deg, #efd290, #d4a65c); box-shadow: 0 12px 28px rgba(4, 22, 16, .24); }
.text-action { border: 0; color: #dce8e1; background: transparent; font-size: 12px; }
.text-action span { display: inline-block; margin-left: 5px; transition: .2s; }
.text-action:hover span { transform: translate(3px, -3px); }
.hero-rhythm { position: relative; z-index: 2; display: grid; align-content: center; justify-items: center; }
.rhythm-orbit { display: grid; place-items: center; width: 176px; height: 176px; border-radius: 50%; background: conic-gradient(#e8c47e var(--progress), rgba(255, 255, 255, .12) 0); box-shadow: 0 18px 50px rgba(3, 20, 14, .2); }
.rhythm-orbit::before { position: absolute; width: 154px; height: 154px; border-radius: 50%; background: #174333; content: ""; }
.rhythm-orbit > div { position: relative; z-index: 1; text-align: center; }
.rhythm-orbit strong { font: 500 48px var(--display); }
.rhythm-orbit small { color: #e9c77e; font-size: 13px; }
.rhythm-orbit em { display: block; margin-top: 2px; color: #9eb9ac; font-size: 9px; font-style: normal; letter-spacing: .1em; }
.next-signal { width: min(290px, 100%); margin-top: 24px; padding: 16px 18px; border: 1px solid rgba(255, 255, 255, .1); border-radius: 17px; background: rgba(255, 255, 255, .07); backdrop-filter: blur(12px); }
.next-signal span, .next-signal b, .next-signal small { display: block; }
.next-signal span { color: #e4bd72; font-size: 8px; font-weight: 800; letter-spacing: .16em; }
.next-signal b { margin: 7px 0 5px; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.next-signal small { color: #aac1b6; font-size: 9px; }
.next-signal.quiet b { color: #dce9e2; }

.signal-rail { display: grid; grid-template-columns: repeat(4, 1fr); padding: 6px 2px 12px; border-bottom: 1px solid var(--line); }
.signal-rail article { position: relative; display: grid; grid-template-columns: 30px 1fr; gap: 8px; padding: 16px 24px; }
.signal-rail article + article { border-left: 1px solid var(--line); }
.signal-rail article > span { color: #b1bab4; font: italic 11px var(--display); }
.signal-rail small, .signal-rail strong { display: block; }
.signal-rail small { color: var(--muted); font-size: 9px; font-weight: 700; letter-spacing: .08em; }
.signal-rail strong { margin-top: 6px; font: 500 29px var(--display); }
.signal-rail strong em { color: #88948d; font: 500 10px Inter, sans-serif; }
.signal-rail p { grid-column: 2; margin: 1px 0 0; color: #98a19b; font-size: 9px; }

.dashboard-bento { display: grid; grid-template-columns: 1.25fr .75fr; gap: 20px; }
.itinerary-studio, .goal-cards { padding: 30px; border: 1px solid rgba(255, 255, 255, .72); border-radius: 28px; background: rgba(252, 253, 249, .68); box-shadow: var(--soft-shadow), inset 0 0 0 1px rgba(38, 68, 55, .045); backdrop-filter: blur(14px); }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 15px; margin-bottom: 24px; }
.section-heading h2 { margin: 7px 0 0; font: 500 27px var(--display); }
.section-heading button { padding: 7px 0; border: 0; color: var(--green); background: transparent; font-size: 10px; font-weight: 700; }
.path-list { position: relative; }
.path-list::before { position: absolute; top: 28px; bottom: 28px; left: 87px; width: 1px; background: rgba(31, 88, 64, .13); content: ""; }
.path-list > button { position: relative; z-index: 1; display: grid; grid-template-columns: 58px 38px 1fr auto; align-items: center; gap: 11px; width: 100%; padding: 13px 0; border: 0; color: var(--ink); background: transparent; text-align: left; }
.path-list > button:hover div b { color: var(--green); }
.path-time { color: #66736c; font: 500 11px ui-monospace, monospace; }
.path-list i { display: grid; place-items: center; width: 30px; height: 30px; border: 5px solid #f5f7f2; border-radius: 50%; color: var(--green); background: #dcebe2; font-size: 9px; font-style: normal; font-weight: 700; box-shadow: 0 0 0 1px rgba(31, 88, 64, .12); }
.path-list i.done { color: #fff; background: var(--green); }
.path-list b, .path-list small { display: block; }
.path-list b { transition: .2s; font-size: 12px; }
.path-list small { margin-top: 5px; color: #8b958f; font-size: 9px; }
.path-list em { padding: 5px 9px; border-radius: 99px; color: #7c8981; background: #eef1ec; font-size: 8px; font-style: normal; }
.editorial-empty { display: flex; align-items: center; gap: 14px; padding: 35px 0; }
.editorial-empty > span { display: grid; place-items: center; width: 46px; height: 46px; border-radius: 50%; color: #87a092; background: #edf3ed; }
.editorial-empty b { font: 500 17px var(--display); }
.editorial-empty p { margin: 5px 0 0; color: var(--muted); font-size: 10px; }
.growth-column { display: grid; gap: 20px; }
.section-heading.compact { align-items: center; margin-bottom: 17px; }
.section-heading.compact h2 { font-size: 22px; }
.goal-note { display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 11px; width: 100%; padding: 14px 0; border: 0; border-top: 1px solid var(--line); color: var(--ink); background: transparent; text-align: left; }
.goal-note > span { color: #9aa59e; font: italic 11px var(--display); }
.goal-note b, .goal-note small { display: block; }
.goal-note b { font-size: 11px; }
.goal-note small { margin-top: 5px; color: #919b95; font-size: 8px; }
.goal-note i { color: var(--green); font-style: normal; transition: .2s; }
.goal-note:hover i { transform: translate(3px, -3px); }
.spectrum-card { padding: 30px; border-radius: 28px; color: #f2f5ef; background: linear-gradient(145deg, #1b4436, #102e25); box-shadow: 0 20px 50px rgba(20, 54, 42, .19); }
.spectrum-card .section-heading button { color: #d7ba7d; }
.spectrum-card .section-heading h2 { color: #fff; }
.spectrum-row { display: grid; grid-template-columns: 90px 1fr 28px; align-items: center; gap: 10px; margin-top: 15px; }
.spectrum-row > span { overflow: hidden; color: #bfd1c8; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.spectrum-row > div { height: 4px; overflow: hidden; border-radius: 99px; background: rgba(255, 255, 255, .1); }
.spectrum-row > div i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #7bb697, #e2bd74); }
.spectrum-row > b { color: #e4c37d; font: 500 12px var(--display); text-align: right; }
.mini-empty { padding: 26px 0; color: var(--muted); font-size: 10px; text-align: center; }
.dark-empty { color: #9cb4a8; }

@media (max-width: 1000px) {
  .focus-hero, .dashboard-bento { grid-template-columns: 1fr; }
  .hero-rhythm { grid-template-columns: auto 1fr; justify-items: start; gap: 22px; margin-top: 36px; }
  .next-signal { margin-top: 0; }
  .signal-rail { grid-template-columns: repeat(2, 1fr); }
  .signal-rail article:nth-child(3) { border-left: 0; border-top: 1px solid var(--line); }
  .signal-rail article:nth-child(4) { border-top: 1px solid var(--line); }
}
@media (max-width: 620px) {
  .focus-hero { min-height: auto; padding: 30px 24px; border-radius: 26px; }
  .hero-copy h1 { font-size: 38px; }
  .hero-actions { align-items: flex-start; flex-direction: column; gap: 12px; margin-top: 25px; }
  .hero-rhythm { display: block; }
  .rhythm-orbit { width: 142px; height: 142px; margin: 0 auto; }
  .rhythm-orbit::before { width: 122px; height: 122px; }
  .rhythm-orbit strong { font-size: 40px; }
  .next-signal { margin: 19px auto 0; }
  .signal-rail article { padding: 14px 9px; }
  .signal-rail strong { font-size: 23px; }
  .itinerary-studio, .goal-cards, .spectrum-card { padding: 21px; border-radius: 22px; }
  .path-list::before { left: 37px; }
  .path-list > button { grid-template-columns: 30px 34px 1fr; }
  .path-time { font-size: 9px; }
  .path-list > button > em { display: none; }
}
</style>
