// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The one `dogfood` session holds the workflows of the dogfood and of the ticket pipeline, so no apply (yk check, gradle, tool, ticket run) removes the others'.
import { expect, test } from 'bun:test'
import { join } from 'node:path'
import { read, rm, tmp, ts } from './helpers.ts'

test('renderSession writes the dogfood and the ticket workflows into the one session, __TICKET__ replaced', () => {
  const d = tmp()
  try {
    const out = join(d, 's.json')
    const r = ts('src/session-render.ts', [out], { env: { YUKTA_DEV_HOME: join(d, 'home') } }) // no $DEV_HOME/main: the checkout's own files
    expect(r.code, r.all).toBe(0)
    const s = JSON.parse(read(out))
    for (const w of ['tool', 'check', 'heal', 'ticket', 'attempt_round', 'steps_round', 'step', 'finalize', 'escalate'])
      expect(Object.keys(s.workflows), w).toContain(w)
    expect(JSON.stringify(s)).not.toContain('__TICKET__')
  } finally {
    rm(d)
  }
})
