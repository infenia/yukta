// The quality gate of an agent team: the text a teammate gets back when its task cannot be completed because `yk check --no-heal` failed.

const PROBLEM = /^(ERROR|FAILED|OUTPUT) /

// How a `yk check` ended, from its output: the status of the RESULT line, undefined when there is none (stopped, killed).
export const resultStatus = (out: string) => /^RESULT status=(\S+)/m.exec(out)?.[1]

// The RESULT line and up to 12 problem lines before it, in the words a teammate can act on.
export const gateMessage = (subject: string, out: string) => {
  const lines = out.split('\n')
  const result = lines.filter(l => l.startsWith('RESULT ')).pop() ?? ''
  const problems = lines.filter(l => PROBLEM.test(l)).slice(-12).map(l => l.slice(0, 300))
  return [
    `Task "${subject}" cannot be completed yet: yk check --no-heal failed on the shared checkout (the whole diff, so a failure may be another teammate's file).`,
    ...problems,
    result,
    'Fix what it names (yk detail <session> <execution> shows the full output), run yk check again, then mark the task completed.',
  ]
    .filter(Boolean)
    .join('\n')
}
