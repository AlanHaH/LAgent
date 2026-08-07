import type { Page, TestInfo } from '@playwright/test'
import type { RuntimeContext } from './types'

type CustomAction = (
  page: Page,
  args: Record<string, unknown>,
  context: RuntimeContext,
  testInfo: TestInfo,
) => Promise<void>

/**
 * JSON 步骤无法表达的复杂业务放在这里。
 * 新增函数后，场景中使用：
 * { "action": "custom", "handler": "函数名", "args": { ... } }
 */
export const customActions: Record<string, CustomAction> = {
  async clearBrowserStorage(page) {
    await page.evaluate(() => {
      localStorage.clear()
      sessionStorage.clear()
    })
  },

  async closeVisibleDialogs(page) {
    const closeButtons = page.locator('.el-dialog:visible .el-dialog__headerbtn')
    while (await closeButtons.count()) {
      await closeButtons.first().click()
      await page.waitForTimeout(150)
    }
  },

  async rememberCurrentUrl(page, args, context) {
    const variable = String(args.variable || 'CURRENT_URL')
    context.variables[variable] = page.url()
  },
}
