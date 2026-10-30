import { expect, test } from 'claude-code/testing'

import { applyLine, newCheck, parsePlan } from './plan'

const PLAN = [
  '# 20 changed files since 34c42b3a7',
  '# gradle: every stage, unscoped',
  'spotless\t.\t./gradlew spotlessCheck -q --console=plain\t./gradlew spotlessApply',
  'compile\t.\t./gradlew compileJava -q --console=plain\t',
  '# ui: 6 files',
  'ui.tsc\tui\tnode_modules/.bin/tsc --noEmit\t',
  "reuse\t.\t'reuse' 'lint'\tfix-reuse.sh",
].join('\n')

test('the plan is parsed into grouped stages', () => {
  const plan = parsePlan(PLAN)
  expect(plan.summary).toBe('20 changed files since 34c42b3a7')
  expect(plan.stages.map(s => `${s.group}:${s.name}${s.fix ? '+' : ''}`)).toEqual([
    'gradle:spotless+',
    'gradle:compile',
    'ui:ui.tsc',
    'repo:reuse+',
  ])
  expect(plan.stages[0]?.cmd).toBe('./gradlew spotlessCheck')
})

test('a check moves through its stages and ends on the RESULT line', () => {
  const check = newCheck('e1', parsePlan(PLAN), 0)
  expect(check.stages[0]?.state).toBe('running')
  applyLine(check, 'STAGE spotless finished')
  applyLine(check, 'STAGE compile.heal finished')
  expect(check.stages.map(s => s.state)).toEqual(['done', 'healed', 'running', 'pending'])
  applyLine(check, 'ERROR ui_tsc: type error')
  applyLine(check, 'RESULT status=FAILURE failed_node=ui_tsc healed=compile:fmt execution=e1 session=s wall=12 cost=$0.01')
  expect(check.stages.map(s => s.state)).toEqual(['done', 'healed', 'failed', 'pending'])
  expect(check.isDone).toBe(true)
  expect(check.result?.wall).toBe('12')
  expect(check.notes).toHaveLength(1)
})
