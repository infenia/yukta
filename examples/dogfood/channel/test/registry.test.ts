import { expect, test } from 'bun:test'
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { register } from '../src/registry.ts'

const dir = () => join(mkdtempSync(join(tmpdir(), 'yukta-registry-')), 'reg')

test('writes {name, port, pid, started} and removes it again', () => {
  const d = dir()
  const reg = register(d, 'one', 4321)
  expect(reg.name).toBe('one')
  expect(JSON.parse(readFileSync(join(d, 'one.json'), 'utf8'))).toMatchObject({ name: 'one', port: 4321, pid: process.pid })
  reg.remove()
  expect(existsSync(join(d, 'one.json'))).toBe(false)
})

test('a live channel with the name makes the next one <name>-<pid>', () => {
  const d = dir()
  register(d, 'one', 1) // this process is alive
  const second = register(d, 'one', 2)
  expect(second.name).toBe(`one-${process.pid}`)
  expect(existsSync(join(d, `one-${process.pid}.json`))).toBe(true)
  expect(JSON.parse(readFileSync(join(d, 'one.json'), 'utf8')).port).toBe(1)
})

test('takes over a stale entry (dead pid) and an unreadable one', () => {
  const d = dir()
  register(d, 'x', 1).remove() // creates the dir
  writeFileSync(join(d, 'two.json'), '{"name":"two","port":1,"pid":999999,"started":1}')
  expect(register(d, 'two', 9).name).toBe('two')
  expect(JSON.parse(readFileSync(join(d, 'two.json'), 'utf8')).pid).toBe(process.pid)
  writeFileSync(join(d, 'three.json'), 'not json')
  expect(register(d, 'three', 9).name).toBe('three')
})
