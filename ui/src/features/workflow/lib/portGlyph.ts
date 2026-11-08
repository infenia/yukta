// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** How an output port is told apart without reading its name: an icon and a tone. */
export interface PortGlyph {
  /** Material icon name, or null to fall back to the port's first letter. */
  icon: string | null;
  /** Handle colour: `ok` and `bad` follow the success and error tokens. */
  tone: 'ok' | 'bad' | 'neutral';
}

const OK = new Set(['success', 'ok', 'true', 'yes', 'pass', 'passed']);
const BAD = new Set(['failure', 'failed', 'error', 'false', 'no', 'fail']);

export function portGlyph(port: string | null): PortGlyph {
  const name = port?.toLowerCase() ?? 'default';
  if (OK.has(name)) return { icon: 'check', tone: 'ok' };
  if (BAD.has(name)) return { icon: 'close', tone: 'bad' };
  if (name === 'default') return { icon: 'arrow_forward', tone: 'neutral' };
  return { icon: null, tone: 'neutral' };
}
