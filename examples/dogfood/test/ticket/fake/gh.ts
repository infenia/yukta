#!/usr/bin/env bun
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// A fake `gh` for the offline tests: logs every call to $FAKE_DIR/gh.log, copies comment and PR bodies; the issue body comes from $FAKE_DIR/body,
// the project status from $FAKE_DIR/status.
import { appendFileSync, copyFileSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const a = process.argv.slice(2)
const dir = process.env.FAKE_DIR!
appendFileSync(join(dir, 'gh.log'), a.join(' ') + '\n')
const body = readFileSync(join(dir, 'body'), 'utf8').replace(/\n+$/, '')
const url = 'https://github.com/infenia/yukta/issues/7'
const flag = (name: string): string | undefined => a[a.indexOf(name) + 1]

switch (`${a[0]} ${a[1]}`) {
  case 'issue view':
    if (a.join(' ').includes('-q .url')) console.log(url)
    else
      console.log(
        JSON.stringify({
          number: 7,
          title: 'feat(core): rate limit',
          labels: [{ name: 'core' }],
          body,
          state: 'OPEN',
          projectItems: [{ status: { name: readFileSync(join(dir, 'status'), 'utf8').trim() }, title: 'Yukta Roadmap' }],
          assignees: [{ login: 'me' }],
          url,
        }),
      )
    break
  case 'api user':
    console.log(process.env.FAKE_ME ?? 'me')
    break
  case 'auth token':
    console.log('tok')
    break
  case 'project item-list':
    console.log(JSON.stringify({ items: [{ status: readFileSync(join(dir, 'status'), 'utf8').trim(), content: { number: 7 } }] }))
    break
  case 'project view':
    console.log('{"id":"P1"}')
    break
  case 'project field-list':
    console.log('{"fields":[{"name":"Status","id":"F1","options":[{"name":"Ready","id":"o1"},{"name":"In progress","id":"o2"}]}]}')
    break
  case 'project item-add':
    console.log('{"id":"I1"}')
    break
  case 'project item-edit':
    break
  case 'issue comment': {
    const f = flag('-F')
    if (f) appendFileSync(join(dir, 'comments.md'), readFileSync(f, 'utf8') + '----\n')
    break
  }
  case 'pr create': {
    const f = flag('--body-file')
    if (f) copyFileSync(f, join(dir, 'pr-body.md'))
    console.log('https://github.com/infenia/yukta/pull/99')
    break
  }
  default:
    console.error(`fake gh: unexpected ${a.join(' ')}`)
    process.exit(9)
}
