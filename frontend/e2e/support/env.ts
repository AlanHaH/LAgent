import fs from 'node:fs'
import path from 'node:path'

function unquote(value: string) {
  const trimmed = value.trim()
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"'))
    || (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) return trimmed.slice(1, -1)
  return trimmed
}

export function loadProjectEnv() {
  const envPath = path.resolve(process.cwd(), '..', '.env')
  if (!fs.existsSync(envPath)) return

  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const normalized = line.trim()
    if (!normalized || normalized.startsWith('#')) continue
    const equals = normalized.indexOf('=')
    if (equals <= 0) continue
    const key = normalized.slice(0, equals).trim()
    if (process.env[key] !== undefined) continue
    process.env[key] = unquote(normalized.slice(equals + 1))
  }
}
