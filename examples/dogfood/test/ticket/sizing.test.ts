// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The hybrid router's pieces in isolation: the Sizing section (sizing.ts), the models of a route (route.ts modelsFor) and of a step (lib.ts stepModel).
import { afterAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { stepModel } from '../../src/ticket/lib.ts'
import { modelsFor } from '../../src/ticket/route.ts'
import { measure, sizingOf, sizingRoute } from '../../src/ticket/sizing.ts'
import { KIT, json, put, rm, tmp } from './helpers.ts'

const P = json(join(KIT, 'project.json'))
const d = tmp()
afterAll(() => rm(d))

test('sizingOf reads the section only, merges repeated lists and reports bad items', () => {
  const body =
    '## Goal\n- files: not/this.ts\n\n## Sizing\n- files: `ui/a.ts`, ui/b\n- files: ui/a.ts\n- change: delete rewire\n- open_decisions: 2\n- note: ignored\n'
  expect(sizingOf(body)).toEqual({ sizing: { files: ['ui/a.ts', 'ui/b'], change: ['delete', 'rewire'], open_decisions: 2 }, errors: [] })
  expect(sizingOf('## Sizing\n- none given (the router uses the criteria and labels only)\n').sizing, 'the placeholder line').toBeNull()
  const bad = sizingOf('## Sizing\n- files: ../x, ok.ts\n- open_decisions: some\n- change: refactor')
  expect(bad.errors).toHaveLength(3)
  expect(bad.sizing, 'bad items are dropped, the rest is too little to size by').toBeNull()
})

test('measure expands globs and directories against the tree; sizingRoute applies the rules', () => {
  const tree = ['ui/src/a.tsx', 'ui/src/b/c.tsx', 'ui/src/b/d.tsx', 'ui/e2e/tests/x.spec.ts', 'core/A.java', 'docs/x.md']
  const m = measure({ files: ['ui/src/b', 'ui/src/*.tsx', 'ui/new.ts', 'ui/**/*.go'], change: ['delete'], open_decisions: 0 }, tree)
  expect([m.paths, m.new_files, m.unmatched, m.file_count]).toEqual([
    ['ui/src/a.tsx', 'ui/src/b/c.tsx', 'ui/src/b/d.tsx', 'ui/new.ts'],
    ['ui/new.ts'],
    ['ui/**/*.go'],
    4,
  ])
  expect(sizingRoute(m, P)).toMatchObject({ complexity: 'M', risk: 'low' })
  const e2e = measure({ files: ['ui/e2e/tests/x.spec.ts', 'docs/x.md'], change: ['delete'], open_decisions: 0 }, tree)
  expect(sizingRoute(e2e, P), 'ui/e2e is a high-risk path; docs is no module').toMatchObject({ complexity: 'S', risk: 'high' })
  const two = measure({ files: ['ui/src/a.tsx', 'core/A.java'], change: [], open_decisions: 0 }, tree)
  expect(sizingRoute(two, P), 'two ecosystems: M and high, as two module labels would be').toMatchObject({ complexity: 'M', risk: 'high' })
  const sec = measure({ files: ['core/A.java'], change: ['security'], open_decisions: 3 }, null)
  expect(sizingRoute(sec, P)).toMatchObject({ complexity: 'M', risk: 'high' })
  expect(measure({ files: ['ui/src/**'], change: [], open_decisions: 0 }, null).file_count, 'no tree: an entry is one file').toBe(1)
})

test('modelsFor: the table, the architect one effort up for open decisions, then the ticket roles', () => {
  const t = (sizing: any, roles: any = {}): any => ({ sizing, roles })
  const C = 'cases_to_code'
  expect(modelsFor(t(null), P, 'S', 'low', C).architect).toEqual({ model: 'sonnet', effort: 'medium' })
  expect(modelsFor(t({ open_decisions: 1 }), P, 'S', 'low', C).architect).toEqual({ model: 'sonnet', effort: 'high' })
  expect(modelsFor(t({ open_decisions: 1 }), P, 'L', 'low', C).architect, 'high stays high').toEqual({ model: 'opus', effort: 'high' })
  expect(modelsFor(t(null), P, 'L', 'high', C).test_writer).toEqual({ model: 'sonnet', effort: 'medium' })
  const tw = { test_writer: { model: 'opus', effort: 'high' } }
  for (const mode of ['existing', 'implementer_writes'])
    expect(modelsFor(t(null, tw), P, 'L', 'high', mode).test_writer, `${mode}: no tests up front, no test writer call`).toBeNull()
  const own = modelsFor(t({ open_decisions: 1 }, { architect: { model: 'haiku', effort: 'low' } }), P, 'L', 'high', C)
  expect([own.architect, own.reviewer]).toEqual([
    { model: 'haiku', effort: 'low' },
    { model: 'opus', effort: 'medium' },
  ])
})

test('stepModel: the ladder choice, else implementer_tricky for a tricky step, else implementer', () => {
  const run = join(d, 'run')
  const routing = {
    models: { implementer: { model: 'haiku', effort: 'medium' }, implementer_tricky: { model: 'sonnet', effort: 'medium' } },
  }
  put(join(run, 'routing.json'), routing)
  put(join(run, 'spec/spec.json'), {
    steps: [
      { id: 'S1', complexity: 'trivial' },
      { id: 'S2', complexity: 'standard' },
      { id: 'S3', complexity: 'tricky' },
    ],
  })
  expect(['S1', 'S2', 'S3', 'S9'].map((s) => stepModel(run, s).model)).toEqual(['haiku', 'haiku', 'sonnet', 'haiku'])
  const noTricky = { models: { implementer: routing.models.implementer, implementer_tricky: null } }
  expect(stepModel(run, 'S3', noTricky).model, 'no implementer_tricky: implementer').toBe('haiku')
  put(join(run, 'steps/S3/model.json'), { model: 'sonnet', effort: 'high' })
  expect(stepModel(run, 'S3'), 'the ladder moved it').toEqual({ model: 'sonnet', effort: 'high' })
  rm(join(run, 'spec'))
  expect(stepModel(run, 'S2').model, 'no spec yet').toBe('haiku')
})
