import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from '@playwright/test'
import { loadProjectEnv } from '../support/env'
import { runScenario } from '../support/runner'
import type { BusinessScenario } from '../support/types'

loadProjectEnv()

const currentDir = path.dirname(fileURLToPath(import.meta.url))
const scenariosDir = path.resolve(currentDir, '..', 'scenarios')
const scenarioFiles = fs.readdirSync(scenariosDir)
  .filter((file) => file.endsWith('.json') && !file.startsWith('_'))
  .sort()

for (const filename of scenarioFiles) {
  const scenario = JSON.parse(
    fs.readFileSync(path.join(scenariosDir, filename), 'utf8'),
  ) as BusinessScenario

  test.describe(scenario.name, () => {
    test(`${scenario.tags?.join(' ') || '@business'} · ${scenario.description || filename}`, async ({ page }, testInfo) => {
      test.skip(scenario.enabled === false, '场景已在 JSON 中禁用')
      const missing = (scenario.requirements || []).filter((key) => !process.env[key])
      test.skip(missing.length > 0, `缺少环境变量：${missing.join(', ')}`)
      await runScenario(page, scenario, testInfo)
    })
  })
}
