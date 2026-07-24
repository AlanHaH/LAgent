<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

type DemoState = 'idle' | 'thinking' | 'ready'
type FeatureKey = 'profile' | 'plan' | 'today' | 'knowledge' | 'insight'

const router = useRouter()
const auth = useAuthStore()
const page = ref<HTMLElement | null>(null)
const direction = ref('Java 后端')
const weeklyHours = ref(8)
const guidance = ref('启发式')
const demoState = ref<DemoState>('idle')
const demoStep = ref(0)
const activeFeature = ref<FeatureKey>('profile')
const timers: number[] = []
let observer: IntersectionObserver | undefined

const directions = ['Java 后端', '生成式 AI', '数据分析']
const guidanceModes = ['启发式', '直接指导']

const features: Array<{
  key: FeatureKey
  index: string
  name: string
  eyebrow: string
  title: string
  copy: string
}> = [
  { key: 'profile', index: '01', name: '画像', eyebrow: 'LEARNER MODEL', title: '对话不是问卷，画像会持续生长', copy: 'Agent 在自然对话中识别目标、基础、偏好与时间约束。每一项推断都能查看、修正，正式写入前由你确认。' },
  { key: 'plan', index: '02', name: '规划', eyebrow: 'ADAPTIVE PLANNING', title: '目标被拆成真正能执行的路径', copy: '从里程碑到每天的学习块，自动考虑依赖关系、难度与可用时间；临时变化发生时，只重排受影响的部分。' },
  { key: 'today', index: '03', name: '执行', eyebrow: 'DAILY FOCUS', title: '每天只回答一个问题：下一步是什么', copy: '今日工作台把任务、资料、专注记录与完成反馈放在同一处，减少在工具之间切换造成的注意力损耗。' },
  { key: 'knowledge', index: '04', name: '知识', eyebrow: 'GROUNDED KNOWLEDGE', title: '你的资料，变成可追溯的第二大脑', copy: '上传笔记和文档后，可以基于个人知识库提问。答案附带来源片段，让理解和复习始终有据可查。' },
  { key: 'insight', index: '05', name: '复盘', eyebrow: 'FEEDBACK LOOP', title: '系统观察变化，而不是制造焦虑', copy: '趋势、掌握度、错题与计划偏差被组合成可解释反馈，帮助你判断应该坚持、降速，还是改变学习策略。' },
]

const activeFeatureData = computed(() => features.find((item) => item.key === activeFeature.value) || features[0])
const planTitle = computed(() => `${direction.value} · ${weeklyHours.value} 小时适应性路径`)
const dailyMinutes = computed(() => Math.max(35, Math.round(weeklyHours.value * 60 / 6 / 5) * 5))
const planItems = computed(() => {
  const maps: Record<string, Array<[string, string]>> = {
    'Java 后端': [['基础校准', '集合与并发诊断'], ['核心构建', 'Spring Boot API'], ['实战迁移', '学习系统服务化']],
    '生成式 AI': [['认知地图', '模型与提示基础'], ['能力构建', 'RAG 与 Agent 工作流'], ['项目验证', '可评估的 AI 应用']],
    '数据分析': [['基础校准', 'SQL 与统计诊断'], ['核心构建', 'Python 分析管线'], ['洞察表达', '业务看板与结论']],
  }
  return maps[direction.value]
})

function clearTimers() {
  while (timers.length) window.clearTimeout(timers.pop())
}

function runDemo() {
  clearTimers()
  demoState.value = 'thinking'
  demoStep.value = 0
  timers.push(window.setTimeout(() => { demoStep.value = 1 }, 480))
  timers.push(window.setTimeout(() => { demoStep.value = 2 }, 980))
  timers.push(window.setTimeout(() => {
    demoStep.value = 3
    demoState.value = 'ready'
  }, 1500))
}

function enterProduct(register = false) {
  if (auth.authenticated) {
    router.push('/dashboard')
    return
  }
  router.push(register ? { path: '/login', query: { mode: 'register' } } : '/login')
}

function scrollTo(id: string) {
  document.querySelector(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function updatePointer(event: PointerEvent) {
  if (!page.value) return
  page.value.style.setProperty('--pointer-x', `${event.clientX}px`)
  page.value.style.setProperty('--pointer-y', `${event.clientY}px`)
}

onMounted(() => {
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) entry.target.classList.add('is-visible')
    }
  }, { threshold: 0.14 })
  document.querySelectorAll('.landing-page .reveal').forEach((element) => observer?.observe(element))
})

onBeforeUnmount(() => {
  clearTimers()
  observer?.disconnect()
})
</script>

<template>
  <main ref="page" class="landing-page" @pointermove="updatePointer">
    <div class="pointer-glow" aria-hidden="true"></div>

    <header class="landing-nav">
      <button class="landing-brand" aria-label="返回知序首页" @click="scrollTo('#top')">
        <span class="landing-brand-mark">序</span>
        <span>知序</span>
      </button>
      <nav class="nav-links" aria-label="首页导航">
        <button @click="scrollTo('#capabilities')">产品能力</button>
        <button @click="scrollTo('#workflow')">学习闭环</button>
        <button @click="scrollTo('#experience')">现场体验</button>
      </nav>
      <div class="nav-actions">
        <button v-if="!auth.authenticated" class="text-button" @click="enterProduct(false)">登录</button>
        <button class="nav-primary" @click="enterProduct(true)">
          {{ auth.authenticated ? '进入工作台' : '开始构建路径' }}
          <span aria-hidden="true">↗</span>
        </button>
      </div>
    </header>

    <section id="top" class="hero-section">
      <div class="hero-grid" aria-hidden="true"></div>
      <div class="hero-orbit orbit-one" aria-hidden="true"></div>
      <div class="hero-orbit orbit-two" aria-hidden="true"></div>

      <div class="hero-copy reveal is-visible">
        <div class="hero-kicker"><span></span> AI AGENT · ADAPTIVE LEARNING</div>
        <h1>把学习，<br />变成一个会<span>自我进化</span>的系统。</h1>
        <p class="hero-intro">知序理解你真正想抵达的地方，把模糊目标变成每天可执行的行动，并在每一次反馈后重新规划下一步。</p>
        <div class="hero-actions">
          <button class="primary-cta" @click="enterProduct(true)">
            <span>{{ auth.authenticated ? '继续今天的学习' : '免费创建学习画像' }}</span>
            <i aria-hidden="true">→</i>
          </button>
          <button class="watch-cta" @click="scrollTo('#experience')">
            <span class="play-mark" aria-hidden="true">▶</span> 先体验一次规划
          </button>
        </div>
        <div class="hero-principles" aria-label="产品原则">
          <span><i>01</i> 对话生成画像</span>
          <span><i>02</i> 计划由你确认</span>
          <span><i>03</i> 每次反馈都有效</span>
        </div>
      </div>

      <div class="hero-console reveal is-visible">
        <div class="console-topline">
          <span class="console-status"><i></i> AGENT ONLINE</span>
          <span>SESSION / 001</span>
        </div>
        <div class="agent-dialogue">
          <span class="agent-avatar">序</span>
          <div>
            <small>LEARNING AGENT</small>
            <p>我会先理解你的目标和现实约束，再给出可以调整的方案。</p>
          </div>
        </div>
        <div class="console-question">
          <label>你现在最想建立哪项能力？</label>
          <div class="choice-row">
            <button v-for="item in directions" :key="item" :class="{ active: direction === item }" @click="direction = item">{{ item }}</button>
          </div>
        </div>
        <div class="console-metric">
          <div><label for="hero-hours">每周可投入时间</label><b>{{ weeklyHours }} h</b></div>
          <input id="hero-hours" v-model.number="weeklyHours" type="range" min="4" max="16" step="2" />
          <div class="range-labels"><span>轻量</span><span>专注</span><span>进阶</span></div>
        </div>
        <div class="console-foot">
          <span><i></i> 约束已进入规划上下文</span>
          <button @click="scrollTo('#experience')">继续体验 <b>↓</b></button>
        </div>
      </div>

      <div class="hero-index" aria-hidden="true"><span>INTELLIGENCE</span><b>01</b></div>
    </section>

    <section class="principle-band" aria-label="知序的工作方式">
      <p>不再堆积更多课程</p><i></i><p>从理解你开始</p><i></i><p>让计划随反馈进化</p>
    </section>

    <section id="capabilities" class="capabilities-section section-shell">
      <div class="section-heading reveal">
        <span class="section-no">01 / CAPABILITIES</span>
        <div><p class="eyebrow">一个完整的学习操作系统</p><h2>从“我想学”，到<span>我真正掌握。</span></h2></div>
        <p>不是五个彼此割裂的工具，而是一条由 Agent 串联、可持续自我修正的学习链路。</p>
      </div>

      <div class="feature-stage reveal">
        <div class="feature-tabs" role="tablist" aria-label="产品能力">
          <button
            v-for="feature in features"
            :key="feature.key"
            role="tab"
            :aria-selected="activeFeature === feature.key"
            :class="{ active: activeFeature === feature.key }"
            @click="activeFeature = feature.key"
          >
            <span>{{ feature.index }}</span><b>{{ feature.name }}</b><i aria-hidden="true">→</i>
          </button>
        </div>

        <div class="feature-copy" aria-live="polite">
          <span class="eyebrow">{{ activeFeatureData.eyebrow }}</span>
          <h3>{{ activeFeatureData.title }}</h3>
          <p>{{ activeFeatureData.copy }}</p>
          <button @click="enterProduct(true)">体验完整能力 <span>↗</span></button>
        </div>

        <div class="product-window" :data-feature="activeFeature">
          <div class="window-bar"><div><i></i><i></i><i></i></div><span>知序 · LEARNING OS</span><b>···</b></div>

          <div v-if="activeFeature === 'profile'" class="mock-profile mock-screen">
            <div class="profile-radar"><span>目标清晰度</span><b>82</b><i></i><i></i><i></i></div>
            <div class="profile-facts">
              <small>持续更新的学习者模型</small>
              <h4>你的优势是结构化理解，<br />当前瓶颈在实践迁移。</h4>
              <div><span>知识基础</span><b style="--value: 68%"></b><em>68%</em></div>
              <div><span>执行稳定</span><b style="--value: 76%"></b><em>76%</em></div>
              <div><span>实践迁移</span><b style="--value: 41%"></b><em>41%</em></div>
              <p><i></i> 新推断待你确认：偏好“先案例、后原理”</p>
            </div>
          </div>

          <div v-else-if="activeFeature === 'plan'" class="mock-plan mock-screen">
            <div class="plan-head"><div><small>SPRINT 02</small><h4>构建第一个可测试 API</h4></div><span>本周 · 8h</span></div>
            <div class="plan-line">
              <article class="done"><i>✓</i><small>MON</small><b>理解 REST 边界</b><span>45 min</span></article>
              <article class="active"><i>02</i><small>TODAY</small><b>实现用户模块</b><span>90 min</span></article>
              <article><i>03</i><small>FRI</small><b>补充集成测试</b><span>60 min</span></article>
            </div>
            <p class="plan-note"><span>AGENT</span> 根据昨天的完成反馈，已把测试任务拆成两个更小的学习块。</p>
          </div>

          <div v-else-if="activeFeature === 'today'" class="mock-today mock-screen">
            <div class="focus-ring"><div><b>38</b><span>MIN</span></div></div>
            <div class="focus-copy"><small>NEXT BEST ACTION</small><h4>完成用户登录接口的<br />异常处理</h4><p>完成条件：覆盖凭据错误、账户禁用与参数校验。</p><button>开始专注 <span>→</span></button></div>
            <div class="focus-side"><span>上下文已准备</span><b>3</b><small>相关笔记</small><b>2</b><small>验收标准</small></div>
          </div>

          <div v-else-if="activeFeature === 'knowledge'" class="mock-knowledge mock-screen">
            <aside><small>MY KNOWLEDGE</small><b class="selected">Spring Security 笔记</b><b>项目需求文档</b><b>错题与复盘</b></aside>
            <div class="knowledge-answer"><small>基于你的 3 份资料</small><h4>为什么这里要使用<br />无状态认证？</h4><p>因为当前服务采用前后端分离架构，令牌包含必要身份信息，服务端无需维护会话状态……</p><div><span>01</span><b>Spring Security 笔记</b><em>第 4 节</em></div><div><span>02</span><b>项目需求文档</b><em>3.2.1</em></div></div>
          </div>

          <div v-else class="mock-insight mock-screen">
            <div class="insight-chart"><small>MASTERY TREND</small><h4>掌握度正在稳定增长</h4><div class="chart-bars"><i v-for="height in [28, 38, 34, 51, 58, 69, 78]" :key="height" :style="{ height: `${height}%` }"></i></div><div class="chart-axis"><span>W1</span><span>W4</span><span>W7</span></div></div>
            <div class="insight-note"><span>本周洞察</span><b>+ 14%</b><p>你在“先做后学”的任务中保持率更高。下周计划已增加一个实践块。</p><small><i></i> 依据 12 次任务反馈</small></div>
          </div>
        </div>
      </div>
    </section>

    <section id="workflow" class="workflow-section">
      <div class="section-shell">
        <div class="workflow-heading reveal">
          <span class="section-no light">02 / THE LOOP</span>
          <h2>一个不断闭合的<br /><i>学习回路。</i></h2>
          <p>每一步都为下一步提供更好的上下文。系统不是替你决定，而是让你的决定越来越清晰。</p>
        </div>
        <div class="loop-grid reveal">
          <article><span>01</span><div class="loop-icon">⌁</div><h3>理解</h3><p>通过对话形成可修正的学习画像</p><i>→</i></article>
          <article><span>02</span><div class="loop-icon">⌗</div><h3>规划</h3><p>把目标拆解成有依赖关系的路径</p><i>→</i></article>
          <article><span>03</span><div class="loop-icon">◉</div><h3>行动</h3><p>把注意力留给今天最重要的一步</p><i>→</i></article>
          <article><span>04</span><div class="loop-icon">↗</div><h3>反馈</h3><p>用完成、掌握和感受校准策略</p><i>↺</i></article>
        </div>
      </div>
    </section>

    <section id="experience" class="experience-section section-shell">
      <div class="section-heading reveal">
        <span class="section-no">03 / LIVE EXPERIENCE</span>
        <div><p class="eyebrow">不是演示视频，亲手试一次</p><h2>给 Agent 三个约束，<br /><span>看路径如何出现。</span></h2></div>
        <p>这是前端交互预览，不会保存数据。登录后，真实 Agent 会通过多轮对话理解更完整的你。</p>
      </div>

      <div class="planner-lab reveal">
        <div class="lab-controls">
          <div class="lab-title"><span>YOUR INPUT</span><b>01—03</b></div>
          <fieldset>
            <legend><span>01</span> 目标方向</legend>
            <div class="lab-options"><button v-for="item in directions" :key="item" :class="{ active: direction === item }" @click="direction = item">{{ item }}<i>✓</i></button></div>
          </fieldset>
          <fieldset>
            <legend><span>02</span> 每周投入</legend>
            <div class="hours-display"><b>{{ weeklyHours }}</b><span>HOURS<br />/ WEEK</span></div>
            <input v-model.number="weeklyHours" type="range" min="4" max="16" step="2" aria-label="每周学习小时数" />
            <div class="range-labels"><span>4h</span><span>10h</span><span>16h</span></div>
          </fieldset>
          <fieldset>
            <legend><span>03</span> 指导方式</legend>
            <div class="guidance-switch"><button v-for="item in guidanceModes" :key="item" :class="{ active: guidance === item }" @click="guidance = item">{{ item }}</button></div>
          </fieldset>
          <button class="generate-button" :disabled="demoState === 'thinking'" @click="runDemo">
            <span>{{ demoState === 'thinking' ? 'Agent 正在构建路径' : demoState === 'ready' ? '根据新约束重新生成' : '生成我的路径预览' }}</span><i>{{ demoState === 'thinking' ? '···' : '→' }}</i>
          </button>
        </div>

        <div class="lab-output" aria-live="polite">
          <div class="output-head"><div><i></i><span>AGENT PATH BUILDER</span></div><b>{{ demoState === 'ready' ? 'READY' : demoState === 'thinking' ? 'PROCESSING' : 'WAITING' }}</b></div>

          <div v-if="demoState === 'idle'" class="output-idle">
            <div class="idle-orbit"><span>AI</span><i></i><i></i></div>
            <small>等待你的学习约束</small>
            <h3>路径不会来自模板，<br />而是来自你的现实。</h3>
            <p>调整左侧三个选项，然后生成一次个性化预览。</p>
          </div>

          <div v-else-if="demoState === 'thinking'" class="output-thinking">
            <div class="thinking-symbol"><span></span><i></i><b>AI</b></div>
            <h3>正在理解并规划…</h3>
            <div class="thinking-steps">
              <p :class="{ done: demoStep >= 1 }"><i>{{ demoStep >= 1 ? '✓' : '01' }}</i><span>匹配目标与当前阶段</span></p>
              <p :class="{ done: demoStep >= 2 }"><i>{{ demoStep >= 2 ? '✓' : '02' }}</i><span>计算可持续学习强度</span></p>
              <p :class="{ done: demoStep >= 3 }"><i>{{ demoStep >= 3 ? '✓' : '03' }}</i><span>生成里程碑与首周行动</span></p>
            </div>
          </div>

          <div v-else class="output-ready">
            <div class="ready-heading"><div><small>YOUR ADAPTIVE PATH</small><h3>{{ planTitle }}</h3></div><span><i></i> 已生成</span></div>
            <div class="ready-summary">
              <div><small>每日建议</small><b>{{ dailyMinutes }}<i>min</i></b></div>
              <div><small>指导策略</small><b>{{ guidance }}</b></div>
              <div><small>首轮周期</small><b>4<i>周</i></b></div>
            </div>
            <div class="ready-path">
              <article v-for="(item, index) in planItems" :key="item[0]">
                <span>0{{ index + 1 }}</span><div><small>{{ item[0] }}</small><b>{{ item[1] }}</b></div><em>{{ index === 0 ? '本周' : `第 ${index + 1} 阶段` }}</em>
              </article>
            </div>
            <div class="agent-suggestion"><span>序</span><p><small>AGENT SUGGESTION</small>考虑到你每周可用 {{ weeklyHours }} 小时，我会把首个实践任务控制在 {{ dailyMinutes }} 分钟左右，并采用“{{ guidance }}”反馈。</p></div>
            <button class="save-path" @click="enterProduct(true)">创建账户，保存并继续完善 <span>↗</span></button>
          </div>
        </div>
      </div>
    </section>

    <section class="trust-section section-shell reveal">
      <div class="trust-statement"><span>HUMAN IN CONTROL</span><h2>AI 提出方案，<br /><i>决定权始终属于你。</i></h2></div>
      <div class="trust-points">
        <article><b>可确认</b><p>画像推断与正式计划在写入前，清晰展示变更内容。</p></article>
        <article><b>可追溯</b><p>知识问答保留来源，计划调整说明依据与影响范围。</p></article>
        <article><b>可修改</b><p>时间、周期、节奏与策略随时可改，不被系统锁定。</p></article>
      </div>
    </section>

    <section class="final-cta">
      <div class="final-lines" aria-hidden="true"></div>
      <span class="eyebrow light">YOUR NEXT STEP</span>
      <h2>下一段学习路径，<br />从真正理解你开始。</h2>
      <p>用一场对话建立画像，让目标、行动与反馈从今天开始连接。</p>
      <button @click="enterProduct(true)"><span>{{ auth.authenticated ? '返回我的工作台' : '开始与学习 Agent 对话' }}</span><i>→</i></button>
    </section>

    <footer class="landing-footer">
      <div class="landing-brand"><span class="landing-brand-mark">序</span><span>知序</span></div>
      <p>AI Agent 驱动的自适应个人学习管理系统</p>
      <span>© 2026 ZHIXU</span>
    </footer>
  </main>
</template>

<style scoped>
.landing-page {
  --night: #09281f;
  --night-soft: #123b2f;
  --cream: #f2f0e8;
  --lime: #d1eb75;
  --gold: #d9b46c;
  --ink: #10231b;
  position: relative;
  overflow: hidden;
  min-height: 100vh;
  color: var(--ink);
  background: var(--cream);
  font-family: "Inter", "PingFang SC", "Microsoft YaHei", sans-serif;
}
.landing-page button { font: inherit; }
.pointer-glow { position: fixed; z-index: 50; top: 0; left: 0; width: 360px; height: 360px; border-radius: 50%; pointer-events: none; background: radial-gradient(circle, rgba(209, 235, 117, .09), transparent 68%); transform: translate(calc(var(--pointer-x, -500px) - 50%), calc(var(--pointer-y, -500px) - 50%)); mix-blend-mode: screen; }
.landing-nav { position: absolute; z-index: 20; top: 0; left: 50%; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; width: min(1440px, calc(100% - 80px)); height: 88px; border-bottom: 1px solid rgba(255,255,255,.14); color: white; transform: translateX(-50%); }
.landing-brand { display: flex; align-items: center; gap: 11px; border: 0; color: inherit; background: none; font-size: 19px; font-weight: 700; letter-spacing: .18em; cursor: pointer; }
.landing-brand-mark { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 50%; color: var(--night); background: var(--lime); font: 600 17px "Noto Serif SC", "Songti SC", serif; letter-spacing: 0; }
.nav-links { display: flex; gap: 36px; }
.nav-links button, .text-button { border: 0; color: rgba(255,255,255,.68); background: transparent; cursor: pointer; font-size: 12px; letter-spacing: .1em; }
.nav-links button:hover, .text-button:hover { color: white; }
.nav-actions { display: flex; align-items: center; justify-content: flex-end; gap: 24px; }
.nav-primary { display: flex; align-items: center; gap: 22px; padding: 11px 16px; border: 1px solid rgba(255,255,255,.34); color: white; background: rgba(255,255,255,.06); cursor: pointer; font-size: 12px; letter-spacing: .05em; transition: .25s ease; }
.nav-primary:hover { border-color: var(--lime); color: var(--night); background: var(--lime); }
.nav-primary span { font-size: 15px; }

.hero-section { position: relative; display: grid; grid-template-columns: minmax(0, 1.03fr) minmax(430px, .72fr); align-items: center; gap: clamp(50px, 7vw, 120px); min-height: 760px; padding: 150px max(6vw, calc((100vw - 1320px) / 2)) 80px; color: white; background: radial-gradient(circle at 72% 45%, rgba(54, 119, 92, .52), transparent 28%), linear-gradient(135deg, #071e18 0%, #0b3025 54%, #102f27 100%); }
.hero-grid { position: absolute; inset: 0; opacity: .16; background-image: linear-gradient(rgba(255,255,255,.12) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.1) 1px, transparent 1px); background-size: 80px 80px; mask-image: linear-gradient(to right, black, transparent 85%); }
.hero-orbit { position: absolute; border: 1px solid rgba(255,255,255,.1); border-radius: 50%; pointer-events: none; }
.hero-orbit::after { position: absolute; width: 7px; height: 7px; border-radius: 50%; background: var(--lime); box-shadow: 0 0 22px var(--lime); content: ""; }
.orbit-one { top: 17%; right: 4%; width: 470px; height: 470px; animation: orbit 28s linear infinite; }
.orbit-one::after { top: 14%; left: 12%; }
.orbit-two { right: 14%; bottom: -19%; width: 620px; height: 620px; animation: orbit 45s linear infinite reverse; }
.orbit-two::after { top: 48%; right: -4px; }
.hero-copy, .hero-console { position: relative; z-index: 2; }
.hero-kicker { display: flex; align-items: center; gap: 12px; color: #b9c9c2; font-size: 10px; font-weight: 700; letter-spacing: .24em; }
.hero-kicker span { width: 34px; height: 1px; background: var(--lime); }
.hero-copy h1 { max-width: 790px; margin: 27px 0 26px; font: 400 clamp(51px, 5.3vw, 82px)/1.08 "Noto Serif SC", "Songti SC", Georgia, serif; letter-spacing: -.045em; }
.hero-copy h1 span { color: var(--lime); font-style: italic; white-space: nowrap; }
.hero-intro { max-width: 580px; color: #b8c9c2; font-size: 16px; line-height: 1.9; }
.hero-actions { display: flex; align-items: center; gap: 30px; margin-top: 40px; }
.primary-cta { display: flex; align-items: center; justify-content: space-between; gap: 42px; min-width: 244px; padding: 17px 19px 17px 22px; border: 1px solid var(--lime); color: var(--night); background: var(--lime); cursor: pointer; font-size: 13px; font-weight: 700; transition: transform .25s ease, box-shadow .25s ease; }
.primary-cta:hover { box-shadow: 0 14px 35px rgba(209,235,117,.14); transform: translateY(-2px); }
.primary-cta i { font-size: 20px; font-style: normal; }
.watch-cta { display: flex; align-items: center; gap: 10px; border: 0; color: white; background: transparent; cursor: pointer; font-size: 12px; letter-spacing: .04em; }
.play-mark { display: grid; width: 31px; height: 31px; place-items: center; border: 1px solid rgba(255,255,255,.3); border-radius: 50%; font-size: 8px; }
.hero-principles { display: flex; gap: 23px; margin-top: 58px; color: #94aaa1; font-size: 10px; letter-spacing: .06em; }
.hero-principles span { display: flex; gap: 7px; }
.hero-principles i { color: var(--lime); font-style: normal; }

.hero-console { padding: 25px; border: 1px solid rgba(255,255,255,.19); background: linear-gradient(145deg, rgba(255,255,255,.1), rgba(255,255,255,.035)); box-shadow: 0 28px 80px rgba(0,0,0,.24); backdrop-filter: blur(18px); }
.console-topline, .console-foot { display: flex; align-items: center; justify-content: space-between; color: #7f9c91; font: 600 9px/1 monospace; letter-spacing: .14em; }
.console-status, .console-foot span { display: flex; align-items: center; gap: 7px; }
.console-status i, .console-foot i { width: 6px; height: 6px; border-radius: 50%; background: var(--lime); box-shadow: 0 0 12px rgba(209,235,117,.7); }
.agent-dialogue { display: grid; grid-template-columns: 42px 1fr; gap: 15px; margin: 32px 0; }
.agent-avatar { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 50%; color: var(--night); background: var(--lime); font-family: "Noto Serif SC", serif; }
.agent-dialogue small { color: #80988f; font-size: 8px; letter-spacing: .15em; }
.agent-dialogue p { margin: 7px 0 0; color: #d9e3df; font-size: 13px; line-height: 1.65; }
.console-question, .console-metric { padding: 20px 0; border-top: 1px solid rgba(255,255,255,.1); }
.console-question label, .console-metric label { display: block; margin-bottom: 13px; color: #9fb2aa; font-size: 10px; letter-spacing: .04em; }
.choice-row { display: flex; flex-wrap: wrap; gap: 7px; }
.choice-row button { padding: 9px 12px; border: 1px solid rgba(255,255,255,.15); color: #9fb2aa; background: rgba(255,255,255,.03); cursor: pointer; font-size: 11px; transition: .2s; }
.choice-row button.active { border-color: var(--lime); color: var(--night); background: var(--lime); }
.console-metric > div:first-child { display: flex; align-items: center; justify-content: space-between; }
.console-metric b { color: var(--lime); font: 500 20px Georgia, serif; }
input[type="range"] { width: 100%; height: 3px; border-radius: 2px; outline: none; background: linear-gradient(to right, var(--lime), var(--lime)); accent-color: var(--lime); cursor: pointer; }
.range-labels { display: flex; justify-content: space-between; margin-top: 8px; color: #769086; font-size: 8px; letter-spacing: .08em; }
.console-foot { padding-top: 19px; border-top: 1px solid rgba(255,255,255,.1); }
.console-foot button { border: 0; color: var(--lime); background: transparent; cursor: pointer; font-size: 10px; letter-spacing: .06em; }
.console-foot button b { margin-left: 7px; }
.hero-index { position: absolute; right: 22px; bottom: 35px; display: flex; align-items: center; gap: 10px; color: rgba(255,255,255,.28); font-size: 8px; letter-spacing: .22em; transform: rotate(-90deg); transform-origin: right bottom; }
.hero-index b { color: var(--lime); font: 400 23px Georgia, serif; }

.principle-band { display: flex; align-items: center; justify-content: center; gap: clamp(25px, 6vw, 90px); min-height: 88px; color: #748079; background: #e8e8df; font: 600 10px/1 sans-serif; letter-spacing: .14em; text-transform: uppercase; }
.principle-band i { width: 4px; height: 4px; border-radius: 50%; background: var(--gold); }
.section-shell { width: min(1320px, calc(100% - 80px)); margin: 0 auto; }
.capabilities-section, .experience-section { padding: 130px 0; }
.section-heading { display: grid; grid-template-columns: 150px minmax(0, 1fr) 350px; align-items: end; gap: 40px; margin-bottom: 70px; }
.section-no { align-self: start; color: #8c958f; font: 600 9px monospace; letter-spacing: .17em; }
.section-no.light { color: #789187; }
.eyebrow { color: #8b7653; font-size: 9px; font-weight: 700; letter-spacing: .2em; }
.eyebrow.light { color: #99b0a6; }
.section-heading h2 { margin: 16px 0 0; font: 400 clamp(38px, 4vw, 58px)/1.18 "Noto Serif SC", "Songti SC", Georgia, serif; letter-spacing: -.035em; }
.section-heading h2 span { color: #356c56; font-style: italic; }
.section-heading > p { margin: 0; color: #7b847f; font-size: 13px; line-height: 1.9; }

.feature-stage { display: grid; grid-template-columns: 150px 300px minmax(0, 1fr); min-height: 510px; border-top: 1px solid #cfd2ca; }
.feature-tabs { display: flex; flex-direction: column; border-right: 1px solid #cfd2ca; }
.feature-tabs button { display: grid; grid-template-columns: 27px 1fr auto; align-items: center; gap: 8px; min-height: 75px; padding: 0 18px 0 0; border: 0; border-bottom: 1px solid #d8dad3; color: #939b96; background: transparent; cursor: pointer; text-align: left; transition: .2s; }
.feature-tabs button span { font: 500 9px monospace; }
.feature-tabs button b { font-size: 12px; letter-spacing: .12em; }
.feature-tabs button i { opacity: 0; font-style: normal; }
.feature-tabs button.active { color: var(--night); }
.feature-tabs button.active span { color: #a2824c; }
.feature-tabs button.active i { opacity: 1; }
.feature-copy { padding: 52px 38px; }
.feature-copy h3 { margin: 19px 0; font: 500 28px/1.45 "Noto Serif SC", "Songti SC", serif; }
.feature-copy p { color: #77817b; font-size: 13px; line-height: 1.95; }
.feature-copy button { display: flex; gap: 16px; margin-top: 34px; padding: 0 0 7px; border: 0; border-bottom: 1px solid #9c875e; color: #5d4e34; background: transparent; cursor: pointer; font-size: 11px; }
.product-window { align-self: stretch; margin-top: 38px; overflow: hidden; border: 1px solid #c7cec7; background: #f8faf6; box-shadow: 0 24px 60px rgba(22,48,38,.09); }
.window-bar { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; height: 42px; padding: 0 14px; border-bottom: 1px solid #d9ddd8; color: #9aa39e; font: 600 8px monospace; letter-spacing: .13em; }
.window-bar > div { display: flex; gap: 5px; }
.window-bar i { width: 5px; height: 5px; border-radius: 50%; background: #bdc5c0; }
.window-bar b { justify-self: end; letter-spacing: .2em; }
.mock-screen { min-height: 425px; animation: screen-in .35s ease both; }
.mock-profile { display: grid; grid-template-columns: .82fr 1.18fr; }
.profile-radar { position: relative; display: grid; place-content: center; place-items: center; overflow: hidden; color: #89a097; background: #103a2e; font-size: 9px; letter-spacing: .08em; }
.profile-radar::before, .profile-radar::after, .profile-radar i { position: absolute; width: 210px; height: 210px; border: 1px solid rgba(209,235,117,.16); border-radius: 50%; content: ""; }
.profile-radar::after { width: 130px; height: 130px; }
.profile-radar i:nth-of-type(1) { width: 55px; height: 55px; background: rgba(209,235,117,.08); }
.profile-radar i:nth-of-type(2) { width: 1px; height: 100%; border: 0; border-left: 1px solid rgba(209,235,117,.12); border-radius: 0; }
.profile-radar i:nth-of-type(3) { width: 100%; height: 1px; border: 0; border-top: 1px solid rgba(209,235,117,.12); border-radius: 0; }
.profile-radar span, .profile-radar b { position: relative; z-index: 2; }
.profile-radar b { margin-top: 8px; color: var(--lime); font: 400 52px Georgia, serif; }
.profile-facts { padding: 48px 36px; }
.profile-facts > small, .plan-head small, .focus-copy small, .knowledge-answer > small, .insight-chart small { color: #9b8866; font-size: 8px; letter-spacing: .13em; }
.profile-facts h4 { margin: 12px 0 31px; font: 500 20px/1.55 "Noto Serif SC", serif; }
.profile-facts > div { display: grid; grid-template-columns: 70px 1fr 35px; align-items: center; gap: 10px; margin: 17px 0; font-size: 9px; }
.profile-facts > div b { height: 3px; background: linear-gradient(to right, #3d8063 var(--value), #dde3de var(--value)); }
.profile-facts > div em { color: #6f7d76; font-style: normal; text-align: right; }
.profile-facts p { margin-top: 30px; padding: 12px; color: #53685d; background: #e7eee8; font-size: 9px; }
.profile-facts p i { display: inline-block; width: 6px; height: 6px; margin-right: 7px; border-radius: 50%; background: #6e9d58; }
.mock-plan { padding: 45px 38px; }
.plan-head { display: flex; align-items: end; justify-content: space-between; }
.plan-head h4 { margin: 8px 0 0; font: 500 23px "Noto Serif SC", serif; }
.plan-head > span { padding: 7px 10px; color: #486b5b; background: #e4ede7; font-size: 9px; }
.plan-line { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 43px; }
.plan-line article { position: relative; display: flex; min-height: 160px; padding: 24px 17px; border-top: 1px solid #ccd5ce; flex-direction: column; }
.plan-line article::before { position: absolute; top: -4px; left: 18px; width: 7px; height: 7px; border-radius: 50%; background: #aab6af; content: ""; }
.plan-line article.active { color: white; background: #164536; }
.plan-line article.active::before, .plan-line article.done::before { background: var(--lime); }
.plan-line article i { color: #829189; font: 400 11px monospace; font-style: normal; }
.plan-line article small { margin: 24px 0 8px; color: #94a29a; font-size: 8px; }
.plan-line article b { font: 500 13px "Noto Serif SC", serif; }
.plan-line article span { margin-top: auto; color: #91a099; font-size: 9px; }
.plan-note { margin: 23px 0 0; padding: 14px 18px; color: #697871; background: #edf1ed; font-size: 10px; line-height: 1.6; }
.plan-note span { margin-right: 10px; color: #8f7143; font: 700 8px monospace; }
.mock-today { display: grid; grid-template-columns: 180px 1fr 95px; align-items: center; padding: 44px 30px; }
.focus-ring { display: grid; width: 140px; height: 140px; place-items: center; border: 1px solid #bfd0c5; border-radius: 50%; box-shadow: inset 0 0 0 12px #f4f7f3, inset 0 0 0 13px #dce5df; }
.focus-ring div { display: flex; align-items: baseline; gap: 5px; }
.focus-ring b { color: #225941; font: 400 43px Georgia, serif; }
.focus-ring span { color: #94a099; font-size: 8px; }
.focus-copy h4 { margin: 13px 0; font: 500 23px/1.5 "Noto Serif SC", serif; }
.focus-copy p { color: #7b8780; font-size: 10px; line-height: 1.7; }
.focus-copy button { display: flex; justify-content: space-between; width: 170px; margin-top: 25px; padding: 12px 14px; border: 0; color: white; background: #174737; font-size: 10px; }
.focus-side { display: flex; padding-left: 17px; border-left: 1px solid #d7ddd8; flex-direction: column; }
.focus-side > span { margin-bottom: 24px; color: #95784d; font-size: 8px; }
.focus-side b { color: #275d47; font: 400 28px Georgia, serif; }
.focus-side small { margin: 3px 0 15px; color: #97a29c; font-size: 8px; }
.mock-knowledge { display: grid; grid-template-columns: 150px 1fr; }
.mock-knowledge aside { display: flex; gap: 8px; padding: 31px 16px; border-right: 1px solid #dce1dd; flex-direction: column; }
.mock-knowledge aside small { margin-bottom: 12px; color: #9a8a70; font-size: 7px; letter-spacing: .1em; }
.mock-knowledge aside b { padding: 10px; color: #8e9992; font-size: 9px; font-weight: 500; }
.mock-knowledge aside b.selected { color: #275542; background: #e2ebe5; }
.knowledge-answer { padding: 39px 33px; }
.knowledge-answer h4 { margin: 13px 0; font: 500 23px/1.45 "Noto Serif SC", serif; }
.knowledge-answer > p { color: #6e7b73; font-size: 11px; line-height: 1.9; }
.knowledge-answer > div { display: grid; grid-template-columns: 25px 1fr auto; gap: 10px; margin-top: 12px; padding: 11px 0; border-top: 1px solid #e0e4e0; font-size: 8px; }
.knowledge-answer > div span { color: #a08454; }
.knowledge-answer > div em { color: #9ba49f; font-style: normal; }
.mock-insight { display: grid; grid-template-columns: 1.5fr .8fr; padding: 42px 34px; }
.insight-chart { padding-right: 34px; border-right: 1px solid #d8ded9; }
.insight-chart h4 { margin: 10px 0; font: 500 21px "Noto Serif SC", serif; }
.chart-bars { display: flex; align-items: end; gap: 12px; height: 220px; border-bottom: 1px solid #ced7d1; background: repeating-linear-gradient(to bottom, transparent, transparent 54px, #e5e9e5 55px); }
.chart-bars i { width: 100%; max-width: 28px; background: linear-gradient(to top, #184c39, #7aa286); animation: bar-grow .45s ease both; }
.chart-axis { display: flex; justify-content: space-between; margin-top: 8px; color: #a0a8a3; font-size: 7px; }
.insight-note { align-self: center; margin-left: 27px; padding: 23px; color: white; background: #164536; }
.insight-note > span { color: #9bb0a7; font-size: 8px; }
.insight-note > b { display: block; margin: 14px 0; color: var(--lime); font: 400 38px Georgia, serif; }
.insight-note p { color: #c1d0ca; font-size: 10px; line-height: 1.8; }
.insight-note small { color: #859f95; font-size: 7px; }
.insight-note small i { display: inline-block; width: 5px; height: 5px; margin-right: 5px; border-radius: 50%; background: var(--lime); }

.workflow-section { padding: 130px 0 145px; color: white; background: #0b3025; }
.workflow-heading { display: grid; grid-template-columns: 150px 1fr 360px; align-items: end; gap: 40px; }
.workflow-heading h2 { margin: 0; font: 400 clamp(42px, 5vw, 67px)/1.18 "Noto Serif SC", serif; letter-spacing: -.035em; }
.workflow-heading h2 i { color: var(--lime); font-style: italic; }
.workflow-heading p { margin: 0; color: #92a89f; font-size: 13px; line-height: 1.9; }
.loop-grid { display: grid; grid-template-columns: repeat(4, 1fr); margin-top: 85px; border-top: 1px solid rgba(255,255,255,.16); border-bottom: 1px solid rgba(255,255,255,.16); }
.loop-grid article { position: relative; min-height: 250px; padding: 25px 27px; border-right: 1px solid rgba(255,255,255,.12); }
.loop-grid article:last-child { border-right: 0; }
.loop-grid article > span { color: #779086; font: 600 8px monospace; }
.loop-icon { margin: 34px 0 25px; color: var(--lime); font: 300 32px Georgia, serif; }
.loop-grid h3 { margin: 0 0 12px; font: 500 22px "Noto Serif SC", serif; }
.loop-grid p { max-width: 190px; color: #8fa69c; font-size: 10px; line-height: 1.8; }
.loop-grid article > i { position: absolute; top: 50%; right: -8px; z-index: 2; display: grid; width: 17px; height: 17px; place-items: center; border-radius: 50%; color: #789188; background: #0b3025; font-size: 11px; font-style: normal; }
.loop-grid article:last-child > i { right: 24px; color: var(--lime); }

.experience-section { padding-bottom: 150px; }
.planner-lab { display: grid; grid-template-columns: 410px 1fr; min-height: 650px; border: 1px solid #c9cec7; background: #f7f8f3; box-shadow: 0 28px 80px rgba(27,54,43,.1); }
.lab-controls { padding: 29px 34px; border-right: 1px solid #d4d9d3; }
.lab-title, .output-head { display: flex; align-items: center; justify-content: space-between; padding-bottom: 22px; border-bottom: 1px solid #d8ddd7; color: #849089; font: 600 8px monospace; letter-spacing: .13em; }
.lab-controls fieldset { margin: 28px 0 0; padding: 0; border: 0; }
.lab-controls legend { width: 100%; margin-bottom: 15px; color: #334b40; font-size: 11px; font-weight: 700; }
.lab-controls legend span { margin-right: 8px; color: #9b8158; font: 600 8px monospace; }
.lab-options { display: grid; gap: 7px; }
.lab-options button { display: flex; align-items: center; justify-content: space-between; padding: 12px 13px; border: 1px solid #d5dbd5; color: #6b7770; background: white; cursor: pointer; font-size: 11px; }
.lab-options button i { display: none; color: #315d4a; font-style: normal; }
.lab-options button.active { border-color: #5c816f; color: #214b38; background: #e8f0e8; }
.lab-options button.active i { display: inline; }
.hours-display { display: flex; align-items: end; gap: 10px; margin: 4px 0 18px; }
.hours-display b { color: #164b37; font: 400 49px Georgia, serif; line-height: 1; }
.hours-display span { color: #99a39e; font: 600 7px/1.3 monospace; }
.lab-controls .range-labels { color: #9aa39e; }
.guidance-switch { display: grid; grid-template-columns: 1fr 1fr; padding: 3px; background: #e5e9e4; }
.guidance-switch button { padding: 10px; border: 0; color: #8a948e; background: transparent; cursor: pointer; font-size: 10px; }
.guidance-switch button.active { color: #1d4b37; background: white; box-shadow: 0 3px 10px rgba(35,64,51,.08); }
.generate-button { display: flex; align-items: center; justify-content: space-between; width: 100%; margin-top: 30px; padding: 16px 18px; border: 0; color: white; background: #123f30; cursor: pointer; font-size: 11px; font-weight: 700; }
.generate-button:disabled { cursor: wait; opacity: .8; }
.generate-button i { color: var(--lime); font-size: 18px; font-style: normal; }
.lab-output { padding: 29px 34px; background: radial-gradient(circle at 75% 30%, rgba(167,197,179,.12), transparent 32%), #eef2ec; }
.output-head div { display: flex; align-items: center; gap: 8px; }
.output-head i { width: 5px; height: 5px; border-radius: 50%; background: #72a383; box-shadow: 0 0 8px #72a383; }
.output-head b { color: #527363; font-size: 7px; }
.output-idle, .output-thinking { display: flex; min-height: 560px; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.idle-orbit { position: relative; display: grid; width: 120px; height: 120px; margin-bottom: 30px; place-items: center; border: 1px solid #cad8cf; border-radius: 50%; }
.idle-orbit::before { position: absolute; width: 78px; height: 78px; border: 1px solid #d4ded7; border-radius: 50%; content: ""; }
.idle-orbit span { position: relative; z-index: 2; color: #4d7964; font: 500 15px Georgia, serif; }
.idle-orbit i { position: absolute; width: 6px; height: 6px; border-radius: 50%; background: #8aab77; }
.idle-orbit i:first-of-type { top: 9px; left: 28px; }
.idle-orbit i:last-of-type { right: 0; bottom: 42px; }
.output-idle > small { color: #9a815b; font-size: 8px; letter-spacing: .14em; }
.output-idle h3, .output-thinking h3 { margin: 16px 0 12px; font: 500 27px/1.5 "Noto Serif SC", serif; }
.output-idle p { color: #8a948e; font-size: 11px; }
.thinking-symbol { position: relative; display: grid; width: 110px; height: 110px; margin-bottom: 25px; place-items: center; border: 1px solid #b8cbc0; border-radius: 50%; animation: pulse 1.4s ease infinite; }
.thinking-symbol span, .thinking-symbol i { position: absolute; inset: -11px; border: 1px dashed #c0d0c6; border-radius: 50%; animation: orbit 7s linear infinite; }
.thinking-symbol i { inset: 14px; animation-direction: reverse; animation-duration: 4s; }
.thinking-symbol b { color: #34634e; font: 500 16px Georgia, serif; }
.thinking-steps { width: min(360px, 100%); margin-top: 20px; text-align: left; }
.thinking-steps p { display: flex; align-items: center; gap: 15px; margin: 0; padding: 13px 0; border-bottom: 1px solid #d4dbd5; color: #98a29c; font-size: 10px; }
.thinking-steps p i { display: grid; width: 24px; height: 24px; place-items: center; border: 1px solid #cad3cd; border-radius: 50%; font: 500 7px monospace; font-style: normal; }
.thinking-steps p.done { color: #3e604f; }
.thinking-steps p.done i { border-color: #739681; color: #174834; background: #dce9dd; }
.output-ready { padding-top: 34px; animation: screen-in .45s ease both; }
.ready-heading { display: flex; align-items: start; justify-content: space-between; gap: 20px; }
.ready-heading small { color: #9a8057; font: 600 8px monospace; letter-spacing: .13em; }
.ready-heading h3 { margin: 10px 0; font: 500 clamp(22px, 2.3vw, 31px) "Noto Serif SC", serif; }
.ready-heading > span { display: flex; align-items: center; gap: 6px; padding: 7px 10px; color: #52705f; background: #dde9df; font-size: 8px; }
.ready-heading > span i { width: 5px; height: 5px; border-radius: 50%; background: #5b8b6f; }
.ready-summary { display: grid; grid-template-columns: repeat(3, 1fr); margin: 24px 0; border-top: 1px solid #d0d8d2; border-bottom: 1px solid #d0d8d2; }
.ready-summary div { padding: 16px 13px; border-right: 1px solid #d0d8d2; }
.ready-summary div:last-child { border: 0; }
.ready-summary small { display: block; margin-bottom: 8px; color: #909c95; font-size: 8px; }
.ready-summary b { color: #214f3b; font: 500 19px "Noto Serif SC", serif; }
.ready-summary b i { margin-left: 3px; color: #849189; font-size: 8px; font-style: normal; }
.ready-path article { display: grid; grid-template-columns: 34px 1fr auto; align-items: center; gap: 13px; padding: 15px 10px; border-bottom: 1px solid #d8ded9; }
.ready-path article > span { display: grid; width: 27px; height: 27px; place-items: center; border: 1px solid #aebfb4; border-radius: 50%; color: #496b5a; font: 500 8px monospace; }
.ready-path small { display: block; margin-bottom: 4px; color: #9a815b; font-size: 7px; }
.ready-path b { font: 500 12px "Noto Serif SC", serif; }
.ready-path em { color: #89968f; font-size: 8px; font-style: normal; }
.agent-suggestion { display: grid; grid-template-columns: 32px 1fr; gap: 11px; margin-top: 20px; padding: 14px; color: #c4d2cb; background: #174637; }
.agent-suggestion > span { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 50%; color: #1a4536; background: var(--lime); font: 500 12px serif; }
.agent-suggestion p { margin: 0; font-size: 9px; line-height: 1.7; }
.agent-suggestion small { display: block; margin-bottom: 3px; color: #8ca69b; font-size: 7px; }
.save-path { display: flex; align-items: center; justify-content: space-between; width: 100%; margin-top: 16px; padding: 13px 16px; border: 1px solid #315e4a; color: #1e503b; background: transparent; cursor: pointer; font-size: 10px; }

.trust-section { display: grid; grid-template-columns: .9fr 1.5fr; gap: 100px; padding: 50px 0 130px; border-top: 1px solid #cdd1ca; }
.trust-statement > span { color: #957c54; font: 700 8px monospace; letter-spacing: .15em; }
.trust-statement h2 { margin: 20px 0; font: 400 34px/1.4 "Noto Serif SC", serif; }
.trust-statement h2 i { color: #3d715b; font-style: italic; }
.trust-points { display: grid; grid-template-columns: repeat(3, 1fr); gap: 30px; }
.trust-points article { padding-top: 18px; border-top: 2px solid #2e634d; }
.trust-points b { font: 500 17px "Noto Serif SC", serif; }
.trust-points p { color: #7d8781; font-size: 11px; line-height: 1.8; }
.final-cta { position: relative; display: flex; min-height: 540px; padding: 90px 30px; align-items: center; justify-content: center; overflow: hidden; color: white; background: #0a2c22; flex-direction: column; text-align: center; }
.final-lines { position: absolute; width: 650px; height: 650px; border: 1px solid rgba(209,235,117,.08); border-radius: 50%; box-shadow: 0 0 0 75px rgba(209,235,117,.025), 0 0 0 150px rgba(209,235,117,.018); }
.final-cta > *:not(.final-lines) { position: relative; z-index: 1; }
.final-cta h2 { margin: 23px 0 14px; font: 400 clamp(42px, 5vw, 65px)/1.25 "Noto Serif SC", serif; letter-spacing: -.035em; }
.final-cta p { color: #9db0a7; font-size: 13px; }
.final-cta button { display: flex; align-items: center; justify-content: space-between; gap: 50px; min-width: 270px; margin-top: 35px; padding: 17px 19px; border: 1px solid var(--lime); color: #102f24; background: var(--lime); cursor: pointer; font-size: 12px; font-weight: 700; }
.final-cta button i { font-size: 19px; font-style: normal; }
.landing-footer { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; min-height: 110px; padding: 0 max(6vw, calc((100vw - 1320px) / 2)); color: #84978e; background: #071f18; font-size: 9px; letter-spacing: .08em; }
.landing-footer .landing-brand { color: white; }
.landing-footer > span { justify-self: end; }

.reveal { opacity: 0; transform: translateY(26px); transition: opacity .8s ease, transform .8s cubic-bezier(.2,.7,.2,1); }
.reveal.is-visible { opacity: 1; transform: translateY(0); }
@keyframes orbit { to { transform: rotate(360deg); } }
@keyframes screen-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
@keyframes bar-grow { from { height: 0; } }
@keyframes pulse { 50% { box-shadow: 0 0 0 15px rgba(84,130,104,.06); } }

@media (max-width: 1120px) {
  .landing-nav { width: calc(100% - 48px); }
  .hero-section { grid-template-columns: 1fr 410px; gap: 45px; padding-inline: 40px; }
  .hero-copy h1 { font-size: clamp(48px, 6vw, 68px); }
  .section-shell { width: calc(100% - 60px); }
  .section-heading { grid-template-columns: 110px 1fr 280px; }
  .feature-stage { grid-template-columns: 120px 250px 1fr; }
  .feature-copy { padding-inline: 28px; }
  .workflow-heading { grid-template-columns: 110px 1fr 300px; }
  .planner-lab { grid-template-columns: 360px 1fr; }
}

@media (max-width: 900px) {
  .nav-links { display: none; }
  .landing-nav { grid-template-columns: 1fr auto; }
  .hero-section { grid-template-columns: 1fr; padding: 145px 40px 80px; }
  .hero-copy { max-width: 720px; }
  .hero-console { width: min(570px, 100%); }
  .section-heading, .workflow-heading { grid-template-columns: 90px 1fr; }
  .section-heading > p, .workflow-heading > p { grid-column: 2; }
  .feature-stage { grid-template-columns: 1fr; }
  .feature-tabs { display: grid; grid-template-columns: repeat(5, 1fr); border-right: 0; }
  .feature-tabs button { grid-template-columns: 1fr; justify-items: center; min-height: 64px; padding: 9px; text-align: center; }
  .feature-tabs button span, .feature-tabs button i { display: none; }
  .feature-copy { padding: 35px 0; }
  .product-window { margin-top: 0; }
  .loop-grid { grid-template-columns: repeat(2, 1fr); }
  .loop-grid article:nth-child(2) { border-right: 0; }
  .planner-lab { grid-template-columns: 1fr; }
  .lab-controls { border-right: 0; border-bottom: 1px solid #d4d9d3; }
  .lab-controls fieldset { display: inline-block; width: calc(33.333% - 14px); margin-right: 17px; vertical-align: top; }
  .lab-controls fieldset:nth-of-type(3) { margin-right: 0; }
  .generate-button { margin-top: 28px; }
  .trust-section { grid-template-columns: 1fr; gap: 30px; }
}

@media (max-width: 640px) {
  .pointer-glow { display: none; }
  .landing-nav { width: calc(100% - 34px); height: 72px; }
  .landing-brand { font-size: 16px; }
  .landing-brand-mark { width: 30px; height: 30px; font-size: 14px; }
  .text-button { display: none; }
  .nav-primary { gap: 9px; padding: 10px 12px; font-size: 10px; }
  .hero-section { min-height: auto; padding: 120px 20px 65px; }
  .hero-copy h1 { margin-top: 22px; font-size: 46px; }
  .hero-intro { font-size: 14px; }
  .hero-actions { align-items: stretch; flex-direction: column; gap: 18px; }
  .primary-cta { width: 100%; }
  .watch-cta { align-self: center; }
  .hero-principles { display: grid; grid-template-columns: 1fr 1fr; margin-top: 40px; }
  .hero-console { padding: 19px; }
  .agent-dialogue { margin: 25px 0; }
  .principle-band { gap: 13px; padding: 0 14px; text-align: center; }
  .principle-band p { font-size: 8px; line-height: 1.4; }
  .section-shell { width: calc(100% - 34px); }
  .capabilities-section, .experience-section { padding: 90px 0; }
  .section-heading, .workflow-heading { grid-template-columns: 1fr; gap: 20px; margin-bottom: 45px; }
  .section-heading > p, .workflow-heading > p { grid-column: 1; }
  .section-heading h2 { font-size: 38px; }
  .feature-tabs { overflow-x: auto; grid-template-columns: repeat(5, minmax(70px, 1fr)); }
  .feature-copy h3 { font-size: 24px; }
  .product-window { overflow-x: auto; }
  .mock-screen { min-width: 600px; }
  .workflow-section { padding: 90px 0; }
  .workflow-heading h2 { font-size: 43px; }
  .loop-grid { grid-template-columns: 1fr; margin-top: 50px; }
  .loop-grid article { min-height: 210px; border-right: 0; border-bottom: 1px solid rgba(255,255,255,.12); }
  .loop-grid article > i { top: auto; right: 50%; bottom: -8px; transform: rotate(90deg); }
  .loop-grid article:last-child > i { right: 25px; bottom: 20px; transform: none; }
  .planner-lab { min-height: 0; }
  .lab-controls, .lab-output { padding: 24px 20px; }
  .lab-controls fieldset { display: block; width: 100%; margin: 28px 0 0; }
  .lab-output { min-height: 600px; }
  .ready-summary { grid-template-columns: 1fr 1fr 1fr; }
  .ready-summary div { padding-inline: 8px; }
  .ready-summary b { font-size: 14px; }
  .ready-path article { grid-template-columns: 30px 1fr; }
  .ready-path article em { display: none; }
  .trust-points { grid-template-columns: 1fr; }
  .final-cta { min-height: 500px; }
  .landing-footer { display: flex; padding: 30px 20px; gap: 18px; flex-direction: column; justify-content: center; text-align: center; }
  .landing-footer > span { justify-self: auto; }
}

@media (prefers-reduced-motion: reduce) {
  .landing-page *, .landing-page *::before, .landing-page *::after { scroll-behavior: auto !important; animation-duration: .01ms !important; animation-iteration-count: 1 !important; transition-duration: .01ms !important; }
  .pointer-glow { display: none; }
}
</style>
