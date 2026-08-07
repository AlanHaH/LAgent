import path from 'node:path'
import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { customActions } from './custom-actions'
import { locatorFor } from './locator'
import type { BusinessScenario, Condition, RuntimeContext, ScenarioStep } from './types'

const templatePattern = /\$\{([A-Z0-9_]+)\}/gi

function resolveString(value: string, context: RuntimeContext) {
  return value.replace(templatePattern, (_match, key: string) => {
    const resolved = context.variables[key] ?? process.env[key]
    if (resolved === undefined || resolved === '') {
      throw new Error(`缺少测试变量 ${key}，请在 .env、系统环境变量或场景 variables 中配置`)
    }
    return String(resolved)
  })
}

function resolveValue<T>(value: T, context: RuntimeContext): T {
  if (typeof value === 'string') return resolveString(value, context) as T
  if (Array.isArray(value)) return value.map((item) => resolveValue(item, context)) as T
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, resolveValue(item, context)]),
    ) as T
  }
  return value
}

function valueAtJsonPath(payload: unknown, jsonPath: string) {
  const normalized = jsonPath.replace(/^\$\.?/, '')
  if (!normalized) return payload
  return normalized.split('.').reduce<unknown>((current, segment) => {
    if (current === null || current === undefined) return undefined
    const match = /^(.+?)\[(\d+)]$/.exec(segment)
    if (match) {
      const parent = (current as Record<string, unknown>)[match[1]]
      return Array.isArray(parent) ? parent[Number(match[2])] : undefined
    }
    return (current as Record<string, unknown>)[segment]
  }, payload)
}

async function conditionMatches(page: Page, condition: Condition) {
  const locator = locatorFor(page, condition.target)
  const timeout = condition.timeoutMs ?? 1_500
  const state = condition.state ?? 'visible'
  let matched = false
  try {
    if (state === 'visible') matched = await locator.isVisible({ timeout })
    else if (state === 'hidden') matched = await locator.isHidden({ timeout })
    else if (state === 'enabled') matched = await locator.isEnabled({ timeout })
    else matched = await locator.isDisabled({ timeout })
  } catch {
    matched = false
  }
  return condition.negate ? !matched : matched
}

function stepTitle(step: ScenarioStep, index: number) {
  if (step.name) return `${index + 1}. ${step.name}`
  const target = 'target' in step && step.target
    ? step.target.name || step.target.label || step.target.placeholder || step.target.text || step.target.css
    : undefined
  return `${index + 1}. ${step.action}${target ? ` · ${target}` : ''}`
}

function safeFilename(value: string) {
  return value.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '-').slice(0, 100)
}

async function browserApi(page: Page, step: Extract<ScenarioStep, { action: 'api' }>, context: RuntimeContext) {
  const pathValue = resolveString(step.path, context)
  const body = resolveValue(step.body, context)
  return page.evaluate(async ({ requestPath, method, requestBody }) => {
    const normalizedPath = requestPath.startsWith('/api/')
      ? requestPath
      : `/api/v1/${requestPath.replace(/^\//, '')}`
    const token = localStorage.getItem('access_token')
    const response = await fetch(normalizedPath, {
      method,
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Request-Id': crypto.randomUUID(),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: requestBody === undefined || method === 'GET' ? undefined : JSON.stringify(requestBody),
    })
    const contentType = response.headers.get('content-type') || ''
    const payload = contentType.includes('application/json')
      ? await response.json()
      : await response.text()
    return { status: response.status, payload }
  }, { requestPath: pathValue, method: step.method || 'GET', requestBody: body })
}

async function login(page: Page, step: Extract<ScenarioStep, { action: 'login' }>, context: RuntimeContext) {
  const loginValue = resolveString(step.login, context)
  const passwordValue = resolveString(step.password, context)
  const destination = step.destination ? resolveString(step.destination, context) : undefined

  if (step.mode === 'api') {
    await page.goto('/login')
    const result = await browserApi(page, {
      action: 'api',
      method: 'POST',
      path: '/auth/login',
      body: { login: loginValue, password: passwordValue, deviceId: 'visual-test' },
      expectedStatus: 200,
    }, context)
    expect(result.status, '登录接口状态').toBe(200)
    const data = (result.payload as { data?: Record<string, unknown> }).data
    if (!data?.accessToken || !data?.refreshToken || !data?.user) throw new Error('登录接口未返回完整令牌')
    await page.evaluate((tokens) => {
      localStorage.setItem('access_token', String(tokens.accessToken))
      localStorage.setItem('refresh_token', String(tokens.refreshToken))
      localStorage.setItem('user', JSON.stringify(tokens.user))
    }, data)
    await page.goto(destination || '/dashboard')
    return
  }

  await page.goto('/login')
  await page.getByPlaceholder('用户名或注册邮箱').fill(loginValue)
  await page.getByPlaceholder('请输入密码').fill(passwordValue)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL(destination ? `**${destination}**` : /\/(admin|dashboard|onboarding)/, { timeout: 20_000 })
}

async function executeStep(
  page: Page,
  step: ScenarioStep,
  context: RuntimeContext,
  testInfo: TestInfo,
) {
  switch (step.action) {
    case 'goto':
      await page.goto(resolveString(step.path, context), { waitUntil: step.waitUntil || 'domcontentloaded' })
      break
    case 'login':
      await login(page, step, context)
      break
    case 'click':
      await locatorFor(page, step.target).click()
      break
    case 'doubleClick':
      await locatorFor(page, step.target).dblclick()
      break
    case 'hover':
      await locatorFor(page, step.target).hover()
      break
    case 'check':
      await locatorFor(page, step.target).check()
      break
    case 'uncheck':
      await locatorFor(page, step.target).uncheck()
      break
    case 'fill':
      await locatorFor(page, step.target).fill(resolveString(step.value, context))
      break
    case 'type':
      await locatorFor(page, step.target).pressSequentially(resolveString(step.value, context))
      break
    case 'press':
      if (step.target) await locatorFor(page, step.target).press(step.key)
      else await page.keyboard.press(step.key)
      break
    case 'select':
      await locatorFor(page, step.target).selectOption(resolveValue(step.value, context))
      break
    case 'upload': {
      const files = resolveValue(step.files, context)
      const resolvedFiles = (Array.isArray(files) ? files : [files])
        .map((file) => path.resolve(process.cwd(), String(file)))
      await locatorFor(page, step.target).setInputFiles(resolvedFiles)
      break
    }
    case 'wait':
      await page.waitForTimeout(step.milliseconds)
      break
    case 'waitFor':
      await locatorFor(page, step.target).waitFor({
        state: step.state || 'visible',
        timeout: step.timeoutMs,
      })
      break
    case 'expect': {
      const assertion = step.soft ? expect.soft : expect
      const timeout = step.timeoutMs
      if (step.assertion === 'url') {
        await assertion(page).toHaveURL(new RegExp(String(resolveValue(step.value, context))), { timeout })
        break
      }
      if (!step.target) throw new Error(`${step.assertion} 断言需要 target`)
      const locator = locatorFor(page, step.target)
      if (step.assertion === 'visible') await assertion(locator).toBeVisible({ timeout })
      else if (step.assertion === 'hidden') await assertion(locator).toBeHidden({ timeout })
      else if (step.assertion === 'enabled') await assertion(locator).toBeEnabled({ timeout })
      else if (step.assertion === 'disabled') await assertion(locator).toBeDisabled({ timeout })
      else if (step.assertion === 'text') await assertion(locator).toHaveText(String(resolveValue(step.value, context)), { timeout })
      else if (step.assertion === 'containsText') await assertion(locator).toContainText(String(resolveValue(step.value, context)), { timeout })
      else if (step.assertion === 'value') await assertion(locator).toHaveValue(String(resolveValue(step.value, context)), { timeout })
      else if (step.assertion === 'count') await assertion(locator).toHaveCount(Number(step.value), { timeout })
      break
    }
    case 'api': {
      const result = await browserApi(page, step, context)
      expect(result.status, `${step.method || 'GET'} ${step.path}`).toBe(step.expectedStatus ?? 200)
      if (step.save) {
        const stored = valueAtJsonPath(result.payload, step.save.jsonPath)
        if (stored === undefined) throw new Error(`响应中不存在 ${step.save.jsonPath}`)
        context.variables[step.save.variable] = stored
      }
      break
    }
    case 'screenshot': {
      const file = testInfo.outputPath(safeFilename(step.filename || `manual-${Date.now()}.png`))
      await page.screenshot({ path: file, fullPage: step.fullPage ?? true })
      await testInfo.attach(step.name || '手动截图', { path: file, contentType: 'image/png' })
      break
    }
    case 'pause':
      await page.pause()
      break
    case 'custom': {
      const handler = customActions[step.handler]
      if (!handler) throw new Error(`未注册自定义动作：${step.handler}`)
      await handler(page, resolveValue(step.args || {}, context), context, testInfo)
      break
    }
  }
}

export async function runScenario(page: Page, scenario: BusinessScenario, testInfo: TestInfo) {
  testInfo.setTimeout(scenario.timeoutMs || 120_000)
  const context: RuntimeContext = {
    variables: {
      RUN_ID: new Date().toISOString().replace(/\D/g, '').slice(0, 14),
      ...scenario.variables,
    },
  }
  const browserEvents: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      browserEvents.push(`[console:${message.type()}] ${message.text()}`)
    }
  })
  page.on('requestfailed', (request) => {
    browserEvents.push(`[requestfailed] ${request.method()} ${request.url()} · ${request.failure()?.errorText}`)
  })

  for (const [index, step] of scenario.steps.entries()) {
    const title = stepTitle(step, index)
    await test.step(title, async () => {
      if (step.when && !(await conditionMatches(page, step.when))) {
        testInfo.annotations.push({ type: 'skip-step', description: title })
        return
      }

      try {
        await executeStep(page, step, context, testInfo)
      } catch (error) {
        if (!step.continueOnError) throw error
        browserEvents.push(`[continueOnError] ${title} · ${error instanceof Error ? error.message : String(error)}`)
      }

      if (step.capture ?? scenario.captureEachStep) {
        const file = testInfo.outputPath(`${String(index + 1).padStart(2, '0')}-${safeFilename(step.name || step.action)}.png`)
        await page.screenshot({ path: file, fullPage: false })
        await testInfo.attach(title, { path: file, contentType: 'image/png' })
      }
    })
  }

  if (browserEvents.length) {
    await testInfo.attach('浏览器警告与失败请求', {
      body: Buffer.from(browserEvents.join('\n'), 'utf8'),
      contentType: 'text/plain',
    })
  }
}
