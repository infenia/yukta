import { expect, test } from 'claude-code/testing'

import { gateMessage, resultStatus } from './gate'

const OUT = [
  'WARN fast: harmless',
  'ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable',
  'OUTPUT tests: 3 tests failed',
  'MORE yk detail dogfood e1',
  'RESULT status=FAILED failed_node=style healed=- execution=e1 session=dogfood wall=3 cost=$0 bytes=10',
].join('\n')

test('resultStatus reads the RESULT line, or nothing', () => {
  expect(resultStatus(OUT)).toBe('FAILED')
  expect(resultStatus('RESULT status=SUCCESS failed_node=-')).toBe('SUCCESS')
  expect(resultStatus('ERROR x: stopped')).toBeUndefined()
})

test('gateMessage names the task, the problems and the RESULT, not the noise', () => {
  const text = gateMessage('Add retry', OUT)
  expect(text).toMatch(/^Task "Add retry" cannot be completed yet: yk check --no-heal failed/)
  expect(text).toContain('ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable')
  expect(text).toContain('OUTPUT tests: 3 tests failed')
  expect(text).toContain('RESULT status=FAILED failed_node=style')
  expect(text).not.toContain('harmless')
  expect(text).toMatch(/mark the task completed\.$/)
})

test('gateMessage keeps the last 12 problem lines', () => {
  const many = Array.from({ length: 30 }, (_, i) => `ERROR s: problem ${i}`).join('\n')
  const text = gateMessage('t', `${many}\nRESULT status=FAILED`)
  expect(text).not.toContain('problem 17')
  expect(text).toContain('problem 18')
  expect(text).toContain('problem 29')
})
