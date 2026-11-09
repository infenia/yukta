// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk new engine: the module's files, the two registrations, and the refusals.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { cmdNew, files, names, scaffold } from '../src/scaffold.ts'
import { put, read, rm, tmp } from './helpers.ts'

let root: string
beforeAll(() => {
  root = tmp()
  put(
    join(root, 'settings.gradle.kts'),
    'include("core")\ninclude("plugins:supervisors:rules")\ninclude("plugins:supervisors:process-engine")\ninclude("plugins:terminals:x")\n',
  )
  put(
    join(root, 'boot/build.gradle.kts'),
    'dependencies {\n    implementation(project(":plugins:supervisors:rules"))\n    implementation(project(":plugins:supervisors:process-engine"))\n    implementation(project(":web"))\n}\n',
  )
})
afterAll(() => rm(root))

test('names: the package, the class and the module follow the name', () => {
  expect(names('my-agent')).toEqual({
    type: 'my-agent',
    module: 'my-agent-engine',
    pkg: 'com.infenia.yukta.supervisor.myagent',
    cls: 'MyAgent',
    dir: 'plugins/supervisors/my-agent-engine',
  })
  for (const bad of ['', 'My', 'a_b', '-a', 'a-', 'a--b', '1a', 'a/b', '../x']) expect(() => names(bad), bad).toThrow()
})

test('the generated files carry the licence header and the engine type', () => {
  const all = files('my-agent')
  expect(Object.keys(all)).toContain('plugins/supervisors/my-agent-engine/build.gradle.kts')
  for (const [path, text] of Object.entries(all)) {
    expect(text.startsWith('// SPDX-License-Identifier: Apache-2.0\n// SPDX-FileCopyrightText: 2026 Infenia Private Limited\n'), path).toBe(
      true,
    )
  }
  const factory = Object.entries(all).find(([p]) => p.endsWith('MyAgentEngineFactory.java'))![1]
  expect(factory).toContain('ENGINE_TYPE = "my-agent"')
  expect(factory).toContain('package com.infenia.yukta.supervisor.myagent;')
})

test('scaffold writes the module and registers it after the last supervisor module, once', () => {
  const out = scaffold(root, 'my-agent')
  expect(out).toContain('settings.gradle.kts')
  expect(
    existsSync(
      join(root, 'plugins/supervisors/my-agent-engine/src/main/java/com/infenia/yukta/supervisor/myagent/MyAgentEngineConfiguration.java'),
    ),
  ).toBe(true)
  expect(read(join(root, 'settings.gradle.kts'))).toBe(
    'include("core")\ninclude("plugins:supervisors:rules")\ninclude("plugins:supervisors:process-engine")\ninclude("plugins:supervisors:my-agent-engine")\ninclude("plugins:terminals:x")\n',
  )
  expect(read(join(root, 'boot/build.gradle.kts'))).toContain(
    '    implementation(project(":plugins:supervisors:my-agent-engine"))\n    implementation(project(":web"))',
  )
  expect(() => scaffold(root, 'my-agent')).toThrow('exists already')
})

test('cmdNew: usage, a bad name and success are told by the exit code', () => {
  const err = console.error
  const log = console.log
  console.error = () => {}
  console.log = () => {}
  try {
    expect(cmdNew([], root)).toBe(2)
    expect(cmdNew(['plugin', 'x'], root)).toBe(2)
    expect(cmdNew(['engine', 'Bad Name'], root)).toBe(1)
    expect(cmdNew(['engine', 'second'], root)).toBe(0)
    expect(cmdNew(['engine', 'second'], root)).toBe(1)
  } finally {
    console.error = err
    console.log = log
  }
})
