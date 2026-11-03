// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The testcases of a directory of JUnit XML reports (TEST-*.xml): enough of the format for the gate and the failure digest.
import { join, readFileSync, readdirSync } from './lib.ts'

export const unxml = (s: string): string =>
  s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n))).replace(/&amp;/g, '&')

const attr = (s: string, n: string): string => unxml(new RegExp(`\\b${n}="([^"]*)"`).exec(s)?.[1] ?? '')

export type Case = { classname: string; name: string; skipped: boolean; bad: { kind: 'failure' | 'error'; type: string; message: string; text: string } | null }

export function junitCases(dir: string): Case[] {
  const out: Case[] = []
  for (const f of readdirSync(dir).filter(n => /^TEST-.*\.xml$/.test(n)).sort()) {
    const xml = readFileSync(join(dir, f), 'utf8')
    for (const tc of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const inner = tc[2] ?? ''
      const failure = /<failure\b([^>]*?)(?:\/>|>([\s\S]*?)<\/failure>)/.exec(inner)
      const error = /<error\b([^>]*?)(?:\/>|>([\s\S]*?)<\/error>)/.exec(inner)
      const b = failure ?? error
      out.push({
        classname: attr(tc[1]!, 'classname'), name: attr(tc[1]!, 'name'), skipped: /<skipped\b/.test(inner),
        bad: b ? { kind: failure ? 'failure' : 'error', type: attr(b[1]!, 'type'), message: attr(b[1]!, 'message'), text: unxml(b[2] ?? '') } : null,
      })
    }
  }
  return out
}
