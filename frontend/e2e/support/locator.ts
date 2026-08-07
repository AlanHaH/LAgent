import type { Locator, Page } from '@playwright/test'
import type { LocatorTarget } from './types'

export function locatorFor(page: Page, target: LocatorTarget): Locator {
  let locator: Locator
  const exact = target.exact ?? false

  if (target.role) {
    locator = page.getByRole(target.role as never, target.name
      ? { name: target.name, exact }
      : undefined)
  } else if (target.label) {
    locator = page.getByLabel(target.label, { exact })
  } else if (target.placeholder) {
    locator = page.getByPlaceholder(target.placeholder, { exact })
  } else if (target.text) {
    locator = page.getByText(target.text, { exact })
  } else if (target.testId) {
    locator = page.getByTestId(target.testId)
  } else if (target.css) {
    locator = page.locator(target.css)
  } else {
    throw new Error('定位目标至少需要 role、label、placeholder、text、testId 或 css 之一')
  }

  return target.nth === undefined ? locator : locator.nth(target.nth)
}
