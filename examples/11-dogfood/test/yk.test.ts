// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk check --stage/--skip: filterPlan keeps or drops stages by name, either spelling of - and _; the usage text; the shell-quoting of a command line.
import { expect, test } from 'bun:test'
import { filterPlan, shq, sq } from '../src/lib.ts'
import { usage } from '../src/yk.ts'

const plan = '# note\nstyle\t.\tc1\t\nui_tsc\tui\tc2\t\nreuse\t.\tc3\t'
const stages = (s: string): string => s.split('\n').map(l => l.split('\t')[0]).join(',')

test('--stage keeps only the named stages, --skip drops them', () => {
  expect(stages(filterPlan(plan, 'style,ui-tsc', ''))).toBe('# note,style,ui_tsc')
  expect(stages(filterPlan(plan, '', 'style'))).toBe('# note,ui_tsc,reuse')
  expect(filterPlan(plan, '', '').split('\n')).toHaveLength(4)
})
test('shell quoting', () => {
  expect(shq(':core:test')).toBe(':core:test')
  expect(shq('a b')).toBe('a\\ b')
  expect(shq('')).toBe("''")
  expect(sq("it's")).toBe("'it'\\''s'")
})
test('usage lists the commands', () => {
  const u = usage()
  expect(u).toContain('yk gradle')
  expect(u).toContain('RESULT status=')
  expect(u.split('\n')[0]).toStartWith('yk: develop Yukta with Yukta')
})
