// Pure parsing for the /yk-plan and /yk-check panes: `yk plan` output and `yk watch --stages` lines.

export type StageState = 'pending' | 'running' | 'done' | 'healed' | 'failed'
export type Stage = { name: string; dir: string; cmd: string; fix: boolean; group: string; state: StageState }
export type Plan = { summary: string; notes: string[]; stages: Stage[] }
export type Check = {
  execution: string
  stages: Stage[]
  notes: string[]
  result?: Record<string, string>
  startedAt: number
  isDone: boolean
}

const norm = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '_')

const groupOf = (name: string, dir: string, cmd: string) => {
  if (name.includes('.')) return name.split('.')[0] ?? 'repo'
  if (cmd.includes('gradlew')) return 'gradle'
  if (dir === 'cli' || /^\W*go\b/.test(cmd)) return 'go'
  if (dir === 'ui') return 'ui'
  return 'repo'
}

/** `yk plan`: '# ...' comment lines, then `stage TAB dir TAB command TAB free fix`. */
export function parsePlan(text: string): Plan {
  const plan: Plan = { summary: '', notes: [], stages: [] }
  for (const line of text.split('\n')) {
    if (line.startsWith('#')) {
      const note = line.replace(/^#\s*/, '')
      if (!plan.summary && /changed files?/.test(note)) plan.summary = note
      else plan.notes.push(note)
    } else if (line.trim()) {
      const [name = '', dir = '.', cmd = '', fix = ''] = line.split('\t')
      plan.stages.push({
        name,
        dir,
        cmd: cmd.replace(/ -q --console=plain/, '').trim(),
        fix: !!fix.trim(),
        group: groupOf(name, dir, cmd),
        state: 'pending',
      })
    }
  }
  return plan
}

const find = (check: Check, name: string) => check.stages.find(s => norm(s.name) === norm(name))

const advance = (check: Check) => {
  for (const s of check.stages) if (s.state === 'running') s.state = 'pending'
  const next = check.stages.find(s => s.state === 'pending')
  if (next && !check.result) next.state = 'running'
}

/** One line of `yk watch --stages`: STAGE finished, ERROR/WARN/HEAL/SLOW/OUTPUT notes, or the RESULT line. */
export function applyLine(check: Check, line: string): void {
  const stage = /^STAGE (\S+?)(\.heal)? finished$/.exec(line)
  if (stage) {
    const name = stage[1] ?? ''
    const known = find(check, name)
    const s = known ?? { name, dir: '', cmd: '', fix: false, group: 'run', state: 'pending' as StageState }
    if (!known) check.stages.push(s)
    s.state = stage[2] || s.state === 'healed' ? 'healed' : 'done'
    advance(check)
  } else if (line.startsWith('RESULT ')) {
    const result = Object.fromEntries([...line.matchAll(/(\w+)=(\S*)/g)].map(m => [m[1] ?? '', m[2] ?? '']))
    check.result = result
    const failed = result.failed_node
    const bad = failed && failed !== '-' ? find(check, failed.replace(/\.heal$/, '')) : undefined
    const ok = result.status === 'SUCCESS'
    for (const s of check.stages) if (s.state === 'running' || s.state === 'pending') s.state = ok ? 'done' : 'pending'
    if (bad) bad.state = 'failed'
    check.isDone = true
  } else if (/^(ERROR|WARN|HEAL|SLOW|OUTPUT|APPROVAL) /.test(line)) {
    check.notes = [...check.notes, line].slice(-8)
  }
}

export function newCheck(execution: string, plan: Plan, startedAt: number): Check {
  const stages = plan.stages.map(s => ({ ...s, state: 'pending' as StageState }))
  const check: Check = { execution, stages, notes: [], startedAt, isDone: false }
  advance(check)
  return check
}
