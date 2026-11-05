// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// A stand-in for Ollama's System One endpoint (and /api/version, /api/tags; the model "stale" and "missing" answer as Ollama does), for the online
// tests of heal.
//
//   fake-ollama PORT_FILE
//
// Listens on a free port on 127.0.0.1 and writes it to PORT_FILE. POST /v1/systemone answers a choice question: the level named in the state after
// "difficulty:" (for example a failing check whose output says "difficulty: hard"), else "average" at 0.7. A state that says "difficulty: uncertain"
// gets 0.1 for every level (below the confidence floor); one that says "difficulty: boom" gets an HTTP 500.
import { isFile, readFileSync, writeFileSync } from './lib.ts'

export function startFakeOllama(portFile: string): { port: number; stop: () => void } {
  const server = Bun.serve({
    hostname: '127.0.0.1',
    port: 0,
    async fetch(req) {
      const url = new URL(req.url)
      if (req.method === 'GET')
        return Response.json(url.pathname === '/api/version' ? { version: '0.40.1' } : { models: [{ name: 'fake' }] })
      writeFileSync(
        `${portFile}.auth`,
        `${req.headers.get('authorization') ?? ''} keep_alive=${((await req.clone().json()) as { keep_alive?: string }).keep_alive ?? ''}`,
      ) // tests: what the last request carried
      const body = (await req.json()) as { state?: string; model: string; questions: Record<string, { criteria: Record<string, string> }> }
      const state = body.state ?? ''
      const [name, question] = Object.entries(body.questions)[0]!
      const crit = Object.keys(question.criteria)
      let want = 'average'
      if (body.model === 'stale')
        return new Response("'stale' was downloaded in a format that is no longer supported. To re-download, run 'ollama pull stale'", {
          status: 500,
        }) // what Ollama says of a model in the old format
      if (body.model === 'missing') return new Response("model 'missing' not found", { status: 404 })
      const hint = `${portFile}.hint` // tests: answer this label whatever the state says
      if (isFile(hint)) want = readFileSync(hint, 'utf8').trim()
      else if (state.includes('difficulty:'))
        want = state
          .split('difficulty:')[1]!
          .trim()
          .split(/\s+/)[0]!
          .replace(/^[.,;]+|[.,;]+$/g, '')
      if (want === 'boom') return new Response('boom', { status: 500 })
      let probs: Record<string, number> = Object.fromEntries(crit.map((k) => [k, 0.05]))
      if (want === 'uncertain') {
        probs = Object.fromEntries(crit.map((k) => [k, 0.1]))
        want = crit[0]!
      } else if (want in probs) probs[want] = 0.7
      else {
        want = crit[0]!
        probs[want] = 0.7
      } // a question without that label: the first one
      return Response.json({
        model: body.model,
        answers: { [name]: { type: 'choice', choice: want, probabilities: probs } },
        usage: { input_tokens: Math.floor(state.length / 4), output_tokens: 0 },
      })
    },
  })
  writeFileSync(portFile, String(server.port))
  return { port: server.port!, stop: () => void server.stop(true) }
}

if (import.meta.main) {
  startFakeOllama(process.argv[2]!)
}
