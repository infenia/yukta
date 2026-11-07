// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The colors a node or group may declare; names, not meanings, so any workflow can use them. */
export const PALETTE = ['teal', 'sky', 'violet', 'magenta', 'stone'] as const;

export type PaletteColor = (typeof PALETTE)[number];

export function isPaletteColor(value: unknown): value is PaletteColor {
  return PALETTE.includes(value as PaletteColor);
}

/** The solid color (a CSS var reference), or undefined for none or an unknown name. */
export function paletteColor(
  color: string | null | undefined
): string | undefined {
  return isPaletteColor(color) ? `var(--pal-${color})` : undefined;
}

/** A soft tint of the color over the page background; `percent` is how much color. */
export function paletteTint(
  color: string | null | undefined,
  percent = 14
): string | undefined {
  return isPaletteColor(color)
    ? `color-mix(in oklab, var(--pal-${color}) ${percent}%, var(--background))`
    : undefined;
}
