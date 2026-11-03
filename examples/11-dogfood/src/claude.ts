// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The step of the `claude` workflow: one headless Claude Code session. The message on stdin is {"prompt": "..."}; the answer is claude's JSON
// result on stdout; a non-zero exit is the workflow's failure port. The one launcher of every headless Claude of the dev loop (the fixers and the
// implementer).
//
// Environment (a value of "-" is unset; the workflow cannot pass an empty one):
//   YK_MODEL haiku|sonnet|opus   YK_EFFORT low|medium|high   YK_BUDGET USD   YK_RULES system prompt file   YK_SCHEMA JSON schema of the answer
//   YK_STAGE the failed stage: picks the role file fixer-rules/<kind>.md appended to YK_RULES (none for a stage without one)
//   YK_ROLE architect|test_writer|implementer|reviewer: a ticket-pipeline role, picks fixer-rules/ticket-<role>.md (wins over YK_STAGE)
//   YK_TOOLS readonly: Read, Grep and Glob only   YK_MAX_TURNS turn cap
//   YK_SESSION session id   YK_ADVISOR advisor model (experimental, off when unset)   YK_CLAUDE_BIN a stand-in for `claude` (tests)
//   YK_MCP_URL and YK_MCP_KEY (both): the Yukta server's MCP endpoint, read tools only; the key should be a VIEWER key, so the server refuses a
//   change whatever the prompt says, and the mutating tools are denied here as well. The key reaches claude by environment, never by argument.
//
// Slim on purpose: none of the user's plugins, skills or settings and no MCP server but that one (their tool schemas cost more than a fix), a
// fixed tool list and nothing that varies per run in the system prompt. Not --bare: it would also drop the login. The project hooks still load
// (they keep the gradle guard); YUKTA_HEAL_FIXER=1 lets the session call ./gradlew directly. It never commits or pushes: the workflow does.
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { DEV_HOME, DEV_URL, HERE, fixerKey, isFile, join, readFileSync, rmSync, run, text, writeFileSync } from './lib.ts'

// A headless claude must not think it is the session that started the server.
const UNSET = ['CLAUDECODE', 'CLAUDE_CODE_SESSION_ID', 'CLAUDE_CODE_ENTRYPOINT', 'CLAUDE_CODE_SSE_PORT', 'CLAUDE_CODE_MESSAGING_SOCKET', 'CLAUDE_CODE_MESSAGING_TOKEN',
  'CLAUDE_CODE_CHILD_SESSION', 'CLAUDE_CODE_SESSION_ATTENDED', 'CLAUDE_PID']
// the read tools of the endpoint a fixer may call, and the tools that change a run or the server, denied even if the key allowed them
const MCP_READ = ['get_node_output', 'get_execution_tree', 'get_execution_logs', 'get_execution_result', 'get_workflow_status', 'get_workflow_history', 'get_workflow_details']
const MCP_DENY = ['start_workflow', 'control_workflow', 'control_node', 'set_node_breakpoint', 'signal_node', 'rollback_workflow', 'note_workflow_version', 'set_scheduler_limits', 'create_session']

/** The claude command line for one session (the tests read it back). */
export function claudeArgs(o: { mode: string; prompt: string; rules: string; budget: string; sid?: string; env: NodeJS.ProcessEnv }): string[] {
  const e = o.env
  const mcp: string[] = []
  const allow: string[] = []
  const deny: string[] = []
  if (e.YK_MCP_URL && e.YK_MCP_KEY) {
    mcp.push('--mcp-config', JSON.stringify({ mcpServers: { yukta: { type: 'http', url: e.YK_MCP_URL, headers: { 'X-API-Key': '${YK_MCP_KEY}' } } } }))
    for (const t of MCP_READ) allow.push(`mcp__yukta__${t}`)
    for (const t of MCP_DENY) deny.push(`mcp__yukta__${t}`)
  }
  let tools = 'Read,Edit,Write,Grep,Glob,Bash'
  let allowTools = ['Read', 'Edit', 'Write', 'Grep', 'Glob', 'Bash(./gradlew:*)', 'Bash(make:*)', 'Bash(go test:*)', 'Bash(go vet:*)', 'Bash(node_modules/.bin/*:*)', 'Bash(reuse:*)', 'Bash(actionlint:*)', 'Bash(mint:*)', 'Bash(cd:*)']
  if (e.YK_TOOLS === 'readonly') { tools = 'Read,Grep,Glob'; allowTools = ['Read', 'Grep', 'Glob'] }
  const model = ['--model', o.mode]
  if (e.YK_MAX_TURNS) model.push('--max-turns', e.YK_MAX_TURNS)
  if (e.YK_EFFORT) model.push('--effort', e.YK_EFFORT)
  if (e.YK_ADVISOR) model.push('--advisor', e.YK_ADVISOR)
  const session = o.sid ? ['--session-id', o.sid] : ['--no-session-persistence']
  return ['-p', o.prompt, ...model, ...session, '--setting-sources', 'project', '--strict-mcp-config', ...mcp, '--disable-slash-commands', '--exclude-dynamic-system-prompt-sections',
    '--tools', tools, '--permission-mode', 'auto', '--permission-prompts', 'none', '--allowedTools', ...allowTools, ...allow,
    '--disallowedTools', 'Bash(git commit:*)', 'Bash(git push:*)', ...deny, '--append-system-prompt', readFileSync(o.rules, 'utf8'), '--max-budget-usd', o.budget,
    '--output-format', 'json', '--json-schema', e.YK_SCHEMA ?? (() => { throw new Error('YK_SCHEMA is the answer schema') })()]
}

/** The role file kind of a stage / ticket role (fixer-rules/<kind>.md). */
export function roleKind(stage: string | undefined, role: string | undefined): string {
  let kind = ''
  if (stage === 'style') kind = 'pmd'
  else if (stage === 'spotbugs' || stage === 'compile' || stage === 'coverage') kind = stage
  else if (stage === 'tests') kind = 'junit'
  else if (stage === 'ui_vitest') kind = 'vitest'
  else if (stage?.startsWith('ui_')) kind = 'ui'
  else if (stage?.startsWith('cli_')) kind = 'go'
  if (role) {
    if (!['architect', 'test_writer', 'implementer', 'reviewer'].includes(role)) throw new Error(`unknown YK_ROLE '${role}'`)
    kind = `ticket-${role.replaceAll('_', '-')}`
  }
  return kind
}

export function main(argv: string[], stdin?: string): number {
  const args = [...argv]
  if (args[0] === '--role') {
    if (!args[1]) { console.error('claude: --role needs a role'); return 2 }
    process.env.YK_ROLE = args[1]
    args.splice(0, 2)
  }
  for (const v of ['YK_TOOLS', 'YK_MAX_TURNS', 'YK_MODEL', 'YK_EFFORT', 'YK_BUDGET', 'YK_RULES', 'YK_SCHEMA', 'YK_SESSION', 'YK_CLAUDE_BIN', 'YK_ADVISOR', 'YK_STAGE', 'YK_ROLE']) {
    if (process.env[v] === '-') delete process.env[v]
  }
  let prompt = ''
  try { prompt = JSON.parse(stdin ?? readFileSync(0, 'utf8')).prompt ?? '' } catch { /* no JSON */ }
  if (!prompt) { console.error('claude: no prompt on stdin'); return 2 }
  const env = process.env
  // A fixer does not read the project's CLAUDE.md (about 5k tokens on every attempt, measured): fixer-rules.md has the few rules it needs.
  env.CLAUDE_CODE_DISABLE_CLAUDE_MDS = '1'
  // The aliases move when a new model ships; a fixer's cost and behaviour must not.
  Object.assign(env, { ANTHROPIC_DEFAULT_HAIKU_MODEL: 'claude-haiku-5-5', ANTHROPIC_DEFAULT_SONNET_MODEL: 'claude-sonnet-5-5', ANTHROPIC_DEFAULT_OPUS_MODEL: 'claude-opus-5-5' })
  // Read-only MCP access to the server that runs this workflow (YUKTA_FIXER_MCP=off to disable).
  if ((env.YUKTA_FIXER_MCP ?? 'on') !== 'off') {
    const url = env.YUKTA_SERVER_URL || DEV_URL
    let key = env.YUKTA_FIXER_MCP_KEY ?? ''
    if (!key && url === DEV_URL && isFile(join(DEV_HOME, 'fixer-key')) && text(join(DEV_HOME, 'fixer-key')).length) key = text(join(DEV_HOME, 'fixer-key')).trim() || fixerKey()
    if (key) { env.YK_MCP_URL = `${url.replace(/\/$/, '')}/mcp`; env.YK_MCP_KEY = key }
  }
  if (env.YK_CLAUDE_BIN) env.YUKTA_CLAUDE_BIN = env.YK_CLAUDE_BIN
  let kind: string
  try { kind = roleKind(env.YK_STAGE, env.YK_ROLE) } catch (e) { console.error(`claude: ${(e as Error).message}`); return 2 }
  const base = env.YK_RULES
  if (!base) { console.error('claude: YK_RULES'); return 2 }
  let rules = base
  let tmp = ''
  const roleFile = join(HERE, 'fixer-rules', `${kind}.md`)
  if (kind && isFile(roleFile)) {
    tmp = mkdtempSync(join(tmpdir(), 'claude-rules-'))
    rules = join(tmp, 'rules.md')
    writeFileSync(rules, readFileSync(base, 'utf8') + readFileSync(roleFile, 'utf8'))
  }
  try {
    if (!env.YK_MODEL) { console.error('claude: YK_MODEL'); return 2 }
    if (!env.YK_SCHEMA) { console.error('claude: YK_SCHEMA is the answer schema'); return 2 }
    const childEnv: Record<string, string | undefined> = { ...env, YUKTA_HEAL_FIXER: '1' }
    for (const u of UNSET) childEnv[u] = undefined
    const r = run([env.YUKTA_CLAUDE_BIN || 'claude', ...claudeArgs({ mode: env.YK_MODEL, prompt, rules, budget: env.YK_BUDGET || '1', sid: env.YK_SESSION, env })], { env: childEnv })
    process.stdout.write(r.stdout)
    if (r.code) { console.error(r.stderr.trim().split('\n').slice(-5).join('\n')); return r.code }
    return 0
  } finally { if (tmp) rmSync(tmp, { recursive: true, force: true }) }
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
