// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export function openCommandPalette(): void {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

/** Fired to open the palette from anywhere (the header's search button). */
export const OPEN_EVENT = 'yukta:open-command-palette';
