// 黑夜模式：主题应用与切换（class 挂在 <html> 上，样式在 styles.css 的 html.dark 覆盖块）
import { ref } from 'vue'

export type Theme = 'light' | 'dark'

/** 响应式主题状态：脚本内需要随主题变化的代码（如 echarts 颜色）依赖它 */
export const isDark = ref(typeof document !== 'undefined' && document.documentElement.classList.contains('dark'))

const STORAGE_KEY = 'theme'

export function themeFromStorage(): Theme {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'light' || saved === 'dark') return saved
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

export function currentTheme(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  isDark.value = theme === 'dark'
  const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (meta) meta.content = theme === 'dark' ? '#0e1713' : '#f4f1ea'
}

export function toggleTheme(): Theme {
  const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark'
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch { /* localStorage 不可用时仅本次生效 */ }
  applyTheme(next)
  return next
}
