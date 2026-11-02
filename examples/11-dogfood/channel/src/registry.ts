// The registry of running channels: $DIR/<name>.json = {name, port, pid, started}. A reader (scripts/pickup.sh, `yk pickup status`) drops
// the entries whose pid is dead; the channel removes its own entry on exit.
import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM' // exists, not ours
  }
}

export type Registration = { name: string; path: string; remove: () => void }

// A live channel with the same name makes this one `<name>-<pid>`; a stale file (dead pid, unreadable) is taken over.
export function register(dir: string, wanted: string, port: number): Registration {
  mkdirSync(dir, { recursive: true })
  let name = wanted
  let path = join(dir, `${name}.json`)
  try {
    const pid = (JSON.parse(readFileSync(path, 'utf8')) as { pid?: unknown }).pid
    if (typeof pid === 'number' && alive(pid)) {
      name = `${wanted}-${process.pid}`
      path = join(dir, `${name}.json`)
    }
  } catch {
    // no file, or one we cannot read: take the name
  }
  const tmp = `${path}.tmp`
  writeFileSync(tmp, JSON.stringify({ name, port, pid: process.pid, started: Math.floor(Date.now() / 1000) }))
  renameSync(tmp, path)
  return { name, path, remove: () => rmSync(path, { force: true }) }
}
