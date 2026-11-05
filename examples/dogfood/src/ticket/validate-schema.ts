// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// validate-schema NAME FILE: exit 0 when FILE (JSON) is valid against schemas/NAME.schema.json, else 1 and up to 10 error lines.
import Ajv2020 from 'ajv/dist/2020'
import { KIT, join, readdirSync, readFileSync } from './lib.ts'

const dir = join(KIT, 'schemas')

export function validateSchema(name: string, file: string): { code: number; lines: string[] } {
  const names = readdirSync(dir)
    .filter((f) => f.endsWith('.schema.json'))
    .sort()
  if (!names.includes(`${name}.schema.json`)) {
    throw new Error(`validate-schema: no schema '${name}' (have: ${names.map((n) => n.slice(0, -12)).join(', ')})`)
  }
  let data: unknown
  try {
    data = JSON.parse(readFileSync(file, 'utf8'))
  } catch (e) {
    return { code: 1, lines: [`${file}: not readable JSON: ${(e as Error).message}`] }
  }
  return validateDoc(name, data, file, names)
}

/** Validates a parsed document; `label` names it in the error lines. */
export function validateDoc(
  name: string,
  data: unknown,
  label: string,
  names = readdirSync(dir)
    .filter((f) => f.endsWith('.schema.json'))
    .sort(),
): { code: number; lines: string[] } {
  const ajv = new Ajv2020({ allErrors: true, strict: false })
  for (const n of names) ajv.addSchema(JSON.parse(readFileSync(join(dir, n), 'utf8')), n)
  const validate = ajv.getSchema(`${name}.schema.json`) ?? ajv.compile(JSON.parse(readFileSync(join(dir, `${name}.schema.json`), 'utf8')))
  if (validate(data)) return { code: 0, lines: [] }
  const errors = [...(validate.errors ?? [])].sort((a, b) => a.instancePath.localeCompare(b.instancePath))
  return { code: 1, lines: errors.slice(0, 10).map((e) => `${label}: ${e.instancePath || '/'}: ${(e.message ?? '').slice(0, 200)}`) }
}

export function main(argv: string[]): number {
  if (argv.length !== 2) {
    console.error('usage: validate-schema NAME FILE')
    return 1
  }
  try {
    const r = validateSchema(argv[0]!, argv[1]!)
    for (const l of r.lines) console.log(l)
    return r.code
  } catch (e) {
    console.error((e as Error).message)
    return 1
  }
}
