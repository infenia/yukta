// Which build, test or lint command of this repo must run through yk instead, and how.
// A port of the pre-bash case of .claude/hooks/yk-hook.sh.

const WRAPPERS = /^([A-Za-z_][A-Za-z0-9_]*=.*|env|exec|nice|time|command|timeout|[0-9]+[smh]?)$/
const NOISE = /^(-q|--quiet|--console=.*|--continue|--offline|--daemon|--no-daemon|--parallel|\d*>.*|<.*)$/
const JS = new Set(['pnpm', 'npm', 'npx', 'pnpx'])

/** The yk command to run instead of `cmd`, or undefined when `cmd` may run as it is. */
export function rewrite(cmd: string): string | undefined {
  for (const segment of cmd.split(/&&|\|\||[;|&()]/)) {
    const words = segment.trim().split(/\s+/).filter(Boolean)
    while (words.length && WRAPPERS.test(words[0] ?? '')) words.shift()
    const name = (words[0] ?? '').split('/').pop() ?? ''
    const args = words.slice(1)
    const all = words.join(' ')
    const isGradle = name === 'gradlew' || name === 'gradle'
    const isGo = name === 'go' && /^(test|vet|build|fmt)$/.test(args[0] ?? '')
    if (!isGradle && !isGo && !JS.has(name)) continue
    // Running the app being developed is not a build or a check, but only for the command that runs it.
    if (/[\s:](bootRun|nativeRun)\s/.test(` ${isGradle ? args.join(' ') : all} `)) return undefined
    if (isGradle) return `yk gradle ${args.filter(a => !NOISE.test(a)).join(' ') || '<tasks>'}`
    return isGo ? `yk tool -C cli ${all}` : `yk tool -C ui ${all}`
  }
  return undefined
}
