// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Shared setup of the offline tests: temp dirs, processes, the fake Ollama (in its own process, as the tests wait with blocking calls) and fake claude.
import { spawnSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const HERE = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export type Out = { code: number; out: string; err: string; all: string }
export const tmp = (): string => mkdtempSync(join(tmpdir(), 'dogfood-test-'))
export const rm = (d: string): void => rmSync(d, { recursive: true, force: true })
export const read = (p: string): string => (existsSync(p) ? readFileSync(p, 'utf8') : '')
export const json = (p: string): any => JSON.parse(readFileSync(p, 'utf8'))
export const put = (p: string, s: string | object, mode?: number): void => {
  mkdirSync(dirname(p), { recursive: true })
  writeFileSync(p, typeof s === 'string' ? s : JSON.stringify(s, null, 2) + '\n')
  if (mode) chmodSync(p, mode)
}

export function sh(
  cmd: string[],
  o: { cwd?: string; env?: Record<string, string | undefined>; input?: string; timeout?: number } = {},
): Out {
  const env: Record<string, string | undefined> = { ...process.env, ...(o.env ?? {}) }
  for (const k of Object.keys(env)) if (env[k] === undefined) delete env[k]
  const r = spawnSync(cmd[0]!, cmd.slice(1), {
    cwd: o.cwd,
    env: env as NodeJS.ProcessEnv,
    input: o.input,
    encoding: 'utf8',
    maxBuffer: 1 << 28,
    timeout: o.timeout,
  })
  return { code: r.status ?? 1, out: r.stdout ?? '', err: r.stderr ?? '', all: (r.stdout ?? '') + (r.stderr ?? '') }
}
export const git = (cwd: string, ...a: string[]): Out => sh(['git', ...a], { cwd })
export const ts = (script: string, args: string[], o: Parameters<typeof sh>[1] = {}): Out =>
  sh([process.execPath, join(HERE, script), ...args], o)
/** Runs a snippet with src/lib.ts imported as `l`, in a fresh process (the library reads its paths from the environment once). */
export const withLib = (code: string, env: Record<string, string | undefined> = {}): Out =>
  sh([process.execPath, '-e', `import * as l from '${HERE}/src/lib.ts'; ${code}`], { env, cwd: HERE })

export function initRepo(dir: string, files: Record<string, string> = {}): void {
  mkdirSync(dir, { recursive: true })
  git(dir, 'init', '-q', '-b', 'main', '.')
  for (const [f, c] of Object.entries(files)) put(join(dir, f), c)
  git(dir, 'add', '-A')
  sh(['git', '-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '-m', 'init', '--allow-empty'], { cwd: dir })
}

/** The fake Ollama in a child process; resolves with the port. */
export async function startOllama(dir: string): Promise<{ port: number; kill: () => void }> {
  const proc = Bun.spawn([process.execPath, join(HERE, 'src/fake-ollama.ts'), join(dir, 'port')], { stdout: 'ignore', stderr: 'ignore' })
  for (let i = 0; i < 80 && !read(join(dir, 'port')); i++) await Bun.sleep(100)
  return { port: Number(read(join(dir, 'port'))), kill: () => proc.kill() }
}

/** An executable TypeScript script (a fake `claude`, `gh`, `yk`). */
export function script(path: string, body: string): string {
  put(path, `#!/usr/bin/env bun\n${body}\n`)
  chmodSync(path, 0o755)
  return path
}
