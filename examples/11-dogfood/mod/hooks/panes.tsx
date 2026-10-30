import type { Check, Plan, Stage } from './plan'

// The look of /yk-plan and /yk-check. Pure drawing: state in, elements out.
// `ui` is the surface's element table ($.ui.resolve(e)); handlers come from the caller.

type Ui = { Box: any; Button: any; Text: any }

const TITLE = ['#2F6BFF', '#5E7FE8', '#9E7A9B', '#E8742E', '#E85D0C']
const SPIN = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏']
const GROUP: Record<string, { color: string; icon: string; label: string }> = {
  gradle: { color: '#E85D0C', icon: '☕', label: 'Gradle' },
  ui: { color: '#5B8BFF', icon: '◈', label: 'UI' },
  go: { color: '#6A7A9B', icon: '❖', label: 'Go CLI' },
  repo: { color: '#9E7A9B', icon: '✦', label: 'Repo' },
  run: { color: '#6A7A9B', icon: '•', label: 'Other' },
}
const OK = '#34d399'
const BAD = '#f87171'
const WARN = '#FF9A57'
const RUN = '#5B8BFF'

const groupOf = (g: string) => GROUP[g] ?? GROUP.run!

const duration = (s: number) => (s >= 60 ? `${Math.floor(s / 60)}m${String(Math.round(s % 60)).padStart(2, '0')}s` : `${Math.round(s)}s`)

const Title = ({ Box, Text }: Ui, label: string, frame: number) => (
  <Box>
    <Text bold color="#E85D0C">▲ </Text>
    {[...label].map((c, i) => (
      <Text bold color={TITLE[(i + Math.floor(frame / 3)) % TITLE.length]}>{c}</Text>
    ))}
  </Box>
)

const bar = (done: number, total: number, width: number, frame: number, isLive: boolean) => {
  const filled = total ? Math.round((done / total) * width) : 0
  const glint = isLive ? Math.floor(frame / 2) % Math.max(1, width) : -1
  return Array.from({ length: width }, (_, i) => (i === glint && i < filled ? '▓' : i < filled ? '█' : '░')).join('')
}

const wrap = (text: string, width: number) => (text.length > width ? `${text.slice(0, Math.max(0, width - 1))}…` : text)

export function PlanPane(ui: Ui, plan: Plan | undefined, width: number, frame: number, onRun: () => void, onRefresh: () => void) {
  const { Box, Button, Text } = ui
  if (!plan) {
    return (
      <Box flexDirection="column" paddingX={1}>
        {Title(ui, 'YK PLAN', frame)}
        <Text dimColor italic>{SPIN[frame % SPIN.length]} planning…</Text>
      </Box>
    )
  }
  const groups = [...new Set(plan.stages.map(s => s.group))]
  const fixes = plan.stages.filter(s => s.fix).length
  return (
    <Box flexDirection="column" paddingX={1}>
      {Title(ui, 'YK PLAN', frame)}
      <Box marginTop={1}>
        <Text color="#5B8BFF" bold>{plan.summary || 'no changes'}</Text>
      </Box>
      <Box>
        <Text color={OK} bold>{plan.stages.length}</Text>
        <Text dimColor> stages  ·  </Text>
        <Text color={WARN} bold>{fixes}</Text>
        <Text dimColor> with a free fix  ·  </Text>
        <Text>{groups.map(g => groupOf(g).label).join(' · ')}</Text>
      </Box>
      {groups.map(g => {
        const info = groupOf(g)
        const rows = plan.stages.filter(s => s.group === g)
        return (
          <Box key={`g-${g}`} marginTop={1} flexDirection="column" borderStyle="round" borderColor={info.color} paddingX={1}>
            <Text bold color={info.color}>{info.icon} {info.label} <Text dimColor>({rows.length})</Text></Text>
            {rows.map((s: Stage, i: number) => (
              <Box key={`s-${g}-${s.name}`}>
                <Text dimColor>{String(i + 1).padStart(2, ' ')} </Text>
                <Text bold color={info.color}>{s.name.padEnd(12)}</Text>
                <Text dimColor>{wrap(s.cmd, Math.max(10, width - 24))}</Text>
                {s.fix && <Text color={WARN}> ✚</Text>}
              </Box>
            ))}
          </Box>
        )
      })}
      {plan.notes.length > 0 && (
        <Box marginTop={1} flexDirection="column">
          {plan.notes.map(n => <Text dimColor italic>› {wrap(n, width)}</Text>)}
        </Box>
      )}
      <Box marginTop={1}>
        <Button key="plan-run" label="▶ Run check" onPress={onRun} />
        <Text>  </Text>
        <Button key="plan-refresh" label="↻ Refresh" onPress={onRefresh} />
        <Text dimColor>   ✚ = fixed automatically before a model is asked</Text>
      </Box>
    </Box>
  )
}

export function CheckPane(ui: Ui, check: Check | undefined, width: number, frame: number, now: number, onStop: () => void, onRerun: () => void) {
  const { Box, Button, Text } = ui
  if (!check) {
    return (
      <Box flexDirection="column" paddingX={1}>
        {Title(ui, 'YK CHECK', frame)}
        <Text dimColor italic>No check yet. Press ▶ Run check in /yk-plan, or run /yk-check.</Text>
      </Box>
    )
  }
  const done = check.stages.filter(s => s.state === 'done' || s.state === 'healed').length
  const total = check.stages.length
  const result = check.result
  const ok = result?.status === 'SUCCESS'
  const wall = result?.wall ? Number(result.wall) : (now - check.startedAt) / 1000
  const accent = !check.isDone ? RUN : ok ? OK : BAD
  const badge = !check.isDone ? `${SPIN[frame % SPIN.length]} RUNNING` : ok ? '✔ PASSED' : '✘ FAILED'
  const icon = (s: Stage) =>
    s.state === 'done' ? <Text color={OK}>✔</Text>
    : s.state === 'healed' ? <Text color={WARN}>✚</Text>
    : s.state === 'failed' ? <Text color={BAD}>✘</Text>
    : s.state === 'running' ? <Text color={RUN} bold>{SPIN[frame % SPIN.length]}</Text>
    : <Text dimColor>○</Text>
  return (
    <Box flexDirection="column" paddingX={1}>
      <Box>
        {Title(ui, 'YK CHECK', frame)}
        <Text>  </Text>
        <Text bold color={accent}>{badge}</Text>
        <Text dimColor>  ⏱ {duration(wall)}</Text>
      </Box>
      <Box marginTop={1}>
        <Text color={accent}>{bar(done, total, Math.max(10, Math.min(40, width - 16)), frame, !check.isDone)}</Text>
        <Text bold>  {done}</Text>
        <Text dimColor>/{total}  {total ? Math.round((done / total) * 100) : 0}%</Text>
      </Box>
      <Box marginTop={1} flexDirection="column" borderStyle="round" borderColor={accent} paddingX={1}>
        {check.stages.map(s => {
          const info = groupOf(s.group)
          return (
            <Box key={`st-${s.name}`}>
              {icon(s)}
              <Text> </Text>
              <Text
                bold={s.state === 'running' || s.state === 'failed'}
                dimColor={s.state === 'pending'}
                color={s.state === 'running' ? RUN : s.state === 'failed' ? BAD : undefined}
              >
                {s.name.padEnd(12)}
              </Text>
              <Text color={info.color} dimColor={s.state === 'pending'}>{info.icon}</Text>
              {s.state === 'healed' && <Text color={WARN}> healed</Text>}
              {s.state === 'running' && <Text color={RUN} italic> running…</Text>}
            </Box>
          )
        })}
      </Box>
      {check.notes.length > 0 && (
        <Box marginTop={1} flexDirection="column">
          {check.notes.slice(-4).map(n => (
            <Text color={n.startsWith('ERROR') ? BAD : n.startsWith('HEAL') ? WARN : undefined} dimColor={!/^(ERROR|HEAL)/.test(n)}>
              {n.startsWith('ERROR') ? '✘ ' : n.startsWith('HEAL') ? '✚ ' : '› '}
              {wrap(n.replace(/^[A-Z]+ /, ''), width - 4)}
            </Text>
          ))}
        </Box>
      )}
      {result && (
        <Box marginTop={1} flexDirection="column" borderStyle="double" borderColor={accent} paddingX={1}>
          <Text bold color={accent}>{ok ? '✔ gate passed' : `✘ failed at ${result.failed_node ?? '-'}`}</Text>
          <Text>
            <Text dimColor>healed </Text>{result.healed && result.healed !== '-' ? result.healed : 'nothing'}
            <Text dimColor>   cost </Text>{result.cost ?? '$0'}
          </Text>
          {!ok && <Text dimColor>full output: yk detail {result.session ?? '<session>'} {result.execution ?? '<execution>'}</Text>}
        </Box>
      )}
      <Box marginTop={1}>
        {!check.isDone && <Button key="check-stop" label="■ Stop" onPress={onStop} />}
        {check.isDone && <Button key="check-rerun" label="↻ Run again" onPress={onRerun} />}
        <Text dimColor>   {check.execution.slice(0, 8)}</Text>
      </Box>
    </Box>
  )
}
