<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/http'
import type {
  BookInfoView,
  BookIntroView,
  BookLoginStatusView,
  BookProgressView,
  BookQrLoginView,
  BookShelfView,
  BookView,
  ReadDataDetailView,
  ReadDataMode,
} from '../types'

type Phase = 'LOADING' | 'UNLOGGED' | 'LOGGED'

const phase = ref<Phase>('LOADING')
const loginStatus = ref<BookLoginStatusView | null>(null)
const qr = ref<BookQrLoginView | null>(null)
const bookshelf = ref<BookShelfView | null>(null)
const loginMode = ref<'qr' | 'apikey'>('qr')
const apiKeyInput = ref('')
const keyword = ref('')
const categoryFilter = ref('')
const qrLoading = ref(false)
const apiKeyLoading = ref(false)
const shelfLoading = ref(false)
let qrTimer: number | undefined

const categories = computed(() =>
  [...new Set((bookshelf.value?.books || []).map((b) => b.category).filter(Boolean))] as string[],
)
const filteredBooks = computed(() => {
  let list = bookshelf.value?.books || []
  const kw = keyword.value.trim().toLowerCase()
  if (kw) list = list.filter((b) => b.title.toLowerCase().includes(kw) || (b.author || '').toLowerCase().includes(kw))
  if (categoryFilter.value) list = list.filter((b) => b.category === categoryFilter.value)
  return list
})
const accountMark = computed(() => (loginStatus.value?.nickname || '读').slice(0, 1).toUpperCase())
const qrHint = computed(() => {
  const status = qr.value?.status
  if (status === 'SCANNED') return '已扫码，请在手机上确认登录'
  if (status === 'EXPIRED') return '二维码已过期，点击重新获取'
  if (status === 'FAILED') return '登录失败，请重试'
  return qr.value?.message || '请用微信扫一扫登录'
})

function progressText(book: BookView): string {
  if (book.isFinished) return '已读完'
  if (book.status === 'reading') return `读到 ${book.lastReadChapter || Math.round(book.readingProgress * 100) + '%'}`
  return '未开始'
}

function isAuthError(error: unknown): boolean {
  const code = (error as any)?.response?.data?.error?.code as string | undefined
  return code === 'WEREAD_NOT_LOGGED_IN' || code === 'WEREAD_LOGIN_EXPIRED' || code === 'WEREAD_API_KEY_INVALID'
}

async function refreshStatus() {
  try {
    const status = await api<BookLoginStatusView>({ url: '/books/login-status', silent: true })
    loginStatus.value = status
    qr.value = status.loginQr
    if (status.loggedIn) {
      stopQrPolling()
      phase.value = 'LOGGED'
      await loadShelf(true)
      void loadRecommend()
      void loadReadData()
    } else if (status.loginQr && (status.loginQr.status === 'EXPIRED' || status.loginQr.status === 'FAILED')) {
      stopQrPolling()
    }
  } catch (error) {
    if (isAuthError(error)) {
      ElMessage.warning('微信读书登录已过期，请重新登录')
      phase.value = 'UNLOGGED'
    }
  }
}

function startQrPolling() {
  stopQrPolling()
  qrTimer = window.setInterval(() => { void refreshStatus() }, 2500)
}
function stopQrPolling() {
  if (qrTimer !== undefined) {
    clearInterval(qrTimer)
    qrTimer = undefined
  }
}

async function startQr() {
  qrLoading.value = true
  try {
    qr.value = await api<BookQrLoginView>({ method: 'POST', url: '/books/login-qrcode' })
    startQrPolling()
  } finally {
    qrLoading.value = false
  }
}

async function submitApiKey() {
  const key = apiKeyInput.value.trim()
  if (!key) {
    ElMessage.warning('请粘贴 wrk- 开头的 API Key')
    return
  }
  apiKeyLoading.value = true
  try {
    loginStatus.value = await api<BookLoginStatusView>({ method: 'POST', url: '/books/api-key', data: { apiKey: key } })
    phase.value = 'LOGGED'
    await loadShelf(true)
    void loadRecommend()
    void loadReadData()
  } catch (error) {
    if (!isAuthError(error)) throw error
  } finally {
    apiKeyLoading.value = false
  }
}

async function loadShelf(silent = false) {
  shelfLoading.value = true
  try {
    bookshelf.value = await api<BookShelfView>({ url: '/books/bookshelf', silent })
  } catch (error) {
    if (isAuthError(error)) {
      ElMessage.warning('微信读书登录已失效，请重新登录')
      stopQrPolling()
      phase.value = 'UNLOGGED'
      loginStatus.value = null
      bookshelf.value = null
    }
  } finally {
    shelfLoading.value = false
  }
}

async function disconnect() {
  try {
    await api({ method: 'POST', url: '/books/logout' })
  } catch { /* 断开失败不阻塞回到未登录态 */ }
  stopQrPolling()
  phase.value = 'UNLOGGED'
  loginStatus.value = null
  bookshelf.value = null
  qr.value = null
  keyword.value = ''
  categoryFilter.value = ''
  readData.value = null
  readDataError.value = ''
  recommendations.value = null
  recommendError.value = ''
  detailVisible.value = false
}

// ---- 阅读数据 / 为你推荐 / 书籍详情弹窗（均需 API Key，失败只做局部降级，不整页登出） ----
const readDataMode = ref<ReadDataMode>('overall')
const readData = ref<ReadDataDetailView | null>(null)
const readDataError = ref('')
const readDataLoading = ref(false)
const recommendations = ref<BookIntroView[] | null>(null)
const recommendError = ref('')
const recommendLoading = ref(false)

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailInfo = ref<BookInfoView | null>(null)
const detailProgress = ref<BookProgressView | null>(null)
const detailSimilar = ref<BookIntroView[] | null>(null)
const detailInfoError = ref('')
const detailProgressError = ref('')
const detailSimilarError = ref('')
let detailToken: symbol | undefined

function formatDuration(sec: number | null | undefined): string {
  if (!sec || sec <= 0) return '—'
  const days = Math.floor(sec / 86400)
  const hours = Math.floor(sec / 3600)
  if (days >= 365) return `${(days / 365).toFixed(1)} 年`
  if (days >= 1) return `${days} 天 ${hours % 24} 小时`
  if (hours >= 1) return `${hours} 小时 ${Math.floor((sec % 3600) / 60)} 分`
  return `${Math.floor(sec / 60)} 分钟`
}

function sectionError(error: unknown): string {
  const code = (error as any)?.response?.data?.error?.code as string | undefined
  if (code === 'WEREAD_NOT_LOGGED_IN' || code === 'WEREAD_LOGIN_EXPIRED') return '该功能需要 API Key 登录'
  return '加载失败，请稍后重试'
}

async function loadReadData() {
  readDataLoading.value = true
  readDataError.value = ''
  try {
    readData.value = await api<ReadDataDetailView>({
      url: '/books/readdata-detail',
      params: { mode: readDataMode.value },
      silent: true,
    })
  } catch (error) {
    readData.value = null
    readDataError.value = sectionError(error)
  } finally {
    readDataLoading.value = false
  }
}

async function loadRecommend() {
  recommendLoading.value = true
  recommendError.value = ''
  try {
    recommendations.value = await api<BookIntroView[]>({
      url: '/books/recommend',
      params: { count: 12 },
      silent: true,
    })
  } catch (error) {
    recommendations.value = null
    recommendError.value = sectionError(error)
  } finally {
    recommendLoading.value = false
  }
}

async function openDetail(bookId: string) {
  const token = Symbol(bookId)
  detailToken = token
  detailVisible.value = true
  detailLoading.value = true
  detailInfo.value = null
  detailProgress.value = null
  detailSimilar.value = null
  detailInfoError.value = ''
  detailProgressError.value = ''
  detailSimilarError.value = ''
  const [info, progress, similar] = await Promise.allSettled([
    api<BookInfoView>({ url: '/books/info', params: { bookId }, silent: true }),
    api<BookProgressView>({ url: '/books/getprogress', params: { bookId }, silent: true }),
    api<BookIntroView[]>({ url: '/books/similar', params: { bookId, count: 8 }, silent: true }),
  ])
  if (detailToken !== token) return // 已切换到另一本书，丢弃过期结果
  if (info.status === 'fulfilled') detailInfo.value = info.value
  else detailInfoError.value = sectionError(info.reason)
  if (progress.status === 'fulfilled') detailProgress.value = progress.value
  else detailProgressError.value = sectionError(progress.reason)
  if (similar.status === 'fulfilled') detailSimilar.value = similar.value
  else detailSimilarError.value = sectionError(similar.reason)
  detailLoading.value = false
}

onMounted(async () => {
  await refreshStatus()
  if (!loginStatus.value?.loggedIn) phase.value = 'UNLOGGED'
})
onUnmounted(stopQrPolling)
</script>

<template>
  <div class="books-view">
    <div v-if="phase === 'LOADING'" v-loading="true" class="books-loading" />

    <div v-else-if="phase === 'UNLOGGED'" class="books-login">
      <div class="books-login-intro">
        <span class="eyebrow">WEREAD BOOKSHELF</span>
        <h3>连接你的微信读书</h3>
        <p>绑定后即可在知识库看到你的微信读书书架：书名、作者、分类与阅读进度。支持扫码登录或官方 API Key。</p>
      </div>
      <el-segmented v-model="loginMode" :options="[{ label: '扫码登录', value: 'qr' }, { label: 'API Key', value: 'apikey' }]" />
      <div class="login-panel">
        <template v-if="loginMode === 'qr'">
          <div class="qr-box">
            <img v-if="qr?.qrBase64" :src="qr.qrBase64" class="qr-img" alt="微信读书登录二维码" />
            <div v-else class="qr-placeholder" @click="startQr">
              <span>点击获取二维码</span>
            </div>
            <p class="qr-tip">{{ qrHint }}</p>
          </div>
          <el-button type="primary" :loading="qrLoading" @click="startQr">获取登录二维码</el-button>
        </template>
        <template v-else>
          <el-input v-model="apiKeyInput" placeholder="粘贴 wrk- 开头的 API Key" clearable show-password @keyup.enter="submitApiKey" />
          <p class="apikey-hint">
            微信读书网页版 → 官方 Skill 页（weread.qq.com/r/weread-skills）扫码登录 → 复制「wrk-」开头的 Key。
            密钥仅保存在本机 weread-mcp 服务，不会写入知识库。
          </p>
          <el-button type="primary" :loading="apiKeyLoading" @click="submitApiKey">连接</el-button>
        </template>
      </div>
    </div>

    <div v-else class="books-logged">
      <div class="books-toolbar">
        <div class="books-account">
          <img v-if="loginStatus?.headImgUrl" :src="loginStatus.headImgUrl" class="account-avatar" alt="" />
          <span v-else class="account-mark">{{ accountMark }}</span>
          <b>{{ loginStatus?.nickname || '微信读书用户' }}</b>
          <el-tag size="small" effect="plain">{{ loginStatus?.loginType === 'API_KEY' ? 'API Key' : '扫码' }}</el-tag>
        </div>
        <div class="books-actions">
          <el-input v-model="keyword" clearable placeholder="搜索书名 / 作者" style="width: 190px" />
          <el-select v-model="categoryFilter" clearable placeholder="按分类筛选" style="width: 150px">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
          <el-button :loading="shelfLoading" @click="loadShelf()">同步书架</el-button>
          <el-button type="danger" plain @click="disconnect">断开连接</el-button>
        </div>
      </div>
      <div class="books-stats">
        <span>共 <b>{{ bookshelf?.total ?? 0 }}</b> 本</span>
        <span>在读 <b>{{ bookshelf?.readingCount ?? 0 }}</b></span>
        <span>读完 <b>{{ bookshelf?.finishedCount ?? 0 }}</b></span>
      </div>

      <div class="readdata-card">
        <div class="readdata-head">
          <b>📊 我的阅读数据</b>
          <el-segmented
            v-model="readDataMode"
            :options="[
              { label: '周', value: 'weekly' },
              { label: '月', value: 'monthly' },
              { label: '年', value: 'annually' },
              { label: '总', value: 'overall' },
            ]"
            size="small"
            @change="loadReadData"
          />
        </div>
        <div v-if="readDataLoading" v-loading="true" class="readdata-loading" />
        <p v-else-if="readDataError" class="readdata-hint">{{ readDataError }}</p>
        <template v-else-if="readData">
          <div class="readdata-stats">
            <div class="rstat"><small>总阅读时长</small><b>{{ formatDuration(readData.totalReadTime) }}</b></div>
            <div class="rstat"><small>听书时长</small><b>{{ formatDuration(readData.wrListenTime) }}</b></div>
            <div class="rstat"><small>阅读天数</small><b>{{ readData.readDays ?? '—' }} 天</b></div>
            <div class="rstat"><small>坚持指数</small><b>{{ readData.readRate ?? '—' }}%</b></div>
          </div>
          <div class="readdata-chips">
            <el-tag v-if="readData.preferCategoryWord" size="small" effect="plain">{{ readData.preferCategoryWord }}</el-tag>
            <el-tag v-if="readData.preferTimeWord" size="small" type="warning" effect="plain">{{ readData.preferTimeWord }}</el-tag>
            <el-tag v-if="readData.medals?.length" size="small" type="success" effect="plain">{{ readData.medals.length }} 枚勋章</el-tag>
          </div>
          <div v-if="readData.preferBooks?.length" class="readdata-prefer">
            <small>最爱：</small>
            <el-tag v-for="(p, i) in readData.preferBooks.slice(0, 8)" :key="i" size="small" type="info" effect="plain">{{ p.title }}</el-tag>
          </div>
        </template>
      </div>

      <div v-if="recommendations?.length || recommendError" class="recommend-card">
        <div class="recommend-head"><b>✨ 为你推荐</b></div>
        <p v-if="recommendError" class="readdata-hint">{{ recommendError }}</p>
        <div v-else-if="recommendLoading" v-loading="true" class="readdata-loading" />
        <div v-else class="recommend-row">
          <div v-for="b in recommendations" :key="b.bookId" class="recommend-book" @click="openDetail(b.bookId)">
            <img v-if="b.coverUrl" :src="b.coverUrl" :alt="b.title || ''" loading="lazy" />
            <span v-else class="recommend-fallback">{{ (b.title || '书').slice(0, 1) }}</span>
            <small>{{ b.title }}</small>
            <em>{{ b.price ? '¥' + b.price : '' }}</em>
          </div>
        </div>
      </div>

      <div v-if="filteredBooks.length" class="books-grid">
        <article
          v-for="book in filteredBooks"
          :key="book.bookId"
          class="book-card"
          @click="openDetail(book.bookId)"
        >
          <div class="book-cover" :class="{ finished: book.isFinished }">
            <img v-if="book.coverUrl" :src="book.coverUrl" :alt="book.title" loading="lazy" />
            <span v-else class="book-cover-fallback">{{ book.title.slice(0, 1) }}</span>
            <em v-if="book.isFinished" class="book-finished-badge">已读完</em>
          </div>
          <div class="book-meta">
            <b :title="book.title">{{ book.title }}</b>
            <small>{{ book.author || '佚名' }}</small>
            <div class="book-tags">
              <el-tag v-if="book.category" size="small" effect="plain">{{ book.category }}</el-tag>
              <el-tag v-if="book.format" size="small" type="info" effect="plain">{{ book.format }}</el-tag>
            </div>
            <el-progress
              v-if="book.status === 'reading'"
              :percentage="Math.round(book.readingProgress * 100)"
              :stroke-width="4"
              :show-text="false"
              class="book-progress"
            />
            <small class="book-progress-text">{{ progressText(book) }}</small>
            <a
              v-if="book.deepLink"
              :href="book.deepLink"
              target="_blank"
              rel="noopener"
              class="book-read-link"
              @click.stop
            >
              在线阅读 ↗
            </a>
          </div>
        </article>
      </div>
      <el-empty v-else description="书架为空，去微信读书添加几本吧" :image-size="80" />
    </div>
  </div>

  <el-dialog v-model="detailVisible" title="书籍详情" width="720px" class="book-detail-dialog">
    <div v-if="detailLoading" v-loading="true" class="detail-loading" />
    <div v-else class="book-detail">
      <div class="detail-cover">
        <img v-if="detailInfo?.coverUrl" :src="detailInfo.coverUrl" :alt="detailInfo?.title || ''" />
        <span v-else class="detail-cover-fallback">{{ (detailInfo?.title || '书').slice(0, 1) }}</span>
      </div>
      <div class="detail-body">
        <template v-if="detailInfo">
          <h3>{{ detailInfo.title }}</h3>
          <p class="detail-author">
            {{ detailInfo.author || '佚名' }}
            <el-tag v-if="detailInfo.category" size="small" effect="plain" class="detail-cat">{{ detailInfo.category }}</el-tag>
          </p>
          <div v-if="detailProgress" class="detail-progress">
            <el-progress :percentage="detailProgress.progressPercent ?? 0" :stroke-width="6" />
            <small>
              读到第 {{ detailProgress.chapterIdx ?? '?' }} 章 · 已读 {{ detailProgress.progressPercent ?? 0 }}%
              · 累计阅读 {{ formatDuration(detailProgress.readingTime) }}
            </small>
          </div>
          <p v-else-if="detailProgressError" class="detail-hint">{{ detailProgressError }}</p>
          <p v-if="detailInfo.intro" class="detail-intro">{{ detailInfo.intro }}</p>
          <dl class="detail-meta">
            <template v-if="detailInfo.publisher"><div><dt>出版社</dt><dd>{{ detailInfo.publisher }}</dd></div></template>
            <template v-if="detailInfo.publishTime"><div><dt>出版</dt><dd>{{ detailInfo.publishTime }}</dd></div></template>
            <template v-if="detailInfo.isbn"><div><dt>ISBN</dt><dd>{{ detailInfo.isbn }}</dd></div></template>
            <template v-if="detailInfo.wordCount"><div><dt>字数</dt><dd>{{ (detailInfo.wordCount / 10000).toFixed(1) }} 万</dd></div></template>
            <template v-if="detailInfo.translator"><div><dt>译者</dt><dd>{{ detailInfo.translator }}</dd></div></template>
            <template v-if="detailInfo.newRating && detailInfo.newRating > 0"><div><dt>评分</dt><dd>{{ detailInfo.newRating }} / 5</dd></div></template>
          </dl>
          <div v-if="detailSimilar?.length" class="detail-similar">
            <b>相似书籍</b>
            <div class="similar-row">
              <div v-for="b in detailSimilar" :key="b.bookId" class="similar-book" @click="openDetail(b.bookId)">
                <img v-if="b.coverUrl" :src="b.coverUrl" :alt="b.title || ''" loading="lazy" />
                <span v-else class="similar-fallback">{{ (b.title || '书').slice(0, 1) }}</span>
                <small>{{ b.title }}</small>
              </div>
            </div>
          </div>
          <p v-else-if="detailSimilarError" class="detail-hint">{{ detailSimilarError }}</p>
          <div class="detail-actions">
            <el-button type="primary" tag="a" :href="detailInfo.deepLink || '#'" target="_blank" rel="noopener">
              在线阅读 ↗
            </el-button>
          </div>
        </template>
        <p v-else-if="detailInfoError" class="detail-hint">{{ detailInfoError }}</p>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.books-view { min-height: 320px; }
.books-loading { min-height: 240px; }

.books-login {
  max-width: 460px;
  margin: 0 auto;
  padding: 28px 0 40px;
  display: grid;
  justify-items: center;
  gap: 18px;
}
.books-login-intro { text-align: center; }
.books-login-intro h3 { margin: 10px 0 8px; font: 500 24px/1.3 var(--display); color: var(--ink); }
.books-login-intro p { margin: 0 auto; max-width: 420px; color: var(--muted); font-size: 13px; line-height: 1.8; }
.login-panel { width: 100%; max-width: 360px; display: grid; justify-items: stretch; gap: 12px; }

.qr-box { text-align: center; }
.qr-img { width: 200px; height: 200px; border: 1px solid var(--line); border-radius: 16px; padding: 8px; background: #fff; }
.qr-placeholder {
  width: 200px; height: 200px; margin: 0 auto;
  display: grid; place-items: center;
  border: 1px dashed var(--line); border-radius: 16px;
  color: var(--muted); font-size: 13px; cursor: pointer;
}
.qr-tip { margin: 10px 0 0; color: var(--muted); font-size: 12px; min-height: 18px; }
.apikey-hint { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }

.books-toolbar {
  display: flex; justify-content: space-between; align-items: center; gap: 14px; flex-wrap: wrap;
  padding: 14px 16px; margin-bottom: 14px;
  border: 1px solid var(--line); border-radius: 14px; background: var(--paper-solid);
}
.books-account { display: flex; align-items: center; gap: 10px; }
.account-avatar { width: 34px; height: 34px; border-radius: 50%; object-fit: cover; }
.account-mark {
  width: 34px; height: 34px; display: grid; place-items: center;
  border-radius: 50%; color: #f7f3e8; font-weight: 700;
  background: linear-gradient(145deg, #225e49, #0f372a);
}
.books-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }

.books-stats { display: flex; gap: 22px; margin-bottom: 16px; color: var(--muted); font-size: 13px; }
.books-stats b { color: var(--ink); }

.books-grid {
  display: grid; gap: 16px;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
}
.book-card {
  display: grid; grid-template-rows: auto 1fr;
  border: 1px solid var(--line); border-radius: 14px; overflow: hidden;
  background: var(--paper-solid);
  transition: transform .15s, box-shadow .15s;
}
.book-card:hover { transform: translateY(-2px); box-shadow: var(--lift-shadow); }
.book-cover { position: relative; aspect-ratio: 3 / 4; background: var(--mint); }
.book-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.book-cover.finished img { filter: grayscale(.85); opacity: .85; }
.book-cover-fallback {
  position: absolute; inset: 0; display: grid; place-items: center;
  font: 600 42px var(--display); color: rgba(23, 107, 80, .45);
}
.book-finished-badge {
  position: absolute; top: 8px; left: 8px; padding: 2px 8px;
  border-radius: 999px; background: rgba(23, 107, 80, .9); color: #fff;
  font-size: 10px; font-style: normal; font-weight: 600;
}
.book-meta { padding: 10px 12px 12px; display: grid; gap: 6px; align-content: start; }
.book-meta b { font-size: 13.5px; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.book-meta small { color: var(--muted); font-size: 11.5px; }
.book-tags { display: flex; gap: 4px; flex-wrap: wrap; min-height: 22px; }
.book-progress { margin-top: 2px; }
.book-progress-text { font-size: 11px; }
.book-read-link {
  justify-self: start;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--green);
  background: transparent;
  font-size: 11.5px;
  font-weight: 600;
  text-decoration: none;
  transition: color .15s, background .15s, border-color .15s;
}
.book-read-link:hover {
  color: var(--green-deep);
  background: var(--mint);
  border-color: var(--green);
}

.book-card { cursor: pointer; }

.readdata-card, .recommend-card {
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--paper-solid);
}
.readdata-head, .recommend-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin-bottom: 10px;
}
.readdata-head b, .recommend-head b { font-size: 13px; color: var(--ink); }
.readdata-loading { min-height: 56px; }
.readdata-hint { margin: 4px 0 0; color: var(--muted); font-size: 12px; }
.readdata-stats {
  display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px;
}
.rstat { display: grid; gap: 2px; }
.rstat small { color: var(--muted); font-size: 10.5px; }
.rstat b { color: var(--green); font-size: 16px; }
.readdata-chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 10px; }
.readdata-prefer { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.readdata-prefer small { color: var(--muted); font-size: 11px; }

.recommend-row {
  display: flex; gap: 12px; overflow-x: auto; padding-bottom: 4px;
}
.recommend-book {
  flex: 0 0 92px; display: grid; gap: 4px; cursor: pointer;
}
.recommend-book img, .recommend-fallback {
  width: 92px; height: 122px; object-fit: cover; border-radius: 10px; background: var(--mint);
}
.recommend-fallback {
  display: grid; place-items: center; color: rgba(23, 107, 80, .45);
  font: 600 30px var(--display);
}
.recommend-book small {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--ink); font-size: 11px;
}
.recommend-book em {
  color: var(--gold); font-size: 10px; font-style: normal; font-weight: 700;
}

.detail-loading { min-height: 220px; }
.book-detail { display: grid; grid-template-columns: 180px 1fr; gap: 20px; }
.detail-cover { aspect-ratio: 3 / 4; border-radius: 12px; overflow: hidden; background: var(--mint); }
.detail-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.detail-cover-fallback {
  width: 100%; height: 100%; display: grid; place-items: center;
  color: rgba(23, 107, 80, .45); font: 600 46px var(--display);
}
.detail-body { min-width: 0; }
.detail-body h3 { margin: 0 0 4px; font: 500 22px/1.4 var(--display); color: var(--ink); }
.detail-author { margin: 0 0 10px; color: var(--muted); font-size: 12.5px; display: flex; align-items: center; gap: 8px; }
.detail-progress { margin: 10px 0; }
.detail-progress small { color: var(--muted); font-size: 11px; }
.detail-hint { color: var(--muted); font-size: 12px; }
.detail-intro { margin: 8px 0 12px; color: #405048; font-size: 12.5px; line-height: 1.8; }
.detail-meta { margin: 0 0 12px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 16px; }
.detail-meta div { display: flex; gap: 8px; font-size: 11.5px; }
.detail-meta dt { color: var(--muted); flex: 0 0 42px; }
.detail-meta dd { margin: 0; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detail-similar { margin-top: 6px; }
.detail-similar > b { display: block; margin-bottom: 8px; color: var(--ink); font-size: 12px; }
.similar-row { display: flex; gap: 10px; overflow-x: auto; padding-bottom: 4px; }
.similar-book { flex: 0 0 64px; display: grid; gap: 3px; cursor: pointer; }
.similar-book img, .similar-fallback {
  width: 64px; height: 84px; object-fit: cover; border-radius: 8px; background: var(--mint);
}
.similar-fallback {
  display: grid; place-items: center; color: rgba(23, 107, 80, .45);
  font: 600 22px var(--display);
}
.similar-book small {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink); font-size: 10px;
}
.detail-actions { margin-top: 14px; }

@media (max-width: 640px) {
  .book-detail { grid-template-columns: 1fr; }
  .detail-cover { width: 120px; aspect-ratio: 3 / 4; }
  .readdata-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 700px) {
  .books-toolbar { flex-direction: column; align-items: stretch; }
  .books-actions { justify-content: flex-start; }
}
</style>
