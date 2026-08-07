<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

type AuthMode = 'login' | 'register' | 'forgot'
type FormField = 'login' | 'username' | 'email' | 'password' | 'confirmPassword' | 'verificationCode'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mode = ref<AuthMode>('login')
const loading = ref(false)
const codeSending = ref(false)
const countdown = ref(0)
const form = reactive({ login: '', username: '', email: '', password: '', confirmPassword: '', verificationCode: '' })
const fieldErrors = reactive<Record<FormField, string>>({
  login: '', username: '', email: '', password: '', confirmPassword: '', verificationCode: '',
})
const errorMessage = ref('')
const successMessage = ref('')
let countdownTimer: number | undefined

function switchMode(next: AuthMode) {
  mode.value = next
  errorMessage.value = ''
  successMessage.value = ''
  form.password = ''
  form.confirmPassword = ''
  form.verificationCode = ''
  clearFieldErrors()
  stopCountdown()
}

function clearFieldError(field: FormField) {
  fieldErrors[field] = ''
}

function clearFieldErrors() {
  for (const field of Object.keys(fieldErrors) as FormField[]) fieldErrors[field] = ''
}

function stopCountdown() {
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer)
  countdownTimer = undefined
  countdown.value = 0
}

function startCountdown(seconds: number) {
  stopCountdown()
  countdown.value = Math.max(1, seconds)
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopCountdown()
  }, 1000)
}

function authErrorMessage(error: unknown) {
  const response = (error as { response?: { status?: number; data?: { error?: {
    code?: string
    message?: string
    fieldErrors?: Array<{ field: string; message: string }>
  } } } })?.response
  if (!response) return '暂时无法连接服务器，请稍后重试'
  const serverFieldErrors = response.data?.error?.fieldErrors || []
  const fieldMap: Record<string, FormField> = { newPassword: 'password' }
  for (const item of serverFieldErrors) {
    const target = fieldMap[item.field] || item.field as FormField
    if (target in fieldErrors) fieldErrors[target] = item.message
  }
  if (serverFieldErrors.length) return '请检查表单中标红的字段'
  return response.data?.error?.message
    || (mode.value === 'login' && response.status === 401 ? '账号或密码错误' : '操作失败，请检查填写内容后重试')
}

function validEmail(email: string) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

async function sendCode() {
  if (codeSending.value || countdown.value > 0) return
  errorMessage.value = ''
  successMessage.value = ''
  clearFieldError('email')
  const email = form.email.trim()
  if (!validEmail(email)) {
    fieldErrors.email = '请输入有效的邮箱地址，例如 name@qq.com'
    errorMessage.value = '请先正确填写邮箱地址'
    return
  }
  codeSending.value = true
  try {
    const delivery = await auth.sendEmailCode(email, mode.value === 'register' ? 'REGISTER' : 'PASSWORD_RESET')
    startCountdown(delivery.resendAfterSeconds)
    successMessage.value = mode.value === 'forgot'
      ? '如果该邮箱已注册，验证码邮件会在几分钟内送达'
      : `验证码已发送，${Math.ceil(delivery.expiresInSeconds / 60)} 分钟内有效`
  } catch (error) {
    errorMessage.value = authErrorMessage(error)
  } finally {
    codeSending.value = false
  }
}

async function submit() {
  if (loading.value) return
  errorMessage.value = ''
  successMessage.value = ''
  clearFieldErrors()
  if (!validateForm()) return
  loading.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(form.login.trim(), form.password)
      const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
        ? route.query.redirect
        : auth.isAdmin ? '/admin' : '/dashboard'
      await router.replace(redirect)
    } else if (mode.value === 'register') {
      await auth.register(form.username.trim(), form.email.trim(), form.password, form.verificationCode)
      await router.replace('/onboarding')
    } else {
      await auth.resetPassword(form.email.trim(), form.verificationCode, form.password)
      form.login = form.email.trim()
      switchMode('login')
      successMessage.value = '密码已重置，请使用新密码登录'
    }
  } catch (error) {
    errorMessage.value = authErrorMessage(error)
  } finally {
    loading.value = false
  }
}

function validateForm() {
  if (mode.value === 'login') {
    if (!form.login.trim()) fieldErrors.login = '请输入用户名或邮箱'
    if (!form.password) fieldErrors.password = '请输入密码'
  } else {
    if (mode.value === 'register') {
      const username = form.username.trim()
      if (!username) fieldErrors.username = '请输入用户名'
      else if (username.length < 3 || username.length > 50) fieldErrors.username = '用户名长度需为 3～50 个字符'
      else if (!/^[A-Za-z0-9_]+$/.test(username)) fieldErrors.username = '用户名只能包含英文字母、数字和下划线，不能填写完整邮箱'
    }
    if (!form.email.trim()) fieldErrors.email = '请输入邮箱地址'
    else if (!validEmail(form.email.trim())) fieldErrors.email = '邮箱格式不正确，例如 name@qq.com'
    if (!/^\d{6}$/.test(form.verificationCode)) fieldErrors.verificationCode = '请输入邮件中的 6 位数字验证码'
    if (!form.password) fieldErrors.password = mode.value === 'forgot' ? '请输入新密码' : '请输入密码'
    else if (form.password.length < 8 || form.password.length > 128) fieldErrors.password = '密码长度需为 8～128 个字符'
    if (!form.confirmPassword) fieldErrors.confirmPassword = '请再次输入密码'
    else if (form.password !== form.confirmPassword) fieldErrors.confirmPassword = '两次输入的密码不一致'
  }
  if (Object.values(fieldErrors).some(Boolean)) {
    errorMessage.value = '请检查表单中标红的字段'
    return false
  }
  return true
}

onBeforeUnmount(stopCountdown)
onMounted(() => {
  if (route.query.mode === 'register') switchMode('register')
})
</script>

<template>
  <div class="auth-page">
    <section class="auth-story">
      <RouterLink class="story-nav story-home-link" to="/" aria-label="返回知序首页"><span class="brand-mark">序</span><b>知序</b></RouterLink>
      <div class="story-copy"><span class="eyebrow light">AI ADAPTIVE LEARNING</span><h1>让每一次学习，<br />都有清晰的下一步。</h1><p>目标、计划、知识与反馈形成闭环。Agent 提出方案，你始终掌握最终决定权。</p></div>
      <div class="story-proof"><span>01 学习画像</span><span>02 动态计划</span><span>03 可解释反馈</span></div>
    </section>
    <section class="auth-form">
      <div class="auth-card">
        <RouterLink class="back-home" to="/">← 返回产品首页</RouterLink>
        <span class="eyebrow">WELCOME</span>
        <h2>{{ mode === 'login' ? '继续你的学习旅程' : mode === 'register' ? '创建学习账户' : '找回你的账户' }}</h2>
        <p>{{ mode === 'login' ? '登录后查看今天的最佳行动。' : mode === 'register' ? '验证邮箱后，我们将生成你的学习画像。' : '通过注册邮箱验证身份并设置新密码。' }}</p>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="auth-error" />
        <el-alert v-if="successMessage" :title="successMessage" type="success" show-icon :closable="false" class="auth-error" />
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item v-if="mode === 'login'" label="用户名或邮箱" :error="fieldErrors.login">
            <el-input v-model="form.login" size="large" autocomplete="username" placeholder="用户名或注册邮箱" @input="clearFieldError('login')" />
          </el-form-item>
          <el-form-item v-if="mode === 'register'" label="用户名" :error="fieldErrors.username">
            <el-input v-model="form.username" size="large" autocomplete="username" maxlength="50" placeholder="例如 alan_2026" @input="clearFieldError('username')" />
            <small class="field-hint">3～50 位，只能使用英文字母、数字和下划线；邮箱请填在下一项</small>
          </el-form-item>
          <template v-if="mode !== 'login'">
            <el-form-item label="邮箱" :error="fieldErrors.email">
              <el-input v-model="form.email" type="email" size="large" autocomplete="email" placeholder="例如 name@example.com" @input="clearFieldError('email')" />
            </el-form-item>
            <el-form-item label="邮箱验证码" :error="fieldErrors.verificationCode">
              <div class="verification-row">
                <el-input v-model="form.verificationCode" size="large" inputmode="numeric" maxlength="6" placeholder="邮件中的 6 位数字" @input="clearFieldError('verificationCode')" />
                <el-button size="large" :loading="codeSending" :disabled="countdown > 0" @click="sendCode">
                  {{ countdown > 0 ? `${countdown} 秒后重发` : '发送验证码' }}
                </el-button>
              </div>
            </el-form-item>
          </template>
          <el-form-item :label="mode === 'forgot' ? '新密码' : '密码'" :error="fieldErrors.password">
            <el-input v-model="form.password" type="password" show-password size="large" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" :placeholder="mode === 'login' ? '请输入密码' : '8～128 个字符'" @input="clearFieldError('password')" />
            <small v-if="mode !== 'login'" class="field-hint">密码长度为 8～128 个字符</small>
          </el-form-item>
          <el-form-item v-if="mode !== 'login'" label="确认密码" :error="fieldErrors.confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" show-password size="large" autocomplete="new-password" placeholder="再次输入相同密码" @input="clearFieldError('confirmPassword')" />
          </el-form-item>
          <div v-if="mode === 'login'" class="auth-actions">
            <button type="button" @click="switchMode('forgot')">忘记密码？</button>
          </div>
          <el-button type="primary" native-type="submit" size="large" :loading="loading" class="full">
            {{ mode === 'login' ? '登录' : mode === 'register' ? '验证并注册' : '重置密码' }}
          </el-button>
        </el-form>
        <div class="switch-mode">
          {{ mode === 'login' ? '还没有账户？' : '已经有账户？' }}
          <button type="button" @click="switchMode(mode === 'login' ? 'register' : 'login')">{{ mode === 'login' ? '立即注册' : '返回登录' }}</button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.auth-error { margin: 18px 0; }
.story-home-link { color: inherit; text-decoration: none; }
.back-home { display: inline-block; margin-bottom: 24px; color: var(--muted); text-decoration: none; font-size: 12px; }
.back-home:hover { color: var(--green); }
.field-hint { display: block; margin-top: 6px; color: var(--muted); font-size: 11px; line-height: 1.5; }
.verification-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; width: 100%; }
.verification-row .el-button { min-width: 124px; }
.auth-actions { display: flex; justify-content: flex-end; margin: -8px 0 16px; }
.auth-actions button { border: 0; color: var(--green); background: transparent; cursor: pointer; font-size: 13px; font-weight: 600; }
@media (max-width: 420px) {
  .verification-row { grid-template-columns: 1fr; }
  .verification-row .el-button { width: 100%; }
}
</style>
