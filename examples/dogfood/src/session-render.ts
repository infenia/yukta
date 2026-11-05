// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// bun src/session-render.ts OUT: writes the one `dogfood` session (what every yk command applies) to OUT.
import { renderSession } from './lib.ts'

renderSession(
  process.argv[2] ??
    (() => {
      throw new Error('usage: session-render.ts OUT')
    })(),
)
