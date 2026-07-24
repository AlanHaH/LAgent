<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { EmailCodeDelivery, UserView } from '../types'

const auth = useAuthStore()
const account = reactive({ email: '', emailVerificationCode: '', timezone: 'Asia/Shanghai', version: 0, emailVerified: false })
const originalEmail = ref('')
const prefs = reactive<Record<string, boolean>>({ TASK_DUE: true, TASK_OVERDUE: true, REPORT: true, DOCUMENT: true })
const prefVersion = ref(0)
const loading = ref(false)
const codeSending = ref(false)
const countdown = ref(0)
const notifications = ref<any[]>([])
let countdownTimer: number | undefined
const emailChanged = computed(() => account.email.trim().toLowerCase() !== originalEmail.value.toLowerCase())

onMounted(async () => {
  const [user, preference, notificationPage] = await Promise.all([
    api<UserView>({ url: '/users/me' }),
    api<any>({ url: '/notification-preferences' }),
    api<any>({ url: '/notifications', params: { pageSize: 10 } }),
  ])
  Object.assign(account, user)
  originalEmail.value = user.email
  Object.assign(prefs, preference.values)
  prefVersion.value = preference.version
  notifications.value = notificationPage.items
})

function startCountdown(seconds: number) {
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
  countdown.value = Math.max(1, seconds)
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && countdownTimer !== undefined) {
      window.clearInterval(countdownTimer)
      countdownTimer = undefined
    }
  }, 1000)
}

async function sendEmailCode() {
  if (codeSending.value || countdown.value > 0) return
  codeSending.value = true
  try {
    const delivery = await api<EmailCodeDelivery>({
      method: 'POST', url: '/users/me/email-verification-code', data: { email: account.email.trim() },
    })
    startCountdown(delivery.resendAfterSeconds)
    ElMessage.success('验证码已发送到新邮箱')
  } finally {
    codeSending.value = false
  }
}

async function save() {
  if (emailChanged.value && !/^\d{6}$/.test(account.emailVerificationCode)) {
    ElMessage.warning('更换邮箱需要输入 6 位验证码')
    return
  }
  loading.value = true
  try {
    const user = await api<UserView>({
      method: 'PATCH',
      url: '/users/me',
      data: {
        email: account.email.trim(),
        emailVerificationCode: emailChanged.value ? account.emailVerificationCode : undefined,
        timezone: account.timezone,
        version: account.version,
      },
    })
    Object.assign(account, user, { emailVerificationCode: '' })
    originalEmail.value = user.email
    auth.user = user
    localStorage.setItem('user', JSON.stringify(user))
    const preference = await api<any>({
      method: 'PUT', url: '/notification-preferences', data: { values: prefs, version: prefVersion.value },
    })
    prefVersion.value = preference.version
    ElMessage.success('设置已保存')
  } finally {
    loading.value = false
  }
}

async function logoutAll() {
  await ElMessageBox.confirm('这会撤销所有设备上的刷新令牌，当前设备也需要重新登录。', '退出所有设备', { type: 'warning' })
  await api({ method: 'POST', url: '/auth/logout-all' })
  localStorage.clear()
  location.assign('/login')
}

onBeforeUnmount(() => {
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
})
</script>

<template>
  <div class="settings">
    <div class="page-head">
      <div><h2>账户与提醒</h2><p>时区影响任务归属日、学习时长切分和所有统计区间。</p></div>
      <el-button type="primary" :loading="loading" @click="save">保存设置</el-button>
    </div>
    <div class="grid settings-grid">
      <section class="panel">
        <div class="panel-title">
          <div><h3>账户信息</h3><p>用户名不可修改，邮箱变更需要验证新地址</p></div>
          <el-tag v-if="account.emailVerified" type="success" effect="plain">邮箱已验证</el-tag>
        </div>
        <el-form label-position="top">
          <el-form-item label="邮箱"><el-input v-model="account.email" type="email" autocomplete="email" /></el-form-item>
          <el-form-item v-if="emailChanged" label="新邮箱验证码">
            <div class="email-code-row">
              <el-input v-model="account.emailVerificationCode" maxlength="6" inputmode="numeric" placeholder="6 位数字" />
              <el-button :loading="codeSending" :disabled="countdown > 0" @click="sendEmailCode">
                {{ countdown > 0 ? `${countdown} 秒后重发` : '发送验证码' }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="IANA 时区"><el-input v-model="account.timezone" placeholder="Asia/Shanghai" /></el-form-item>
        </el-form>
        <el-divider />
        <div class="security">
          <div><b>退出所有设备</b><p>安全事件发生时撤销全部会话。</p></div>
          <el-button type="danger" plain @click="logoutAll">全部退出</el-button>
        </div>
      </section>
      <section class="panel">
        <div class="panel-title"><div><h3>通知偏好</h3><p>安全通知不会被关闭</p></div></div>
        <div class="pref-row"><span>任务即将到期</span><el-switch v-model="prefs.TASK_DUE" /></div>
        <div class="pref-row"><span>任务已经逾期</span><el-switch v-model="prefs.TASK_OVERDUE" /></div>
        <div class="pref-row"><span>学习报告完成</span><el-switch v-model="prefs.REPORT" /></div>
        <div class="pref-row"><span>文档处理完成</span><el-switch v-model="prefs.DOCUMENT" /></div>
      </section>
    </div>
    <section class="panel recent">
      <div class="panel-title"><div><h3>最近通知</h3><p>相同业务事件使用去重键，避免重复提醒。</p></div></div>
      <div v-if="!notifications.length" class="empty">暂无通知</div>
      <div v-for="notification in notifications" :key="notification.publicId" class="notification">
        <span :class="{ unread: !notification.readAt }" />
        <div><b>{{ notification.title }}</b><p>{{ notification.content }}</p></div>
        <small>{{ notification.createdAt }}</small>
      </div>
    </section>
  </div>
</template>

<style scoped>
.settings { max-width: 1050px; margin: auto; }
.settings-grid { grid-template-columns: 1fr 1fr; }
.pref-row, .security, .notification { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--line); padding: 15px 0; }
.security p, .notification p { margin: 4px 0; color: var(--muted); font-size: 11px; }
.recent { margin-top: 18px; }
.notification { display: grid; grid-template-columns: 8px 1fr 180px; gap: 10px; }
.notification > span { width: 7px; height: 7px; border-radius: 50%; background: #c5cbc6; }
.notification > span.unread { background: var(--green); }
.notification small { color: var(--muted); }
.email-code-row { display: grid; grid-template-columns: 1fr auto; gap: 10px; width: 100%; }
@media (max-width: 800px) { .settings-grid { grid-template-columns: 1fr; } }
@media (max-width: 420px) { .email-code-row { grid-template-columns: 1fr; } }
</style>
