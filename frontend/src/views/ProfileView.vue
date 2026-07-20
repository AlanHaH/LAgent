<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/http'

const active = ref(0); const loading = ref(false); const directions = ref<any[]>([]); const profileVersion = ref<number>(); const preferenceVersion = ref<number>()
const profile = reactive({ timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai', weekStart: 1, planPeriodDays: 28, backgroundText: '', directionId: undefined as number|undefined, currentStage: 'BEGINNER' })
const pref = reactive({ contentModes: ['TEXT', 'PRACTICE'], guidanceStyle: 'SOCRATIC', taskGranularity: 'MEDIUM', focusMinutes: 45, capacityRatio: .85, difficultyMin: 1, difficultyMax: 4, reminders: { TASK_DUE: true, TASK_OVERDUE: true } })
const slots = ref<any[]>(([{ weekday: 1, start: '19:00', end: '21:00', energyLevel: 'HIGH' },{ weekday: 3, start: '19:00', end: '21:00', energyLevel: 'MEDIUM' },{ weekday: 6, start: '09:00', end: '12:00', energyLevel: 'HIGH' }]))
onMounted(async () => {
  directions.value = await api<any[]>({ url: '/learning-directions' })
  const current = await api<any>({ url: '/profiles/me' })
  if (current) { Object.assign(profile, current, { directionId: current.directions?.[0]?.directionId, currentStage: current.directions?.[0]?.currentStage }); profileVersion.value = current.version; if (current.preference) { Object.assign(pref, current.preference); preferenceVersion.value = current.preference.version } }
  try { const a = await api<any>({ url: '/profiles/me/availability' }); if (a.rules?.length) slots.value = a.rules.map((x:any) => ({ weekday:x.weekday,start:x.startTime||x.start,end:x.endTime||x.end,energyLevel:x.energyLevel })) } catch { /* optional */ }
})
async function next() {
  loading.value = true
  try {
    if (active.value === 0) { const saved = await api<any>({ method:'PUT',url:'/profiles/me',data:{ timezone:profile.timezone,weekStart:profile.weekStart,planPeriodDays:profile.planPeriodDays,backgroundText:profile.backgroundText,directions:[{directionId:profile.directionId,currentStage:profile.currentStage,primary:true}],version:profileVersion.value } }); profileVersion.value=saved.version }
    if (active.value === 1) { const saved = await api<any>({method:'PUT',url:'/profiles/me/preferences',data:{...pref,version:preferenceVersion.value}}); preferenceVersion.value=saved.version }
    if (active.value === 2) await api({method:'PUT',url:'/profiles/me/availability',data:{slots:slots.value}})
    if (active.value < 3) active.value++; else { await api({method:'POST',url:'/profiles/me/generation-jobs'}); ElMessage.success('学习画像生成完成') }
  } finally { loading.value=false }
}
function addSlot(){slots.value.push({weekday:2,start:'19:00',end:'20:00',energyLevel:'MEDIUM'})}
</script>

<template>
  <div class="profile-wrap"><div class="page-head"><div><h2>建立你的学习画像</h2><p>这些信息只用于约束计划生成；任何 Agent 修改都需要你确认。</p></div></div>
    <el-steps :active="active" finish-status="success" simple><el-step title="基础与方向"/><el-step title="学习偏好"/><el-step title="可用时间"/><el-step title="确认生成"/></el-steps>
    <section class="panel profile-panel">
      <template v-if="active===0"><div class="panel-title"><div><h3>你现在在哪里，想去哪里？</h3><p>主方向用于诊断、知识点和计划的默认范围。</p></div></div><el-form label-position="top" class="form-grid"><el-form-item label="时区"><el-input v-model="profile.timezone"/></el-form-item><el-form-item label="计划周期"><el-select v-model="profile.planPeriodDays"><el-option :value="14" label="2 周"/><el-option :value="28" label="4 周"/><el-option :value="56" label="8 周"/></el-select></el-form-item><el-form-item label="学习方向"><el-select v-model="profile.directionId" filterable><el-option v-for="d in directions" :key="d.id" :value="Number(d.id)" :label="d.name"/></el-select></el-form-item><el-form-item label="当前阶段"><el-select v-model="profile.currentStage"><el-option value="BEGINNER" label="入门"/><el-option value="INTERMEDIATE" label="进阶"/><el-option value="ADVANCED" label="高级"/></el-select></el-form-item><el-form-item label="背景说明" class="wide"><el-input v-model="profile.backgroundText" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="学习经历、基础、近期困难……"/></el-form-item></el-form></template>
      <template v-if="active===1"><div class="panel-title"><div><h3>选择最适合你的节奏</h3><p>系统最多使用可用时间的 85%，保留生活缓冲。</p></div></div><el-form label-position="top" class="form-grid"><el-form-item label="内容形式"><el-checkbox-group v-model="pref.contentModes"><el-checkbox value="TEXT">文字</el-checkbox><el-checkbox value="VIDEO">视频</el-checkbox><el-checkbox value="PRACTICE">练习</el-checkbox><el-checkbox value="PROJECT">项目</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="引导方式"><el-radio-group v-model="pref.guidanceStyle"><el-radio-button value="SOCRATIC">苏格拉底式</el-radio-button><el-radio-button value="DIRECT">直接讲解</el-radio-button></el-radio-group></el-form-item><el-form-item label="专注时长"><el-slider v-model="pref.focusMinutes" :min="10" :max="120" show-input/></el-form-item><el-form-item label="容量比例"><el-slider v-model="pref.capacityRatio" :min=".5" :max=".85" :step=".05" show-input/></el-form-item></el-form></template>
      <template v-if="active===2"><div class="panel-title"><div><h3>每周可用时间</h3><p>跨午夜时段会自动拆分，重叠时段会被拒绝。</p></div><el-button @click="addSlot">添加时段</el-button></div><div v-for="(s,i) in slots" :key="i" class="slot-row"><el-select v-model="s.weekday"><el-option v-for="(d,n) in ['周一','周二','周三','周四','周五','周六','周日']" :key="n" :value="n+1" :label="d"/></el-select><el-time-select v-model="s.start" start="00:00" step="00:30" end="23:30"/><span>至</span><el-time-select v-model="s.end" start="00:00" step="00:30" end="23:30"/><el-select v-model="s.energyLevel"><el-option value="HIGH" label="高能量"/><el-option value="MEDIUM" label="中等"/><el-option value="LOW" label="低能量"/></el-select><el-button text type="danger" @click="slots.splice(i,1)">删除</el-button></div></template>
      <template v-if="active===3"><div class="review"><span class="review-icon">✓</span><h3>画像信息已保存</h3><p>下一步将把方向、偏好与时间约束固化为一个可追溯版本。以后每次更新都会保留历史。</p><div class="review-data"><span>方向 <b>{{ directions.find(x=>Number(x.id)===profile.directionId)?.name }}</b></span><span>专注周期 <b>{{ pref.focusMinutes }} 分钟</b></span><span>容量上限 <b>{{ Math.round(pref.capacityRatio*100) }}%</b></span><span>每周时段 <b>{{ slots.length }} 个</b></span></div></div></template>
      <div class="form-actions"><el-button v-if="active>0" @click="active--">上一步</el-button><el-button type="primary" :loading="loading" @click="next">{{ active===3?'生成学习画像':'保存并继续' }}</el-button></div>
    </section>
  </div>
</template>
<style scoped>.profile-wrap{max-width:1050px;margin:auto}.profile-panel{margin-top:20px;min-height:430px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px 24px}.wide{grid-column:1/-1}.form-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:25px}.slot-row{display:grid;grid-template-columns:130px 150px 24px 150px 140px 60px;gap:10px;align-items:center;margin-bottom:12px}.review{text-align:center;padding:30px 15%}.review-icon{display:grid;place-items:center;margin:auto;width:64px;height:64px;border-radius:50%;background:var(--mint);color:var(--green);font-size:28px}.review h3{font-size:25px}.review p{color:var(--muted);line-height:1.8}.review-data{display:grid;grid-template-columns:1fr 1fr;text-align:left;border:1px solid var(--line);border-radius:12px;margin-top:25px}.review-data span{padding:15px;color:var(--muted)}.review-data b{float:right;color:var(--ink)}@media(max-width:800px){.form-grid{grid-template-columns:1fr}.slot-row{grid-template-columns:1fr 1fr}.slot-row span{display:none}}</style>
