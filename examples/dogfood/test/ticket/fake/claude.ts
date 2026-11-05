#!/usr/bin/env bun
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// A fake `claude` for the offline tests: the role comes from the prompt; $FAKE (a file) holds the mode:
// happy | blocked | badjson | badonce | scope | high | twostep | parallel | chain | blockonce.
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const KIT = process.env.KIT!
const mode = readFileSync(process.env.FAKE!, 'utf8').trim()
const dir = dirname(process.env.FAKE!)
const prompt = process.argv[3] ?? ''
appendFileSync(join(dir, 'calls'), prompt.slice(0, 40) + '\n')
if (process.env.FAKE_PROMPTS) appendFileSync(join(dir, 'prompts.log'), prompt + '\n=====\n')
const fixture = (p: string): any => JSON.parse(readFileSync(join(KIT, 'fixtures', p), 'utf8'))
const answer = (a: unknown): void =>
  console.log(
    JSON.stringify({
      is_error: false,
      total_cost_usd: Number(process.env.FAKE_COST ?? '0.02'),
      duration_ms: 5,
      usage: { input_tokens: 10, output_tokens: 5, cache_read_input_tokens: 900, cache_creation_input_tokens: 100 },
      structured_output: a,
    }),
  )
const done = (id: string): unknown => ({ step_id: id, status: 'DONE', notes: 'ok' })
const blocked = (id: string, category: string, detail: string): unknown => ({
  step_id: id,
  status: 'BLOCKED',
  blocked: { category, detail },
  notes: '',
})
const once = join(dir, 'once')
const second = (): any => {
  const s = fixture('ticket-spec/valid.json').steps[0]
  return {
    ...s,
    id: 'S2',
    depends_on: mode === 'parallel' ? [] : ['S1'],
    covers: [],
    write_files: ['core/src/main/java/C.java'],
    title: 'Add C',
  }
}

if (prompt.startsWith('Write the TicketSpec')) {
  const spec = fixture('ticket-spec/valid.json')
  spec.tests_mode = 'implementer_writes'
  spec.locked_files = []
  spec.risk = mode === 'high' ? 'high' : 'low'
  spec.steps[0].write_files = ['core/src/main/java/A.java']
  spec.steps[0].done_when.commands = ['yk gradle :core:test --tests com.infenia.ATest']
  spec.acceptance_criteria[0].verification.command = 'yk gradle :core:test --tests com.infenia.ATest'
  spec.ticket_id = '7'
  if (mode === 'happy') spec.scaffold_patch = '' // a ticket without stubs: the real architect answers an empty path
  if (['twostep', 'parallel', 'chain'].includes(mode)) spec.steps.push(second())
  answer(spec)
} else if (prompt.startsWith('Implement this step')) {
  const s2 = prompt.includes('"id": "S2"')
  const writeA = (): void => writeFileSync('core/src/main/java/A.java', 'class A {}\n')
  const writeC = (): void => writeFileSync('core/src/main/java/C.java', 'class C {}\n')
  if (['twostep', 'parallel', 'chain'].includes(mode)) {
    if (s2) {
      if (mode === 'twostep' && !existsSync(once)) {
        writeFileSync(once, '')
        answer(blocked('S2', 'spec_conflict', 'two rules disagree'))
      } else {
        writeC()
        answer(done('S2'))
      }
    } else {
      writeA()
      answer(done('S1'))
    }
  } else if (mode === 'blockonce') {
    if (existsSync(once)) {
      writeA()
      answer(done('S1'))
    } else {
      writeFileSync(once, '')
      answer(blocked('S1', 'spec_conflict', 'two rules disagree'))
    }
  } else if (mode === 'blocked') answer(blocked('S1', 'missing_context', 'no signature'))
  else if (mode === 'badjson') answer({ nope: 1 })
  else if (mode === 'badonce') {
    if (existsSync(once)) {
      writeA()
      answer(done('S1'))
    } else {
      writeFileSync(once, '')
      answer({ nope: 1 })
    }
  } else if (mode === 'scope') {
    writeA()
    writeFileSync('core/src/main/java/Other.java', 'x\n')
    answer(done('S1'))
  } else {
    writeA()
    answer(done('S1'))
  }
} else if (prompt.startsWith('Revise the spec')) {
  const sid = prompt.includes('"step_id": "S2"') ? 'S2' : 'S1'
  const base = fixture('ticket-spec/valid.json')
  const s2 = { ...base.steps[0], id: 'S2', depends_on: ['S1'], covers: [], write_files: ['core/src/main/java/C.java'], title: 'Add C' }
  const step = [...base.steps, s2].find((s: any) => s.id === sid)
  answer({
    reason: 'clarified',
    replace_steps: [{ ...step, instructions: ['clarified'] }],
    replace_test_cases: [],
    remove_test_case_ids: [],
    scaffold_patch_addendum: null,
    new_assumptions: ['clarified'],
  })
} else if (prompt.startsWith('Write the test files')) answer(fixture('test-writer-result/valid.json'))
else if (prompt.startsWith('Review the change')) answer(fixture('review-result/valid.json'))
else {
  console.error(`fake claude: unknown prompt: ${prompt}`)
  process.exit(1)
}
