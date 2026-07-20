<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore(); const route = useRoute(); const router = useRouter(); const mode = ref<'login'|'register'>('login'); const loading = ref(false)
const form = reactive({ login: '', username: '', email: '', password: '' })
const errorMessage = ref('')

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  errorMessage.value = ''
}

function authErrorMessage(error: unknown) {
  const response = (error as { response?: { status?: number; data?: { error?: { message?: string } } } })?.response
  if (!response) return '暂时无法连接服务器，请稍后重试'
  return response.data?.error?.message
    || (mode.value === 'login' && response.status === 401 ? '账号或密码错误' : '操作失败，请检查填写内容后重试')
}

async function submit() {
  if (loading.value) return
  errorMessage.value = ''
  if (mode.value === 'login' && (!form.login.trim() || !form.password)) {
    errorMessage.value = '请输入用户名（或邮箱）和密码'
    return
  }
  if (mode.value === 'register' && (!form.username.trim() || !form.email.trim() || !form.password)) {
    errorMessage.value = '请完整填写用户名、邮箱和密码'
    return
  }
  loading.value = true
  try {
    if (mode.value === 'login') await auth.login(form.login, form.password)
    else await auth.register(form.username, form.email, form.password)
    await router.replace(String(route.query.redirect || (mode.value === 'register' ? '/onboarding' : '/dashboard')))
  } catch (error) {
    errorMessage.value = authErrorMessage(error)
  } finally { loading.value = false }
}
</script>

<template>
  <div class="auth-page">
    <section class="auth-story">
      <div class="story-nav"><span class="brand-mark">序</span><b>知序</b></div>
      <div class="story-copy"><span class="eyebrow light">AI ADAPTIVE LEARNING</span><h1>让每一次学习，<br />都有清晰的下一步。</h1><p>目标、计划、知识与反馈形成闭环。Agent 提出方案，你始终掌握最终决定权。</p></div>
      <div class="story-proof"><span>01 学习画像</span><span>02 动态计划</span><span>03 可解释反馈</span></div>
    </section>
    <section class="auth-form">
      <div class="auth-card">
        <span class="eyebrow">WELCOME</span><h2>{{ mode === 'login' ? '继续你的学习旅程' : '创建学习账户' }}</h2><p>{{ mode === 'login' ? '登录后查看今天的最佳行动。' : '只需一分钟，之后我们将生成你的学习画像。' }}</p>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="auth-error" />
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item v-if="mode === 'login'" label="用户名或邮箱"><el-input v-model="form.login" size="large" autocomplete="username" /></el-form-item>
          <template v-else><el-form-item label="用户名"><el-input v-model="form.username" size="large" /></el-form-item><el-form-item label="邮箱"><el-input v-model="form.email" type="email" size="large" /></el-form-item></template>
          <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password size="large" autocomplete="current-password" @keyup.enter="submit" /></el-form-item>
          <el-button type="primary" native-type="button" size="large" :loading="loading" class="full" @click="submit">{{ mode === 'login' ? '登录' : '注册并开始' }}</el-button>
        </el-form>
        <div class="switch-mode">{{ mode === 'login' ? '还没有账户？' : '已经有账户？' }} <button @click="switchMode">{{ mode === 'login' ? '立即注册' : '返回登录' }}</button></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.auth-error { margin: 18px 0; }
</style>
