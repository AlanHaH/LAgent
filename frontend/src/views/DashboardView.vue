<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { api } from '../api/http'

const overview = ref<any>({ metrics: {}, studyTime: [], mastery: [] }); const tasks = ref<any[]>([]); const goals = ref<any[]>([]); const loading = ref(true)
const start = dayjs().subtract(6, 'day').format('YYYY-MM-DD'); const end = dayjs().format('YYYY-MM-DD')
const metrics = computed(() => overview.value.metrics || {})
const completed = computed(() => Number(metrics.value.completedTasks?.value || 0)); const planned = computed(() => Number(metrics.value.plannedTasks?.value || 0))
onMounted(async () => {
  const results = await Promise.allSettled([api<any>({ url: '/analytics/overview', params: { start, end } }), api<any[]>({ url: '/tasks', params: { date: end } }), api<any>({ url: '/goals', params: { status: 'ACTIVE', pageSize: 5 } })])
  if (results[0].status === 'fulfilled') overview.value = results[0].value
  if (results[1].status === 'fulfilled') tasks.value = results[1].value
  if (results[2].status === 'fulfilled') goals.value = results[2].value.items
  loading.value = false
})
</script>

<template>
  <div v-loading="loading">
    <div class="page-head"><div><h2>把注意力放在最重要的事上</h2><p>{{ dayjs().format('M 月 D 日') }} · 系统已根据你的目标和可用时间整理今日行动。</p></div><el-button type="primary" @click="$router.push('/today')">开始今日学习</el-button></div>
    <div class="grid grid-4">
      <div class="panel stat"><span class="label">本周有效学习</span><div class="value">{{ Math.round(Number(metrics.effectiveStudySeconds?.value || 0) / 60) }}<small> 分钟</small></div><span class="delta">自动与手工学习均计入</span></div>
      <div class="panel stat"><span class="label">任务完成率</span><div class="value">{{ metrics.taskCompletionRate?.value ?? '—' }}<small v-if="metrics.taskCompletionRate?.value">%</small></div><span class="delta">{{ completed }} / {{ planned }} 项</span></div>
      <div class="panel stat"><span class="label">连续学习</span><div class="value">{{ overview.studyTime.filter((x:any) => x.totalSeconds > 0).length }}<small> 天</small></div><span class="delta">近 7 天记录</span></div>
      <div class="panel stat"><span class="label">掌握度证据</span><div class="value">{{ overview.mastery.length }}<small> 项</small></div><span class="delta">置信度与分数独立展示</span></div>
    </div>
    <div class="grid dashboard-body">
      <section class="panel">
        <div class="panel-title"><div><h3>今日行动</h3><p>按优先级与时间窗口执行</p></div><el-button text @click="$router.push('/today')">查看全部</el-button></div>
        <div v-if="!tasks.length" class="empty">今天还没有任务。发布一个 Agent 计划后，这里会自动出现行动项。</div>
        <div v-for="task in tasks.slice(0,5)" :key="task.publicId" class="task-row">
          <span class="task-time">{{ task.task.scheduledStart ? dayjs(task.task.scheduledStart).format('HH:mm') : '待安排' }}</span><div class="task-main"><b>{{ task.task.title }}</b><small>{{ task.task.estimatedMinutes }} 分钟 · {{ task.task.lifecycleStatus }}</small></div><el-progress type="circle" :width="40" :stroke-width="4" :percentage="task.task.lifecycleStatus === 'COMPLETED' ? 100 : 0" :show-text="false" />
        </div>
      </section>
      <section class="panel">
        <div class="panel-title"><div><h3>活动目标</h3><p>Agent 规划围绕目标展开</p></div><el-button text @click="$router.push('/goals')">管理</el-button></div>
        <div v-if="!goals.length" class="empty">创建并激活一个学习目标，开始生成个性化计划。</div>
        <div v-for="goal in goals" :key="goal.publicId" class="goal-row"><div><span class="tag">{{ goal.type }}</span><h4>{{ goal.name }}</h4><small>{{ goal.startDate }} — {{ goal.dueDate }}</small></div><el-button circle @click="$router.push('/plans')">→</el-button></div>
      </section>
    </div>
  </div>
</template>

<style scoped>.dashboard-body{grid-template-columns:1.45fr 1fr;margin-top:18px}.value small{font:500 12px 'Noto Sans SC';color:#7d887f}.task-row{display:flex;align-items:center;border-top:1px solid var(--line);padding:15px 3px}.task-time{width:66px;color:var(--green);font:500 13px monospace}.task-main{flex:1}.task-main b,.task-main small{display:block}.task-main small,.goal-row small{color:var(--muted);margin-top:5px;font-size:11px}.goal-row{display:flex;justify-content:space-between;align-items:center;border-top:1px solid var(--line);padding:15px 3px}.goal-row h4{margin:8px 0 3px}@media(max-width:900px){.dashboard-body{grid-template-columns:1fr}}</style>
