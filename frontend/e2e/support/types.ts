export type LocatorTarget = {
  role?: string
  name?: string
  label?: string
  placeholder?: string
  text?: string
  testId?: string
  css?: string
  exact?: boolean
  nth?: number
}

export type Condition = {
  target: LocatorTarget
  state?: 'visible' | 'hidden' | 'enabled' | 'disabled'
  timeoutMs?: number
  negate?: boolean
}

export type StepBase = {
  name?: string
  when?: Condition
  continueOnError?: boolean
  capture?: boolean
}

export type ScenarioStep = StepBase & (
  | { action: 'goto'; path: string; waitUntil?: 'load' | 'domcontentloaded' | 'networkidle' }
  | { action: 'login'; login: string; password: string; mode?: 'ui' | 'api'; destination?: string }
  | { action: 'click' | 'doubleClick' | 'hover' | 'check' | 'uncheck'; target: LocatorTarget }
  | { action: 'fill' | 'type'; target: LocatorTarget; value: string }
  | { action: 'press'; target?: LocatorTarget; key: string }
  | { action: 'select'; target: LocatorTarget; value: string | string[] }
  | { action: 'upload'; target: LocatorTarget; files: string | string[] }
  | { action: 'wait'; milliseconds: number }
  | { action: 'waitFor'; target: LocatorTarget; state?: 'attached' | 'detached' | 'visible' | 'hidden'; timeoutMs?: number }
  | {
      action: 'expect'
      assertion: 'visible' | 'hidden' | 'enabled' | 'disabled' | 'text' | 'containsText' | 'value' | 'count' | 'url'
      target?: LocatorTarget
      value?: string | number
      soft?: boolean
      timeoutMs?: number
    }
  | {
      action: 'api'
      method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
      path: string
      body?: unknown
      expectedStatus?: number
      save?: { variable: string; jsonPath: string }
    }
  | { action: 'screenshot'; filename?: string; fullPage?: boolean }
  | { action: 'pause' }
  | { action: 'custom'; handler: string; args?: Record<string, unknown> }
)

export type BusinessScenario = {
  name: string
  description?: string
  tags?: string[]
  enabled?: boolean
  timeoutMs?: number
  captureEachStep?: boolean
  requirements?: string[]
  variables?: Record<string, string | number | boolean>
  steps: ScenarioStep[]
}

export type RuntimeContext = {
  variables: Record<string, unknown>
}
